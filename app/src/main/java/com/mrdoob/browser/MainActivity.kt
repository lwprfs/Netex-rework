package com.mrdoob.browser

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewFeature
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.mrdoob.browser.DevToolsPrefs.Flag
import com.mrdoob.browser.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "MainActivity"
private const val BLANK_URL = "about:blank"
private const val PROGRESS_ALPHA = 80
private const val PREF_LAST_URL = "last_url"
private const val PREF_CRASHED = "renderer_crashed"
private val SHARE_URL_REGEX = Regex("""https?://\S+""")

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var panel: PanelRenderer

    private val extensionRouter = ExtensionRouter()
    private val extensionBridge = ExtensionBridge(
        router = extensionRouter,
        onOrientationLock = { type -> applyOrientationLock(type) },
        onConsoleEntry = { level, segmentsJson ->
            ConsoleLog.add(ConsoleLog.Entry(
                level = consoleLevelFromName(level),
                segmentsJson = segmentsJson
            ))
        }
    )
    private val panelExtensionBridge = PanelExtensionBridge(extensionRouter)
    private val networkBridge = NetworkBridge { rid, dataUrl -> panel.deliverBlob(rid, dataUrl) }
    private val panelBridge = PanelBridge(
        onFetchBlob = { rid -> fetchBlobFromMain(rid) },
        onEval = { evalId, source -> evalInPage(evalId, source) },
        onHarMenu = { runOnUiThread { showHarSheet() } },
        onHarOpen = { msg -> runOnUiThread { openHarViewer(msg) } },
        onCopy = { text -> runOnUiThread { copyToClipboard(text, "Copied") } },
        onShare = { title, text -> runOnUiThread { shareText(title, text) } },
        onBlock = { pattern -> runOnUiThread { blockPattern(pattern) } },
        onPref = { key, value -> BrowserPrefs.setDev(this, key, value) },
        onReplay = { id, req ->
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { Replayer.run(req) }
                panel.deliverReplay(id, result)
            }
        },
        onDevTools = { msg -> runOnUiThread { handleDevToolsAction(msg) } }
    )
    private val saveHarAs = registerForActivityResult(
        ActivityResultContracts.CreateDocument(HarExporter.MIME_SAVE)
    ) { uri ->
        if (uri != null) lifecycleScope.launch { HarExporter.saveTo(this@MainActivity, uri) }
    }

    private lateinit var floatingControls: FloatingControls
    private lateinit var findBar: FindInPage
    private var appearanceRev = 0
    private var nightMask = 0
    private var siteIcon: Bitmap? = null
    private var defaultMobileUserAgent = ""
    private var appliedCustomUa = ""
    private var afterStoragePermission: (() -> Unit)? = null
    private val requestStoragePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                this,
                "No storage permission — HAR files will be saved in the app's own Downloads folder",
                Toast.LENGTH_LONG
            ).show()
        }
        afterStoragePermission?.invoke()
        afterStoragePermission = null
    }
    private val floatingHost = object : FloatingHost {
        override fun hardReload() = this@MainActivity.hardReload()
        override fun openHarSettings() = showHarSheet()
        override fun openCookieSettings() { CookieSheet(this@MainActivity, currentUrl).show() }
        override fun clearCookiesAndReload() = clearSiteCookiesAndReload()
        override fun saveHar() = ensureStoragePermission {
            lifecycleScope.launch { HarExporter.saveToDownloads(this@MainActivity) }
        }
        override fun shareHar() {
            lifecycleScope.launch { HarExporter.share(this@MainActivity) }
        }
    }

    private lateinit var documentStartScript: String
    private var docStartInjectionAvailable: Boolean = false
    private var progressAnimator: Animator? = null
    private var progressClip: ClipDrawable? = null
    private var imeWasOpen = false
    private var loading = false
    private var panelAnimator: ValueAnimator? = null
    private var lastPanelOpen = false

    private var desktopMode = false
    private lateinit var mobileUserAgent: String
    private var desktopViewportScript: ScriptHandler? = null

    private var currentUrl: String = BLANK_URL
    private var lastStartedNavId = 0L
    private var lastInjectedNav = -1L
    private var loopDialog: androidx.appcompat.app.AlertDialog? = null
    private var currentTitle: String? = null
    private var currentTab = PanelRenderer.Tab.CONSOLE
    private var sourceDirty = true

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var preFullscreenOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    override fun onCreate(savedInstanceState: Bundle?) {

        Appearance.apply(this)
        appearanceRev = Appearance.revision(this)
        nightMask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        progressClip = (binding.urlBubble.background as? LayerDrawable)
            ?.findDrawableByLayerId(android.R.id.progress) as? ClipDrawable

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeOpen = ime.bottom > 0
            if (imeWasOpen && !imeOpen && binding.urlBar.hasFocus()) {
                binding.urlBar.clearFocus()
            }
            imeWasOpen = imeOpen
            v.setPadding(sys.left, sys.top, sys.right, maxOf(sys.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        HarRecorder.init(applicationContext)
        RequestRules.init(applicationContext)
        BrowserLibrary.init(applicationContext)
        BrowserLibrary.preload()
        InjectionStore.init(applicationContext)
        desktopMode = DesktopMode.isEnabled(this)
        documentStartScript = DocumentStartScripts.load(this)
        extensionRouter.bindPageView(binding.webView)
        extensionRouter.bindPanelView(binding.threePanelView)
        installBridges()
        configureWebView()
        panel = PanelRenderer(this, binding.panelView, binding.webView).also { it.init() }
        setupThreeJsPanel(this, binding.threePanelView, panelExtensionBridge)
        floatingControls = FloatingControls(binding.root, floatingHost)
        floatingControls.applyPrefs()
        findBar = FindInPage(binding.root, binding.webView)
        observeDetectionState()
        observeNetworkLog()
        observeHarState()
        observeHarSize()
        observeConsoleLog()
        observeProtection()
        restoreNetworkPanel()
        wireUrlBar()
        wirePanelHeader()
        if (Appearance.rememberTab(this)) {
            BrowserPrefs.getDev(this, "last_tab")?.toIntOrNull()
                ?.takeIf { it in PanelRenderer.Tab.entries.indices }
                ?.let { binding.panelTabs.getTabAt(it)?.select() }
        }
        wireNavigation()
        binding.menuButton.setOnClickListener { showToolsSheet() }
        installBackHandler()
        setupDragHandler()
        binding.webView.roundCorners(22)
        binding.panelView.roundCorners(18)
        binding.threePanelView.roundCorners(18)
        updateNavState()
        updateSecurityIcon()

        excludeFromSystemGestures(binding.navBack)
        excludeFromSystemGestures(binding.threeLogoButton)

        if (savedInstanceState != null) {
            binding.webView.restoreState(savedInstanceState)
        } else {
            val incoming = extractUrlFromIntent(intent) ?: startupUrl()
            if (incoming != null) {
                loadUrl(incoming)
            } else {
                binding.webView.loadUrl(BLANK_URL)
                focusUrlBar()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if ((newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) != nightMask) {
            recreate()
            return
        }
        binding.webView.setBackgroundColor(
            binding.webView.materialColor(com.google.android.material.R.attr.colorSurfaceContainerLowest)
        )
        panel.applyTheme()
    }

    override fun onResume() {
        super.onResume()
        if (Appearance.revision(this) != appearanceRev) {
            recreate()
            return
        }
        applyAddressBarSize()

        if (::floatingControls.isInitialized) floatingControls.applyPrefs()
        applyBrowserPrefs()
        if (::panel.isInitialized) panel.pushPrefs()
    }

    private fun applyBrowserPrefs() {
        val web = binding.webView
        var reload = false
        val js = BrowserPrefs.javaScriptEnabled(this)
        if (web.settings.javaScriptEnabled != js) {
            web.settings.javaScriptEnabled = js
            reload = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, BrowserPrefs.thirdPartyCookies(this))
        val custom = BrowserPrefs.customUserAgent(this)
        if (custom != appliedCustomUa) {
            appliedCustomUa = custom
            mobileUserAgent = custom.ifBlank { defaultMobileUserAgent }
            applyDesktopMode()
            reload = true
        }
        if (reload && hasPage()) web.reload()
    }

    override fun onPause() {
        super.onPause()
        val url = currentUrl.takeIf { it.isNotEmpty() && it != BLANK_URL }
        getPreferences(MODE_PRIVATE).edit().putString(PREF_LAST_URL, url).apply()
        if (DevToolsPrefs.global(DevToolsPrefs.Flag.PRESERVE_RESTART)) {
            NetworkLog.persist(this)
            NavigationTracker.save(this)
        } else {
            NetworkLog.deletePersisted(this)
            NavigationTracker.deletePersisted(this)
        }
    }

    override fun onDestroy() {

        if (fullscreenView != null) exitFullscreen()
        HarRecorder.onStarted = null
        super.onDestroy()
    }

    private fun startupUrl(): String? = when (BrowserPrefs.startupMode(this)) {
        BrowserPrefs.STARTUP_HOME -> BrowserPrefs.homepage(this).takeIf { it.isNotBlank() }
        BrowserPrefs.STARTUP_BLANK -> null
        else -> lastSessionUrl()
    }

    private fun lastSessionUrl(): String? = getPreferences(MODE_PRIVATE)
        .getString(PREF_LAST_URL, null)
        ?.takeIf { it.isNotEmpty() && it != BLANK_URL }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractUrlFromIntent(intent)?.let { loadUrl(it) }
    }

    private fun extractUrlFromIntent(intent: Intent?): String? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
                if (text.isEmpty()) null

                else SHARE_URL_REGEX.find(text)?.value ?: text
            }
            else -> null
        }
    }

    private fun installBridges() {
        val webView = binding.webView

        docStartInjectionAvailable = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (docStartInjectionAvailable) {
            WebViewCompat.addDocumentStartJavaScript(webView, documentStartScript, setOf("*"))
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, ExtensionBridge.JS_OBJECT_NAME, setOf("*"), extensionBridge)
            WebViewCompat.addWebMessageListener(webView, NetworkBridge.JS_OBJECT_NAME, setOf("*"), networkBridge)
            WebViewCompat.addWebMessageListener(binding.panelView, PanelBridge.JS_OBJECT_NAME, setOf("*"), panelBridge)
        } else {
            @SuppressLint("AddJavascriptInterface")
            run {
                webView.addJavascriptInterface(extensionBridge.Fallback(), ExtensionBridge.JS_OBJECT_NAME)
                webView.addJavascriptInterface(networkBridge.Fallback(), NetworkBridge.JS_OBJECT_NAME)
                binding.panelView.addJavascriptInterface(panelBridge.Fallback(), PanelBridge.JS_OBJECT_NAME)
            }
        }

        @SuppressLint("AddJavascriptInterface")
        webView.addJavascriptInterface(HarFlag(), HarFlag.JS_OBJECT_NAME)

        @SuppressLint("AddJavascriptInterface")
        webView.addJavascriptInterface(DevToolsFlag(), DevToolsFlag.JS_OBJECT_NAME)
        @SuppressLint("AddJavascriptInterface")
        webView.addJavascriptInterface(NavGuardFlag(), NavGuardFlag.JS_OBJECT_NAME)

        HarRecorder.onStarted = {
            webView.post {
                webView.evaluateJavascript(
                    "try{window.__netex&&window.__netex.rescan&&window.__netex.rescan()}catch(e){}", null
                )
            }
        }

        if (!docStartInjectionAvailable) {
            Toast.makeText(
                this,
                "Old WebView — detection may be unreliable. Update Android System WebView from Play Store.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        val webView = binding.webView
        webView.setBackgroundColor(
            webView.materialColor(com.google.android.material.R.attr.colorSurfaceContainerLowest)
        )
        val settings = webView.settings
        settings.javaScriptEnabled = BrowserPrefs.javaScriptEnabled(this)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, BrowserPrefs.thirdPartyCookies(this))
        settings.domStorageEnabled = true
        @Suppress("DEPRECATION")
        settings.databaseEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.textZoom = BrowserPrefs.textZoom(this)
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {

            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
        }
        settings.setSupportZoom(true)
        // New-window requests (target=_blank, window.open) reach onCreateWindow, where they are confirmed.
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        mobileUserAgent = settings.userAgentString
            .replace("; wv", "")
            .replace(" wv", "") + " Netex/0.1"
        defaultMobileUserAgent = mobileUserAgent
        appliedCustomUa = BrowserPrefs.customUserAgent(this)
        if (appliedCustomUa.isNotBlank()) mobileUserAgent = appliedCustomUa
        applyDesktopMode()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                // Loop protection's "stop automatic navigation" comes first.
                if (NavigationTracker.onShouldOverride(url, request.isForMainFrame, request.isRedirect, request.hasGesture())) return true
                if (!request.isForMainFrame) return false
                return onMainFrameNavigation(url, request.isRedirect, request.hasGesture())
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                RequestHeaders.remember(request)
                NavigationTracker.onIntercept(request)
                NavConfirm.blockedMainFrame(request)?.let { return it }
                return RequestRules.intercept(request)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                setLoading(true)
                siteIcon = null
                updateFavicon()
                DetectionStore.reset()

                val started = NavigationTracker.onPageStarted(url)
                pageEventAt = System.currentTimeMillis()
                if (started == null || started.first) {
                    val host = DevToolsPrefs.siteKey(url)
                    val type = started?.type ?: NavigationTracker.Type.LOAD
                    if (DevToolsPrefs.preserveFor(type, host)) panel.markNavigation("network", url, started?.navId ?: 0)
                    else NetworkLog.clearForNavigation(
                        DevToolsPrefs.on(DevToolsPrefs.Flag.PRESERVE_FAILED, host),
                        DevToolsPrefs.on(DevToolsPrefs.Flag.PRESERVE_DOCS, host)
                    )
                }
                lastStartedNavId = started?.navId ?: 0
                if (BrowserPrefs.devFlag(this@MainActivity, "keep_console")) panel.markNavigation("console", url)
                else ConsoleLog.reset()
                extensionRouter.onPageNavigationStarted()
                setUrlBubbleProgress(0, animate = false)
                if (!docStartInjectionAvailable && lastInjectedNav != lastStartedNavId) {

                    lastInjectedNav = lastStartedNavId
                    view.evaluateJavascript(documentStartScript, null)
                }
                if (url != null && url != currentUrl) currentTitle = null
                syncUrlBar(url)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                NavigationTracker.onPageFinished(url)
                pageEventAt = System.currentTimeMillis()
                NavConfirm.noteVisited(url)
                NavConfirm.endChain()
                scheduleBridgeCheck(NavigationTracker.currentId(), url)
                setLoading(false)
                updateNavState()
                sourceDirty = true
                maybeRefreshSource()
                BrowserLibrary.recordVisit(url, view.title)

                for (item in InjectionStore.matching(url)) {
                    view.evaluateJavascript(InjectionStore.scriptFor(item), null)
                }
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                syncUrlBar(url)
                updateNavState()
                HarRecorder.onPageVisited(url)
                NavigationTracker.onVisited(url, isReload)
                CookieTools.rememberSite(applicationContext, url)

                sourceDirty = true
                maybeRefreshSource()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                NavigationTracker.onRequestFailed(request, 0, error.description?.toString().orEmpty().ifEmpty { "Network error ${error.errorCode}" })
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: android.webkit.WebResourceResponse) {
                NavigationTracker.onRequestFailed(request, errorResponse.statusCode, "")
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
                Log.w(TAG, "WebView render process gone, recreating activity")

                getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_CRASHED, true).apply()
                NavigationTracker.onRenderGone(detail?.didCrash() ?: false, currentUrl)
                recreate()
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                setLoading(newProgress in 0..99)
                setUrlBubbleProgress(newProgress)
            }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                // Netex has one tab: the new window's address is read from a throw-away WebView, confirmed, then loaded here.
                val temp = WebView(this@MainActivity)
                temp.settings.javaScriptEnabled = false
                val done = java.util.concurrent.atomic.AtomicBoolean(false)
                fun finish(url: String?) {
                    if (url.isNullOrEmpty() || url == "about:blank" || !done.compareAndSet(false, true)) return
                    binding.root.post {
                        temp.stopLoading()
                        temp.destroy()
                        onNewWindowRequest(url, isUserGesture)
                    }
                }
                temp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean { finish(r.url.toString()); return true }
                    override fun onPageStarted(v: WebView, u: String?, f: Bitmap?) { finish(u) }
                    override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): android.webkit.WebResourceResponse? {
                        if (r.isForMainFrame) finish(r.url.toString())
                        return android.webkit.WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                }
                transport.webView = temp
                resultMsg.sendToTarget()
                // window.open() with no address (content written by script) never yields one: do not leak the WebView.
                binding.root.postDelayed({ if (done.compareAndSet(false, true)) temp.destroy() }, 8000)
                return true
            }

            override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
                siteIcon = icon
                updateFavicon()
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                currentTitle = title
                HarRecorder.onPageTitle(title)
                if (!binding.urlBar.hasFocus()) setUrlBarText(currentDisplay())
            }

            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                val segments = JSONArray().put(
                    JSONObject().put("text", message.message() ?: "").put("style", "")
                )
                ConsoleLog.add(ConsoleLog.Entry(
                    level = consoleLevelOf(message.messageLevel()),
                    segmentsJson = segments.toString(),
                    sourceId = message.sourceId()?.takeIf { it.isNotEmpty() },
                    lineNumber = message.lineNumber()
                ))
                return true
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                enterFullscreen(view, callback)
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }
        }
    }

    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (fullscreenView != null) {
            callback.onCustomViewHidden()
            return
        }
        fullscreenView = view
        fullscreenCallback = callback
        preFullscreenOrientation = requestedOrientation
        binding.root.visibility = View.GONE
        (window.decorView as ViewGroup).addView(
            view,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        WindowInsetsControllerCompat(window, view).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun exitFullscreen() {
        val view = fullscreenView ?: return
        (window.decorView as ViewGroup).removeView(view)
        binding.root.visibility = View.VISIBLE
        fullscreenView = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        setRequestedOrientationIfChanged(preFullscreenOrientation)
        WindowInsetsControllerCompat(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    private fun applyOrientationLock(type: String?) {
        if (fullscreenView == null) return
        val target = when (type) {
            null -> preFullscreenOrientation
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            "landscape-primary" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "landscape-secondary" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            "portrait-primary" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "portrait-secondary" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            "any" -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            "natural" -> ActivityInfo.SCREEN_ORIENTATION_USER
            else -> return
        }
        setRequestedOrientationIfChanged(target)
    }

    private fun setRequestedOrientationIfChanged(value: Int) {
        if (requestedOrientation != value) requestedOrientation = value
    }

    private fun observeDetectionState() {
        var appliedTint: Int? = null
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DetectionStore.flow.collect { state ->
                    when (state) {
                        is DetectionState.NotDetected -> {
                            binding.threeLogo.alpha = 0.3f
                            binding.revisionBadge.visibility = View.INVISIBLE
                        }
                        is DetectionState.Detected -> {
                            binding.threeLogo.alpha = 1.0f
                            binding.revisionBadge.text = state.badgeText
                            binding.revisionBadge.visibility = View.VISIBLE
                            val tint = state.backgroundColor?.let(::parseHexColor)
                                ?: binding.revisionBadge.materialColor(com.google.android.material.R.attr.colorPrimary)
                            if (tint != appliedTint) {
                                binding.revisionBadge.background?.mutate()?.setTint(tint)
                                appliedTint = tint
                            }
                        }
                    }
                }
            }
        }
    }

    private fun observeNetworkLog() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                NetworkLog.events.collect { event ->
                    when (event) {
                        is NetworkLog.Event.Added -> {
                            val displayUrl = UrlDisplay.shorten(event.record.url, currentUrl)
                            panel.appendNetworkRow(event.record, displayUrl)
                        }
                        NetworkLog.Event.Cleared -> panel.clearNetwork()
                        is NetworkLog.Event.Trimmed -> panel.clearForNavigation(event.keepFailed, event.keepDocs)
                        is NetworkLog.Event.Patched -> panel.patchNetworkRow(event.requestId, event.status, event.failed, event.error)
                    }
                }
            }
        }
    }

    private fun observeHarState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                HarRecorder.status.map { it.state }.distinctUntilChanged().collect { state ->
                    panel.setHarState(state)
                    tintMenuButton(state)
                    floatingControls.onHarState(state)
                }
            }
        }
    }

    private fun observeHarSize() {
        lifecycleScope.launch {
            var warned = false
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                HarRecorder.status
                    .combine(HarRecorder.fileBytes) { st, file -> Triple(maxOf(st.bytes, file), st.entries, st.nearLimit) }
                    .distinctUntilChanged()
                    .collect { (bytes, count, near) ->
                        val warn = near && HarOptions.warnNearLimit
                        floatingControls.onSize(bytes, count, warn)
                        if (warn && !warned) {
                            warned = true
                            Toast.makeText(this@MainActivity, "HAR is almost full — pause and save it soon", Toast.LENGTH_LONG).show()
                        } else if (!near) {
                            warned = false
                        }
                    }
            }
        }
    }

    private fun tintMenuButton(state: HarRecorder.State) {
        val color = when (state) {
            HarRecorder.State.RECORDING -> R.color.nx_rec
            HarRecorder.State.PAUSED -> R.color.nx_paused
            HarRecorder.State.IDLE -> null
        }
        if (color == null) {
            binding.recBadge.visibility = View.GONE
        } else {
            binding.recBadge.backgroundTintList =
                android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(this, color))
            binding.recBadge.visibility = View.VISIBLE
        }
    }

    private fun userNavigated(kind: String) {
        NavigationTracker.noteUserAction(kind)
        if (NavigationTracker.holding) releaseHold()
    }

    private fun releaseHold() {
        ProtectionEngine.release()
        binding.webView.resumeTimers()
    }

    private fun scheduleBridgeCheck(navId: Long, url: String?) {
        if (navId == 0L || !BrowserPrefs.javaScriptEnabled(this) || !NavigationGuard.isWebUrl(url)) return
        binding.webView.postDelayed({ ProtectionEngine.checkBridge(navId, url) }, 1500)
    }

    private fun observeProtection() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    ProtectionEngine.ui.collect { ui ->
                        when (ui) {
                            is ProtectionEngine.Ui.Notice ->
                                com.google.android.material.snackbar.Snackbar.make(binding.root, ui.text, 6000).apply {
                                    if (ui.detailsAction) setAction("Details") {
                                        openPanelTab(PanelRenderer.Tab.NETWORK)
                                        handleDevToolsAction(JSONObject().put("action", "get-detection"))
                                    }
                                }.show()
                            is ProtectionEngine.Ui.LoopWarning -> showLoopDialog(ui)
                            is ProtectionEngine.Ui.ReinitBridge -> reinitBridge(ui.navId)
                        }
                    }
                }
                launch { NavConfirm.forms.collect { handleFormAsk(it) } }
                launch {
                    kotlinx.coroutines.flow.merge(
                        DevToolsPrefs.revision.map { 0 }, HarRecorder.status.map { 0 }, DevToolsEvents.revision.map { 0 }
                    ).collect { pushDevStatus() }
                }
            }
        }
    }

    private fun pushDevStatus() {
        if (!::panel.isInitialized) return
        val host = DevToolsPrefs.currentHost
        val det = JSONObject(ProtectionEngine.detectionJson(host))
        val o = JSONObject()
            .put("enabled", DevToolsPrefs.on(Flag.ENABLED, host))
            .put("auto", DevToolsPrefs.on(Flag.AUTO_MASTER, host))
            .put("compat", host != null && DevToolsPrefs.compatActive(host))
            .put("activated", if (host == null) 0 else DevToolsPrefs.activations(host).map { it.label }.distinct().size)
            .put("loop", NavigationTracker.loopIndicator())
            .put("recording", HarRecorder.status.value.state != HarRecorder.State.IDLE)
            .put("recordingPersist", DevToolsPrefs.on(Flag.REC_CONTINUOUS, host))
            .put("detection", if (det.optBoolean("found")) det.optString("confidence") else "none")
        panel.setDevStatus(o.toString())
    }

    private fun showLoopDialog(ui: ProtectionEngine.Ui.LoopWarning) {
        if (loopDialog?.isShowing == true) return
        val seq = ui.state.sequence.joinToString("\n") { s ->
            "#${s.navId} ${s.type.name.lowercase()}  ${UrlDisplay.shorten(s.url, s.url)}"
        }
        val note = "Netex cannot block a page that reloads itself with JavaScript; Stop refuses automatic " +
            "navigations and pauses page timers until you navigate yourself. Logs and recording are kept either way."
        loopDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Navigation loop detected")
            .setMessage("${ui.host} keeps navigating without you doing anything.\n\n$seq\n\n$note")
            .setPositiveButton("Stop automatic navigation") { _, _ ->
                ProtectionEngine.hold(currentUrl)
                binding.webView.stopLoading()
                binding.webView.pauseTimers()
            }
            .setNegativeButton("Continue") { _, _ -> NavigationTracker.dismissLoop() }
            .setNeutralButton("Details") { _, _ ->
                openPanelTab(PanelRenderer.Tab.NETWORK)
                handleDevToolsAction(JSONObject().put("action", "get-nav"))
            }
            .show()
    }

    private fun reinitBridge(navId: Long) {
        if (navId != NavigationTracker.currentId()) return
        val js = "(function(){try{if(window.__netex&&window.__netex.hello){window.__netex.hello();return 'hello';}return 'missing';}catch(e){return 'error';}})()"
        binding.webView.evaluateJavascript(js) { r ->
            if (r != null && r.contains("missing")) binding.webView.evaluateJavascript(documentStartScript, null)
        }
    }

    private fun restoreNetworkPanel() {
        val crashed = getPreferences(MODE_PRIVATE).getBoolean(PREF_CRASHED, false)
        if (crashed) getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_CRASHED, false).apply()
        val snap = NetworkLog.snapshot()
        if (snap.isNotEmpty() && (NetworkLog.restoredFromDisk || DevToolsPrefs.on(Flag.PRESERVE_CONTEXT))) {
            snap.forEach { panel.appendNetworkRow(it, UrlDisplay.shorten(it.url, it.url)) }
        }
        NetworkLog.markRestoredShown()
        if (crashed) ProtectionEngine.onRecreatedAfterCrash()
    }


    // --- HAR viewer (the Network tab's HAR button) ---------------------------------------

    private var harViewFile: java.io.File? = null

    private fun openHarViewer(msg: JSONObject) {
        when (msg.optString("action")) {
            "share" -> { harViewFile?.let { HarExporter.shareFile(this, it) }; return }
            "save" -> { harViewFile?.let { f -> lifecycleScope.launch { HarExporter.saveFile(this@MainActivity, f) } }; return }
            "settings" -> { showHarSheet(); return }
        }
        val filter = msg.optString("filter", "all").filter { it.isLetterOrDigit() }.ifEmpty { "all" }
        val pageUrl = currentUrl
        lifecycleScope.launch {
            val json = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val har = HarRecorder.buildViewerHar(filter)
                val dir = java.io.File(cacheDir, "har/view").apply { mkdirs() }
                val file = java.io.File(dir, HarBuilder.fileBase(listOf(pageUrl)) + "_" + filter + ".har")
                file.writeText(har.text)
                harViewFile = file
                val limit = 600_000
                JSONObject()
                    .put("filter", filter)
                    .put("count", har.count)
                    .put("bytes", file.length())
                    .put("name", file.name)
                    .put("source", if (har.fromRecording) "recording" else "log")
                    .put("truncated", har.text.length > limit)
                    .put("preview", har.text.take(limit))
                    .toString()
            }
            panel.deliverDevTools("showHar", json)
        }
    }


    // --- Navigation confirmation ----------------------------------------------------------------------------

    private var pageEventAt = 0L

    private val passSchemes = setOf("about", "data", "blob", "javascript", "file", "content", "ws", "wss", "view-source")

    /** shouldOverrideUrlLoading for the main frame. Returns true when the navigation is cancelled here. */
    private fun onMainFrameNavigation(url: String, isRedirect: Boolean, hasGesture: Boolean): Boolean {
        if (!BrowserPrefs.navigationConfirmation(this)) return false
        if (NavConfirm.passOnce(url)) return false
        if (!NavPolicy.isWeb(url)) {
            val scheme = NavPolicy.schemeOf(url) ?: return false
            if (scheme in passSchemes) return false
            val req = NavPolicy.Request(currentUrl, url, NavPolicy.Type.EXTERNAL_SCHEME, hasGesture)
            val action = evaluateNavigation(req, emptyList()) { ok -> if (ok) continueNavigation(req, emptyList()) }
            // WebView cannot open another app by itself, so even an allowed link is launched (and cancelled) here.
            if (action == NavPolicy.Action.ALLOW) launchExternalApp(url)
            return true
        }
        val current = currentUrl.takeIf { NavPolicy.isWeb(it) } ?: return false
        val from = if (isRedirect) NavigationTracker.lastHopUrl() ?: current else current

        if (isRedirect && NavConfirm.inApprovedChain()) {
            // The user already approved this chain: no second dialog. Blocked domains still apply.
            val host = NavPolicy.hostOf(url)
            if (host != null && NavPolicy.listMatches(NavConfirm.list(NavConfirm.ListKind.BLOCKED), host, false)) {
                refuse(url, "$host is on your blocked list (redirect chain)")
                return true
            }
            val hops = NavConfirm.noteChainHop(url)
            if (hops == 4 || hops == 8) {
                com.google.android.material.snackbar.Snackbar.make(binding.root, "This redirect chain has $hops hops", 4000).show()
            }
            return false
        }

        val userInit = hasGesture || (isRedirect && NavigationTracker.activeStartedByUser())
        val type = NavConfirm.classify(url, isRedirect, hasGesture)
        val hops = NavigationTracker.activeHopCount()
        val chain = if (isRedirect) {
            val seen = NavigationTracker.activeHopUrls()
            (if (seen.firstOrNull() != current) listOf(current) else emptyList()) + seen + url
        } else emptyList()
        val req = NavPolicy.Request(from, url, type, userInit, System.currentTimeMillis() - pageEventAt, hops)
        val action = evaluateNavigation(req, chain) { ok -> if (ok) continueNavigation(req, chain) }
        return action != NavPolicy.Action.ALLOW
    }

    /** A target=_blank link or window.open(): confirm, then load in this tab. */
    private fun onNewWindowRequest(url: String, userGesture: Boolean) {
        if (!BrowserPrefs.navigationConfirmation(this)) { binding.webView.loadUrl(url); return }
        val type = if (NavPolicy.isWeb(url)) NavPolicy.Type.NEW_WINDOW else NavPolicy.Type.EXTERNAL_SCHEME
        val req = NavPolicy.Request(currentUrl, url, type, userGesture, System.currentTimeMillis() - pageEventAt, 0)
        val action = evaluateNavigation(req, emptyList()) { ok -> if (ok) continueNavigation(req, emptyList()) }
        if (action == NavPolicy.Action.ALLOW) continueNavigation(req, emptyList())
    }

    /**
     * Applies [NavPolicy]. ALLOW: the caller lets the navigation go. BLOCK or ASK: the caller must cancel it; for ASK
     * a dialog follows and [resume] gets the user's answer. Only one dialog is ever open, and a destination the user
     * just cancelled is dropped silently, so a page that keeps trying cannot create a loop or a stream of dialogs.
     */
    private fun evaluateNavigation(req: NavPolicy.Request, chain: List<String>, resume: (Boolean) -> Unit): NavPolicy.Action {
        val d = NavPolicy.decide(req, NavConfirm.config())
        return when (d.action) {
            NavPolicy.Action.ALLOW -> NavPolicy.Action.ALLOW
            NavPolicy.Action.BLOCK -> {
                refuse(req.to, d.reason)
                NavPolicy.Action.BLOCK
            }
            NavPolicy.Action.ASK -> {
                val key = NavConfirm.keyOf(req.to)
                // Only navigation nobody asked for is dropped silently; a tap always gets its dialog.
                if (!req.userInitiated && NavConfirm.recentlyCancelled(key)) {
                    silentRepeat(req.to, key)
                    NavPolicy.Action.BLOCK
                } else if (!NavConfirm.beginPrompt(key)) {
                    NavPolicy.Action.BLOCK
                } else {
                    // A moment for the page's own hints (navigation type) to arrive before the dialog is built.
                    binding.root.postDelayed({ showNavigationDialog(req, d, chain, key, resume) }, 150)
                    NavPolicy.Action.ASK
                }
            }
        }
    }

    private fun showNavigationDialog(
        req: NavPolicy.Request, d: NavPolicy.Decision, chain: List<String>, key: String, resume: (Boolean) -> Unit
    ) {
        if (isFinishing || isDestroyed) { NavConfirm.endPrompt(); return }
        val type = if (req.type == NavPolicy.Type.AUTOMATIC) NavConfirm.classify(req.to, false, false) else req.type
        val external = type == NavPolicy.Type.EXTERNAL_SCHEME
        val info = NavConfirmDialog.Info(
            currentSite = NavPolicy.hostOf(req.from) ?: "",
            destinationSite = NavPolicy.hostOf(req.to) ?: (NavPolicy.schemeOf(req.to) ?: "unknown") + ": link",
            url = req.to,
            type = type,
            initiator = type.initiator,
            reason = d.reason,
            relation = d.relation,
            chain = chain.map { NavPolicy.hostOf(it) ?: it },
            warnings = d.warnings,
            external = external
        )
        val entry = if (external) (NavPolicy.schemeOf(req.to) ?: "") + ":" else NavPolicy.hostOf(req.to) ?: ""
        NavConfirmDialog.show(this, info) { choice ->
            NavConfirm.endPrompt()
            when (choice) {
                NavConfirmDialog.Choice.CANCEL -> {
                    NavConfirm.noteCancelled(key)
                    NavConfirm.report(DevToolsEvents.Severity.INFO, req.to, "Cancelled by you (${type.label})", listOf("Stayed on the page"))
                    NavConfirm.logRefused(req.to, "Cancelled by user")
                    resume(false)
                }
                NavConfirmDialog.Choice.CONTINUE -> {
                    NavConfirm.report(DevToolsEvents.Severity.INFO, req.to, "Allowed once by you (${type.label})")
                    resume(true)
                }
                NavConfirmDialog.Choice.ALWAYS_ALLOW -> {
                    if (entry.isNotEmpty()) NavConfirm.addToList(NavConfirm.ListKind.ALLOWED, entry)
                    NavConfirm.report(DevToolsEvents.Severity.INFO, req.to, "Always allowed: $entry", listOf("Added to allowed list"))
                    resume(true)
                }
                NavConfirmDialog.Choice.ALWAYS_BLOCK -> {
                    if (entry.isNotEmpty()) NavConfirm.addToList(NavConfirm.ListKind.BLOCKED, entry)
                    NavConfirm.noteCancelled(key)
                    NavConfirm.report(DevToolsEvents.Severity.WARNING, req.to, "Always blocked: $entry", listOf("Added to blocked list"))
                    NavConfirm.logRefused(req.to, "Blocked by user: $entry")
                    resume(false)
                }
            }
        }
    }

    /** The user said yes: load it here (or launch the app). Marked as the user's own navigation so it is not a loop. */
    private fun continueNavigation(req: NavPolicy.Request, chain: List<String>) {
        if (req.type == NavPolicy.Type.EXTERNAL_SCHEME) { launchExternalApp(req.to); return }
        NavConfirm.allowOnce(req.to)
        NavConfirm.approveChain(chain.ifEmpty { listOfNotNull(req.from, req.to) })
        userNavigated("load")
        val headers = HashMap<String, String>()
        val origin = NavPolicy.originOf(req.from)
        if (req.type != NavPolicy.Type.HTTP_REDIRECT && origin != null && NavPolicy.originOf(req.to) != origin) headers["Referer"] = "$origin/"
        binding.webView.loadUrl(req.to, headers)
    }

    private fun refuse(url: String, reason: String) {
        val key = NavConfirm.keyOf(url)
        val n = NavConfirm.noteSilentBlock("b:$key")
        NavConfirm.report(DevToolsEvents.Severity.WARNING, url, reason, listOf("Navigation blocked"))
        NavConfirm.logRefused(url, reason)
        if (n == 1 || n % 5 == 0) {
            com.google.android.material.snackbar.Snackbar.make(binding.root, "Blocked navigation to ${NavPolicy.hostOf(url) ?: url}", 4000).show()
        }
    }

    private fun silentRepeat(url: String, key: String) {
        val n = NavConfirm.noteSilentBlock(key)
        NavConfirm.report(DevToolsEvents.Severity.INFO, url, "Repeated navigation attempt dropped after you cancelled it", listOf("No dialog shown"))
        if (n == 3) {
            val host = NavPolicy.hostOf(url)
            com.google.android.material.snackbar.Snackbar.make(binding.root, "This page keeps trying to open ${host ?: url}", 6000).apply {
                if (host != null) setAction("Always block") {
                    NavConfirm.addToList(NavConfirm.ListKind.BLOCKED, host)
                    NavConfirm.logRefused(url, "Blocked by user: $host")
                }
            }.show()
        }
    }

    private fun launchExternalApp(url: String) {
        try {
            val intent = if (url.startsWith("intent:", ignoreCase = true)) Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            else Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
            // A web page must not choose the component, a selector or URI grants.
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.component = null
            intent.selector = null
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            val fallback = try {
                if (url.startsWith("intent:", true)) Intent.parseUri(url, Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url") else null
            } catch (_: Exception) { null }
            if (NavPolicy.isWeb(fallback)) binding.webView.loadUrl(fallback!!)
            else Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "This link could not be opened", Toast.LENGTH_SHORT).show()
        }
    }

    /** A form on the page wants to leave the site (nav-guard.js held it). */
    private fun handleFormAsk(ask: NavConfirm.FormAsk) {
        if (!NavPolicy.isWeb(ask.to)) return
        val req = NavPolicy.Request(ask.from, ask.to, NavPolicy.Type.FORM, true, System.currentTimeMillis() - pageEventAt, 0)
        fun reply(ok: Boolean) {
            if (ok) NavPolicy.hostOf(ask.to)?.let { NavConfirm.allowHostOnce(it) }
            val id = org.json.JSONObject.quote(ask.id)
            binding.webView.evaluateJavascript("window.__netex&&window.__netex.navResume&&window.__netex.navResume($id,$ok)", null)
        }
        val action = evaluateNavigation(req, emptyList()) { ok -> reply(ok) }
        if (action == NavPolicy.Action.ALLOW) reply(true)
        else if (action == NavPolicy.Action.BLOCK) reply(false)
    }

    private fun handleDevToolsAction(msg: JSONObject) {
        val host = DevToolsPrefs.siteKey(currentUrl) ?: DevToolsPrefs.currentHost
        when (msg.optString("action")) {
            "set-preserve" -> DevToolsPrefs.userSet(Flag.PRESERVE_NAV, msg.optBoolean("on"), host)
            "get-nav" -> panel.deliverDevTools("showNavHistory", NavigationTracker.snapshotJson())
            "get-events" -> panel.deliverDevTools("showEvents", DevToolsEvents.toJson())
            "clear-events" -> { DevToolsEvents.clear(); panel.deliverDevTools("showEvents", DevToolsEvents.toJson()) }
            "export-events" -> shareText("Netex developer tools events", DevToolsEvents.exportText())
            "clear-nav" -> NavigationTracker.clearHistory()
            "get-detection" -> panel.deliverDevTools("showDetection", ProtectionEngine.detectionJson(host))
            "stop-nav" -> {
                ProtectionEngine.hold(currentUrl)
                binding.webView.stopLoading()
                binding.webView.pauseTimers()
            }
            "continue-nav" -> releaseHold()
            "site-preserve" -> if (host != null) {
                DevToolsPrefs.setSite(host, Flag.PRESERVE_NAV, true)
                DevToolsPrefs.setSite(host, Flag.PRESERVE_RELOAD, true)
                DevToolsPrefs.setSite(host, Flag.PRESERVE_REDIRECT, true)
            }
            "site-nav" -> if (host != null) {
                DevToolsPrefs.setSite(host, Flag.NAV_TRACKING, true)
                DevToolsPrefs.setSite(host, Flag.LOOP_DETECT, true)
                DevToolsPrefs.setSite(host, Flag.REDIRECT_MONITOR, true)
            }
            "site-compat" -> if (host != null) DevToolsPrefs.setSite(host, Flag.COMPAT_ENABLED, true)
            "open-settings" -> openSettings()
        }
        pushDevStatus()
    }

    private fun showToolsSheet() {
        ToolsSheet(this, object : ToolsHost {
            override val isDesktopMode: Boolean get() = desktopMode
            override val currentTextZoom: Int get() = BrowserPrefs.textZoom(this@MainActivity)
            override val pageUrl: String get() = currentUrl
            override fun changeDesktopMode(on: Boolean) = setDesktopMode(on)
            override fun changeTextZoom(percent: Int) {
                BrowserPrefs.setTextZoom(this@MainActivity, percent)
                binding.webView.settings.textZoom = percent
            }
            override fun openSettings() = this@MainActivity.openSettings()
            override val isBookmarked: Boolean get() = BrowserLibrary.isBookmarked(currentUrl)
            override fun findInPage() { findBar.show() }
            override fun toggleBookmark() = this@MainActivity.toggleBookmark()
            override fun openLibrary() { startActivity(Intent(this@MainActivity, LibraryActivity::class.java)) }
            override fun openInjections() { InjectionSheet(this@MainActivity, injectionHost).show() }
            override fun sharePage() = this@MainActivity.sharePage()
            override fun copyLink() {
                if (hasPage()) copyToClipboard(currentUrl, "Link copied")
            }
            override fun openExternal() = this@MainActivity.openExternal()
            override fun hardReload() = this@MainActivity.hardReload()
            override fun openCookies() { CookieSheet(this@MainActivity, currentUrl).show() }
            override fun openHar() = showHarSheet()
            override fun clearBrowsingData() = confirmClearBrowsingData()
        }).show()
    }

    private fun applyAddressBarSize() {
        val h = dp(if (Appearance.addressBarCompact(this)) 44 else 50)
        val lp = binding.urlBubble.layoutParams
        if (lp.height != h) {
            lp.height = h
            binding.urlBubble.layoutParams = lp
        }
    }

    private fun updateFavicon() {
        val show = siteIcon != null && !binding.urlBar.hasFocus()
        binding.faviconIcon.visibility = if (show) View.VISIBLE else View.GONE
        if (show) binding.faviconIcon.setImageBitmap(siteIcon)
    }

    private fun showUrlActions(anchor: View) {
        anchor.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        val menu = android.widget.PopupMenu(this, anchor)
        val hasPage = hasPage() && currentUrl.startsWith("http")
        if (hasPage) menu.menu.add(0, 1, 0, "Copy link")
        menu.menu.add(0, 2, 1, "Paste and go")
        if (hasPage) {
            menu.menu.add(0, 3, 2, "Share page")
            menu.menu.add(0, 4, 3, "Open in another app")
            menu.menu.add(0, 5, 4, "Hard reload")
        }
        menu.menu.add(0, 6, 5, "Find in page")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> copyToClipboard(currentUrl, "Link copied")
                2 -> {
                    val clip = (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
                    if (clip.isEmpty()) Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                    else loadUrl(clip)
                }
                3 -> shareText(currentTitle ?: currentUrl, currentUrl)
                4 -> startActivity(
                    Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)), "Open with")
                        .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this, MainActivity::class.java)))
                )
                5 -> hardReload()
                6 -> findBar.show()
            }
            true
        }
        menu.show()
    }

    /** True while the developer panel owns the whole screen (page, address bar and tab row hidden). */
    private var panelFullscreen = false
    private var heightBeforeFullscreen = 0

    private fun panelHeightPx(): Int =
        if (panelFullscreen) binding.root.height else binding.bottomPanel.layoutParams.height

    private fun enterPanelFullscreen() {
        if (panelFullscreen) return
        panelAnimator?.cancel()
        val current = binding.bottomPanel.layoutParams.height
        heightBeforeFullscreen = if (current > 0) current else minOf(maxPanelHeight(), (binding.root.height * 0.5f).toInt())
        panelFullscreen = true
        binding.webView.visibility = View.GONE
        binding.chromeContainer.visibility = View.GONE
        binding.panelTabRow.visibility = View.GONE
        binding.panelFullBar.visibility = View.VISIBLE
        binding.panelFullTitle.text = binding.panelTabs.getTabAt(currentTab.ordinal)?.text ?: ""
        binding.panelFullClear.visibility = if (currentTab == PanelRenderer.Tab.CONSOLE || currentTab == PanelRenderer.Tab.NETWORK) View.VISIBLE else View.INVISIBLE
        val params = binding.bottomPanel.layoutParams
        params.height = ViewGroup.LayoutParams.MATCH_PARENT
        binding.bottomPanel.layoutParams = params
        ProtectionEngine.setPanelOpen(true)
        floatingControls.setSuppressed(true)
        if (currentTab == PanelRenderer.Tab.SOURCE) maybeRefreshSource()
    }

    /** Back button of the full-screen panel: return to the page with the drawer open at its previous size. */
    private fun exitPanelFullscreen() {
        if (!panelFullscreen) return
        panelFullscreen = false
        binding.webView.visibility = View.VISIBLE
        binding.chromeContainer.visibility = View.VISIBLE
        binding.panelFullBar.visibility = View.GONE
        binding.panelTabRow.visibility = View.VISIBLE
        floatingControls.setSuppressed(false)
        val params = binding.bottomPanel.layoutParams
        params.height = 0
        binding.bottomPanel.layoutParams = params
        binding.root.post {
            val h = heightBeforeFullscreen.coerceIn(dp(120), maxOf(dp(120), maxPanelHeight()))
            setPanelHeight(h)
        }
    }

    private fun toggleFullscreenPanel() {
        if (panelFullscreen) exitPanelFullscreen() else enterPanelFullscreen()
    }

    private fun hardReload() {
        binding.webView.clearCache(true)
        userNavigated("reload")
        binding.webView.reload()
    }

    private fun clearSiteCookiesAndReload() {
        if (!hasPage() || !currentUrl.startsWith("http")) {
            Toast.makeText(this, "Open a website first", Toast.LENGTH_SHORT).show()
            return
        }
        if (CookieTools.isWhitelisted(this, currentUrl)) {
            Toast.makeText(this, "Cookies kept — this site is on your whitelist", Toast.LENGTH_SHORT).show()
            return
        }
        val url = currentUrl
        CookieTools.clearSite(url) {
            runOnUiThread {
                clearSiteStorage(url)
                Toast.makeText(this, "Cookies and site data deleted", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun clearSiteStorage(url: String) {
        try {
            val uri = Uri.parse(url)
            val port = if (uri.port != -1) ":${uri.port}" else ""
            WebStorage.getInstance().deleteOrigin("${uri.scheme}://${uri.host}$port")
        } catch (_: Exception) {

        }

        binding.webView.evaluateJavascript(
            "try{localStorage.clear();sessionStorage.clear()}catch(e){};0"
        ) { hardReload() }
    }

    private val injectionHost = object : InjectionHost {
        override val pageUrl: String get() = currentUrl
        override fun runOnPage(script: String) { binding.webView.evaluateJavascript(script, null) }
    }

    private fun toggleBookmark() {
        if (!hasPage() || !currentUrl.startsWith("http")) {
            Toast.makeText(this, "Open a website first", Toast.LENGTH_SHORT).show()
            return
        }
        val url = currentUrl
        if (BrowserLibrary.isBookmarked(url)) {
            BrowserLibrary.removeBookmarkByUrl(url)
            Toast.makeText(this, "Bookmark removed", Toast.LENGTH_SHORT).show()
        } else {
            val title = currentTitle?.takeIf { it.isNotBlank() } ?: Uri.parse(url).host ?: url
            BrowserLibrary.addBookmark(title, url)
            Toast.makeText(this, "Bookmarked", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openSettings() {
        startActivity(
            Intent(this, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_URL, currentUrl.takeIf { it.startsWith("http") })
        )
    }

    private fun ensureStoragePermission(then: () -> Unit) {
        val needed = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (!needed) return then()
        afterStoragePermission = then
        requestStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    private fun hasPage(): Boolean = currentUrl.isNotEmpty() && currentUrl != BLANK_URL

    private fun shareText(title: String, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, title.ifEmpty { "Share" }))
    }

    private fun blockPattern(pattern: String) {
        val p = pattern.trim()
        if (p.isEmpty()) return
        val rules = RequestRules.rules()
        if (rules.any { it.pattern == p && it.action == RequestRules.Action.BLOCK }) {
            Toast.makeText(this, "Already blocked", Toast.LENGTH_SHORT).show()
            return
        }
        RequestRules.save(rules + RequestRules.Rule(pattern = p, action = RequestRules.Action.BLOCK))
        Toast.makeText(this, "Blocked $p — reload to see the effect", Toast.LENGTH_LONG).show()
    }

    private fun copyToClipboard(text: String, message: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Netex", text))
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun sharePage() {
        if (!hasPage()) return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, currentUrl)
            currentTitle?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
        startActivity(Intent.createChooser(send, "Share page"))
    }

    private fun openExternal() {
        if (!hasPage()) return
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)), "Open with"))
        } catch (_: Exception) {
            Toast.makeText(this, "No app can open this", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmClearBrowsingData() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear browsing data?")
            .setMessage("This removes cache, cookies, site storage and history, and signs you out of sites.")
            .setPositiveButton("Clear") { _, _ ->
                binding.webView.clearCache(true)
                binding.webView.clearHistory()
                WebStorage.getInstance().deleteAllData()
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                updateNavState()
                Toast.makeText(this, "Browsing data cleared", Toast.LENGTH_SHORT).show()
                if (hasPage()) binding.webView.reload()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyDesktopMode() {
        binding.webView.settings.userAgentString =
            if (desktopMode) DesktopMode.userAgent(mobileUserAgent) else mobileUserAgent
        desktopViewportScript?.remove()
        desktopViewportScript = null
        if (desktopMode && docStartInjectionAvailable) {
            desktopViewportScript = WebViewCompat.addDocumentStartJavaScript(
                binding.webView, DesktopMode.VIEWPORT_SCRIPT, setOf("*")
            )
        }
    }

    private fun setDesktopMode(enabled: Boolean) {
        if (enabled == desktopMode) return
        desktopMode = enabled
        DesktopMode.setEnabled(this, enabled)
        applyDesktopMode()
        binding.webView.reload()
    }

    private fun showHarSheet() {
        HarSheet(this) { name -> saveHarAs.launch(name) }.show()
    }

    private fun observeConsoleLog() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ConsoleLog.events.collect { event ->
                    when (event) {
                        is ConsoleLog.Event.Added -> panel.appendConsoleEntry(event.entry)
                        ConsoleLog.Event.Cleared -> panel.clearConsole()
                    }
                }
            }
        }
    }

    private fun consoleLevelOf(level: ConsoleMessage.MessageLevel?): ConsoleLog.Level = when (level) {
        ConsoleMessage.MessageLevel.WARNING -> ConsoleLog.Level.WARN
        ConsoleMessage.MessageLevel.ERROR -> ConsoleLog.Level.ERROR
        ConsoleMessage.MessageLevel.DEBUG -> ConsoleLog.Level.DEBUG
        else -> ConsoleLog.Level.LOG
    }

    private fun consoleLevelFromName(name: String): ConsoleLog.Level = when (name.lowercase()) {
        "warn", "warning" -> ConsoleLog.Level.WARN
        "error" -> ConsoleLog.Level.ERROR
        "debug" -> ConsoleLog.Level.DEBUG
        "info" -> ConsoleLog.Level.INFO
        else -> ConsoleLog.Level.LOG
    }

    private fun wireUrlBar() {
        binding.urlBar.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadUrl(v.text.toString())
                v.clearFocus()
                hideKeyboard(v)
                true
            } else false
        }
        binding.urlBar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.securityIcon.setImageResource(R.drawable.ic_search)
                binding.urlBar.setText(if (currentUrl == BLANK_URL) "" else currentUrl)

                binding.urlBar.post { binding.urlBar.selectAll() }
            } else {
                updateSecurityIcon()
                setUrlBarText(currentDisplay())
            }
            updateFavicon()
        }
    }

    private fun dismissUrlEditing() {
        if (binding.urlBar.hasFocus()) {
            binding.urlBar.clearFocus()
            hideKeyboard(binding.urlBar)
        }
    }

    private fun wireNavigation() {
        binding.navBack.setOnClickListener { if (binding.webView.canGoBack()) { userNavigated("back"); binding.webView.goBack() } }
        binding.navForward.setOnClickListener { if (binding.webView.canGoForward()) { userNavigated("forward"); binding.webView.goForward() } }
        binding.navDevtools.setOnClickListener { togglePanel() }
        binding.dragHandle.setOnClickListener { togglePanel() }
        binding.panelReload.setOnClickListener {
            if (loading) { NavigationTracker.noteUserAction("stop"); binding.webView.stopLoading() }
            else { userNavigated("reload"); binding.webView.reload() }
        }
        binding.panelReload.setOnLongClickListener { showUrlActions(it); true }
        binding.faviconIcon.setOnLongClickListener { showUrlActions(it); true }
        binding.securityIcon.setOnLongClickListener { showUrlActions(it); true }
        binding.securityIcon.setOnClickListener {
            Toast.makeText(this, binding.securityIcon.contentDescription, Toast.LENGTH_SHORT).show()
        }
        binding.threeLogoButton.setOnClickListener {
            dismissUrlEditing()
            openPanelTab(PanelRenderer.Tab.THREEJS)
        }
    }

    private fun updateNavState() {
        val back = binding.webView.canGoBack()
        val fwd = binding.webView.canGoForward()
        binding.navBack.isEnabled = back
        binding.navBack.alpha = if (back) 1f else 0.35f
        binding.navForward.isEnabled = fwd
        binding.navForward.alpha = if (fwd) 1f else 0.35f
    }

    private fun setLoading(on: Boolean) {
        if (on == loading) return
        loading = on
        binding.panelReload.setImageResource(if (on) R.drawable.ic_close else R.drawable.ic_refresh)
        binding.panelReload.contentDescription = if (on) "Stop" else "Reload"
    }

    private fun updateSecurityIcon() {
        val (icon, label) = when {
            currentUrl.startsWith("https://") -> R.drawable.ic_lock to "Secure connection"
            currentUrl.startsWith("http://") -> R.drawable.ic_warning to "Not secure"
            else -> R.drawable.ic_search to "Search"
        }
        binding.securityIcon.setImageResource(icon)
        binding.securityIcon.contentDescription = label
    }

    private fun wirePanelHeader() {
        binding.panelTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                switchTab(PanelRenderer.Tab.entries[tab.position])
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        binding.panelClear.setOnClickListener {
            when (currentTab) {
                PanelRenderer.Tab.CONSOLE -> ConsoleLog.reset()
                PanelRenderer.Tab.NETWORK -> NetworkLog.reset()
                else -> Unit
            }
        }
        binding.panelClose.setOnClickListener { animatePanelTo(0) }
        binding.panelExpand.setOnClickListener { enterPanelFullscreen() }
        binding.panelFullBack.setOnClickListener { exitPanelFullscreen() }
        binding.panelFullClear.setOnClickListener { binding.panelClear.performClick() }
    }

    private fun excludeFromSystemGestures(view: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        view.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
            v.systemGestureExclusionRects = listOf(Rect(0, 0, r - l, b - t))
        }
    }

    private fun switchTab(tab: PanelRenderer.Tab) {
        if (tab == currentTab) return
        currentTab = tab
        if (Appearance.rememberTab(this)) BrowserPrefs.setDev(this, "last_tab", tab.ordinal.toString())
        binding.panelClear.visibility =
            if (tab == PanelRenderer.Tab.CONSOLE || tab == PanelRenderer.Tab.NETWORK) View.VISIBLE else View.INVISIBLE
        val showThreeJs = tab == PanelRenderer.Tab.THREEJS
        binding.panelView.visibility = if (showThreeJs) View.GONE else View.VISIBLE
        binding.threePanelView.visibility = if (showThreeJs) View.VISIBLE else View.GONE
        if (!showThreeJs) panel.setTab(tab)
        if (tab == PanelRenderer.Tab.SOURCE) maybeRefreshSource()
    }

    private fun hideKeyboard(view: View) {
        ViewCompat.getWindowInsetsController(view)?.hide(WindowInsetsCompat.Type.ime())
    }

    private fun showKeyboard(view: View) {
        ViewCompat.getWindowInsetsController(view)?.show(WindowInsetsCompat.Type.ime())
    }

    private fun focusUrlBar() {
        binding.urlBar.requestFocus()
        binding.urlBar.post { showKeyboard(binding.urlBar) }
    }

    private fun setUrlBarText(text: String) {
        if (binding.urlBar.text?.toString() != text) binding.urlBar.setText(text)
    }

    private fun syncUrlBar(url: String?) {
        val safe = url.orEmpty()
        if (safe.isNotEmpty()) currentUrl = safe
        if (!binding.urlBar.hasFocus()) updateSecurityIcon()
        if (!binding.urlBar.hasFocus()) setUrlBarText(currentDisplay())
    }

    private fun currentDisplay(): String {
        if (currentUrl.isEmpty() || currentUrl == BLANK_URL) return getString(R.string.new_tab)
        val host = Uri.parse(currentUrl).host?.removePrefix("www.")
        if (!host.isNullOrEmpty()) return host
        currentTitle?.takeIf { it.isNotBlank() && it != BLANK_URL }?.let { return it }
        return getString(R.string.loading)
    }

    private fun setUrlBubbleProgress(percent: Int, animate: Boolean = true) {
        val clip = progressClip ?: return

        progressAnimator?.cancel()
        progressAnimator = null

        val target = percent.coerceIn(0, 100) * 100

        if (!animate || target < clip.level) {
            clip.alpha = PROGRESS_ALPHA
            clip.level = target
            return
        }

        clip.alpha = PROGRESS_ALPHA
        progressAnimator = if (percent >= 100) {
            val fill = ValueAnimator.ofInt(clip.level, 10000).apply {
                duration = Appearance.scaled(this@MainActivity, 200L)
                addUpdateListener { clip.level = it.animatedValue as Int }
            }
            val fade = ValueAnimator.ofInt(PROGRESS_ALPHA, 0).apply {
                duration = Appearance.scaled(this@MainActivity, 250L)
                startDelay = 80L
                addUpdateListener { clip.alpha = it.animatedValue as Int }
            }
            AnimatorSet().apply {
                playSequentially(fill, fade)
                start()
            }
        } else {
            ValueAnimator.ofInt(clip.level, target).apply {
                duration = Appearance.scaled(this@MainActivity, 300L)
                interpolator = DecelerateInterpolator()
                addUpdateListener { clip.level = it.animatedValue as Int }
                start()
            }
        }
    }

    private fun loadUrl(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return
        val normalized = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("about:") || trimmed.startsWith("file:") -> trimmed
            looksLikeUrl(trimmed) -> "https://$trimmed"
            else -> SearchEngines.searchUrl(this, trimmed)
        }
        userNavigated("load")
        binding.webView.loadUrl(normalized)
    }

    private fun looksLikeUrl(input: String): Boolean {
        if (input.contains(' ')) return false
        val host = Uri.parse("https://$input").host?.takeIf { it.isNotEmpty() } ?: return false
        if (host.equals("localhost", ignoreCase = true)) return true
        if (Patterns.IP_ADDRESS.matcher(host).matches()) return true
        val tld = host.substringAfterLast('.', "").lowercase()
        return tld in knownTlds(this)
    }

    companion object {

        private var cachedTlds: Set<String>? = null

        private fun knownTlds(context: Context): Set<String> = cachedTlds ?: context
            .resources.openRawResource(R.raw.tlds)
            .bufferedReader().useLines { it.filter(String::isNotBlank).toSet() }
            .also { cachedTlds = it }
    }

    private fun installBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (panelFullscreen) {
                    exitPanelFullscreen()
                } else if (::findBar.isInitialized && findBar.isShown) {
                    findBar.hide()
                } else if (fullscreenView != null) {
                    exitFullscreen()
                } else if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    private fun setupDragHandler() {
        var startHeight = 0
        val onStart: () -> Unit = {
            panelAnimator?.cancel()
            startHeight = binding.bottomPanel.layoutParams.height
        }

        val onMove: (Float) -> Unit = { dy ->
            setPanelHeight((startHeight - dy.toInt()).coerceIn(0, maxPanelHeight()))
        }

        val onEnd: (Float) -> Unit = {
            val h = binding.bottomPanel.layoutParams.height
            if (h in 1 until dp(72)) animatePanelTo(0)
        }
        binding.chromeContainer.onDragStart = onStart
        binding.chromeContainer.onDragMove = onMove
        binding.chromeContainer.onDragEnd = onEnd
    }

    private fun maxPanelHeight(): Int {
        val minPageHeight = dp(96)
        return (binding.root.height - binding.chromeContainer.height - minPageHeight).coerceAtLeast(0)
    }

    private fun isPanelOpen(): Boolean = panelHeightPx() > 0

    private fun togglePanel() {
        if (isPanelOpen()) animatePanelTo(0)
        else animatePanelTo(minOf(maxPanelHeight(), (binding.root.height * 0.5f).toInt()))
    }

    private fun openPanelTab(tab: PanelRenderer.Tab) {
        if (!isPanelOpen()) togglePanel()
        binding.panelTabs.getTabAt(tab.ordinal)?.select()
    }

    private fun animatePanelTo(target: Int) {
        panelAnimator?.cancel()
        val from = binding.bottomPanel.layoutParams.height
        if (from == target) return
        panelAnimator = ValueAnimator.ofInt(from, target).apply {
            duration = Appearance.scaled(this@MainActivity, 240L)
            interpolator = DecelerateInterpolator()
            addUpdateListener { setPanelHeight(it.animatedValue as Int) }
            start()
        }
    }

    private fun setPanelHeight(h: Int) {
        val p = binding.bottomPanel
        val params = p.layoutParams
        if (params.height == h) return
        val wasClosed = params.height == 0
        params.height = h
        p.layoutParams = params
        val open = h > 0
        ProtectionEngine.setPanelOpen(open)
        if (open != lastPanelOpen) {
            lastPanelOpen = open
            if (open) binding.navDevtools.setColorFilter(
                binding.navDevtools.materialColor(com.google.android.material.R.attr.colorPrimary)
            ) else binding.navDevtools.clearColorFilter()
        }
        val max = maxPanelHeight()
        if (max > 0) {
            binding.panelExpand.setImageResource(R.drawable.ic_fullscreen)
            binding.panelExpand.contentDescription = "Open developer tools full screen"
        }
        if (wasClosed && h > 0 && currentTab == PanelRenderer.Tab.SOURCE) maybeRefreshSource()
    }

    private fun maybeRefreshSource() {
        if (!sourceDirty) return
        if (currentTab != PanelRenderer.Tab.SOURCE) return
        if (panelHeightPx() <= 0) return
        sourceDirty = false
        panel.refreshSource()
    }

    private fun fetchBlobFromMain(rid: String) {
        binding.webView.evaluateJavascript(
            "window.__netex && window.__netex.findBlob(${JSONObject.quote(rid)})",
            null
        )
    }

    private fun evalInPage(evalId: String, source: String) {
        val js = "(window.__netex && window.__netex.consoleEval) " +
            "? window.__netex.consoleEval(${JSONObject.quote(source)}) " +
            ": [false,'console eval not ready']"
        binding.webView.evaluateJavascript(js) { result ->
            panel.deliverEvalResult(evalId, result ?: "null")
        }
    }

    private fun parseHexColor(hex: String): Int? = try { Color.parseColor(hex) } catch (_: IllegalArgumentException) { null }
}
