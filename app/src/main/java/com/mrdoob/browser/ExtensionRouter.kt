package com.mrdoob.browser

import android.webkit.WebView
import org.json.JSONObject

class ExtensionRouter {

    private var pageView: WebView? = null
    private var panelView: WebView? = null
    private var pageReady: Boolean = false

    private val livePorts = LinkedHashMap<String, String>()

    private val portSetupMessage = HashMap<String, String>()
    private val pendingPanelToPage = ArrayDeque<String>()

    fun bindPageView(view: WebView) { pageView = view }
    fun bindPanelView(view: WebView) { panelView = view }

    fun onPageNavigationStarted() {
        pageReady = false

        pendingPanelToPage.clear()
        broadcastCommittedToPanel()
    }

    fun onPageReady(frameId: Int = 0) {
        pageReady = true

        if (frameId != 0) broadcastCommittedToPanel()
        livePorts.forEach { (portId, name) ->
            dispatchToPage(connectEnvelope(portId, name))
            portSetupMessage[portId]?.let { dispatchToPage(it) }
        }
        while (pendingPanelToPage.isNotEmpty()) dispatchToPage(pendingPanelToPage.removeFirst())
    }

    private fun broadcastCommittedToPanel() {
        livePorts.keys.forEach { dispatchToPanel(committedEnvelope(it)) }
    }

    fun onPagePortMessage(envelope: JSONObject) { dispatchToPanel(envelope.toString()) }
    fun onPagePortDisconnect(envelope: JSONObject) { dispatchToPanel(envelope.toString()) }

    fun onPanelConnect(envelope: JSONObject) {
        val portId = envelope.optString("portId").takeIf { it.isNotEmpty() } ?: return
        val name = envelope.optString("name")
        livePorts[portId] = name
        if (pageReady) {
            dispatchToPage(connectEnvelope(portId, name))
            portSetupMessage[portId]?.let { dispatchToPage(it) }
        }
    }

    fun onPanelPortMessage(envelope: JSONObject) {
        val portId = envelope.optString("portId").takeIf { it.isNotEmpty() }
        if (portId != null) portSetupMessage.putIfAbsent(portId, envelope.toString())
        sendToPage(envelope.toString())
    }

    fun onPanelPortDisconnect(envelope: JSONObject) {
        val portId = envelope.optString("portId").takeIf { it.isNotEmpty() } ?: return
        livePorts.remove(portId)
        portSetupMessage.remove(portId)
        sendToPage(envelope.toString())
    }

    private fun sendToPage(envelopeJson: String) {
        if (pageReady) dispatchToPage(envelopeJson) else pendingPanelToPage.addLast(envelopeJson)
    }

    private fun dispatchToPanel(envelopeJson: String) {
        val view = panelView ?: return
        view.post {
            view.evaluateJavascript("window.__netexExt && window.__netexExt.dispatch($envelopeJson)", null)
        }
    }

    private fun dispatchToPage(envelopeJson: String) {
        val view = pageView ?: return
        view.post {

            view.evaluateJavascript("window.__netexExt && window.__netexExt.dispatchAllFrames($envelopeJson)", null)
        }
    }

    private fun connectEnvelope(portId: String, name: String): String =
        JSONObject().apply {
            put("type", EnvelopeType.PORT_CONNECT)
            put("portId", portId)
            put("name", name)
        }.toString()

    private fun committedEnvelope(portId: String): String =
        JSONObject().apply {
            put("type", EnvelopeType.PORT_MESSAGE)
            put("portId", portId)
            put("message", JSONObject().apply {
                put("id", "three-devtools")
                put("name", "committed")
                put("frameId", 0)
            })
        }.toString()
}
