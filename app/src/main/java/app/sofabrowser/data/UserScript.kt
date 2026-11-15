package app.sofabrowser.data

/**
 * A Greasemonkey-style user script: its `// ==UserScript==` header, read the way the Greasemonkey and Tampermonkey
 * documentation describe it, and its source. [parse] refuses scripts that need what Sofa doesn't offer, with the reason,
 * so nothing is installed half working (see docs/user-scripts.md).
 */
class UserScript private constructor(
    val name: String,
    val namespace: String,
    val version: String,
    val description: String,
    /** The `@match` and `@include` lines, as written. */
    val sites: List<String>,
    val includes: List<Pattern>,
    val excludes: List<Pattern>,
    val runAt: RunAt,
    val noFrames: Boolean,
    /** What the script asks for besides plain page access, as written (`none` left out). */
    val grants: List<String>,
    val updateUrl: String?,
    val downloadUrl: String?,
    val source: String,
) {
    enum class RunAt(val header: String) { START("document-start"), END("document-end"), IDLE("document-idle") }

    /** A regular expression in the syntax JavaScript and Java share, tested against the page address without its #part. */
    data class Pattern(val regex: String, val ignoreCase: Boolean = false) {
        private val compiled by lazy { Regex(regex, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()) }
        fun matches(url: String) = compiled.containsMatchIn(url)
    }

    sealed class Parsed {
        class Ok(val script: UserScript) : Parsed()
        class Refused(val reason: Reason, val detail: String = "") : Parsed()
    }

    enum class Reason { NOT_A_SCRIPT, TOO_LARGE, NO_NAME, NO_SITES, BAD_SITE, GRANT, REQUIRE, RESOURCE, CONNECT, RUN_AT }

    /** Who installed it twice is the same script: a new version replaces the old one and keeps its values. */
    val identity get() = "$namespace\u0000$name"

    /** Whether it runs on [url] (an http or https address). */
    fun runsOn(url: String): Boolean {
        val u = url.substringBefore('#')
        if (!u.startsWith("http://", ignoreCase = true) && !u.startsWith("https://", ignoreCase = true)) return false
        return includes.any { it.matches(u) } && excludes.none { it.matches(u) }
    }

    /** True when it runs on every site, or on any site at all a pattern can't narrow down, which the user should see first. */
    val allSites: Boolean get() = includes.any { p -> PROBES.all { p.matches(it) } }

    /** The sites in a few words each, for the screens that list them: hosts where the pattern names one. */
    fun siteNames(): List<String> = if (allSites) emptyList() else sites.map(::siteName).distinct()

    /** Whether [newer] asks for more than this: more sites, or more help. */
    fun newerAsksMore(newer: UserScript) = !newer.sites.all { it in sites } || !newer.grants.all { it in grants } ||
        (newer.allSites && !allSites)

    companion object {
        const val MAX_BYTES = 1 shl 20

        /** What a script may ask for: storage, styles and its own details (`none` asks for nothing). */
        val ALLOWED_GRANTS = setOf(
            "none", "GM_addStyle", "GM.addStyle", "GM_getValue", "GM.getValue", "GM_setValue", "GM.setValue",
            "GM_deleteValue", "GM.deleteValue", "GM_listValues", "GM.listValues", "GM_info", "GM.info",
        )

        private val PROBES = listOf("https://a.example/", "http://b.test/x/y?z")
        private val HEADER_LINE = Regex("""^//\s*@(\S+)(?:\s+(.*?))?\s*$""")
        private val MATCH = Regex("""^(\*|https?)://([^/]*)(/.*)$""")

        fun parse(source: String): Parsed {
            if (source.length > MAX_BYTES || source.toByteArray().size > MAX_BYTES) return Parsed.Refused(Reason.TOO_LARGE)
            val lines = source.lineSequence().map { it.trim() }.toList()
            val start = lines.indexOfFirst { it.matches(Regex("""//\s*==UserScript==""")) }
            val end = lines.indexOfFirst { it.matches(Regex("""//\s*==/UserScript==""")) }
            if (start < 0 || end <= start) return Parsed.Refused(Reason.NOT_A_SCRIPT)
            val fields = lines.subList(start + 1, end).mapNotNull { HEADER_LINE.find(it) }
                .map { it.groupValues[1] to it.groupValues[2] }
            fun first(key: String) = fields.firstOrNull { it.first == key }?.second?.takeIf { it.isNotBlank() }
            fun all(key: String) = fields.filter { it.first == key }.map { it.second }.filter { it.isNotBlank() }

            val name = first("name") ?: return Parsed.Refused(Reason.NO_NAME)
            all("require").firstOrNull()?.let { return Parsed.Refused(Reason.REQUIRE, it) }
            all("resource").firstOrNull()?.let { return Parsed.Refused(Reason.RESOURCE, it) }
            all("connect").firstOrNull()?.let { return Parsed.Refused(Reason.CONNECT, it) }
            val grants = all("grant").flatMap { it.split(Regex("\\s+")) }.filter { it.isNotEmpty() }
            grants.firstOrNull { it !in ALLOWED_GRANTS }?.let { return Parsed.Refused(Reason.GRANT, it) }
            val runAt = when (val r = first("run-at") ?: "document-idle") {
                "document-start" -> RunAt.START
                "document-end", "document-body" -> RunAt.END
                "document-idle" -> RunAt.IDLE
                else -> return Parsed.Refused(Reason.RUN_AT, r)
            }

            val matches = all("match")
            val includes = all("include")
            if (matches.isEmpty() && includes.isEmpty()) return Parsed.Refused(Reason.NO_SITES)
            val inc = mutableListOf<Pattern>()
            for (m in matches) inc += matchPattern(m) ?: return Parsed.Refused(Reason.BAD_SITE, m)
            for (i in includes) inc += includePattern(i) ?: return Parsed.Refused(Reason.BAD_SITE, i)
            val exc = mutableListOf<Pattern>()
            // An exclusion Sofa can't read would run the script where it was meant not to: refused too.
            for (m in all("exclude-match")) exc += matchPattern(m) ?: return Parsed.Refused(Reason.BAD_SITE, m)
            for (e in all("exclude")) exc += includePattern(e) ?: return Parsed.Refused(Reason.BAD_SITE, e)

            return Parsed.Ok(UserScript(
                name = name,
                namespace = first("namespace").orEmpty(),
                version = first("version").orEmpty(),
                description = first("description").orEmpty(),
                sites = matches + includes,
                includes = inc,
                excludes = exc,
                runAt = runAt,
                noFrames = fields.any { it.first == "noframes" },
                grants = grants.filter { it != "none" }.distinct(),
                updateUrl = first("updateURL"),
                downloadUrl = first("downloadURL"),
                source = source,
            ))
        }

        /** A Chrome match pattern, for http and https: `<all_urls>`, or a scheme, host and path where `*` is a wildcard. */
        fun matchPattern(p: String): Pattern? {
            if (p == "<all_urls>") return Pattern("^https?://")
            val m = MATCH.find(p) ?: return null
            val (scheme, host, path) = m.destructured
            val s = if (scheme == "*") "https?" else scheme
            val h = when {
                host == "*" -> "[^/]+"
                host.startsWith("*.") && '*' !in host.substring(2) && host.length > 2 -> "(?:[^/]+\\.)?" + escape(host.substring(2).lowercase())
                '*' in host || host.isEmpty() -> return null
                else -> escape(host.lowercase())
            }
            val port = if (':' in host) "" else "(?::\\d+)?"
            return Pattern("^$s://$h$port${glob(path)}$")
        }

        /** An `@include` or `@exclude`: a glob where `*` is anything and `.tld` any top-level domain, or `/a regex/`. */
        fun includePattern(p: String): Pattern? {
            if (p.length > 2 && p.startsWith("/")) {
                val close = p.lastIndexOf('/')
                val flags = p.substring(close + 1)
                if (close > 0 && flags.all { it in "gimsuy" }) {
                    val body = p.substring(1, close)
                    if (runCatching { Regex(body) }.isFailure) return null
                    return Pattern(body, ignoreCase = 'i' in flags)
                }
            }
            if (p == "*") return Pattern("^")
            return Pattern("^" + glob(p).replace("\\.tld", "\\.(?:[a-z]{2,}|[a-z]{2,3}\\.[a-z]{2})") + "$")
        }

        private fun glob(s: String) = s.split('*').joinToString(".*") { escape(it) }

        private fun escape(s: String) = buildString { for (c in s) { if (c in "\\^$.|?*+()[]{}/") append('\\'); append(c) } }

        /** The host a site line names, without `*.` and the scheme, or the line itself when it names none. */
        fun siteName(line: String): String {
            val host = Regex("""^(?:\*|https?|http\*)://([^/]+)""").find(line)?.groupValues?.get(1) ?: return line
            val h = host.removePrefix("*.")
            return if ('*' in h || h.isEmpty()) line else h
        }
    }
}
