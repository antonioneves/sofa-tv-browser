package app.sofabrowser.browser

/**
 * A page that signs the TV in through the phone: it shows a code and says "go to example.com/activate and enter it".
 * The signed-in phone approves the code on the real site, where its password manager works as usual, and the TV's
 * page carries on by itself. Sofa spots these pages from their words and shows the address as a QR code.
 */
object ActivationCodes {

    /** The page's visible text, as a JSON string; the start is enough, the code is near the top. */
    const val TEXT_SCRIPT = "(document.body ? document.body.innerText : '').slice(0, 8000)"

    /** [address] is where the phone goes (with https://); [code] is what it enters there. */
    data class Activation(val address: String, val code: String)

    private val PATHS = "activate|activation|tv8|begin|mytv|tvsignin|tvlogin|link|pair|code|redeem|device"

    private val ADDRESS = Regex(
        "(?<![\\w.@/-])((?:https?://)?(?:[a-z0-9][a-z0-9-]*\\.)+[a-z]{2,}/(?:$PATHS)(?:/[a-z0-9-]{1,32})?)(?![\\w-])",
        RegexOption.IGNORE_CASE,
    )

    /** What the page says around the address: a code is asked for. */
    private val HINT = Regex("\\b(code|pin|enter|activate|activation|sign in|log in|link)\\b", RegexOption.IGNORE_CASE)

    private val CODE_LINE = Regex("^(?:[A-Z0-9]{4,10}|[A-Z0-9]{3,5}(?:-[A-Z0-9]{3,5}){1,3})$")

    private const val REACH = 700

    /** [json] is what [TEXT_SCRIPT] returned; [pageUrl] is the page itself, which is never its own activation page. */
    fun find(json: String?, pageUrl: String?): Activation? {
        val text = unquote(json ?: return null).takeIf { it.isNotBlank() } ?: return null
        val page = host(pageUrl)
        for (m in ADDRESS.findAll(text)) {
            val raw = m.groupValues[1]
            val address = if (raw.startsWith("http", ignoreCase = true)) raw else "https://$raw"
            val host = host(address) ?: continue
            val path = address.substringAfter(host)
            if (host == page && pageUrl?.contains(path, ignoreCase = true) == true) continue
            val before = text.substring((m.range.first - REACH).coerceAtLeast(0), m.range.first)
            val after = text.substring(m.range.last + 1, (m.range.last + 1 + REACH).coerceAtMost(text.length))
            if (!HINT.containsMatchIn(before.takeLast(REACH / 2) + raw + after.take(REACH / 2))) continue
            val code = codeIn(after.lines()) ?: codeIn(before.lines().asReversed()) ?: continue
            return Activation(address, code)
        }
        return null
    }

    /** The first line that looks like a code: a dashed group, or letters and digits with a digit. */
    private fun codeIn(lines: List<String>): String? =
        lines.map { it.trim() }.firstOrNull { CODE_LINE.matches(it) && (it.any(Char::isDigit) || '-' in it) }

    private fun host(url: String?): String? =
        url?.let { runCatching { java.net.URI(it).host }.getOrNull() }?.removePrefix("www.")?.lowercase()

    /** The text of a JSON string as evaluateJavascript returns it, with its escapes undone. */
    private fun unquote(json: String): String {
        if (json.length < 2 || json[0] != '"' || json.last() != '"') return ""
        val out = StringBuilder()
        var i = 1
        while (i < json.length - 1) {
            val c = json[i++]
            if (c != '\\' || i >= json.length - 1) { out.append(c); continue }
            when (val e = json[i++]) {
                'n' -> out.append('\n')
                't' -> out.append('\t')
                'r' -> {}
                'u' -> if (i + 4 <= json.length - 1) { out.append(json.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: '?'); i += 4 }
                else -> out.append(e)
            }
        }
        return out.toString()
    }
}
