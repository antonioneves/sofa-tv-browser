package app.sofabrowser.browser

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.net.URI

/**
 * The page side of saved sign-ins: a script in the top frame of HTTPS pages notices a password being sent and a
 * sign-in form waiting to be filled, and fills one in when Sofa sends a saved sign-in. Who sent a message is
 * WebView's word (its origin), never the page's. Nothing is filled without the user picking it in Sofa's own dialog,
 * and only into the document that asked: each document has its own token, so a page that has since navigated away
 * gets nothing.
 */
object SignInPages {

    /** A sign-in form in the document identified by [token] on [origin]. */
    class Form internal constructor(val origin: String, private val token: String, private val proxy: JavaScriptReplyProxy) {
        @SuppressLint("RequiresFeature") // the listener that made the proxy needs WEB_MESSAGE_LISTENER
        fun fill(user: String, password: String) =
            proxy.postMessage(JSONObject().put("n", token).put("u", user).put("p", password).toString())
    }

    interface Listener {
        /** A password was sent from [origin], with [user] when the form had a user name. */
        fun onSignInSent(origin: String, user: String, password: String)
        /** After [onSignInSent], the sign-in form went away without the page changing address. */
        fun onSignInFormGone(origin: String)
        /** The page shows an empty password field that a saved sign-in could fill. */
        fun onSignInForm(form: Form)
    }

    private class Installed(val script: ScriptHandler, val name: String)

    private val installed = HashMap<WebView, Installed>()

    /** Adds the script and its listener to [web]; [listener] is called on the main thread. False on old WebViews. */
    @SuppressLint("RequiresFeature") // both checked below
    fun install(web: WebView, key: PageKey, listener: Listener): Boolean {
        if (web in installed) return true
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false
        WebViewCompat.addWebMessageListener(web, key.signIns, setOf("*")) { view, message, sourceOrigin, isMainFrame, reply ->
            val origin = origin(sourceOrigin.toString())
            if (!isMainFrame || origin == null) return@addWebMessageListener
            val o = runCatching { JSONObject(message.data ?: return@addWebMessageListener) }.getOrNull() ?: return@addWebMessageListener
            val token = o.optString("n").takeIf { it.length >= 16 } ?: return@addWebMessageListener
            view.post {
                when {
                    o.has("s") -> o.optJSONObject("s")?.let { s ->
                        val password = s.optString("p")
                        if (password.isNotEmpty() && password.length <= MAX_LENGTH) {
                            listener.onSignInSent(origin, s.optString("u").trim().take(MAX_LENGTH), password)
                        }
                    }
                    o.has("g") -> listener.onSignInFormGone(origin)
                    o.has("f") -> listener.onSignInForm(Form(origin, token, reply))
                }
            }
        }
        val script = WebViewCompat.addDocumentStartJavaScript(web, script(key.signIns), setOf("*"))
        installed[web] = Installed(script, key.signIns)
        return true
    }

    /** Takes the script away again: pages loaded from now on get nothing. */
    @SuppressLint("RequiresFeature")
    fun remove(web: WebView) {
        val i = installed.remove(web) ?: return
        i.script.remove()
        WebViewCompat.removeWebMessageListener(web, i.name)
    }

    fun forget(web: WebView) { installed.remove(web) }

    /** "https://host[:port]" for an HTTPS address; null for anything else, where nothing is saved or filled. */
    fun origin(url: String?): String? {
        val u = runCatching { URI(url ?: return null) }.getOrNull() ?: return null
        if (!u.scheme.equals("https", true)) return null
        val host = u.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val port = if (u.port == -1 || u.port == 443) "" else ":${u.port}"
        return "https://$host$port"
    }

    private const val MAX_LENGTH = 1000

    private fun script(name: String) = """
(() => {
  if (window.top !== window || location.protocol !== 'https:') return;
  const out = window[${JSONObject.quote(name)}];
  if (!out) return;
  const a = new Uint8Array(16); crypto.getRandomValues(a);
  const token = Array.from(a, (b) => b.toString(16).padStart(2, '0')).join('');
  const send = (o) => { try { out.postMessage(JSON.stringify(Object.assign({ n: token }, o))); } catch (e) {} };
  const shown = (el) => !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length);
  const passwords = (scope) => Array.from((scope || document).querySelectorAll('input[type=password]')).filter(shown);
  // The user name: a field marked as one, else the last text, email or phone field before the password.
  const userField = (pw) => {
    const scope = pw.form || document;
    const marked = scope.querySelector('input[autocomplete~=username]');
    if (marked && (shown(marked) || marked.value)) return marked;
    const before = Array.from(scope.querySelectorAll('input')).filter((i) =>
      /^(text|email|tel)$/.test(i.type) && shown(i) && (pw.compareDocumentPosition(i) & Node.DOCUMENT_POSITION_PRECEDING));
    return before[before.length - 1] || null;
  };

  let sent = '';
  let sentField = null;
  const capture = (scope) => {
    const filled = passwords(scope && scope.querySelectorAll ? scope : document).filter((p) => p.value);
    if (!filled.length) return;
    // New password and its repeat, as on sign-up and change-password forms: the new one; else the only one.
    const n = filled.length;
    const pw = n >= 2 && filled[n - 1].value === filled[n - 2].value ? filled[n - 2] : filled[0];
    const u = userField(pw);
    const user = u ? u.value.trim() : '';
    if (sent === user + '\n' + pw.value) return;
    sent = user + '\n' + pw.value;
    sentField = pw;
    send({ s: { u: user, p: pw.value } });
    // A page that signs in without loading another one: the form going away is the sign that it worked.
    setTimeout(() => { if (sentField && (!sentField.isConnected || !shown(sentField))) send({ g: 1 }); }, 2500);
  };
  document.addEventListener('submit', (e) => capture(e.target), true);
  document.addEventListener('click', (e) => {
    const b = e.target && e.target.closest ? e.target.closest('button, input[type=submit], [role=button]') : null;
    if (b) capture(b.form || document);
  }, true);
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && e.target && e.target.type === 'password') capture(e.target.form || document);
  }, true);

  // Asks once per document, when an empty password field shows up.
  let asked = false;
  let timer = 0;
  const look = () => {
    timer = 0;
    if (asked || !passwords().some((p) => !p.value)) return;
    asked = true;
    observer.disconnect();
    send({ f: 1 });
  };
  const observer = new MutationObserver(() => { if (!timer) timer = setTimeout(look, 500); });
  const watch = () => {
    look();
    if (!asked) observer.observe(document.documentElement, { childList: true, subtree: true });
    // Sign-in forms show up early; no watching a long-lived page for ever.
    setTimeout(() => observer.disconnect(), 30000);
  };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', watch, { once: true }); else watch();

  const setValue = (el, v) => {
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(el, v);
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  };
  out.addEventListener('message', (e) => {
    let o; try { o = JSON.parse(e.data); } catch (x) { return; }
    if (!o || o.n !== token) return;
    const pw = passwords().find((p) => !p.value) || passwords()[0];
    if (!pw) return;
    const u = userField(pw);
    if (u && o.u) setValue(u, o.u);
    setValue(pw, o.p);
    // Filled, not sent: what Sofa filled isn't offered for saving again.
    sent = (u ? u.value.trim() : '') + '\n' + o.p;
  });
})();
""".trimIndent()
}
