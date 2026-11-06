package app.sofabrowser.data

/**
 * The parts of a page the user hid from the page menu, as CSS selectors kept for each site (by registrable domain).
 * A site's selectors are stored as one text, one selector a line. A selector may come from a backup file, so only
 * plain ones are kept: no braces, angle brackets, comments or line breaks, which could break out of the one rule
 * each is placed in.
 */
object HiddenElements {

    const val MAX_PER_SITE = 100
    private const val MAX_LENGTH = 400

    fun valid(selector: String) = selector.isNotBlank() && selector.length <= MAX_LENGTH &&
        selector.none { it == '{' || it == '}' || it == '<' || it == '\n' || it == '\r' || it == ';' } &&
        "/*" !in selector && "*/" !in selector

    /** The selectors in one site's stored [text]. */
    fun parse(text: String?): List<String> = text.orEmpty().lineSequence().filter(::valid).distinct().take(MAX_PER_SITE).toList()

    fun join(selectors: List<String>) = selectors.joinToString("\n")

    /** [text] with [selector] added; unchanged when it is already there, or not a plain selector, or the site has too many. */
    fun add(text: String?, selector: String): String {
        val all = parse(text)
        return if (!valid(selector) || selector in all || all.size >= MAX_PER_SITE) join(all) else join(all + selector)
    }

    /** [text] without the selector added last. */
    fun removeLast(text: String?): String = join(parse(text).dropLast(1))

    /** How many parts of the site's pages are hidden. */
    fun count(text: String?) = parse(text).size
}
