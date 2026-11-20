package com.mrdoob.browser

import org.json.JSONObject

private const val TAG = "ExtensionBridge"

/** Receives console entries and orientation-lock requests from the page-side shims. */
class ExtensionBridge(
    private val onOrientationLock: (String?) -> Unit,
    private val onConsoleEntry: (level: String, segmentsJson: String) -> Unit,
) : JsonMessageBridge(TAG) {

    companion object {
        const val JS_OBJECT_NAME = "AndroidExtension"
    }

    override fun onMessage(obj: JSONObject) {
        when (obj.optString("type")) {
            EnvelopeType.ORIENTATION_LOCK -> onOrientationLock(obj.optString("orientation"))
            EnvelopeType.ORIENTATION_UNLOCK -> onOrientationLock(null)
            EnvelopeType.CONSOLE_ENTRY -> onConsoleEntry(
                obj.optString("level"),
                obj.optJSONArray("segments")?.toString() ?: "[]"
            )
        }
    }
}
