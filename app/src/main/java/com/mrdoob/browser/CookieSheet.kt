package com.mrdoob.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.EditText
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mrdoob.browser.databinding.DialogAddCookieBinding
import com.mrdoob.browser.databinding.ItemCookieBinding
import com.mrdoob.browser.databinding.SheetCookiesBinding

class CookieSheet(
    private val activity: AppCompatActivity,
    private val currentUrl: String
) {

    private lateinit var b: SheetCookiesBinding

    fun show() {
        val dialog = BottomSheetDialog(activity)
        b = SheetCookiesBinding.inflate(activity.layoutInflater)
        dialog.setContentView(b.root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        val initial = currentUrl.takeIf { it.startsWith("http") }
            ?.substringBefore('?')?.substringBefore('#').orEmpty()
        b.cookieSite.setText(initial)
        b.cookieSite.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = refresh()
        })

        b.cookieAdd.setOnClickListener { showAddDialog() }
        b.cookieClearSite.setOnClickListener {
            val url = site() ?: return@setOnClickListener toast("Enter a site first")
            if (CookieTools.isWhitelisted(activity, url)) {
                return@setOnClickListener toast("Cookies kept — this site is on your whitelist")
            }
            CookieTools.clearSite(url) { activity.runOnUiThread { refresh() } }
        }
        b.cookieExport.setOnClickListener { exportJson() }
        b.cookieImport.setOnClickListener { showImportDialog() }
        b.cookieClearAll.setOnClickListener {
            MaterialAlertDialogBuilder(activity)
                .setTitle("Clear all cookies?")
                .setMessage("This removes cookies for every site and signs you out everywhere.")
                .setPositiveButton("Clear all") { _, _ ->
                    CookieTools.clearAll { activity.runOnUiThread { refresh() } }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        refreshSites()
        refresh()
        dialog.show()
    }

    private fun site(): String? = CookieTools.normalizeSite(b.cookieSite.text.toString())

    private fun refreshSites() {
        val sites = CookieTools.sites(activity)
        b.cookieSites.removeAllViews()
        b.cookieSitesScroll.visibility = if (sites.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        for (host in sites) {
            val chip = Chip(activity)
            chip.text = host
            chip.setOnClickListener {
                b.cookieSite.setText("https://$host/")
                b.cookieSite.setSelection(b.cookieSite.text?.length ?: 0)
            }
            b.cookieSites.addView(chip)
        }
    }

    private fun refresh() {
        b.cookieList.removeAllViews()
        val url = site()
        if (url == null) {
            b.cookieCount.text = ""
            b.cookieEmpty.text = "Enter a site, e.g. example.com"
            b.cookieEmpty.visibility = android.view.View.VISIBLE
            return
        }
        val cookies = CookieTools.list(url)
        b.cookieCount.text = "${cookies.size} cookie${if (cookies.size == 1) "" else "s"} sent to ${Uri.parse(url).host}"
        b.cookieEmpty.text = "No cookies for this site"
        b.cookieEmpty.visibility = if (cookies.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        for (c in cookies) {
            val row = ItemCookieBinding.inflate(activity.layoutInflater, b.cookieList, false)
            row.cookieName.text = c.name
            row.cookieValue.text = c.value.ifEmpty { "(empty)" }
            row.root.setOnClickListener {
                val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("cookie", "${c.name}=${c.value}"))
                toast("Copied ${c.name}")
            }
            row.cookieDelete.setOnClickListener {
                CookieTools.delete(url, c.name) { activity.runOnUiThread { refresh() } }
            }
            b.cookieList.addView(row.root)
        }
    }

    private fun showAddDialog() {
        val url = site() ?: return toast("Enter a site first")
        val d = DialogAddCookieBinding.inflate(activity.layoutInflater)
        d.addSite.text = "For ${Uri.parse(url).host}"
        d.addSecure.isChecked = url.startsWith("https://")

        val dialog: AlertDialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Add cookie")
            .setView(d.root)
            .setPositiveButton("Add", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = d.addName.text.toString().trim()
                val value = d.addValue.text.toString().trim()
                val path = d.addPath.text.toString().trim().ifEmpty { "/" }
                val daysText = d.addDays.text.toString().trim()
                val days = daysText.toIntOrNull()
                when {
                    name.isEmpty() -> d.addName.error = "Required"
                    name.any { it == ';' || it == '=' || it.isWhitespace() } ->
                        d.addName.error = "No spaces, ';' or '='"
                    value.contains(';') -> d.addValue.error = "No ';' allowed"
                    !path.startsWith("/") -> d.addPath.error = "Must start with /"
                    daysText.isNotEmpty() && (days == null || days <= 0) -> d.addDays.error = "Positive number"
                    else -> {
                        CookieTools.add(
                            url, name, value, path, days,
                            d.addSecure.isChecked, d.addHttpOnly.isChecked
                        ) { activity.runOnUiThread { refresh() } }
                        CookieTools.rememberSite(activity, url)
                        refreshSites()
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun exportJson() {
        val url = site() ?: return toast("Enter a site first")
        val json = CookieTools.exportJson(url)
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("cookies", json))
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Cookies for ${Uri.parse(url).host}")
            putExtra(Intent.EXTRA_TEXT, json)
        }
        toast("Copied cookies as JSON")
        activity.startActivity(Intent.createChooser(send, "Export cookies (JSON)"))
    }

    private fun showImportDialog() {
        val url = site() ?: return toast("Enter a site first")
        val input = EditText(activity).apply {
            hint = "Paste cookie JSON here"
            minLines = 6
            maxLines = 12
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            val pad = activity.dp(16)
            setPadding(pad, pad, pad, pad)
        }
        // Pre-fill from the clipboard when it already holds JSON.
        val clip = (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .primaryClip?.getItemAt(0)?.coerceToText(activity)?.toString().orEmpty().trim()
        if (clip.startsWith("{") || clip.startsWith("[")) input.setText(clip)

        MaterialAlertDialogBuilder(activity)
            .setTitle("Import cookies for ${Uri.parse(url).host}")
            .setMessage("Accepts an export from this app, or a list like [{\"name\":\"a\",\"value\":\"b\"}].")
            .setView(input)
            .setPositiveButton("Import") { _, _ ->
                CookieTools.importJson(input.text.toString(), url) { added ->
                    activity.runOnUiThread {
                        when {
                            added < 0 -> toast("That isn't valid cookie JSON")
                            added == 0 -> toast("No cookies found to import")
                            else -> {
                                toast("Imported $added cookie${if (added == 1) "" else "s"}")
                                CookieTools.rememberSite(activity, url)
                                refreshSites()
                                refresh()
                            }
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    }
}
