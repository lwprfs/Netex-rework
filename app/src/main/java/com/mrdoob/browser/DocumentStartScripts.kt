package com.mrdoob.browser

import android.content.Context

/** Scripts injected at document start in every frame, joined into one bundle. */
object DocumentStartScripts {

    private val ASSETS = listOf(
        "network-shim.js",
        "devtools-probe.js",
        "nav-guard.js",
        "orientation-shim.js",
        "console-shim.js",
    )

    @Volatile
    private var cached: String? = null

    fun load(context: Context): String {
        cached?.let { return it }
        val joined = ASSETS.joinToString(separator = ";\n") { path ->
            context.assets.open(path).bufferedReader().use { it.readText() }
        }
        cached = joined
        return joined
    }
}
