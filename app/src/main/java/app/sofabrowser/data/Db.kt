package app.sofabrowser.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import app.sofabrowser.Space

data class Site(val id: Long, val title: String, val url: String)

data class HistoryEntry(val id: Long, val title: String, val url: String, val visitedAt: Long)

/**
 * All persistent user data: bookmarks (the pinned ones are Quick access) and history. Everything stays on the device;
 * there is no sync and no upload.
 */
class Db private constructor(context: Context) : SQLiteOpenHelper(context, Space.name("sofa.db"), null, 4) {

    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "history-db").apply { priority = Thread.MIN_PRIORITY }
    }

    override fun onCreate(db: SQLiteDatabase) {
        // pinned: null for a plain bookmark, else its place among the Quick access ones.
        db.execSQL("CREATE TABLE bookmarks (id INTEGER PRIMARY KEY, title TEXT NOT NULL, url TEXT NOT NULL UNIQUE, created INTEGER NOT NULL, pinned INTEGER)")
        db.execSQL("CREATE TABLE history (id INTEGER PRIMARY KEY, title TEXT NOT NULL, url TEXT NOT NULL, visited INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX history_visited ON history (visited)")
        db.execSQL("CREATE INDEX history_url ON history (url)")
        createDownloads(db)
        createMostVisitedRemoved(db)
        val now = System.currentTimeMillis()
        DEFAULT_TILES.forEachIndexed { i, (title, url) ->
            db.insert("bookmarks", null, ContentValues().apply {
                put("title", title); put("url", url); put("created", now - i); put("pinned", i)
            })
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createDownloads(db)
        if (oldVersion < 3) createMostVisitedRemoved(db)
        if (oldVersion < 4) pinTiles(db)
    }

    /** Quick access used to be its own list of tiles; now they are bookmarks that are pinned, in the same order. */
    private fun pinTiles(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE bookmarks ADD COLUMN pinned INTEGER")
        val now = System.currentTimeMillis()
        val tiles = ArrayList<Pair<String, String>>()
        db.rawQuery("SELECT title, url FROM tiles ORDER BY position", null).use { c ->
            while (c.moveToNext()) tiles.add(c.getString(0) to c.getString(1))
        }
        tiles.forEachIndexed { i, (title, url) ->
            val changed = db.update("bookmarks", ContentValues().apply { put("pinned", i) }, "url = ?", arrayOf(url))
            if (changed == 0) db.insert("bookmarks", null, ContentValues().apply {
                put("title", title); put("url", url); put("created", now - i); put("pinned", i)
            })
        }
        db.execSQL("DROP TABLE tiles")
    }

    /** Sites taken off Most visited, and when: only visits after that count for them again. */
    private fun createMostVisitedRemoved(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE most_visited_removed (host TEXT PRIMARY KEY, removed INTEGER NOT NULL)")
    }

    /** Downloads, with enough state to resume them (byte offset, validator) after a restart. */
    private fun createDownloads(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE downloads (
              id INTEGER PRIMARY KEY, url TEXT NOT NULL, name TEXT NOT NULL, mime TEXT,
              total INTEGER NOT NULL DEFAULT -1, done INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL,
              error TEXT, created INTEGER NOT NULL, finished INTEGER NOT NULL DEFAULT 0,
              target TEXT, referer TEXT, user_agent TEXT, validator TEXT, private INTEGER NOT NULL DEFAULT 0
            )""".trimIndent())
    }

    // Quick access: the pinned bookmarks

    fun pinned(): List<Site> = query("SELECT id, title, url FROM bookmarks WHERE pinned IS NOT NULL ORDER BY pinned") {
        Site(it.getLong(0), it.getString(1), it.getString(2))
    }

    fun isPinned(url: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM bookmarks WHERE url = ? AND pinned IS NOT NULL", arrayOf(url)).use { it.moveToFirst() }

    /** Pins the bookmark for [url] to the end of Quick access, bookmarking the page first if it is not yet. */
    fun pin(title: String, url: String) {
        val db = writableDatabase
        val next = db.compileStatement("SELECT COALESCE(MAX(pinned) + 1, 0) FROM bookmarks").simpleQueryForLong()
        val changed = db.update("bookmarks", ContentValues().apply { put("pinned", next) }, "url = ? AND pinned IS NULL", arrayOf(url))
        if (changed == 0 && !isBookmarked(url)) db.insert("bookmarks", null, ContentValues().apply {
            put("title", title.ifBlank { url }); put("url", url); put("created", System.currentTimeMillis()); put("pinned", next)
        })
    }

    /** Takes the bookmark off Quick access; it stays a bookmark. */
    fun unpin(url: String) {
        writableDatabase.update("bookmarks", ContentValues().apply { putNull("pinned") }, "url = ?", arrayOf(url))
    }

    fun updateBookmark(id: Long, title: String, url: String) {
        writableDatabase.updateWithOnConflict("bookmarks", ContentValues().apply { put("title", title); put("url", url) },
            "id = ?", arrayOf(id.toString()), SQLiteDatabase.CONFLICT_IGNORE)
    }

    /** Persists [ids] (bookmark ids) as the new Quick access order. */
    fun reorderPinned(ids: List<Long>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEachIndexed { i, id ->
                db.update("bookmarks", ContentValues().apply { put("pinned", i) }, "id = ?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // Bookmarks

    /** Newest first; [limit] of them when the caller shows only a few (the start page), all when it is 0. */
    fun bookmarks(limit: Int = 0): List<Site> =
        query("SELECT id, title, url FROM bookmarks ORDER BY created DESC" + if (limit > 0) " LIMIT $limit" else "") {
            Site(it.getLong(0), it.getString(1), it.getString(2))
        }

    /** The newest [limit] bookmarks that are not pinned to Quick access, the most recently added first. */
    fun recentBookmarks(limit: Int): List<Site> = query(
        "SELECT id, title, url FROM bookmarks WHERE pinned IS NULL ORDER BY created DESC LIMIT $limit") {
        Site(it.getLong(0), it.getString(1), it.getString(2))
    }

    fun isBookmarked(url: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM bookmarks WHERE url = ?", arrayOf(url)).use { it.moveToFirst() }

    fun addBookmark(title: String, url: String) {
        // Not a REPLACE: that would drop the pin of a bookmark that is added again.
        val values = ContentValues().apply {
            put("title", title.ifBlank { url }); put("url", url); put("created", System.currentTimeMillis())
        }
        if (writableDatabase.update("bookmarks", values, "url = ?", arrayOf(url)) == 0) writableDatabase.insert("bookmarks", null, values)
    }

    fun removeBookmark(url: String) {
        writableDatabase.delete("bookmarks", "url = ?", arrayOf(url))
    }

    /** All bookmarks, newest first, with when each was added. */
    fun bookmarkEntries(): List<BookmarkFile.Entry> =
        query("SELECT title, url, created FROM bookmarks ORDER BY created DESC") {
            BookmarkFile.Entry(it.getString(0), it.getString(1), it.getLong(2))
        }

    fun clearBookmarks() {
        writableDatabase.delete("bookmarks", null, null)
    }

    /**
     * Adds the [entries] not bookmarked yet and returns how many. An entry without a date counts as added now, just
     * after the one before it, so the list keeps the file's order.
     */
    fun importBookmarks(entries: List<BookmarkFile.Entry>): Int {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        var added = 0
        db.beginTransaction()
        try {
            entries.forEachIndexed { i, e ->
                val row = db.insertWithOnConflict("bookmarks", null, ContentValues().apply {
                    put("title", e.title.ifBlank { e.url }); put("url", e.url)
                    put("created", if (e.created > 0) e.created else now - i)
                }, SQLiteDatabase.CONFLICT_IGNORE)
                if (row != -1L) added++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return added
    }

    /** Pins the quick-access [pages] (title, address) that are not pinned yet, bookmarking them first if need be. */
    fun importTiles(pages: List<Pair<String, String>>) {
        pages.filter { !isPinned(it.second) }.forEach { (title, url) -> pin(title.ifBlank { url }, url) }
    }

    /** Adds the visits not in the history yet (same address, same moment) and returns how many. */
    fun importHistory(visits: List<HistoryEntry>): Int {
        val db = writableDatabase
        var added = 0
        db.beginTransaction()
        try {
            visits.forEach { v ->
                val known = db.compileStatement("SELECT COUNT(*) FROM history WHERE url = ? AND visited = ?").apply {
                    bindString(1, v.url); bindLong(2, v.visitedAt)
                }.simpleQueryForLong() > 0
                if (!known) {
                    db.insert("history", null, ContentValues().apply {
                        put("title", v.title); put("url", v.url); put("visited", v.visitedAt)
                    })
                    added++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (added > 0) historyChanged()
        return added
    }

    // History

    fun history(limit: Int = 500): List<HistoryEntry> =
        query("SELECT id, title, url, visited FROM history ORDER BY visited DESC LIMIT $limit") {
            HistoryEntry(it.getLong(0), it.getString(1), it.getString(2), it.getLong(3))
        }

    /** Records a visit, collapsing a reload or same-page revisit within the last minute into one row. */
    fun recordVisit(title: String, url: String) {
        val now = System.currentTimeMillis()
        writer.execute {
            val db = writableDatabase
            val updated = db.update("history", ContentValues().apply { put("title", title); put("visited", now) },
                "url = ? AND visited > ?", arrayOf(url, (now - 60_000).toString()))
            if (updated == 0) {
                db.insert("history", null, ContentValues().apply {
                    put("title", title); put("url", url); put("visited", now)
                })
            }
            historyChanged()
        }
    }

    fun updateHistoryTitle(url: String, title: String) {
        if (title.isBlank()) return
        writer.execute {
            writableDatabase.execSQL(
                "UPDATE history SET title = ? WHERE id = (SELECT id FROM history WHERE url = ? ORDER BY visited DESC LIMIT 1)",
                arrayOf(title, url))
        }
    }

    fun removeHistory(id: Long) {
        writableDatabase.delete("history", "id = ?", arrayOf(id.toString()))
        historyChanged()
    }

    /** Removes every visit to [url]. */
    fun removeHistoryOf(url: String) {
        writableDatabase.delete("history", "url = ?", arrayOf(url))
        historyChanged()
    }

    // The list of removed sites names visited sites too, so it goes with the history.
    fun clearHistory() {
        writableDatabase.delete("history", null, null)
        writableDatabase.delete("most_visited_removed", null, null)
        historyChanged()
    }

    /** Removes the pages "Keep history for" no longer keeps ([days] as in HistoryRetention). */
    fun pruneHistory(days: Int) {
        val cutoff = HistoryRetention.cutoff(days, System.currentTimeMillis()) ?: return
        writableDatabase.delete("history", "visited < ?", arrayOf(cutoff.toString()))
        writableDatabase.delete("most_visited_removed", "removed < ?", arrayOf(cutoff.toString()))
        historyChanged()
    }

    /** Takes [site] off Most visited, keeping its history; it comes back only if visited often again. */
    fun removeMostVisited(site: Site) {
        val host = Uri.parse(site.url).host?.lowercase() ?: return
        writableDatabase.insertWithOnConflict("most_visited_removed", null, ContentValues().apply {
            put("host", host); put("removed", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        historyChanged()
    }

    /**
     * Most visited, worked out from the history: every site with its visits, the most visited first, pinned or not.
     * Working it out reads a month of history, too slow for the home screen to wait for on a TV, so it is done on the
     * history thread after each change and kept here. [mostVisitedOf] is how many changes it has seen.
     */
    @Volatile private var mostVisitedCache: List<Ranked>? = null
    private val historyVersion = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var mostVisitedOf = -1L
    private val counting = java.util.concurrent.atomic.AtomicBoolean()

    private class Ranked(val host: String, val site: Site, val visits: Int)

    /** The history changed: the kept Most visited is out of date, and is worked out again on the history thread. */
    private fun historyChanged() {
        historyVersion.incrementAndGet()
        countLater()
    }

    /** Works out Most visited on the history thread, once for any number of changes made meanwhile. */
    fun countLater() {
        if (!counting.compareAndSet(false, true)) return
        writer.execute {
            counting.set(false)
            val version = historyVersion.get()
            val ranked = runCatching { rankSites() }.getOrNull() ?: return@execute
            synchronized(this) {
                if (version >= mostVisitedOf) { mostVisitedCache = ranked; mostVisitedOf = version }
            }
        }
    }

    /**
     * Sites visited at least [minVisits] times in the last 30 days, grouped by host so that many pages of one
     * site count as that site. Hosts already pinned as tiles are left out, and so are visits before a site was
     * removed from the list.
     */
    fun mostVisited(limit: Int, minVisits: Int = 3): List<Site> {
        val pinned = pinned().mapNotNull { hostOf(it.url) }.toSet()
        // Kept from the last change, or worked out here the first time (and after a change whose count isn't done).
        val ranked = mostVisitedCache?.takeIf { mostVisitedOf == historyVersion.get() } ?: run {
            val version = historyVersion.get()
            rankSites().also { r ->
                synchronized(this) { if (version >= mostVisitedOf) { mostVisitedCache = r; mostVisitedOf = version } }
            }
        }
        return ranked.asSequence()
            .filter { it.visits >= minVisits && it.host.removePrefix("www.") !in pinned }
            .take(limit)
            .mapIndexed { i, r -> r.site.copy(id = -1L - i) }
            .toList()
    }

    /** Every site visited in the last 30 days with its visits since it was last taken off Most visited, the most first. */
    private fun rankSites(): List<Ranked> {
        val since = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        val removed = HashMap<String, Long>()
        readableDatabase.rawQuery("SELECT host, removed FROM most_visited_removed", null).use { c ->
            while (c.moveToNext()) removed[c.getString(0)] = c.getLong(1)
        }
        class Agg(val scheme: String, val latest: Long) {
            var count = 0
            var rootTitle: String? = null
        }
        val byHost = LinkedHashMap<String, Agg>()
        readableDatabase.rawQuery("SELECT title, url, visited FROM history WHERE visited > ? ORDER BY visited DESC",
            arrayOf(since.toString())).use { c ->
            while (c.moveToNext()) {
                val uri = Uri.parse(c.getString(1))
                val scheme = uri.scheme
                val host = uri.host?.lowercase() ?: continue
                if (scheme != "https" && scheme != "http") continue
                if (c.getLong(2) <= (removed[host] ?: 0L)) continue
                val agg = byHost.getOrPut(host) { Agg(scheme, c.getLong(2)) }
                agg.count++
                if (agg.rootTitle == null && (uri.path.isNullOrEmpty() || uri.path == "/")) agg.rootTitle = c.getString(0)
            }
        }
        return byHost.entries
            .sortedWith(compareByDescending<Map.Entry<String, Agg>> { it.value.count }.thenByDescending { it.value.latest })
            .map { (host, agg) ->
                Ranked(host, Site(0L, agg.rootTitle?.takeIf { it.isNotBlank() } ?: prettyHost(host), "${agg.scheme}://$host/"), agg.count)
            }
    }

    /** Prefix/substring matches from bookmarks and history for the address bar, all computed locally. */
    fun suggest(text: String, limit: Int = 6): List<Site> {
        if (text.isBlank()) return emptyList()
        val like = "%" + text.replace("%", "").replace("_", "") + "%"
        return query("""
            SELECT id, title, url FROM (
              SELECT id, title, url, 2 AS rank, created AS t FROM bookmarks WHERE url LIKE ?1 OR title LIKE ?1
              UNION ALL
              SELECT MAX(id), title, url, 1 AS rank, MAX(visited) AS t FROM history
                WHERE url LIKE ?1 OR title LIKE ?1 GROUP BY url
            ) GROUP BY url ORDER BY MAX(rank) DESC, MAX(t) DESC LIMIT $limit""".trimIndent(), arrayOf(like)) {
            Site(it.getLong(0), it.getString(1), it.getString(2))
        }
    }

    private fun <T> query(sql: String, args: Array<String>? = null, row: (android.database.Cursor) -> T): List<T> =
        readableDatabase.rawQuery(sql, args).use { c ->
            buildList { while (c.moveToNext()) add(row(c)) }
        }

    companion object {
        private val DEFAULT_TILES = listOf(
            "YouTube" to "https://www.youtube.com/",
            "Crunchyroll" to "https://www.crunchyroll.com/videos/anime",
            "IGN" to "https://www.ign.com/videos",
            "Plex" to "https://app.plex.tv/desktop/#!/",
            "Wikipedia" to "https://www.wikipedia.org/",
        )

        fun hostOf(url: String): String? =
            runCatching { Uri.parse(url).host }.getOrNull()?.lowercase()?.removePrefix("www.")

        /** "news.example.co.uk" -> "News Example"; used when a site never gave its home page a title. */
        fun prettyHost(host: String): String {
            // IP addresses (and "localhost") are already as readable as they get.
            if (host.all { it.isDigit() || it == '.' } || ':' in host || '.' !in host) return host
            val parts = host.removePrefix("www.").split('.')
            val core = if (parts.size > 2 && parts[parts.size - 2].length <= 3) parts.dropLast(2) else parts.dropLast(1)
            return core.ifEmpty { parts }.joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
        }

        @Volatile private var instance: Db? = null

        fun get(context: Context): Db = instance ?: synchronized(this) {
            instance ?: Db(context.applicationContext).also { instance = it }
        }
    }
}
