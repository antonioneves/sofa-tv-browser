package app.sofabrowser.browser

import java.net.URLEncoder
import java.util.Locale

/**
 * Keywords typed before a search that send it to one site instead of the search engine: "yt cats" searches
 * YouTube, "w paris" Wikipedia. The keyword must be followed by a space and something to search for.
 */
object SearchShortcuts {

    class Shortcut(val keyword: String, val name: String, private val template: String) {
        fun url(query: String, locale: Locale = Locale.getDefault()): String = template
            .replace("{lang}", wikiLanguage(locale))
            .replace("%s", URLEncoder.encode(query, "UTF-8").replace("+", "%20"))
    }

    /** The TV's language as a Wikipedia subdomain; Norwegian Bokmål's Wikipedia is "no". */
    fun wikiLanguage(locale: Locale): String = when (val lang = locale.toLanguageTag().substringBefore('-')) {
        "", "und" -> "en"
        "nb" -> "no"
        else -> lang
    }

    val ALL = listOf(
        Shortcut("yt", "YouTube", "https://www.youtube.com/results?search_query=%s"),
        Shortcut("w", "Wikipedia", "https://{lang}.wikipedia.org/wiki/Special:Search?search=%s"),
        Shortcut("imdb", "IMDb", "https://www.imdb.com/find/?q=%s"),
        Shortcut("tw", "Twitch", "https://www.twitch.tv/search?term=%s"),
        Shortcut("r", "Reddit", "https://www.reddit.com/search/?q=%s"),
        Shortcut("map", "OpenStreetMap", "https://www.openstreetmap.org/search?query=%s"),
        Shortcut("g", "Google", "https://www.google.com/search?q=%s"),
        Shortcut("ddg", "DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    )

    /** The shortcut [text] starts with and what to search for, or null when it doesn't start with one. */
    fun match(text: String): Pair<Shortcut, String>? {
        val t = text.trimStart()
        val space = t.indexOf(' ')
        if (space <= 0) return null
        val query = t.substring(space + 1).trim()
        if (query.isEmpty()) return null
        val keyword = t.substring(0, space).lowercase(Locale.ROOT)
        return ALL.firstOrNull { it.keyword == keyword }?.let { it to query }
    }
}
