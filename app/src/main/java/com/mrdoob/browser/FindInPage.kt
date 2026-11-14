package com.mrdoob.browser

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Find-in-page bar floating at the top of the browser. Uses WebView's built-in find, which
 * highlights every match, scrolls to the active one and reports the match count. (WebView has no
 * case-sensitive option: matching is always case-insensitive.)
 */
class FindInPage(private val parent: FrameLayout, private val webView: WebView) {

    private val context: Context = parent.context
    private var bar: LinearLayout? = null
    private lateinit var input: EditText
    private lateinit var count: TextView

    val isShown: Boolean get() = bar?.visibility == View.VISIBLE

    fun show() {
        val b = bar ?: build().also { bar = it }
        b.visibility = View.VISIBLE
        input.requestFocus()
        input.selectAll()
        input.post {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
        if (input.text.isNotEmpty()) webView.findAllAsync(input.text.toString())
    }

    fun hide() {
        val b = bar ?: return
        if (b.visibility != View.VISIBLE) return
        webView.clearMatches()
        b.visibility = View.GONE
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
        webView.requestFocus()
    }

    private fun build(): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_float_bar)
            elevation = context.dp(12).toFloat()
            setPadding(context.dp(16), context.dp(4), context.dp(4), context.dp(4))
        }
        input = EditText(context).apply {
            hint = "Find in page"
            background = null
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            textSize = 15f
            setOnEditorActionListener { _, _, _ -> webView.findNext(true); true }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val q = s?.toString().orEmpty()
                    if (q.isEmpty()) {
                        webView.clearMatches()
                        count.text = ""
                    } else {
                        webView.findAllAsync(q)
                    }
                }
            })
        }
        count = TextView(context).apply {
            textSize = 13f
            setTextColor(context.nx(R.color.nx_on_surface_variant))
            setPadding(context.dp(8), 0, context.dp(8), 0)
        }
        webView.setFindListener { active, total, _ ->
            count.text = when {
                input.text.isEmpty() -> ""
                total == 0 -> "No matches"
                else -> "${active + 1}/$total"
            }
        }
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroupLP.WRAP, 1f))
        row.addView(count)
        row.addView(iconButton(R.drawable.ic_expand_more, "Previous match", 180f) { webView.findNext(false) })
        row.addView(iconButton(R.drawable.ic_expand_more, "Next match", 0f) { webView.findNext(true) })
        row.addView(iconButton(R.drawable.ic_close, "Close find", 0f) { hide() })

        parent.addView(
            row,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            ).apply {
                topMargin = context.dp(8)
                leftMargin = context.dp(12)
                rightMargin = context.dp(12)
            }
        )
        return row
    }

    private fun iconButton(icon: Int, description: String, rotation: Float, onClick: () -> Unit) =
        ImageButton(context).apply {
            setImageResource(icon)
            contentDescription = description
            this.rotation = rotation
            background = ContextCompat.getDrawable(context, R.drawable.ripple_round)
            setPadding(context.dp(10), context.dp(10), context.dp(10), context.dp(10))
            layoutParams = LinearLayout.LayoutParams(context.dp(44), context.dp(44))
            setOnClickListener { onClick() }
        }

    private object ViewGroupLP {
        const val WRAP = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
