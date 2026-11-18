package com.mrdoob.browser

import android.app.Activity
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** The "Open another website?" dialog: one screen with the facts and four choices. */
object NavConfirmDialog {

    enum class Choice { CANCEL, CONTINUE, ALWAYS_ALLOW, ALWAYS_BLOCK }

    class Info(
        val currentSite: String,
        val destinationSite: String,
        val url: String,
        val type: NavPolicy.Type,
        val initiator: String,
        val reason: String,
        val relation: NavPolicy.Relation?,
        /** Hosts or URLs of the redirect chain so far, first to last. Empty when there is no chain. */
        val chain: List<String>,
        val warnings: List<String>,
        val external: Boolean
    )

    fun show(activity: Activity, info: Info, onChoice: (Choice) -> Unit): AlertDialog {
        var answered = false
        lateinit var dialog: AlertDialog
        fun answer(c: Choice) {
            if (answered) return
            answered = true
            dialog.dismiss()
            onChoice(c)
        }

        val ctx = activity
        val onSurface = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurface, 0)
        val variant = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)
        val error = MaterialColors.getColor(ctx, androidx.appcompat.R.attr.colorError, 0)

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(24), ctx.dp(8), ctx.dp(24), ctx.dp(4))
        }

        fun label(text: String) = TextView(ctx).apply {
            this.text = text
            textSize = 12f
            setTextColor(variant)
            setPadding(0, ctx.dp(10), 0, 0)
        }.also { col.addView(it) }

        fun value(text: String, mono: Boolean = false, color: Int = onSurface) = TextView(ctx).apply {
            this.text = text
            textSize = 15f
            setTextColor(color)
            if (mono) { typeface = Typeface.MONOSPACE; textSize = 13f }
            setTextIsSelectable(true)
        }.also { col.addView(it) }

        label("Current website"); value(info.currentSite.ifEmpty { "(none)" })
        label("Destination website"); value(info.destinationSite)
        label("Full destination URL"); value(info.url, mono = true).apply { maxLines = 6; ellipsize = TextUtils.TruncateAt.END }
        label("Navigation type")
        value(info.type.label + (info.relation?.let { " · ${it.label}" } ?: ""))
        label("Navigation initiator"); value(info.initiator)

        if (info.chain.size >= 2) {
            label("Redirect chain")
            info.chain.forEachIndexed { i, hop ->
                if (i > 0) value("↓", color = variant).apply { gravity = Gravity.START; setPadding(ctx.dp(12), 0, 0, 0) }
                value(hop, mono = true, color = if (i == info.chain.lastIndex) onSurface else variant)
            }
        }

        if (info.warnings.isNotEmpty()) {
            label("Warning").setTextColor(error)
            info.warnings.forEach { value("• $it", color = error).textSize = 13f }
            value("These are observations about how the navigation started, not a verdict on the site.", color = variant).textSize = 12f
        }
        if (info.reason.isNotBlank()) {
            label("Why you are asked"); value(info.reason).textSize = 13f
        }

        fun button(text: String, style: Int, c: Choice, tint: Int? = null) =
            MaterialButton(ctx, null, style).apply {
                this.text = text
                isAllCaps = false
                if (tint != null) setTextColor(tint)
                setOnClickListener { answer(c) }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4))
                }
            }

        val outlined = com.google.android.material.R.attr.materialButtonOutlinedStyle
        val tonal = com.google.android.material.R.attr.materialButtonOutlinedStyle
        val rowOne = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("Cancel", outlined, Choice.CANCEL))
            addView(MaterialButton(ctx).apply {
                text = "Continue"
                isAllCaps = false
                setOnClickListener { answer(Choice.CONTINUE) }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4))
                }
            })
        }
        val rowTwo = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button(if (info.external) "Always allow app links like this" else "Always allow", tonal, Choice.ALWAYS_ALLOW))
            addView(button(if (info.external) "Always block app links like this" else "Always block", tonal, Choice.ALWAYS_BLOCK, error))
        }
        col.addView(View(ctx), LinearLayout.LayoutParams(1, ctx.dp(12)))
        col.addView(rowOne)
        col.addView(rowTwo)

        val scroll = ScrollView(ctx).apply { addView(col) }
        dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(if (info.external) "Open another app?" else "Open another website?")
            .setView(scroll)
            .setOnCancelListener { answer(Choice.CANCEL) }
            .create()
        dialog.setOnDismissListener { answer(Choice.CANCEL) }
        dialog.show()
        return dialog
    }
}
