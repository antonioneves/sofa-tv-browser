package app.sofabrowser.ui

import android.app.Activity
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.sofabrowser.BuildConfig
import app.sofabrowser.R
import app.sofabrowser.data.UserScript
import app.sofabrowser.data.UserScripts
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The install screen of user scripts: what a script is, where it runs, what it asks for and its source, before anything
 * is installed. Cancel has the focus; the user moves to Install on purpose. The same screen shows updates and the
 * source of an installed script.
 */
object UserScriptInstall {

    /** Gets the script at [url] (without cookies) and shows it; [done] runs after an install or update. */
    fun fromUrl(activity: Activity, url: String, done: (() -> Unit)? = null) {
        SofaToast.makeText(activity, R.string.user_script_getting, Toast.LENGTH_SHORT).show()
        fetch(url) { result ->
            if (activity.isFinishing || activity.isDestroyed) return@fetch
            result.onSuccess { show(activity, it, url, done) }
                .onFailure { SofaToast.makeText(activity, activity.getString(R.string.user_script_get_failed, it.message), Toast.LENGTH_LONG).show() }
        }
    }

    /** Looks for a newer version of [s] at its update address, and shows it when there is one. */
    fun checkUpdate(activity: Activity, s: UserScripts.Installed, done: () -> Unit) {
        val url = listOfNotNull(s.script.downloadUrl, s.script.updateUrl?.takeUnless { it.endsWith(".meta.js") }, s.url)
            .firstOrNull { it.startsWith("https://") || BuildConfig.DEBUG && it.startsWith("http://") }
            ?: return run { Ui.dialog(activity).setMessage(R.string.user_script_no_update_address).setPositiveButton(android.R.string.ok, null).show(); Unit }
        SofaToast.makeText(activity, R.string.user_script_getting, Toast.LENGTH_SHORT).show()
        fetch(url) { result ->
            if (activity.isFinishing || activity.isDestroyed) return@fetch
            result.onSuccess { source ->
                val newer = (UserScript.parse(source) as? UserScript.Parsed.Ok)?.script
                if (newer != null && newer.identity == s.script.identity && newer.source == s.script.source) {
                    SofaToast.makeText(activity, activity.getString(R.string.user_script_up_to_date, s.script.name), Toast.LENGTH_LONG).show()
                } else show(activity, source, url, done)
            }.onFailure { SofaToast.makeText(activity, activity.getString(R.string.user_script_get_failed, it.message), Toast.LENGTH_LONG).show() }
        }
    }

    /** The source of an installed script, to read. */
    fun viewSource(activity: Activity, s: UserScripts.Installed) {
        Ui.dialog(activity).setTitle(title(s.script))
            .setView(body(activity, listOf(sitesLine(activity, s.script)), s.script.source))
            .setPositiveButton(R.string.close, null).show()
    }

    private fun show(activity: Activity, source: String, url: String, done: (() -> Unit)?) {
        val script = when (val parsed = UserScript.parse(source)) {
            is UserScript.Parsed.Refused -> {
                Ui.dialog(activity).setTitle(R.string.user_script_refused_title)
                    .setMessage(reason(activity, parsed))
                    .setPositiveButton(android.R.string.ok, null).show()
                return
            }
            is UserScript.Parsed.Ok -> parsed.script
        }
        val store = UserScripts.get(activity)
        val old = store.sameAs(script)
        val lines = mutableListOf<String>()
        script.description.takeIf { it.isNotBlank() }?.let { lines += it.take(MAX_DESCRIPTION) }
        lines += sitesLine(activity, script)
        uses(activity, script)?.let { lines += it }
        if (old != null) {
            lines += activity.getString(R.string.user_script_replaces, old.script.version.ifEmpty { "?" })
            if (old.script.newerAsksMore(script)) lines += activity.getString(R.string.user_script_asks_more)
        }
        lines += activity.getString(R.string.user_script_warning)
        val dialog = Ui.dialog(activity).setTitle(title(script))
            .setView(body(activity, lines, source))
            .setPositiveButton(if (old != null) R.string.update else R.string.user_script_install) { _, _ ->
                runCatching { store.install(script, url) }
                    .onSuccess {
                        SofaToast.makeText(activity, activity.getString(
                            if (old != null) R.string.user_script_updated else R.string.user_script_installed, script.name), Toast.LENGTH_LONG).show()
                        done?.invoke()
                    }
                    .onFailure { SofaToast.makeText(activity, activity.getString(R.string.user_script_get_failed, it.message), Toast.LENGTH_LONG).show() }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
        // Installing takes a step on purpose: OK right away cancels.
        dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)?.requestFocus()
    }

    private fun title(s: UserScript) = if (s.version.isEmpty()) s.name else "${s.name} ${s.version}"

    private fun sitesLine(activity: Activity, s: UserScript): String =
        if (s.allSites) activity.getString(R.string.user_script_all_sites)
        else activity.getString(R.string.user_script_runs_on, s.siteNames().let { names ->
            if (names.size <= MAX_SITES) names.joinToString(", ") else names.take(MAX_SITES).joinToString(", ") + " +" + (names.size - MAX_SITES)
        })

    private fun uses(activity: Activity, s: UserScript): String? {
        val what = s.grants.map {
            when (it.removePrefix("GM_").removePrefix("GM.")) {
                "addStyle" -> R.string.user_script_uses_styles
                "info" -> R.string.user_script_uses_info
                else -> R.string.user_script_uses_values
            }
        }.distinct().map(activity::getString)
        return if (what.isEmpty()) null else activity.getString(R.string.user_script_uses, what.joinToString(", "))
    }

    /** The lines about the script, then its source in a box that scrolls with the D-pad once it has the focus. */
    private fun body(activity: Activity, lines: List<String>, source: String) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        lines.forEachIndexed { i, line ->
            addView(Ui.text(activity, 26, if (line.isAllSites(activity)) Ui.p.text else Ui.p.textMid, bold = line.isAllSites(activity)).apply {
                text = line
                setLineSpacing(0f, 1.1f)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) topMargin = 10.px
            })
        }
        val shown = if (source.length > MAX_SHOWN) source.take(MAX_SHOWN) + "\n…\n\n" + activity.getString(R.string.user_script_source_cut) else source
        val code = TextView(activity).apply {
            text = shown
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Ui.p.textMid)
            setPadding(20.px, 16.px, 20.px, 16.px)
            setTextIsSelectable(false)
        }
        addView(FocusScrollView(activity, 0).apply {
            isFocusable = true
            isVerticalScrollBarEnabled = true
            background = Ui.rounded(if (Ui.p.dark) 0xFF1A1F38.toInt() else 0xFFF1F2F6.toInt(), 16)
            foreground = Ui.focusBorder(16)
            addView(code)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, SOURCE_HEIGHT.px).apply { topMargin = 20.px })
    }

    private fun String.isAllSites(activity: Activity) = this == activity.getString(R.string.user_script_all_sites)

    private fun reason(activity: Activity, r: UserScript.Parsed.Refused): String = when (r.reason) {
        UserScript.Reason.NOT_A_SCRIPT -> activity.getString(R.string.user_script_refused_not_a_script)
        UserScript.Reason.TOO_LARGE -> activity.getString(R.string.user_script_refused_too_large)
        UserScript.Reason.NO_NAME -> activity.getString(R.string.user_script_refused_no_name)
        UserScript.Reason.NO_SITES -> activity.getString(R.string.user_script_refused_no_sites)
        UserScript.Reason.BAD_SITE -> activity.getString(R.string.user_script_refused_bad_site, r.detail)
        UserScript.Reason.GRANT -> activity.getString(R.string.user_script_refused_grant, r.detail)
        UserScript.Reason.REQUIRE -> activity.getString(R.string.user_script_refused_require, r.detail)
        UserScript.Reason.RESOURCE -> activity.getString(R.string.user_script_refused_resource, r.detail.substringAfter(' ').trim())
        UserScript.Reason.CONNECT -> activity.getString(R.string.user_script_refused_connect, r.detail)
        UserScript.Reason.RUN_AT -> activity.getString(R.string.user_script_refused_run_at, r.detail)
    }

    /** A plain GET without cookies, referrer or identifiers, of at most [UserScript.MAX_BYTES]; [done] on the main thread. */
    private fun fetch(url: String, done: (Result<String>) -> Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread({
            val result = runCatching {
                // Debug builds also take http://, so tests can serve a script from the development machine.
                if (!url.startsWith("https://") && !(BuildConfig.DEBUG && url.startsWith("http://"))) throw IOException("only https:// addresses")
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.useCaches = false
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                try {
                    if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
                    val out = ByteArrayOutputStream()
                    conn.inputStream.use { input ->
                        val buf = ByteArray(1 shl 14)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > UserScript.MAX_BYTES) throw IOException("larger than 1 MB")
                        }
                    }
                    out.toString("UTF-8")
                } finally {
                    conn.disconnect()
                }
            }
            main.post { done(result) }
        }, "user-script-fetch").start()
    }

    private const val MAX_DESCRIPTION = 300
    private const val MAX_SITES = 6
    private const val MAX_SHOWN = 60_000
    private const val SOURCE_HEIGHT = 300
}
