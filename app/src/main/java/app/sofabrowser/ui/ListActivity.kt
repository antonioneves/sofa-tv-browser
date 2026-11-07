package app.sofabrowser.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import app.sofabrowser.R
import app.sofabrowser.searchShortcuts
import app.sofabrowser.Prefs
import app.sofabrowser.browser.PhoneLink
import app.sofabrowser.browser.UrlInput
import app.sofabrowser.data.BookmarkFile
import app.sofabrowser.data.Db
import app.sofabrowser.data.Favicons
import app.sofabrowser.data.Site
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import kotlin.concurrent.thread

/** History or bookmarks as one focusable list. Returns the chosen URL in [SearchActivity.EXTRA_URL]. */
class ListActivity : Activity() {

    private val db by lazy { Db.get(this) }
    private val history get() = intent.getStringExtra(EXTRA_MODE) == MODE_HISTORY
    private lateinit var list: LinearLayout
    private lateinit var empty: View

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
            header.addView(headerButton(R.string.bookmarks_transfer) { transfer() },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = 16.px
                })
        }
        header.addView(headerButton(R.string.delete_all) { confirmDeleteAll() })
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

    private fun headerButton(label: Int, click: () -> Unit) = Ui.text(this, 28, Ui.p.textMid).apply {
        text = getString(label)
        isFocusable = true
        isClickable = true
        gravity = Gravity.CENTER
        background = Ui.fillSelector(Ui.p.fillFocused, Ui.p.focus, 12)
        setPadding(32.px, 14.px, 32.px, 14.px)
        setOnFocusChangeListener { v, has -> (v as android.widget.TextView).setTextColor(if (has) Ui.p.onFocus else Ui.p.textMid) }
        setOnClickListener { click() }
    }

    private fun load() {
        list.removeAllViews()
        if (history) {
            var lastHeader: String? = null
            db.history().forEach { h ->
                val header = dayLabel(h.visitedAt)
                if (header != lastHeader) {
                    list.addView(Ui.text(this, 30, Ui.p.textWeak).apply { text = header; setPadding(0, 36.px, 0, 20.px) })
                    lastHeader = header
                }
                addRow(h.title, h.url, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(h.visitedAt))) {
                    db.removeHistory(h.id)
                }
            }
        } else {
            db.bookmarks().forEach { b -> addRow(b.title, b.url, null) { db.removeBookmark(b.url) } }
        }
        empty.visibility = if (list.childCount == 0) View.VISIBLE else View.GONE
        list.getChildAt((0 until list.childCount).firstOrNull { list.getChildAt(it) is LinearLayout } ?: 0)
            ?.let { (it as? LinearLayout)?.getChildAt(0)?.requestFocus() }
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

    private fun addRow(title: String, url: String, meta: String?, delete: () -> Unit) {
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        val open = {
            setResult(RESULT_OK, Intent().putExtra(SearchActivity.EXTRA_URL, url))
            finish()
        }
        val remove = {
            delete()
            val i = list.indexOfChild(line)
            list.removeView(line)
            (list.getChildAt(i) as? LinearLayout ?: list.getChildAt(i - 1) as? LinearLayout)?.getChildAt(0)?.requestFocus()
            empty.visibility = if (list.childCount == 0) View.VISIBLE else View.GONE
        }
        val menu = {
            TileMenu(this, Site(0, title.ifBlank { url }, url), listOf(
                TileMenu.Item(R.drawable.ic_open_in_new, R.string.open, open),
                TileMenu.Item(R.drawable.ic_delete, R.string.delete, remove),
            )).show()
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            background = Ui.fillSelector(Ui.p.fill, Ui.p.fillFocused, 16)
            foreground = Ui.focusBorder(16)
            setPadding(40.px, 0, 40.px, 0)
            setOnClickListener { open() }
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
        line.addView(body, LinearLayout.LayoutParams(0, 136.px, 1f))
        list.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 16.px
        })
    }

    private fun confirmDeleteAll() {
        Ui.dialog(this)
            .setMessage(if (history) R.string.confirm_clear_history else R.string.confirm_clear_bookmarks)
            .setPositiveButton(R.string.delete_all) { _, _ ->
                if (history) db.clearHistory() else db.bookmarks().forEach { db.removeBookmark(it.url) }
                load()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- Bookmarks to and from other browsers, as the file Chrome exports (see BookmarkFile) ----

    private var phoneLink: PhoneLink? = null

    /** The phone or computer page, and a file on the TV when it has a file picker. */
    private fun transfer() {
        val export = filePicker(fileIntent(Intent.ACTION_CREATE_DOCUMENT).putExtra(Intent.EXTRA_TITLE, PhoneLink.FILE_NAME))
        val import = filePicker(fileIntent(Intent.ACTION_OPEN_DOCUMENT))
        val actions = buildList {
            add(R.string.bookmarks_with_phone to { withPhone() })
            if (export != null) add(R.string.bookmarks_export_file to { startActivityForResult(export, REQ_EXPORT) })
            if (import != null) add(R.string.bookmarks_import_file to { startActivityForResult(import, REQ_IMPORT) })
        }
        if (actions.size == 1) return withPhone()
        Ui.dialog(this)
            .setTitle(R.string.bookmarks_transfer_title)
            .setMessage(R.string.bookmarks_transfer_how)
            .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun fileIntent(action: String) = Intent(action).addCategory(Intent.CATEGORY_OPENABLE).setType("text/html")

    /**
     * [intent] for a real file picker, or null when there is none. Android TV answers file intents with a stub that
     * does nothing, so it is left out; with one picker left it opens directly, with more the TV asks which.
     */
    private fun filePicker(intent: Intent): Intent? {
        val (stubs, pickers) = packageManager.queryIntentActivities(intent, 0)
            .partition { it.activityInfo.packageName == TV_STUBS }
        return when (pickers.size) {
            0 -> null
            1 -> intent.setClassName(pickers[0].activityInfo.packageName, pickers[0].activityInfo.name)
            else -> Intent.createChooser(intent, null).putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS,
                stubs.map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }.toTypedArray())
        }
    }

    private fun withPhone() {
        val link = PhoneLink(this, onText = { text ->
            val url = UrlInput.resolve(text, Prefs(this).searchEngine, searchShortcuts(this))
            if (url.isNotEmpty()) {
                setResult(RESULT_OK, Intent().putExtra(SearchActivity.EXTRA_URL, url))
                finish()
            }
        }, onImported = { added, message ->
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            if (added > 0) load()
        }, onChanged = { load() })
        val address = link.start(this) ?: return Toast.makeText(this, R.string.phone_no_network, Toast.LENGTH_LONG).show()
        phoneLink?.stop()
        phoneLink = link
        Ui.dialog(this)
            .setTitle(R.string.bookmarks_transfer_title)
            .setView(PhoneCard.view(this, address,
                listOf(R.string.bookmarks_step_open, R.string.bookmarks_step_export, R.string.bookmarks_step_import)
                    .map(::getString),
                getString(R.string.bookmarks_or_open)))
            .setNegativeButton(R.string.close, null)
            .setOnDismissListener { link.stop(); if (phoneLink === link) phoneLink = null }
            .show()
    }

    private fun addedText(added: Int) =
        if (added == 0) getString(R.string.bookmarks_added_none)
        else resources.getQuantityString(R.plurals.bookmarks_added, added, added)

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK || requestCode != REQ_EXPORT && requestCode != REQ_IMPORT) return
        val main = Handler(Looper.getMainLooper())
        thread(name = "bookmark-file") {
            var added = 0
            val message = try {
                if (requestCode == REQ_EXPORT) {
                    val file = BookmarkFile.write(db.bookmarkEntries()).toByteArray(Charsets.UTF_8)
                    contentResolver.openOutputStream(uri, "wt")!!.use { it.write(file) }
                    getString(R.string.bookmarks_exported)
                } else {
                    val text = contentResolver.openInputStream(uri)!!.use { String(it.readBytes(), Charsets.UTF_8) }
                    BookmarkFile.read(text)?.let { added = db.importBookmarks(it); addedText(added) }
                        ?: getString(R.string.bookmarks_not_a_file)
                }
            } catch (_: Exception) {
                getString(R.string.bookmarks_file_failed)
            }
            main.post {
                if (isDestroyed) return@post
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                if (added > 0) load()
            }
        }
    }

    override fun onDestroy() {
        phoneLink?.stop()
        super.onDestroy()
    }

    companion object {
        private const val REQ_EXPORT = 1
        private const val REQ_IMPORT = 2
        private const val TV_STUBS = "com.android.tv.frameworkpackagestubs"
        const val EXTRA_MODE = "mode"
        const val MODE_HISTORY = "history"
        const val MODE_BOOKMARKS = "bookmarks"
    }
}
