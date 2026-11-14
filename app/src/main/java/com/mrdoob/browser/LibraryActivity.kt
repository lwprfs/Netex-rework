package com.mrdoob.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** Bookmarks and browsing history: search, open, edit, delete (with undo), clear, import / export. */
class LibraryActivity : AppCompatActivity() {

    private sealed class Row {
        data class Header(val text: String) : Row()
        data class History(val visit: BrowserLibrary.Visit) : Row()
        data class Mark(val bookmark: BrowserLibrary.Bookmark) : Row()
    }

    private lateinit var root: LinearLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var tabs: TabLayout
    private lateinit var search: EditText
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private var rows: List<Row> = emptyList()
    private var showingHistory = false

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1
        override fun isEnabled(position: Int) = rows[position] !is Row.Header

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = rows[position]
            if (row is Row.Header) {
                val tv = (convertView as? TextView) ?: TextView(this@LibraryActivity).apply {
                    textSize = 12.5f
                    setTextColor(context.nx(R.color.nx_primary))
                    setPadding(dp(20), dp(16), dp(20), dp(6))
                }
                tv.text = row.text
                return tv
            }
            val item = (convertView as? LinearLayout) ?: LinearLayout(this@LibraryActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(10), dp(20), dp(10))
                addView(TextView(context).apply { textSize = 15f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                addView(TextView(context).apply {
                    textSize = 12.5f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(context.nx(R.color.nx_on_surface_variant))
                })
            }
            val (title, url) = when (row) {
                is Row.History -> row.visit.title to row.visit.url
                is Row.Mark -> row.bookmark.title to row.bookmark.url
                else -> "" to ""
            }
            (item.getChildAt(0) as TextView).text = title.ifEmpty { url }
            (item.getChildAt(1) as TextView).text = url
            return item
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Appearance.apply(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        BrowserLibrary.init(applicationContext)

        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        toolbar = MaterialToolbar(this).apply {
            title = "Bookmarks & history"
            setNavigationIcon(R.drawable.ic_back)
            setNavigationContentDescription("Back")
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        tabs = TabLayout(this).apply {
            addTab(newTab().setText("Bookmarks"))
            addTab(newTab().setText("History"))
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    showingHistory = tab.position == 1
                    invalidateOptionsMenuItems()
                    refresh()
                }
                override fun onTabUnselected(tab: TabLayout.Tab) {}
                override fun onTabReselected(tab: TabLayout.Tab) {}
            })
        }
        root.addView(tabs, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        search = EditText(this).apply {
            hint = "Search"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = refresh()
            })
        }
        root.addView(search, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(16), dp(8), dp(16), dp(4)) })

        empty = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(context.nx(R.color.nx_on_surface_variant))
            setPadding(dp(32), dp(48), dp(32), dp(48))
        }
        list = ListView(this).apply {
            adapter = this@LibraryActivity.adapter
            divider = null
            clipToPadding = false
            setOnItemClickListener { _, _, position, _ -> open(urlOf(rows[position]) ?: return@setOnItemClickListener) }
            setOnItemLongClickListener { _, _, position, _ -> showActions(rows[position]); true }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(empty, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        invalidateOptionsMenuItems()
        refresh()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // --- toolbar menu --------------------------------------------------------------------------

    private fun invalidateOptionsMenuItems() {
        toolbar.menu.clear()
        if (showingHistory) {
            toolbar.menu.add("Clear history…").setOnMenuItemClickListener { confirmClear(); true }
        } else {
            toolbar.menu.add("Import bookmarks…").setOnMenuItemClickListener { showImport(); true }
            toolbar.menu.add("Export bookmarks").setOnMenuItemClickListener { exportBookmarks(); true }
        }
    }

    // --- content -------------------------------------------------------------------------------

    private fun refresh() {
        val q = search.text.toString().trim().lowercase()
        fun hit(title: String, url: String) = q.isEmpty() || title.lowercase().contains(q) || url.lowercase().contains(q)

        val out = ArrayList<Row>()
        if (showingHistory) {
            var lastDay = ""
            for (v in BrowserLibrary.history()) {
                if (!hit(v.title, v.url)) continue
                val day = dayLabel(v.time)
                if (day != lastDay) { out += Row.Header(day); lastDay = day }
                out += Row.History(v)
            }
            empty.text = if (q.isEmpty()) "No history yet.\nPages you visit will show up here." else "Nothing matches “$q”."
        } else {
            for (b in BrowserLibrary.bookmarks().sortedBy { it.title.lowercase() }) {
                if (hit(b.title, b.url)) out += Row.Mark(b)
            }
            empty.text = if (q.isEmpty()) "No bookmarks yet.\nUse Menu → Bookmark this page." else "Nothing matches “$q”."
        }
        rows = out
        adapter.notifyDataSetChanged()
        empty.visibility = if (out.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (out.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun dayLabel(time: Long): String {
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val day = 24 * 60 * 60 * 1000L
        return when {
            time >= today -> "Today"
            time >= today - day -> "Yesterday"
            else -> DateFormat.getDateInstance(DateFormat.FULL).format(Date(time))
        }
    }

    private fun urlOf(row: Row): String? = when (row) {
        is Row.History -> row.visit.url
        is Row.Mark -> row.bookmark.url
        else -> null
    }

    private fun open(url: String) {
        startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .setClass(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }

    private fun copy(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("url", text))
        Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
    }

    private fun showActions(row: Row) {
        when (row) {
            is Row.History -> {
                val v = row.visit
                val marked = BrowserLibrary.isBookmarked(v.url)
                val options = arrayOf("Open", "Copy link", if (marked) "Remove bookmark" else "Bookmark", "Delete")
                MaterialAlertDialogBuilder(this).setTitle(v.title.ifEmpty { v.url })
                    .setItems(options) { _, which ->
                        when (which) {
                            0 -> open(v.url)
                            1 -> copy(v.url)
                            2 -> {
                                if (marked) BrowserLibrary.removeBookmarkByUrl(v.url)
                                else BrowserLibrary.addBookmark(v.title, v.url)
                                Toast.makeText(this, if (marked) "Bookmark removed" else "Bookmarked", Toast.LENGTH_SHORT).show()
                            }
                            3 -> {
                                BrowserLibrary.removeVisit(v)
                                refresh()
                                undo("Removed from history") { BrowserLibrary.restoreVisit(v); refresh() }
                            }
                        }
                    }.show()
            }
            is Row.Mark -> {
                val b = row.bookmark
                MaterialAlertDialogBuilder(this).setTitle(b.title)
                    .setItems(arrayOf("Open", "Edit", "Copy link", "Delete")) { _, which ->
                        when (which) {
                            0 -> open(b.url)
                            1 -> showEdit(b)
                            2 -> copy(b.url)
                            3 -> {
                                BrowserLibrary.removeBookmark(b)
                                refresh()
                                undo("Bookmark deleted") { BrowserLibrary.restoreBookmark(b); refresh() }
                            }
                        }
                    }.show()
            }
            else -> {}
        }
    }

    private fun undo(message: String, restore: () -> Unit) {
        Snackbar.make(root, message, Snackbar.LENGTH_LONG).setAction("Undo") { restore() }.show()
    }

    private fun showEdit(b: BrowserLibrary.Bookmark) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val title = EditText(this).apply { hint = "Title"; setText(b.title); isSingleLine = true }
        val url = EditText(this).apply {
            hint = "URL"; setText(b.url); isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        form.addView(title)
        form.addView(url)
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Edit bookmark").setView(form)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val u = url.text.toString().trim()
                if (!(u.startsWith("http://") || u.startsWith("https://"))) {
                    url.error = "Must start with http:// or https://"
                    return@setOnClickListener
                }
                BrowserLibrary.updateBookmark(b.id, title.text.toString(), u)
                dialog.dismiss()
                refresh()
            }
        }
        dialog.show()
    }

    private fun confirmClear() {
        val labels = arrayOf("Last hour", "Today", "Last 7 days", "All time")
        var choice = 3
        MaterialAlertDialogBuilder(this).setTitle("Clear history")
            .setSingleChoiceItems(labels, choice) { _, which -> choice = which }
            .setPositiveButton("Clear") { _, _ ->
                val now = System.currentTimeMillis()
                val since = when (choice) {
                    0 -> now - 60 * 60 * 1000L
                    1 -> Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    2 -> now - 7 * 24 * 60 * 60 * 1000L
                    else -> 0L
                }
                val n = BrowserLibrary.clearHistory(since)
                refresh()
                Toast.makeText(this, "Removed $n ${if (n == 1) "entry" else "entries"}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun exportBookmarks() {
        if (BrowserLibrary.bookmarks().isEmpty()) {
            Toast.makeText(this, "No bookmarks to export", Toast.LENGTH_SHORT).show()
            return
        }
        val html = BrowserLibrary.exportBookmarksHtml()
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("bookmarks", html))
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Netex bookmarks")
                putExtra(Intent.EXTRA_TEXT, html)
            }, "Export bookmarks"
        ))
        Toast.makeText(this, "Bookmarks copied as HTML", Toast.LENGTH_SHORT).show()
    }

    private fun showImport() {
        val input = EditText(this).apply {
            hint = "Paste bookmark HTML or JSON here"
            minLines = 6
            maxLines = 12
            gravity = Gravity.TOP or Gravity.START
            textSize = 13f
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (clip.contains("<a ", ignoreCase = true) || clip.trim().startsWith("[")) input.setText(clip)
        MaterialAlertDialogBuilder(this).setTitle("Import bookmarks")
            .setMessage("Works with a bookmarks export from Chrome or Firefox (HTML), or a JSON list of {title, url}.")
            .setView(input)
            .setPositiveButton("Import") { _, _ ->
                val n = BrowserLibrary.importBookmarks(input.text.toString())
                when {
                    n < 0 -> Toast.makeText(this, "No bookmarks found in that text", Toast.LENGTH_LONG).show()
                    else -> {
                        Toast.makeText(this, "Imported $n new bookmark${if (n == 1) "" else "s"}", Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }
}
