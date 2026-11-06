package app.sofabrowser.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenElementsTest {

    @Test
    fun plainSelectorsAreKept() {
        assertTrue(HiddenElements.valid("div.banner"))
        assertTrue(HiddenElements.valid("body > div:nth-of-type(2) > #ad"))
        assertTrue(HiddenElements.valid("a[href=\"/x\"]"))
    }

    @Test
    fun selectorsThatCouldBreakOutOfTheirRuleAreNot() {
        assertFalse(HiddenElements.valid("a{color:red}"))
        assertFalse(HiddenElements.valid("a } b"))
        assertFalse(HiddenElements.valid("a /* x */"))
        assertFalse(HiddenElements.valid("a\nb"))
        assertFalse(HiddenElements.valid("<style>"))
        assertFalse(HiddenElements.valid(" "))
        assertFalse(HiddenElements.valid("a".repeat(401)))
    }

    @Test
    fun addingKeepsEachSelectorOnce() {
        val one = HiddenElements.add(null, "div.ad")
        val two = HiddenElements.add(one, ".cookie")
        assertEquals(listOf("div.ad", ".cookie"), HiddenElements.parse(two))
        assertEquals(two, HiddenElements.add(two, "div.ad"))
        assertEquals(two, HiddenElements.add(two, "x{}"))
        assertEquals(2, HiddenElements.count(two))
    }

    @Test
    fun aSiteKeepsAtMostSoMany() {
        var text: String? = null
        repeat(HiddenElements.MAX_PER_SITE + 5) { text = HiddenElements.add(text, "div.a$it") }
        assertEquals(HiddenElements.MAX_PER_SITE, HiddenElements.count(text))
    }

    @Test
    fun backupKeepsOnlyHostsAndPlainSelectors() {
        val clean = BackupFile.cleanHidden(mapOf("example.com" to "div.ad\nbad{}\n.x", "bad host" to "div", "a.com" to "{}"))
        assertEquals(mapOf("example.com" to "div.ad\n.x"), clean)
    }

    @Test
    fun hiddenPartsShowAmongASitesChoices() {
        val sites = SiteChoice.collect(emptyMap(), emptyMap(), emptySet(), emptySet(), mapOf("www.example.com" to "div.ad\n.x"))
        assertEquals(listOf(SiteChoice("example.com", hidden = 2)), sites)
    }
}
