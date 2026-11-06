package app.sofabrowser.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.CustomHeader
import androidx.webkit.ProfileStore
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.sofabrowser.Prefs
import app.sofabrowser.siteUserAgents
import app.sofabrowser.siteZooms
import app.sofabrowser.hiddenElements
import app.sofabrowser.cookieBannerSites
import app.sofabrowser.data.HiddenElements
import app.sofabrowser.adblock.AdBlocker
import app.sofabrowser.adblock.Allowlist
import app.sofabrowser.adblock.InPageBlocking
import app.sofabrowser.adblock.NavigationGuard
import app.sofabrowser.adblock.Sites
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the single WebView: creates it in the normal or private profile, applies settings and relays page events.
 * BrowsHere has no tabs either; one view keeps memory use low on TV hardware.
 */
class Browser(private val context: Context, private val prefs: Prefs, private val host: Host) {

    /** Also hears about sign-ins on pages (SignInPages), when "Offer to save sign-ins" is on outside private browsing. */
    interface Host : SignInPages.Listener {
        fun onPageStarted(url: String)
        fun onPageFinished(url: String)
        /** The new page is about to be drawn for the first time. */
        fun onPageVisible(url: String) {}
        fun onProgress(percent: Int)
        fun onTitle(url: String, title: String)
        fun onIcon(url: String, icon: Bitmap)
        fun onVisited(url: String)
        fun onEnterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback)
        fun onExitFullscreen()
        fun onVideoState(state: VideoState)
        /** Whether a video plays anywhere on the page (any frame, fullscreen or not). */
        fun onPlaying(playing: Boolean)
        fun onVideoInfo(info: VideoInfo)
        fun onVideoCandidate(candidate: WatchTarget.Candidate)
        fun onVideoSource(source: VideoSource)
        /** The media times of the frames the watched video showed, for "Match frame rate". */
        fun onVideoFrameTimes(times: List<Double>)
        /** The user just started a video in [frame] that "Open videos in Sofa Player" may take ([VideoRelay]'s offerStart). */
        fun onVideoStarted(frame: String)
        fun onSslError(handler: SslErrorHandler, error: SslError)
        fun onProtectedMediaRequest(origin: String, grant: (Boolean) -> Unit)
        fun onDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?, length: Long)
        fun onBlockedLink(url: String)
        /** A pop-up was blocked; [url] is set when "Allow once" can open it. */
        fun onPopupBlocked(url: String?)
        /** The page tried to leave for a listed host on its own; "Allow once" can follow it. */
        fun onRedirectBlocked()
        fun onRendererGone()
    }

    var web: WebView? = null
        private set
    var privateMode = false
        private set

    /** True when private browsing runs in its own WebView profile; false on WebViews too old for profiles. */
    var isolated = false
        private set

    /** Host of the page in the main frame, readable from WebView's network threads. */
    @Volatile var pageHost: String? = null
        private set

    /** Address of the page in the main frame, for page-wide filter exceptions. */
    @Volatile var pageUrl: String? = null
        private set

    /**
     * Where the main frame is going: a link or a server redirect it follows. A redirect reaches neither
     * shouldInterceptRequest nor onPageStarted before its certificate is checked, so [pageUrl] still names the page before.
     */
    private var mainFrameTarget: String? = null

    /** The "Browse as" mode the view has now, with the custom string when it is "custom". */
    private var appliedUserAgent: String? = null
    /** Per-site "Browse as" choices made in private browsing, which are kept only until it closes. */
    private val privateUserAgents = HashMap<String, String>()
    /** Per-site zoom chosen in private browsing, likewise. */
    private val privateZooms = HashMap<String, Int>()
    private val privateHidden = HashMap<String, String>()
    /** Sites the cookie banner check is done with in private browsing, which has cookies of its own. */
    private val privateBannerSites = HashSet<String>()
    /** Ad blocking turned off or back on per host in private browsing; read on WebView's network threads too. */
    private val privateAdBlockOff = ConcurrentHashMap<String, Boolean>()
    /** "Keys go to the site" per host in private browsing. */
    private val privateKeysToPage = HashMap<String, Boolean>()
    /** Answers to the protected-content prompt per origin in private browsing. */
    private val privateDrmAnswers = HashMap<String, Boolean>()

    val adBlocker = AdBlocker.get(context)
    private val guard = NavigationGuard()
    /** Set while Sofa taps the page on its own ([unattended]), so the guard doesn't take that tap for the user's. */
    private var tappingAlone = false
    private val frames = PlaybackFrames()

    /** Video streams the current page fetched, for Sofa Player and "Play in…". */
    val streams = StreamSniffer()

    /** Creates (or re-creates) the WebView. Returns the new view; the caller attaches it to its layout. */
    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature", "ClickableViewAccessibility") // MULTI_PROFILE: see profilesSupported
    fun create(private: Boolean): WebView {
        destroy()
        privateMode = private
        isolated = private && profilesSupported
        if (app.sofabrowser.BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        val view = WebView(context)
        if (profilesSupported) {
            // Must happen before anything else touches the view.
            WebViewCompat.setProfile(view, if (isolated) PRIVATE_PROFILE else androidx.webkit.Profile.DEFAULT_PROFILE_NAME)
        }
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.setBackgroundColor(android.graphics.Color.WHITE)
        val density = context.resources.displayMetrics.density
        // Same trick as BrowsHere: a desktop-ish ~1130-1500 CSS px viewport that stays readable from the sofa.
        view.setInitialScale((85 * if (density >= 1) minOf(density, 2f) else density).toInt())
        WebViews.configure(view, prefs)
        appliedUserAgent = agentKey(prefs.userAgent)
        applyGpc(view)
        // Fresh random names per view for everything injected into pages (see PageKey).
        val key = PageKey()
        VideoRelay.install(view, key, panelScale(),
            onState = { state -> view.post { host.onVideoState(state) } },
            onPlaying = { frame, on -> view.post { frames.report(frame, on); host.onPlaying(frames.anyPlaying) } },
            onInfo = { info -> view.post { host.onVideoInfo(info) } },
            onCandidate = { c -> view.post { host.onVideoCandidate(c) } },
            onSource = { s -> view.post { host.onVideoSource(s) } },
            onFrameTimes = { t -> view.post { host.onVideoFrameTimes(t) } },
            onFetched = { url, final, body, sender -> view.post { fetched(url, final, body, sender) } },
            onStarted = { frame -> view.post { host.onVideoStarted(frame) } })
        // Every press that reaches the page (the pointer's taps, a real mouse, OK in keys-to-page mode) may open one
        // window. Both listeners only watch.
        view.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) { if (tappingAlone) guard.onUnattendedInput() else guard.onInput() }
            false
        }
        view.setOnKeyListener { _, code, e ->
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && code in PRESS_KEYS) guard.onInput()
            false
        }
        PageDownloads.install(view, key)
        pageKey = key
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(view, key.banners, setOf("*")) { v, message, _, _, _ ->
                val pageHost = runCatching { org.json.JSONObject(message.data.orEmpty()).optString("h") }.getOrNull()
                v.post { onBannerSiteDone(pageHost) }
            }
        }
        applyBannerRejection(view)
        applySignIns(view)
        HiddenPages.install(view, key, ::onElementPicked)
        applyHidden(view)
        InPageBlocking.install(view, key, adBlocker, active = { blocking(pageHost) }) { url ->
            debug("trap link removed: $url")
            adBlocker.countBlocked()
            guard.blocked(url, SystemClock.uptimeMillis())
            host.onPopupBlocked(url)
        }
        view.webViewClient = client
        view.webChromeClient = chrome
        view.setDownloadListener { url, ua, disposition, mime, length -> host.onDownload(url, ua, disposition, mime, length) }
        web = view
        return view
    }

    fun destroy() {
        web?.let {
            SignInPages.forget(it)
            it.stopLoading()
            (it.parent as? android.view.ViewGroup)?.removeView(it)
            it.destroy()
        }
        web = null
        bannerScript = null
        hiddenScript = null
        picking = false
        frames.clear()
        host.onPlaying(false)
        if (isolated) deletePrivateProfile()
        if (privateMode) {
            privateUserAgents.clear(); privateZooms.clear(); privateHidden.clear(); privateBannerSites.clear()
            privateAdBlockOff.clear(); privateKeysToPage.clear(); privateDrmAnswers.clear()
        }
        appliedUserAgent = null
        privateMode = false
        isolated = false
    }

    fun applySettings() {
        web?.let {
            val mode = userAgentMode(it.url)
            WebViews.configure(it, prefs, mode)
            appliedUserAgent = agentKey(mode)
            applyGpc(it)
            applyBannerRejection(it)
            applySignIns(it)
            applyHidden(it)
        }
    }

    private var pageKey: PageKey? = null

    /** Never in private browsing: nothing typed there is offered for saving, and nothing saved is filled in. */
    private fun applySignIns(view: WebView) {
        val key = pageKey ?: return
        if (prefs.saveSignIns && !privateMode) SignInPages.install(view, key, host) else SignInPages.remove(view)
    }

    private var hiddenScript: ScriptHandler? = null

    /** Whether the user is choosing parts of the page to hide ([startPicking]). */
    var picking = false
        private set

    /** Hides what the user chose for each site, on pages loaded from now on; the page in view keeps what it has. */
    private fun applyHidden(view: WebView) {
        hiddenScript?.remove()
        hiddenScript = null
        val sites = (if (privateMode) hiddenElements(context).toMap() + privateHidden else hiddenElements(context))
            .mapValues { HiddenElements.parse(it.value) }.filterValues { it.isNotEmpty() }
        hiddenScript = HiddenPages.apply(view, sites)
    }

    /** Lets the user point at parts of the page in view and hide them; false when this WebView can't. */
    fun startPicking(): Boolean {
        val view = web ?: return false
        val key = pageKey ?: return false
        if (!HiddenPages.supported() || siteOf(view.url) == null) return false
        picking = true
        HiddenPages.pick(view, key)
        return true
    }

    fun stopPicking() {
        if (!picking) return
        picking = false
        val view = web ?: return
        pageKey?.let { HiddenPages.stop(view, it) }
    }

    private fun onElementPicked(pageHost: String, selector: String) {
        val view = web ?: return
        val site = Sites.of(pageHost)
        // Only while the user is picking, and only for the site in view.
        if (!picking || siteOf(view.url) != site || !HiddenElements.valid(selector)) return
        if (privateMode) privateHidden[site] = HiddenElements.add(privateHidden[site] ?: hiddenElements(context)[site], selector)
        else hiddenElements(context).let { it[site] = HiddenElements.add(it[site], selector) }
        applyHidden(view)
    }

    /** Brings back the part of the site in view that was hidden last, and reloads the page; false when none is hidden. */
    fun undoHidden(): Boolean {
        val view = web ?: return false
        val site = siteOf(view.url) ?: return false
        val saved = if (privateMode) privateHidden[site] ?: hiddenElements(context)[site] else hiddenElements(context)[site]
        if (HiddenElements.count(saved) == 0) return false
        val rest = HiddenElements.removeLast(saved)
        if (privateMode) privateHidden[site] = rest
        else hiddenElements(context).let { if (rest.isEmpty()) it.remove(site) else it[site] = rest }
        stopPicking()
        applyHidden(view)
        reload()
        return true
    }

    /** The site's own "Browse as" choice for [url], or null when it follows the default in Settings. */
    fun siteUserAgent(url: String?): String? {
        val site = siteOf(url) ?: return null
        // In private browsing "" is a choice of the default, over a saved choice.
        if (privateMode) privateUserAgents[site]?.let { return it.ifEmpty { null } }
        return siteUserAgents(context)[site]
    }

    /** The zoom step kept for [url]'s site, 0 (100%) when none is. */
    fun siteZoom(url: String?): Int {
        val site = siteOf(url) ?: return 0
        return if (privateMode) privateZooms[site] ?: 0 else siteZooms(context)[site]?.toIntOrNull() ?: 0
    }

    /** Keeps [step] for [url]'s site; private browsing keeps it only until it closes. */
    fun setSiteZoom(url: String?, step: Int) {
        val site = siteOf(url) ?: return
        if (privateMode) { if (step == 0) privateZooms.remove(site) else privateZooms[site] = step; return }
        val saved = siteZooms(context)
        if (step == 0) saved.remove(site) else saved[site] = step.toString()
    }

    /** The "Browse as" mode that [url] loads with. */
    fun userAgentMode(url: String?): String = siteUserAgent(url) ?: prefs.userAgent

    /**
     * Remembers [mode] for the site of [url] (null: back to the default in Settings) and reloads the page with it.
     * Private browsing keeps the choice only until it closes.
     */
    fun setSiteUserAgent(url: String, mode: String?) {
        val site = siteOf(url) ?: return
        if (privateMode) {
            privateUserAgents[site] = mode.orEmpty()
        } else {
            val saved = siteUserAgents(context)
            if (mode == null) saved.remove(site) else saved[site] = mode
        }
        web?.let { applyUserAgentFor(it, url) }
        reload()
    }

    /** Gives [view] the "Browse as" mode of [url]'s site before it loads; true when that changed it. */
    private fun applyUserAgentFor(view: WebView, url: String?): Boolean {
        val mode = userAgentMode(url)
        val key = agentKey(mode)
        if (key == appliedUserAgent) return false
        WebViews.applyUserAgent(view, mode, prefs.customUserAgent)
        appliedUserAgent = key
        return true
    }

    private fun agentKey(mode: String) = if (mode == "custom") "custom:${prefs.customUserAgent}" else mode

    private fun siteOf(url: String?): String? = if (isWebUrl(url)) hostOf(url)?.lowercase()?.let(Sites::of) else null

    /** Loads [url] in the view, with the "Browse as" mode of its site. */
    fun load(url: String, headers: Map<String, String> = emptyMap()) {
        val view = web ?: return
        applyUserAgentFor(view, url)
        view.loadUrl(url, headers)
    }

    private var bannerScript: ScriptHandler? = null

    /** Adds or removes the cookie banner script to match the setting; the page in view keeps what it has until it reloads. */
    private fun applyBannerRejection(view: WebView) {
        bannerScript?.remove()
        bannerScript = null
        val key = pageKey ?: return
        if (!prefs.rejectCookieBanners || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val done = if (privateMode) privateBannerSites else cookieBannerSites(context).let { saved ->
            CookieBanners.current(saved, System.currentTimeMillis()).also { keep -> saved.keys.filter { it !in keep }.forEach { saved.remove(it) } }
        }
        bannerScript = WebViewCompat.addDocumentStartJavaScript(view, CookieBanners.script(key.banners, done), setOf("*"))
    }

    /** The cookie banner check is done with the page in view: leave its site alone on pages loaded from now on. */
    private fun onBannerSiteDone(pageHost: String?) {
        val view = web ?: return
        val site = siteOf(view.url) ?: return
        // Only for the site in view, which a page's frames can't name otherwise.
        if (pageHost.isNullOrEmpty() || Sites.of(pageHost.lowercase()) != site) return
        if (privateMode) { if (!privateBannerSites.add(site)) return }
        else cookieBannerSites(context).let { saved ->
            if (site in CookieBanners.current(saved, System.currentTimeMillis())) return
            saved[site] = System.currentTimeMillis().toString()
        }
        applyBannerRejection(view)
    }

    @SuppressLint("RequiresFeature") // MULTI_PROFILE is checked via profilesSupported
    private fun applyGpc(view: WebView) {
        if (prefs.sendGpc) WebViews.addDocumentStartScript(view, GPC_SCRIPT)
        if (!profilesSupported || !WebViewFeature.isFeatureSupported(WebViewFeature.CUSTOM_REQUEST_HEADERS)) return
        val profile = WebViewCompat.getProfile(view)
        if (prefs.sendGpc) profile.addCustomHeader(CustomHeader("Sec-GPC", "1", setOf("*")))
        else profile.clearCustomHeader("Sec-GPC")
    }

    // onRenderProcessGone is implemented below; lint flags it only because the callback starts at API 26.
    @SuppressLint("MissingOnRenderProcessGone")
    private val client = object : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            // A page that went back or forward on its own got here without passing shouldOverrideUrlLoading.
            if (isWebUrl(url) && applyUserAgentFor(view, url)) { view.reload(); return }
            pageHost = AdBlocker.hostOf(url)
            pageUrl = url
            guard.pageStarted()
            // The picker belongs to the page it was started on.
            picking = false
            frames.clear()
            host.onPlaying(false)
            host.onPageStarted(url)
        }

        override fun onPageFinished(view: WebView, url: String) = host.onPageFinished(url)

        override fun onPageCommitVisible(view: WebView, url: String) = host.onPageVisible(url)

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            pageHost = AdBlocker.hostOf(url)
            pageUrl = url
            if (!isReload) host.onVisited(url)
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            if (request.isForMainFrame) {
                // A new document's subresources are requested before onPageStarted reaches the UI thread, so the
                // page host, count and streams switch over here, on the network thread, ahead of them.
                pageHost = request.url.host
                pageUrl = url
                adBlocker.resetPageCount()
                streams.clear()
            } else {
                if (url !in refetching && StreamSniffer.isCandidate(url)) streams.request(url, listed(url), request.requestHeaders)
                if (app.sofabrowser.BuildConfig.DEBUG && extensionOf(url) in setOf("m3u8", "mpd")) android.util.Log.d("SofaStream", "manifest $url")
            }
            return if (blocking(pageHost)) adBlocker.intercept(request, pageUrl, pageHost) else null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            return when (url.scheme?.lowercase()) {
                "http", "https" -> (request.isForMainFrame && blockNavigation(view, request)).also { blocked ->
                    if (request.isForMainFrame && !blocked) mainFrameTarget = url.toString()
                } || (request.isForMainFrame && switchUserAgent(view, url.toString()))
                "about", "data", "blob", "javascript", null -> false
                "intent" -> {
                    // Never launch other apps from web content; follow the page's own web fallback if it has one.
                    val fallback = runCatching { Intent.parseUri(url.toString(), Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url") }.getOrNull()
                    if (fallback != null && (fallback.startsWith("https://") || fallback.startsWith("http://"))) view.loadUrl(fallback)
                    else host.onBlockedLink(url.toString())
                    true
                }
                else -> { host.onBlockedLink(url.toString()); true }
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            // Only the page itself, or the one the main frame is going to, may ask the user. Anything else with a broken
            // certificate (ad frames on such sites often have one) is refused quietly, as Chrome does, instead of
            // putting a dialog over the page.
            val errorHost = hostOf(error.url)
            val mine = error.url == pageUrl || error.url == mainFrameTarget || errorHost == null ||
                errorHost.equals(pageHost, ignoreCase = true) || errorHost.equals(mainFrameTarget?.let(::hostOf), ignoreCase = true)
            if (!mine) {
                debug("certificate error refused: ${error.url}")
                handler.cancel()
            } else {
                host.onSslError(handler, error)
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (view === web) host.onRendererGone()
            return true
        }
    }

    private val chrome = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) = host.onProgress(newProgress)

        override fun onReceivedTitle(view: WebView, title: String?) {
            val url = view.url ?: return
            if (!title.isNullOrBlank()) host.onTitle(url, title)
        }

        override fun onReceivedIcon(view: WebView, icon: Bitmap) {
            view.url?.let { host.onIcon(it, icon) }
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) = host.onEnterFullscreen(view, callback)

        override fun onHideCustomView() = host.onExitFullscreen()

        override fun onPermissionRequest(request: PermissionRequest) {
            // Only DRM playback (Widevine) is ever allowed, per site and after asking. Camera, microphone and
            // MIDI are refused without a prompt; the app doesn't hold those permissions anyway.
            if (request.resources.contentEquals(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))) {
                host.onProtectedMediaRequest(request.origin.toString()) { ok -> if (ok) request.grant(request.resources) else request.deny() }
            } else {
                request.deny()
            }
        }

        @SuppressLint("RequiresFeature") // MULTI_PROFILE is checked via profilesSupported
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            guard.windowRequested(isUserGesture)
            // What the press was on, now, before the page can move things: windows from a link to them are the user's.
            val pressed = pressedLink(view)
            debug("window: gesture=$isUserGesture dialog=$isDialog link=$pressed")
            if (!isUserGesture) { adBlocker.countBlocked(); host.onPopupBlocked(null); return false }
            // Open the target in the same view (no tabs): catch the first URL the throwaway view is sent to.
            val catcher = WebView(view.context)
            // A page can script into a window it opened, so the throwaway view must not reach the normal profile's
            // cookies while browsing privately.
            if (profilesSupported) runCatching { WebViewCompat.setProfile(catcher, WebViewCompat.getProfile(view).name) }
            val opener = pageHost
            var used = false
            @SuppressLint("MissingOnRenderProcessGone") // implemented; the callback starts at API 26
            val catcherClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    if (!used) openWindow(view, request.url.toString(), opener, pressed)
                    used = true
                    v.post { v.destroy() }
                    return true
                }

                override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail): Boolean {
                    v.destroy()
                    return true
                }
            }
            catcher.webViewClient = catcherClient
            // A window that is opened but never navigated (e.g. filled with document.write) is dropped.
            view.postDelayed({ if (!used) { used = true; catcher.destroy() } }, 10_000)
            (resultMsg.obj as WebView.WebViewTransport).webView = catcher
            resultMsg.sendToTarget()
            return true
        }

        override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
            if (app.sofabrowser.BuildConfig.DEBUG) {
                android.util.Log.d("SofaConsole", "${message.messageLevel()} ${message.sourceId()}:${message.lineNumber()} ${message.message()}")
            }
            return true
        }
    }

    // Manifests fetched again by the page itself (VideoRelay.fetch): the answers for one request at a time.

    /** [waiting] maps each address asked for to the origin of the frames asked to fetch it. */
    private class PageFetch(val waiting: MutableMap<String, String>, val done: (Map<String, Pair<String, String>>) -> Unit) {
        val got = HashMap<String, Pair<String, String>>()
    }
    private var pageFetch: PageFetch? = null
    /** What Sofa has the page fetch again: not the page's own fetches, so they don't count as such (StreamSniffer). */
    @Volatile private var refetching: Set<String> = emptySet()

    /**
     * Has the frames that fetched [streams] fetch them again, as their players did. [done] gets, within [timeoutMs],
     * the final address and text of each that came back, by the address asked for. On the main thread.
     */
    fun fetchInPage(streams: List<StreamSniffer.Stream>, timeoutMs: Long, done: (Map<String, Pair<String, String>>) -> Unit) {
        val view = web
        val asks = streams.mapNotNull { s -> StreamResolver.frameOf(s)?.let(PageHeaders::origin)?.let { s.url to it.serialized } }
        if (view == null || asks.isEmpty()) { done(emptyMap()); return }
        pageFetch?.let { finish(it) }
        val f = PageFetch(asks.toMap(HashMap()), done)
        refetching = f.waiting.keys.toSet()
        pageFetch = f
        asks.forEach { (url, origin) -> VideoRelay.fetch(view, url, origin) }
        view.postDelayed({ finish(f) }, timeoutMs)
    }

    /** An answer counts only from a frame of the origin asked ([sender] is WebView's word, not the frame's). */
    private fun fetched(url: String, final: String?, body: String?, sender: String) {
        val f = pageFetch ?: return
        if (!PageHeaders.sameOrigin(f.waiting[url], sender)) {
            if (url in f.waiting && app.sofabrowser.BuildConfig.DEBUG) android.util.Log.d("SofaStream", "ignored answer from $sender for $url")
            return
        }
        f.waiting.remove(url)
        if (body != null) f.got[url] = (final ?: url) to body
        if (f.waiting.isEmpty()) finish(f)
    }

    private fun finish(f: PageFetch) {
        if (pageFetch !== f) return
        pageFetch = null
        refetching = emptySet()
        f.done(f.got)
    }

    /** Panel pixels per app pixel: the TV's display mode (4K on a 4K panel) against the window Android gives apps. */
    private fun panelScale(): Double {
        val dm = context.resources.displayMetrics
        val mode = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.mode
            ?: return 1.0
        return PanelScale.of(mode.physicalWidth, mode.physicalHeight, dm.widthPixels, dm.heightPixels)
    }

    /**
     * When the main frame is about to load [url] (a link or a redirect hop, always a GET) from a site that is
     * browsed as something else, loads it again with that site's mode: a navigation already under way keeps the
     * user agent it started with. Returns true when it did.
     */
    private fun switchUserAgent(view: WebView, url: String): Boolean {
        if (!applyUserAgentFor(view, url)) return false
        // Only the origin, as Chrome's default referrer policy sends to another site, and nothing from https to http.
        val from = pageUrl?.let(Uri::parse)
        val referer = if (from?.scheme == "https" && url.startsWith("http:")) null
            else from?.takeIf { it.scheme == "http" || it.scheme == "https" }?.let { "${it.scheme}://${it.authority}/" }
        view.loadUrl(url, referer?.let { mapOf("Referer" to it) } ?: emptyMap())
        return true
    }

    /** Loads a window's first address in [view], unless the pop-up rules block it. */
    private fun openWindow(view: WebView, url: String, opener: String?, pressed: String?) {
        val sameSite = opener != null && hostOf(url)?.let { Sites.same(it.lowercase(), opener.lowercase()) } == true
        val verdict = guard.newWindow(blocking(opener), isWebUrl(url) && adBlocker.popupListed(url, opener),
            fromLink = pressed.leadsTo(url), sameSite = sameSite)
        debug("window $verdict: $url (from $opener, link=$pressed)")
        if (verdict == NavigationGuard.Verdict.BLOCK) {
            adBlocker.countBlocked()
            guard.blocked(url, SystemClock.uptimeMillis())
            host.onPopupBlocked(url)
        } else {
            // The window came from a press, so its redirects are the user's too.
            guard.userNavigation()
            applyUserAgentFor(view, url)
            view.loadUrl(url)
        }
    }

    /** True when the pop-up and redirect rules (NavigationGuard) stop a main-frame navigation or redirect hop. */
    private fun blockNavigation(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        // The per-site switch of the page the navigation left, not of a host the chain passes through.
        if (!request.isRedirect) chainHost = pageHost
        val target = hostOf(url)?.lowercase()
        val crossSite = target != null && chainHost?.let { !Sites.same(target, it.lowercase()) } == true
        val verdict = guard.navigation(request.hasGesture(), request.isRedirect, isWebUrl(url) && adBlocker.pageListed(url),
            blocking(chainHost), fromLink = pressedLink(view).leadsTo(url), crossSite = crossSite)
        debug("navigation $verdict: $url gesture=${request.hasGesture()} redirect=${request.isRedirect}")
        if (verdict == NavigationGuard.Verdict.ALLOW) return false
        adBlocker.countBlocked()
        guard.blocked(url, SystemClock.uptimeMillis())
        host.onRedirectBlocked()
        return true
    }

    private var chainHost: String? = null

    /**
     * What the last press landed on, from WebView's own hit test (no page script involved): a link's address, "" for an
     * image link (WebView doesn't say where it goes), or null when it wasn't a link.
     */
    private fun pressedLink(view: WebView): String? {
        val hit = view.hitTestResult
        return when (hit.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> hit.extra.orEmpty()
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> ""
            else -> null
        }
    }

    /** Whether a press on the link [this] (see [pressedLink]) explains going to [url]. */
    private fun String?.leadsTo(url: String): Boolean = when {
        this == null -> false
        isEmpty() -> true
        else -> substringBefore('#') == url.substringBefore('#') || hostOf(this)?.equals(hostOf(url), ignoreCase = true) == true
    }

    /** Pop-up and redirect decisions in the log of test builds, for sites that misbehave. */
    private fun debug(message: String) {
        if (app.sofabrowser.BuildConfig.DEBUG) android.util.Log.d("SofaPopup", message)
    }

    private fun listed(url: String) = isWebUrl(url) && hostOf(url)?.let(adBlocker::listed) == true

    /** Sofa is about to load an address itself; its redirects then count as the user's. */
    fun userNavigation() = guard.userNavigation()

    /** Runs [tap], a tap Sofa makes on the page with nobody pressing anything ("Watch by itself"). */
    fun unattended(tap: () -> Unit) {
        tappingAlone = true
        try { tap() } finally { tappingAlone = false }
    }

    // History moves and reloads are the user's own navigations too.
    fun goBack() { guard.userNavigation(); historyStep(-1) }
    fun goForward() { guard.userNavigation(); historyStep(1) }

    /** Goes [steps] through the history, with the "Browse as" mode of the page it reaches. */
    private fun historyStep(steps: Int) {
        val view = web ?: return
        val list = view.copyBackForwardList()
        list.getItemAtIndex(list.currentIndex + steps)?.let { applyUserAgentFor(view, it.url) }
        view.goBackOrForward(steps)
    }
    fun reload() { guard.userNavigation(); web?.reload() }

    /** Whether ad blocking (and with it pop-up and redirect blocking) applies to pages from [pageHost]. */
    private fun blocking(pageHost: String?) = prefs.adBlock && (pageHost == null || !adBlockOff(pageHost))

    // The choices below are saved for good, except in private browsing, which keeps them only until it closes.

    /** Whether ad blocking is turned off for [host]. */
    fun adBlockOff(host: String): Boolean =
        privateAdBlockOff[Allowlist.normalize(host)].takeIf { privateMode } ?: Allowlist.contains(context, host)

    fun setAdBlockOff(host: String, off: Boolean) {
        if (privateMode) privateAdBlockOff[Allowlist.normalize(host)] = off else Allowlist.set(context, host, off)
    }

    /** Whether the remote's keys go to [host]'s pages instead of moving the pointer. */
    fun keysToPage(host: String): Boolean = privateKeysToPage[host].takeIf { privateMode } ?: prefs.keysToPage(host)

    fun setKeysToPage(host: String, on: Boolean) {
        if (privateMode) privateKeysToPage[host] = on else prefs.setKeysToPage(host, on)
    }

    /** [origin]'s answer to the protected-content prompt: true allowed, false blocked, null never answered. */
    fun drmAnswer(origin: String): Boolean? = privateDrmAnswers[origin].takeIf { privateMode } ?: prefs.drmAnswer(origin)

    fun setDrmAnswer(origin: String, allowed: Boolean) {
        if (privateMode) privateDrmAnswers[origin] = allowed else prefs.setDrmAnswer(origin, allowed)
    }

    /** The address blocked last, if "Allow once" may still open it. */
    fun pendingAllow(): String? = guard.pending(SystemClock.uptimeMillis())

    /** Opens the address blocked last, once. */
    fun allowOnce(): String? = guard.takePending(SystemClock.uptimeMillis())

    companion object {
        private const val PRIVATE_PROFILE = "private"
        private val PRESS_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A)
        private const val GPC_SCRIPT =
            "try{Object.defineProperty(Navigator.prototype,'globalPrivacyControl',{get:()=>true,configurable:true})}catch(e){}"

        val profilesSupported get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

        /** Deletes the private profile with all its cookies, storage and cache. Call only with no view using it. */
        @SuppressLint("RequiresFeature") // MULTI_PROFILE is checked via profilesSupported
        fun deletePrivateProfile() {
            if (!profilesSupported) return
            runCatching {
                val store = ProfileStore.getInstance()
                if (PRIVATE_PROFILE in store.allProfileNames) store.deleteProfile(PRIVATE_PROFILE)
            }
        }

        fun isWebUrl(url: String?) = url != null && (url.startsWith("http://") || url.startsWith("https://"))

        fun hostOf(url: String?): String? = url?.let { runCatching { Uri.parse(it).host }.getOrNull() }
    }
}
