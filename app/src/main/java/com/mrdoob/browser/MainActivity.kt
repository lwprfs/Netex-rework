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
private const val PROGRESS_ALPHA = 80 // ~31% of 255
private const val PREF_LAST_URL = "last_url"
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
        onCopy = { text -> runOnUiThread { copyToClipboard(text, "Copied") } },
        onShare = { title, text -> runOnUiThread { shareText(title, text) } },
        onBlock = { pattern -> runOnUiThread { blockPattern(pattern) } },
        onPref = { key, value -> BrowserPrefs.setDev(this, key, value) },
        onReplay = { id, req ->
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { Replayer.run(req) }
                panel.deliverReplay(id, result)
            }
        }
    )
    private val saveHarAs = registerForActivityResult(
        ActivityResultContracts.CreateDocument(HarExporter.MIME_SAVE)
    ) { uri ->
        if (uri != null) lifecycleScope.launch { HarExporter.saveTo(this@MainActivity, uri) }
    }

    // Floating pop-up (record / reload / cookies). Storage permission is only needed on Android 9-.
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
    private var currentTitle: String? = null
    private var currentTab = PanelRenderer.Tab.CONSOLE
    private var sourceDirty = true

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var preFullscreenOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    override fun onCreate(savedInstanceState: Bundle?) {
        // Accent colour / Material You dynamic colours (Settings → Appearance) before any view is built.
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
        // Reserve the edge-adjacent chrome buttons from Android's back-gesture region,
        // otherwise a tap near the screen edge gets swallowed as a back swipe.
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
        // Light <-> dark: rebuild so every view picks up the other palette.
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
            recreate() // theme / dynamic colours / accent changed in Settings
            return
        }
        applyAddressBarSize()
        // Settings may have changed the floating bubble (shown / ring buttons / haptics).
        if (::floatingControls.isInitialized) floatingControls.applyPrefs()
        applyBrowserPrefs()
        if (::panel.isInitialized) panel.pushPrefs()
    }

    /** Applies Settings → Browser (JavaScript, third-party cookies, user agent) to the live WebView. */
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
    }

    override fun onDestroy() {
        // onRenderProcessGone → recreate() and process death mid-fullscreen leave the
        // custom view attached to decorView; clean up so we don't leak.
        if (fullscreenView != null) exitFullscreen()
        super.onDestroy()
    }

    /** Page to open at launch, following Settings → Startup (restore last page / homepage / blank). */
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
                // If the share is a "Check this out: https://..." style blob,
                // pull the first URL out — otherwise feed the raw text to loadUrl
                // (which routes plain text to search).
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

        // Lets the network shim know whether a HAR recording is running (extra capture work only then).
        @SuppressLint("AddJavascriptInterface")
        webView.addJavascriptInterface(HarFlag(), HarFlag.JS_OBJECT_NAME)

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
            // WebView checks system night mode itself; pages with their own dark theme are left alone.
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
        }
        settings.setSupportZoom(true)
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
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

            // Never intercepts; only remembers the real request headers for HAR export.
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                RequestHeaders.remember(request)
                return RequestRules.intercept(request) // block / mock / delay rules; null = normal
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                setLoading(true)
                siteIcon = null
                updateFavicon()
                DetectionStore.reset()
                // "Preserve log" keeps old rows and just marks the navigation; otherwise start clean.
                if (BrowserPrefs.devFlag(this@MainActivity, "keep_network")) panel.markNavigation("network", url)
                else NetworkLog.reset()
                if (BrowserPrefs.devFlag(this@MainActivity, "keep_console")) panel.markNavigation("console", url)
                else ConsoleLog.reset()
                extensionRouter.onPageNavigationStarted()
                setUrlBubbleProgress(0, animate = false)
                if (!docStartInjectionAvailable) {
                    // Racy fallback: best-effort injection before page scripts run.
                    view.evaluateJavascript(documentStartScript, null)
                }
                if (url != null && url != currentUrl) currentTitle = null
                syncUrlBar(url)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                setLoading(false)
                updateNavState()
                sourceDirty = true
                maybeRefreshSource()
                BrowserLibrary.recordVisit(url, view.title)
                // Scripts & styles from Menu → Scripts & styles that match this site.
                for (item in InjectionStore.matching(url)) {
                    view.evaluateJavascript(InjectionStore.scriptFor(item), null)
                }
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                syncUrlBar(url)
                updateNavState()
                HarRecorder.onPageVisited(url)
                CookieTools.rememberSite(applicationContext, url)
                // SPA route changes (history.pushState) skip onPageFinished — keep source honest.
                sourceDirty = true
                maybeRefreshSource()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
                Log.w(TAG, "WebView render process gone, recreating activity")
                recreate()
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                setLoading(newProgress in 0..99)
                setUrlBubbleProgress(newProgress)
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

    // Per the Screen Orientation API, lock() is only valid in fullscreen — rotating the
    // whole browser otherwise would be hostile.
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

    // setRequestedOrientation is not a no-op when the value is unchanged — it round-trips
    // through ActivityClient. Pages can spam lock() during transitions; gate the write.
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

    // Live numbers on the floating recording pill: request count plus size, the larger of the
    // captured-body estimate (updates per request) and the real .har file size on disk (a few
    // seconds behind). Also warns once when the session gets close to its limit.
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

    /** Long-press on the lock, site icon or reload button: quick actions for the current page. */
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

    private fun toggleFullscreenPanel() {
        val max = maxPanelHeight()
        val current = binding.bottomPanel.layoutParams.height
        if (current >= max - dp(8)) animatePanelTo(minOf(max, (binding.root.height * 0.5f).toInt()))
        else animatePanelTo(max)
    }

    private fun hardReload() {
        binding.webView.clearCache(true)
        binding.webView.reload()
    }

    /**
     * Floating "delete cookies" button: removes this site's cookies, local/session storage and
     * IndexedDB, then hard reloads. Sites on the cookie whitelist are left untouched.
     */
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

    /** Clears local storage, session storage and IndexedDB of [url]'s origin, then hard reloads. */
    private fun clearSiteStorage(url: String) {
        try {
            val uri = Uri.parse(url)
            val port = if (uri.port != -1) ":${uri.port}" else ""
            WebStorage.getInstance().deleteOrigin("${uri.scheme}://${uri.host}$port")
        } catch (_: Exception) {
            // best effort
        }
        // Session storage is per tab and can only be emptied from inside the page.
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

    /** Runs [then] straight away, or after the permission prompt (granted or not) on Android 9 and older. */
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

    /** "Block URL / domain" from the Network tab: adds a Block rule (manage them in Settings → Request rules). */
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
                // Wait until ACTION_UP's cursor placement settles, otherwise it clears the selection.
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
        binding.navBack.setOnClickListener { if (binding.webView.canGoBack()) binding.webView.goBack() }
        binding.navForward.setOnClickListener { if (binding.webView.canGoForward()) binding.webView.goForward() }
        binding.navDevtools.setOnClickListener { togglePanel() }
        binding.dragHandle.setOnClickListener { togglePanel() }
        binding.panelReload.setOnClickListener {
            if (loading) binding.webView.stopLoading() else binding.webView.reload()
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
        binding.panelExpand.setOnClickListener { toggleFullscreenPanel() }
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
        // Bundled IANA TLD list; file extensions like ".js" / ".py" fall through to search.
        private var cachedTlds: Set<String>? = null

        private fun knownTlds(context: Context): Set<String> = cachedTlds ?: context
            .resources.openRawResource(R.raw.tlds)
            .bufferedReader().useLines { it.filter(String::isNotBlank).toSet() }
            .also { cachedTlds = it }
    }

    private fun installBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::findBar.isInitialized && findBar.isShown) {
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
        // Y axis grows downward -> up-drag has negative dy -> panel grows.
        val onMove: (Float) -> Unit = { dy ->
            setPanelHeight((startHeight - dy.toInt()).coerceIn(0, maxPanelHeight()))
        }
        // A barely-open panel snaps shut; otherwise it stays where released.
        val onEnd: (Float) -> Unit = {
            val h = binding.bottomPanel.layoutParams.height
            if (h in 1 until dp(72)) animatePanelTo(0)
        }
        binding.chromeContainer.onDragStart = onStart
        binding.chromeContainer.onDragMove = onMove
        binding.chromeContainer.onDragEnd = onEnd
    }

    // The page WebView keeps a minimum height: collapsed to zero, pages see a
    // zero-sized viewport (WebGPU swapchains reject it outright).
    private fun maxPanelHeight(): Int {
        val minPageHeight = dp(96)
        return (binding.root.height - binding.chromeContainer.height - minPageHeight).coerceAtLeast(0)
    }

    private fun isPanelOpen(): Boolean = binding.bottomPanel.layoutParams.height > 0

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
        if (open != lastPanelOpen) {
            lastPanelOpen = open
            if (open) binding.navDevtools.setColorFilter(
                binding.navDevtools.materialColor(com.google.android.material.R.attr.colorPrimary)
            ) else binding.navDevtools.clearColorFilter()
        }
        val max = maxPanelHeight()
        if (max > 0) {
            val full = h > 0 && h >= max - dp(8)
            binding.panelExpand.setImageResource(if (full) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
            binding.panelExpand.contentDescription = if (full) "Shrink developer tools" else "Expand developer tools"
        }
        if (wasClosed && h > 0 && currentTab == PanelRenderer.Tab.SOURCE) maybeRefreshSource()
    }

    private fun maybeRefreshSource() {
        if (!sourceDirty) return
        if (currentTab != PanelRenderer.Tab.SOURCE) return
        if (binding.bottomPanel.layoutParams.height <= 0) return
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
