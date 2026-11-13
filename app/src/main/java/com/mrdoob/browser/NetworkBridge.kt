package com.mrdoob.browser

import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "NetworkBridge"

class NetworkBridge(
    private val onBlobResponse: (String, String) -> Unit
) : JsonMessageBridge(TAG) {

    companion object {
        const val JS_OBJECT_NAME = "AndroidNetwork"
    }

    override fun onMessage(obj: JSONObject) {
        when (obj.optString("type")) {
            "blob-response" -> onBlobResponse(obj.optString("requestId"), obj.optString("body"))
            "" -> addRecord(obj)
        }
    }

    private fun addRecord(obj: JSONObject) {
        NetworkLog.add(
            NetworkLog.Record(
                method = obj.optString("method"),
                url = obj.optString("url"),
                status = obj.optInt("status"),
                body = obj.optString("body"),
                contentType = obj.optString("contentType").takeIf { it.isNotEmpty() },
                durationMs = obj.optLong("duration"),
                sizeBytes = obj.optLong("size"),
                requestId = obj.optString("requestId").takeIf { it.isNotEmpty() },
                pending = obj.optBoolean("pending", false),
                startedAt = obj.optLong("started"),
                requestHeaders = RequestHeaders.merge(
                    obj.optString("method").ifEmpty { "GET" },
                    obj.optString("url"),
                    pairs(obj.optJSONArray("reqHeaders"))
                ),
                responseHeaders = pairs(obj.optJSONArray("resHeaders")),
                requestBody = obj.optString("reqBody"),
                harBody = obj.optString("harBody"),
                harBase64 = obj.optBoolean("harB64", false),
                encodedSize = obj.optLong("encoded", -1),
                decodedSize = obj.optLong("decoded", -1),
                transferSize = obj.optLong("transfer", -1),
                protocol = obj.optString("protocol"),
                timings = obj.optJSONArray("timings")?.let { a -> LongArray(a.length()) { a.optLong(it, -1) } }
            )
        )
    }

    private fun pairs(arr: JSONArray?): List<Pair<String, String>> {
        if (arr == null) return emptyList()
        val out = ArrayList<Pair<String, String>>(arr.length())
        for (i in 0 until arr.length()) {
            val p = arr.optJSONArray(i) ?: continue
            out += p.optString(0) to p.optString(1)
        }
        return out
    }
}
