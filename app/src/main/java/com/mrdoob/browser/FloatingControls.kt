package com.mrdoob.browser

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.mrdoob.browser.HarRecorder.State
import kotlin.math.abs

/** What the floating pop-up needs from the browser. */
interface FloatingHost {
    fun hardReload()
    fun openHarSettings()
    fun openCookieSettings()
    fun clearCookiesAndReload()
    fun saveHar()
    fun shareHar()
}

/**
 * Small draggable pop-up that floats over the browser (this app only, never over other apps,
 * so no overlay permission is involved). What it shows follows the HAR recorder state:
 *
 *  - idle, collapsed      -> settings (gear) button; tap opens the controls
 *  - idle, open           -> Play · Reload · HAR settings · Cookie settings · Delete cookies · X
 *  - recording            -> a red pill with the live .har size; tap it to pause
 *  - paused, collapsed    -> settings (gear) button; tap opens Save / Share
 *  - paused, open         -> Save to Downloads · Share · X
 *
 * Every circle is filled with a colour that tells its type/status (green play, red recording,
 * amber paused, blue-ish settings, red delete ...); icon/text colour is picked for contrast.
 *
 * X closes the open controls back to the gear (while paused it also ends the session; the
 * captured data stays exportable until the next recording starts).
 */
class FloatingControls(
    private val parent: FrameLayout,
    private val host: FloatingHost
) {

    private val context: Context = parent.context
    private val bar = DragBar(context)

    private var expanded = false
    private var state: State = HarRecorder.status.value.state
    private var sizeBytes = 0L
    private var sizeView: TextView? = null

    init {
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundResource(R.drawable.bg_float_bar)
        bar.elevation = context.dp(10).toFloat()
        val pad = context.dp(4)
        bar.setPadding(pad, pad, pad, pad)
        bar.visibility = View.GONE
        parent.addView(
            bar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = context.dp(12)
                marginEnd = context.dp(10)
            }
        )
        // Keep it on screen when its size, or the screen's, changes.
        bar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> bar.clampToParent() }
        parent.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> bar.clampToParent() }
        render()
    }

    fun setEnabled(on: Boolean) {
        bar.visibility = if (on) View.VISIBLE else View.GONE
        if (on) bar.post { bar.clampToParent() }
    }

    /** Called whenever the HAR recorder changes state (from any place, not only this pop-up). */
    fun onHarState(newState: State) {
        if (newState != state) expanded = false
        state = newState
        render()
    }

    /** Live HAR size in bytes, shown on the recording pill. */
    fun onSize(bytes: Long) {
        sizeBytes = bytes
        sizeView?.let {
            it.text = formatSize(bytes)
            it.contentDescription = "Pause recording, ${formatSize(bytes)} captured"
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    // --- rendering ------------------------------------------------------------------------

    private fun render() {
        bar.removeAllViews()
        sizeView = null
        when (state) {
            State.RECORDING -> {
                val pill = sizePill {
                    HarRecorder.togglePause()
                    onHarState(State.PAUSED) // show the gear right away
                }
                sizeView = pill
                onSize(sizeBytes)
                bar.addView(pill)
            }
            State.PAUSED -> if (expanded) {
                bar.addView(button(R.drawable.ic_download, "Save HAR to Downloads", R.color.nx_primary) {
                    host.saveHar()
                })
                bar.addView(button(R.drawable.ic_share, "Share / open HAR", R.color.nx_tertiary) {
                    host.shareHar()
                })
                bar.addView(closeButton("End recording and close") {
                    HarRecorder.stop()
                    expanded = false
                    onHarState(State.IDLE)
                })
            } else {
                bar.addView(gearButton(R.color.nx_paused)) // amber = paused
            }
            State.IDLE -> if (expanded) {
                bar.addView(button(R.drawable.ic_play, "Start recording", R.color.nx_ok) {
                    HarRecorder.start()
                    onHarState(State.RECORDING) // collapse to the live-size pill right away
                })
                bar.addView(button(R.drawable.ic_refresh, "Hard reload", R.color.nx_secondary_container) {
                    host.hardReload()
                })
                bar.addView(button(R.drawable.ic_network, "Network capture settings", R.color.nx_primary_container) {
                    host.openHarSettings()
                })
                bar.addView(button(R.drawable.ic_cookie, "Cookie settings", R.color.nx_tertiary_container) {
                    host.openCookieSettings()
                })
                bar.addView(button(R.drawable.ic_cookie_off, "Delete cookies and hard reload", R.color.nx_error) {
                    host.clearCookiesAndReload()
                })
                bar.addView(closeButton("Close") {
                    expanded = false
                    render()
                })
            } else {
                bar.addView(gearButton(R.color.nx_primary_container)) // idle
            }
        }
    }

    private fun gearButton(@ColorRes fill: Int) = button(R.drawable.ic_settings, "Floating controls", fill) {
        expanded = true
        render()
    }

    private fun closeButton(description: String, onClick: () -> Unit) =
        button(R.drawable.ic_close, description, R.color.nx_surface_variant, onClick)

    /** Filled circle/pill background; returns the fill and the contrasting content colour. */
    private fun filled(@ColorRes fillRes: Int): Pair<RippleDrawable, Int> {
        val fill = ContextCompat.getColor(context, fillRes)
        val on = if (ColorUtils.calculateLuminance(fill) > 0.5) 0xFF1B1B21.toInt() else 0xFFFFFFFF.toInt()
        val shape = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = context.dp(22).toFloat()
        }
        val ripple = RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(on, 70)), shape, null)
        return ripple to on
    }

    private fun button(
        @DrawableRes icon: Int,
        description: String,
        @ColorRes fill: Int,
        onClick: () -> Unit
    ): ImageButton = ImageButton(context).apply {
        val (bg, on) = filled(fill)
        setImageResource(icon)
        contentDescription = description
        background = bg
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        setPadding(context.dp(10), context.dp(10), context.dp(10), context.dp(10))
        layoutParams = LinearLayout.LayoutParams(context.dp(44), context.dp(44)).apply {
            marginStart = context.dp(2)
            marginEnd = context.dp(2)
        }
        imageTintList = ColorStateList.valueOf(on)
        setOnClickListener { onClick() }
    }

    /** Red recording pill showing the live HAR size; tapping it pauses. */
    private fun sizePill(onClick: () -> Unit): TextView = TextView(context).apply {
        val (bg, on) = filled(R.color.nx_rec)
        background = bg
        setTextColor(on)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        maxLines = 1
        minWidth = context.dp(44)
        setPadding(context.dp(14), 0, context.dp(14), 0)
        isClickable = true
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, context.dp(44)
        ).apply {
            marginStart = context.dp(2)
            marginEnd = context.dp(2)
        }
        setOnClickListener { onClick() }
    }

    /** A LinearLayout that can be dragged anywhere inside its parent; taps still reach the buttons. */
    private class DragBar(context: Context) : LinearLayout(context) {

        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startTx = 0f
        private var startTy = 0f
        private var dragging = false

        private fun begin(ev: MotionEvent) {
            downX = ev.rawX
            downY = ev.rawY
            startTx = translationX
            startTy = translationY
            dragging = false
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> begin(ev)
                MotionEvent.ACTION_MOVE -> if (!dragging &&
                    (abs(ev.rawX - downX) > slop || abs(ev.rawY - downY) > slop)
                ) {
                    dragging = true
                    parent.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            return dragging
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> begin(ev) // touch landed on the bar's padding
                MotionEvent.ACTION_MOVE -> {
                    dragging = true
                    translationX = startTx + (ev.rawX - downX)
                    translationY = startTy + (ev.rawY - downY)
                    clampToParent()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
            }
            return true
        }

        fun clampToParent() {
            val p = parent as? View ?: return
            if (p.width == 0 || width == 0) return
            val minTx = (p.paddingLeft - left).toFloat()
            val maxTx = ((p.width - p.paddingRight) - width - left).toFloat()
            val minTy = (p.paddingTop - top).toFloat()
            val maxTy = ((p.height - p.paddingBottom) - height - top).toFloat()
            translationX = translationX.coerceIn(minTx, maxOf(minTx, maxTx))
            translationY = translationY.coerceIn(minTy, maxOf(minTy, maxTy))
        }
    }
}
