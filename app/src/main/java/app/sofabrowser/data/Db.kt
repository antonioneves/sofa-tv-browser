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
 * All persistent user data: bookmarks in their folders (the pinned ones are Quick access) and history. Everything stays on the device;
 * there is no sync and no upload.
 */
class Db private constructor(context: Context) : SQLiteOpenHelper(context, Space.name("sofa.db"), null, 5) {

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
        createFolders(db)
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
        // Folders. Every bookmark there before stays at the top level.
        if (oldVersion < 5) createFolders(db)
    }

    /** Bookmark folders: a folder's [Folder.parent] is null at the top level, as a bookmark's folder is. */
    private fun createFolders(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE folders (id INTEGER PRIMARY KEY, parent INTEGER, title TEXT NOT NULL, created INTEGER NOT NULL)")
        db.execSQL("ALTER TABLE bookmarks ADD COLUMN folder INTEGER")
        db.execSQL("CREATE INDEX bookmarks_folder ON bookmarks (folder)")
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

    // Bookmarks and their folders

    /** Every bookmark, in any folder, newest first. */
    fun bookmarks(): List<Site> = query("SELECT id, title, url FROM bookmarks ORDER BY created DESC") {
        Site(it.getLong(0), it.getString(1), it.getString(2))
    }

    /** The bookmarks in [folder] (null: the top level), newest first. */
    fun bookmarksIn(folder: Long?): List<Site> =
        query("SELECT id, title, url FROM bookmarks WHERE " + inFolder(folder) + " ORDER BY created DESC", folderArgs(folder)) {
            Site(it.getLong(0), it.getString(1), it.getString(2))
        }

    /** The newest [limit] bookmarks at the top level that are not pinned to Quick access, the most recently added first. */
    fun recentBookmarks(limit: Int): List<Site> = query(
        "SELECT id, title, url FROM bookmarks WHERE pinned IS NULL AND folder IS NULL ORDER BY created DESC LIMIT $limit") {
        Site(it.getLong(0), it.getString(1), it.getString(2))
    }

    /** How many bookmarks each folder holds itself (not counting its folders); the top level under null. */
    fun bookmarkCounts(): Map<Long?, Int> = HashMap<Long?, Int>().also { out ->
        readableDatabase.rawQuery("SELECT folder, COUNT(*) FROM bookmarks GROUP BY folder", null).use { c ->
            while (c.moveToNext()) out[if (c.isNull(0)) null else c.getLong(0)] = c.getInt(1)
        }
    }

    fun folderTree(): FolderTree = FolderTree(query("SELECT id, parent, title, created FROM folders") {
        Folder(it.getLong(0), if (it.isNull(1)) null else it.getLong(1), it.getString(2), it.getLong(3))
    })

    /** A new folder called [title] in [parent]; its id. */
    fun addFolder(parent: Long?, title: String): Long = writableDatabase.insertOrThrow("folders", null, ContentValues().apply {
        if (parent == null) putNull("parent") else put("parent", parent)
        put("title", title)
        put("created", System.currentTimeMillis())
    })

    fun renameFolder(id: Long, title: String) {
        writableDatabase.update("folders", ContentValues().apply { put("title", title) }, "id = ?", arrayOf(id.toString()))
    }

    /** Moves folder [id] into [to]; the caller checked [FolderTree.canMove]. */
    fun moveFolder(id: Long, to: Long?) {
        writableDatabase.update("folders", ContentValues().apply { if (to == null) putNull("parent") else put("parent", to) },
            "id = ?", arrayOf(id.toString()))
    }

    fun moveBookmark(url: String, to: Long?) {
        writableDatabase.update("bookmarks", ContentValues().apply { if (to == null) putNull("folder") else put("folder", to) },
            "url = ?", arrayOf(url))
    }

    /** Folder [id] with every folder and bookmark in it. */
    fun deleteFolder(id: Long) {
        val ids = folderTree().subtree(id).joinToString(",")
        transaction { db ->
            db.execSQL("DELETE FROM bookmarks WHERE folder IN ($ids)")
            db.execSQL("DELETE FROM folders WHERE id IN ($ids)")
        }
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

    /** All bookmarks, newest first, with when each was added and its folder, for a bookmarks file or a backup. */
    fun bookmarkEntries(): List<BookmarkFile.Entry> {
        val tree = folderTree()
        return query("SELECT title, url, created, folder FROM bookmarks ORDER BY created DESC") {
            BookmarkFile.Entry(it.getString(0), it.getString(1), it.getLong(2), tree.pathNames(if (it.isNull(3)) null else it.getLong(3)))
        }
    }

    /** Every folder by its path, so a file or a backup keeps empty ones and the dates they were made. */
    fun folderEntries(): List<BookmarkFile.Folder> {
        val tree = folderTree()
        return tree.outline().map { (f, _) -> BookmarkFile.Folder(tree.pathNames(f.id), f.created) }
    }

    /** Every bookmark and every folder. */
    fun clearBookmarks() = transaction { db ->
        db.delete("bookmarks", null, null)
        db.delete("folders", null, null)
    }

    /** What an import added: bookmarks, and the folders they went into. */
    data class Imported(val bookmarks: Int, val folders: Int)

    /**
     * Adds [entries] that aren't bookmarked yet, each in its folder, made where missing; a folder already there with the
     * same name in the same place takes them, so importing a file twice adds nothing. A bookmark already there stays
     * in its folder with its title. An entry without a date counts as added now, just after the one before it, so the
     * list keeps the file's order. [folders] are made too even when empty (a backup's), and give made folders their dates.
     */
    fun importBookmarks(entries: List<BookmarkFile.Entry>, folders: List<BookmarkFile.Folder> = emptyList()): Imported {
        val now = System.currentTimeMillis()
        var added = 0
        val filled = HashSet<Long>()
        transaction { db ->
            val tree = folderTree()
            val dates = folders.associate { it.path to it.created }
            fun folder(path: List<String>) = tree.resolve(path) { parent, title ->
                val made = dates[tree.pathNames(parent) + title]?.takeIf { it > 0 } ?: now
                db.insertOrThrow("folders", null, ContentValues().apply {
                    if (parent == null) putNull("parent") else put("parent", parent)
                    put("title", title)
                    put("created", made)
                })
            }
            folders.forEach { folder(it.path) }
            entries.forEachIndexed { i, e ->
                // A bookmark already there is left alone, so its folder isn't made for it.
                if (db.rawQuery("SELECT 1 FROM bookmarks WHERE url = ?", arrayOf(e.url)).use { it.moveToFirst() }) return@forEachIndexed
                val to = folder(e.folder)
                db.insertOrThrow("bookmarks", null, ContentValues().apply {
                    put("title", e.title.ifBlank { e.url }); put("url", e.url); put("created", if (e.created > 0) e.created else now - i)
                    if (to == null) putNull("folder") else put("folder", to)
                })
                added++
                to?.let { filled += it }
            }
        }
        return Imported(added, filled.size)
    }

    private fun inFolder(folder: Long?) = if (folder == null) "folder IS NULL" else "folder = ?"

    private fun folderArgs(folder: Long?) = folder?.let { arrayOf(it.toString()) }

    private fun transaction(work: (SQLiteDatabase) -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            work(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
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
