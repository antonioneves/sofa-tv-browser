package app.sofabrowser.browser

import android.os.SystemClock

/**
 * Keeps a page that kills its renderer every time it loads from being loaded for ever. A page whose renderer died is
 * loaded again once; when the renderer dies again within [WINDOW] of that, the page is left alone. Opening it again
 * afterwards starts the count afresh.
 */
class CrashLoop(private val clock: () -> Long = { SystemClock.elapsedRealtime() }) {
    private var last: Long? = null

    /** Whether the page whose renderer just died should be loaded again. */
    fun reload(): Boolean {
        val now = clock()
        val again = last?.let { now - it < WINDOW } ?: false
        last = if (again) null else now
        return !again
    }

    companion object {
        const val WINDOW = 120_000L
    }
}
