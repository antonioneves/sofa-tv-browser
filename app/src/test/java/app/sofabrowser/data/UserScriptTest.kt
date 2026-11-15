package app.sofabrowser.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserScriptTest {

    private fun script(vararg header: String, body: String = "console.log(1)"): UserScript.Parsed =
        UserScript.parse((listOf("// ==UserScript==") + header.map { "// $it" } + "// ==/UserScript==" + body).joinToString("\n"))

    private fun ok(vararg header: String) = (script(*header) as UserScript.Parsed.Ok).script

    private fun refused(vararg header: String) = (script(*header) as UserScript.Parsed.Refused).reason

    @Test
    fun readsTheHeader() {
        val s = ok("@name  Skip intro", "@namespace sofa.test", "@version 1.2", "@description Skips it",
            "@match https://*.example.com/watch*", "@run-at document-start", "@noframes", "@grant GM_addStyle",
            "@updateURL https://example.com/s.meta.js")
        assertEquals("Skip intro", s.name)
        assertEquals("sofa.test", s.namespace)
        assertEquals("1.2", s.version)
        assertEquals("Skips it", s.description)
        assertEquals(UserScript.RunAt.START, s.runAt)
        assertTrue(s.noFrames)
        assertEquals(listOf("GM_addStyle"), s.grants)
        assertEquals("https://example.com/s.meta.js", s.updateUrl)
        assertEquals(listOf("example.com"), s.siteNames())
    }

    @Test
    fun runsAtIdleWithoutRunAt() {
        assertEquals(UserScript.RunAt.IDLE, ok("@name a", "@match *://*/*").runAt)
    }

    @Test
    fun matchPatternsFollowChrome() {
        val s = ok("@name a", "@match *://*.example.com/watch*")
        assertTrue(s.runsOn("https://example.com/watch?v=1"))
        assertTrue(s.runsOn("http://www.example.com/watch"))
        assertTrue(s.runsOn("https://a.b.example.com:8080/watch/x#t=1"))
        assertFalse(s.runsOn("https://example.com/"))
        assertFalse(s.runsOn("https://notexample.com/watch"))
        assertFalse(s.runsOn("https://example.com.evil.test/watch"))
        assertFalse(s.runsOn("file:///sdcard/watch"))
    }

    @Test
    fun includeGlobsAndRegexes() {
        val glob = ok("@name a", "@include http*://news.example.tld/*")
        assertTrue(glob.runsOn("https://news.example.com/a"))
        assertTrue(glob.runsOn("http://news.example.co.uk/"))
        assertFalse(glob.runsOn("https://news.example.com.evil.test/"))
        val re = ok("@name a", "@include /^https://(www\\.)?example\\.org/\\d+$/i")
        assertTrue(re.runsOn("https://EXAMPLE.org/123"))
        assertFalse(re.runsOn("https://example.org/abc"))
    }

    @Test
    fun exclusionsWin() {
        val s = ok("@name a", "@match https://example.com/*", "@exclude-match https://example.com/login*", "@exclude *pay*")
        assertTrue(s.runsOn("https://example.com/home"))
        assertFalse(s.runsOn("https://example.com/login"))
        assertFalse(s.runsOn("https://example.com/paying"))
    }

    @Test
    fun allSitesStandsOut() {
        assertTrue(ok("@name a", "@match <all_urls>").allSites)
        assertTrue(ok("@name a", "@match *://*/*").allSites)
        assertTrue(ok("@name a", "@include *").allSites)
        assertTrue(ok("@name a", "@include http*").allSites)
        assertFalse(ok("@name a", "@match https://example.com/*").allSites)
        assertTrue(ok("@name a", "@match <all_urls>").siteNames().isEmpty())
    }

    @Test
    fun refusesWhatSofaDoesNotOffer() {
        assertEquals(UserScript.Reason.GRANT, refused("@name a", "@match *://*/*", "@grant GM_xmlhttpRequest"))
        assertEquals(UserScript.Reason.GRANT, refused("@name a", "@match *://*/*", "@grant unsafeWindow"))
        assertEquals(UserScript.Reason.REQUIRE, refused("@name a", "@match *://*/*", "@require https://cdn.test/x.js"))
        assertEquals(UserScript.Reason.RESOURCE, refused("@name a", "@match *://*/*", "@resource css https://cdn.test/x.css"))
        assertEquals(UserScript.Reason.CONNECT, refused("@name a", "@match *://*/*", "@connect example.com"))
        assertEquals(UserScript.Reason.RUN_AT, refused("@name a", "@match *://*/*", "@run-at context-menu"))
        assertEquals(UserScript.Reason.NO_SITES, refused("@name a"))
        assertEquals(UserScript.Reason.NO_NAME, refused("@match *://*/*"))
        assertEquals(UserScript.Reason.BAD_SITE, refused("@name a", "@match https://exa*mple.com/*"))
        assertEquals(UserScript.Reason.BAD_SITE, refused("@name a", "@match *://*/*", "@exclude /([/"))
    }

    @Test
    fun takesWhatSofaOffers() {
        val s = ok("@name a", "@match *://*/*", "@grant none", "@grant GM.getValue", "@grant GM_setValue", "@grant GM_info")
        assertEquals(listOf("GM.getValue", "GM_setValue", "GM_info"), s.grants)
    }

    @Test
    fun refusesWhatIsNotAScript() {
        assertEquals(UserScript.Reason.NOT_A_SCRIPT, (UserScript.parse("<html>hello</html>") as UserScript.Parsed.Refused).reason)
        val big = "// ==UserScript==\n// @name a\n// @match *://*/*\n// ==/UserScript==\n" + "x".repeat(UserScript.MAX_BYTES)
        assertEquals(UserScript.Reason.TOO_LARGE, (UserScript.parse(big) as UserScript.Parsed.Refused).reason)
    }

    @Test
    fun anUpdateAsksMoreWithNewSitesOrHelp() {
        val old = ok("@name a", "@match https://example.com/*")
        assertFalse(old.newerAsksMore(ok("@name a", "@version 2", "@match https://example.com/*")))
        assertTrue(old.newerAsksMore(ok("@name a", "@match https://example.com/*", "@match https://other.test/*")))
        assertTrue(old.newerAsksMore(ok("@name a", "@match https://example.com/*", "@grant GM_setValue")))
    }
}
