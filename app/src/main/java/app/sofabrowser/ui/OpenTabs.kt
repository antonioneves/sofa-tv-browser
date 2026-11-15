package app.sofabrowser.ui

import android.graphics.Bitmap

/**
 * The open tabs, handed from the browser to the search screen, which shows them under the field. Both run in one
 * process, and the pictures of the pages are too big to go in an intent.
 */
object OpenTabs {
    class Tab(val id: Long, val url: String, val title: String, val thumbnail: Bitmap?, val current: Boolean)

    var list: List<Tab> = emptyList()
}
