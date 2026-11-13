package com.mrdoob.browser

import org.json.JSONObject

private const val TAG = "PanelBridge"

class PanelBridge(
    private val onFetchBlob: (String) -> Unit,
    private val onEval: (String, String) -> Unit,
    private val onHarMenu: () -> Unit,
    private val onCopy: (String) -> Unit
) : JsonMessageBridge(TAG) {

    companion object {
        const val JS_OBJECT_NAME = "AndroidPanel"
    }

    override fun onMessage(obj: JSONObject) {
        when (obj.optString("type")) {
            "fetch-blob" -> onFetchBlob(obj.optString("requestId"))
            "eval" -> onEval(obj.optString("evalId"), obj.optString("source"))
            "har-menu" -> onHarMenu()
            "copy" -> onCopy(obj.optString("text"))
        }
    }
}
