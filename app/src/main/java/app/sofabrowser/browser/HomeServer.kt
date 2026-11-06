package app.sofabrowser.browser

import android.content.Context
import android.net.ConnectivityManager
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
 * A tiny web server on the home network, at an address with a random key that only the TV shows. It answers only
 * under that key, one request at a time, and [handle] runs on its thread. Pages such as typing on the phone run it
 * only while their code is on screen.
 */
class HomeServer(private val maxBody: Int, private val handle: (Request) -> Response) {

    /** [path] is what follows the key: "" for the page itself, or "/name". */
    class Request(val method: String, val path: String, val body: ByteArray)

    class Response(val status: String, val type: String, val body: ByteArray, val headers: List<String> = emptyList()) {
        companion object {
            fun html(html: String, status: String = "200 OK") =
                Response(status, "text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8))
            fun text(text: String, status: String = "200 OK") =
                Response(status, "text/plain; charset=utf-8", text.toByteArray(Charsets.UTF_8))
            fun status(status: String) = Response(status, "text/plain; charset=utf-8", ByteArray(0))
        }
    }

    private val key = (1..KEY_LENGTH).map { KEY_CHARS[random.nextInt(KEY_CHARS.length)] }.joinToString("")
    @Volatile private var server: ServerSocket? = null

    /** Starts serving; the address of the page, or null without a home network. */
    fun start(context: Context): String? {
        val address = lanAddress(context) ?: return null
        val s = try { ServerSocket(0) } catch (_: IOException) { return null }
        server = s
        thread(name = "home-server", isDaemon = true) {
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
        val (method, target) = lines.first().split(" ").let { if (it.size < 2) return else it[0] to it[1] }
        val out = socket.getOutputStream()
        fun reply(r: Response) {
            val extra = r.headers.joinToString("") { "$it\r\n" }
            out.write(("HTTP/1.1 ${r.status}\r\nContent-Type: ${r.type}\r\nContent-Length: ${r.body.size}\r\n$extra" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
            out.write(r.body)
            out.flush()
        }
        val path = pathUnder(target, key) ?: return reply(Response.status("404 Not Found"))
        val length = lines.firstNotNullOfOrNull { line ->
            line.takeIf { it.startsWith("content-length:", ignoreCase = true) }?.substringAfter(':')?.trim()?.toIntOrNull()
        } ?: 0
        if (length !in 0..maxBody) return reply(Response.status("413 Content Too Large"))
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        reply(handle(Request(method, path, if (read == length) body else body.copyOf(read))))
    }

    companion object {
        private const val KEY_LENGTH = 10
        private const val KEY_CHARS = "abcdefghijkmnpqrstuvwxyz23456789"
        private const val TIMEOUT_MS = 10_000
        private const val MAX_HEAD = 8192
        private val random = SecureRandom()

        /** What follows "/[key]" in a request target, without the query; null for any other address. */
        internal fun pathUnder(target: String, key: String): String? {
            val path = target.substringBefore('?')
            val rest = path.removePrefix("/$key").takeIf { it.length < path.length } ?: return null
            return rest.takeIf { it.isEmpty() || it.startsWith("/") }
        }

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

        /** The style the pages share: dark, large and easy to tap. */
        internal const val STYLE = """
:root { color-scheme: dark; }
body { margin: 0; padding: 32px 20px; background: #121212; color: #e6e6e6; font: 18px/1.5 system-ui, sans-serif; }
main { max-width: 560px; margin: 0 auto; }
h1 { font-size: 22px; margin: 0 0 20px; }
h2 { font-size: 18px; margin: 32px 0 4px; }
p { margin: 0 0 12px; color: #b0b0b0; }
input, button, .button { display: block; box-sizing: border-box; width: 100%; font: inherit; border-radius: 12px; padding: 14px 16px; text-align: center; text-decoration: none; }
input { border: 1px solid #444; background: #1e1e1e; color: inherit; margin-bottom: 12px; text-align: start; }
button, .button { border: 0; background: #4f8cff; color: #fff; font-weight: 600; cursor: pointer; }
"""

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
