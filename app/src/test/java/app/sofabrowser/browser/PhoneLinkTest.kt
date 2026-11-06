package app.sofabrowser.browser

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneLinkTest {

    private fun head(raw: String) = PhoneLink.readHead(ByteArrayInputStream(raw.toByteArray(Charsets.ISO_8859_1)))

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
    fun formFieldsAreDecoded() {
        assertEquals("cats & dogs", PhoneLink.formField("x=1&t=cats+%26+dogs", "t"))
        assertEquals("https://example.com/?q=ä", PhoneLink.formField("t=https%3A%2F%2Fexample.com%2F%3Fq%3D%C3%A4", "t"))
        assertNull(PhoneLink.formField("x=1", "t"))
    }

    @Test
    fun pageWordsAreEscaped() {
        assertEquals("&lt;b&gt; &amp; &quot;", PhoneLink.esc("<b> & \""))
    }
}
