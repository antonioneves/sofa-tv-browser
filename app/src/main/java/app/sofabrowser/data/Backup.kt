package app.sofabrowser.data

import android.content.Context
import app.sofabrowser.Space
import app.sofabrowser.adblock.Allowlist
import app.sofabrowser.savedSearchShortcuts
import app.sofabrowser.siteUserAgents
import app.sofabrowser.siteZooms
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the user set up, in one JSON file: settings, bookmarks, history, quick-access tiles, search shortcuts,
 * the choices made for single sites and the ad blocking allowlist. What the file may carry is up to [BackupFile];
 * restoring adds to what is here (bookmarks, history, tiles) or replaces the same setting, and never deletes.
 */
object Backup {

    /** The backup file's text. */
    fun write(context: Context): String {
        val db = Db.get(context)
        val settings = JSONObject()
        val p = context.getSharedPreferences(Space.name("settings"), Context.MODE_PRIVATE)
        for ((k, v) in p.all) {
            if (k !in BackupFile.settingKeys) continue
            settings.put(k, if (v is Set<*>) JSONArray(v.toList()) else v)
        }
        fun pages(items: List<Pair<String, String>>) = JSONArray(items.map { JSONObject().put("title", it.first).put("url", it.second) })
        return JSONObject()
            .put("sofa_backup", BackupFile.VERSION)
            .put("settings", settings)
            .put("tiles", pages(db.tiles().map { it.title to it.url }))
            .put("bookmarks", JSONArray(db.bookmarkEntries().map {
                JSONObject().put("title", it.title).put("url", it.url).put("created", it.created)
            }))
            .put("history", JSONArray(db.history(HISTORY_LIMIT).map {
                JSONObject().put("title", it.title).put("url", it.url).put("visited", it.visitedAt)
            }))
            .put("search_shortcuts", JSONObject(savedSearchShortcuts(context).toMap()))
            .put("site_user_agents", JSONObject(siteUserAgents(context).toMap()))
            .put("site_zoom", JSONObject(siteZooms(context).toMap()))
            .put("allowlist", JSONArray(Allowlist.all(context).toList()))
            .toString(1)
    }

    /** Puts the backup in [text] back; false when it isn't a Sofa backup. */
    fun restore(context: Context, text: String): Boolean {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return false
        if (root.optInt("sofa_backup", 0) !in 1..BackupFile.VERSION) return false
        val db = Db.get(context)

        root.optJSONObject("settings")?.let { o ->
            val clean = BackupFile.cleanSettings(o.keys().asSequence().associateWith { k -> value(o.get(k)) })
            val edit = context.getSharedPreferences(Space.name("settings"), Context.MODE_PRIVATE).edit()
            for ((k, v) in clean) when (v) {
                is Boolean -> edit.putBoolean(k, v)
                is Int -> edit.putInt(k, v)
                is String -> edit.putString(k, v)
                is Set<*> -> edit.putStringSet(k, v.filterIsInstance<String>().toSet())
            }
            edit.apply()
        }

        fun objects(name: String): List<JSONObject> = root.optJSONArray(name)?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        }.orEmpty()
        fun pairs(name: String) = BackupFile.cleanPages(objects(name).map { it.optString("title") to it.optString("url") })

        db.importTiles(pairs("tiles"))
        val marks = pairs("bookmarks").map { it.second }.toSet()
        val created = objects("bookmarks").filter { it.optString("url") in marks }
        db.importBookmarks(created.map { BookmarkFile.Entry(it.optString("title"), it.optString("url"), it.optLong("created", 0)) })
        val seen = pairs("history").map { it.second }.toSet()
        db.importHistory(objects("history").filter { it.optString("url") in seen && it.optLong("visited", 0) > 0 }
            .map { HistoryEntry(0, it.optString("title").take(500), it.optString("url"), it.optLong("visited")) })

        fun strings(name: String): Map<String, String> = root.optJSONObject(name)?.let { o ->
            BackupFile.cleanMap(o.keys().asSequence().associateWith { k -> o.optString(k) })
        }.orEmpty()
        savedSearchShortcuts(context).putAll(strings("search_shortcuts"))
        siteUserAgents(context).putAll(strings("site_user_agents").filterValues { it in app.sofabrowser.Prefs.UA_MODES })
        siteZooms(context).putAll(strings("site_zoom").filterValues { it.toIntOrNull() != null })
        root.optJSONArray("allowlist")?.let { a ->
            BackupFile.cleanHosts((0 until a.length()).map { a.opt(it) }).forEach { Allowlist.set(context, it, true) }
        }
        return true
    }

    private fun value(v: Any?): Any? = if (v is JSONArray) (0 until v.length()).map { v.opt(it) } else v

    private const val HISTORY_LIMIT = 100_000
}
