package com.mrdoob.browser

import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mrdoob.browser.databinding.ItemOptionRowBinding

/** What the scripts / styles manager needs from the browser. */
interface InjectionHost {
    val pageUrl: String
    /** Runs JavaScript in the page that is open right now. */
    fun runOnPage(script: String)
}

/**
 * Manager for user scripts (JavaScript) and user styles (CSS): create, edit, enable, delete,
 * reorder (execution order = list order), run now / preview on the open page, and choose the
 * sites they apply to.
 */
class InjectionSheet(
    private val activity: AppCompatActivity,
    private val host: InjectionHost
) {

    private lateinit var dialog: BottomSheetDialog
    private lateinit var list: LinearLayout

    fun show() {
        dialog = BottomSheetDialog(activity)
        val scroll = ScrollView(activity)
        list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(16), activity.dp(8), activity.dp(16), activity.dp(24))
        }
        scroll.addView(list)
        dialog.setContentView(scroll)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        render()
        dialog.show()
    }

    private fun render() {
        list.removeAllViews()
        list.addView(TextView(activity).apply {
            text = "Scripts & styles"
            textSize = 20f
            setPadding(activity.dp(6), activity.dp(8), activity.dp(6), activity.dp(8))
        })

        val items = InjectionStore.items()
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            clipToOutline = true
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = ContextCompat.getDrawable(activity, R.drawable.divider_inset)
        }
        list.addView(card, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        items.forEachIndexed { index, item ->
            val where = item.domains.trim().ifEmpty { "all sites" }
            val r = row(card, if (item.type == InjectionStore.Type.JS) R.drawable.ic_code else R.drawable.ic_tune,
                item.name.ifEmpty { "Untitled" }, "${item.type.name} · $where")
            r.optSwitch.visibility = View.VISIBLE
            r.optSwitch.isChecked = item.enabled
            // The switch toggles; tapping the row edits; long-press opens more actions.
            r.optSwitch.setOnClickListener {
                save(items.toMutableList().also { it[index] = item.copy(enabled = r.optSwitch.isChecked) })
            }
            r.root.setOnClickListener { showEditor(item) }
            r.root.setOnLongClickListener { anchorMenu(r.root, items, index); true }
        }

        val add = row(card, R.drawable.ic_add, "Add script or style", "JavaScript or CSS for chosen sites")
        add.optChevron.visibility = View.VISIBLE
        add.root.setOnClickListener { showEditor(null) }

        list.addView(TextView(activity).apply {
            text = "They run when a page finishes loading, in list order (not before the page's own scripts). " +
                "Long-press an entry to run it now, reorder or delete it."
            textSize = 12.5f
            setTextColor(activity.nx(R.color.nx_on_surface_variant))
            setPadding(activity.dp(6), activity.dp(10), activity.dp(6), 0)
        })
    }

    private fun save(items: List<InjectionStore.Item>) {
        InjectionStore.save(items)
    }

    private fun anchorMenu(anchor: View, items: List<InjectionStore.Item>, index: Int) {
        val menu = PopupMenu(activity, anchor)
        menu.menu.add(0, 1, 0, "Run on this page now")
        if (index > 0) menu.menu.add(0, 2, 1, "Move up")
        if (index < items.size - 1) menu.menu.add(0, 3, 2, "Move down")
        menu.menu.add(0, 4, 3, "Delete")
        menu.setOnMenuItemClickListener { m ->
            val mutable = items.toMutableList()
            when (m.itemId) {
                1 -> runNow(items[index])
                2 -> { mutable.add(index - 1, mutable.removeAt(index)); save(mutable); render() }
                3 -> { mutable.add(index + 1, mutable.removeAt(index)); save(mutable); render() }
                4 -> {
                    MaterialAlertDialogBuilder(activity).setTitle("Delete “${items[index].name}”?")
                        .setPositiveButton("Delete") { _, _ -> mutable.removeAt(index); save(mutable); render() }
                        .setNegativeButton("Cancel", null).show()
                }
            }
            true
        }
        menu.show()
    }

    private fun runNow(item: InjectionStore.Item) {
        if (!host.pageUrl.startsWith("http")) {
            Toast.makeText(activity, "Open a website first", Toast.LENGTH_SHORT).show()
            return
        }
        host.runOnPage(InjectionStore.scriptFor(item))
        Toast.makeText(activity, "Applied to this page", Toast.LENGTH_SHORT).show()
    }

    private fun showEditor(existing: InjectionStore.Item?) {
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(24), activity.dp(8), activity.dp(24), 0)
        }
        fun edit(hint: String, value: String, multiline: Boolean = false) = EditText(activity).apply {
            this.hint = hint
            setText(value)
            if (multiline) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                minLines = 6
                maxLines = 14
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 13f
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
            } else {
                isSingleLine = true
            }
            form.addView(this)
        }
        val name = edit("Name", existing?.name.orEmpty())
        val group = RadioGroup(activity).apply { orientation = RadioGroup.HORIZONTAL }
        val js = RadioButton(activity).apply { text = "JavaScript"; id = View.generateViewId() }
        val css = RadioButton(activity).apply { text = "CSS"; id = View.generateViewId() }
        group.addView(js)
        group.addView(css)
        form.addView(group)
        if (existing?.type == InjectionStore.Type.CSS) css.isChecked = true else js.isChecked = true
        val domains = edit("Sites (e.g. example.com, other.org — empty = all)", existing?.domains.orEmpty())
        val code = edit("Code", existing?.code.orEmpty(), multiline = true)
        val enabled = CheckBox(activity).apply { text = "Enabled"; isChecked = existing?.enabled ?: true }
        form.addView(enabled)

        fun build() = InjectionStore.Item(
            id = existing?.id ?: InjectionStore.newId(),
            name = name.text.toString().trim().ifEmpty { "Untitled" },
            type = if (css.isChecked) InjectionStore.Type.CSS else InjectionStore.Type.JS,
            domains = domains.text.toString().trim(),
            code = code.text.toString(),
            enabled = enabled.isChecked
        )

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(if (existing == null) "New script or style" else "Edit")
            .setView(ScrollView(activity).apply { addView(form) })
            .setPositiveButton("Save", null)
            .setNeutralButton("Run now", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (code.text.isBlank()) {
                    code.error = "Enter some code"
                    return@setOnClickListener
                }
                val item = build()
                val all = InjectionStore.items().toMutableList()
                val i = all.indexOfFirst { it.id == item.id }
                if (i >= 0) all[i] = item else all += item
                save(all)
                dialog.dismiss()
                render()
            }
            // Preview without saving; stays until the page reloads.
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (code.text.isBlank()) code.error = "Enter some code" else runNow(build())
            }
        }
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
