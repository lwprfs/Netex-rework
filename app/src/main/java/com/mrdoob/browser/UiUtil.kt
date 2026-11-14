package com.mrdoob.browser

import android.content.Context
import android.graphics.Outline
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.EditText
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat

/** Clips a view (e.g. a WebView) to rounded corners. Unlike a background drawable this survives setBackgroundColor. */
fun View.roundCorners(radiusDp: Int) {
    val radius = radiusDp * resources.displayMetrics.density
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }
    clipToOutline = true
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun Context.colorHex(@ColorRes id: Int): String =
    String.format("#%06X", 0xFFFFFF and nx(id))

/** Calls [onChange] after every edit (small replacement for core-ktx doAfterTextChanged). */
fun EditText.onTextChanged(onChange: (String) -> Unit) {
    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = onChange(s?.toString().orEmpty())
    })
}
