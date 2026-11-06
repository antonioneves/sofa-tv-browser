package app.sofabrowser

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock

/**
 * "Ask for the PIN": how long Sofa's PIN stays good for saved sign-ins after it was entered. Kept in memory only, so
 * closing Sofa forgets it, as do the screen going off and a screensaver starting. [CHOICES] are minutes, 0 meaning
 * the PIN is asked every time.
 */
object PinMemory {

    val CHOICES = listOf(0, 1, 5, 15, 30, 60)

    private var enteredAt = 0L
    internal var clock: () -> Long = { SystemClock.elapsedRealtime() }

    /** The PIN was just entered or chosen. */
    fun remember() { enteredAt = clock() }

    fun forget() { enteredAt = 0 }

    /** Whether the PIN entered less than [minutes] ago still counts. */
    fun valid(minutes: Int) = minutes > 0 && enteredAt > 0 && clock() - enteredAt < minutes * 60_000L

    private var started = 0

    internal fun watch(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { started++ }
            override fun onActivityStopped(activity: Activity) {
                started--
                // No screen of Sofa left in sight: it was closed, or left for another app.
                if (started <= 0 && !activity.isChangingConfigurations) forget()
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        // A screensaver, or the screen going off, doesn't always stop Sofa.
        val dimmer = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = forget()
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_DREAMING_STARTED)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) app.registerReceiver(dimmer, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(dimmer, filter)
    }
}
