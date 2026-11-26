package app.sofabrowser.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import app.sofabrowser.R
import app.sofabrowser.browser.UrlInput
import app.sofabrowser.data.Db
import app.sofabrowser.data.Favicons
import app.sofabrowser.data.FolderTree
import app.sofabrowser.data.Site
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * History or bookmarks as one focusable list. Returns the chosen URL in [SearchActivity.EXTRA_URL].
 *
 * Bookmarks show one folder at a time: at the top level Quick access, then the folders A to Z and the bookmarks there;
 * in a folder an "Up to…" row, its folders and its bookmarks. Back goes back through the folders visited first.
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
    private class FolderItem(val id: Long, val title: String, val detail: String) : Item
    private class Up(val to: Long?, val label: String) : Item

    // The folder shown (null: the top level), and the ones visited before it, which Back returns to.
    private var folder: Long? = null
    private val trail = ArrayList<Long?>()
    private var tree = FolderTree(emptyList())
    private lateinit var titleView: TextView
    private lateinit var newFolderButton: TextView
    private lateinit var moveSelectedButton: TextView
    private lateinit var deleteFolderButton: TextView

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
        if (!history) {
            folder = savedInstanceState?.takeIf { it.containsKey(STATE_FOLDER) }?.getLong(STATE_FOLDER)
                ?: intent.takeIf { it.hasExtra(EXTRA_FOLDER) }?.getLongExtra(EXTRA_FOLDER, 0)
            savedInstanceState?.getLongArray(STATE_TRAIL)?.forEach { trail += it.takeIf { it != TOP } }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.background(false)
            setPadding(116.px, 72.px, 116.px, 0)
            clipToPadding = false
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleView = Ui.text(this, 56, Ui.p.text, bold = true).apply {
            text = getString(if (history) R.string.history else R.string.bookmarks)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
        }
        header.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 32.px })
        if (!history) {
            newFolderButton = headerButton(R.string.folder_new) { newFolder() }
            selectButton = headerButton(R.string.select) { setSelecting(true) }
            selectAllButton = headerButton(R.string.select_all) { selectAll() }
            moveSelectedButton = headerButton(R.string.move_selected) { moveSelected() }
            deleteSelectedButton = headerButton(R.string.delete_selected, danger = true) { confirmDeleteSelected() }
            cancelButton = headerButton(android.R.string.cancel) { setSelecting(false) }
            deleteFolderButton = headerButton(R.string.folder_delete_this, danger = true) { folder?.let(::deleteFolder) }
            listOf(newFolderButton, selectButton, selectAllButton, moveSelectedButton, deleteSelectedButton, cancelButton, deleteFolderButton).forEach {
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
            tree = db.folderTree()
            // A folder deleted meanwhile (from the start page's menu, or a restore) shows the top level.
            if (folder != null && tree[folder] == null) { folder = null; trail.clear() }
            val counts = db.bookmarkCounts()
            fun bookmark(b: Site) = Entry(b.title, b.url, null, bookmark = true) { db.removeBookmark(b.url) }
            val folders = tree.children(folder).map {
                FolderItem(it.id, it.title, FolderText.contents(this, tree.children(it.id).size, counts[it.id] ?: 0))
            }
            val here = folder
            if (here == null) {
                // Two sections: the bookmarks in Quick access, in their order, then the folders and the rest.
                val pinned = db.pinned()
                val pinnedUrls = pinned.map { it.url }.toSet()
                val rest = db.bookmarksIn(null).filter { it.url !in pinnedUrls }
                if (pinned.isNotEmpty()) items += Header(getString(R.string.quick_access))
                pinned.forEach { items += bookmark(it) }
                if (folders.isNotEmpty() || rest.isNotEmpty()) items += Header(getString(R.string.bookmarks))
                items += folders
                rest.forEach { items += bookmark(it) }
            } else {
                val parent = tree[here]?.parent
                items += Up(parent, getString(R.string.folder_up, tree[parent]?.title ?: getString(R.string.bookmarks)))
                items += folders
                db.bookmarksIn(here).forEach { items += bookmark(it) }
                if (items.size == 1) items += Header(getString(R.string.folder_empty))
            }
            titleView.text = (listOf(getString(R.string.bookmarks)) + tree.pathNames(folder)).joinToString("  ›  ")
        }
        adapter.items = items
        adapter.notifyDataSetChanged()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        selected.retainAll(items.mapNotNullTo(HashSet()) { (it as? Entry)?.url })
        updateHeader()
        focusRow(focusAt ?: items.indexOfFirst { it !is Header && it !is Up }.takeIf { it >= 0 } ?: 0)
    }

    private val entries get() = adapter.items.filterIsInstance<Entry>()

    /** Shows folder [to]; [back] when Back returned there, else the folder left is remembered for Back. */
    private fun go(to: Long?, back: Boolean = false) {
        val from = folder
        if (!back) trail += from
        folder = to
        if (selecting) { selecting = false; selected.clear() }
        load()
        // Back in a folder's parent, the folder that was left keeps the focus.
        val at = adapter.items.indexOfFirst { it is FolderItem && it.id == from }
        if (from != null && at >= 0) focusRow(at)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        folder?.let { outState.putLong(STATE_FOLDER, it) }
        outState.putLongArray(STATE_TRAIL, trail.map { it ?: TOP }.toLongArray())
    }

    /**
     * Focus on the row at [position] (or the nearest row after it, else before it), scrolled into sight; [top] keeps it
     * that far from the list's top, as a deleted row's neighbour stays where the deleted row was.
     */
    private fun focusRow(position: Int, top: Int? = null) {
        val items = adapter.items
        if (items.isEmpty() || position < 0) return
        val from = position.coerceAtMost(items.size - 1)
        val at = (from until items.size).firstOrNull { items[it] !is Header } ?: (from downTo 0).firstOrNull { items[it] !is Header } ?: return
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
            items.add(TileMenu.Item(R.drawable.ic_move, R.string.move) { moveBookmarks(listOf(entry.url), entry.title.ifBlank { entry.url }) })
        }
        items.add(TileMenu.Item(R.drawable.ic_delete, R.string.delete) {
            if (entry.bookmark) confirmDeleteBookmark { remove(entry) } else remove(entry)
        }.danger())
        TileMenu(this, Site(0, entry.title.ifBlank { entry.url }, entry.url), items).show()
    }

    private fun showMenu(f: FolderItem) {
        TileMenu(this, Site(0, f.title, ""), listOf(
            TileMenu.Item(R.drawable.ic_folder, R.string.folder_open) { go(f.id) },
            TileMenu.Item(R.drawable.ic_edit, R.string.folder_rename) { renameFolder(f.id) },
            TileMenu.Item(R.drawable.ic_move, R.string.move) { moveFolder(f.id) },
            TileMenu.Item(R.drawable.ic_delete, R.string.delete) { deleteFolder(f.id) }.danger(),
        )).show()
    }

    // Folders

    /** A one-line name field in a dialog, OK saving it through [save], which says whether the name was taken. */
    private fun askName(title: Int, current: String, save: (String) -> Boolean) {
        val field = EditText(this).apply {
            setText(current)
            setSelection(text.length)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val box = FrameLayout(this).apply { setPadding(0, 8.px, 0, 8.px); addView(field) }
        Ui.dialog(this).setTitle(title).setView(box)
            .setPositiveButton(R.string.folder_save) { _, _ ->
                val name = FolderTree.cleanName(field.text.toString())
                if (name.isNotEmpty() && !save(name)) toast(getString(R.string.folder_name_taken, name))
            }
            .setNegativeButton(android.R.string.cancel, null).show()
        // The name first: OK on it brings up the keyboard.
        field.requestFocus()
    }

    private fun newFolder() {
        val parent = folder
        if (!tree.canHold(parent)) return tooDeep()
        askName(R.string.folder_new, "") { name ->
            if (db.folderTree().find(parent, name) != null) return@askName false
            val id = db.addFolder(parent, name)
            load()
            focusRow(adapter.items.indexOfFirst { it is FolderItem && it.id == id })
            true
        }
    }

    private fun renameFolder(id: Long) {
        val f = tree[id] ?: return
        askName(R.string.folder_rename_title, f.title) { name ->
            val other = db.folderTree().find(f.parent, name)
            if (other != null && other.id != id) return@askName false
            db.renameFolder(id, name)
            load()
            focusRow(adapter.items.indexOfFirst { it is FolderItem && it.id == id })
            true
        }
    }

    /**
     * Asks where [what] goes: the top level or any folder, indented under its parent. Only the places it can go are
     * offered, so not where it is now, and for a folder not itself, a folder inside it, one that already has a folder
     * of that name, or one too deep ([allowed] says which). When there is none, says so instead. [move] moves it.
     */
    private fun pickFolder(what: String, now: Long?, allowed: (Long?) -> Boolean, move: (Long?) -> Unit) {
        val places = listOf<Pair<Long?, String>>(null to getString(R.string.folder_top)) +
            tree.outline().map { (f, depth) -> f.id to "    ".repeat(depth) + f.title }
        val offered = places.filter { (id, _) -> id != now && allowed(id) }
        if (offered.isEmpty()) return toast(getString(R.string.folder_no_place))
        Ui.dialog(this).setTitle(getString(R.string.folder_move_title, what))
            .setSingleChoiceItems(offered.map { it.second }.toTypedArray(), -1) { d, i ->
                d.dismiss()
                val to = offered[i].first
                move(to)
                toast(getString(R.string.folder_moved_to, tree[to]?.title ?: getString(R.string.bookmarks)))
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun moveBookmarks(urls: List<String>, what: String) {
        pickFolder(what, folder, { true }) { to ->
            val at = adapter.items.indexOfFirst { it is Entry && it.url == urls.first() }
            urls.forEach { db.moveBookmark(it, to) }
            selecting = false
            selected.clear()
            load(at.takeIf { it >= 0 })
        }
    }

    private fun moveSelected() {
        if (selected.isEmpty()) return
        val urls = selected.toList()
        moveBookmarks(urls, if (urls.size == 1) entries.firstOrNull { it.url == urls[0] }?.title ?: urls[0]
            else resources.getQuantityString(R.plurals.bookmark_count, urls.size, urls.size))
    }

    private fun moveFolder(id: Long) {
        val f = tree[id] ?: return
        pickFolder(f.title, f.parent, { tree.canMove(id, it) && db.folderTree().find(it, f.title) == null }) { to ->
            val at = adapter.items.indexOfFirst { it is FolderItem && it.id == id }
            db.moveFolder(id, to)
            load(at.takeIf { it >= 0 })
        }
    }

    /** Deletes folder [id]; when anything is in it, asks first, with Cancel focused. */
    private fun deleteFolder(id: Long) {
        val f = tree[id] ?: return
        val inside = tree.subtree(id)
        val counts = db.bookmarkCounts()
        val folders = inside.size - 1
        val bookmarks = inside.sumOf { counts[it] ?: 0 }
        val delete = {
            val at = adapter.items.indexOfFirst { it is FolderItem && it.id == id }
            db.deleteFolder(id)
            toast(getString(R.string.folder_deleted))
            if (folder != null && folder in inside) go(f.parent, back = true) else load(at.takeIf { it >= 0 })
        }
        if (folders == 0 && bookmarks == 0) { delete(); return }
        val d = Ui.dialog(this)
            .setMessage(getString(R.string.folder_delete_confirm, f.title, FolderText.deleted(this, folders, bookmarks)))
            .setDestructive()
            .setPositiveButton(R.string.delete) { _, _ -> delete() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        d.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)?.requestFocus()
    }

    private fun tooDeep() = toast(resources.getQuantityString(R.plurals.folder_too_deep, FolderTree.MAX_DEPTH, FolderTree.MAX_DEPTH))

    private fun toast(text: String) = SofaToast.makeText(this, text, Toast.LENGTH_SHORT).show()

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
        override fun isEnabled(position: Int) = items[position] !is Header

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            when (val item = items[position]) {
                is Header -> ((convertView as? TextView) ?: headerView()).apply { text = item.label }
                else -> ((convertView as? RowView) ?: RowView(this@ListActivity)).apply { bind(item) }
            }

        private fun headerView() = Ui.text(this@ListActivity, 30, Ui.p.textWeak).apply {
            setPadding(0, 20.px, 0, 4.px)
            layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, AbsListView.LayoutParams.WRAP_CONTENT)
        }
    }

    /**
     * One entry: OK opens the page (or ticks it while selecting); holding OK, or Menu, opens its options. A folder's row
     * opens the folder, and the Up row its parent.
     */
    private inner class RowView(context: Context) : LinearLayout(context) {
        private var item: Item? = null
        private val entry get() = item as? Entry
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
                when (val i = item) {
                    is Entry -> if (i.bookmark && selecting) toggle(i.url) else open(i)
                    is FolderItem -> go(i.id)
                    is Up -> go(i.to)
                    else -> Unit
                }
            }
            setOnLongClickListener { menu(); true }
            setOnKeyListener { _, keyCode, event ->
                if (keyCode != KeyEvent.KEYCODE_MENU) return@setOnKeyListener false
                if (event.action == KeyEvent.ACTION_UP) menu()
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

        private fun menu() {
            when (val i = item) {
                is Entry -> showMenu(i)
                is FolderItem -> if (!selecting) showMenu(i)
                else -> Unit
            }
        }

        fun bind(i: Item) {
            item = i
            icon.clearColorFilter()
            when (i) {
                is Entry -> {
                    icon.setImageDrawable(Favicons.get(context).drawable(i.url, i.title, 128))
                    title.text = i.title.ifBlank { i.url }
                    meta.text = listOfNotNull(i.meta, UrlInput.display(i.url)).joinToString("  ·  ")
                }
                is FolderItem -> {
                    icon.setImageResource(R.drawable.ic_folder)
                    icon.setColorFilter(Ui.p.textMid, PorterDuff.Mode.SRC_IN)
                    title.text = i.title
                    meta.text = i.detail
                }
                is Up -> {
                    icon.setImageResource(R.drawable.ic_arrow_upward)
                    icon.setColorFilter(Ui.p.textMid, PorterDuff.Mode.SRC_IN)
                    title.text = i.label
                    meta.text = ""
                }
                is Header -> Unit
            }
            meta.visibility = if (meta.text.isNullOrEmpty()) View.GONE else View.VISIBLE
            showCheck()
            // A reused row starts as an unfocused one.
            if (!body.hasFocus()) { body.scaleX = 1f; body.scaleY = 1f }
        }

        fun showCheck() {
            val e = entry ?: run { check.visibility = View.GONE; return }
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
        if (on) focusRow(adapter.items.indexOfFirst { it is Entry }.takeIf { it >= 0 } ?: list.firstVisiblePosition)
        else selectButton.requestFocus()
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

    /**
     * The buttons of the bookmarks screen: New folder, Select, and Delete all at the top or Delete folder in a folder;
     * while selecting Select all, Move selected, Delete selected and Cancel.
     */
    private fun updateHeader() {
        if (history) return
        fun show(v: View, on: Boolean) { v.visibility = if (on) View.VISIBLE else View.GONE }
        show(newFolderButton, !selecting)
        show(selectButton, !selecting && entries.isNotEmpty())
        show(deleteAllButton, !selecting && folder == null)
        show(deleteFolderButton, !selecting && folder != null)
        show(selectAllButton, selecting)
        show(moveSelectedButton, selecting)
        show(deleteSelectedButton, selecting)
        show(cancelButton, selecting)
        deleteSelectedButton.text = getString(R.string.delete_selected, selected.size)
        moveSelectedButton.text = getString(R.string.move_selected, selected.size)
        deleteSelectedButton.alpha = if (selected.isEmpty()) 0.5f else 1f
        moveSelectedButton.alpha = if (selected.isEmpty()) 0.5f else 1f
    }

    /** Asks before one bookmark is deleted, with Cancel focused; [delete] runs when it is confirmed. */
    private fun confirmDeleteBookmark(delete: () -> Unit) {
        Ui.dialog(this)
            .setMessage(resources.getQuantityString(R.plurals.confirm_delete_bookmarks, 1, 1))
            .setDestructive()
            .setPositiveButton(R.string.delete) { _, _ -> delete() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
        if (keyCode == KeyEvent.KEYCODE_BACK && trail.isNotEmpty()) {
            go(trail.removeAt(trail.size - 1), back = true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Deleting History asks for the parental PIN when one is set, once for this visit; bookmarks never do. */
    private fun guardHistory(action: () -> Unit) =
        if (history) PinPad.guard(this, R.string.pin_title_history, stay = true, action = action) else action()

    private fun confirmDeleteAll() {
        Ui.dialog(this)
            .setMessage(when {
                history -> R.string.confirm_clear_history
                tree.outline().isNotEmpty() -> R.string.confirm_clear_bookmarks_folders
                else -> R.string.confirm_clear_bookmarks
            })
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
        /** The folder (its id) the Bookmarks screen opens in; the top level without it. */
        const val EXTRA_FOLDER = "folder"
        private const val STATE_FOLDER = "folder"
        private const val STATE_TRAIL = "trail"
        // The top level, in the saved trail.
        private const val TOP = -1L
        private const val BODY = "body"
    }
}
