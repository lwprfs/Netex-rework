package com.mrdoob.browser

import android.content.Context

/** Small persisted browser settings that are not part of HAR capture. */
object BrowserPrefs {

    private const val PREFS = "netex_tools"
    private const val KEY_TEXT_ZOOM = "text_zoom"
    private const val KEY_FLOATING = "floating_controls"
    private const val KEY_FLOAT_LEFT = "floating_side_left"
    private const val KEY_FLOAT_Y = "floating_y"
    private const val KEY_FLOAT_ITEMS = "floating_items"
    private const val KEY_HAPTICS = "floating_haptics"

    /** Optional buttons of the floating ring (Play / Resume / Save / Share are always there). */
    const val ITEM_RELOAD = "reload"
    const val ITEM_HAR = "har"
    const val ITEM_COOKIES = "cookies"
    const val ITEM_DELETE = "delete"
    val ALL_ITEMS = listOf(ITEM_RELOAD, ITEM_HAR, ITEM_COOKIES, ITEM_DELETE)

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

    /** Which optional ring buttons are shown (all by default). */
    fun floatingItems(context: Context): Set<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FLOAT_ITEMS, null)
            ?: return ALL_ITEMS.toSet()
        return raw.split(',').filter { it in ALL_ITEMS }.toSet()
    }

    fun setFloatingItems(context: Context, items: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_FLOAT_ITEMS, ALL_ITEMS.filter { it in items }.joinToString(",")).apply()
    }

    fun floatingHaptics(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HAPTICS, true)

    fun setFloatingHaptics(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_HAPTICS, on).apply()
    }

    /** Where the bubble was left: which screen edge, and its vertical offset from the top corner. */
    fun floatingOnLeft(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FLOAT_LEFT, false)

    fun floatingY(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_FLOAT_Y, 0f)

    fun setFloatingPosition(context: Context, left: Boolean, y: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_FLOAT_LEFT, left).putFloat(KEY_FLOAT_Y, y).apply()
    }
}
