package app.sofabrowser.browser

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeServerTest {

    private fun head(raw: String) = HomeServer.readHead(ByteArrayInputStream(raw.toByteArray(Charsets.ISO_8859_1)))

    @Test
    fun theHeadEndsAtTheBlankLine() {
        assertEquals("POST /k HTTP/1.1\r\nContent-Length: 3", head("POST /k HTTP/1.1\r\nContent-Length: 3\r\n\r\nt=a"))
    }

    @Test
    fun aCutOffOrEndlessHeadIsRefused() {
        assertNull(head("GET /k HTTP/1.1\r\n"))
        assertNull(head("GET /" + "a".repeat(9000)))
    }

    @Test
    fun onlyAddressesUnderTheKeyAreServed() {
        assertEquals("", HomeServer.pathUnder("/abc", "abc"))
        assertEquals("", HomeServer.pathUnder("/abc?x=1", "abc"))
        assertEquals("/bookmarks.html", HomeServer.pathUnder("/abc/bookmarks.html", "abc"))
        assertNull(HomeServer.pathUnder("/abcd", "abc"))
        assertNull(HomeServer.pathUnder("/ab", "abc"))
        assertNull(HomeServer.pathUnder("/", "abc"))
    }

    @Test
    fun formFieldsAreDecoded() {
        assertEquals("cats & dogs", HomeServer.formField("x=1&t=cats+%26+dogs", "t"))
        assertEquals("https://example.com/?q=ä", HomeServer.formField("t=https%3A%2F%2Fexample.com%2F%3Fq%3D%C3%A4", "t"))
        assertNull(HomeServer.formField("x=1", "t"))
    }

    @Test
    fun pageWordsAreEscaped() {
        assertEquals("&lt;b&gt; &amp; &quot;", HomeServer.esc("<b> & \""))
    }
}
