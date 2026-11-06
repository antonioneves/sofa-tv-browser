package app.sofabrowser.browser

/**
 * Whether the page shown after a password was sent says the site turned it down ("Incorrect password"). A rejection
 * that shows a page without a sign-in form can't be told by the form coming back, so the words on the page are the
 * other sign; a password the site rejected is not offered for saving.
 */
object SignInRejected {

    /** The page's visible text, as a JSON string; the start is enough, an error comes before the rest of the page. */
    const val TEXT_SCRIPT = "(document.body ? document.body.innerText : '').slice(0, 20000)"

    private val WORDS = Regex(
        "(incorrect|invalid|wrong|unknown|bad|incorrecto|incorrecta|incorreto|incorreta|incorrecte|falsch|falsches|ungültig|inválid[oa]|invalide|ungueltig)" +
            "\\W+(\\w+\\W+){0,2}(password|username|user name|e-?mail|credentials|login|sign.?in|passwort|senha|contraseña|contrasena|mot de passe|usuario|utilisateur|benutzername)" +
            "|(login|sign.?in|log.?in|authentication|anmeldung) (has )?(failed|unsuccessful|error|fehlgeschlagen)" +
            "|(password|username|credentials|senha|contraseña|mot de passe|passwort)\\W+(\\w+\\W+){0,3}(is |are |was |were |was not |is not |isn.t |not |no )?(incorrect|invalid|wrong|not (correct|valid|recognized|recognised|match)|does not match|doesn.t match|incorrecta?|incorreta?|incorrect|falsch|ungültig)" +
            "|(couldn.t|could not|unable to|can.t|cannot) (sign|log) you in" +
            "|(no match|not match|doesn.t match|does not match) (for|with) (that|this|your) (e-?mail|username|password)" +
            "|try (again|a different)",
        RegexOption.IGNORE_CASE,
    )

    /** [json] is what [TEXT_SCRIPT] returned: a JSON string, or "null". */
    fun matches(json: String?): Boolean {
        val text = json?.takeIf { it != "null" } ?: return false
        return WORDS.containsMatchIn(text)
    }
}
