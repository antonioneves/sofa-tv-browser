package app.sofabrowser.data

import app.sofabrowser.adblock.FilterList

/**
 * What a Sofa backup may carry back into the settings, and what is left out. A backup file is untrusted: every
 * setting it names must be a known one, of the right type and with a value the app understands, or it is dropped.
 * Left out on purpose: saved sign-ins and the PIN (tied to this TV's keys), the private space switch, and what
 * belongs to one TV (the chosen video player, filter list files, the old-WebView warning, update check state).
 */
object BackupFile {

    const val VERSION = 1
    private const val MAX_TEXT = 2048

    private enum class T { BOOL, INT, TEXT, SET }

    private val KEYS: Map<String, T> = mapOf(
        "setup_done" to T.BOOL, "theme" to T.TEXT, "search_engine" to T.TEXT, "custom_search" to T.TEXT,
        "search_suggestions" to T.BOOL, "voice_search" to T.BOOL, "menu_expanded" to T.BOOL,
        "translate_language" to T.TEXT, "adblock" to T.BOOL, "auto_update_lists" to T.BOOL,
        "cookies" to T.BOOL, "block_3p_cookies" to T.BOOL, "reject_cookie_banners" to T.BOOL, "send_gpc" to T.BOOL,
        "user_agent" to T.TEXT, "custom_user_agent" to T.TEXT, "back_goes_back" to T.BOOL,
        "start_page_on" to T.BOOL, "start_page" to T.TEXT, "javascript" to T.BOOL, "autoplay" to T.BOOL, "images_last" to T.BOOL,
        "match_frame_rate" to T.BOOL, "sofa_player" to T.BOOL, "open_in_player" to T.BOOL,
        "force_external_player" to T.BOOL, "text_zoom" to T.INT, "cursor_speed" to T.INT, "history_days" to T.INT,
        "clear_on_exit" to T.BOOL, "safe_browsing" to T.BOOL, "dark_pages" to T.BOOL, "most_visited" to T.BOOL,
        "check_updates" to T.BOOL, "auto_watch" to T.TEXT, "drm_mode" to T.TEXT,
        "keys_to_page" to T.SET, "auto_watch_sites" to T.SET, "drm_allowed" to T.SET, "drm_blocked" to T.SET,
    )

    /** The settings Sofa keeps in a backup. */
    val settingKeys: Set<String> get() = KEYS.keys

    private val HOST = Regex("[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?")
    private val UA_MODES = setOf("desktop", "mobile", "webview", "ipad", "tv", "custom")
    private val THEMES = setOf("dark", "light", "system")

    /** [raw] (booleans, numbers, text, collections of text) as the settings it may change: unknown or odd ones are gone. */
    fun cleanSettings(raw: Map<String, Any?>): Map<String, Any> {
        val out = HashMap<String, Any>()
        for ((key, type) in KEYS) {
            val v = raw[key] ?: continue
            val clean: Any? = when (type) {
                T.BOOL -> v as? Boolean
                T.INT -> (v as? Number)?.toInt()?.takeIf { it in -1..1_000_000 }
                T.TEXT -> (v as? String)?.takeIf { it.length <= MAX_TEXT && valid(key, it) }
                T.SET -> (v as? Collection<*>)?.takeIf { it.size <= 5000 }
                    ?.let { c -> c.filterIsInstance<String>().filter { it.length <= 300 }.toSet() }
            }
            if (clean != null) out[key] = clean
        }
        return out
    }

    private fun valid(key: String, v: String) = when (key) {
        "theme" -> v in THEMES
        "user_agent" -> v in UA_MODES
        "start_page" -> v.isEmpty() || webAddress(v)
        "custom_search" -> webAddress(v) && "%s" in v
        else -> true
    }

    private fun webAddress(v: String) = (v.startsWith("https://") || v.startsWith("http://")) && v.none { it.isWhitespace() }

    /** The sites of an ad blocking allowlist that look like host names. */
    fun cleanHosts(raw: Collection<*>): List<String> =
        raw.filterIsInstance<String>().map { it.lowercase() }.filter { HOST.matches(it) }.distinct().take(5000)

    /** Pages (title, address) that are web addresses. */
    fun cleanPages(raw: List<Pair<String, String>>): List<Pair<String, String>> =
        raw.filter { (_, url) -> webAddress(url) && url.length <= 8192 }.map { (t, u) -> t.take(500) to u }

    /** Sites (host names) with their hidden parts of pages kept to the plain selectors [HiddenElements] allows. */
    fun cleanHidden(raw: Map<String, String>): Map<String, String> =
        raw.filterKeys { HOST.matches(it) }.mapValues { HiddenElements.join(HiddenElements.parse(it.value)) }.filterValues { it.isNotEmpty() }

    /** A filter list in a backup: one of the lists every install has ([id]), or one added by its https [url]. */
    data class ListChoice(val id: String?, val url: String?, val enabled: Boolean)

    /** The switches of the lists every install has, and the lists added by an https address (at most [MAX_LISTS]). */
    fun cleanLists(raw: List<ListChoice>): List<ListChoice> {
        val known = raw.filter { it.id in FilterList.defaults().map(FilterList::id) }.distinctBy { it.id }.map { ListChoice(it.id, null, it.enabled) }
        val added = raw.filter { it.id == null || it.id !in known.map { k -> k.id } }
            .filter { it.url != null && it.url.startsWith("https://") && it.url.length <= 2048 && it.url.none(Char::isWhitespace) }
            .distinctBy { it.url }.take(MAX_LISTS).map { ListChoice(null, it.url, it.enabled) }
        return known + added
    }

    private const val MAX_LISTS = 10

    /** A string map (shortcuts, per-site choices) with sane keys and values. */
    fun cleanMap(raw: Map<String, String>): Map<String, String> =
        raw.filter { (k, v) -> k.length in 1..300 && v.length <= 4096 }
}
