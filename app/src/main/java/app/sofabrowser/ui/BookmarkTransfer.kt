package app.sofabrowser.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import app.sofabrowser.Prefs
import app.sofabrowser.R
import app.sofabrowser.browser.PhoneLink
import app.sofabrowser.browser.UrlInput
import app.sofabrowser.data.BookmarkFile
import app.sofabrowser.data.Db
import app.sofabrowser.searchShortcuts
import kotlin.concurrent.thread

/**
 * Bookmarks to and from other browsers, as the file Chrome exports (see [BookmarkFile]): through a page served to
 * a phone or computer, or through a file on the TV when it has a file picker. [onUrl] gets an address typed on the
 * phone; [onChanged] is called when the bookmarks changed. The host activity passes its results to [onActivityResult]
 * and calls [stop] when it is destroyed.
 */
class BookmarkTransfer(private val activity: Activity, private val onUrl: (String) -> Unit, private val onChanged: () -> Unit) {

    private val db get() = Db.get(activity)
    private var phoneLink: PhoneLink? = null

    /** The phone or computer page, and a file on the TV when it has a file picker. */
    fun start() {
        val export = filePicker(fileIntent(Intent.ACTION_CREATE_DOCUMENT).putExtra(Intent.EXTRA_TITLE, PhoneLink.FILE_NAME))
        val import = filePicker(fileIntent(Intent.ACTION_OPEN_DOCUMENT))
        val actions = buildList {
            add(R.string.bookmarks_with_phone to { withPhone() })
            @Suppress("DEPRECATION")
            if (export != null) add(R.string.bookmarks_export_file to { activity.startActivityForResult(export, REQ_EXPORT) })
            @Suppress("DEPRECATION")
            if (import != null) add(R.string.bookmarks_import_file to { activity.startActivityForResult(import, REQ_IMPORT) })
        }
        if (actions.size == 1) return withPhone()
        Ui.dialog(activity)
            .setTitle(R.string.bookmarks_transfer_title)
            .setMessage(R.string.bookmarks_transfer_how)
            .setItems(actions.map { activity.getString(it.first) }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun stop() {
        phoneLink?.stop()
    }

    private fun fileIntent(action: String) = Intent(action).addCategory(Intent.CATEGORY_OPENABLE).setType("text/html")

    /**
     * [intent] for a real file picker, or null when there is none. Android TV answers file intents with a stub that
     * does nothing, so it is left out; with one picker left it opens directly, with more the TV asks which.
     */
    private fun filePicker(intent: Intent): Intent? {
        val (stubs, pickers) = activity.packageManager.queryIntentActivities(intent, 0)
            .partition { it.activityInfo.packageName == TV_STUBS }
        return when (pickers.size) {
            0 -> null
            1 -> intent.setClassName(pickers[0].activityInfo.packageName, pickers[0].activityInfo.name)
            else -> Intent.createChooser(intent, null).putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS,
                stubs.map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }.toTypedArray())
        }
    }

    private fun withPhone() {
        val link = PhoneLink(activity, onText = { text ->
            val url = UrlInput.resolve(text, Prefs(activity).searchEngine, searchShortcuts(activity))
            if (url.isNotEmpty()) onUrl(url)
        }, onImported = { added, message ->
            SofaToast.makeText(activity, message, Toast.LENGTH_LONG).show()
            if (added > 0) onChanged()
        }, onChanged = { onChanged() })
        val address = link.start(activity) ?: return SofaToast.makeText(activity, R.string.phone_no_network, Toast.LENGTH_LONG).show()
        phoneLink?.stop()
        phoneLink = link
        Ui.dialog(activity)
            .setTitle(R.string.bookmarks_transfer_title)
            .setView(PhoneCard.view(activity, address,
                listOf(R.string.bookmarks_step_open, R.string.bookmarks_step_export, R.string.bookmarks_step_import)
                    .map(activity::getString),
                activity.getString(R.string.bookmarks_or_open)))
            .setNegativeButton(R.string.close, null)
            .setOnDismissListener { link.stop(); if (phoneLink === link) phoneLink = null }
            .show()
    }

    private fun addedText(added: Int) =
        if (added == 0) activity.getString(R.string.bookmarks_added_none)
        else activity.resources.getQuantityString(R.plurals.bookmarks_added, added, added)

    /** Handles the result of the file picker; false when [requestCode] is not one of this class's. */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQ_EXPORT && requestCode != REQ_IMPORT) return false
        val uri = data?.data ?: return true
        if (resultCode != Activity.RESULT_OK) return true
        val main = Handler(Looper.getMainLooper())
        thread(name = "bookmark-file") {
            var added = 0
            val message = try {
                if (requestCode == REQ_EXPORT) {
                    val file = BookmarkFile.write(db.bookmarkEntries()).toByteArray(Charsets.UTF_8)
                    activity.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(file) }
                    activity.getString(R.string.bookmarks_exported)
                } else {
                    val text = activity.contentResolver.openInputStream(uri)!!.use { String(it.readBytes(), Charsets.UTF_8) }
                    BookmarkFile.read(text)?.let { added = db.importBookmarks(it); addedText(added) }
                        ?: activity.getString(R.string.bookmarks_not_a_file)
                }
            } catch (_: Exception) {
                activity.getString(R.string.bookmarks_file_failed)
            }
            main.post {
                if (activity.isDestroyed) return@post
                SofaToast.makeText(activity, message, Toast.LENGTH_LONG).show()
                if (added > 0) onChanged()
            }
        }
        return true
    }

    companion object {
        // High, so they never meet the host activity's own request codes.
        const val REQ_EXPORT = 7101
        const val REQ_IMPORT = 7102
        private const val TV_STUBS = "com.android.tv.frameworkpackagestubs"
    }
}
