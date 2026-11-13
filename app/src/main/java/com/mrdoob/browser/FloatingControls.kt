package com.mrdoob.browser

import android.annotation.SuppressLint
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
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.mrdoob.browser.HarRecorder.State
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

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
 * Draggable bubble that floats over the browser (this app only, never over other apps, so no
 * overlay permission is involved). Its action buttons open on an arc AROUND the bubble, on the
 * side that faces the middle of the screen. What it shows follows the HAR recorder state:
 *
 *  - idle, closed      -> gear bubble; tap opens the ring
 *  - idle, open        -> bubble turns into X (close); around it: Play, Reload, HAR settings,
 *                         Cookie settings, Delete cookies
 *  - recording         -> red pill with the live .har size; tap it to pause
 *  - paused, closed    -> amber gear bubble; tap opens the ring
 *  - paused, open      -> bubble turns into X (ends the session, keeps the data exportable);
 *                         around it: Save to Downloads, Share
 *
 * Every circle is filled with a colour that tells its type/status; icon/text colour is picked
 * for contrast. Drag the bubble anywhere: the ring follows it.
 */
class FloatingControls(
    private val parent: FrameLayout,
    private val host: FloatingHost
) {

    private val context: Context = parent.context
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private var enabled = false
    private var expanded = false
    private var state: State = HarRecorder.status.value.state
    private var sizeBytes = 0L
    private var sizeView: TextView? = null

    private var bubble: View? = null
    private val satellites = ArrayList<View>()
    private var animateRing = false

    // Drag offset of the bubble from its home corner (top-right).
    private var tx = 0f
    private var ty = 0f

    private val buttonSize = context.dp(44)
    private val ringRadius = context.dp(82).toFloat()
    private val ringGap = Math.toRadians(40.0)

    init {
        // Keep everything on screen when the screen size changes (rotation, keyboard, panel).
        parent.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            clampBubble()
            layoutRing(animate = false)
        }
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) expanded = false
        render()
    }

    /** Called whenever the HAR recorder changes state (from any place, not only this pop-up). */
    fun onHarState(newState: State) {
        if (newState == state) return
        expanded = false
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
        bubble?.let { parent.removeView(it) }
        satellites.forEach { parent.removeView(it) }
        bubble = null
        satellites.clear()
        sizeView = null
        if (!enabled) return

        when (state) {
            State.RECORDING -> {
                val pill = sizePill {
                    HarRecorder.togglePause()
                    onHarState(State.PAUSED) // show the gear right away
                }
                sizeView = pill
                onSize(sizeBytes)
                setBubble(pill)
            }
            State.PAUSED -> if (expanded) {
                setBubble(button(R.drawable.ic_close, "End recording and close", R.color.nx_surface_variant) {
                    HarRecorder.stop()
                    expanded = false
                    onHarState(State.IDLE)
                })
                addSatellite(button(R.drawable.ic_download, "Save HAR to Downloads", R.color.nx_primary) {
                    host.saveHar()
                })
                addSatellite(button(R.drawable.ic_share, "Share / open HAR", R.color.nx_tertiary) {
                    host.shareHar()
                })
            } else {
                setBubble(gearButton(R.color.nx_paused)) // amber = paused
            }
            State.IDLE -> if (expanded) {
                setBubble(button(R.drawable.ic_close, "Close", R.color.nx_surface_variant) {
                    expanded = false
                    render()
                })
                addSatellite(button(R.drawable.ic_play, "Start recording", R.color.nx_ok) {
                    HarRecorder.start()
                    onHarState(State.RECORDING) // collapse to the live-size pill right away
                })
                addSatellite(button(R.drawable.ic_refresh, "Hard reload", R.color.nx_secondary_container) {
                    host.hardReload()
                })
                addSatellite(button(R.drawable.ic_network, "Network capture settings", R.color.nx_primary_container) {
                    host.openHarSettings()
                })
                addSatellite(button(R.drawable.ic_cookie, "Cookie settings", R.color.nx_tertiary_container) {
                    host.openCookieSettings()
                })
                addSatellite(button(R.drawable.ic_cookie_off, "Delete cookies and hard reload", R.color.nx_error) {
                    host.clearCookiesAndReload()
                })
            } else {
                setBubble(gearButton(R.color.nx_primary_container)) // idle
            }
        }

        animateRing = satellites.isNotEmpty()
        parent.post {
            clampBubble()
            layoutRing(animate = true)
        }
    }

    private fun gearButton(@ColorRes fill: Int) = button(R.drawable.ic_settings, "Floating controls", fill) {
        expanded = true
        render()
    }

    private fun setBubble(view: View) {
        view.elevation = context.dp(8).toFloat()
        view.translationX = tx
        view.translationY = ty
        parent.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.RIGHT
            ).apply {
                topMargin = context.dp(12)
                rightMargin = context.dp(10)
            }
        )
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            clampBubble()
            layoutRing(animate = false)
        }
        attachDrag(view)
        bubble = view
    }

    private fun addSatellite(view: View) {
        view.elevation = context.dp(6).toFloat()
        // Positioned purely through translation from the parent's top-left corner.
        parent.addView(
            view,
            FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.TOP or Gravity.LEFT)
        )
        view.visibility = View.INVISIBLE // shown once placed, so it never flashes in a corner
        satellites += view
    }

    // --- ring placement ----------------------------------------------------------------------

    /** Spreads the satellites on an arc around the bubble, on the side facing the screen centre. */
    private fun layoutRing(animate: Boolean) {
        val b = bubble ?: return
        if (satellites.isEmpty() || parent.width == 0 || b.width == 0) return

        val cx = b.left + b.translationX + b.width / 2f
        val cy = b.top + b.translationY + b.height / 2f
        val midX = (parent.paddingLeft + parent.width - parent.paddingRight) / 2f
        val midY = (parent.paddingTop + parent.height - parent.paddingBottom) / 2f
        val base = atan2((midY - cy).toDouble(), (midX - cx).toDouble())

        val minLeft = parent.paddingLeft.toFloat()
        val maxLeft = (parent.width - parent.paddingRight - buttonSize).toFloat()
        val minTop = parent.paddingTop.toFloat()
        val maxTop = (parent.height - parent.paddingBottom - buttonSize).toFloat()

        val n = satellites.size
        val play = animate && animateRing
        satellites.forEachIndexed { i, v ->
            val angle = base + (i - (n - 1) / 2.0) * ringGap
            val left = (cx + ringRadius * cos(angle).toFloat() - buttonSize / 2f)
                .coerceIn(minLeft, maxOf(minLeft, maxLeft))
            val top = (cy + ringRadius * sin(angle).toFloat() - buttonSize / 2f)
                .coerceIn(minTop, maxOf(minTop, maxTop))
            v.translationX = left - parent.paddingLeft
            v.translationY = top - parent.paddingTop
            if (v.visibility != View.VISIBLE) v.visibility = View.VISIBLE
            if (play) {
                // Pop out from the bubble's centre.
                v.alpha = 0f
                v.scaleX = 0.3f
                v.scaleY = 0.3f
                v.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setStartDelay(i * 25L)
                    .setDuration(180L)
                    .setInterpolator(OvershootInterpolator(1.4f))
                    .start()
            }
        }
        if (play) animateRing = false
    }

    private fun clampBubble() {
        val b = bubble ?: return
        if (parent.width == 0 || b.width == 0) return
        val minTx = (parent.paddingLeft - b.left).toFloat()
        val maxTx = ((parent.width - parent.paddingRight) - b.width - b.left).toFloat()
        val minTy = (parent.paddingTop - b.top).toFloat()
        val maxTy = ((parent.height - parent.paddingBottom) - b.height - b.top).toFloat()
        b.translationX = b.translationX.coerceIn(minTx, maxOf(minTx, maxTx))
        b.translationY = b.translationY.coerceIn(minTy, maxOf(minTy, maxTy))
        tx = b.translationX
        ty = b.translationY
    }

    /** Lets the bubble be dragged; a tap (movement under the touch slop) still clicks it. */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(v: View) {
        var downX = 0f
        var downY = 0f
        var startTx = 0f
        var startTy = 0f
        var dragging = false
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startTx = view.translationX
                    startTy = view.translationY
                    dragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && (abs(ev.rawX - downX) > slop || abs(ev.rawY - downY) > slop)) {
                        dragging = true
                        view.parent.requestDisallowInterceptTouchEvent(true)
                        // Release the pressed state/ripple so no click fires after the drag.
                        val cancel = MotionEvent.obtain(ev)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        view.onTouchEvent(cancel)
                        cancel.recycle()
                    }
                    if (dragging) {
                        view.translationX = startTx + (ev.rawX - downX)
                        view.translationY = startTy + (ev.rawY - downY)
                        clampBubble()
                        layoutRing(animate = false)
                    }
                    dragging
                }
                else -> dragging // UP / CANCEL: swallow only if a drag was in progress
            }
        }
    }

    // --- button factories ----------------------------------------------------------------------

    /** Filled circle/pill background; returns it with the contrasting content colour. */
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
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(context.dp(10), context.dp(10), context.dp(10), context.dp(10))
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
        minWidth = buttonSize
        minHeight = buttonSize
        setPadding(context.dp(14), 0, context.dp(14), 0)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }
}
