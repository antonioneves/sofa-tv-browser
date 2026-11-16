package app.sofabrowser.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.sofabrowser.R
import app.sofabrowser.browser.HomeServer.Companion.esc
import app.sofabrowser.browser.HomeServer.Response
import app.sofabrowser.ui.Ui
import org.json.JSONObject

/**
 * A page on the home network for typing a user name and password on the phone, for a sign-in form on the TV. What is
 * sent is scrambled on the phone and opened here (see [SecureText]); [onSignIn] gets it on the main thread, once.
 * The page asks for nothing but the two fields, and works only from the QR code, which carries the key.
 */
class PhoneSignInLink(context: Context, private val site: String, private val onSignIn: (user: String, password: String) -> Unit) {

    private val secure = SecureText()
    private val main = Handler(Looper.getMainLooper())
    private val words = Words(context, site)
    private val server = HomeServer(MAX_BODY, portFor(site)) { r ->
        when {
            r.path.isEmpty() && r.method == "GET" -> Response.html(page())
            r.path == "/send" && r.method == "POST" -> {
                val o = secure.open(String(r.body, Charsets.US_ASCII))?.let { runCatching { JSONObject(it) }.getOrNull() }
                val password = o?.optString("p").orEmpty()
                if (o == null || password.isEmpty() || password.length > MAX_LENGTH) Response.status("400 Bad Request")
                else {
                    val user = o.optString("u").take(MAX_LENGTH)
                    main.post { onSignIn(user, password) }
                    Response.status("204 No Content")
                }
            }
            else -> Response.status("404 Not Found")
        }
    }

    /** The page's address with the key after "#", which the phone's browser keeps to itself; null without a home network. */
    fun start(context: Context): String? = server.start(context)?.let { "$it#${secure.keyHex}" }

    fun stop() = server.stop()

    private class Words(c: Context, site: String) {
        val title = c.getString(R.string.signin_phone_page_title, site)
        val user = c.getString(R.string.signin_phone_user)
        val password = c.getString(R.string.signin_phone_password)
        val send = c.getString(R.string.signin_phone_send)
        val sent = c.getString(R.string.signin_phone_sent)
        val noKey = c.getString(R.string.signin_phone_no_key)
        val failed = c.getString(R.string.bookmarks_send_failed)
    }

    private fun css(c: Int) = "rgba(${c shr 16 and 255}, ${c shr 8 and 255}, ${c and 255}, ${"%.3f".format(java.util.Locale.US, (c ushr 24) / 255f)})"

    private fun page(): String {
        val p = Ui.p
        return """<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(words.title)}</title>
<style>
:root { color-scheme: ${if (p.dark) "dark" else "light"}; }
body { margin: 0; min-height: 100vh; font: 18px/1.4 Roboto, system-ui, sans-serif; color: ${css(p.text)}; background: ${css(p.bgMid)}; }
main { max-width: 560px; margin: 0 auto; padding: 24px 16px; }
h1 { font-size: 24px; font-weight: 500; margin: 0 0 20px; }
input { display: block; box-sizing: border-box; width: 100%; font: inherit; color: inherit; margin: 0 0 12px; padding: 14px 20px; border: 2px solid transparent; border-radius: 999px; background: ${css(p.card)}; outline: none; }
input:focus { border-color: ${css(p.border)}; }
button { display: block; width: 100%; font: inherit; font-weight: 500; padding: 14px 20px; border: 0; border-radius: 999px; background: ${css(p.focus)}; color: ${css(p.onFocus)}; }
#note { margin: 16px 4px 0; color: ${css(p.textMid)}; }
</style></head>
<body><main><h1>${esc(words.title)}</h1>
<form id="f" name="${esc(site)}" autocomplete="on">
<input name="username" id="username" type="text" autocomplete="username" autocapitalize="off" autocorrect="off" placeholder="${esc(words.user)}">
<input name="password" id="password" type="password" autocomplete="current-password" placeholder="${esc(words.password)}">
<button type="submit">${esc(words.send)}</button>
</form>
<p id="note" role="status"></p>
<script>
${SecureText.JS}
var key = location.hash.slice(1), f = document.getElementById('f'), note = document.getElementById('note');
if (!/^[0-9a-f]{64}$/.test(key)) { note.textContent = ${js(words.noKey)}; f.hidden = true; }
f.onsubmit = function (e) {
  e.preventDefault();
  var pw = f.password.value;
  if (!pw) return;
  var x = new XMLHttpRequest();
  x.open('POST', location.pathname + '/send');
  x.onload = function () {
    if (x.status === 204) { f.password.value = ''; note.textContent = ${js(words.sent)}; } else note.textContent = ${js(words.failed)};
  };
  x.onerror = function () { note.textContent = ${js(words.failed)}; };
  x.send(seal(key, JSON.stringify({ u: f.username.value, p: pw })));
};
</script></main></body></html>"""
    }

    /** [s] as a JavaScript string inside the page's script. */
    private fun js(s: String) = JSONObject.quote(s).replace("</", "<\\/")

    companion object {
        /**
         * The same port for the same website, so the phone's password manager, which keeps logins by address, sees one
         * address for each site and can save and suggest its sign-in (as long as the TV keeps its address).
         */
        fun portFor(site: String): Int = 20_000 + (site.lowercase().hashCode() and 0x7fffffff) % 10_000

        private const val MAX_BODY = 16 * 1024
        private const val MAX_LENGTH = 512
    }
}
