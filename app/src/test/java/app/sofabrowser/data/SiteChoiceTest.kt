package app.sofabrowser.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SiteChoiceTest {

    @Test
    fun zoomStepsAre25PercentApart() {
        assertEquals(100, SiteChoice.zoomPercent(0))
        assertEquals(125, SiteChoice.zoomPercent(1))
        assertEquals(156, SiteChoice.zoomPercent(2))
        assertEquals(80, SiteChoice.zoomPercent(-1))
        assertEquals(64, SiteChoice.zoomPercent(-2))
    }

    @Test
    fun choicesKeptByHostComeTogetherUnderTheirSite() {
        val sites = SiteChoice.collect(
            zooms = mapOf("example.com" to "2"),
            userAgents = mapOf("qwant.com" to "desktop"),
            adBlockOff = setOf("news.example.com"),
            keysToPage = setOf("WWW.Example.com", "app.tv.example.co.uk"),
        )
        assertEquals(listOf(
            SiteChoice("example.co.uk", keysToPage = true),
            SiteChoice("example.com", zoomStep = 2, adBlockOff = true, keysToPage = true),
            SiteChoice("qwant.com", userAgent = "desktop"),
        ), sites)
    }

    @Test
    fun aZoomOf100PercentOrUnreadableIsNoChoice() {
        val sites = SiteChoice.collect(mapOf("a.com" to "0", "b.com" to "x"), emptyMap(), emptySet(), emptySet())
        assertEquals(emptyList<SiteChoice>(), sites)
    }
}
