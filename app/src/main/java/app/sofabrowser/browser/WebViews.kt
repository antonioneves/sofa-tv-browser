package app.sofabrowser.browser

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.sofabrowser.Prefs

/** WebView configuration with privacy-preserving defaults, plus the user-agent strings for each mode. */
object WebViews {

    private var chromeMajor: String? = null

    /**
     * Applies [prefs] to [web], with [userAgentMode] for "Browse as". Safe to call again after the user changes a setting.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun configure(web: WebView, prefs: Prefs, userAgentMode: String = prefs.userAgent) {
        val s = web.settings
        s.javaScriptEnabled = prefs.javascript
        s.domStorageEnabled = true
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.mediaPlaybackRequiresUserGesture = !prefs.autoplay
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.setSupportZoom(true)
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.textZoom = prefs.textZoom
        // Nothing on the web should reach app files or content providers.
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.setGeolocationEnabled(false)
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        if (Build.VERSION.SDK_INT >= 26) s.safeBrowsingEnabled = prefs.safeBrowsing
        applyUserAgent(web, userAgentMode, prefs.customUserAgent)

        cookieManager(web).apply {
            setAcceptCookie(prefs.cookies)
            setAcceptThirdPartyCookies(web, prefs.cookies && !prefs.blockThirdPartyCookies)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ATTRIBUTION_REGISTRATION_BEHAVIOR)) {
            WebSettingsCompat.setAttributionRegistrationBehavior(s, WebSettingsCompat.ATTRIBUTION_BEHAVIOR_DISABLED)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, prefs.darkPages)
        }
    }

    /**
     * Desktop mode presents as desktop Chrome on Windows (the most common, so the least distinctive); mobile
     * mode as Chrome on Android. Both use Chrome's reduced UA format with the real Chromium major version, and
     * neither carries WebView's "wv" token, which some sites (Google sign-in among them) refuse. The client hints
     * are kept consistent with the string.
     */
    fun applyUserAgent(web: WebView, mode: String, custom: String) {
        val major = chromeMajor(web.context)
        val desktop = mode != "mobile"
        web.settings.userAgentString = when (mode) {
            "mobile" -> "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Mobile Safari/537.36"
            "webview" -> null // WebView's own default
            "ipad" -> "Mozilla/5.0 (iPad; CPU OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"
            "tv" -> "Mozilla/5.0 (SMART-TV; LINUX; Tizen 8.0) AppleWebKit/537.36 (KHTML, like Gecko) Version/8.0 TV Safari/537.36"
            "custom" -> custom.ifBlank { null }
            else -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
        if (mode != "desktop" && mode != "mobile") {
            // Other modes keep WebView's own client hints.
            WebSettingsCompat.setUserAgentMetadata(web.settings, UserAgentMetadata.Builder().build())
            return
        }
        run {
            // Keep the client hints consistent with the UA string instead of "Android WebView".
            val brands = listOf(
                UserAgentMetadata.BrandVersion.Builder().setBrand("Chromium").setMajorVersion(major)
                    .setFullVersion("$major.0.0.0").build(),
                UserAgentMetadata.BrandVersion.Builder().setBrand("Not_A Brand").setMajorVersion("24")
                    .setFullVersion("24.0.0.0").build(),
            )
            WebSettingsCompat.setUserAgentMetadata(web.settings, UserAgentMetadata.Builder()
                .setBrandVersionList(brands)
                .setFullVersion("$major.0.0.0")
                .setPlatform(if (desktop) "Windows" else "Android")
                .setPlatformVersion("10.0.0")
                .setArchitecture(if (desktop) "x86" else "")
                .setBitness(if (desktop) 64 else UserAgentMetadata.BITNESS_DEFAULT)
                .setMobile(!desktop)
                .setModel("")
                .setWow64(false)
                .build())
        }
    }

    private fun chromeMajor(context: Context): String = chromeMajor ?: run {
        val ua = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault("")
        (WebViewAge.major(null, ua)?.toString() ?: "140").also { chromeMajor = it }
    }

    /** The installed WebView: its package version name ("" when unknown) and Chromium major version. */
    data class Engine(val version: String, val major: Int?) {
        /** For people: the full version when it is Google's format, else just the major one. */
        val label: String get() = if (major != null && version.startsWith("$major.")) version else major?.toString() ?: "?"
    }

    fun engine(context: Context): Engine {
        val version = runCatching { WebViewCompat.getCurrentWebViewPackage(context)?.versionName }.getOrNull().orEmpty()
        val ua = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull()
        return Engine(version, WebViewAge.major(version, ua))
    }

    /** The cookie jar of [web]'s profile (the private profile has its own). */
    @SuppressLint("RequiresFeature") // checked inline
    fun cookieManager(web: WebView): CookieManager =
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) WebViewCompat.getProfile(web).cookieManager
        else CookieManager.getInstance()

    /** Injects [script] at document start in every frame when supported; returns false otherwise. */
    fun addDocumentStartScript(web: WebView, script: String): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false
        WebViewCompat.addDocumentStartJavaScript(web, script, setOf("*"))
        return true
    }

    /**
     * Removes the cookies and storage of [url]'s site (its registrable domain, subdomains included), then runs [done]
     * on the main thread. A newer WebView does it all itself; an older one loses the storage of the page's own origin
     * and the cookies the page can see, which is what signs a person in.
     */
    @SuppressLint("RequiresFeature") // checked inline
    fun clearSiteData(context: Context, web: WebView, url: String, done: () -> Unit) {
        val host = android.net.Uri.parse(url).host?.lowercase() ?: return done()
        val site = app.sofabrowser.adblock.Sites.of(host)
        // Its answer to the cookie banner went with the cookies.
        app.sofabrowser.cookieBannerSites(context).let { saved ->
            saved.keys.filter { app.sofabrowser.adblock.Sites.of(it.lowercase()) == site }.forEach { saved.remove(it) }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
            val storage = if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) WebViewCompat.getProfile(web).webStorage
                else WebStorage.getInstance()
            WebStorageCompat.deleteBrowsingDataForSite(storage, host) { done() }
            return
        }
        val cookies = cookieManager(web)
        val origin = android.net.Uri.parse(url).let { "${it.scheme}://${it.authority}" }
        WebStorage.getInstance().deleteOrigin(origin)
        val names = cookies.getCookie(url).orEmpty().split(';').mapNotNull { it.substringBefore('=').trim().ifEmpty { null } }.toSet()
        // A cookie is only removed by setting it again, expired, with the same domain and path. The path can't be read
        // back: the root covers nearly every sign-in.
        val domains = buildList {
            add(null)
            var d = host
            while (d.length >= site.length) { add(d); d = d.substringAfter('.', "").ifEmpty { break } }
        }
        for (name in names) for (domain in domains) {
            cookies.setCookie(url, "$name=; Max-Age=0; Path=/" + (domain?.let { "; Domain=.$it" } ?: ""))
        }
        cookies.flush()
        // An open page keeps its storage in memory, out of deleteOrigin's reach: it empties it itself, then loads again.
        web.evaluateJavascript(CLEAR_STORAGE_SCRIPT) { web.postDelayed(done, STORAGE_GONE_MS) }
    }

    /** Empties the page's own storage: what an older WebView can't delete for an origin that is open. */
    private const val CLEAR_STORAGE_SCRIPT = """(function(){
        try { localStorage.clear(); } catch (e) {}
        try { sessionStorage.clear(); } catch (e) {}
        try { indexedDB.databases().then(function(l){ l.forEach(function(d){ indexedDB.deleteDatabase(d.name); }); }); } catch (e) {}
        try { caches.keys().then(function(k){ k.forEach(function(n){ caches.delete(n); }); }); } catch (e) {}
        try { navigator.serviceWorker.getRegistrations().then(function(r){ r.forEach(function(x){ x.unregister(); }); }); } catch (e) {}
    })()"""

    /** Long enough for the page's asynchronous deletions to go through before it loads again. */
    private const val STORAGE_GONE_MS = 400L

    /** Removes every trace of browsing: cookies, site storage, cache, form data and HTTP auth. */
    fun clearBrowsingData(context: Context, web: WebView?) {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        // The sites' answers to their cookie banners went with the cookies: look at them again.
        app.sofabrowser.cookieBannerSites(context).clear()
        WebStorage.getInstance().deleteAllData()
        web?.clearCache(true)
        web?.clearFormData()
        web?.clearHistory()
        @Suppress("DEPRECATION")
        WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword()
    }
}
