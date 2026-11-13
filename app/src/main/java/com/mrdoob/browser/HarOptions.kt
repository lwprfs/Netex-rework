package com.mrdoob.browser

import android.content.Context

/** User-selectable extra detail captured into HAR recordings. All on by default. */
object HarOptions {

    private const val PREFS = "netex_tools"
    @Volatile private var prefs: android.content.SharedPreferences? = null

    private fun p(): android.content.SharedPreferences? = prefs

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun getB(key: String) = p()?.getBoolean(key, true) ?: true
    private fun setB(key: String, v: Boolean) { p()?.edit()?.putBoolean(key, v)?.apply() }

    /** Untruncated bodies of text responses (JS, CSS, HTML, JSON). */
    var fullBodies: Boolean
        get() = getB("har_bodies"); set(v) = setB("har_bodies", v)

    /** Scripts, stylesheets, fonts and beacons (besides fetch/XHR/images/documents). */
    var extraResources: Boolean
        get() = getB("har_resources"); set(v) = setB("har_resources", v)

    /** Real browser request headers and the Cookie header. */
    var requestHeaders: Boolean
        get() = getB("har_headers"); set(v) = setB("har_headers", v)

    /** Images embedded as base64. */
    var images: Boolean
        get() = getB("har_images"); set(v) = setB("har_images", v)

    /** Used by the page-side shim: option [name] and a recording is running. */
    fun enabled(name: String): Boolean = HarRecorder.isRecording && when (name) {
        "bodies" -> fullBodies
        "resources" -> extraResources
        "headers" -> requestHeaders
        "images" -> images
        else -> false
    }
}
