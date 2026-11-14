package com.mrdoob.browser

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.mrdoob.browser.HarRecorder.State
import com.mrdoob.browser.databinding.ItemOptionRowBinding
import com.mrdoob.browser.databinding.SheetToolsBinding
import kotlinx.coroutines.launch

/** What the Menu sheet needs from the browser. */
interface ToolsHost {
    val isDesktopMode: Boolean
    val currentTextZoom: Int
    val pageUrl: String
    fun changeDesktopMode(on: Boolean)
    fun changeTextZoom(percent: Int)
    fun openSettings()
    fun sharePage()
    fun copyLink()
    fun openExternal()
    fun hardReload()
    fun openCookies()
    fun openHar()
    fun clearBrowsingData()
}

class ToolsSheet(
    private val activity: AppCompatActivity,
    private val host: ToolsHost
) {

    fun show() {
        val dialog = BottomSheetDialog(activity)
        val b = SheetToolsBinding.inflate(activity.layoutInflater)
        dialog.setContentView(b.root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        val url = host.pageUrl
        b.toolPageHost.text = if (url.startsWith("http")) android.net.Uri.parse(url).host ?: url else "No page loaded"
        b.toolVersion.text = "Netex ${BuildConfig.VERSION_NAME}"

        // Quick actions
        b.toolShare.setOnClickListener { dialog.dismiss(); host.sharePage() }
        b.toolCopy.setOnClickListener { dialog.dismiss(); host.copyLink() }
        b.toolExternal.setOnClickListener { dialog.dismiss(); host.openExternal() }
        b.toolRefresh.setOnClickListener { dialog.dismiss(); host.hardReload() }

        // Browsing rows
        val desktop = row(b.toolCardMain, R.drawable.ic_desktop, "Desktop site", "Request the desktop version of pages")
        desktop.optSwitch.visibility = View.VISIBLE
        desktop.optSwitch.isChecked = host.isDesktopMode
        desktop.root.setOnClickListener {
            val on = !desktop.optSwitch.isChecked
            desktop.optSwitch.isChecked = on
            dialog.dismiss()
            host.changeDesktopMode(on)
        }

        val settings = row(b.toolCardMain, R.drawable.ic_settings, "Settings", "Floating bubble, capture, request rules, cookies")
        settings.optChevron.visibility = View.VISIBLE
        settings.root.setOnClickListener {
            dialog.dismiss()
            host.openSettings()
        }

        val cookies = row(b.toolCardMain, R.drawable.ic_cookie, "Cookies", "View, add and delete cookies")
        cookies.optChevron.visibility = View.VISIBLE
        cookies.root.setOnClickListener { dialog.dismiss(); host.openCookies() }

        val har = row(b.toolCardMain, R.drawable.ic_network, "Network capture", "")
        har.optChevron.visibility = View.VISIBLE
        har.root.setOnClickListener { dialog.dismiss(); host.openHar() }

        // Text size
        val zoomId = when (host.currentTextZoom) {
            85 -> R.id.zoom85
            125 -> R.id.zoom125
            150 -> R.id.zoom150
            else -> R.id.zoom100
        }
        b.toolZoom.check(zoomId)
        b.toolZoom.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val percent = when (id) {
                R.id.zoom85 -> 85
                R.id.zoom125 -> 125
                R.id.zoom150 -> 150
                else -> 100
            }
            if (percent != host.currentTextZoom) host.changeTextZoom(percent)
        }

        // Privacy
        val clear = row(b.toolCardDanger, R.drawable.ic_delete, "Clear browsing data", "Cache, cookies, site storage and history")
        val red = ContextCompat.getColor(activity, R.color.nx_error)
        clear.optTitle.setTextColor(red)
        clear.optIcon.imageTintList = ColorStateList.valueOf(red)
        clear.root.setOnClickListener { dialog.dismiss(); host.clearBrowsingData() }

        val job = activity.lifecycleScope.launch {
            HarRecorder.status.collect { s ->
                val n = "${s.entries} request${if (s.entries == 1) "" else "s"}"
                har.optSubtitle.text = when (s.state) {
                    State.IDLE -> if (s.entries == 0) "Record traffic as a .har file" else "Stopped · $n"
                    State.RECORDING -> "Recording · $n"
                    State.PAUSED -> "Paused · $n"
                }
            }
        }
        dialog.setOnDismissListener { job.cancel() }
        dialog.show()
    }

    private fun row(parent: ViewGroup, @DrawableRes icon: Int, title: String, subtitle: String): ItemOptionRowBinding {
        val r = ItemOptionRowBinding.inflate(activity.layoutInflater, parent, false)
        r.optIcon.setImageResource(icon)
        r.optTitle.text = title
        r.optSubtitle.text = subtitle
        parent.addView(r.root)
        return r
    }
}
