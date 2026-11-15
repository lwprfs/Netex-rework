package com.mrdoob.browser

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewTreeObserver
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.mrdoob.browser.databinding.ItemCategoryRowBinding
import com.mrdoob.browser.databinding.ItemOptionRowBinding

/**
 * Settings: a landing page of categories (with search) and one page per [SettingsCategory], all in
 * this one activity. Everything is saved immediately; the browser picks the changes up when this
 * screen closes. Rows carry stable ids from [SettingsCatalog] so search can open and highlight them.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"
        private const val STATE_CATEGORY = "category"
        private const val STATE_QUERY = "query"
        private const val STATE_LANDING_SCROLL = "landing_scroll"
        private const val MENU_SEARCH = 1
        private const val HIGHLIGHT_MS = 1400L
        private const val VALUE_MAX_WIDTH_DP = 150
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var searchLayout: TextInputLayout
    private lateinit var searchField: TextInputEditText
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private var searchItem: MenuItem? = null

    private var category: SettingsCategory? = null
    private var query = ""
    private var landingScroll = 0
    private var focusId: String? = null
    private val rows = HashMap<String, View>()

    private var pageUrl: String? = null
    private var afterPermission: (() -> Unit)? = null

    private val requestStorage = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                this,
                "No storage permission — HAR files will be saved in the app's own Downloads folder",
                Toast.LENGTH_LONG
            ).show()
        }
        afterPermission?.invoke()
        afterPermission = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Appearance.apply(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Settings can be restored on their own after the process died.
        HarOptions.init(applicationContext)
        RequestRules.init(applicationContext)
        pageUrl = intent.getStringExtra(EXTRA_URL)
        category = SettingsCategory.fromId(savedInstanceState?.getString(STATE_CATEGORY))
        query = savedInstanceState?.getString(STATE_QUERY).orEmpty()
        landingScroll = savedInstanceState?.getInt(STATE_LANDING_SCROLL) ?: 0

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        toolbar = MaterialToolbar(this).apply {
            setNavigationIcon(R.drawable.ic_back)
            setNavigationContentDescription("Back")
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
            searchItem = menu.add(0, MENU_SEARCH, 0, "Search settings").apply {
                setIcon(R.drawable.ic_search)
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                iconTintList = ColorStateList.valueOf(
                    materialColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            }
            setOnMenuItemClickListener {
                if (it.itemId == MENU_SEARCH) { showSearch(); true } else false
            }
        }
        root.addView(toolbar, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        searchLayout = buildSearchField()
        root.addView(searchLayout, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(16), dp(4), dp(16), dp(4)) })

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(24))
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            // The keyboard must never cover the search results.
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    category != null -> closeCategory()
                    query.isNotEmpty() -> searchField.setText("") // first back clears the search
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })

        render(restoreScroll = if (category == null) landingScroll else 0)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_CATEGORY, category?.id)
        outState.putString(STATE_QUERY, query)
        outState.putInt(STATE_LANDING_SCROLL, if (category == null) scroll.scrollY else landingScroll)
    }

    // --- Navigation ----------------------------------------------------------------------------

    private fun openCategory(target: SettingsCategory, focus: String? = null) {
        if (category == null) landingScroll = scroll.scrollY
        category = target
        focusId = focus
        hideKeyboard()
        render()
    }

    private fun closeCategory() {
        category = null
        hideKeyboard()
        render(restoreScroll = landingScroll)
    }

    private fun showSearch() {
        category = null
        render()
        searchField.requestFocus()
        WindowInsetsControllerCompat(window, searchField).show(WindowInsetsCompat.Type.ime())
    }

    private fun hideKeyboard() {
        searchField.clearFocus()
        WindowInsetsControllerCompat(window, content).hide(WindowInsetsCompat.Type.ime())
    }

    /** Draws the current page. Only the page being shown is built, never the whole hierarchy. */
    private fun render(restoreScroll: Int = 0) {
        content.removeAllViews()
        rows.clear()
        val c = category
        searchLayout.visibility = if (c == null) View.VISIBLE else View.GONE
        searchItem?.isVisible = c != null
        toolbar.title = c?.title ?: "Settings"
        when {
            c != null -> buildCategory(c)
            query.isNotBlank() -> buildResults()
            else -> buildLanding()
        }
        val focus = focusId.also { focusId = null }
        afterLayout {
            scroll.scrollTo(0, restoreScroll)
            if (focus != null) rows[focus]?.let { highlight(it) }
        }
    }

    /** Runs once the page just built has been measured, so scroll positions and row offsets are real. */
    private fun afterLayout(block: () -> Unit) {
        content.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                content.viewTreeObserver.removeOnGlobalLayoutListener(this)
                block()
            }
        })
    }

    /** After a dialog or toggle changed what a page shows: redraw it in place, keeping the scroll position. */
    private fun refresh() = render(restoreScroll = scroll.scrollY)

    private fun highlight(row: View) {
        var y = 0
        var v: View = row
        while (v !== content) { y += v.top; v = v.parent as? View ?: return }
        scroll.smoothScrollTo(0, (y - dp(96)).coerceAtLeast(0))
        val original = row.background
        row.background = ColorDrawable(row.materialColor(com.google.android.material.R.attr.colorSecondaryContainer))
        row.postDelayed({ row.background = original }, HIGHLIGHT_MS)
    }

    // --- Landing page and search ---------------------------------------------------------------

    private fun buildSearchField(): TextInputLayout {
        val layout = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle)
        val radius = dp(28).toFloat()
        layout.apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(radius, radius, radius, radius)
            boxBackgroundColor = materialColor(com.google.android.material.R.attr.colorSurfaceContainerHigh)
            boxStrokeWidth = 0
            boxStrokeWidthFocused = dp(2)
            isHintEnabled = false
            setStartIconDrawable(R.drawable.ic_search)
            endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
        }
        searchField = TextInputEditText(layout.context).apply {
            hint = "Search settings"
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = InputType.TYPE_CLASS_TEXT
            setText(query)
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); true } else false
            }
        }
        layout.addView(searchField)
        // Attached after setText so restoring the query doesn't trigger a second render.
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString().orEmpty().trim()
                if (q == query) return
                query = q
                if (category == null) render()
            }
        })
        return layout
    }

    private fun buildLanding() {
        val groups = listOf(
            null to listOf(SettingsCategory.APPEARANCE, SettingsCategory.GENERAL, SettingsCategory.BROWSER),
            "Privacy" to listOf(SettingsCategory.PRIVACY, SettingsCategory.COOKIES),
            "Developer" to listOf(SettingsCategory.DEVELOPER, SettingsCategory.FLOATING,
                SettingsCategory.CAPTURE, SettingsCategory.RULES)
        )
        for ((heading, items) in groups) {
            if (heading != null) section(heading)
            val card = card()
            for (c in items) categoryRow(card, c)
        }
    }

    private fun buildResults() {
        val results = SettingsCatalog.search(query)
        if (results.isEmpty) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(24), dp(56), dp(24), dp(24))
            }
            box.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_search)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                imageTintList = ColorStateList.valueOf(materialColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            }, dp(48), dp(48))
            box.addView(TextView(this, null, 0, R.style.SheetRowTitle).apply {
                text = "No settings match “$query”"
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(4))
            })
            box.addView(TextView(this, null, 0, R.style.SheetRowSubtitle).apply {
                text = "Try another word, such as dark, history, cookies or desktop."
                gravity = Gravity.CENTER
            })
            content.addView(box, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            return
        }
        if (results.categories.isNotEmpty()) {
            section("Pages")
            val card = card()
            for (c in results.categories) categoryRow(card, c)
        }
        if (results.settings.isNotEmpty()) {
            section("Settings")
            val card = card()
            for (s in results.settings) {
                val r = ItemOptionRowBinding.inflate(layoutInflater, card, false)
                r.optIcon.setImageResource(s.category.icon)
                r.optTitle.text = s.title
                r.optSubtitle.text = "${s.category.title} · ${s.description}"
                r.optChevron.visibility = View.VISIBLE
                r.root.setOnClickListener { openCategory(s.category, s.id) }
                card.addView(r.root)
            }
        }
    }

    private fun categoryRow(parent: ViewGroup, c: SettingsCategory) {
        val r = ItemCategoryRowBinding.inflate(layoutInflater, parent, false)
        r.catIcon.setImageResource(c.icon)
        r.catTitle.text = c.title
        r.catDescription.text = c.description
        r.root.setOnClickListener { openCategory(c) }
        parent.addView(r.root)
    }

    // --- Category pages ------------------------------------------------------------------------

    private fun buildCategory(c: SettingsCategory) {
        content.addView(TextView(this, null, 0, R.style.SheetSubtitle).apply {
            text = c.description
            textSize = 14f
            setPadding(dp(6), dp(4), dp(6), dp(4))
        })
        when (c) {
            SettingsCategory.APPEARANCE -> buildAppearance()
            SettingsCategory.GENERAL -> buildGeneral()
            SettingsCategory.BROWSER -> buildBrowser()
            SettingsCategory.PRIVACY -> buildPrivacy()
            SettingsCategory.DEVELOPER -> buildDevTools()
            SettingsCategory.FLOATING -> buildFloating()
            SettingsCategory.CAPTURE -> buildCapture()
            SettingsCategory.RULES -> buildRules()
            SettingsCategory.COOKIES -> buildCookies()
        }
    }

    private fun buildAppearance() {
        section("Theme and colour")
        val card = card()
        valueRow(card, "appearance_theme", R.drawable.ic_tune, "Theme", "Light, dark, or follow the system",
            when (Appearance.themeMode(this)) {
                Appearance.THEME_LIGHT -> "Light"
                Appearance.THEME_DARK -> "Dark"
                else -> "System"
            }) {
            val modes = listOf(Appearance.THEME_SYSTEM, Appearance.THEME_LIGHT, Appearance.THEME_DARK)
            choiceDialog("Theme", arrayOf("Follow system", "Light", "Dark"),
                modes.indexOf(Appearance.themeMode(this))) { which ->
                Appearance.setThemeMode(this, modes[which])
                recreate()
            }
        }
        if (Appearance.dynamicAvailable()) {
            switchRow(card, "appearance_dynamic", R.drawable.ic_image, "Dynamic colours",
                "Use your wallpaper's Material You colours",
                { Appearance.dynamicColors(this) }, { Appearance.setDynamicColors(this, it); recreate() })
        } else {
            row(card, "appearance_dynamic", R.drawable.ic_image, "Dynamic colours", "Needs Android 12 or newer")
        }
        valueRow(card, "appearance_accent", R.drawable.ic_filter, "Accent colour",
            if (Appearance.dynamicColors(this)) "Not used while dynamic colours are on" else "Colour of buttons, tabs and highlights",
            Appearance.accent(this).name.substringBefore(" (")) {
            val checked = Appearance.ACCENTS.indexOfFirst { it.id == Appearance.accentId(this) }
            choiceDialog("Accent colour", Appearance.ACCENTS.map { it.name }.toTypedArray(), checked) { which ->
                Appearance.setAccent(this, Appearance.ACCENTS[which].id)
                recreate()
            }
        }

        section("Layout and motion")
        val layout = card()
        switchRow(layout, "appearance_compact", R.drawable.ic_search, "Compact address bar",
            "A slimmer address bar leaves more room for the page",
            { Appearance.addressBarCompact(this) }, { Appearance.setAddressBarCompact(this, it) })
        valueRow(layout, "appearance_animations", R.drawable.ic_timer, "Animations",
            "Speed of panel, progress and bubble animations",
            when (Appearance.animations(this)) {
                Appearance.ANIM_OFF -> "Off"
                Appearance.ANIM_REDUCED -> "Reduced"
                else -> "Normal"
            }) {
            val values = listOf(Appearance.ANIM_NORMAL, Appearance.ANIM_REDUCED, Appearance.ANIM_OFF)
            choiceDialog("Animations", arrayOf("Normal", "Reduced", "Off"),
                values.indexOf(Appearance.animations(this))) { which ->
                Appearance.setAnimations(this, values[which])
                refresh()
            }
        }

        section("Developer tools")
        val dev = card()
        switchRow(dev, "appearance_remember_tab", R.drawable.ic_code, "Remember developer tools tab",
            "Reopen the Console / Source / Network tab you used last",
            { Appearance.rememberTab(this) }, { Appearance.setRememberTab(this, it) })
    }

    private fun buildGeneral() {
        section("Search")
        val search = card()
        valueRow(search, "general_engine", R.drawable.ic_search, "Search engine",
            "Used when you type something that isn't a web address", SearchEngines.label(this)) { showEngineDialog() }

        section("Startup")
        val startup = card()
        valueRow(startup, "general_startup", R.drawable.ic_history, "Startup",
            "What the browser opens when you start it",
            when (BrowserPrefs.startupMode(this)) {
                BrowserPrefs.STARTUP_HOME -> "Homepage"
                BrowserPrefs.STARTUP_BLANK -> "Blank page"
                else -> "Last page"
            }) {
            val modes = listOf(BrowserPrefs.STARTUP_RESTORE, BrowserPrefs.STARTUP_HOME, BrowserPrefs.STARTUP_BLANK)
            choiceDialog("Startup", arrayOf("Continue where you left off", "Open the homepage", "Blank page"),
                modes.indexOf(BrowserPrefs.startupMode(this))) { which ->
                BrowserPrefs.setStartupMode(this, modes[which])
                refresh()
            }
        }
        valueRow(startup, "general_homepage", R.drawable.ic_open, "Homepage",
            "Opened at launch when Startup is set to Homepage",
            BrowserPrefs.homepage(this).ifEmpty { "Not set" }) { showHomepageDialog() }
    }

    private fun buildBrowser() {
        section("Pages")
        val pages = card()
        switchRow(pages, "browser_js", R.drawable.ic_code, "JavaScript",
            "Turn off to load pages without scripts (the page reloads)",
            { BrowserPrefs.javaScriptEnabled(this) }, { BrowserPrefs.setJavaScriptEnabled(this, it) })
        switchRow(pages, "browser_3p_cookies", R.drawable.ic_cookie, "Third-party cookies",
            "Let embedded content from other sites set cookies",
            { BrowserPrefs.thirdPartyCookies(this) }, { BrowserPrefs.setThirdPartyCookies(this, it) })
        valueRow(pages, "browser_ua", R.drawable.ic_desktop, "User agent",
            "Replaces the mobile user agent (Desktop site still overrides it)",
            if (BrowserPrefs.customUserAgent(this).isEmpty()) "Default" else "Custom") { showUserAgentDialog() }

        section("Navigation")
        val nav = card()
        switchRow(nav, "browser_nav_confirm", R.drawable.ic_lock, "Navigation Confirmation",
            "Ask before leaving the current website",
            { BrowserPrefs.navigationConfirmation(this) }, { BrowserPrefs.setNavigationConfirmation(this, it) })
        hint("Changes apply to the open page when you return to the browser.")
    }

    private fun buildPrivacy() {
        section("Browsing data")
        val data = card()
        navRow(data, "privacy_library", R.drawable.ic_bookmark, "Bookmarks & history", "Search, edit and open them") {
            startActivity(Intent(this, LibraryActivity::class.java))
        }
        row(data, "privacy_clear_history", R.drawable.ic_history, "Clear browsing history",
            "Removes every visited page from the list").root.setOnClickListener {
            confirmDialog("Clear browsing history?", null, "Clear") {
                val n = BrowserLibrary.clearHistory(0)
                Toast.makeText(this, "Removed $n ${if (n == 1) "entry" else "entries"}", Toast.LENGTH_SHORT).show()
            }
        }
        row(data, "privacy_clear_cache", R.drawable.ic_sweep, "Clear cache and site storage",
            "Cached files, local storage and IndexedDB for every site").root.setOnClickListener {
            confirmDialog("Clear cache and site storage?", "Sites may sign you out or forget their saved settings.", "Clear") {
                WebView(this).also { it.clearCache(true); it.destroy() }
                WebStorage.getInstance().deleteAllData()
                Toast.makeText(this, "Cache and site storage cleared", Toast.LENGTH_SHORT).show()
            }
        }
        row(data, "privacy_clear_cookies", R.drawable.ic_cookie_off, "Clear all cookies",
            "Signs you out of every site").root.setOnClickListener {
            confirmDialog("Clear all cookies?", null, "Clear") {
                CookieTools.clearAll {
                    runOnUiThread { Toast.makeText(this, "Cookies cleared", Toast.LENGTH_SHORT).show() }
                }
            }
        }

        // One primary home per setting: these only link to it.
        section("Related settings")
        val related = card()
        navRow(related, null, R.drawable.ic_cookie, "Cookie management", "Keep cookies for chosen sites") {
            openCategory(SettingsCategory.COOKIES)
        }
        navRow(related, null, R.drawable.ic_lock, "Navigation protection", "Ask before leaving the current website") {
            openCategory(SettingsCategory.BROWSER, "browser_nav_confirm")
        }
    }

    private fun buildDevTools() {
        section("Logs")
        val card = card()
        fun dev(key: String) = { BrowserPrefs.devFlag(this, key) }
        fun setDev(key: String) = { on: Boolean -> BrowserPrefs.setDev(this, key, on.toString()) }
        switchRow(card, "dev_keep_console", R.drawable.ic_list, "Preserve console log",
            "Keep console messages when the page navigates or reloads", dev("keep_console"), setDev("keep_console"))
        switchRow(card, "dev_keep_network", R.drawable.ic_network, "Preserve network log",
            "Keep requests when the page navigates or reloads", dev("keep_network"), setDev("keep_network"))
        switchRow(card, "dev_timestamps", R.drawable.ic_timer, "Console timestamps",
            "Show the time each console message arrived", dev("timestamps"), setDev("timestamps"))
        hint("Also available as toggles inside the Console and Network tabs.")

        section("Console")
        val console = card()
        row(console, "dev_clear_commands", R.drawable.ic_sweep, "Clear command history",
            "Forget the JavaScript commands you ran in the console").root.setOnClickListener {
            BrowserPrefs.setDev(this, "cmd_history", "[]")
            Toast.makeText(this, "Command history cleared", Toast.LENGTH_SHORT).show()
        }

        section("Related settings")
        val related = card()
        navRow(related, null, R.drawable.ic_network, "Network capture", "Preferences for recording requests") {
            openCategory(SettingsCategory.CAPTURE)
        }
        navRow(related, null, R.drawable.ic_filter, "Request rules", "Block, mock or delay requests") {
            openCategory(SettingsCategory.RULES)
        }
    }

    private fun buildFloating() {
        section("Bubble")
        val card = card()
        switchRow(card, "float_main", R.drawable.ic_settings, "Floating bubble",
            "Record, reload and cookie buttons on top of the browser (this app only)",
            { BrowserPrefs.floatingControls(this) }, { BrowserPrefs.setFloatingControls(this, it) },
            // Asks for storage access first on Android 9 and older (to save into Downloads).
            beforeEnable = { proceed -> ensureStoragePermission(proceed) })
        switchRow(card, "float_haptics", R.drawable.ic_play, "Haptic feedback",
            "A small tap on Play, Pause, Resume and Delete cookies",
            { BrowserPrefs.floatingHaptics(this) }, { BrowserPrefs.setFloatingHaptics(this, it) })

        section("Buttons in the ring")
        val ring = card()
        val items = listOf(
            Triple(BrowserPrefs.ITEM_RELOAD, R.drawable.ic_refresh, "Hard reload"),
            Triple(BrowserPrefs.ITEM_HAR, R.drawable.ic_network, "Network capture settings"),
            Triple(BrowserPrefs.ITEM_COOKIES, R.drawable.ic_cookie, "Cookie settings"),
            Triple(BrowserPrefs.ITEM_DELETE, R.drawable.ic_cookie_off, "Delete cookies and site data")
        )
        for ((key, icon, title) in items) {
            switchRow(ring, "float_item_$key", icon, title, "",
                { key in BrowserPrefs.floatingItems(this) },
                { on ->
                    val set = BrowserPrefs.floatingItems(this).toMutableSet()
                    if (on) set += key else set -= key
                    BrowserPrefs.setFloatingItems(this, set)
                })
        }
        hint("Play, Resume, Save and Share are always available.")
    }

    private fun buildCapture() {
        section("Recording")
        val card = card()
        switchRow(card, "capture_site_only", R.drawable.ic_network, "This site only",
            "Record only the site you started on; other sites are ignored",
            { HarOptions.siteOnly }, { HarOptions.siteOnly = it })
        switchRow(card, "capture_warn", R.drawable.ic_pause, "Warn before the limit",
            "Toast and amber bubble at 80% of the request / size limit",
            { HarOptions.warnNearLimit }, { HarOptions.warnNearLimit = it })
        hint("Types, bodies and limits are in Menu → Network capture.")
    }

    private fun buildRules() {
        section("Network")
        val network = card()
        valueRow(network, "rules_delay", R.drawable.ic_refresh, "Network delay", "Wait before every request is sent",
            delayLabel(RequestRules.delayMs)) {
            val labels = RequestRules.DELAY_STEPS.map { delayLabel(it) }.toTypedArray()
            choiceDialog("Network delay", labels, RequestRules.DELAY_STEPS.indexOf(RequestRules.delayMs)) { which ->
                RequestRules.delayMs = RequestRules.DELAY_STEPS[which]
                refresh()
            }
        }

        section("Rules")
        val list = card()
        val rules = RequestRules.rules()
        if (rules.isEmpty()) {
            row(list, "rules_list", R.drawable.ic_filter, "No rules yet", "Add one to block or mock a request")
        }
        rules.forEachIndexed { index, rule ->
            valueRow(list, if (index == 0) "rules_list" else null,
                if (rule.action == RequestRules.Action.BLOCK) R.drawable.ic_close else R.drawable.ic_code,
                rule.pattern,
                if (rule.action == RequestRules.Action.BLOCK) "Block (403)" else "Mock ${rule.status} · ${rule.mime}",
                if (rule.enabled) "On" else "Off") { showRuleDialog(index) }
        }
        navRow(list, "rules_add", R.drawable.ic_add, "Add rule", "Block a URL or answer it with your own response") {
            showRuleDialog(null)
        }
        hint("A rule matches when its text appears in the request URL. Use * as a wildcard, e.g. *.png or api.example.com/v1/*")
    }

    private fun buildCookies() {
        section("This site")
        val card = card()
        val host = CookieTools.hostOf(pageUrl)
        if (host != null) {
            switchRow(card, "cookies_keep_site", R.drawable.ic_cookie, "Keep cookies for $host",
                "The delete-cookies button and \"Clear this site\" will skip it",
                { CookieTools.isWhitelisted(this, pageUrl) },
                { on -> CookieTools.setWhitelisted(this, host, on); refresh() })
        } else {
            row(card, "cookies_keep_site", R.drawable.ic_cookie, "Keep cookies for this site",
                "Open a website first, then come back here")
        }

        section("Whitelisted sites")
        val list = card()
        val kept = CookieTools.whitelist(this)
        if (kept.isEmpty()) {
            row(list, "cookies_whitelist", R.drawable.ic_cookie, "No sites kept", "Sites you keep cookies for appear here")
        }
        kept.forEachIndexed { i, site ->
            val r = row(list, if (i == 0) "cookies_whitelist" else null, R.drawable.ic_cookie, site, "Cookies are kept — tap to remove")
            setValue(r, "Remove")
            r.root.setOnClickListener { CookieTools.setWhitelisted(this, site, false); refresh() }
        }
        hint("\"Clear all cookies\" in the cookie sheet still removes everything. Import / export JSON is in the cookie sheet (Menu → Cookies).")
    }

    // --- Dialogs -------------------------------------------------------------------------------

    private fun choiceDialog(title: String, labels: Array<String>, checked: Int, onPick: (Int) -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels, checked.coerceAtLeast(0)) { d, which -> d.dismiss(); onPick(which) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDialog(title: String, message: String?, positive: String, onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .apply { if (message != null) setMessage(message) }
            .setPositiveButton(positive) { _, _ -> onConfirm() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun textField(hintText: String, value: String, multiline: Boolean = false, number: Boolean = false): TextInputLayout {
        val layout = TextInputLayout(this).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = hintText
        }
        val edit = TextInputEditText(layout.context).apply {
            setText(value)
            inputType = when {
                multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                number -> InputType.TYPE_CLASS_NUMBER
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
            if (multiline) { minLines = 3; maxLines = 8 }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) { layout.error = null }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        layout.addView(edit)
        return layout
    }

    private fun TextInputLayout.text() = editText?.text?.toString().orEmpty()

    /** Single-field editor. [validate] returns an error message or null; Save stays open until it passes. */
    private fun inputDialog(
        title: String, message: String?, field: TextInputLayout,
        neutral: String? = null, onNeutral: () -> Unit = {},
        validate: (String) -> String? = { null }, onSave: (String) -> Unit
    ) {
        val frame = FrameLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0) }
        frame.addView(field, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(frame)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
        if (message != null) builder.setMessage(message)
        if (neutral != null) builder.setNeutralButton(neutral) { _, _ -> onNeutral() }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val error = validate(field.text())
                if (error != null) { field.error = error; return@setOnClickListener }
                onSave(field.text())
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showEngineDialog() {
        val engines = SearchEngines.BUILT_IN
        val labels = (engines.map { it.name } + "Custom…").toTypedArray()
        val selected = SearchEngines.current(this)
        val checked = if (selected.id == SearchEngines.CUSTOM_ID) engines.size
        else engines.indexOfFirst { it.id == selected.id }
        choiceDialog("Search engine", labels, checked) { which ->
            if (which < engines.size) {
                SearchEngines.select(this, engines[which].id)
                refresh()
            } else {
                showCustomEngineDialog()
            }
        }
    }

    private fun showCustomEngineDialog() {
        inputDialog("Custom search engine", "Use %s where the search words go.",
            textField("https://example.com/search?q=%s", SearchEngines.customTemplate(this)),
            validate = { if (SearchEngines.isValidTemplate(it.trim())) null else "Must start with https:// and contain %s" }
        ) { SearchEngines.setCustom(this, it.trim()); refresh() }
    }

    private fun showHomepageDialog() {
        inputDialog("Homepage", null, textField("https://example.com", BrowserPrefs.homepage(this)),
            neutral = "Clear", onNeutral = { BrowserPrefs.setHomepage(this, ""); refresh() },
            validate = { if (it.any(Char::isWhitespace)) "A web address can't contain spaces" else null }
        ) { BrowserPrefs.setHomepage(this, it); refresh() }
    }

    private fun showUserAgentDialog() {
        inputDialog("User agent", "Leave empty to use the default.",
            textField("Mozilla/5.0 (…)", BrowserPrefs.customUserAgent(this), multiline = true),
            neutral = "Use default", onNeutral = { BrowserPrefs.setCustomUserAgent(this, ""); refresh() }
        ) { BrowserPrefs.setCustomUserAgent(this, it.replace("\n", " ")); refresh() }
    }

    private fun delayLabel(ms: Int) = when {
        ms <= 0 -> "Off"
        ms < 1000 -> "$ms ms"
        else -> "${ms / 1000} s"
    }

    private fun showRuleDialog(index: Int?) {
        val all = RequestRules.rules().toMutableList()
        val existing = index?.let { all.getOrNull(it) }

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        fun label(text: String) = (layoutInflater.inflate(R.layout.item_section_label, form, false) as TextView).apply {
            this.text = text
            (layoutParams as? ViewGroup.MarginLayoutParams)?.apply { marginStart = 0; topMargin = dp(16); bottomMargin = dp(4) }
        }
        fun add(parent: ViewGroup, v: View, top: Int = 8) = parent.addView(v, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) })

        form.addView(label("Match"))
        val pattern = textField("URL contains (use * as wildcard)", existing?.pattern.orEmpty())
        add(form, pattern, 0)

        form.addView(label("Action"))
        val group = MaterialButtonToggleGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
        fun toggle(text: String) = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            id = View.generateViewId()
        }
        val block = toggle("Block")
        val mock = toggle("Mock response")
        group.addView(block)
        group.addView(mock)
        add(form, group, 0)

        val mockBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mockBox.addView(label("Mock response"))
        val status = textField("Status code (200–599)", (existing?.status ?: 200).toString(), number = true)
        val mime = textField("Content type", existing?.mime ?: "application/json")
        val body = textField("Response body", existing?.body.orEmpty(), multiline = true)
        add(mockBox, status, 0)
        add(mockBox, mime)
        add(mockBox, body)
        form.addView(mockBox)

        val enabled = MaterialSwitch(this).apply {
            text = "Enabled"
            isChecked = existing?.enabled ?: true
        }
        add(form, enabled, 16)

        fun syncMode() { mockBox.visibility = if (group.checkedButtonId == mock.id) View.VISIBLE else View.GONE }
        group.addOnButtonCheckedListener { _, _, _ -> syncMode() }
        group.check(if (existing?.action == RequestRules.Action.MOCK) mock.id else block.id)
        syncMode()

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "Add rule" else "Edit rule")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
        if (index != null) {
            builder.setNeutralButton("Delete") { _, _ ->
                confirmDialog("Delete this rule?", existing?.pattern, "Delete") {
                    all.removeAt(index)
                    RequestRules.save(all)
                    refresh()
                }
            }
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = pattern.text().trim()
                if (text.isEmpty()) { pattern.error = "Enter part of a URL"; return@setOnClickListener }
                val isMock = group.checkedButtonId == mock.id
                val code = status.text().toIntOrNull()
                if (isMock && (code == null || code !in 200..599)) {
                    status.error = "Enter a status between 200 and 599"
                    return@setOnClickListener
                }
                val rule = RequestRules.Rule(
                    pattern = text,
                    action = if (isMock) RequestRules.Action.MOCK else RequestRules.Action.BLOCK,
                    status = code?.coerceIn(200, 599) ?: 200,
                    mime = mime.text().trim().ifEmpty { "application/json" },
                    body = body.text(),
                    enabled = enabled.isChecked
                )
                if (index == null) all += rule else all[index] = rule
                RequestRules.save(all)
                dialog.dismiss()
                refresh()
            }
        }
        dialog.show()
    }

    // --- Rows and layout helpers ---------------------------------------------------------------

    private fun ensureStoragePermission(then: () -> Unit) {
        val needed = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (!needed) return then()
        afterPermission = then
        requestStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    private fun section(text: String) {
        val label = layoutInflater.inflate(R.layout.item_section_label, content, false) as TextView
        label.text = text
        ViewCompat.setAccessibilityHeading(label, true)
        content.addView(label)
    }

    private fun hint(text: String) {
        content.addView(TextView(this, null, 0, R.style.SheetHint).apply {
            this.text = text
            setPadding(dp(6), dp(8), dp(6), 0) // style margins don't apply to code-built views
        })
    }

    private fun card(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            clipToOutline = true
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = ContextCompat.getDrawable(this@SettingsActivity, R.drawable.divider_inset)
        }
        content.addView(card, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return card
    }

    private fun row(parent: ViewGroup, id: String?, @DrawableRes icon: Int, title: String, subtitle: String): ItemOptionRowBinding {
        val r = ItemOptionRowBinding.inflate(layoutInflater, parent, false)
        r.optIcon.setImageResource(icon)
        r.optTitle.text = title
        if (subtitle.isEmpty()) r.optSubtitle.visibility = View.GONE else r.optSubtitle.text = subtitle
        if (id != null) rows[id] = r.root
        parent.addView(r.root)
        return r
    }

    private fun setValue(r: ItemOptionRowBinding, value: String) {
        r.optValue.visibility = View.VISIBLE
        r.optValue.text = value
        r.optValue.maxLines = 1
        r.optValue.ellipsize = TextUtils.TruncateAt.END
        r.optValue.maxWidth = dp(VALUE_MAX_WIDTH_DP) // a long homepage must not squeeze the title
    }

    private fun navRow(parent: ViewGroup, id: String?, @DrawableRes icon: Int, title: String, subtitle: String, onClick: () -> Unit): ItemOptionRowBinding {
        val r = row(parent, id, icon, title, subtitle)
        r.optChevron.visibility = View.VISIBLE
        r.root.setOnClickListener { onClick() }
        return r
    }

    private fun valueRow(parent: ViewGroup, id: String?, @DrawableRes icon: Int, title: String, subtitle: String, value: String, onClick: () -> Unit): ItemOptionRowBinding {
        val r = navRow(parent, id, icon, title, subtitle, onClick)
        setValue(r, value)
        return r
    }

    /**
     * The whole row toggles; the switch itself isn't clickable, so a tap can't fire twice.
     * [beforeEnable] lets a setting ask for something (a permission) before turning on.
     */
    private fun switchRow(
        parent: ViewGroup, id: String?, @DrawableRes icon: Int, title: String, subtitle: String,
        get: () -> Boolean, set: (Boolean) -> Unit,
        beforeEnable: ((proceed: () -> Unit) -> Unit)? = null
    ): ItemOptionRowBinding {
        val r = row(parent, id, icon, title, subtitle)
        r.optSwitch.visibility = View.VISIBLE
        r.optSwitch.isChecked = get()
        fun apply(on: Boolean) { r.optSwitch.isChecked = on; set(on) }
        r.root.setOnClickListener {
            val on = !r.optSwitch.isChecked
            if (on && beforeEnable != null) beforeEnable { apply(true) } else apply(on)
        }
        ViewCompat.setAccessibilityDelegate(r.root, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.Switch"
                info.isCheckable = true
                info.isChecked = r.optSwitch.isChecked
            }
        })
        return r
    }
}
