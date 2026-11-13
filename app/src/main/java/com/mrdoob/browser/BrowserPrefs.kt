package com.mrdoob.browser

import android.content.Context

/** Small persisted browser settings that are not part of HAR capture. */
object BrowserPrefs {

    private const val PREFS = "netex_tools"
    private const val KEY_TEXT_ZOOM = "text_zoom"
    private const val KEY_FLOATING = "floating_controls"

    val TEXT_ZOOM_STEPS = intArrayOf(85, 100, 125, 150)

    fun textZoom(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_TEXT_ZOOM, 100)

    fun setTextZoom(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_TEXT_ZOOM, percent).apply()
    }

    /** Whether the floating record / reload / cookie pop-up is shown (off by default). */
    fun floatingControls(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FLOATING, false)

    fun setFloatingControls(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_FLOATING, on).apply()
    }
}
