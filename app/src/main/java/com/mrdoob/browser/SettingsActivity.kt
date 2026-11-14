package com.mrdoob.browser

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
    }

    private lateinit var content: LinearLayout
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
        buildFloating()
        buildCapture()
        buildRules()
        buildCookies()
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
    }

    private fun hint(text: String) {
        content.addView(TextView(this).apply {
            this.text = text
            textSize = 12.5f
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.nx_on_surface_variant))
            setPadding(dp(6), dp(8), dp(6), 0)
        })
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
        return card
    }

    private fun row(parent: ViewGroup, @DrawableRes icon: Int, title: String, subtitle: String): ItemOptionRowBinding {
        val r = ItemOptionRowBinding.inflate(layoutInflater, parent, false)
        r.optIcon.setImageResource(icon)
        r.optTitle.text = title
        if (subtitle.isEmpty()) r.optSubtitle.visibility = View.GONE else r.optSubtitle.text = subtitle
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
