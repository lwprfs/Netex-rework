package com.mrdoob.browser

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.EditText
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.mrdoob.browser.databinding.ItemOptionRowBinding

/**
 * Full-screen settings: the floating bubble, capture extras, request rules (block / mock /
 * delay) and the per-site cookie whitelist. Everything is saved immediately; the browser picks
 * the changes up when this screen closes.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"
        private const val KIND_LABEL = 0
        private const val KIND_CARD = 1
        private const val KIND_HINT = 2
    }

    private lateinit var content: LinearLayout
    private lateinit var searchField: EditText
    private var query = ""

    // Everything drawn by build(), in order, so the search box can hide what doesn't match.
    private class Entry(val view: View, val kind: Int)
    private val entries = ArrayList<Entry>()
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
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Settings can be restored on their own after the process died.
        HarOptions.init(applicationContext)
        RequestRules.init(applicationContext)
        pageUrl = intent.getStringExtra(EXTRA_URL)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = MaterialToolbar(this).apply {
            title = "Settings"
            setNavigationIcon(R.drawable.ic_back)
            setNavigationContentDescription("Back")
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        searchField = EditText(this).apply {
            hint = "Search settings"
            isSingleLine = true
            setBackgroundResource(R.drawable.bg_card)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawablePadding = dp(12)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString().orEmpty().trim().lowercase()
                    applyFilter()
                }
            })
        }
        root.addView(searchField, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(16), dp(4), dp(16), dp(4)) })

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, dp(4), pad, dp(24))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        build()
    }

    /** (Re)draws every section; called again after a list changes. */
    private fun build() {
        content.removeAllViews()
        entries.clear()
        buildGeneral()
        buildBrowser()
        buildPrivacy()
        buildDevTools()
        buildFloating()
        buildCapture()
        buildRules()
        buildCookies()
        applyFilter()
    }

    /** Settings search: hides rows that don't match, and any card / heading left empty. */
    private fun applyFilter() {
        var labelView: View? = null
        var labelAny = false
        var lastCardVisible = true
        fun flush() { labelView?.visibility = if (query.isEmpty() || labelAny) View.VISIBLE else View.GONE }
        for (e in entries) {
            when (e.kind) {
                KIND_LABEL -> { flush(); labelView = e.view; labelAny = false }
                KIND_CARD -> {
                    val card = e.view as ViewGroup
                    var any = false
                    for (i in 0 until card.childCount) {
                        val child = card.getChildAt(i)
                        val match = query.isEmpty() || (child.tag as? String)?.contains(query) != false
                        child.visibility = if (match) View.VISIBLE else View.GONE
                        any = any || match
                    }
                    card.visibility = if (any) View.VISIBLE else View.GONE
                    lastCardVisible = any
                    labelAny = labelAny || any
                }
                KIND_HINT -> e.view.visibility = if (query.isEmpty()) View.VISIBLE else View.GONE
            }
        }
        flush()
    }

    // --- General -----------------------------------------------------------------------------

    private fun buildGeneral() {
        section("GENERAL")
        val card = newCard()

        val engine = row(card, R.drawable.ic_search, "Search engine", "Used when you type something that isn't a web address")
        engine.optValue.visibility = View.VISIBLE
        engine.optValue.text = SearchEngines.label(this)
        engine.root.setOnClickListener { showEngineDialog() }

        val home = row(card, R.drawable.ic_open, "Homepage", "Opened at launch when Startup is set to Homepage")
        home.optValue.visibility = View.VISIBLE
        home.optValue.text = BrowserPrefs.homepage(this).ifEmpty { "Not set" }
        home.root.setOnClickListener { showHomepageDialog() }

        val startup = row(card, R.drawable.ic_history, "Startup", "What the browser opens when you start it")
        startup.optValue.visibility = View.VISIBLE
        startup.optValue.text = when (BrowserPrefs.startupMode(this)) {
            BrowserPrefs.STARTUP_HOME -> "Homepage"
            BrowserPrefs.STARTUP_BLANK -> "Blank page"
            else -> "Last page"
        }
        startup.root.setOnClickListener {
            val modes = listOf(BrowserPrefs.STARTUP_RESTORE, BrowserPrefs.STARTUP_HOME, BrowserPrefs.STARTUP_BLANK)
            val labels = arrayOf("Continue where you left off", "Open the homepage", "Blank page")
            MaterialAlertDialogBuilder(this)
                .setTitle("Startup")
                .setSingleChoiceItems(labels, modes.indexOf(BrowserPrefs.startupMode(this)).coerceAtLeast(0)) { d, which ->
                    BrowserPrefs.setStartupMode(this, modes[which])
                    d.dismiss()
                    build()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun showEngineDialog() {
        val engines = SearchEngines.BUILT_IN
        val labels = (engines.map { it.name } + "Custom…").toTypedArray()
        val selected = SearchEngines.current(this)
        val checked = if (selected.id == SearchEngines.CUSTOM_ID) engines.size
        else engines.indexOfFirst { it.id == selected.id }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("Search engine")
            .setSingleChoiceItems(labels, checked) { d, which ->
                d.dismiss()
                if (which < engines.size) {
                    SearchEngines.select(this, engines[which].id)
                    build()
                } else {
                    showCustomEngineDialog()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCustomEngineDialog() {
        val input = EditText(this).apply {
            hint = "https://example.com/search?q=%s"
            setText(SearchEngines.customTemplate(this@SettingsActivity))
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Custom search engine")
            .setMessage("Use %s where the search words go.")
            .setView(input)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val t = input.text.toString().trim()
                if (!SearchEngines.isValidTemplate(t)) {
                    input.error = "Must start with https:// and contain %s"
                    return@setOnClickListener
                }
                SearchEngines.setCustom(this, t)
                dialog.dismiss()
                build()
            }
        }
        dialog.show()
    }

    private fun showHomepageDialog() {
        val input = EditText(this).apply {
            hint = "https://example.com"
            setText(BrowserPrefs.homepage(this@SettingsActivity))
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Homepage")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                BrowserPrefs.setHomepage(this, input.text.toString())
                build()
            }
            .setNeutralButton("Clear") { _, _ ->
                BrowserPrefs.setHomepage(this, "")
                build()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Browser ---------------------------------------------------------------------------------

    private fun buildBrowser() {
        section("BROWSER")
        val card = newCard()
        switchRow(card, R.drawable.ic_code, "JavaScript", "Turn off to load pages without scripts (the page reloads)",
            { BrowserPrefs.javaScriptEnabled(this) }, { BrowserPrefs.setJavaScriptEnabled(this, it) })
        switchRow(card, R.drawable.ic_cookie, "Third-party cookies", "Let embedded content from other sites set cookies",
            { BrowserPrefs.thirdPartyCookies(this) }, { BrowserPrefs.setThirdPartyCookies(this, it) })

        val ua = row(card, R.drawable.ic_desktop, "User agent", "Replaces the mobile user agent (Desktop site still overrides it)")
        ua.optValue.visibility = View.VISIBLE
        ua.optValue.text = if (BrowserPrefs.customUserAgent(this).isEmpty()) "Default" else "Custom"
        ua.root.setOnClickListener {
            val input = EditText(this).apply {
                hint = "Mozilla/5.0 (…)"
                setText(BrowserPrefs.customUserAgent(this@SettingsActivity))
                minLines = 3
                maxLines = 6
                textSize = 13f
                setPadding(dp(24), dp(16), dp(24), dp(8))
            }
            MaterialAlertDialogBuilder(this)
                .setTitle("User agent")
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    BrowserPrefs.setCustomUserAgent(this, input.text.toString().replace("\n", " "))
                    build()
                }
                .setNeutralButton("Use default") { _, _ ->
                    BrowserPrefs.setCustomUserAgent(this, "")
                    build()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        hint("Changes apply to the open page when you return to the browser.")
    }

    // --- Developer tools -----------------------------------------------------------------------

    private fun buildDevTools() {
        section("DEVELOPER TOOLS")
        val card = newCard()
        fun dev(key: String) = { BrowserPrefs.devFlag(this, key) }
        fun setDev(key: String) = { on: Boolean -> BrowserPrefs.setDev(this, key, on.toString()) }
        switchRow(card, R.drawable.ic_list, "Preserve console log", "Keep console messages when the page navigates or reloads",
            dev("keep_console"), setDev("keep_console"))
        switchRow(card, R.drawable.ic_network, "Preserve network log", "Keep requests when the page navigates or reloads",
            dev("keep_network"), setDev("keep_network"))
        switchRow(card, R.drawable.ic_timer, "Console timestamps", "Show the time each console message arrived",
            dev("timestamps"), setDev("timestamps"))
        val clear = row(card, R.drawable.ic_sweep, "Clear command history", "Forget the JavaScript commands you ran in the console")
        clear.root.setOnClickListener {
            BrowserPrefs.setDev(this, "cmd_history", "[]")
            Toast.makeText(this, "Command history cleared", Toast.LENGTH_SHORT).show()
        }
        hint("Also available as toggles inside the Console and Network tabs.")
    }

    // --- Privacy & data ------------------------------------------------------------------------

    private fun buildPrivacy() {
        section("PRIVACY & DATA")
        val card = newCard()

        val library = row(card, R.drawable.ic_bookmark, "Bookmarks & history", "Search, edit and open them")
        library.optChevron.visibility = View.VISIBLE
        library.root.setOnClickListener { startActivity(android.content.Intent(this, LibraryActivity::class.java)) }

        val history = row(card, R.drawable.ic_history, "Clear browsing history", "Removes every visited page from the list")
        history.root.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Clear browsing history?")
                .setPositiveButton("Clear") { _, _ ->
                    val n = BrowserLibrary.clearHistory(0)
                    Toast.makeText(this, "Removed $n ${if (n == 1) "entry" else "entries"}", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        val cache = row(card, R.drawable.ic_sweep, "Clear cache and site storage", "Cached files, local storage and IndexedDB for every site")
        cache.root.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Clear cache and site storage?")
                .setMessage("Sites may sign you out or forget their saved settings.")
                .setPositiveButton("Clear") { _, _ ->
                    WebView(this).also { it.clearCache(true); it.destroy() }
                    WebStorage.getInstance().deleteAllData()
                    Toast.makeText(this, "Cache and site storage cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        val cookies = row(card, R.drawable.ic_cookie_off, "Clear all cookies", "Signs you out of every site")
        cookies.root.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Clear all cookies?")
                .setPositiveButton("Clear") { _, _ ->
                    CookieTools.clearAll {
                        runOnUiThread { Toast.makeText(this, "Cookies cleared", Toast.LENGTH_SHORT).show() }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // --- Floating bubble -------------------------------------------------------------------

    private fun buildFloating() {
        section("FLOATING BUBBLE")
        val card = newCard()

        val main = row(card, R.drawable.ic_settings, "Floating bubble",
            "Record, reload and cookie buttons on top of the browser (this app only)")
        main.optSwitch.visibility = View.VISIBLE
        main.optSwitch.isChecked = BrowserPrefs.floatingControls(this)
        main.root.setOnClickListener {
            val on = !main.optSwitch.isChecked
            if (on) {
                // Asks for storage access first on Android 9 and older (to save into Downloads).
                ensureStoragePermission {
                    BrowserPrefs.setFloatingControls(this, true)
                    main.optSwitch.isChecked = true
                }
            } else {
                BrowserPrefs.setFloatingControls(this, false)
                main.optSwitch.isChecked = false
            }
        }

        switchRow(card, R.drawable.ic_play, "Haptic feedback", "A small tap on Play, Pause, Resume and Delete cookies",
            { BrowserPrefs.floatingHaptics(this) }, { BrowserPrefs.setFloatingHaptics(this, it) })

        hint("Buttons in the ring (Play, Resume, Save and Share are always available)")
        val ring = newCard()
        val items = listOf(
            Triple(BrowserPrefs.ITEM_RELOAD, R.drawable.ic_refresh, "Hard reload"),
            Triple(BrowserPrefs.ITEM_HAR, R.drawable.ic_network, "Network capture settings"),
            Triple(BrowserPrefs.ITEM_COOKIES, R.drawable.ic_cookie, "Cookie settings"),
            Triple(BrowserPrefs.ITEM_DELETE, R.drawable.ic_cookie_off, "Delete cookies and site data")
        )
        for ((key, icon, title) in items) {
            switchRow(ring, icon, title, "",
                { key in BrowserPrefs.floatingItems(this) },
                { on ->
                    val set = BrowserPrefs.floatingItems(this).toMutableSet()
                    if (on) set += key else set -= key
                    BrowserPrefs.setFloatingItems(this, set)
                })
        }
    }

    // --- Network capture -------------------------------------------------------------------

    private fun buildCapture() {
        section("NETWORK CAPTURE")
        val card = newCard()
        switchRow(card, R.drawable.ic_network, "This site only",
            "Record only the site you started on; other sites are ignored",
            { HarOptions.siteOnly }, { HarOptions.siteOnly = it })
        switchRow(card, R.drawable.ic_pause, "Warn before the limit",
            "Toast and amber bubble at 80% of the request / size limit",
            { HarOptions.warnNearLimit }, { HarOptions.warnNearLimit = it })
        hint("Types, bodies and limits are in Menu → Network capture.")
    }

    // --- Request rules -----------------------------------------------------------------------

    private fun buildRules() {
        section("REQUEST RULES")
        val card = newCard()

        val delay = row(card, R.drawable.ic_refresh, "Network delay", "Wait before every request is sent")
        delay.optValue.visibility = View.VISIBLE
        delay.optValue.text = delayLabel(RequestRules.delayMs)
        delay.root.setOnClickListener {
            val labels = RequestRules.DELAY_STEPS.map { delayLabel(it) }.toTypedArray()
            val checked = RequestRules.DELAY_STEPS.indexOf(RequestRules.delayMs).coerceAtLeast(0)
            MaterialAlertDialogBuilder(this)
                .setTitle("Network delay")
                .setSingleChoiceItems(labels, checked) { d, which ->
                    RequestRules.delayMs = RequestRules.DELAY_STEPS[which]
                    d.dismiss()
                    build()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        val rules = RequestRules.rules()
        rules.forEachIndexed { index, rule ->
            val r = row(
                card,
                if (rule.action == RequestRules.Action.BLOCK) R.drawable.ic_close else R.drawable.ic_code,
                rule.pattern,
                if (rule.action == RequestRules.Action.BLOCK) "Block (403)"
                else "Mock ${rule.status} · ${rule.mime}"
            )
            r.optValue.visibility = View.VISIBLE
            r.optValue.text = if (rule.enabled) "On" else "Off"
            r.root.setOnClickListener { showRuleDialog(index) }
        }

        val add = row(card, R.drawable.ic_add, "Add rule", "Block a URL or answer it with your own response")
        add.optChevron.visibility = View.VISIBLE
        add.root.setOnClickListener { showRuleDialog(null) }
        hint("A rule matches when its text appears in the request URL. Use * as a wildcard, e.g. *.png or api.example.com/v1/*")
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
            val pad = dp(24)
            setPadding(pad, dp(8), pad, 0)
        }
        fun field(hintText: String, value: String, multiline: Boolean = false, number: Boolean = false): TextInputEditText {
            val layout = TextInputLayout(this).apply {
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                hint = hintText
            }
            val edit = TextInputEditText(layout.context).apply {
                setText(value)
                if (multiline) {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    minLines = 3
                    maxLines = 8
                } else if (number) {
                    inputType = InputType.TYPE_CLASS_NUMBER
                } else {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                }
            }
            layout.addView(edit)
            form.addView(layout, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) })
            return edit
        }

        val pattern = field("URL contains (use * as wildcard)", existing?.pattern.orEmpty())
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val block = RadioButton(this).apply { text = "Block"; id = View.generateViewId() }
        val mock = RadioButton(this).apply { text = "Mock response"; id = View.generateViewId() }
        group.addView(block)
        group.addView(mock)
        form.addView(group, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        val mockBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        form.addView(mockBox)
        // Mock fields live in their own box so they can be hidden for Block rules.
        fun mockField(hintText: String, value: String, multiline: Boolean = false, number: Boolean = false): TextInputEditText {
            val before = form.childCount
            val e = field(hintText, value, multiline, number)
            val v = form.getChildAt(before) // the TextInputLayout just added
            form.removeView(v)
            mockBox.addView(v)
            return e
        }
        val status = mockField("Status code", (existing?.status ?: 200).toString(), number = true)
        val mime = mockField("Content type", existing?.mime ?: "application/json")
        val body = mockField("Response body", existing?.body.orEmpty(), multiline = true)

        val enabled = CheckBox(this).apply {
            text = "Enabled"
            isChecked = existing?.enabled ?: true
        }
        form.addView(enabled)

        fun syncMode() { mockBox.visibility = if (mock.isChecked) View.VISIBLE else View.GONE }
        group.setOnCheckedChangeListener { _, _ -> syncMode() }
        if (existing?.action == RequestRules.Action.MOCK) mock.isChecked = true else block.isChecked = true
        syncMode()

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "Add rule" else "Edit rule")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
        if (index != null) {
            builder.setNeutralButton("Delete") { _, _ ->
                all.removeAt(index)
                RequestRules.save(all)
                build()
            }
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = pattern.text.toString().trim()
                if (text.isEmpty()) {
                    pattern.error = "Enter part of a URL"
                    return@setOnClickListener
                }
                val rule = RequestRules.Rule(
                    pattern = text,
                    action = if (mock.isChecked) RequestRules.Action.MOCK else RequestRules.Action.BLOCK,
                    status = status.text.toString().toIntOrNull()?.coerceIn(200, 599) ?: 200,
                    mime = mime.text.toString().trim().ifEmpty { "application/json" },
                    body = body.text.toString(),
                    enabled = enabled.isChecked
                )
                if (index == null) all += rule else all[index] = rule
                RequestRules.save(all)
                dialog.dismiss()
                build()
            }
        }
        dialog.show()
    }

    // --- Cookies -------------------------------------------------------------------------------

    private fun buildCookies() {
        section("COOKIES")
        val card = newCard()
        val host = CookieTools.hostOf(pageUrl)
        if (host != null) {
            switchRow(card, R.drawable.ic_cookie, "Keep cookies for $host",
                "The delete-cookies button and \"Clear this site\" will skip it",
                { CookieTools.isWhitelisted(this, pageUrl) },
                { on ->
                    CookieTools.setWhitelisted(this, host, on)
                    build()
                })
        } else {
            row(card, R.drawable.ic_cookie, "Keep cookies for this site", "Open a website first, then come back here")
        }

        val kept = CookieTools.whitelist(this)
        if (kept.isNotEmpty()) {
            hint("Whitelisted sites — tap to remove")
            val list = newCard()
            for (site in kept) {
                val r = row(list, R.drawable.ic_cookie, site, "Cookies are kept")
                r.optValue.visibility = View.VISIBLE
                r.optValue.text = "Remove"
                r.root.setOnClickListener {
                    CookieTools.setWhitelisted(this, site, false)
                    build()
                }
            }
        }
        hint("\"Clear all cookies\" in the cookie sheet still removes everything. Import / export JSON is in the cookie sheet (Menu → Cookies).")
    }

    // --- helpers -------------------------------------------------------------------------------

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
        content.addView(label)
        entries += Entry(label, KIND_LABEL)
    }

    private fun hint(text: String) {
        val v = TextView(this).apply {
            this.text = text
            textSize = 12.5f
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.nx_on_surface_variant))
            setPadding(dp(6), dp(8), dp(6), 0)
        }
        content.addView(v)
        entries += Entry(v, KIND_HINT)
    }

    private fun newCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            clipToOutline = true
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = ContextCompat.getDrawable(this@SettingsActivity, R.drawable.divider_inset)
        }
        content.addView(card, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        entries += Entry(card, KIND_CARD)
        return card
    }

    private fun row(parent: ViewGroup, @DrawableRes icon: Int, title: String, subtitle: String): ItemOptionRowBinding {
        val r = ItemOptionRowBinding.inflate(layoutInflater, parent, false)
        r.optIcon.setImageResource(icon)
        r.optTitle.text = title
        if (subtitle.isEmpty()) r.optSubtitle.visibility = View.GONE else r.optSubtitle.text = subtitle
        r.root.tag = "$title $subtitle".lowercase() // what the settings search looks at
        parent.addView(r.root)
        return r
    }

    private fun switchRow(
        parent: ViewGroup, @DrawableRes icon: Int, title: String, subtitle: String,
        get: () -> Boolean, set: (Boolean) -> Unit
    ): ItemOptionRowBinding {
        val r = row(parent, icon, title, subtitle)
        r.optSwitch.visibility = View.VISIBLE
        r.optSwitch.isChecked = get()
        r.root.setOnClickListener {
            val on = !r.optSwitch.isChecked
            r.optSwitch.isChecked = on
            set(on)
        }
        return r
    }
}
