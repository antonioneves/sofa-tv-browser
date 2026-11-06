package app.sofabrowser.browser

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchShortcutsTest {

    @Test
    fun aKeywordAndASpaceSendTheRestToThatSite() {
        val (shortcut, query) = SearchShortcuts.match("YT  cat videos ")!!
        assertEquals("YouTube", shortcut.name)
        assertEquals("cat videos", query)
        assertEquals("https://www.youtube.com/results?search_query=cat%20videos", shortcut.url(query))
    }

    @Test
    fun withoutAQueryOrAKnownKeywordItIsAnOrdinarySearch() {
        assertNull(SearchShortcuts.match("yt"))
        assertNull(SearchShortcuts.match("yt   "))
        assertNull(SearchShortcuts.match("youtube cats"))
        assertNull(SearchShortcuts.match("ytcats"))
    }

    @Test
    fun queriesAreEncoded() {
        val (shortcut, query) = SearchShortcuts.match("g a&b=c ä")!!
        assertEquals("https://www.google.com/search?q=a%26b%3Dc%20%C3%A4", shortcut.url(query))
    }

    @Test
    fun wikipediaFollowsTheTvsLanguage() {
        val (w, query) = SearchShortcuts.match("w Paris")!!
        assertEquals("https://fr.wikipedia.org/wiki/Special:Search?search=Paris", w.url(query, Locale.FRANCE))
        assertEquals("no", SearchShortcuts.wikiLanguage(Locale.forLanguageTag("nb-NO")))
        assertEquals("en", SearchShortcuts.wikiLanguage(Locale.ROOT))
    }
}
