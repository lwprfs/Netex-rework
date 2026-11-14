package com.mrdoob.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

class PanelRenderer(
    private val activity: MainActivity,
    private val panelView: WebView,
    private val mainView: WebView
) {
    enum class Tab { CONSOLE, SOURCE, NETWORK, THREEJS }

    private var shellLoaded = false
    private val pending = ArrayDeque<() -> Unit>()

    @SuppressLint("SetJavaScriptEnabled")
    fun init() {
        panelView.settings.javaScriptEnabled = true
        panelView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        panelView.setBackgroundColor(panelView.materialColor(com.google.android.material.R.attr.colorSurfaceContainerLowest))
        panelView.webChromeClient = WebChromeClient()
        panelView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                if (shellLoaded) return
                shellLoaded = true
                applyTheme()
                pushPrefs()
                while (pending.isNotEmpty()) pending.removeFirst().invoke()
            }
        }
        val html = buildShellHtml()
        panelView.loadDataWithBaseURL(
            "https://cdn.jsdelivr.net/", html, "text/html", "UTF-8", null
        )
    }

    fun setTab(tab: Tab) {

        val name = when (tab) {
            Tab.CONSOLE -> "console"
            Tab.SOURCE -> "source"
            Tab.NETWORK -> "network"
            Tab.THREEJS -> return
        }
        afterShellLoaded {
            evalPanel("window.__panel.setActiveTab(${q(name)})")
        }
    }

    fun refreshSource() {
        mainView.evaluateJavascript("document.documentElement.outerHTML") { json ->
            val raw = try {
                JSONTokener(json).nextValue() as? String
            } catch (_: Exception) { null } ?: return@evaluateJavascript
            afterShellLoaded {
                evalPanel("window.__panel.setSource(${q(raw)})")
            }
        }
    }

    fun appendNetworkRow(record: NetworkLog.Record, displayUrl: String) {
        val payload = JSONObject().apply {
            put("method", record.method)
            put("url", record.url)
            put("displayUrl", displayUrl)
            put("status", record.status)
            put("body", record.body)
            put("contentType", record.contentType ?: "")
            put("durationMs", record.durationMs)
            put("size", record.sizeBytes)
            put("requestId", record.requestId ?: "")
            put("pending", record.pending)
            // For "Copy as cURL" and "Replay" in the Network tab.
            put("reqHeaders", org.json.JSONArray().also { arr ->
                record.requestHeaders.forEach { (k, v) -> arr.put(org.json.JSONArray().put(k).put(v)) }
            })
            put("reqBody", record.requestBody.take(20000))
            put("resHeaders", org.json.JSONArray().also { arr ->
                record.responseHeaders.forEach { (k, v) -> arr.put(org.json.JSONArray().put(k).put(v)) }
            })
            put("startedAt", record.startedAt)
            put("kind", record.kind)
            // [blocked, dns, connect, ssl, send, wait, receive]; -1 = the page could not measure it.
            record.timings?.let { t -> put("timings", org.json.JSONArray().also { a -> t.forEach { a.put(it) } }) }
        }.toString()
        afterShellLoaded {
            evalPanel("window.__panel.appendNetworkRow($payload)")
        }
    }

    fun clearNetwork() {
        afterShellLoaded {
            evalPanel("window.__panel.clearNetwork()")
        }
    }

    fun setHarState(state: HarRecorder.State) {
        afterShellLoaded {
            evalPanel("window.__panel.setHarState(${q(state.name.lowercase())})")
        }
    }

    fun deliverBlob(rid: String, dataUrl: String) {
        afterShellLoaded {
            evalPanel("window.__panel.deliverBlob(${q(rid)}, ${q(dataUrl)})")
        }
    }

    fun appendConsoleEntry(entry: ConsoleLog.Entry) {
        val payload = JSONObject().apply {
            put("level", entry.level.name.lowercase())
            put("segments", JSONArray(entry.segmentsJson))
            put("sourceId", entry.sourceId ?: "")
            put("line", entry.lineNumber)
        }.toString()
        afterShellLoaded {
            evalPanel("window.__panel.appendConsoleEntry($payload)")
        }
    }

    /** Sends the saved developer-panel options (timestamps, preserve logs, command history) to the panel. */
    fun pushPrefs() {
        val o = JSONObject().apply {
            put("timestamps", BrowserPrefs.devFlag(activity, "timestamps"))
            put("keepConsole", BrowserPrefs.devFlag(activity, "keep_console"))
            put("keepNetwork", BrowserPrefs.devFlag(activity, "keep_network"))
            put("history", try { JSONArray(BrowserPrefs.getDev(activity, "cmd_history") ?: "[]") } catch (_: Exception) { JSONArray() })
        }.toString()
        afterShellLoaded { evalPanel("window.__panel.setPrefs($o)") }
    }

    /** Preserve-logs mode: keep the old rows, but mark where the page navigated. */
    fun markNavigation(kind: String, url: String?) {
        afterShellLoaded { evalPanel("window.__panel.markNavigation(${q(kind)}, ${q(url ?: "")})") }
    }

    fun deliverReplay(id: String, resultJson: String) {
        afterShellLoaded { evalPanel("window.__panel.deliverReplay(${q(id)}, ${q(resultJson)})") }
    }

    fun clearConsole() {
        afterShellLoaded {
            evalPanel("window.__panel.clearConsole()")
        }
    }

    fun deliverEvalResult(evalId: String, resultJson: String) {
        afterShellLoaded {
            evalPanel("window.__panel.deliverEvalResult(${q(evalId)}, ${q(resultJson)})")
        }
    }

    fun applyTheme() {
        val dark = isNightMode()
        fun hex(id: Int) = activity.colorHex(id)
        val tokens = JSONObject().apply {
            put("dark", dark)
            put("bg", hex(R.color.nx_container_lowest))
            put("surface", hex(R.color.nx_container_high))
            put("surfaceLow", hex(R.color.nx_container_low))
            put("fg", hex(R.color.nx_on_surface))
            put("fgDim", hex(R.color.nx_on_surface_variant))
            put("outline", hex(R.color.nx_outline_variant))
            put("primary", hex(R.color.nx_primary))
            put("primaryContainer", hex(R.color.nx_primary_container))
            put("onPrimaryContainer", hex(R.color.nx_on_primary_container))
            put("error", hex(R.color.nx_error))
            put("ok", hex(R.color.nx_ok))
            put("warn", hex(R.color.nx_paused))
            put("rec", hex(R.color.nx_rec))
        }.toString()
        panelView.setBackgroundColor(panelView.materialColor(com.google.android.material.R.attr.colorSurfaceContainerLowest))
        afterShellLoaded { evalPanel("window.__panel.setTheme($tokens)") }
    }

    private fun afterShellLoaded(action: () -> Unit) {
        if (shellLoaded) action() else pending.addLast(action)
    }

    private fun evalPanel(js: String) {
        panelView.evaluateJavascript(js, null)
    }

    private fun q(s: String): String = JSONObject.quote(s)

    private fun isNightMode(): Boolean = (activity.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun buildShellHtml(): String {
        val context: Context = activity
        val shell = context.assets.open("panel-shell.html").bufferedReader().use { it.readText() }
        val js = context.assets.open("panel.js").bufferedReader().use { it.readText() }
        return shell.replace("<!--__PANEL_JS__-->", "<script>$js</script>")
    }
}
