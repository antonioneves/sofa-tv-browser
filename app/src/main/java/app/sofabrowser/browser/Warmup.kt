package app.sofabrowser.browser

import android.annotation.SuppressLint
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature

/**
 * Gets a site ready before it is opened, as Chrome does while an address is typed: when the user points at a tile or
 * a suggestion, or types an address, the connection to that site (name lookup, connection, encryption) is opened
 * ahead, so the page starts that much sooner when they press OK. Only a connection: nothing is asked of the site, no
 * cookie is sent, and nothing at all in private browsing. Needs a WebView that offers it; others are left as they are.
 */
object Warmup {
    /** A connection nobody uses is closed after about ten seconds, so the same site is opened again after this long. */
    private const val AGAIN_MS = 8_000L
    private val recent = HashMap<String, Long>()

    /** Opens a connection to the site of [url], unless one was opened lately. Call on the main thread. */
    @SuppressLint("RequiresFeature") // checked below
    @OptIn(Profile.ExperimentalPreconnect::class)
    fun preconnect(url: String?) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        if (uri.scheme != "https" && uri.scheme != "http" || uri.host.isNullOrEmpty()) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) || !WebViewFeature.isFeatureSupported(WebViewFeature.PRECONNECT)) return
        val origin = "${uri.scheme}://${uri.encodedAuthority}"
        val now = SystemClock.uptimeMillis()
        if ((recent[origin] ?: Long.MIN_VALUE / 2) > now - AGAIN_MS) return
        recent.entries.removeAll { it.value <= now - AGAIN_MS }
        recent[origin] = now
        runCatching { ProfileStore.getInstance().getProfile(Profile.DEFAULT_PROFILE_NAME)?.preconnect(origin) }
    }
}
