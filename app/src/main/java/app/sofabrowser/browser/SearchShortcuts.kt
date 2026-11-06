package app.sofabrowser.browser

import app.sofabrowser.data.Db
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/**
 * Keywords typed before a search that send it to one site instead of the search engine: "yt cats" searches
 * YouTube, "w paris" Wikipedia. The keyword must be followed by a space and something to search for.
 *
 * Sofa comes with [BUILT_IN]; the user's changes are kept as keyword to search address, where an address changes a
 * built-in one or adds a shortcut, and "" removes a built-in one.
 */
object SearchShortcuts {

    class Shortcut(val keyword: String, val name: String, val template: String, val builtIn: Boolean = false) {
        fun url(query: String, locale: Locale = Locale.getDefault()): String = template
            .replace("{lang}", wikiLanguage(locale))
            .replace("%s", URLEncoder.encode(query, "UTF-8").replace("+", "%20"))
    }

    val BUILT_IN = listOf(
        Shortcut("yt", "YouTube", "https://www.youtube.com/results?search_query=%s", true),
        Shortcut("w", "Wikipedia", "https://{lang}.wikipedia.org/wiki/Special:Search?search=%s", true),
        Shortcut("imdb", "IMDb", "https://www.imdb.com/find/?q=%s", true),
        Shortcut("tw", "Twitch", "https://www.twitch.tv/search?term=%s", true),
        Shortcut("r", "Reddit", "https://www.reddit.com/search/?q=%s", true),
        Shortcut("map", "OpenStreetMap", "https://www.openstreetmap.org/search?query=%s", true),
        Shortcut("g", "Google", "https://www.google.com/search?q=%s", true),
        Shortcut("ddg", "DuckDuckGo", "https://duckduckgo.com/?q=%s", true),
    )

    /** The built-in shortcuts as changed in [saved], then the added ones by keyword. */
    fun all(saved: Map<String, String>): List<Shortcut> {
        val builtIn = BUILT_IN.mapNotNull { b ->
            when (val t = saved[b.keyword]) {
                null -> b
                "" -> null
                else -> Shortcut(b.keyword, if (host(t) == host(b.template)) b.name else nameOf(t), t, true)
            }
        }
        val added = saved.filter { (k, t) -> t.isNotEmpty() && BUILT_IN.none { it.keyword == k } }.toSortedMap()
            .map { (k, t) -> Shortcut(k, nameOf(t), t) }
        return builtIn + added
    }

    /** The shortcut [text] starts with and what to search for, or null when it doesn't start with one. */
    fun match(text: String, shortcuts: List<Shortcut>): Pair<Shortcut, String>? {
        val t = text.trimStart()
        val space = t.indexOf(' ')
        if (space <= 0) return null
        val query = t.substring(space + 1).trim()
        if (query.isEmpty()) return null
        val keyword = t.substring(0, space).lowercase(Locale.ROOT)
        return shortcuts.firstOrNull { it.keyword == keyword }?.let { it to query }
    }

    /** A keyword is one word, short enough to be quicker than typing the site. */
    fun keywordOf(text: String): String? = text.trim().lowercase(Locale.ROOT).takeIf { k ->
        k.length in 1..MAX_KEYWORD && k.none { it.isWhitespace() }
    }

    /** A search address is a web address with %s where the search goes. */
    fun validTemplate(t: String) = (t.startsWith("https://") || t.startsWith("http://")) && "%s" in t && host(t) != null

    /** The TV's language as a Wikipedia subdomain; Norwegian Bokmål's Wikipedia is "no". */
    fun wikiLanguage(locale: Locale): String = when (val lang = locale.toLanguageTag().substringBefore('-')) {
        "", "und" -> "en"
        "nb" -> "no"
        else -> lang
    }

    private fun host(template: String): String? =
        runCatching { URI(template.replace("{lang}", "en").replace("%s", "x")).host }.getOrNull()?.lowercase()?.removePrefix("www.")

    private fun nameOf(template: String) = host(template)?.let { Db.prettyHost(it) } ?: template

    const val MAX_KEYWORD = 16
}
