package com.mrdoob.browser

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Message
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

private const val PANEL_URL = "file:///android_asset/threejs-devtools/panel/panel.html"
private const val SHIM_ASSET = "chrome-shim-panel.js"

@SuppressLint("SetJavaScriptEnabled")
fun setupThreeJsPanel(context: Context, view: WebView, bridge: PanelExtensionBridge) {
    view.setBackgroundColor(view.materialColor(com.google.android.material.R.attr.colorSurfaceContainerLowest))
    view.settings.javaScriptEnabled = true
    view.settings.domStorageEnabled = true
    view.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    view.settings.allowFileAccess = true
    view.settings.setSupportMultipleWindows(true)
    view.webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            if (BuildConfig.DEBUG) {
                Log.d("ThreeJsPanelConsole", "${message.messageLevel()} ${message.message()} (${message.sourceId()}:${message.lineNumber()})")
            }
            return true
        }

        override fun onCreateWindow(
            parent: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message
        ): Boolean {
            val transient = WebView(parent.context)
            transient.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    val intent = Intent(Intent.ACTION_VIEW, request.url)
                    try { parent.context.startActivity(intent) } catch (_: ActivityNotFoundException) {}
                    v.destroy()
                    return true
                }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = transient
            resultMsg.sendToTarget()
            return true
        }
    }
    view.webViewClient = WebViewClient()

    val shim = context.assets.open(SHIM_ASSET).bufferedReader().use { it.readText() }
    val themeInjector = buildThreeJsPanelThemeInjector(view)
    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
        WebViewCompat.addDocumentStartJavaScript(view, "$shim\n;$themeInjector", setOf("*"))
    }
    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
        WebViewCompat.addWebMessageListener(view, PanelExtensionBridge.JS_OBJECT_NAME, setOf("*"), bridge)
    } else {
        @SuppressLint("AddJavascriptInterface")
        view.addJavascriptInterface(bridge.Fallback(), PanelExtensionBridge.JS_OBJECT_NAME)
    }

    view.loadUrl(PANEL_URL)
}

private fun buildThreeJsPanelThemeInjector(view: WebView): String {
    val bg = view.materialColorHex(com.google.android.material.R.attr.colorSurfaceContainerLowest)
    val fg = view.materialColorHex(com.google.android.material.R.attr.colorOnSurface)
    val css = "body{background:$bg!important;color:$fg!important}"
    return "(function(){var s=document.createElement('style');s.textContent=${JSONObject.quote(css)};document.documentElement.appendChild(s);})();"
}
