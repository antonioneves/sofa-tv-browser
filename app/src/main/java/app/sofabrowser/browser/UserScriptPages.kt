package app.sofabrowser.browser

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import app.sofabrowser.data.UserScript
import app.sofabrowser.data.UserScripts
import org.json.JSONArray
import org.json.JSONObject

/**
 * The page side of user scripts: each switched-on script is added to every document from its start, in a wrapper that
 * runs it only on http and https pages its patterns match (WebView can only choose by origin), at the time it asks
 * for, with the little help Sofa offers (GM_addStyle, stored values, GM_info). Scripts run in the page's own world, as
 * with `@grant none` elsewhere: WebView has no other.
 */
object UserScriptPages {

    fun supported() = HiddenPages.supported()

    /** Hears values the scripts store: [onSet] gets the script, the name and the value as JSON (null to delete). */
    @SuppressLint("RequiresFeature") // checked
    fun install(web: WebView, key: PageKey, onSet: (id: String, name: String, json: String?) -> Unit) {
        if (!supported()) return
        WebViewCompat.addWebMessageListener(web, key.scripts, setOf("*")) { view, message, sourceOrigin, _, _ ->
            val o = runCatching { JSONObject(message.data ?: return@addWebMessageListener) }.getOrNull() ?: return@addWebMessageListener
            val id = o.optString("id")
            val url = o.optString("u")
            val name = o.optString("k")
            if (id.isEmpty() || name.isEmpty() || originOf(url) != sourceOrigin.toString().trimEnd('/')) return@addWebMessageListener
            val json = if (o.optBoolean("del")) null else o.optString("v").takeIf { o.has("v") } ?: return@addWebMessageListener
            view.post {
                // Only a script that runs on the page that says it is its own may store: a page can't write to a script
                // made for other sites.
                val s = UserScripts.get(view.context).byId(id) ?: return@post
                if (s.script.runsOn(url)) onSet(id, name, json)
            }
        }
    }

    private fun originOf(url: String): String? {
        val u = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase() ?: return null
        val host = u.host?.lowercase() ?: return null
        return "$scheme://$host" + if (u.port != -1) ":${u.port}" else ""
    }

    /** Adds [scripts] with their stored values to the documents [web] loads from now on. */
    @SuppressLint("RequiresFeature") // checked
    fun apply(web: WebView, key: PageKey, scripts: List<Pair<UserScripts.Installed, JSONObject>>): List<ScriptHandler> {
        if (!supported()) return emptyList()
        return scripts.map { (s, values) -> WebViewCompat.addDocumentStartJavaScript(web, wrap(s, values, key.scripts), setOf("*")) }
    }

    fun wrap(s: UserScripts.Installed, values: JSONObject, port: String): String {
        val script = s.script
        fun patterns(list: List<UserScript.Pattern>) = JSONArray().also { a ->
            list.forEach { p -> a.put(JSONArray().put(p.regex).put(p.ignoreCase)) }
        }
        val info = JSONObject()
            .put("script", JSONObject().put("name", script.name).put("namespace", script.namespace)
                .put("version", script.version).put("description", script.description)
                .put("runAt", script.runAt.header).put("noframes", script.noFrames))
            .put("scriptHandler", "Sofa").put("version", app.sofabrowser.BuildConfig.VERSION_NAME)
        val run = when (script.runAt) {
            UserScript.RunAt.START -> "go();"
            UserScript.RunAt.END -> "if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', go, {once: true}); else go();"
            UserScript.RunAt.IDLE -> "const idle = () => setTimeout(go, 0);" +
                "if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', idle, {once: true}); else idle();"
        }
        return """
(() => {
  if (location.protocol !== 'http:' && location.protocol !== 'https:') return;
  ${if (script.noFrames) "if (window.top !== window) return;" else ""}
  const here = () => location.href.split('#')[0];
  const u = here();
  const test = (p) => { try { return new RegExp(p[0], p[1] ? 'i' : '').test(u); } catch (e) { return false; } };
  if (!${patterns(script.includes)}.some(test) || ${patterns(script.excludes)}.some(test)) return;
  const port = window[${JSONObject.quote(port)}];
  const ID = ${JSONObject.quote(s.id)};
  const values = $values;
  const send = (m) => { m.id = ID; m.u = here(); try { port.postMessage(JSON.stringify(m)); } catch (e) {} };
  const copy = (v) => v === undefined ? undefined : JSON.parse(JSON.stringify(v));
  const GM_info = $info;
  const GM_getValue = (k, d) => Object.prototype.hasOwnProperty.call(values, k) ? copy(values[k]) : d;
  const GM_setValue = (k, v) => {
    const json = JSON.stringify(v);
    if (json === undefined) return;
    values[k] = JSON.parse(json);
    send({k: String(k), v: json});
  };
  const GM_deleteValue = (k) => { delete values[k]; send({k: String(k), del: true}); };
  const GM_listValues = () => Object.keys(values);
  const GM_addStyle = (css) => {
    const style = document.createElement('style');
    style.textContent = css;
    (document.head || document.documentElement).appendChild(style);
    return style;
  };
  const GM = {
    info: GM_info,
    getValue: async (k, d) => GM_getValue(k, d),
    setValue: async (k, v) => GM_setValue(k, v),
    deleteValue: async (k) => GM_deleteValue(k),
    listValues: async () => GM_listValues(),
    addStyle: async (css) => GM_addStyle(css),
  };
  const go = () => {
    try {
      (function () {
${script.source}
      }).call(window);
    } catch (e) { console.error('[' + GM_info.script.name + ']', e); }
  };
  $run
})();"""
    }
}
