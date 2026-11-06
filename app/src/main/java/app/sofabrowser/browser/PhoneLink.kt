package app.sofabrowser.browser

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Typing on the phone: a tiny web page served by Sofa on the home network, at an address with a random key that only
 * the QR code on the TV shows. What is sent from it goes to [onText] (on the main thread). It runs only while the
 * code is on screen, and nothing passes through anywhere but the home network.
 */
class PhoneLink(private val page: Page, private val onText: (String) -> Unit) {

    /** The phone page's words, in the TV's language. */
    class Page(val title: String, val hint: String, val send: String, val sent: String)

    private val key = (1..KEY_LENGTH).map { KEY_CHARS[random.nextInt(KEY_CHARS.length)] }.joinToString("")
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var server: ServerSocket? = null

    /** Starts serving; the address for the phone, or null without a home network. */
    fun start(context: Context): String? {
        val address = lanAddress(context) ?: return null
        val s = try { ServerSocket(0) } catch (_: IOException) { return null }
        server = s
        thread(name = "phone-link", isDaemon = true) {
            while (!s.isClosed) {
                val client = try { s.accept() } catch (_: IOException) { break }
                client.use { runCatching { serve(it) } }
            }
        }
        return "http://${address.hostAddress}:${s.localPort}/$key"
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = TIMEOUT_MS
        val input = socket.getInputStream()
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val (method, path) = lines.first().split(" ").let { if (it.size < 2) return else it[0] to it[1] }
        val out = socket.getOutputStream()
        fun reply(status: String, html: String) {
            val body = html.toByteArray(Charsets.UTF_8)
            out.write(("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
            out.write(body)
            out.flush()
        }
        if (path.substringBefore('?') != "/$key") return reply("404 Not Found", "")
        when (method) {
            "GET" -> reply("200 OK", html(null))
            "POST" -> {
                val length = lines.firstNotNullOfOrNull { line ->
                    line.takeIf { it.startsWith("content-length:", ignoreCase = true) }?.substringAfter(':')?.trim()?.toIntOrNull()
                } ?: 0
                if (length !in 0..MAX_BODY) return reply("413 Content Too Large", "")
                val body = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(body, read, length - read)
                    if (n < 0) break
                    read += n
                }
                val text = formField(String(body, 0, read, Charsets.UTF_8), "t")?.trim().orEmpty()
                if (text.isEmpty()) return reply("200 OK", html(null))
                reply("200 OK", html(page.sent))
                main.post { onText(text) }
            }
            else -> reply("405 Method Not Allowed", "")
        }
    }

    private fun html(done: String?): String {
        val form = if (done != null) "<p>${esc(done)}</p>" else """
<form method="post" accept-charset="utf-8">
<input name="t" type="text" autofocus autocomplete="off" autocapitalize="off" placeholder="${esc(page.hint)}">
<button type="submit">${esc(page.send)}</button>
</form>"""
        return """<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(page.title)}</title>
<style>
:root { color-scheme: dark; }
body { margin: 0; padding: 32px 20px; background: #121212; color: #e6e6e6; font: 18px/1.5 system-ui, sans-serif; }
h1 { font-size: 22px; margin: 0 0 20px; }
input, button { box-sizing: border-box; width: 100%; font: inherit; border-radius: 12px; padding: 14px 16px; }
input { border: 1px solid #444; background: #1e1e1e; color: inherit; margin-bottom: 12px; }
button { border: 0; background: #4f8cff; color: #fff; font-weight: 600; }
</style></head>
<body><h1>${esc(page.title)}</h1>$form</body></html>"""
    }

    companion object {
        private const val KEY_LENGTH = 10
        private const val KEY_CHARS = "abcdefghijkmnpqrstuvwxyz23456789"
        private const val TIMEOUT_MS = 10_000
        private const val MAX_HEAD = 8192
        private const val MAX_BODY = 16384
        private val random = SecureRandom()

        /** The request line and headers, up to the blank line; null when the request is too long or cut off. */
        internal fun readHead(input: InputStream): String? {
            val buf = ByteArrayOutputStream()
            var match = 0
            while (buf.size() < MAX_HEAD) {
                val b = input.read()
                if (b < 0) return null
                buf.write(b)
                match = when {
                    b == '\r'.code && match % 2 == 0 -> match + 1
                    b == '\n'.code && match % 2 == 1 -> match + 1
                    b == '\r'.code -> 1
                    else -> 0
                }
                if (match == 4) return buf.toString("ISO-8859-1").removeSuffix("\r\n\r\n")
            }
            return null
        }

        /** One field of a form sent as application/x-www-form-urlencoded. */
        internal fun formField(body: String, name: String): String? = body.split('&').firstNotNullOfOrNull { pair ->
            val k = pair.substringBefore('=')
            if (k != name) null else runCatching { URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") }.getOrNull()
        }

        internal fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        /** The TV's IPv4 address on the home network (Wi-Fi or Ethernet). */
        private fun lanAddress(context: Context): InetAddress? {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val fromNetwork = runCatching {
                cm?.getLinkProperties(cm.activeNetwork)?.linkAddresses?.map { it.address }
            }.getOrNull().orEmpty()
            val all = fromNetwork + runCatching {
                NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp }.flatMap { it.inetAddresses.toList() }
            }.getOrDefault(emptyList())
            return all.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        }
    }
}
