package com.mrdoob.browser

import android.content.Context
import android.net.Uri

/** Search engines used when the address bar text is not a URL. `%s` is replaced by the query. */
object SearchEngines {

    data class Engine(val id: String, val name: String, val template: String)

    private const val PREFS = "netex_tools"
    private const val KEY_ENGINE = "search_engine"
    private const val KEY_CUSTOM = "search_custom"

    const val CUSTOM_ID = "custom"

    /** The first entry keeps Netex's original behaviour (DuckDuckGo "!ducky" jumps to the top result). */
    val BUILT_IN = listOf(
        Engine("ddg_first", "DuckDuckGo — open first result", "https://duckduckgo.com/?q=!ducky%20%s"),
        Engine("ddg", "DuckDuckGo", "https://duckduckgo.com/?q=%s"),
        Engine("google", "Google", "https://www.google.com/search?q=%s"),
        Engine("bing", "Bing", "https://www.bing.com/search?q=%s"),
        Engine("brave", "Brave Search", "https://search.brave.com/search?q=%s")
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun customTemplate(context: Context): String = prefs(context).getString(KEY_CUSTOM, "").orEmpty()

    fun selectedId(context: Context): String = prefs(context).getString(KEY_ENGINE, BUILT_IN[0].id) ?: BUILT_IN[0].id

    fun select(context: Context, id: String) {
        prefs(context).edit().putString(KEY_ENGINE, id).apply()
    }

    /** A custom template must be http(s) and contain %s where the query goes. */
    fun isValidTemplate(template: String): Boolean {
        val t = template.trim()
        return (t.startsWith("https://") || t.startsWith("http://")) && t.contains("%s") && !t.contains(' ')
    }

    fun setCustom(context: Context, template: String) {
        prefs(context).edit().putString(KEY_CUSTOM, template.trim()).putString(KEY_ENGINE, CUSTOM_ID).apply()
    }

    fun current(context: Context): Engine {
        val id = selectedId(context)
        if (id == CUSTOM_ID) {
            val t = customTemplate(context)
            if (isValidTemplate(t)) return Engine(CUSTOM_ID, "Custom", t)
        }
        return BUILT_IN.firstOrNull { it.id == id } ?: BUILT_IN[0]
    }

    fun label(context: Context): String {
        val e = current(context)
        return if (e.id == CUSTOM_ID) "Custom — ${Uri.parse(e.template.replace("%s", "q")).host.orEmpty()}" else e.name
    }

    fun searchUrl(context: Context, query: String): String =
        current(context).template.replace("%s", Uri.encode(query))
}
