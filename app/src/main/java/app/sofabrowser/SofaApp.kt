package app.sofabrowser

import android.app.Application
import android.content.Context

/** Settles at process start which space this run of Sofa belongs to, before anything opens its data. */
class SofaApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        Space.start(base)
    }

    override fun onCreate() {
        super.onCreate()
        Space.watch(this)
        PinMemory.watch(this)
    }
}
