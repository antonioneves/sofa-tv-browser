package app.sofabrowser.browser

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class SearchShortcutsTest {

    private val builtIn = SearchShortcuts.BUILT_IN

    @Test
    fun aKeywordAndASpaceSendTheRestToThatSite() {
        val (shortcut, query) = SearchShortcuts.match("YT  cat videos ", builtIn)!!
        assertEquals("YouTube", shortcut.name)
        assertEquals("cat videos", query)
        assertEquals("https://www.youtube.com/results?search_query=cat%20videos", shortcut.url(query))
    }

    @Test
    fun withoutAQueryOrAKnownKeywordItIsAnOrdinarySearch() {
        assertNull(SearchShortcuts.match("yt", builtIn))
        assertNull(SearchShortcuts.match("yt   ", builtIn))
        assertNull(SearchShortcuts.match("youtube cats", builtIn))
        assertNull(SearchShortcuts.match("ytcats", builtIn))
    }

    @Test
    fun queriesAreEncoded() {
        val (shortcut, query) = SearchShortcuts.match("g a&b=c ä", builtIn)!!
        assertEquals("https://www.google.com/search?q=a%26b%3Dc%20%C3%A4", shortcut.url(query))
    }

    @Test
    fun wikipediaFollowsTheTvsLanguage() {
        val (w, query) = SearchShortcuts.match("w Paris", builtIn)!!
        assertEquals("https://fr.wikipedia.org/wiki/Special:Search?search=Paris", w.url(query, Locale.FRANCE))
        assertEquals("no", SearchShortcuts.wikiLanguage(Locale.forLanguageTag("nb-NO")))
        assertEquals("en", SearchShortcuts.wikiLanguage(Locale.ROOT))
    }

    @Test
    fun savedShortcutsChangeRemoveAndAdd() {
        val all = SearchShortcuts.all(mapOf(
            "yt" to "https://www.youtube.com/results?search_query=%s&sp=EgIQAQ",
            "r" to "",
            "map" to "https://www.google.com/maps/search/%s",
            "jf" to "http://192.168.1.20:8096/web/#/search.html?query=%s",
            "aw" to "https://www.allrecipes.com/search?q=%s",
        ))
        val byKey = all.associateBy { it.keyword }
        assertFalse("r" in byKey)
        // A changed built-in keeps its name on the same site, and takes the new site's otherwise.
        assertEquals("YouTube", byKey.getValue("yt").name)
        assertEquals("Google", byKey.getValue("map").name)
        assertTrue(byKey.getValue("map").builtIn)
        assertEquals("192.168.1.20", byKey.getValue("jf").name)
        assertEquals("Allrecipes", byKey.getValue("aw").name)
        // Sofa's come first in their own order, then the added ones by keyword.
        assertEquals(listOf("yt", "w", "imdb", "tw", "map", "g", "ddg", "aw", "jf"), all.map { it.keyword })
        assertEquals("http://192.168.1.20:8096/web/#/search.html?query=the%20office", SearchShortcuts.match("jf the office", all)!!.let { (s, q) -> s.url(q) })
    }

    @Test
    fun keywordsAndAddressesAreChecked() {
        assertEquals("rt", SearchShortcuts.keywordOf(" RT "))
        assertNull(SearchShortcuts.keywordOf(""))
        assertNull(SearchShortcuts.keywordOf("two words"))
        assertNull(SearchShortcuts.keywordOf("a".repeat(SearchShortcuts.MAX_KEYWORD + 1)))
        assertTrue(SearchShortcuts.validTemplate("https://example.com/?q=%s"))
        assertFalse(SearchShortcuts.validTemplate("https://example.com/"))
        assertFalse(SearchShortcuts.validTemplate("ftp://example.com/%s"))
    }
}
