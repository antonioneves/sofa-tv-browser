package app.sofabrowser.browser

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslatorTest {

    @Test
    fun theTvLanguageMapsToGooglesCodes() {
        assertEquals("pt", Translator.codeFor(Locale.forLanguageTag("pt-PT")))
        assertEquals("en", Translator.codeFor(Locale.US))
        assertEquals("zh-CN", Translator.codeFor(Locale.forLanguageTag("zh-Hans-CN")))
        assertEquals("zh-TW", Translator.codeFor(Locale.forLanguageTag("zh-Hant")))
        assertEquals("zh-TW", Translator.codeFor(Locale.forLanguageTag("zh-HK")))
        assertEquals("iw", Translator.codeFor(Locale.forLanguageTag("he-IL")))
        assertEquals("no", Translator.codeFor(Locale.forLanguageTag("nb-NO")))
        assertEquals("tl", Translator.codeFor(Locale.forLanguageTag("fil")))
    }

    @Test
    fun aLanguageGoogleDoesntOfferHasNoCode() {
        assertNull(Translator.codeFor(Locale.forLanguageTag("tlh")))
    }

    @Test
    fun languagesAreNamedForTheReader() {
        assertEquals("Português", Translator.name("pt", Locale.forLanguageTag("pt")))
        assertEquals("German", Translator.name("de", Locale.ENGLISH))
        assertEquals("Hebrew", Translator.name("iw", Locale.ENGLISH))
        assertEquals("Chinese (Simplified)", Translator.name("zh-CN", Locale.ENGLISH))
    }

    @Test
    fun theScriptAsksForTheLanguage() {
        assertTrue(Translator.script("zh-TW").endsWith("})('zh-TW');"))
    }
}
