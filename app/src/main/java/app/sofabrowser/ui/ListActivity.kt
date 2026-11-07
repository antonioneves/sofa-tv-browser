package app.sofabrowser.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import app.sofabrowser.R
import app.sofabrowser.browser.UrlInput
import app.sofabrowser.data.Db
import app.sofabrowser.data.Favicons
import app.sofabrowser.data.Site
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * History or bookmarks as one focusable list. Returns the chosen URL in [SearchActivity.EXTRA_URL].
 *
 * Only the rows on screen exist as views (a [ListView]), and an icon is looked up when its row is shown, so a list of
 * thousands of imported bookmarks costs about what a screenful does.
 */
class ListActivity : Activity() {

    private val db by lazy { Db.get(this) }
    private val history get() = intent.getStringExtra(EXTRA_MODE) == MODE_HISTORY
    private lateinit var list: ListView
    private lateinit var empty: View
    private val adapter = Rows()

    private sealed interface Item
    private class Header(val label: String) : Item
    private class Entry(val title: String, val url: String, val meta: String?, val bookmark: Boolean, val delete: () -> Unit) : Item

    // Selecting bookmarks to delete several at once: OK ticks a row instead of opening it.
    private var selecting = false
    private val selected = LinkedHashSet<String>()
    private lateinit var selectButton: TextView
    private lateinit var selectAllButton: TextView
    private lateinit var deleteSelectedButton: TextView
    private lateinit var cancelButton: TextView
    private lateinit var deleteAllButton: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.background(false)
            setPadding(116.px, 72.px, 116.px, 0)
            clipToPadding = false
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(Ui.text(this, 56, Ui.p.text, bold = true).apply {
            text = getString(if (history) R.string.history else R.string.bookmarks)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (!history) {
            selectButton = headerButton(R.string.select) { setSelecting(true) }
            selectAllButton = headerButton(R.string.select_all) { selectAll() }
            deleteSelectedButton = headerButton(R.string.delete_selected, danger = true) { confirmDeleteSelected() }
            cancelButton = headerButton(android.R.string.cancel) { setSelecting(false) }
            listOf(selectButton, selectAllButton, deleteSelectedButton, cancelButton).forEach {
                header.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = 16.px })
            }
        }
        deleteAllButton = headerButton(R.string.delete_all, danger = true) { guardHistory { confirmDeleteAll() } }
        header.addView(deleteAllButton)
        root.addView(header)

        // Reaches 24 px into the side margins, with as much padding in the list, so a focused card grows without being cut.
        list = FocusListView(this).apply {
            itemsCanFocus = true
            selector = ColorDrawable(Color.TRANSPARENT)
            divider = ColorDrawable(Color.TRANSPARENT)
            dividerHeight = 16.px
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            setPadding(24.px, 24.px, 24.px, 72.px)
            adapter = this@ListActivity.adapter
        }
        val frame = FrameLayout(this)
        frame.addView(list)
        empty = Ui.text(this, 32, Ui.p.textWeak).apply {
            text = getString(if (history) R.string.history_empty else R.string.bookmarks_empty)
            gravity = Gravity.CENTER
        }
        frame.addView(empty, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            marginStart = -24.px
            marginEnd = -24.px
        })
        setContentView(root)
        load()
    }

    private fun headerButton(label: Int, danger: Boolean = false, click: () -> Unit) = Ui.text(this, 28, if (danger) Color.WHITE else Ui.p.textMid).apply {
        text = getString(label)
        isFocusable = true
        isClickable = true
        gravity = Gravity.CENTER
        // A delete stays red-orange, brighter when focused; the other buttons are tonal, inverted when focused.
        background = if (danger) Ui.fillSelector(Ui.DANGER_IDLE, Ui.DANGER, 12) else Ui.fillSelector(Ui.p.fillFocused, Ui.p.focus, 12)
        setPadding(32.px, 14.px, 32.px, 14.px)
        if (!danger) setOnFocusChangeListener { v, has -> (v as TextView).setTextColor(if (has) Ui.p.onFocus else Ui.p.textMid) }
        setOnClickListener { click() }
    }

    /** Reads the list again; the focus goes to the row at [focusAt] (the first row when null), or the nearest one. */
    private fun load(focusAt: Int? = null) {
        val items = ArrayList<Item>()
        if (history) {
            var lastHeader: String? = null
            val time = DateFormat.getTimeInstance(DateFormat.SHORT)
            db.history().forEach { h ->
                val header = dayLabel(h.visitedAt)
                if (header != lastHeader) {
                    items += Header(header)
                    lastHeader = header
                }
                items += Entry(h.title, h.url, time.format(Date(h.visitedAt)), bookmark = false) { db.removeHistory(h.id) }
            }
        } else {
            // Two sections: the bookmarks in Quick access, in their order, then the rest.
            val pinned = db.pinned()
            val pinnedUrls = pinned.map { it.url }.toSet()
            val rest = db.bookmarks().filter { it.url !in pinnedUrls }
            if (pinned.isNotEmpty()) items += Header(getString(R.string.quick_access))
            pinned.forEach { b -> items += Entry(b.title, b.url, null, bookmark = true) { db.removeBookmark(b.url) } }
            if (rest.isNotEmpty()) items += Header(getString(R.string.bookmarks))
            rest.forEach { b -> items += Entry(b.title, b.url, null, bookmark = true) { db.removeBookmark(b.url) } }
        }
        adapter.items = items
        adapter.notifyDataSetChanged()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        selected.retainAll(items.mapNotNullTo(HashSet()) { (it as? Entry)?.url })
        updateHeader()
        focusRow(focusAt ?: items.indexOfFirst { it is Entry })
    }

    private val entries get() = adapter.items.filterIsInstance<Entry>()

    /**
     * Focus on the row at [position] (or the nearest row after it, else before it), scrolled into sight; [top] keeps it
     * that far from the list's top, as a deleted row's neighbour stays where the deleted row was.
     */
    private fun focusRow(position: Int, top: Int? = null) {
        val items = adapter.items
        if (items.isEmpty() || position < 0) return
        val from = position.coerceAtMost(items.size - 1)
        val at = (from until items.size).firstOrNull { items[it] is Entry } ?: (from downTo 0).firstOrNull { items[it] is Entry } ?: return
        if (top != null) list.setSelectionFromTop(at, top) else list.setSelection(at)
        list.post { list.getChildAt(at - list.firstVisiblePosition)?.findViewWithTag<View>(BODY)?.requestFocus() }
    }

    private fun remove(entry: Entry) = guardHistory {
        val at = adapter.items.indexOf(entry).takeIf { it >= 0 } ?: return@guardHistory
        val top = list.getChildAt(at - list.firstVisiblePosition)?.top
        entry.delete()
        if (entry.bookmark) {
            // Read again, so a section that has run out of rows loses its header.
            load(at)
            return@guardHistory
        }
        val items = ArrayList(adapter.items).apply { removeAt(at) }
        // A day heading with no visit left under it goes too, and what followed moves up one more place.
        var next = at
        if (items.getOrNull(at - 1) is Header && items.getOrNull(at) !is Entry) { items.removeAt(at - 1); next = at - 1 }
        adapter.items = items
        adapter.notifyDataSetChanged()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        focusRow(next, top)
    }

    /** Adding a bookmark to Quick access (or removing it) moves it to the other section, so the list is read again. */
    private fun togglePin(entry: Entry) {
        val at = adapter.items.indexOf(entry)
        if (db.isPinned(entry.url)) db.unpin(entry.url) else db.pin(entry.title, entry.url)
        load(at)
    }

    private fun open(entry: Entry) {
        setResult(RESULT_OK, Intent().putExtra(SearchActivity.EXTRA_URL, entry.url))
        finish()
    }

    private fun showMenu(entry: Entry) {
        val items = mutableListOf(TileMenu.Item(R.drawable.ic_open_in_new, R.string.open) { open(entry) })
        if (entry.bookmark) {
            val pinned = db.isPinned(entry.url)
            items.add(TileMenu.Item(if (pinned) R.drawable.ic_star_filled else R.drawable.ic_star,
                if (pinned) R.string.remove_from_quick_access else R.string.add_to_quick_access) { togglePin(entry) })
        }
        items.add(TileMenu.Item(R.drawable.ic_delete, R.string.delete) { remove(entry) }.danger())
        TileMenu(this, Site(0, entry.title.ifBlank { entry.url }, entry.url), items).show()
    }

    private fun dayLabel(t: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = t }
        val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
        val days = now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)
        return when {
            sameYear && days == 0 -> getString(R.string.today)
            sameYear && days == 1 -> getString(R.string.yesterday)
            else -> DateUtils.formatDateTime(this, t, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY)
        }
    }

    /** The list's rows: headings and entries, each kind made once per visible slot and reused. */
    private inner class Rows : BaseAdapter() {
        var items: List<Item> = emptyList()

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (items[position] is Header) 0 else 1
        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int) = items[position] is Entry

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            when (val item = items[position]) {
                is Header -> ((convertView as? TextView) ?: headerView()).apply { text = item.label }
                is Entry -> ((convertView as? RowView) ?: RowView(this@ListActivity)).apply { bind(item) }
            }

        private fun headerView() = Ui.text(this@ListActivity, 30, Ui.p.textWeak).apply {
            setPadding(0, 20.px, 0, 4.px)
            layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, AbsListView.LayoutParams.WRAP_CONTENT)
        }
    }

    /** One entry: OK opens the page (or ticks it while selecting); holding OK, or Menu, opens its options. */
    private inner class RowView(context: Context) : LinearLayout(context) {
        private var entry: Entry? = null
        private val icon = ImageView(context)
        private val title = Ui.text(context, 34, Ui.p.text).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        private val meta = Ui.text(context, 24, Ui.p.textWeak).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(0, 10.px, 0, 0)
        }
        // While selecting, a checkbox at the end of the card shows whether the bookmark is ticked.
        private val check = ImageView(context).apply { visibility = View.GONE }
        private val body = LinearLayout(context).apply {
            tag = BODY
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            background = Ui.fillSelector(Ui.p.fill, Ui.p.fillFocused, 16)
            foreground = Ui.focusBorder(16)
            setPadding(40.px, 0, 40.px, 0)
            setOnClickListener {
                val e = entry ?: return@setOnClickListener
                if (e.bookmark && selecting) toggle(e.url) else open(e)
            }
            setOnLongClickListener { entry?.let(::showMenu); true }
            setOnKeyListener { _, keyCode, event ->
                if (keyCode != KeyEvent.KEYCODE_MENU) return@setOnKeyListener false
                if (event.action == KeyEvent.ACTION_UP) entry?.let(::showMenu)
                true
            }
            setOnFocusChangeListener { v, has -> Ui.animateScale(v, if (has) 1.02f else 1f, has) }
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, AbsListView.LayoutParams.WRAP_CONTENT)
            body.addView(icon, LayoutParams(64.px, 64.px).apply { marginEnd = 32.px })
            val texts = LinearLayout(context).apply { orientation = VERTICAL }
            texts.addView(title)
            texts.addView(meta)
            body.addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            body.addView(check, LayoutParams(48.px, 48.px).apply { marginStart = 32.px })
            addView(body, LayoutParams(0, 136.px, 1f))
        }

        fun bind(e: Entry) {
            entry = e
            icon.setImageDrawable(Favicons.get(context).drawable(e.url, e.title, 128))
            title.text = e.title.ifBlank { e.url }
            meta.text = listOfNotNull(e.meta, UrlInput.display(e.url)).joinToString("  ·  ")
            showCheck()
            // A reused row starts as an unfocused one.
            if (!body.hasFocus()) { body.scaleX = 1f; body.scaleY = 1f }
        }

        fun showCheck() {
            val e = entry ?: return
            val on = e.url in selected
            check.visibility = if (e.bookmark && selecting) View.VISIBLE else View.GONE
            check.setImageResource(if (on) R.drawable.ic_check_box else R.drawable.ic_check_box_outline_blank)
            check.setColorFilter(if (on) Ui.BRAND else Ui.p.textMid, PorterDuff.Mode.SRC_IN)
        }
    }

    /** A list that keeps the focused row [margin] away from its top and bottom edges, as [FocusScrollView] does. */
    private class FocusListView(context: Context, private val margin: Int = 120.px) : ListView(context) {
        // ListView keeps this much of the next row in sight when the arrows move focus (its "scroll preview").
        override fun getVerticalFadingEdgeLength() = margin
    }

    /** The checkboxes of the rows in sight; the others are drawn right when they come into view. */
    private fun refreshChecks() {
        for (i in 0 until list.childCount) (list.getChildAt(i) as? RowView)?.showCheck()
    }

    private fun setSelecting(on: Boolean) {
        selecting = on
        if (!on) selected.clear()
        refreshChecks()
        updateHeader()
        if (on) focusRow(list.firstVisiblePosition) else selectButton.requestFocus()
    }

    private fun toggle(url: String) {
        if (!selected.add(url)) selected.remove(url)
        refreshChecks()
        updateHeader()
    }

    private fun selectAll() {
        entries.mapTo(selected) { it.url }
        refreshChecks()
        updateHeader()
    }

    /** The buttons of the bookmarks screen: Select and Delete all, or while selecting Select all, Delete selected and Cancel. */
    private fun updateHeader() {
        if (history) return
        fun show(v: View, on: Boolean) { v.visibility = if (on) View.VISIBLE else View.GONE }
        show(selectButton, !selecting && adapter.items.isNotEmpty())
        show(deleteAllButton, !selecting)
        show(selectAllButton, selecting)
        show(deleteSelectedButton, selecting)
        show(cancelButton, selecting)
        deleteSelectedButton.text = getString(R.string.delete_selected, selected.size)
        deleteSelectedButton.alpha = if (selected.isEmpty()) 0.5f else 1f
    }

    private fun confirmDeleteSelected() {
        val n = selected.size
        if (n == 0) return
        Ui.dialog(this)
            .setMessage(resources.getQuantityString(R.plurals.confirm_delete_bookmarks, n, n))
            .setDestructive()
            .setPositiveButton(R.string.delete) { _, _ ->
                selected.forEach { db.removeBookmark(it) }
                selected.clear()
                selecting = false
                load()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // BACK while selecting only stops selecting (TV remotes send BACK as a key; see the manifest).
    @SuppressLint("GestureBackNavigation")
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && selecting) {
            setSelecting(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Deleting History asks for the parental PIN when one is set, once for this visit; bookmarks never do. */
    private fun guardHistory(action: () -> Unit) =
        if (history) PinPad.guard(this, R.string.pin_title_history, stay = true, action = action) else action()

    private fun confirmDeleteAll() {
        Ui.dialog(this)
            .setMessage(if (history) R.string.confirm_clear_history else R.string.confirm_clear_bookmarks)
            .setDestructive()
            .setPositiveButton(R.string.delete_all) { _, _ ->
                if (history) db.clearHistory() else db.clearBookmarks()
                load()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_HISTORY = "history"
        const val MODE_BOOKMARKS = "bookmarks"
        private const val BODY = "body"
    }
}
