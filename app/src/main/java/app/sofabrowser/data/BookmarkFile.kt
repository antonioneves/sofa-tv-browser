package app.sofabrowser.data

/**
 * Bookmarks as the HTML file Chrome exports and imports (the old Netscape format, which Firefox, Edge and Safari also
 * read and write): `<A HREF>` links in nested `<DL>` lists, each folder named by the `<H3>` before its list. Reading
 * keeps each link's folder as a path of names; writing gives the same tree back.
 */
object BookmarkFile {

    /**
     * [created] is in milliseconds; 0 when the file doesn't say. [folder] is the path of folder names it sits in, from
     * the top ("News", "Tech"); empty at the top.
     */
    data class Entry(val title: String, val url: String, val created: Long, val folder: List<String> = emptyList())

    /** A folder to write even when nothing is in it: its path from the top, and when it was made (0: not known). */
    data class Folder(val path: List<String>, val created: Long = 0)

    /** At most this many folders are taken from one file; what is in any after them goes to the folder above. */
    const val MAX_FOLDERS = 2_000

    /**
     * [entries] as a bookmarks file: each folder an `<H3>` followed by its list, folders first (A to Z, as Sofa shows
     * them) and then the folder's bookmarks in the order given. [folders] adds the dates folders were made, and the
     * folders with nothing in them.
     */
    fun write(entries: List<Entry>, folders: List<Folder> = emptyList()): String {
        class Node {
            var created = 0L
            val children = LinkedHashMap<String, Node>()
            val entries = ArrayList<Entry>()
        }
        val root = Node()
        fun node(path: List<String>) = path.fold(root) { n, name -> n.children.getOrPut(name) { Node() } }
        folders.forEach { f -> if (f.path.isNotEmpty()) node(f.path).created = f.created }
        entries.forEach { e -> node(e.folder).entries += e }
        return buildString {
            append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n")
            append("<!-- This is an automatically generated file.\n     It will be read and overwritten.\n     DO NOT EDIT! -->\n")
            append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n")
            append("<TITLE>Bookmarks</TITLE>\n<H1>Bookmarks</H1>\n")
            fun list(n: Node, indent: String) {
                append(indent).append("<DL><p>\n")
                val inner = "$indent    "
                for ((name, c) in n.children.entries.sortedWith(compareBy(FolderTree.byName) { it.key })) {
                    append(inner).append("<DT><H3")
                    if (c.created > 0) append(" ADD_DATE=\"").append(c.created / 1000).append('"')
                    append('>').append(esc(name)).append("</H3>\n")
                    list(c, inner)
                }
                for (e in n.entries) {
                    append(inner).append("<DT><A HREF=\"").append(esc(e.url)).append('"')
                    if (e.created > 0) append(" ADD_DATE=\"").append(e.created / 1000).append('"')
                    append('>').append(esc(e.title)).append("</A>\n")
                }
                append(indent).append("</DL><p>\n")
            }
            list(root, "")
        }
    }

    /**
     * The web addresses in a bookmarks file, in file order, each once, with their folders. Other links in it
     * (javascript:, the browser's own pages) are left out; null when it isn't a bookmarks file at all.
     *
     * The browser's bookmarks bar (the top folder marked `PERSONAL_TOOLBAR_FOLDER`) is opened up: what is in it goes to
     * the top, as it is what the browser showed first. Other top folders ("Other bookmarks") stay. A folder without a
     * name is opened up into the one above it. A folder exists here only through the bookmarks in it, so one left
     * empty is not made.
     */
    fun read(html: String): List<Entry>? {
        if (!html.removePrefix("\uFEFF").trimStart().startsWith("<!DOCTYPE NETSCAPE-Bookmark-file-1", ignoreCase = true)) return null
        val seen = HashSet<String>()
        val out = ArrayList<Entry>()
        // The folder path, and for each open list whether it added a name to the path (the file's outer list doesn't).
        val path = ArrayList<String>()
        val named = ArrayList<Boolean>()
        // A folder's name waits for the list that follows it.
        var pending: String? = null
        val folders = HashSet<List<String>>()
        for (m in TOKEN.findAll(html)) {
            val g = m.groups
            when {
                g[1] != null -> {
                    pending = null
                    val attrs = m.groupValues[1]
                    val url = attr(attrs, "HREF")?.let(::unescape)?.trim() ?: continue
                    val scheme = url.substringBefore(':', "").lowercase()
                    if (scheme != "http" && scheme != "https" || !seen.add(url)) continue
                    val title = text(m.groupValues[2])
                    out += Entry(title.ifEmpty { url }, url, attr(attrs, "ADD_DATE")?.toLongOrNull()?.let(::millis) ?: 0, path.toList())
                }
                g[4] != null -> {
                    val toolbar = path.isEmpty() && attr(m.groupValues[3], "PERSONAL_TOOLBAR_FOLDER").equals("true", ignoreCase = true)
                    pending = if (toolbar) "" else FolderTree.cleanName(text(m.groupValues[4]))
                }
                g[5] != null -> {
                    val name = pending
                    pending = null
                    val opens = !name.isNullOrEmpty() && path.size < FolderTree.MAX_DEPTH &&
                        (path + name in folders || folders.size < MAX_FOLDERS)
                    if (opens) { path += name; folders += path.toList() }
                    named += opens
                }
                g[6] != null -> {
                    pending = null
                    if (named.isNotEmpty() && named.removeAt(named.size - 1)) path.removeAt(path.size - 1)
                }
            }
        }
        return out
    }

    /** Text between tags as one line: inner tags dropped, entities decoded, runs of white space made one space. */
    private fun text(html: String) = unescape(html.replace(TAG, "")).trim().replace(SPACE, " ")

    /** Chrome and Firefox write seconds; a few tools write milliseconds or microseconds. */
    private fun millis(t: Long): Long = when {
        t <= 0 -> 0
        t < 100_000_000_000L -> t * 1000
        t < 100_000_000_000_000L -> t
        else -> t / 1000
    }

    private fun attr(attrs: String, name: String): String? =
        Regex("""\b$name\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>"']+))""", RegexOption.IGNORE_CASE).find(attrs)
            ?.groups?.drop(1)?.firstNotNullOfOrNull { it?.value }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    internal fun unescape(s: String): String = ENTITY.replace(s) { m ->
        val name = m.groupValues[1]
        when {
            name.startsWith("#x", true) -> name.substring(2).toIntOrNull(16)?.let(::codePoint)
            name.startsWith("#") -> name.substring(1).toIntOrNull()?.let(::codePoint)
            else -> NAMED[name.lowercase()]
        } ?: m.value
    }

    private fun codePoint(c: Int) = if (Character.isValidCodePoint(c)) String(Character.toChars(c)) else null

    /**
     * The parts of the file that matter, in order: a link and its title (groups 1–2); a folder name, which ends at
     * `</H3>` or, when a file forgets to close it, at the next link, folder or list line (3–4); a list opening (5) or
     * closing (6).
     */
    private val TOKEN = Regex(
        """<A\s([^>]*)>(.*?)</A\s*>""" +
            """|<H3(\s[^>]*)?>(.*?)(?:</H3\s*>|(?=<A\s|<DT|<H3|</?DL))""" +
            """|(<DL\b[^>]*>)|(</DL\s*>)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TAG = Regex("<[^>]*>")
    private val SPACE = Regex("\\s+")
    private val ENTITY = Regex("&(#[xX][0-9a-fA-F]+|#\\d+|[a-zA-Z]+);")
    private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")
}
