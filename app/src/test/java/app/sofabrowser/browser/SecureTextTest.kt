package app.sofabrowser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SecureTextTest {
    private val keyHex = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
    private fun key() = SecureText(ByteArray(32) { Integer.parseInt(keyHex.substring(it * 2, it * 2 + 2), 16).toByte() })
    private val nonce = ByteArray(16) { it.toByte() }

    /** Made by the phone page's own script (run in node), so the two sides are known to agree. */
    @Test fun readsWhatThePhonePageSends() {
        val sent = "55f736d535a08195ba88b26ba6a88bf7.765225087c13a37c70f11e917731c1997835ec2c9e2fd92e08a8c011." +
            "4862441bc0012391bed7fa80467039dfc8a1e29b46e647922bdd1d4e74077d22"
        assertEquals("{\"u\":\"ana\",\"p\":\"pässw0rd!\"}", key().open(sent))
    }

    @Test fun roundTrips() {
        val sealed = SecureText.seal(keyHex, nonce, "a longer text than one block of the stream, 日本語 included!")
        assertEquals("a longer text than one block of the stream, 日本語 included!", key().open(sealed))
    }

    @Test fun textIsNotInTheMessage() {
        assert(!SecureText.seal(keyHex, nonce, "hunter2hunter2").contains("hunter2"))
    }

    @Test fun alteredMessagesAreRefused() {
        val sealed = SecureText.seal(keyHex, nonce, "secret")
        val parts = sealed.split('.')
        val flipped = parts[0] + "." + (if (parts[1][0] == '0') "1" else "0") + parts[1].drop(1) + "." + parts[2]
        assertNull(key().open(flipped))
        assertNull(key().open("garbage"))
        assertNull(key().open("$sealed.00"))
    }

    @Test fun anotherKeyIsRefused() {
        assertNull(SecureText().open(SecureText.seal(keyHex, nonce, "secret")))
    }

    @Test fun eachMessageWorksOnce() {
        val t = key()
        val sealed = SecureText.seal(keyHex, nonce, "secret")
        assertEquals("secret", t.open(sealed))
        assertNull(t.open(sealed))
    }
}
