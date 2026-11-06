package app.sofabrowser.browser

import java.util.Locale

/**
 * Translates the page in place with Google's website translator, which runs in the page: the page's text goes to
 * Google, and only when the user asks. Sites whose Content-Security-Policy refuses Google's scripts can't be translated.
 */
object Translator {

    /** The languages Google's website translator translates into, by its own codes. */
    val LANGUAGES = listOf(
        "af", "am", "ar", "az", "be", "bg", "bn", "bs", "ca", "cs", "cy", "da", "de", "el", "en", "eo", "es", "et",
        "eu", "fa", "fi", "fr", "ga", "gd", "gl", "gu", "ha", "hi", "hr", "ht", "hu", "hy", "id", "is", "it", "iw",
        "ja", "jw", "ka", "kk", "km", "kn", "ko", "ku", "ky", "la", "lb", "lo", "lt", "lv", "mg", "mi", "mk", "ml",
        "mn", "mr", "ms", "mt", "my", "ne", "nl", "no", "pa", "pl", "pt", "ro", "ru", "si", "sk", "sl", "sm", "so",
        "sq", "sr", "su", "sv", "sw", "ta", "te", "tg", "th", "tl", "tr", "uk", "ur", "uz", "vi", "xh", "yi", "yo",
        "zh-CN", "zh-TW", "zu",
    )

    /** Google's code for [locale]'s language, or null when Google doesn't translate into it. */
    fun codeFor(locale: Locale): String? {
        val code = when (val lang = locale.language) {
            "zh" -> if (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")) "zh-TW" else "zh-CN"
            "he" -> "iw"
            "jv" -> "jw"
            "fil" -> "tl"
            "nb", "nn" -> "no"
            "in" -> "id"
            "ji" -> "yi"
            else -> lang
        }
        return code.takeIf { it in LANGUAGES }
    }

    /** The language of [code], named in [display]'s language, such as "Português" or "Chinese (Simplified)". */
    fun name(code: String, display: Locale): String {
        val tag = when (code) {
            "iw" -> "he"
            "jw" -> "jv"
            "tl" -> "fil"
            "zh-CN" -> "zh-Hans"
            "zh-TW" -> "zh-Hant"
            else -> code
        }
        return Locale.forLanguageTag(tag).getDisplayName(display).replaceFirstChar { it.titlecase(display) }
    }

    /**
     * Loads Google's translator into the page (once) and has it translate into [code]. Its banner, tooltips and
     * highlights are hidden: the menu turns translation off, and the remote's pointer would set the tooltips off.
     */
    fun script(code: String) = """
        (function(lang) {
          if (!document.getElementById('sofa-tr-css')) {
            var css = document.createElement('style');
            css.id = 'sofa-tr-css';
            css.textContent = 'iframe.skiptranslate,.goog-te-banner-frame,#goog-gt-tt,.goog-te-balloon-frame,' +
              '.VIpgJd-ZVi9od-ORHb-OEVmcd,.VIpgJd-yAWNEb-L7lbkb{display:none!important}body{top:0!important}' +
              '.goog-text-highlight,.VIpgJd-yAWNEb-VIpgJd-fmcmS-sn54Q{background:none!important;box-shadow:none!important}';
            (document.head || document.documentElement).appendChild(css);
          }
          function pick() {
            var box = document.querySelector('select.goog-te-combo');
            if (!box || !box.querySelector('option[value="' + lang + '"]')) return false;
            box.value = lang;
            box.dispatchEvent(new Event('change'));
            return true;
          }
          function wait(tries) { if (!pick() && tries > 0) setTimeout(function() { wait(tries - 1); }, 250); }
          if (document.getElementById('sofa-tr')) { wait(60); return; }
          var holder = document.createElement('div');
          holder.id = 'sofa-tr';
          holder.style.display = 'none';
          document.documentElement.appendChild(holder);
          window.sofaTranslateReady = function() {
            new google.translate.TranslateElement({ pageLanguage: 'auto', autoDisplay: false }, 'sofa-tr');
            wait(60);
          };
          var s = document.createElement('script');
          s.src = 'https://translate.google.com/translate_a/element.js?cb=sofaTranslateReady';
          (document.head || document.documentElement).appendChild(s);
        })('$code');
    """.trimIndent()

    /** Forgets the language Google's translator keeps in a cookie, so the site never translates by itself later. */
    private const val FORGET = """
        (function() {
          var gone = '=;expires=Thu, 01 Jan 1970 00:00:00 GMT;path=/';
          var host = location.hostname;
          document.cookie = 'googtrans' + gone;
          document.cookie = 'googtrans' + gone + ';domain=' + host;
          document.cookie = 'googtrans' + gone + ';domain=.' + host.split('.').slice(-2).join('.');
        })();
    """

    /** True once the page is translated, forgetting the cookie then; for polling after [script]. */
    const val DONE = """
        (function() {
          var done = /\btranslated-(ltr|rtl)\b/.test(document.documentElement.className);
          if (done) $FORGET
          return done;
        })()
    """

    /** Run before reloading the page untranslated. */
    const val UNDO = FORGET
}
