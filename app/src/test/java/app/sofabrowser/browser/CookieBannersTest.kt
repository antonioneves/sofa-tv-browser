package app.sofabrowser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieBannersTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000 * day

    @Test
    fun sitesAreLeftAloneForThirtyDays() {
        val looked = mapOf(
            "fresh.com" to (now - day).toString(),
            "almost.com" to (now - 30 * day + 1).toString(),
            "old.com" to (now - 30 * day).toString(),
            "future.com" to (now + day).toString(),
            "broken.com" to "x",
        )
        assertEquals(listOf("almost.com", "fresh.com"), CookieBanners.current(looked, now))
    }

    @Test
    fun scriptCarriesTheSitesAndChannel() {
        val script = CookieBanners.script("_chan", listOf("a.com", "b.org"))
        assertTrue(script.contains("const DONE = [\"a.com\",\"b.org\"];"))
        assertTrue(script.contains("const L = \"_chan\";"))
    }
}
