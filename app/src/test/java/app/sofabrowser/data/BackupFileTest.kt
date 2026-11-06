package app.sofabrowser.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupFileTest {

    @Test
    fun knownSettingsOfTheRightTypeStay() {
        val clean = BackupFile.cleanSettings(mapOf("adblock" to false, "text_zoom" to 125, "theme" to "light",
            "keys_to_page" to listOf("a.com", 3)))
        assertEquals(false, clean["adblock"])
        assertEquals(125, clean["text_zoom"])
        assertEquals("light", clean["theme"])
        assertEquals(setOf("a.com"), clean["keys_to_page"])
    }

    @Test
    fun unknownOrSensitiveSettingsAreDropped() {
        val clean = BackupFile.cleanSettings(mapOf("save_signins" to true, "private_space" to true,
            "video_player" to "x/y", "whatever" to "1"))
        assertTrue(clean.isEmpty())
    }

    @Test
    fun wrongTypesAndValuesAreDropped() {
        val clean = BackupFile.cleanSettings(mapOf("adblock" to "yes", "text_zoom" to "big", "theme" to "pink",
            "user_agent" to "bot", "start_page" to "javascript:alert(1)", "custom_search" to "https://x.test/?q="))
        assertTrue(clean.isEmpty())
    }

    @Test
    fun startPageAndSearchMustBeWebAddresses() {
        val clean = BackupFile.cleanSettings(mapOf("start_page" to "https://example.com", "custom_search" to "https://x.test/?q=%s"))
        assertEquals(2, clean.size)
    }

    @Test
    fun hostsAndPagesAreChecked() {
        assertEquals(listOf("example.com"), BackupFile.cleanHosts(listOf("Example.com", "a b", "<x>", 5, "example.com")))
        assertFalse(BackupFile.cleanPages(listOf("t" to "file:///etc/passwd", "t" to "javascript:1")).isNotEmpty())
        assertEquals(1, BackupFile.cleanPages(listOf("t" to "https://a.com")).size)
    }

    @Test
    fun filterListsKeepSwitchesAndHttpsAdditions() {
        val got = BackupFile.cleanLists(listOf(
            BackupFile.ListChoice("easylist", null, true),
            BackupFile.ListChoice("easylist", null, false),
            BackupFile.ListChoice("nonsense", null, true),
            BackupFile.ListChoice(null, "https://example.com/list.txt", false),
            BackupFile.ListChoice(null, "http://example.com/plain.txt", true),
            BackupFile.ListChoice(null, "https://exa mple.com/x", true),
        ))
        assertEquals(listOf(
            BackupFile.ListChoice("easylist", null, true),
            BackupFile.ListChoice(null, "https://example.com/list.txt", false),
        ), got)
    }
}
