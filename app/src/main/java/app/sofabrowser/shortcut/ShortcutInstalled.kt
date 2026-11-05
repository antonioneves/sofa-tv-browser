package app.sofabrowser.shortcut

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import app.sofabrowser.R

/** Result of installing a page's launcher tile: shows Android's confirmation screen, or says how it went. */
class ShortcutInstalled : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
                // Google TV's launcher only notices a new app while it is on screen: with Sofa moved aside, the
                // confirmation shows over the launcher, and accepting it returns there. Only after the confirmation
                // is up, because newer Android blocks it once the app has left the foreground.
                TvShortcut.source.get()?.moveTaskToBack(true)
            }
            PackageInstaller.STATUS_SUCCESS -> Toast.makeText(context, R.string.shortcut_added, Toast.LENGTH_LONG).show()
            // A cancelled confirmation needs no message.
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
            else -> {
                val reason = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: status.toString()
                Toast.makeText(context, context.getString(R.string.shortcut_failed, reason), Toast.LENGTH_LONG).show()
            }
        }
    }
}
