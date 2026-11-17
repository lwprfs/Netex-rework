package com.mrdoob.browser

import android.webkit.JavascriptInterface

class HarFlag {
    companion object { const val JS_OBJECT_NAME = "AndroidHar" }

    @JavascriptInterface
    fun on(): Boolean = HarRecorder.isRecording

    @JavascriptInterface
    fun opt(name: String): Boolean = HarOptions.enabled(name)

    @JavascriptInterface
    fun limit(name: String): Double = HarOptions.limitBytes(name)
}
