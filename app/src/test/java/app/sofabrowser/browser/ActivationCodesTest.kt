package app.sofabrowser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActivationCodesTest {

    private fun json(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    @Test
    fun codeAfterTheAddress() {
        val a = ActivationCodes.find(json("Sign in on your TV\nGo to youtube.com/activate and enter\nABC-DEFG-HIJ\nThis code expires soon"), "https://www.youtube.com/tv")
        assertEquals("https://youtube.com/activate", a?.address)
        assertEquals("ABC-DEFG-HIJ", a?.code)
    }

    @Test
    fun codeBeforeTheAddress() {
        val a = ActivationCodes.find(json("Your code\nK7P2QX\nEnter it at https://www.netflix.com/tv8 on your phone"), "https://www.netflix.com/login")
        assertEquals("https://www.netflix.com/tv8", a?.address)
        assertEquals("K7P2QX", a?.code)
    }

    @Test
    fun theActivationPageItselfIsNotOffered() {
        assertNull(ActivationCodes.find(json("Enter the code\nAB12CD\nactivate at youtube.com/activate"), "https://www.youtube.com/activate"))
    }

    @Test
    fun ordinaryPagesAreNot() {
        assertNull(ActivationCodes.find(json("Read our guide to the new link at example.com/link-building. Code of conduct applies."), "https://example.org/"))
        assertNull(ActivationCodes.find(json("Go to example.com/activate to find out more"), "https://example.org/"))
        assertNull(ActivationCodes.find("null", null))
    }
}
