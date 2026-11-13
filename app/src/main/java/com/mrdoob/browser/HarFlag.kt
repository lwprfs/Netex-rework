package com.mrdoob.browser

import android.webkit.JavascriptInterface

/** Synchronous "is HAR recording on?" probe for the page-side network shim. */
class HarFlag {
    companion object { const val JS_OBJECT_NAME = "AndroidHar" }

    @JavascriptInterface
    fun on(): Boolean = HarRecorder.isRecording

    @JavascriptInterface
    fun opt(name: String): Boolean = HarOptions.enabled(name)
}
