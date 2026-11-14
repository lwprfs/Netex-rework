package com.mrdoob.browser

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors

/**
 * Look-and-feel settings: theme mode (system / light / dark), Material You dynamic colours,
 * accent colour, address-bar size and animation intensity.
 */
object Appearance {

    data class Accent(val id: String, val name: String, val overlay: Int?)

    /** Indigo is the built-in palette; the others are theme overlays that replace the primary colours. */
    val ACCENTS = listOf(
        Accent("indigo", "Indigo (default)", null),
        Accent("teal", "Teal", R.style.ThemeOverlay_Netex_Accent_Teal),
        Accent("blue", "Blue", R.style.ThemeOverlay_Netex_Accent_Blue),
        Accent("green", "Green", R.style.ThemeOverlay_Netex_Accent_Green),
        Accent("amber", "Amber", R.style.ThemeOverlay_Netex_Accent_Amber),
        Accent("rose", "Rose", R.style.ThemeOverlay_Netex_Accent_Rose)
    )

    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    const val ANIM_NORMAL = "normal"
    const val ANIM_REDUCED = "reduced"
    const val ANIM_OFF = "off"

    private const val PREFS = "netex_appearance"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_DYNAMIC = "dynamic_colors"
    private const val KEY_ACCENT = "accent"
    private const val KEY_COMPACT = "address_bar_compact"
    private const val KEY_ANIM = "animations"
    private const val KEY_REV = "revision"
    private const val KEY_REMEMBER_TAB = "remember_tab"

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // --- theme mode -------------------------------------------------------------------------

    fun themeMode(c: Context): String = p(c).getString(KEY_THEME, THEME_SYSTEM) ?: THEME_SYSTEM

    fun setThemeMode(c: Context, mode: String) {
        p(c).edit().putString(KEY_THEME, mode).apply()
        applyNightMode(c)
        bump(c)
    }

    fun applyNightMode(c: Context) {
        AppCompatDelegate.setDefaultNightMode(
            when (themeMode(c)) {
                THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    // --- colours ---------------------------------------------------------------------------

    /** Material You wallpaper colours need Android 12 or newer. */
    fun dynamicAvailable(): Boolean = DynamicColors.isDynamicColorAvailable()

    fun dynamicColors(c: Context): Boolean = p(c).getBoolean(KEY_DYNAMIC, false) && dynamicAvailable()

    fun setDynamicColors(c: Context, on: Boolean) {
        p(c).edit().putBoolean(KEY_DYNAMIC, on).apply()
        bump(c)
    }

    fun accentId(c: Context): String = p(c).getString(KEY_ACCENT, "indigo") ?: "indigo"

    fun accent(c: Context): Accent = ACCENTS.firstOrNull { it.id == accentId(c) } ?: ACCENTS[0]

    fun setAccent(c: Context, id: String) {
        p(c).edit().putString(KEY_ACCENT, id).apply()
        bump(c)
    }

    /** Call at the start of every activity's onCreate, before inflating views. */
    fun apply(activity: Activity) {
        if (dynamicColors(activity)) {
            DynamicColors.applyToActivityIfAvailable(activity)
        } else {
            accent(activity).overlay?.let { activity.theme.applyStyle(it, true) }
        }
    }

    // --- layout / motion ----------------------------------------------------------------------

    fun addressBarCompact(c: Context): Boolean = p(c).getBoolean(KEY_COMPACT, false)
    fun setAddressBarCompact(c: Context, on: Boolean) { p(c).edit().putBoolean(KEY_COMPACT, on).apply() }

    fun animations(c: Context): String = p(c).getString(KEY_ANIM, ANIM_NORMAL) ?: ANIM_NORMAL
    fun setAnimations(c: Context, v: String) { p(c).edit().putString(KEY_ANIM, v).apply() }

    /** Multiplier for the app's own animation durations (0 = no animation). */
    fun animScale(c: Context): Float = when (animations(c)) {
        ANIM_OFF -> 0f
        ANIM_REDUCED -> 0.5f
        else -> 1f
    }

    fun scaled(c: Context, ms: Long): Long = (ms * animScale(c)).toLong()

    /** Remember the last developer-tools tab between launches. */
    fun rememberTab(c: Context): Boolean = p(c).getBoolean(KEY_REMEMBER_TAB, true)
    fun setRememberTab(c: Context, on: Boolean) { p(c).edit().putBoolean(KEY_REMEMBER_TAB, on).apply() }

    // --- change tracking ---------------------------------------------------------------------

    /** Bumped whenever a colour-affecting setting changes, so open screens know to recreate. */
    fun revision(c: Context): Int = p(c).getInt(KEY_REV, 0)

    private fun bump(c: Context) {
        p(c).edit().putInt(KEY_REV, revision(c) + 1).apply()
    }
}
