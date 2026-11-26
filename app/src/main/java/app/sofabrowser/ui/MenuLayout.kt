package app.sofabrowser.ui

import app.sofabrowser.R

/**
 * The order of the actions behind the browse menu's More button (the sidebar's balloon, the row under the bottom
 * bar) and which of them are shown, as chosen in Settings > Menu buttons. The bar's own buttons (Home, the dock
 * button, Reload, Back, Forward, the tabs) always stay where they are.
 *
 * Stored as one setting ([encode]): button ids in order, separated by commas, a hidden one prefixed with "-", for
 * example `watch,zoom,-find`. "" is the default menu, so a user who never changed it gets a later version's default.
 * The ids never change, so a stored or exported layout stays readable: unknown ids are skipped, and a button added
 * in a later version shows up at its default place.
 */
class MenuLayout private constructor(val entries: List<Entry>) {

    /** A movable button, named as its [BrowserMenu.Action]. [ZOOM] is the zoom group (out, the percentage, in), moved as one. */
    enum class Item(val id: String, val icon: Int, val label: Int) {
        ZOOM("zoom", R.drawable.ic_zoom_in, R.string.zoom),
        // Watch is the action a TV is most often used for: first of the actions.
        WATCH("watch", R.drawable.ic_play_circle, R.string.watch),
        // Kept per site; never shown in private browsing, which saves none.
        AUTO_WATCH("auto_watch", R.drawable.ic_autoplay, R.string.auto_watch),
        FIND("find", R.drawable.ic_find_in_page, R.string.find_in_page),
        TRANSLATE("translate", R.drawable.ic_translate, R.string.translate),
        READER("reader", R.drawable.ic_article, R.string.reader_view),
        HIDE_PARTS("hide_parts", R.drawable.ic_visibility_off, R.string.hide_parts),
        BOOKMARK("bookmark", R.drawable.ic_star, R.string.bookmark),
        PIN("pin", R.drawable.ic_add, R.string.add_to_quick_access),
        // Never shown in the private space.
        TV_SHORTCUT("tv_shortcut", R.drawable.ic_tv, R.string.tv_shortcut),
        CLOSE_TAB("close_tab", R.drawable.ic_close, R.string.close_tab),
        BOOKMARKS("bookmarks", R.drawable.ic_bookmarks, R.string.bookmarks),
        HISTORY("history", R.drawable.ic_history, R.string.history),
        DOWNLOADS("downloads", R.drawable.ic_download, R.string.downloads),
        // "Allow once" (shown only after a block) always follows it.
        ADBLOCK("adblock", R.drawable.ic_shield, R.string.adblock_site),
        PHONE_SIGNIN("phone_signin", R.drawable.ic_smartphone, R.string.activation_title),
        USER_AGENT("user_agent", R.drawable.ic_desktop_windows, R.string.user_agent),
        CLEAR_SITE("clear_site", R.drawable.ic_delete, R.string.clear_site_data),
        SCROLL("scroll", R.drawable.ic_swap_vert, R.string.scroll_mode),
        POINTER("pointer", R.drawable.ic_mouse, R.string.pointer),
        SETTINGS("settings", R.drawable.ic_settings, R.string.settings);

        /** Can't be hidden: without Settings there would be no way back to this choice. */
        val locked get() = this == SETTINGS
    }

    data class Entry(val item: Item, val shown: Boolean)

    val shown: List<Item> get() = entries.filter { it.shown }.map { it.item }
    val isDefault get() = entries == DEFAULT.entries
    val hiddenCount get() = entries.count { !it.shown }

    /** [from] moved to [to] (indexes in [entries]); out-of-range moves change nothing. */
    fun move(from: Int, to: Int): MenuLayout {
        if (from !in entries.indices || to !in entries.indices || from == to) return this
        return MenuLayout(entries.toMutableList().apply { add(to, removeAt(from)) })
    }

    /** The button at [index] shown or hidden; a [Item.locked] one stays shown. */
    fun show(index: Int, on: Boolean): MenuLayout {
        val e = entries.getOrNull(index) ?: return this
        if (e.item.locked || e.shown == on) return this
        return MenuLayout(entries.toMutableList().apply { set(index, e.copy(shown = on)) })
    }

    fun encode(): String = if (isDefault) "" else entries.joinToString(",") { (if (it.shown) "" else "-") + it.item.id }

    override fun equals(other: Any?) = other is MenuLayout && other.entries == entries
    override fun hashCode() = entries.hashCode()

    companion object {
        val DEFAULT = MenuLayout(Item.entries.map { Entry(it, true) })

        fun decode(stored: String?): MenuLayout {
            if (stored.isNullOrBlank()) return DEFAULT
            val list = ArrayList<Entry>()
            for (raw in stored.split(',')) {
                val token = raw.trim()
                val hidden = token.startsWith("-")
                val item = Item.entries.firstOrNull { it.id == token.removePrefix("-") } ?: continue
                if (list.none { it.item == item }) list.add(Entry(item, !hidden || item.locked))
            }
            // A button this layout doesn't name (new in this version) goes after the one before it in the default order.
            for (item in Item.entries) {
                if (list.any { it.item == item }) continue
                val before = Item.entries.subList(0, item.ordinal).asReversed().firstOrNull { b -> list.any { it.item == b } }
                list.add(if (before == null) 0 else list.indexOfFirst { it.item == before } + 1, Entry(item, true))
            }
            return MenuLayout(list)
        }
    }
}
