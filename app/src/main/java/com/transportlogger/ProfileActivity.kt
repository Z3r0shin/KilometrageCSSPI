package com.transportlogger

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doAfterTextChanged

/**
 * Profil : qui réclame. Demandé une fois au premier lancement, où l'écran est
 * seul sur la pile, puis modifiable depuis les Réglages.
 */
class ProfileActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FIRST_RUN = "firstRun"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)
        // Ui.edgeToEdge pads for the bars only; here the keyboard counts too, so
        // the last fields and the button scroll above it.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val pad = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            val resized = pad.bottom != v.paddingBottom
            v.setPadding(pad.left, pad.top, pad.right, pad.bottom)
            // Left alone, the scroll stops at the caret line and the keyboard
            // cuts the bottom of the field being typed in.
            if (resized) v.doOnNextLayout { currentFocus?.let(::reveal) }
            insets
        }

        val firstRun = intent.getBooleanExtra(EXTRA_FIRST_RUN, false)
        val btnBack = findViewById<View>(R.id.btnBack)
        val btnSave = findViewById<Button>(R.id.btnSave)
        if (firstRun) {
            BetaNotice.showOnce(this)
            btnBack.visibility = View.GONE
            findViewById<TextView>(R.id.profileTitle).apply {
                text = "Bienvenue"
                // Without the arrow the title lines up with the content below.
                updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    marginStart = (12 * resources.displayMetrics.density).toInt()
                }
            }
            findViewById<TextView>(R.id.intro).apply {
                text = "Avant de commencer, dites-nous qui réclame. $text"
            }
            btnSave.text = "Commencer"
        } else {
            btnBack.setOnClickListener { finish() }
        }

        val saved = Profile.load(this)
        mapOf(
            R.id.editName to saved.name,
            R.id.editEmployeeNo to saved.employeeNo,
            R.id.editAddress to saved.address,
            R.id.editCity to saved.city,
            R.id.editPostalCode to saved.postalCode,
            R.id.editFunction to saved.function,
            R.id.editService to saved.service
        ).forEach { (id, value) ->
            findViewById<EditText>(id).apply {
                setText(value)
                setOnFocusChangeListener { field, focused -> if (focused) field.post { reveal(field) } }
            }
        }

        btnSave.isEnabled = entered().complete
        for (id in intArrayOf(R.id.editName, R.id.editEmployeeNo)) {
            findViewById<EditText>(id).doAfterTextChanged { btnSave.isEnabled = entered().complete }
        }
        btnSave.setOnClickListener {
            Profile.save(this, entered())
            if (firstRun) startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    /** Scrolls a field fully into view, with its label above and some air below. */
    private fun reveal(field: View) {
        val air = (24 * resources.displayMetrics.density).toInt()
        field.requestRectangleOnScreen(Rect(0, -2 * air, field.width, field.height + air))
    }

    private fun entered() = Profile(
        text(R.id.editName),
        text(R.id.editEmployeeNo),
        text(R.id.editAddress),
        text(R.id.editCity),
        text(R.id.editPostalCode),
        text(R.id.editFunction),
        text(R.id.editService)
    )

    private fun text(id: Int) = findViewById<EditText>(id).text.toString()
}
