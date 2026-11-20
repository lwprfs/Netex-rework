package com.mrdoob.browser

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.graphics.ColorUtils
import com.mrdoob.browser.HarRecorder.State
import kotlin.math.abs

interface FloatingHost {
    fun hardReload()
    fun openHarSettings()
    fun openCookieSettings()
    fun clearCookiesAndReload()
    fun saveHar()
    fun shareHar()
}

/**
 * The floating network-tools control.
 *
 * Collapsed: one small surface that shows the recording state (a plain tool icon when idle, a dot and the request
 * count while recording or paused). Tap it to open a compact action panel; tap anywhere outside the panel to close
 * it. The bubble can be dragged, snaps to the nearest edge and remembers where it was.
 */
class FloatingControls(
    private val parent: FrameLayout,
    private val host: FloatingHost
) {

    private val context: Context = parent.context
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val handler = Handler(Looper.getMainLooper())

    private var enabled = false
    private var expanded = false
    private var suppressed = false
    private var state: State = HarRecorder.status.value.state
    private var sizeBytes = 0L
    private var requestCount = 0
    private var nearLimit = false

    private var items: Set<String> = BrowserPrefs.floatingItems(context)
    private var haptics = BrowserPrefs.floatingHaptics(context)
    private var sizeLevel = BrowserPrefs.floatingSize(context)
    private var opacity = BrowserPrefs.floatingOpacity(context)
    private var autoHide = BrowserPrefs.floatingAutoHide(context)
    private var labeled = BrowserPrefs.floatingLabeled(context)
    private var showRecord = BrowserPrefs.floatingRecord(context)

    private var bubble: View? = null
    private var scrim: View? = null
    private var countView: TextView? = null
    private var statusView: TextView? = null
    private var warnView: TextView? = null
    private var pulse: ObjectAnimator? = null

    private var tx = 0f
    private var ty = BrowserPrefs.floatingY(context)
    private var onLeft = BrowserPrefs.floatingOnLeft(context)
    private var isDragging = false
    private var snapping = false

    private val fade = Runnable {
        val b = bubble
        if (b != null && !expanded && !isDragging) b.animate().alpha(baseAlpha() * 0.35f).setDuration(Appearance.scaled(context, 250L)).start()
    }

    init {
        parent.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> clampBubble() }
    }

    private fun collapsedHeight() = context.dp(when (sizeLevel) { 0 -> 36; 2 -> 52; else -> 44 })
    private fun baseAlpha() = (opacity / 100f).coerceIn(0.3f, 1f)

    fun applyPrefs() {
        haptics = BrowserPrefs.floatingHaptics(context)
        val on = BrowserPrefs.floatingControls(context)
        val newItems = BrowserPrefs.floatingItems(context)
        val newSize = BrowserPrefs.floatingSize(context)
        val newOpacity = BrowserPrefs.floatingOpacity(context)
        val newAuto = BrowserPrefs.floatingAutoHide(context)
        val newLabeled = BrowserPrefs.floatingLabeled(context)
        val newRecord = BrowserPrefs.floatingRecord(context)
        var posChanged = false
        if (!isDragging && !snapping) {
            val l = BrowserPrefs.floatingOnLeft(context)
            val y = BrowserPrefs.floatingY(context)
            if (l != onLeft || y != ty) { onLeft = l; ty = y; posChanged = true }
        }
        if (on != enabled || newItems != items || newSize != sizeLevel || newOpacity != opacity || newAuto != autoHide ||
            newLabeled != labeled || newRecord != showRecord || posChanged
        ) {
            enabled = on
            items = newItems
            sizeLevel = newSize
            opacity = newOpacity
            autoHide = newAuto
            labeled = newLabeled
            showRecord = newRecord
            if (!on) expanded = false
            render()
        }
    }

    /** Hides the control while the developer tools are full screen. */
    fun setSuppressed(hide: Boolean) {
        suppressed = hide
        if (hide && expanded) { expanded = false; render() }
        bubble?.visibility = if (hide) View.GONE else View.VISIBLE
        scrim?.visibility = if (hide) View.GONE else View.VISIBLE
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
        countView?.let {
            it.text = if (near) "⚠ $count" else "$count"
            it.contentDescription = "$count requests, ${formatSize(bytes)} captured" + if (near) ", nearly full" else ""
        }
        statusView?.text = statusText()
        warnView?.visibility = if (near && state != State.IDLE) View.VISIBLE else View.GONE
        if (nearChanged && bubble != null && !expanded) render()
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    private fun statusText(): String = when (state) {
        State.RECORDING -> "Recording · $requestCount requests · ${formatSize(sizeBytes)}"
        State.PAUSED -> "Paused · $requestCount requests · ${formatSize(sizeBytes)}"
        State.IDLE -> "Not recording"
    }

    // --- rendering ---------------------------------------------------------------------------------------

    private fun clearViews() {
        handler.removeCallbacks(fade)
        pulse?.cancel()
        pulse = null
        bubble?.let { parent.removeView(it) }
        scrim?.let { parent.removeView(it) }
        bubble = null
        scrim = null
        countView = null
        statusView = null
        warnView = null
    }

    private fun render() {
        clearViews()
        if (!enabled) return
        if (expanded) {
            addScrim()
            setBubble(buildPanel(), draggable = false)
        } else {
            setBubble(buildCollapsed(), draggable = true)
        }
        parent.post { clampBubble() }
    }

    private fun addScrim() {
        val v = View(context)
        v.setOnClickListener { expanded = false; render() }
        v.contentDescription = "Close floating tools"
        parent.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        v.visibility = if (suppressed) View.GONE else View.VISIBLE
        scrim = v
    }

    private fun surfaceBg(color: Int, radius: Float): RippleDrawable {
        val shape = GradientDrawable().apply { setColor(color); cornerRadius = radius }
        val on = if (ColorUtils.calculateLuminance(color) > 0.5) 0xFF1B1B21.toInt() else 0xFFFFFFFF.toInt()
        return RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(on, 40)), shape, null)
    }

    private fun dot(@ColorRes color: Int): View = View(context).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(context.nx(color)) }
        layoutParams = LinearLayout.LayoutParams(context.dp(10), context.dp(10)).apply { marginEnd = context.dp(8) }
    }

    private fun icon(@DrawableRes res: Int, tint: Int, sizeDp: Int) = ImageView(context).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        layoutParams = LinearLayout.LayoutParams(context.dp(sizeDp), context.dp(sizeDp))
    }

    private fun buildCollapsed(): View {
        val h = collapsedHeight()
        val onSurface = context.nx(R.color.nx_on_surface)
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = h
            minimumWidth = h
            background = surfaceBg(context.nx(R.color.nx_container_high), h / 2f)
            isClickable = true
            isFocusable = true
            val pad = context.dp(if (state == State.IDLE && !labeled) 0 else 14)
            setPadding(pad, 0, pad, 0)
            contentDescription = when (state) {
                State.IDLE -> "Network tools"
                State.RECORDING -> "Network tools, recording, $requestCount requests"
                State.PAUSED -> "Network tools, recording paused, $requestCount requests"
            }
            setOnClickListener { tap(this); expanded = true; render() }
        }
        when (state) {
            State.IDLE -> {
                box.addView(icon(R.drawable.ic_network, context.nx(R.color.nx_on_surface_variant), if (sizeLevel == 0) 18 else 22))
                if (labeled) box.addView(TextView(context).apply {
                    text = "Tools"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(onSurface)
                    setPadding(context.dp(8), 0, 0, 0)
                })
            }
            State.RECORDING, State.PAUSED -> {
                val d = dot(if (nearLimit) R.color.nx_paused else if (state == State.RECORDING) R.color.nx_rec else R.color.nx_paused)
                box.addView(d)
                if (state == State.RECORDING) {
                    val dur = Appearance.scaled(context, 900L)
                    if (dur > 0) pulse = ObjectAnimator.ofFloat(d, View.ALPHA, 1f, 0.3f).apply {
                        duration = dur
                        repeatCount = ValueAnimator.INFINITE
                        repeatMode = ValueAnimator.REVERSE
                        start()
                    }
                }
                val tv = TextView(context).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(onSurface)
                    maxLines = 1
                    text = if (nearLimit) "⚠ $requestCount" else "$requestCount"
                }
                countView = tv
                box.addView(tv)
            }
        }
        return box
    }

    private class Tile(@DrawableRes val icon: Int, val label: String, val tone: Int, val haptic: Boolean, val action: () -> Unit)

    private fun tiles(): List<Tile> {
        val out = ArrayList<Tile>()
        fun collapse(then: () -> Unit): () -> Unit = { expanded = false; render(); then() }
        when (state) {
            State.RECORDING -> {
                out += Tile(R.drawable.ic_pause, "Pause", R.color.nx_paused, true, collapse { HarRecorder.togglePause(); onHarState(State.PAUSED) })
                out += Tile(R.drawable.ic_stop, "Stop", R.color.nx_error, true, collapse { HarRecorder.stop(); onHarState(State.IDLE) })
                out += Tile(R.drawable.ic_download, "Save HAR", R.color.nx_primary_container, false, collapse { host.saveHar() })
                out += Tile(R.drawable.ic_share, "Share HAR", R.color.nx_tertiary_container, false, collapse { host.shareHar() })
            }
            State.PAUSED -> {
                out += Tile(R.drawable.ic_play, "Resume", R.color.nx_ok, true, collapse { HarRecorder.togglePause(); onHarState(State.RECORDING) })
                out += Tile(R.drawable.ic_stop, "Stop", R.color.nx_error, true, collapse { HarRecorder.stop(); onHarState(State.IDLE) })
                out += Tile(R.drawable.ic_download, "Save HAR", R.color.nx_primary_container, false, collapse { host.saveHar() })
                out += Tile(R.drawable.ic_share, "Share HAR", R.color.nx_tertiary_container, false, collapse { host.shareHar() })
            }
            State.IDLE -> if (showRecord) {
                out += Tile(R.drawable.ic_play, "Record", R.color.nx_ok, true, collapse { HarRecorder.start(); onHarState(State.RECORDING) })
            }
        }
        if (BrowserPrefs.ITEM_RELOAD in items) out += Tile(R.drawable.ic_refresh, "Hard reload", R.color.nx_secondary_container, false, collapse { host.hardReload() })
        if (state == State.IDLE) {
            if (BrowserPrefs.ITEM_HAR in items) out += Tile(R.drawable.ic_network, "Capture", R.color.nx_primary_container, false, collapse { host.openHarSettings() })
        }
        if (BrowserPrefs.ITEM_COOKIES in items) out += Tile(R.drawable.ic_cookie, "Cookies", R.color.nx_tertiary_container, false, collapse { host.openCookieSettings() })
        if (BrowserPrefs.ITEM_DELETE in items) out += Tile(R.drawable.ic_cookie_off, "Clear site data", R.color.nx_error, true, collapse { host.clearCookiesAndReload() })
        return out
    }

    private fun buildPanel(): View {
        val onSurface = context.nx(R.color.nx_on_surface)
        val variant = context.nx(R.color.nx_on_surface_variant)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = surfaceBg(context.nx(R.color.nx_container_high), context.dp(22).toFloat())
            setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
            minimumWidth = context.dp(236)
        }
        card.addView(TextView(context).apply {
            text = "Network Tools"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(onSurface)
            setPadding(context.dp(4), 0, context.dp(4), 0)
        })
        val status = TextView(context).apply {
            text = statusText()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(if (state == State.RECORDING) context.nx(R.color.nx_rec) else variant)
            setPadding(context.dp(4), context.dp(2), context.dp(4), context.dp(8))
        }
        statusView = status
        card.addView(status)
        val warn = TextView(context).apply {
            text = "Recording is nearly full. Save it soon."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(context.nx(R.color.nx_paused))
            setPadding(context.dp(4), 0, context.dp(4), context.dp(8))
            visibility = if (nearLimit && state != State.IDLE) View.VISIBLE else View.GONE
        }
        warnView = warn
        card.addView(warn)

        val list = tiles()
        var row: LinearLayout? = null
        list.forEachIndexed { i, t ->
            if (i % 2 == 0) {
                row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                card.addView(row)
            }
            row!!.addView(tileView(t))
        }
        if (list.size % 2 == 1) {
            // Keep the last tile the same width as the others.
            row?.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        }
        if (list.isEmpty()) {
            card.addView(TextView(context).apply {
                text = "Turn on actions in Settings, Floating Controls."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(variant)
            })
        }
        return card
    }

    private fun tileView(t: Tile): View {
        val fill = context.nx(t.tone)
        val on = if (ColorUtils.calculateLuminance(fill) > 0.5) 0xFF1B1B21.toInt() else 0xFFFFFFFF.toInt()
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(48)
            setPadding(context.dp(12), 0, context.dp(10), 0)
            background = surfaceBg(ColorUtils.setAlphaComponent(fill, if (t.tone == R.color.nx_ok || t.tone == R.color.nx_error || t.tone == R.color.nx_paused) 255 else 200), context.dp(14).toFloat())
            isClickable = true
            isFocusable = true
            contentDescription = t.label
            addView(icon(t.icon, on, 18))
            addView(TextView(context).apply {
                text = t.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(on)
                maxLines = 1
                setPadding(context.dp(8), 0, 0, 0)
            })
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(context.dp(3), context.dp(3), context.dp(3), context.dp(3))
            }
            setOnClickListener {
                if (t.haptic) tap(this)
                t.action()
            }
        }
    }

    private fun setBubble(view: View, draggable: Boolean) {
        view.elevation = context.dp(if (draggable) 6 else 12).toFloat()
        view.translationX = tx
        view.translationY = ty
        view.alpha = if (draggable) baseAlpha() else 1f
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
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> clampBubble() }
        if (draggable) attachDrag(view)
        view.visibility = if (suppressed) View.GONE else View.VISIBLE
        bubble = view
        if (draggable) scheduleFade()
    }

    private fun scheduleFade() {
        handler.removeCallbacks(fade)
        if (autoHide && !expanded) handler.postDelayed(fade, 3000)
    }

    private fun wake() {
        handler.removeCallbacks(fade)
        bubble?.animate()?.alpha(if (expanded) 1f else baseAlpha())?.setDuration(Appearance.scaled(context, 120L))?.start()
    }

    // --- position ---------------------------------------------------------------------------------------------

    private fun edgeMargin() = context.dp(6)

    /** Keeps the control fully on screen (inside the system-bar insets the parent already applies). */
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
        // An open panel is taller than the bubble: it may be pushed up to fit without moving the remembered spot.
        if (!expanded) {
            tx = b.translationX
            ty = b.translationY
        }
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
            addUpdateListener { b.translationX = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    snapping = false
                    clampBubble()
                    BrowserPrefs.setFloatingPosition(context, onLeft, ty)
                    scheduleFade()
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
                    wake()
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
                        if (haptics) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        // Cancel the press so lifting the finger after a drag never counts as a tap.
                        val cancel = MotionEvent.obtain(ev)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        view.onTouchEvent(cancel)
                        cancel.recycle()
                    }
                    if (dragging) {
                        view.translationX = startTx + (ev.rawX - downX)
                        view.translationY = startTy + (ev.rawY - downY)
                        clampBubble()
                    }
                    dragging
                }
                else -> {
                    val was = dragging
                    if (was) {
                        dragging = false
                        isDragging = false
                        snapToEdge()
                    } else {
                        scheduleFade()
                    }
                    was
                }
            }
        }
    }

    private fun tap(v: View) {
        if (haptics) v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }
}
