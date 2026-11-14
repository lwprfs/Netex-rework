package com.mrdoob.browser

import android.content.res.ColorStateList
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.mrdoob.browser.HarRecorder.State
import com.mrdoob.browser.databinding.ItemOptionLimitBinding
import com.mrdoob.browser.databinding.ItemOptionRowBinding
import com.mrdoob.browser.databinding.ItemOptionTextBinding
import com.mrdoob.browser.databinding.SheetHarBinding
import kotlinx.coroutines.launch

/**
 * Network capture sheet.
 *  - Session: live setup summary, presets, session limits.
 *  - Detail:  request types, bodies + manual MB limits, headers & cookies, privacy, filters.
 *  - Export:  share / save / file manager / clear.
 */
class HarSheet(
    private val activity: AppCompatActivity,
    private val onPickLocation: (String) -> Unit
) {

    private lateinit var b: SheetHarBinding
    private lateinit var dialog: BottomSheetDialog
    private lateinit var summaryView: TextView

    /** Re-read every stored option into its control (after a preset or reset). */
    private val refreshers = ArrayList<() -> Unit>()
    private val exportRows = ArrayList<View>()
    private var clearRow: View? = null

    private val inflater get() = activity.layoutInflater

    fun show() {
        dialog = BottomSheetDialog(activity)
        b = SheetHarBinding.inflate(inflater)
        dialog.setContentView(b.root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        b.root.minimumHeight = (activity.resources.displayMetrics.heightPixels * 0.82f).toInt()

        buildSessionPane()
        buildDetailPane()
        buildExportPane()
        wireControls()

        val job = activity.lifecycleScope.launch { HarRecorder.status.collect { render(it) } }
        dialog.setOnDismissListener { job.cancel() }
        dialog.show()
    }

    // --- controls -----------------------------------------------------------------------

    private fun wireControls() {
        b.harStart.setOnClickListener { HarRecorder.start() }
        b.harPause.setOnClickListener { HarRecorder.togglePause() }
        b.harStop.setOnClickListener { HarRecorder.stop() }

        b.harTabs.check(R.id.harTabSession)
        b.harTabs.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            b.harPaneSession.visibility = if (id == R.id.harTabSession) View.VISIBLE else View.GONE
            b.harPaneDetail.visibility = if (id == R.id.harTabDetail) View.VISIBLE else View.GONE
            b.harPaneExport.visibility = if (id == R.id.harTabExport) View.VISIBLE else View.GONE
        }
    }

    private fun onChanged() {
        if (::summaryView.isInitialized) summaryView.text = HarOptions.summary()
    }

    private fun refreshAll() {
        for (r in refreshers) r()
        onChanged()
    }

    // --- Session pane -------------------------------------------------------------------

    private fun buildSessionPane() {
        val pane = b.harPaneSession

        section(pane, "Current setup")
        val card = newCard(pane)
        summaryView = TextView(activity).apply {
            setPadding(activity.dp(18), activity.dp(16), activity.dp(18), activity.dp(16))
            textSize = 13.5f
            setLineSpacing(0f, 1.2f)
            setTextColor(ContextCompat.getColor(activity, R.color.nx_on_surface))
            text = HarOptions.summary()
        }
        card.addView(summaryView)

        section(pane, "Presets")
        val presets = ChipGroup(activity).apply {
            chipSpacingHorizontal = activity.dp(8)
            chipSpacingVertical = activity.dp(4)
        }
        val presetList = listOf(
            "Lite" to HarOptions.Preset.LITE,
            "Balanced" to HarOptions.Preset.BALANCED,
            "Full" to HarOptions.Preset.FULL
        )
        for ((label, preset) in presetList) {
            val chip = inflater.inflate(R.layout.item_chip_assist, presets, false) as Chip
            chip.text = label
            chip.setOnClickListener {
                HarOptions.applyPreset(preset)
                refreshAll()
                Toast.makeText(activity, "$label preset applied", Toast.LENGTH_SHORT).show()
            }
            presets.addView(chip)
        }
        pane.addView(presets)
        hint(pane, "Lite: API calls and pages only, small limits. Balanced: every type with sensible limits. Full: everything, large limits.")

        section(pane, "Session limits")
        val limits = newCard(pane)
        limitRow(
            limits, R.drawable.ic_list, "Max requests", "Oldest requests are dropped beyond this",
            hasSwitch = false, getOn = { true }, setOn = {},
            getValue = { HarOptions.maxEntries.toFloat() }, setValue = { HarOptions.maxEntries = it.toInt() },
            unit = "requests", min = HarOptions.MIN_ENTRIES.toFloat(), max = HarOptions.MAX_ENTRIES.toFloat(),
            integer = true
        )
        limitRow(
            limits, R.drawable.ic_storage, "Max stored size", "Total of all captured bodies",
            hasSwitch = false, getOn = { true }, setOn = {},
            getValue = { HarOptions.maxTotalMb }, setValue = { HarOptions.maxTotalMb = it },
            unit = "MB", min = HarOptions.MIN_TOTAL_MB, max = HarOptions.MAX_TOTAL_MB, integer = false
        )
        switchRow(
            limits, R.drawable.ic_pause, "Pause when full", "Stop recording instead of dropping the oldest",
            { HarOptions.stopWhenFull }, { HarOptions.stopWhenFull = it }
        )
        hint(pane, "Bigger limits use more phone memory while recording.")
    }

    // --- Detail pane --------------------------------------------------------------------

    private fun buildDetailPane() {
        val pane = b.harPaneDetail

        section(pane, "Record these requests")
        val chips = ChipGroup(activity).apply {
            chipSpacingHorizontal = activity.dp(8)
            chipSpacingVertical = activity.dp(2)
        }
        for (kind in HarOptions.Kind.entries) {
            val chip = inflater.inflate(R.layout.item_chip_filter, chips, false) as Chip
            chip.text = kind.label
            chip.isChecked = HarOptions.recordKind(kind)
            chip.setOnCheckedChangeListener { _, on ->
                HarOptions.setRecordKind(kind, on)
                onChanged()
            }
            refreshers += { chip.isChecked = HarOptions.recordKind(kind) }
            chips.addView(chip)
        }
        pane.addView(chips)
        hint(pane, "Unchecked types are left out of the .har file. The Network tab still shows them.")

        section(pane, "Bodies & size limits")
        val bodies = newCard(pane)
        limitRow(
            bodies, R.drawable.ic_doc, "Text bodies", "JS, CSS, HTML, JSON and XML responses",
            hasSwitch = true, getOn = { HarOptions.textBodies }, setOn = { HarOptions.textBodies = it },
            getValue = { HarOptions.textLimitMb }, setValue = { HarOptions.textLimitMb = it },
            unit = "MB", min = HarOptions.MIN_LIMIT_MB, max = HarOptions.MAX_LIMIT_MB, integer = false,
            fieldHint = "per body"
        )
        limitRow(
            bodies, R.drawable.ic_image, "Images", "Embedded in the file as base64",
            hasSwitch = true, getOn = { HarOptions.images }, setOn = { HarOptions.images = it },
            getValue = { HarOptions.imageLimitMb }, setValue = { HarOptions.imageLimitMb = it },
            unit = "MB", min = HarOptions.MIN_LIMIT_MB, max = HarOptions.MAX_LIMIT_MB, integer = false,
            fieldHint = "per image"
        )
        limitRow(
            bodies, R.drawable.ic_storage, "Fonts & binaries", "Web fonts and other binary files, as base64",
            hasSwitch = true, getOn = { HarOptions.fonts }, setOn = { HarOptions.fonts = it },
            getValue = { HarOptions.fontLimitMb }, setValue = { HarOptions.fontLimitMb = it },
            unit = "MB", min = HarOptions.MIN_LIMIT_MB, max = HarOptions.MAX_LIMIT_MB, integer = false,
            fieldHint = "per file"
        )
        limitRow(
            bodies, R.drawable.ic_network, "Request payloads", "POST / PUT / PATCH data sent by the page",
            hasSwitch = true, getOn = { HarOptions.requestBodies }, setOn = { HarOptions.requestBodies = it },
            getValue = { HarOptions.requestBodyLimitMb }, setValue = { HarOptions.requestBodyLimitMb = it },
            unit = "MB", min = HarOptions.MIN_LIMIT_MB, max = HarOptions.MAX_LIMIT_MB, integer = false,
            fieldHint = "per request"
        )
        hint(pane, "Anything larger than its limit is cut off or left out. Values accept decimals, e.g. 0.5.")

        section(pane, "Headers & cookies")
        val headers = newCard(pane)
        switchRow(
            headers, R.drawable.ic_list, "Request headers", "Real headers sent by the browser (User-Agent, Accept, Referer…)",
            { HarOptions.requestHeaders }, { HarOptions.requestHeaders = it }
        )
        switchRow(
            headers, R.drawable.ic_cookie, "Cookies", "The Cookie header of each request",
            { HarOptions.cookies }, { HarOptions.cookies = it }
        )
        switchRow(
            headers, R.drawable.ic_list, "Response headers", "Status line extras, content type, caching…",
            { HarOptions.responseHeaders }, { HarOptions.responseHeaders = it }
        )
        switchRow(
            headers, R.drawable.ic_timer, "Timing breakdown", "DNS, connect, TLS, wait and receive",
            { HarOptions.timings }, { HarOptions.timings = it }
        )

        section(pane, "Privacy & filters")
        val privacy = newCard(pane)
        switchRow(
            privacy, R.drawable.ic_shield, "Redact sensitive values", "Mask Authorization, cookies, API keys and token parameters",
            { HarOptions.redact }, { HarOptions.redact = it }
        )
        switchRow(
            privacy, R.drawable.ic_network, "Third-party requests", "Requests to sites other than the one you are visiting",
            { HarOptions.thirdParty }, { HarOptions.thirdParty = it }
        )
        textRow(
            privacy, R.drawable.ic_filter, "Skip URLs containing", "analytics, doubleclick, …",
            { HarOptions.excludeUrls }, { HarOptions.excludeUrls = it }
        )

        val reset = com.google.android.material.button.MaterialButton(
            activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "Reset capture options"
            setIconResource(R.drawable.ic_reset)
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = activity.dp(20) }
            setOnClickListener {
                HarOptions.resetAll()
                refreshAll()
                Toast.makeText(activity, "Capture options reset", Toast.LENGTH_SHORT).show()
            }
        }
        pane.addView(reset)
    }

    // --- Export pane --------------------------------------------------------------------

    private fun buildExportPane() {
        val pane = b.harPaneExport

        section(pane, "Save the .har file")
        val card = newCard(pane)
        exportRows += actionRow(card, R.drawable.ic_share, "Share", "Send the .har file to another app") {
            dialog.dismiss()
            activity.lifecycleScope.launch { HarExporter.share(activity) }
        }
        exportRows += actionRow(card, R.drawable.ic_share, "Share as ZIP", "Compressed .zip containing the .har") {
            dialog.dismiss()
            activity.lifecycleScope.launch { HarExporter.shareZip(activity) }
        }
        exportRows += actionRow(card, R.drawable.ic_download, "Save to Downloads", "Saved straight to Downloads/Netex") {
            activity.lifecycleScope.launch { HarExporter.saveToDownloads(activity) }
        }
        exportRows += actionRow(card, R.drawable.ic_folder, "File manager", "Choose where to save it") {
            dialog.dismiss()
            activity.lifecycleScope.launch { HarExporter.pickLocation(activity, onPickLocation) }
        }

        section(pane, "Danger zone")
        val danger = newCard(pane)
        clearRow = actionRow(
            danger, R.drawable.ic_delete, "Clear recording", "Deletes the current and cached .har files",
            destructive = true
        ) {
            HarRecorder.clear()
            Toast.makeText(activity, "HAR cache cleared", Toast.LENGTH_SHORT).show()
        }
    }

    // --- live status --------------------------------------------------------------------

    private fun render(s: HarRecorder.Status) {
        val counts = "${s.entries} request${if (s.entries == 1) "" else "s"} · " +
            "${s.pages} page${if (s.pages == 1) "" else "s"}"
        b.harStatus.text = when {
            s.state == State.IDLE && s.entries == 0 -> "Ready to record"
            s.state == State.IDLE -> "Stopped · $counts"
            s.state == State.PAUSED && s.full -> "Limit reached · $counts"
            s.state == State.PAUSED -> "Paused · $counts"
            s.entries == 0 -> "Recording · reload the page to capture requests"
            else -> "Recording · $counts"
        }
        val dot = when (s.state) {
            State.RECORDING -> R.color.nx_rec
            State.PAUSED -> R.color.nx_paused
            State.IDLE -> R.color.nx_idle
        }
        b.harDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, dot))
        b.harFile.text = s.fileName ?: if (s.state == State.IDLE) "Press Record, then reload the page" else "No requests yet"

        val frac = if (s.maxBytes > 0) (s.bytes.toDouble() / s.maxBytes.toDouble()).coerceIn(0.0, 1.0) else 0.0
        b.harMeter.setProgressCompat((frac * 1000).toInt(), true)
        b.harMeterLabel.text = "≈ ${megabytes(s.bytes)} of ${megabytes(s.maxBytes)} stored · " +
            "${s.entries} of ${s.maxEntries} requests"

        b.harStart.text = if (s.state == State.IDLE && s.entries == 0) "Record" else "New"
        val paused = s.state == State.PAUSED
        b.harPause.text = if (paused) "Resume" else "Pause"
        b.harPause.setIconResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause)
        b.harPause.isEnabled = s.state != State.IDLE
        b.harStop.isEnabled = s.state != State.IDLE

        val hasData = s.entries > 0
        for (row in exportRows) enable(row, hasData)
        clearRow?.let { enable(it, s.state != State.IDLE || hasData) }
    }

    private fun enable(row: View, on: Boolean) {
        row.isEnabled = on
        row.alpha = if (on) 1f else 0.4f
    }

    private fun megabytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb < 10) String.format(java.util.Locale.US, "%.1f MB", mb)
        else String.format(java.util.Locale.US, "%.0f MB", mb)
    }

    // --- row builders -------------------------------------------------------------------

    private fun section(parent: ViewGroup, text: String) {
        val label = inflater.inflate(R.layout.item_section_label, parent, false) as TextView
        label.text = text
        parent.addView(label)
    }

    private fun hint(parent: ViewGroup, text: String) {
        val v = TextView(activity).apply {
            this.text = text
            textSize = 12.5f
            setTextColor(ContextCompat.getColor(activity, R.color.nx_on_surface_variant))
            setPadding(activity.dp(6), activity.dp(8), activity.dp(6), 0)
        }
        parent.addView(v)
    }

    private fun newCard(parent: ViewGroup): android.widget.LinearLayout {
        val card = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            clipToOutline = true
            showDividers = android.widget.LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = ContextCompat.getDrawable(activity, R.drawable.divider_inset)
        }
        parent.addView(card, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return card
    }

    private fun switchRow(
        parent: ViewGroup, @DrawableRes icon: Int, title: String, subtitle: String,
        get: () -> Boolean, set: (Boolean) -> Unit
    ) {
        val r = ItemOptionRowBinding.inflate(inflater, parent, false)
        r.optIcon.setImageResource(icon)
        r.optTitle.text = title
        r.optSubtitle.text = subtitle
        r.optSwitch.visibility = View.VISIBLE
        r.optSwitch.isChecked = get()
        r.root.setOnClickListener {
            val v = !get()
            set(v)
            r.optSwitch.isChecked = v
            onChanged()
        }
        refreshers += { r.optSwitch.isChecked = get() }
        parent.addView(r.root)
    }

    private fun actionRow(
        parent: ViewGroup, @DrawableRes icon: Int, title: String, subtitle: String,
        destructive: Boolean = false, onClick: () -> Unit
    ): View {
        val r = ItemOptionRowBinding.inflate(inflater, parent, false)
        r.optIcon.setImageResource(icon)
        r.optTitle.text = title
        r.optSubtitle.text = subtitle
        if (destructive) {
            val red = ContextCompat.getColor(activity, R.color.nx_error)
            r.optTitle.setTextColor(red)
            r.optIcon.imageTintList = ColorStateList.valueOf(red)
        } else {
            r.optChevron.visibility = View.VISIBLE
        }
        r.root.setOnClickListener { onClick() }
        parent.addView(r.root)
        return r.root
    }

    private fun textRow(
        parent: ViewGroup, @DrawableRes icon: Int, label: String, placeholder: String,
        get: () -> String, set: (String) -> Unit
    ) {
        val r = ItemOptionTextBinding.inflate(inflater, parent, false)
        r.optIcon.setImageResource(icon)
        r.optTextLayout.hint = label
        r.optTextLayout.placeholderText = placeholder
        r.optTextLayout.helperText = "Comma separated. Matching requests are not recorded."
        r.optText.setText(get())
        r.optText.onTextChanged {
            set(it)
            onChanged()
        }
        refreshers += { if (r.optText.text?.toString() != get()) r.optText.setText(get()) }
        parent.addView(r.root)
    }

    private fun limitRow(
        parent: ViewGroup, @DrawableRes icon: Int, title: String, subtitle: String,
        hasSwitch: Boolean, getOn: () -> Boolean, setOn: (Boolean) -> Unit,
        getValue: () -> Float, setValue: (Float) -> Unit,
        unit: String, min: Float, max: Float, integer: Boolean, fieldHint: String = ""
    ) {
        val r = ItemOptionLimitBinding.inflate(inflater, parent, false)
        r.optIcon.setImageResource(icon)
        r.optTitle.text = title
        r.optSubtitle.text = subtitle
        r.optLimitLayout.suffixText = unit
        r.optLimitLayout.hint = if (fieldHint.isNotEmpty()) "Limit $fieldHint" else "Limit"
        r.optLimitHint.text = "${HarOptions.fmt(min)}–${HarOptions.fmt(max)}"
        if (integer) r.optLimit.inputType = InputType.TYPE_CLASS_NUMBER

        fun shown(v: Float): String = if (integer) v.toInt().toString() else HarOptions.fmt(v)
        fun applyEnabled(on: Boolean) {
            r.optLimitLayout.isEnabled = on
            r.optLimit.isEnabled = on
            r.optLimitRow.alpha = if (on) 1f else 0.45f
        }

        r.optLimit.setText(shown(getValue()))
        if (hasSwitch) {
            r.optSwitch.isChecked = getOn()
            applyEnabled(getOn())
            r.optRow.setOnClickListener {
                val v = !getOn()
                setOn(v)
                r.optSwitch.isChecked = v
                applyEnabled(v)
                onChanged()
            }
        } else {
            r.optSwitch.visibility = View.GONE
            r.optRow.isClickable = false
            r.optRow.isFocusable = false
            r.optRow.background = null
        }

        r.optLimit.onTextChanged { text ->
            val parsed = text.replace(',', '.').toFloatOrNull()
            if (parsed == null || parsed < min || parsed > max) {
                r.optLimitLayout.error = "Enter ${HarOptions.fmt(min)}–${HarOptions.fmt(max)} $unit"
            } else {
                r.optLimitLayout.error = null
                if (parsed != getValue()) {
                    setValue(parsed)
                    onChanged()
                }
            }
        }
        refreshers += {
            if (hasSwitch) {
                r.optSwitch.isChecked = getOn()
                applyEnabled(getOn())
            }
            r.optLimit.setText(shown(getValue()))
        }
        parent.addView(r.root)
    }
}
