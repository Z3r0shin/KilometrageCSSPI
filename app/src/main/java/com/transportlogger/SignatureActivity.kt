package com.transportlogger

import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.InputFilter
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged

/**
 * Signature : tracée au doigt, ou tapée en lettres carrées. Ouverte depuis le
 * Profil ; enregistrée tout de suite, à part du reste du profil.
 */
class SignatureActivity : AppCompatActivity() {

    companion object {
        /** The profile name, for a first suggestion in block letters. */
        const val EXTRA_NAME = "name"
        private const val STATE_TYPED = "typed"
    }

    private lateinit var pad: SignaturePad
    private lateinit var editTyped: EditText
    private lateinit var btnSave: Button
    private var typed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signature)
        // The keyboard counts too, so the field and the button stay above it.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val pad = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            v.setPadding(pad.left, pad.top, pad.right, pad.bottom)
            insets
        }
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        pad = findViewById(R.id.pad)
        editTyped = findViewById(R.id.editTyped)
        btnSave = findViewById(R.id.btnSave)
        editTyped.filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(60))

        val saved = Signature.load(this)
        when (saved) {
            is Signature.Drawn -> pad.showSaved(BitmapFactory.decodeByteArray(saved.png, 0, saved.png.size))
            is Signature.Typed -> editTyped.setText(saved.text)
            null -> {}
        }
        if (saved !is Signature.Typed) {
            val name = intent.getStringExtra(EXTRA_NAME) ?: Profile.load(this).name
            editTyped.setText(Signature.suggestion(name))
        }

        pad.onChange = ::refresh
        editTyped.doAfterTextChanged { refresh() }
        findViewById<View>(R.id.btnClear).setOnClickListener { pad.clear() }
        findViewById<View>(R.id.modeDrawn).setOnClickListener { setMode(false) }
        findViewById<View>(R.id.modeTyped).setOnClickListener { setMode(true) }

        btnSave.setOnClickListener {
            if (typed) {
                Signature.saveTyped(this, editTyped.text.toString())
            } else {
                // Nothing new drawn over a saved drawing: keep it as it is.
                pad.toPng()?.let { Signature.saveDrawn(this, it) }
            }
            finish()
        }
        findViewById<View>(R.id.btnRemove).apply {
            visibility = if (saved != null) View.VISIBLE else View.GONE
            setOnClickListener {
                Signature.clear(this@SignatureActivity)
                finish()
            }
        }

        setMode(savedInstanceState?.getBoolean(STATE_TYPED) ?: (saved is Signature.Typed))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_TYPED, typed)
    }

    private fun setMode(typed: Boolean) {
        this.typed = typed
        findViewById<View>(R.id.panelDrawn).visibility = if (typed) View.GONE else View.VISIBLE
        findViewById<View>(R.id.panelTyped).visibility = if (typed) View.VISIBLE else View.GONE
        mapOf(R.id.modeDrawn to !typed, R.id.modeTyped to typed).forEach { (id, selected) ->
            findViewById<TextView>(id).apply {
                setBackgroundResource(if (selected) R.drawable.bg_segment_pill else 0)
                setTextColor(ContextCompat.getColor(context, if (selected) R.color.text else R.color.text_muted))
                typeface = ResourcesCompat.getFont(
                    context, if (selected) R.font.instrument_bold else R.font.instrument_medium
                )
            }
        }
        if (!typed) {
            editTyped.clearFocus()
            getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(editTyped.windowToken, 0)
        }
        refresh()
    }

    private fun refresh() {
        findViewById<View>(R.id.padHint).visibility =
            if (pad.hasInk || pad.showsSaved) View.INVISIBLE else View.VISIBLE
        findViewById<View>(R.id.btnClear).isEnabled = pad.hasInk || pad.showsSaved
        btnSave.isEnabled = if (typed) editTyped.text.isNotBlank() else pad.hasInk || pad.showsSaved
    }
}
