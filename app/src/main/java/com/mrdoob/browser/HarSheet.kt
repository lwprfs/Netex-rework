package com.mrdoob.browser

import android.content.res.ColorStateList
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.mrdoob.browser.HarRecorder.State
import com.mrdoob.browser.databinding.SheetHarBinding
import kotlinx.coroutines.launch

/** HAR recording controls: status card, start / pause, export rows, clear. */
class HarSheet(
    private val activity: AppCompatActivity,
    private val onPickLocation: (String) -> Unit
) {

    fun show() {
        val dialog = BottomSheetDialog(activity)
        val b = SheetHarBinding.inflate(activity.layoutInflater)
        dialog.setContentView(b.root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        val job = activity.lifecycleScope.launch { HarRecorder.status.collect { render(b, it) } }
        dialog.setOnDismissListener { job.cancel() }

        bindOption(b.harOptBodies, b.harOptBodiesSwitch, HarOptions::fullBodies)
        bindOption(b.harOptResources, b.harOptResourcesSwitch, HarOptions::extraResources)
        bindOption(b.harOptHeaders, b.harOptHeadersSwitch, HarOptions::requestHeaders)
        bindOption(b.harOptImages, b.harOptImagesSwitch, HarOptions::images)

        b.harStart.setOnClickListener { HarRecorder.start() }
        b.harPause.setOnClickListener { HarRecorder.togglePause() }
        b.harClear.setOnClickListener {
            HarRecorder.clear()
            Toast.makeText(activity, "HAR cache cleared", Toast.LENGTH_SHORT).show()
        }
        b.harShare.setOnClickListener {
            dialog.dismiss()
            activity.lifecycleScope.launch { HarExporter.share(activity) }
        }
        b.harSave.setOnClickListener {
            activity.lifecycleScope.launch { HarExporter.saveToDownloads(activity) }
        }
        b.harFiles.setOnClickListener {
            dialog.dismiss()
            activity.lifecycleScope.launch { HarExporter.pickLocation(activity, onPickLocation) }
        }
        dialog.show()
    }

    private fun bindOption(
        row: android.view.View,
        sw: com.google.android.material.materialswitch.MaterialSwitch,
        prop: kotlin.reflect.KMutableProperty0<Boolean>
    ) {
        sw.isChecked = prop.get()
        row.setOnClickListener {
            prop.set(!prop.get())
            sw.isChecked = prop.get()
        }
    }

    private fun render(b: SheetHarBinding, s: HarRecorder.Status) {
        val counts = "${s.entries} request${if (s.entries == 1) "" else "s"} · " +
            "${s.pages} page${if (s.pages == 1) "" else "s"}"
        b.harStatus.text = when {
            s.state == State.IDLE -> "Not recording"
            s.state == State.PAUSED -> "Paused · $counts"
            s.entries == 0 -> "Recording · reload the page to capture requests"
            else -> "Recording · $counts"
        }
        val dot = when (s.state) {
            State.RECORDING -> 0xFFE53935.toInt()
            State.PAUSED -> 0xFFFBC02D.toInt()
            State.IDLE -> 0xFF9E9E9E.toInt()
        }
        b.harDot.backgroundTintList = ColorStateList.valueOf(dot)
        b.harFile.text = s.fileName ?: "No file yet"
        b.harPause.text = if (s.state == State.PAUSED) "Resume" else "Pause"
        b.harPause.isEnabled = s.state != State.IDLE

        val hasData = s.entries > 0
        for (row in listOf(b.harShare, b.harSave, b.harFiles)) {
            row.isEnabled = hasData
            row.alpha = if (hasData) 1f else 0.4f
        }
        val hasSession = s.state != State.IDLE
        b.harClear.isEnabled = hasSession
        b.harClear.alpha = if (hasSession) 1f else 0.4f
    }
}
