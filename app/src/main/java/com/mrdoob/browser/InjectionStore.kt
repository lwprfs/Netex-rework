package com.mrdoob.browser

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * User scripts (JavaScript) and user styles (CSS) that run automatically on matching sites.
 * They are applied when a page finishes loading, in list order. WebView offers no reliable
 * "before the page runs" hook for these, so a script cannot change what the page does at start-up.
 */
object InjectionStore {

    enum class Type { JS, CSS }

    data class Item(
        val id: String,
        val name: String,
        val type: Type,
        /** Comma / space separated hosts (subdomains included). Empty or * = every site. */
        val domains: String,
        val code: String,
        val enabled: Boolean
    )

    private const val PREFS = "netex_tools"
    private const val KEY = "injections"

    @Volatile private var appContext: Context? = null
    @Volatile private var cache: List<Item>? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun newId(): String = UUID.randomUUID().toString()

    fun items(): List<Item> = cache ?: load()

    private fun load(): List<Item> {
        val raw = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString(KEY, null)
        val list = ArrayList<Item>()
        if (!raw.isNullOrEmpty()) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val type = try { Type.valueOf(o.optString("t")) } catch (_: Exception) { continue }
                    list += Item(
                        o.optString("i").ifEmpty { newId() }, o.optString("n"), type,
                        o.optString("d"), o.optString("c"), o.optBoolean("e", true)
                    )
                }
            } catch (_: Exception) { /* corrupt: start empty */ }
        }
        cache = list
        return list
    }

    fun save(items: List<Item>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("i", it.id).put("n", it.name).put("t", it.type.name)
                .put("d", it.domains).put("c", it.code).put("e", it.enabled))
        }
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString(KEY, arr.toString())?.apply()
        cache = items.toList()
    }

    fun matches(item: Item, url: String?): Boolean {
        val host = try { Uri.parse(url ?: return false).host?.lowercase() } catch (_: Exception) { null } ?: return false
        val rules = item.domains.split(',', ' ', '\n', ';').map { it.trim().lowercase().removePrefix("*.") }
            .filter { it.isNotEmpty() }
        if (rules.isEmpty() || rules.contains("*")) return true
        return rules.any { host == it || host.endsWith(".$it") }
    }

    fun matching(url: String?): List<Item> =
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) emptyList()
        else items().filter { it.enabled && matches(it, url) }

    /** JavaScript that applies [item] to the current page. */
    fun scriptFor(item: Item): String = when (item.type) {
        Type.JS -> "(function(){try{\n${item.code}\n}catch(e){console.error('[Netex script '+" +
            "${JSONObject.quote(item.name)}+']',e)}})();"
        Type.CSS -> "(function(){var id='netex-css-'+${JSONObject.quote(item.id)};" +
            "var e=document.getElementById(id);if(!e){e=document.createElement('style');e.id=id;" +
            "(document.head||document.documentElement).appendChild(e);}" +
            "e.textContent=${JSONObject.quote(item.code)};})();"
    }
}
