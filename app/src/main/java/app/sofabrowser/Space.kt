package app.sofabrowser

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebView
import app.sofabrowser.data.SignIns
import app.sofabrowser.data.SpacePin
import app.sofabrowser.ui.PinActivity
import java.io.File

/**
 * The private space: a second Sofa inside Sofa, behind a PIN, with its own settings, bookmarks, history, filter lists,
 * downloads and WebView data. A run of the process belongs to one side for good: going across restarts the process,
 * so no singleton or WebView state can carry over. WebView can only keep a second data folder from Android 9.
 *
 * The PIN keeps people out of the screens; the data is kept apart in Sofa's app storage, not encrypted with the PIN.
 */
object Space {
    /** Written just before the restart into the space and read once, so any other start is the normal side. */
    private const val ENTER = "space_enter"
    private const val PREFIX = "space."
    private const val WEBVIEW_SUFFIX = "space"
    private const val SAVE_MILLIS = 300L
    private val OUTSIDE = setOf(Intent.ACTION_VIEW, Intent.ACTION_WEB_SEARCH, Intent.ACTION_SEARCH, Intent.ACTION_SEND)

    val supported get() = Build.VERSION.SDK_INT >= 28

    /** Whether to show the way in, or out once inside: the way in is switched on in Settings. */
    fun offered(context: Context) = active || supported && Prefs(context).privateSpace

    /** Whether this run of Sofa is the private space. */
    @Volatile var active = false
        private set

    /** Sofa went out of sight while in the space: the PIN comes first when it returns. */
    @Volatile var locked = false
        private set

    private val live = ArrayList<Activity>()
    private var started = 0

    internal fun start(base: Context) {
        val entering = File(base.filesDir, ENTER)
        if (!entering.delete() || !supported) return
        active = true
        WebView.setDataDirectorySuffix(WEBVIEW_SUFFIX)
    }

    internal fun watch(app: Application) {
        if (active) app.registerActivityLifecycleCallbacks(Watcher)
    }

    /** The name of a preferences file or database on this side. */
    fun name(base: String) = if (active) PREFIX + base else base

    /** This side's folder for files. */
    fun files(context: Context): File =
        if (active) File(context.filesDir, "space").apply { mkdirs() } else context.filesDir

    /** This side's folder for caches. */
    fun cache(context: Context): File =
        if (active) File(context.cacheDir, "space").apply { mkdirs() } else context.cacheDir

    fun enter(activity: Activity) {
        File(activity.filesDir, ENTER).createNewFile()
        restart(activity, Intent(activity, MainActivity::class.java))
    }

    /** Back to the normal side, opening [next] there if given. */
    fun leave(activity: Activity, next: Intent? = null) =
        restart(activity, next ?: Intent(activity, MainActivity::class.java))

    private fun restart(activity: Activity, intent: Intent) {
        // What's saved in the background (cookies, settings) gets to disk before the process goes.
        CookieManager.getInstance().flush()
        Thread.sleep(SAVE_MILLIS)
        // Closed first, or the start would go to the screen already open, which dies with the process.
        live.toList().forEach { it.finish() }
        activity.finish()
        activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        Process.killProcess(Process.myPid())
    }

    fun unlock() {
        locked = false
    }

    /**
     * What's launched from outside never lands in the space unasked: a TV home tile always, and a link while the
     * space is out of sight, go to the normal side. Returns true when Sofa is restarting for it.
     */
    fun sendOut(activity: Activity, intent: Intent?, page: Boolean): Boolean {
        if (!active || intent == null) return false
        if (!page && !(locked && intent.action in OUTSIDE)) return false
        leave(activity, Intent(intent))
        return true
    }

    /** Erases the whole space and its PIN. Only from the normal side, where nothing has the data open. */
    fun erase(context: Context) {
        check(!active)
        File(context.filesDir, "space").deleteRecursively()
        File(context.cacheDir, "space").deleteRecursively()
        File(context.dataDir, "app_webview_$WEBVIEW_SUFFIX").deleteRecursively()
        File(context.cacheDir, "webview_$WEBVIEW_SUFFIX").deleteRecursively()
        File(context.dataDir, "shared_prefs").list().orEmpty().filter { it.startsWith(PREFIX) }
            .forEach { context.deleteSharedPreferences(it.removeSuffix(".xml")) }
        context.databaseList().filter { it.startsWith(PREFIX) && !it.endsWith("-journal") }.forEach { context.deleteDatabase(it) }
        SignIns.deleteSpaceKey(PREFIX)
        SpacePin.clear(context)
    }

    private object Watcher : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, state: Bundle?) {
            live += activity
            // No picture of a private page in the TV's list of recent apps. Test builds stay open to adb's screenshots.
            if (!BuildConfig.DEBUG) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

        override fun onActivityStarted(activity: Activity) {
            started++
            if (!locked || activity is PinActivity) return
            // Before its first frame: nothing of the space shows under the PIN screen as it opens.
            activity.window.decorView.visibility = View.INVISIBLE
            activity.startActivity(
                Intent(activity, PinActivity::class.java)
                    .putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_UNLOCK)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }

        override fun onActivityResumed(activity: Activity) {
            if (!locked) activity.window.decorView.visibility = View.VISIBLE
        }

        override fun onActivityStopped(activity: Activity) {
            started--
            if (started <= 0 && !activity.isChangingConfigurations) locked = true
        }

        override fun onActivityDestroyed(activity: Activity) {
            live -= activity
        }

        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    }
}
