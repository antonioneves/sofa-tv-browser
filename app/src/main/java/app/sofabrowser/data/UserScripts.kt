package app.sofabrowser.data

import android.annotation.SuppressLint
import android.content.Context
import app.sofabrowser.Space
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.util.UUID

/**
 * The user scripts the user installed, on this side of the private space: each script's file and its stored values
 * in a folder of their own, and the list of them with each one's switches in preferences. Private browsing keeps the
 * values its scripts store apart, in memory, until [endPrivateSession]. Main thread only.
 */
class UserScripts private constructor(private val context: Context) {

    data class Installed(
        val id: String,
        /** Where it was installed from, for "Check for an update" when the script names no update address. */
        val url: String?,
        val enabled: Boolean,
        /** "Also in private browsing", off unless the user turns it on. */
        val allowPrivate: Boolean,
        val script: UserScript,
    )

    private val dir get() = File(Space.files(context), DIR)
    private val store get() = context.getSharedPreferences(Space.name(STORE), Context.MODE_PRIVATE)
    private var loaded: List<Installed>? = null
    private val values = HashMap<String, JSONObject>()
    private val listeners = LinkedHashSet<() -> Unit>()

    val all: List<Installed> get() = loaded ?: load().also { loaded = it }

    /** Hears every change: scripts installed, switched or removed, and values stored. */
    fun listen(l: () -> Unit) { listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }

    fun byId(id: String) = all.firstOrNull { it.id == id }

    /** The installed script [script] would replace: the same name and namespace. */
    fun sameAs(script: UserScript) = all.firstOrNull { it.script.identity == script.identity }

    /** Installs [script], or replaces the one with its name and namespace, keeping that one's values and switches. */
    fun install(script: UserScript, url: String?): Installed {
        val old = sameAs(script)
        val item = Installed(old?.id ?: UUID.randomUUID().toString(), url ?: old?.url, old?.enabled ?: true,
            old?.allowPrivate ?: false, script)
        dir.mkdirs()
        val tmp = File(dir, item.id + ".js.tmp")
        tmp.writeText(script.source)
        if (!tmp.renameTo(sourceFile(item.id))) { tmp.delete(); throw java.io.IOException("can't save the script") }
        loaded = if (old != null) all.map { if (it.id == old.id) item else it } else all + item
        save()
        return item
    }

    fun setEnabled(id: String, on: Boolean) = edit(id) { it.copy(enabled = on) }
    fun setAllowPrivate(id: String, on: Boolean) = edit(id) { it.copy(allowPrivate = on) }

    /** Deletes the script and everything it stored. */
    fun remove(id: String) {
        sourceFile(id).delete()
        valuesFile(id).delete()
        values.remove(id)
        privateValues.remove(id)
        loaded = all.filter { it.id != id }
        save()
    }

    /** The scripts made for [url], switched on or not, that may run in this kind of browsing. */
    fun forPage(url: String?, private: Boolean): List<Installed> =
        if (url == null) emptyList() else all.filter { (!private || it.allowPrivate) && it.script.runsOn(url) }

    /** What [id] stored, as a JSON object of names and values. */
    fun values(id: String, private: Boolean): JSONObject =
        (if (private) privateValues[id] else null) ?: values.getOrPut(id) { readValues(id) }

    /**
     * Stores [json] (a JSON text) under [name] for [id], or deletes it when [json] is null. False when it isn't JSON or
     * would take the script past [MAX_VALUE_BYTES].
     */
    fun setValue(id: String, private: Boolean, name: String, json: String?): Boolean {
        if (byId(id) == null) return false
        val value = if (json == null) null else runCatching {
            val t = JSONTokener(json)
            t.nextValue().also { if (t.more()) error("trailing") }
        }.getOrNull() ?: if (json != null) return false else null
        // A copy, so a value refused for size leaves the stored ones as they were.
        val next = JSONObject(values(id, private).toString())
        if (value == null) next.remove(name) else next.put(name, value)
        val text = next.toString()
        if (text.length > MAX_VALUE_BYTES) return false
        if (private) privateValues[id] = next
        else {
            values[id] = next
            dir.mkdirs()
            valuesFile(id).writeText(text)
        }
        notifyChanged()
        return true
    }

    private fun readValues(id: String) =
        runCatching { JSONObject(valuesFile(id).readText()) }.getOrNull() ?: JSONObject()

    private fun edit(id: String, change: (Installed) -> Installed) {
        loaded = all.map { if (it.id == id) change(it) else it }
        save()
    }

    private fun load(): List<Installed> {
        val list = runCatching { JSONArray(store.getString(KEY_LIST, "[]")) }.getOrNull() ?: JSONArray()
        return (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val source = runCatching { sourceFile(id).readText() }.getOrNull() ?: return@mapNotNull null
            val script = (UserScript.parse(source) as? UserScript.Parsed.Ok)?.script ?: return@mapNotNull null
            Installed(id, o.optString("url").takeIf { it.isNotEmpty() }, o.optBoolean("on", true), o.optBoolean("private"), script)
        }
    }

    private fun save() {
        val list = JSONArray()
        all.forEach { list.put(JSONObject().put("id", it.id).put("url", it.url ?: "").put("on", it.enabled).put("private", it.allowPrivate)) }
        store.edit().putString(KEY_LIST, list.toString()).apply()
        notifyChanged()
    }

    private fun notifyChanged() = listeners.toList().forEach { it() }

    // Ids are UUIDs Sofa made, never names from a script.
    private fun sourceFile(id: String) = File(dir, "$id.js")
    private fun valuesFile(id: String) = File(dir, "$id.values.json")

    companion object {
        const val MAX_VALUE_BYTES = 1 shl 20
        private const val DIR = "user_scripts"
        private const val STORE = "user_scripts"
        private const val KEY_LIST = "list"

        /** Values stored in private browsing, by script; shared by all private tabs, gone with [endPrivateSession]. */
        private val privateValues = HashMap<String, JSONObject>()

        @SuppressLint("StaticFieldLeak") // the application context
        private var instance: UserScripts? = null
        private var instanceSpace: Boolean? = null

        fun get(context: Context): UserScripts {
            if (instanceSpace != Space.active) { instance = null; instanceSpace = Space.active }
            return instance ?: UserScripts(context.applicationContext).also { instance = it }
        }

        fun endPrivateSession() = privateValues.clear()
    }
}
