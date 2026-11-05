package app.sofabrowser.shortcut

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.widget.Toast
import app.sofabrowser.R
import app.sofabrowser.data.Favicons
import com.android.apksig.ApkSigner
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.security.auth.x500.X500Principal
import kotlin.concurrent.thread

/**
 * "Add to TV home screen": Android TV launchers list installed apps only, so a page gets its own tile by installing a
 * tiny app (the :shortcut template) named and pictured for the page. It opens the page in Sofa, as the page alone.
 * The copy is made and signed here, on the TV; nothing is fetched or sent anywhere.
 */
object TvShortcut {
    private const val TEMPLATE = "shortcut-template.apk"
    private const val TEMPLATE_PACKAGE = "app.sofabrowser.shortcut"
    private const val KEY_ALIAS = "sofa-tv-shortcuts"
    private const val BANNER_W = 640
    private const val BANNER_H = 360

    /** What the page says about its own look: its theme colour and the address of its largest icon. */
    class Look(val color: Int?, val iconUrl: String?, val imageUrl: String? = null)

    const val LOOK_SCRIPT = """(function(){var m=document.querySelector('meta[name=theme-color]'),b=null,n=-1;
document.querySelectorAll('link[rel~=icon],link[rel^=apple-touch-icon]').forEach(function(l){
var s=parseInt(((l.sizes&&l.sizes[0])||'0').split('x')[0])||0;if(/apple/.test(l.rel))s=Math.max(s,180);
if(l.href&&!/\.svg/i.test(l.href)&&s>=n){b=l.href;n=s}});
var o=document.querySelector('meta[property="og:image"],meta[name="twitter:image"]');
return JSON.stringify({c:m?m.content:'',i:b||'',g:o?new URL(o.content,location.href).href:''})})()"""

    fun look(raw: String?): Look = runCatching {
        val text = org.json.JSONTokener(raw).nextValue() as String
        val o = org.json.JSONObject(text)
        Look(runCatching { Color.parseColor(o.optString("c").trim()) }.getOrNull(), o.optString("i").ifBlank { null }, o.optString("g").ifBlank { null })
    }.getOrDefault(Look(null, null))

    /** The screen the button was pressed on, to move aside once Android's confirmation is up. */
    var source = java.lang.ref.WeakReference<android.app.Activity>(null)
        private set

    fun add(context: Context, url: String, title: String, look: Look = Look(null, null)) {
        source = java.lang.ref.WeakReference(context as? android.app.Activity)
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            toast(app, R.string.shortcut_allow_installs)
            runCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
        toast(app, R.string.shortcut_building)
        thread(name = "tv-shortcut") {
            runCatching { install(app, build(app, url, title, look)) }
                .onFailure { toast(app, app.getString(R.string.shortcut_failed, it.message ?: it.javaClass.simpleName)) }
        }
    }

    /** One tile per address: the same page again replaces its tile instead of adding another. */
    private fun packageFor(url: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
        return "app.sofabrowser.site.s$hash"
    }

    private fun build(context: Context, url: String, title: String, look: Look): File {
        val label = title.trim().ifBlank { Uri.parse(url).host.orEmpty() }.take(40)
        val manifestTexts = mapOf(
            TEMPLATE_PACKAGE to packageFor(url),
            "SHORTCUT_LABEL" to label,
            "SHORTCUT_URL" to url,
            "SHORTCUT_TARGET" to context.packageName,
        )
        val dir = File(context.cacheDir, "shortcuts").apply { deleteRecursively(); mkdirs() }
        val unsigned = File(dir, "unsigned.apk")
        context.assets.open(TEMPLATE).use { template ->
            writeApk(template, unsigned, { ManifestPatch.replace(it, manifestTexts) }, banner(context, url, label, look))
        }
        val signed = File(dir, "tile.apk")
        sign(unsigned, signed)
        unsigned.delete()
        return signed
    }

    // The APK

    /**
     * Copies the template, with the manifest patched and the banner picture swapped in. Stored entries (the resource
     * table among them) are lined up on 4 bytes, which Android requires of them.
     */
    private fun writeApk(template: java.io.InputStream, to: File, manifest: (ByteArray) -> ByteArray, banner: ByteArray) {
        val counted = Counting(to.outputStream().buffered())
        ZipOutputStream(counted).use { zip ->
            ZipInputStream(template).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    if (entry.isDirectory || entry.name.startsWith("META-INF/")) continue
                    val bytes = zin.readBytes()
                    val data = when {
                        entry.name == "AndroidManifest.xml" -> manifest(bytes)
                        entry.name.startsWith("res/") && entry.name.endsWith(".png") -> banner
                        else -> bytes
                    }
                    val out = ZipEntry(entry.name)
                    if (entry.method == ZipEntry.STORED) {
                        out.method = ZipEntry.STORED
                        out.size = data.size.toLong()
                        out.compressedSize = data.size.toLong()
                        out.crc = CRC32().apply { update(data) }.value
                        val pad = ((4 - (counted.count + 30 + entry.name.toByteArray().size) % 4) % 4).toInt()
                        if (pad > 0) out.extra = ByteArray(pad + 4).also { it[0] = 0x35; it[1] = 0xD9.toByte(); it[2] = pad.toByte() }
                    } else out.method = ZipEntry.DEFLATED
                    zip.putNextEntry(out)
                    zip.write(data)
                    zip.closeEntry()
                }
            }
        }
    }

    private class Counting(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    }

    /** Signed with a key made once and kept in the TV's key store, so a page's tile can be replaced later. */
    private fun sign(input: File, output: File) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(KEY_ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    .setCertificateSubject(X500Principal("CN=Sofa TV Browser shortcuts"))
                    .setCertificateNotAfter(Date(4_102_444_800_000L))
                    .build())
            }.generateKeyPair()
        }
        val key = store.getKey(KEY_ALIAS, null) as PrivateKey
        val cert = store.getCertificate(KEY_ALIAS) as X509Certificate
        val signer = ApkSigner.SignerConfig.Builder("sofa", key, listOf(cert)).build()
        ApkSigner.Builder(listOf(signer))
            .setInputApk(input)
            .setOutputApk(output)
            .setMinSdkVersion(24)
            .setV1SigningEnabled(false)
            .setV2SigningEnabled(true)
            .build()
            .sign()
    }

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setSize(apk.length()) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("tile.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            // The installer adds the status to this intent, so it has to be mutable.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, Intent(context, ShortcutInstalled::class.java), flags).intentSender)
        }
        apk.delete()
    }

    // The banner: the page's own icon and name on its theme colour (or one taken from its host).

    /** The page's icon: one request to the page's own site, without cookies; nothing else is fetched. */
    private fun download(iconUrl: String?, minSize: Int = 96): Bitmap? = runCatching {
        if (iconUrl == null || !iconUrl.startsWith("https://")) return null
        val conn = (java.net.URL(iconUrl).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 5000; readTimeout = 5000
        }
        val bytes = conn.inputStream.use { it.readBytes() }.takeIf { it.size <= 2_000_000 }
        bytes?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
    }.getOrNull()?.takeIf { it.width >= minSize && it.height >= minSize }

    private fun banner(context: Context, url: String, label: String, look: Look): ByteArray {
        val bmp = Bitmap.createBitmap(BANNER_W, BANNER_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val host = Uri.parse(url).host.orEmpty()
        val hue = ((host.hashCode() and 0x7fffffff) % 360).toFloat()
        val bg = look.color?.let { Color.rgb(Color.red(it), Color.green(it), Color.blue(it)) }
            ?: Color.HSVToColor(floatArrayOf(hue, 0.5f, 0.34f))
        val light = (0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)) > 150
        val ink = if (light) Color.rgb(0x1b, 0x1b, 0x1f) else Color.WHITE
        c.drawColor(bg)
        // A soft light from the corner, so the colour isn't flat.
        c.drawRect(0f, 0f, BANNER_W.toFloat(), BANNER_H.toFloat(), Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, BANNER_W.toFloat(), BANNER_H.toFloat(),
                Color.argb(if (light) 40 else 56, 255, 255, 255), Color.argb(if (light) 24 else 72, 0, 0, 0), android.graphics.Shader.TileMode.CLAMP)
        })

        // A wide preview picture of the page fills the tile, under a dark veil that keeps the name readable.
        val hero = download(look.imageUrl, 400)?.takeIf { it.width >= it.height * 1.3f }
        if (hero != null) {
            val scale = maxOf(BANNER_W / hero.width.toFloat(), BANNER_H / hero.height.toFloat())
            val src = android.graphics.Rect(0, 0, (BANNER_W / scale).toInt().coerceAtMost(hero.width), (BANNER_H / scale).toInt().coerceAtMost(hero.height))
            src.offset((hero.width - src.width()) / 2, (hero.height - src.height()) / 2)
            c.drawBitmap(hero, src, RectF(0f, 0f, BANNER_W.toFloat(), BANNER_H.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
            c.drawRect(0f, 0f, BANNER_W.toFloat(), BANNER_H.toFloat(), Paint().apply {
                shader = android.graphics.LinearGradient(0f, BANNER_H * 0.2f, 0f, BANNER_H.toFloat(),
                    Color.argb(60, 0, 0, 0), Color.argb(245, 0, 0, 0), android.graphics.Shader.TileMode.CLAMP)
            })
            val small = 84
            val box = RectF(32f, BANNER_H - 32f - small, 32f + small, BANNER_H - 32f)
            val logo = download(look.iconUrl)
            if (logo != null) {
                // On the page's colour, so a logo with a see-through background still shows.
                c.drawRoundRect(box, 20f, 20f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg })
                c.save(); c.clipPath(android.graphics.Path().apply { addRoundRect(box, 20f, 20f, android.graphics.Path.Direction.CW) })
                c.drawBitmap(logo, null, box, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                c.restore()
            } else {
                c.drawRoundRect(box, 20f, 20f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg })
                Favicons.get(context).drawable(url, label, small).apply { setBounds(box.left.toInt() + 12, box.top.toInt() + 12, box.right.toInt() - 12, box.bottom.toInt() - 12) }.draw(c)
            }
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = 44f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setShadowLayer(6f, 0f, 2f, Color.argb(160, 0, 0, 0))
            }
            val x = box.right + 24
            val layout = StaticLayout.Builder.obtain(label, 0, label.length, paint, (BANNER_W - x - 32).toInt())
                .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).build()
            c.save(); c.translate(x, box.centerY() - layout.height / 2f); layout.draw(c); c.restore()
            return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }

        val icon = 200
        val left = 52f
        val top = (BANNER_H - icon) / 2f
        val box = RectF(left, top, left + icon, top + icon)
        val big = download(look.iconUrl)
        if (big != null) {
            val clip = android.graphics.Path().apply { addRoundRect(box, 44f, 44f, android.graphics.Path.Direction.CW) }
            c.drawRoundRect(box.apply { offset(0f, 6f) }, 44f, 44f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0, 0, 0) })
            box.offset(0f, -6f)
            c.save(); c.clipPath(clip)
            c.drawBitmap(big, null, box, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            c.restore()
        } else {
            c.drawRoundRect(box, 44f, 44f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            val d = Favicons.get(context).drawable(url, label, icon)
            val pad = 28
            d.setBounds((left + pad).toInt(), (top + pad).toInt(), (left + icon - pad).toInt(), (top + icon - pad).toInt())
            d.draw(c)
        }

        val textLeft = left + icon + 36
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = 52f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val layout = StaticLayout.Builder.obtain(label, 0, label.length, paint, (BANNER_W - textLeft - 36).toInt())
            .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        c.save()
        c.translate(textLeft, (BANNER_H - layout.height) / 2f)
        layout.draw(c)
        c.restore()
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun toast(context: Context, res: Int) = toast(context, context.getString(res))
    private fun toast(context: Context, text: String) =
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
}
