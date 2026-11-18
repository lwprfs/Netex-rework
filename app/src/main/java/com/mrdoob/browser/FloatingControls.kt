package com.mrdoob.browser

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
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

interface FloatingHost {
    fun hardReload()
    fun openHarSettings()
    fun openCookieSettings()
    fun clearCookiesAndReload()
    fun saveHar()
    fun shareHar()
}

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
    private var requestCount = 0
    private var nearLimit = false
    private var sizeView: TextView? = null

    private var items: Set<String> = BrowserPrefs.floatingItems(context)
    private var haptics = BrowserPrefs.floatingHaptics(context)

    private var bubble: View? = null
    private val satellites = ArrayList<View>()
    private var animateRing = false

    private var tx = 0f
    private var ty = BrowserPrefs.floatingY(context)
    private var onLeft = BrowserPrefs.floatingOnLeft(context)
    private var isDragging = false
    private var snapping = false

    private val buttonSize = context.dp(44)
    private val ringRadius = context.dp(82).toFloat()
    private val ringGap = Math.toRadians(40.0)

    init {

        parent.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            clampBubble()
            layoutRing(animate = false)
        }
    }

    fun applyPrefs() {
        haptics = BrowserPrefs.floatingHaptics(context)
        val on = BrowserPrefs.floatingControls(context)
        val newItems = BrowserPrefs.floatingItems(context)
        if (on != enabled || newItems != items) {
            enabled = on
            items = newItems
            if (!on) expanded = false
            render()
        }
    }

    private var suppressed = false

    /** Hides the bubble while the developer panel is full screen. */
    fun setSuppressed(hide: Boolean) {
        suppressed = hide
        if (hide && expanded) { expanded = false; render() }
        bubble?.visibility = if (hide) View.GONE else View.VISIBLE
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) expanded = false
        render()
    }

    fun onHarState(newState: State) {
        if (newState == state) return
        expanded = false
        state = newState
        render()
    }

    fun onSize(bytes: Long, count: Int, near: Boolean) {
        sizeBytes = bytes
        requestCount = count
        val nearChanged = near != nearLimit
        nearLimit = near
        sizeView?.let {
            if (nearChanged) stylePill(it)
            val text = "$count · ${formatSize(bytes)}"
            it.text = if (near) "⚠ $text" else text
            it.contentDescription = "Pause recording, $count requests, ${formatSize(bytes)} captured" +
                if (near) ", nearly full" else ""
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

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
                    onHarState(State.PAUSED)
                }
                sizeView = pill
                onSize(sizeBytes, requestCount, nearLimit)
                setBubble(pill)
            }
            State.PAUSED -> if (expanded) {
                setBubble(button(R.drawable.ic_close, "End recording and close", R.color.nx_surface_variant) {
                    HarRecorder.stop()
                    expanded = false
                    onHarState(State.IDLE)
                })
                addSatellite(button(R.drawable.ic_play, "Resume recording", R.color.nx_ok, haptic = true) {
                    HarRecorder.togglePause()
                    onHarState(State.RECORDING)
                })
                addSatellite(button(R.drawable.ic_download, "Save HAR to Downloads", R.color.nx_primary) {
                    host.saveHar()
                })
                addSatellite(button(R.drawable.ic_share, "Share / open HAR", R.color.nx_tertiary) {
                    host.shareHar()
                })
            } else {
                setBubble(gearButton(R.color.nx_paused))
            }
            State.IDLE -> if (expanded) {
                setBubble(button(R.drawable.ic_close, "Close", R.color.nx_surface_variant) {
                    expanded = false
                    render()
                })
                addSatellite(button(R.drawable.ic_play, "Start recording", R.color.nx_ok, haptic = true) {
                    HarRecorder.start()
                    onHarState(State.RECORDING)
                })
                if (BrowserPrefs.ITEM_RELOAD in items) {
                    addSatellite(button(R.drawable.ic_refresh, "Hard reload", R.color.nx_secondary_container) {
                        host.hardReload()
                    })
                }
                if (BrowserPrefs.ITEM_HAR in items) {
                    addSatellite(button(R.drawable.ic_network, "Network capture settings", R.color.nx_primary_container) {
                        host.openHarSettings()
                    })
                }
                if (BrowserPrefs.ITEM_COOKIES in items) {
                    addSatellite(button(R.drawable.ic_cookie, "Cookie settings", R.color.nx_tertiary_container) {
                        host.openCookieSettings()
                    })
                }
                if (BrowserPrefs.ITEM_DELETE in items) {
                    addSatellite(button(R.drawable.ic_cookie_off, "Delete cookies and hard reload", R.color.nx_error, haptic = true) {
                        host.clearCookiesAndReload()
                    })
                }
            } else {
                setBubble(gearButton(R.color.nx_primary_container))
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
        view.visibility = if (suppressed) View.GONE else View.VISIBLE
        bubble = view
    }

    private fun addSatellite(view: View) {
        view.elevation = context.dp(6).toFloat()

        parent.addView(
            view,
            FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.TOP or Gravity.LEFT)
        )
        view.visibility = View.INVISIBLE
        satellites += view
    }

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

                v.alpha = 0f
                v.scaleX = 0.3f
                v.scaleY = 0.3f
                v.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setStartDelay(Appearance.scaled(context, i * 25L))
                    .setDuration(Appearance.scaled(context, 180L))
                    .setInterpolator(OvershootInterpolator(1.4f))
                    .start()
            }
        }
        if (play) animateRing = false
    }

    private fun edgeMargin() = context.dp(6)

    private fun clampBubble() {
        val b = bubble ?: return
        if (parent.width == 0 || b.width == 0) return
        val m = edgeMargin()
        val minTx = (parent.paddingLeft + m - b.left).toFloat()
        val maxTx = ((parent.width - parent.paddingRight - m) - b.width - b.left).toFloat()
        val minTy = (parent.paddingTop - b.top).toFloat()
        val maxTy = ((parent.height - parent.paddingBottom) - b.height - b.top).toFloat()
        val hi = maxOf(minTx, maxTx)

        val wantX = if (isDragging || snapping) b.translationX else if (onLeft) minTx else maxTx
        b.translationX = wantX.coerceIn(minTx, hi)
        b.translationY = b.translationY.coerceIn(minTy, maxOf(minTy, maxTy))
        tx = b.translationX
        ty = b.translationY
    }

    private fun snapToEdge() {
        val b = bubble ?: return
        if (parent.width == 0 || b.width == 0) return
        val m = edgeMargin()
        val minTx = (parent.paddingLeft + m - b.left).toFloat()
        val maxTx = ((parent.width - parent.paddingRight - m) - b.width - b.left).toFloat()
        val centerX = b.left + b.translationX + b.width / 2f
        onLeft = centerX < (parent.paddingLeft + parent.width - parent.paddingRight) / 2f
        val target = if (onLeft) minTx else maxOf(minTx, maxTx)
        snapping = true
        ValueAnimator.ofFloat(b.translationX, target).apply {
            duration = Appearance.scaled(context, 180L)
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                b.translationX = it.animatedValue as Float
                layoutRing(animate = false)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    snapping = false
                    clampBubble()
                    layoutRing(animate = false)
                    BrowserPrefs.setFloatingPosition(context, onLeft, ty)
                }
            })
            start()
        }
    }

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
                        isDragging = true
                        view.parent.requestDisallowInterceptTouchEvent(true)

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
                else -> {

                    val was = dragging
                    if (was) {
                        dragging = false
                        isDragging = false
                        snapToEdge()
                    }
                    was
                }
            }
        }
    }

    private fun filled(@ColorRes fillRes: Int): Pair<RippleDrawable, Int> {
        val fill = context.nx(fillRes)
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
        haptic: Boolean = false,
        onClick: () -> Unit
    ): ImageButton = ImageButton(context).apply {
        val (bg, on) = filled(fill)
        setImageResource(icon)
        contentDescription = description
        background = bg
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(context.dp(10), context.dp(10), context.dp(10), context.dp(10))
        imageTintList = ColorStateList.valueOf(on)
        setOnClickListener {
            if (haptic) tap(this)
            onClick()
        }
    }

    private fun tap(v: View) {
        if (haptics) v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    private fun stylePill(tv: TextView) {
        val (bg, on) = filled(if (nearLimit) R.color.nx_paused else R.color.nx_rec)
        tv.background = bg
        tv.setTextColor(on)
    }

    private fun sizePill(onClick: () -> Unit): TextView = TextView(context).apply {
        stylePill(this)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        maxLines = 1
        minWidth = buttonSize
        minHeight = buttonSize
        setPadding(context.dp(14), 0, context.dp(14), 0)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            tap(this)
            onClick()
        }
    }
}
