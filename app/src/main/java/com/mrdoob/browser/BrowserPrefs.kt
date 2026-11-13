package com.mrdoob.browser

import android.content.Context

/** Small persisted browser settings that are not part of HAR capture. */
object BrowserPrefs {

    private const val PREFS = "netex_tools"
    private const val KEY_TEXT_ZOOM = "text_zoom"

    val TEXT_ZOOM_STEPS = intArrayOf(85, 100, 125, 150)

    fun textZoom(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_TEXT_ZOOM, 100)

    fun setTextZoom(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_TEXT_ZOOM, percent).apply()
    }
}
