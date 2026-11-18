package com.mrdoob.browser

import android.content.Context

object BrowserPrefs {

    private const val PREFS = "netex_tools"
    private const val KEY_TEXT_ZOOM = "text_zoom"
    private const val KEY_FLOATING = "floating_controls"
    private const val KEY_FLOAT_LEFT = "floating_side_left"
    private const val KEY_FLOAT_Y = "floating_y"
    private const val KEY_FLOAT_ITEMS = "floating_items"
    private const val KEY_HAPTICS = "floating_haptics"
    private const val KEY_HOME = "homepage"
    private const val KEY_STARTUP = "startup_mode"
    private const val KEY_JS = "javascript_enabled"
    private const val KEY_3P = "third_party_cookies"
    private const val KEY_UA = "custom_user_agent"
    private const val KEY_NAV_CONFIRM = "navigation_confirmation"

    const val STARTUP_RESTORE = "restore"
    const val STARTUP_HOME = "home"
    const val STARTUP_BLANK = "blank"

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

    fun floatingControls(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FLOATING, false)

    fun setFloatingControls(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_FLOATING, on).apply()
    }

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

    fun floatingOnLeft(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FLOAT_LEFT, false)

    fun floatingY(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_FLOAT_Y, 0f)

    fun setFloatingPosition(context: Context, left: Boolean, y: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_FLOAT_LEFT, left).putFloat(KEY_FLOAT_Y, y).apply()
    }

    private fun p(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun homepage(context: Context): String = p(context).getString(KEY_HOME, "").orEmpty()
    fun setHomepage(context: Context, url: String) { p(context).edit().putString(KEY_HOME, url.trim()).apply() }

    fun startupMode(context: Context): String = p(context).getString(KEY_STARTUP, STARTUP_RESTORE) ?: STARTUP_RESTORE
    fun setStartupMode(context: Context, mode: String) { p(context).edit().putString(KEY_STARTUP, mode).apply() }

    fun javaScriptEnabled(context: Context): Boolean = p(context).getBoolean(KEY_JS, true)
    fun setJavaScriptEnabled(context: Context, on: Boolean) { p(context).edit().putBoolean(KEY_JS, on).apply() }

    fun thirdPartyCookies(context: Context): Boolean = p(context).getBoolean(KEY_3P, false)
    fun setThirdPartyCookies(context: Context, on: Boolean) { p(context).edit().putBoolean(KEY_3P, on).apply() }

    fun navigationConfirmation(context: Context): Boolean = p(context).getBoolean(KEY_NAV_CONFIRM, true)

    @Volatile private var navConfirmCache: Boolean? = null

    /** Context-free read for WebView threads; refreshed whenever the switch changes or [navigationConfirmation] is read. */
    fun isNavigationConfirmationOn(): Boolean = navConfirmCache ?: true
    fun setNavigationConfirmation(context: Context, on: Boolean) {
        navConfirmCache = on
        p(context).edit().putBoolean(KEY_NAV_CONFIRM, on).apply()
    }

    fun primeNavigationConfirmation(context: Context) { navConfirmCache = navigationConfirmation(context) }

    fun customUserAgent(context: Context): String = p(context).getString(KEY_UA, "").orEmpty()
    fun setCustomUserAgent(context: Context, ua: String) { p(context).edit().putString(KEY_UA, ua.trim()).apply() }

    fun getDev(context: Context, key: String): String? = p(context).getString("dev_$key", null)
    fun setDev(context: Context, key: String, value: String) { p(context).edit().putString("dev_$key", value).apply() }
    fun devFlag(context: Context, key: String): Boolean = getDev(context, key) == "true"
}
