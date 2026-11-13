package com.mrdoob.browser

import android.net.Uri
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

/** Full-height sheet: pick a site, see its cookies, add / delete them. */
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
            CookieTools.clearSite(url) { activity.runOnUiThread { refresh() } }
        }
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
            .setPositiveButton("Add", null) // overridden below so errors keep the dialog open
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

    private fun toast(msg: String) {
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    }
}
