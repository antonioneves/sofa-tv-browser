package app.sofabrowser.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInRejectedTest {

    @Test
    fun rejectionMessagesAreRecognised() {
        listOf(
            "\"Incorrect password. Please try again.\"",
            "\"Your password is incorrect\"",
            "\"Invalid username or password\"",
            "\"Wrong email or password\"",
            "\"Login failed\"",
            "\"We couldn't sign you in\"",
            "\"Senha incorreta\"",
            "\"Falsches Passwort\"",
        ).forEach { assertTrue(it, SignInRejected.matches(it)) }
    }

    @Test
    fun ordinaryPagesAreNot() {
        listOf(
            "\"Welcome back, Sam. Your account\"",
            "\"Secure Area. You logged into a secure area!\"",
            "\"Inbox (3) Compose Settings\"",
            "null",
            "",
        ).forEach { assertFalse(it, SignInRejected.matches(it)) }
        assertFalse(SignInRejected.matches(null))
    }
}
