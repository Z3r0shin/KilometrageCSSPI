package com.transportlogger

import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * The bottom sheet that attaches a P.O. to a trip line: scan it, type it, or
 * pick a recent one. An aller-retour carries one P.O., so both of its legs get
 * the number.
 *
 * Create it as a field of the activity: it registers the scanner's result
 * callback, which has to happen before the activity starts.
 */
internal class PoSheet(private val activity: AppCompatActivity) {

    private var field: EditText? = null

    private val scanLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val f = field ?: return@registerForActivityResult
            val code = result.data?.getStringExtra(ScanActivity.EXTRA_CODE)
            if (code != null) {
                f.setText(code)
                f.setSelection(f.text.length)
            } else if (result.data?.getBooleanExtra(ScanActivity.EXTRA_MANUAL, false) == true) {
                f.requestFocus()
                (activity.getSystemService(AppCompatActivity.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(f, InputMethodManager.SHOW_IMPLICIT)
            }
        }

    fun show(line: FormulaireExport.Line, onSaved: () -> Unit) {
        val store = TripStore(activity)
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_po, null)
        dialog.setContentView(view)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.setOnDismissListener { field = null }

        view.findViewById<TextView>(R.id.poRoute).text = TripRow.route(line)
        view.findViewById<TextView>(R.id.poDetail).text =
            "${Fmt.day(line.trip.date)} · ${Fmt.time(line.trip.ts)} · ${Fmt.km(line.total)}"

        val edit = view.findViewById<EditText>(R.id.editPo)
        val save = view.findViewById<View>(R.id.btnSavePo)
        val original = line.po ?: ""
        field = edit
        edit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                save.isEnabled = s.toString().trim() != original
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        edit.setText(original)
        edit.setSelection(edit.text.length)
        save.isEnabled = false

        view.findViewById<View>(R.id.btnScanPo).setOnClickListener {
            scanLauncher.launch(Intent(activity, ScanActivity::class.java))
        }

        val recents = store.recentPos().filter { it != original }
        view.findViewById<View>(R.id.poRecentsRow).visibility =
            if (recents.isEmpty()) View.GONE else View.VISIBLE
        val row = view.findViewById<LinearLayout>(R.id.poRecents)
        recents.forEach { po -> row.addView(recentChip(po) { edit.setText(po); edit.setSelection(po.length) }) }

        view.findViewById<View>(R.id.btnCancelPo).setOnClickListener { dialog.dismiss() }
        save.setOnClickListener {
            val po = edit.text.toString()
            store.setPo(line.trip.ts, po)
            line.back?.let { store.setPo(it.ts, po) }
            dialog.dismiss()
            onSaved()
        }
        dialog.show()
    }

    private fun recentChip(po: String, onPick: () -> Unit): TextView {
        val d = activity.resources.displayMetrics.density
        return TextView(activity).apply {
            text = po
            typeface = ResourcesCompat.getFont(activity, R.font.jetbrains_mono_medium)
            textSize = 13f
            setTextColor(ContextCompat.getColor(activity, R.color.text))
            setBackgroundResource(R.drawable.bg_chip_recent)
            gravity = Gravity.CENTER
            minHeight = (44 * d).toInt()
            setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * d).toInt() }
            setOnClickListener { onPick() }
        }
    }
}
