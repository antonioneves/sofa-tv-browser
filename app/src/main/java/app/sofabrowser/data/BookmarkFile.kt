package app.sofabrowser.data

/**
 * Bookmarks as the HTML file Chrome exports and imports (the old Netscape format, which Firefox, Edge and Safari also
 * read and write). Sofa's bookmarks have no folders, so a file's folders are flattened on import.
 */
object BookmarkFile {

    /** [created] is in milliseconds; 0 when the file doesn't say. */
    data class Entry(val title: String, val url: String, val created: Long)

    /** [entries] in the order given, as Chrome writes them. */
    fun write(entries: List<Entry>): String = buildString {
        append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n")
        append("<!-- This is an automatically generated file.\n     It will be read and overwritten.\n     DO NOT EDIT! -->\n")
        append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n")
        append("<TITLE>Bookmarks</TITLE>\n<H1>Bookmarks</H1>\n<DL><p>\n")
        for (e in entries) {
            append("    <DT><A HREF=\"").append(esc(e.url)).append('"')
            if (e.created > 0) append(" ADD_DATE=\"").append(e.created / 1000).append('"')
            append('>').append(esc(e.title)).append("</A>\n")
        }
        append("</DL><p>\n")
    }

    /**
     * The web addresses in a bookmarks file, in file order, each once. Other links in it (javascript:, the browser's
     * own pages) are left out; null when it isn't a bookmarks file at all.
     */
    fun read(html: String): List<Entry>? {
        if (!html.removePrefix("\uFEFF").trimStart().startsWith("<!DOCTYPE NETSCAPE-Bookmark-file-1", ignoreCase = true)) return null
        val seen = HashSet<String>()
        return LINK.findAll(html).mapNotNull { m ->
            val attrs = m.groupValues[1]
            val url = attr(attrs, "HREF")?.let(::unescape)?.trim() ?: return@mapNotNull null
            val scheme = url.substringBefore(':', "").lowercase()
            if (scheme != "http" && scheme != "https" || !seen.add(url)) return@mapNotNull null
            val title = unescape(m.groupValues[2].replace(TAG, "")).trim().replace(SPACE, " ")
            Entry(title.ifEmpty { url }, url, attr(attrs, "ADD_DATE")?.toLongOrNull()?.let(::millis) ?: 0)
        }.toList()
    }

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

    private val LINK = Regex("""<A\s([^>]*)>(.*?)</A\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TAG = Regex("<[^>]*>")
    private val SPACE = Regex("\\s+")
    private val ENTITY = Regex("&(#[xX][0-9a-fA-F]+|#\\d+|[a-zA-Z]+);")
    private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")
}
