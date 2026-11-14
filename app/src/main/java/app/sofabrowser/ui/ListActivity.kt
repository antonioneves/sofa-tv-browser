package app.sofabrowser.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Bundle
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.sofabrowser.R
import app.sofabrowser.browser.UrlInput
import app.sofabrowser.data.Db
import app.sofabrowser.data.Favicons
import app.sofabrowser.data.Site
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** History or bookmarks as one focusable list. Returns the chosen URL in [SearchActivity.EXTRA_URL]. */
class ListActivity : Activity() {

    private val db by lazy { Db.get(this) }
    private val history get() = intent.getStringExtra(EXTRA_MODE) == MODE_HISTORY
    private lateinit var list: LinearLayout
    private lateinit var empty: View

    // Selecting bookmarks to delete several at once: OK ticks a row instead of opening it.
    private var selecting = false
    private val selected = LinkedHashSet<String>()
    private val checks = HashMap<String, ImageView>()
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
        deleteAllButton = headerButton(R.string.delete_all, danger = true) { confirmDeleteAll() }
        header.addView(deleteAllButton)
        root.addView(header)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24.px, 24.px, 24.px, 72.px); clipChildren = false; clipToPadding = false }
        // Reaches 24 px into the side margins, with as much padding in the list, so a focused card grows without being cut.
        val scroll = FocusScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            addView(list)
        }
        val frame = FrameLayout(this)
        frame.addView(scroll)
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
        if (!danger) setOnFocusChangeListener { v, has -> (v as android.widget.TextView).setTextColor(if (has) Ui.p.onFocus else Ui.p.textMid) }
        setOnClickListener { click() }
    }

    private fun load() {
        list.removeAllViews()
        checks.clear()
        if (history) {
            var lastHeader: String? = null
            db.history().forEach { h ->
                val header = dayLabel(h.visitedAt)
                if (header != lastHeader) {
                    addHeader(header)
                    lastHeader = header
                }
                addRow(h.title, h.url, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(h.visitedAt))) {
                    db.removeHistory(h.id)
                }
            }
        } else {
            // Two sections: the bookmarks in Quick access, in their order, then the rest.
            val pinned = db.pinned()
            val pinnedUrls = pinned.map { it.url }.toSet()
            val rest = db.bookmarks().filter { it.url !in pinnedUrls }
            if (pinned.isNotEmpty()) addHeader(getString(R.string.quick_access))
            pinned.forEach { b -> addRow(b.title, b.url, null, bookmark = true) { db.removeBookmark(b.url) } }
            if (rest.isNotEmpty()) addHeader(getString(R.string.bookmarks))
            rest.forEach { b -> addRow(b.title, b.url, null, bookmark = true) { db.removeBookmark(b.url) } }
        }
        empty.visibility = if (list.childCount == 0) View.VISIBLE else View.GONE
        selected.retainAll(checks.keys)
        updateHeader()
        list.getChildAt((0 until list.childCount).firstOrNull { list.getChildAt(it) is LinearLayout } ?: 0)
            ?.let { (it as? LinearLayout)?.getChildAt(0)?.requestFocus() }
    }

    private fun addHeader(text: String) {
        list.addView(Ui.text(this, 30, Ui.p.textWeak).apply { this.text = text; setPadding(0, 36.px, 0, 20.px) })
    }

    /** Gives the focus to the row at [index], or the nearest one after it, else before it; headers are skipped. */
    private fun focusRowNear(index: Int) {
        val rows = (0 until list.childCount).filter { list.getChildAt(it) is LinearLayout }
        val at = rows.firstOrNull { it >= index } ?: rows.lastOrNull() ?: return
        (list.getChildAt(at) as LinearLayout).getChildAt(0)?.requestFocus()
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

    private fun addRow(title: String, url: String, meta: String?, bookmark: Boolean = false, delete: () -> Unit) {
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        val open = {
            setResult(RESULT_OK, Intent().putExtra(SearchActivity.EXTRA_URL, url))
            finish()
        }
        val remove = {
            delete()
            val i = list.indexOfChild(line)
            if (bookmark) {
                // Rebuilt, so a section that has run out of rows loses its header.
                load()
                focusRowNear(i)
            } else {
                list.removeView(line)
                (list.getChildAt(i) as? LinearLayout ?: list.getChildAt(i - 1) as? LinearLayout)?.getChildAt(0)?.requestFocus()
                empty.visibility = if (list.childCount == 0) View.VISIBLE else View.GONE
            }
        }
        // Adding a bookmark to Quick access (or removing it) moves it to the other section, so the list is rebuilt.
        val togglePin = {
            val i = list.indexOfChild(line)
            if (db.isPinned(url)) db.unpin(url) else db.pin(title, url)
            load()
            focusRowNear(i)
        }
        val menu = {
            val items = mutableListOf(TileMenu.Item(R.drawable.ic_open_in_new, R.string.open, open))
            if (bookmark) {
                val pinned = db.isPinned(url)
                items.add(TileMenu.Item(if (pinned) R.drawable.ic_star_filled else R.drawable.ic_star,
                    if (pinned) R.string.remove_from_quick_access else R.string.add_to_quick_access, togglePin))
            }
            items.add(TileMenu.Item(R.drawable.ic_delete, R.string.delete, remove).danger())
            TileMenu(this, Site(0, title.ifBlank { url }, url), items).show()
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            background = Ui.fillSelector(Ui.p.fill, Ui.p.fillFocused, 16)
            foreground = Ui.focusBorder(16)
            setPadding(40.px, 0, 40.px, 0)
            setOnClickListener { if (bookmark && selecting) toggle(url) else open() }
            // Holding OK, or Menu, opens the card's options.
            setOnLongClickListener { menu(); true }
            setOnKeyListener { _, keyCode, event ->
                if (keyCode != KeyEvent.KEYCODE_MENU) return@setOnKeyListener false
                if (event.action == KeyEvent.ACTION_UP) menu()
                true
            }
            setOnFocusChangeListener { v, has -> Ui.animateScale(v, if (has) 1.02f else 1f, has) }
        }
        body.addView(ImageView(this).apply { setImageDrawable(Favicons.get(this@ListActivity).drawable(url, title, 128)) },
            LinearLayout.LayoutParams(64.px, 64.px).apply { marginEnd = 32.px })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(Ui.text(this, 34, Ui.p.text).apply { text = title.ifBlank { url }; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        texts.addView(Ui.text(this, 24, Ui.p.textWeak).apply {
            text = listOfNotNull(meta, UrlInput.display(url)).joinToString("  ·  ")
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(0, 10.px, 0, 0)
        })
        body.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        // While selecting, a checkbox at the end of the card shows whether the bookmark is ticked.
        if (bookmark) {
            val box = ImageView(this).apply { visibility = View.GONE }
            checks[url] = box
            body.addView(box, LinearLayout.LayoutParams(48.px, 48.px).apply { marginStart = 32.px })
            updateCheck(url)
        }
        line.addView(body, LinearLayout.LayoutParams(0, 136.px, 1f))
        list.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 16.px
        })
    }

    private fun setSelecting(on: Boolean) {
        selecting = on
        if (!on) selected.clear()
        checks.keys.forEach(::updateCheck)
        updateHeader()
        if (on) focusRowNear(0) else selectButton.requestFocus()
    }

    private fun toggle(url: String) {
        if (!selected.add(url)) selected.remove(url)
        updateCheck(url)
        updateHeader()
    }

    private fun selectAll() {
        selected.addAll(checks.keys)
        checks.keys.forEach(::updateCheck)
        updateHeader()
    }

    private fun updateCheck(url: String) {
        val box = checks[url] ?: return
        val on = url in selected
        box.visibility = if (selecting) View.VISIBLE else View.GONE
        box.setImageResource(if (on) R.drawable.ic_check_box else R.drawable.ic_check_box_outline_blank)
        box.setColorFilter(if (on) Ui.BRAND else Ui.p.textMid, PorterDuff.Mode.SRC_IN)
    }

    /** The buttons of the bookmarks screen: Select and Delete all, or while selecting Select all, Delete selected and Cancel. */
    private fun updateHeader() {
        if (history) return
        fun show(v: View, on: Boolean) { v.visibility = if (on) View.VISIBLE else View.GONE }
        show(selectButton, !selecting && list.childCount > 0)
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

    private fun confirmDeleteAll() {
        Ui.dialog(this)
            .setMessage(if (history) R.string.confirm_clear_history else R.string.confirm_clear_bookmarks)
            .setDestructive()
            .setPositiveButton(R.string.delete_all) { _, _ ->
                if (history) db.clearHistory() else db.bookmarks().forEach { db.removeBookmark(it.url) }
                load()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_HISTORY = "history"
        const val MODE_BOOKMARKS = "bookmarks"
    }
}
