package app.sofabrowser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignInPagesTest {

    @Test
    fun httpsAddressesGiveTheirOrigin() {
        assertEquals("https://example.com", SignInPages.origin("https://Example.com/login?next=/"))
        assertEquals("https://example.com", SignInPages.origin("https://example.com:443/"))
        assertEquals("https://example.com:8443", SignInPages.origin("https://example.com:8443/a"))
        assertEquals("https://accounts.example.com", SignInPages.origin("https://accounts.example.com"))
    }

    @Test
    fun nothingButHttpsHasOne() {
        assertNull(SignInPages.origin("http://example.com/login"))
        assertNull(SignInPages.origin("file:///sdcard/a.html"))
        assertNull(SignInPages.origin("javascript:alert(1)"))
        assertNull(SignInPages.origin("not a url"))
        assertNull(SignInPages.origin(null))
    }
}
