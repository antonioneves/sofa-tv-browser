package app.sofabrowser.browser

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject

/**
 * The page side of "Hide parts of the page": a script that hides what the user chose for a site, and the picker that
 * lets them choose with the pointer. The picker runs only after the user starts it from the menu; it marks the part
 * under the pointer and, on OK, hides it and hands its selector to Sofa. A page can't start it, and what a page
 * sends while it isn't running is dropped.
 */
object HiddenPages {

    /** Hides the selectors of [sites] (site -> selectors) on their pages and the frames from the same site, from the start. */
    @SuppressLint("RequiresFeature") // the caller checks DOCUMENT_START_SCRIPT through supported()
    fun apply(web: WebView, sites: Map<String, List<String>>): ScriptHandler? {
        if (sites.isEmpty() || !supported()) return null
        val rules = JSONObject().also { o -> sites.forEach { (site, list) -> o.put(site, JSONArray(list)) } }
        return WebViewCompat.addDocumentStartJavaScript(web, """
(() => {
  const rules = $rules;
  const h = location.hostname.toLowerCase();
  const list = [];
  for (const site in rules) if (h === site || h.endsWith('.' + site)) list.push(...rules[site]);
  if (!list.length || !window.CSSStyleSheet) return;
  const sheet = new CSSStyleSheet();
  for (const s of list) { try { sheet.insertRule(s + '{display:none!important}', sheet.cssRules.length); } catch (e) {} }
  document.adoptedStyleSheets = [...document.adoptedStyleSheets, sheet];
})();""", setOf("*"))
    }

    fun supported() = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

    /** Listens for what the picker hides: [onPicked] gets the host of the page and the selector, on the main thread. */
    @SuppressLint("RequiresFeature") // checked
    fun install(web: WebView, key: PageKey, onPicked: (host: String, selector: String) -> Unit) {
        if (!supported()) return
        WebViewCompat.addWebMessageListener(web, key.hide, setOf("*")) { view, message, sourceOrigin, isMainFrame, _ ->
            if (!isMainFrame) return@addWebMessageListener
            val host = runCatching { Uri.parse(sourceOrigin.toString()).host }.getOrNull()?.lowercase() ?: return@addWebMessageListener
            val o = runCatching { JSONObject(message.data ?: return@addWebMessageListener) }.getOrNull() ?: return@addWebMessageListener
            val selector = o.optString("s").takeIf { it.isNotEmpty() } ?: return@addWebMessageListener
            view.post { onPicked(host, selector) }
        }
    }

    /** Starts the picker in the page in view. */
    fun pick(web: WebView, key: PageKey) = web.evaluateJavascript(picker(key.hide), null)

    /** Ends the picker, as it is in the page now (a new page has none). */
    fun stop(web: WebView, key: PageKey) =
        web.evaluateJavascript("window.dispatchEvent(new Event(${JSONObject.quote(key.hide)}))", null)

    private fun picker(name: String) = """
(() => {
  const NAME = ${JSONObject.quote(name)};
  const out = window[NAME];
  if (!out || window.top !== window) return;
  window.dispatchEvent(new Event(NAME));
  const stable = (s) => s.length <= 30 && !/\d{3,}/.test(s) && !/^[a-zA-Z]{0,2}[-_]?[0-9a-f]{6,}${'$'}/.test(s);
  const unique = (s) => { try { return document.querySelectorAll(s).length === 1; } catch (e) { return false; } };
  const part = (e) => {
    if (e.id && stable(e.id)) return '#' + CSS.escape(e.id);
    let s = e.localName + Array.from(e.classList).filter(stable).slice(0, 3).map((c) => '.' + CSS.escape(c)).join('');
    const p = e.parentElement;
    if (p) {
      const same = Array.from(p.children).filter((x) => x.localName === e.localName);
      if (same.length > 1 && !unique(s)) s += ':nth-of-type(' + (same.indexOf(e) + 1) + ')';
    }
    return s;
  };
  const selectorOf = (el) => {
    const path = [];
    for (let e = el; e && e !== document.body && e !== document.documentElement; e = e.parentElement) {
      path.unshift(part(e));
      const s = path.join(' > ');
      if (unique(s)) return s;
    }
    const s = 'body > ' + path.join(' > ');
    return unique(s) ? s : null;
  };
  let last = null, prev = ['', ''];
  const unmark = () => { if (last) { last.style.outline = prev[0]; last.style.outlineOffset = prev[1]; last = null; } };
  const mark = (el) => {
    if (el === last || el === document.body || el === document.documentElement) return;
    unmark();
    last = el; prev = [el.style.outline, el.style.outlineOffset];
    el.style.setProperty('outline', '4px solid #ff8a3d', 'important');
    el.style.setProperty('outline-offset', '-4px', 'important');
  };
  const over = (e) => mark(e.target);
  const quiet = (e) => e.stopImmediatePropagation();
  const click = (e) => {
    e.preventDefault(); e.stopImmediatePropagation();
    const el = e.target;
    if (!el || el === document.body || el === document.documentElement) return;
    unmark();
    const s = selectorOf(el);
    if (!s) return;
    el.style.setProperty('display', 'none', 'important');
    out.postMessage(JSON.stringify({ s }));
  };
  const quietEvents = ['mousedown', 'mouseup', 'pointerdown', 'pointerup', 'touchstart', 'touchend', 'dblclick', 'contextmenu', 'submit'];
  const stop = () => {
    unmark();
    window.removeEventListener(NAME, stop);
    for (const t of ['mouseover', 'pointerover', 'mousemove']) window.removeEventListener(t, over, true);
    for (const t of quietEvents) window.removeEventListener(t, quiet, true);
    window.removeEventListener('click', click, true);
  };
  window.addEventListener(NAME, stop);
  for (const t of ['mouseover', 'pointerover', 'mousemove']) window.addEventListener(t, over, true);
  for (const t of quietEvents) window.addEventListener(t, quiet, true);
  window.addEventListener('click', click, true);
})();"""
}
