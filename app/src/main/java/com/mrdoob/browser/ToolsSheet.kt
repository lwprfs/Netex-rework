package com.mrdoob.browser

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.mrdoob.browser.HarRecorder.State
import com.mrdoob.browser.databinding.SheetToolsBinding
import kotlinx.coroutines.launch

class ToolsSheet(
    private val activity: AppCompatActivity,
    private val desktopMode: Boolean,
    private val onDesktopMode: (Boolean) -> Unit,
    private val onCookies: () -> Unit,
    private val onHar: () -> Unit
) {

    fun show() {
        val dialog = BottomSheetDialog(activity)
        val b = SheetToolsBinding.inflate(activity.layoutInflater)
        dialog.setContentView(b.root)

        b.toolDesktopSwitch.isChecked = desktopMode
        b.toolDesktopSwitch.setOnCheckedChangeListener { _, on ->
            dialog.dismiss()
            onDesktopMode(on)
        }
        b.toolDesktop.setOnClickListener { b.toolDesktopSwitch.toggle() }
        b.toolCookies.setOnClickListener { dialog.dismiss(); onCookies() }
        b.toolHar.setOnClickListener { dialog.dismiss(); onHar() }

        val job = activity.lifecycleScope.launch {
            HarRecorder.status.collect { s ->
                b.toolHarSub.text = when (s.state) {
                    State.IDLE -> "Not recording"
                    State.RECORDING -> "Recording · ${s.entries} request${if (s.entries == 1) "" else "s"}"
                    State.PAUSED -> "Paused · ${s.entries} request${if (s.entries == 1) "" else "s"}"
                }
            }
        }
        dialog.setOnDismissListener { job.cancel() }
        dialog.show()
    }
}
