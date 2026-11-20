package com.mrdoob.browser

/** Message types the page-side shims send over the AndroidExtension bridge. */
object EnvelopeType {
    const val ORIENTATION_LOCK = "orientation.lock"
    const val ORIENTATION_UNLOCK = "orientation.unlock"
    const val CONSOLE_ENTRY = "console.entry"
}
