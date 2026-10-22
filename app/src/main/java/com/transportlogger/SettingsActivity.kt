package com.transportlogger

import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.slider.Slider
import java.text.SimpleDateFormat
import java.util.Calendar

/**
 * Réglages : profil, thème, fenêtre de travail (défaut 07:30 – 16:15) et congés,
 * durée d'arrêt minimale, taille par défaut des zones et import/export des lieux.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var btnStart: TextView
    private lateinit var btnEnd: TextView
    private lateinit var radiusLabel: TextView
    private lateinit var dwellLabel: TextView

    private val importZonesLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                try {
                    val json = contentResolver.openInputStream(uri)!!
                        .bufferedReader(Charsets.UTF_8).readText()
                    val n = PlaceStore(this).importJson(json)
                    Toast.makeText(this, "$n lieu(x) importé(s) ou mis à jour.", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Import invalide : ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

    private var pendingZonesJson = ""
    private val exportZonesLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
            if (uri != null) {
                try {
                    contentResolver.openOutputStream(uri)
                        ?.use { it.write(pendingZonesJson.toByteArray(Charsets.UTF_8)) }
                    Toast.makeText(this, "Lieux exportés.", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Échec : ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        Ui.edgeToEdge(this, findViewById(R.id.root), padBottom = false)
        Nav.attach(this, R.id.nav_settings)

        btnStart = findViewById(R.id.btnStart)
        btnEnd = findViewById(R.id.btnEnd)
        radiusLabel = findViewById(R.id.radiusLabel)
        dwellLabel = findViewById(R.id.dwellLabel)

        // Tells two phones' builds apart when an update is passed along.
        val info = packageManager.getPackageInfo(packageName, 0)
        findViewById<TextView>(R.id.appVersion).text =
            "Version ${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"

        mapOf(
            R.id.themeSystem to Settings.THEME_SYSTEM,
            R.id.themeLight to Settings.THEME_LIGHT,
            R.id.themeDark to Settings.THEME_DARK
        ).forEach { (id, mode) ->
            // Changing the mode recreates the screen in the new theme.
            findViewById<View>(id).setOnClickListener {
                if (Settings.themeMode(this) != mode) Settings.setThemeMode(this, mode)
            }
        }

        // Keeps the press ripple inside the rounded corners.
        findViewById<View>(R.id.profileCard).clipToOutline = true
        findViewById<View>(R.id.rowProfile).setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }

        findViewById<View>(R.id.rowStart).setOnClickListener { pickTime(true) }
        findViewById<View>(R.id.rowEnd).setOnClickListener { pickTime(false) }
        findViewById<View>(R.id.rowConges).setOnClickListener {
            startActivity(Intent(this, CalendarActivity::class.java))
        }

        val radiusSlider = findViewById<Slider>(R.id.radiusSlider)
        radiusSlider.value = Settings.defaultRadius(this).toFloat().coerceIn(30f, 250f)
        radiusSlider.addOnChangeListener { _, v, _ ->
            Settings.setDefaultRadius(this, v.toInt())
            refreshLabels()
        }

        findViewById<View>(R.id.btnApplyAll).setOnClickListener {
            val r = Settings.defaultRadius(this)
            AlertDialog.Builder(this)
                .setMessage("Transformer tous les lieux sans terrain en carrés de $r m de côté ?")
                .setPositiveButton("Appliquer") { _, _ ->
                    PlaceStore(this).applySizeToAll(r.toDouble())
                    Toast.makeText(this, "Taille appliquée.", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Annuler", null)
                .show()
        }

        val dwellSlider = findViewById<Slider>(R.id.dwellSlider)
        dwellSlider.value = (Settings.dwellSec(this) / 30f).coerceIn(2f, 10f)   // 1.0–5.0 min by half-minutes
        dwellSlider.addOnChangeListener { _, v, _ ->
            Settings.setDwellSec(this, (v * 30).toInt())
            refreshLabels()
        }

        val marginSlider = findViewById<Slider>(R.id.marginSlider)
        marginSlider.value = Settings.zoneMarginM(this).toFloat().coerceIn(0f, 60f)
        marginSlider.addOnChangeListener { _, v, _ ->
            Settings.setZoneMarginM(this, v.toInt())
            refreshLabels()
        }

        findViewById<View>(R.id.btnImportZones).setOnClickListener {
            importZonesLauncher.launch(arrayOf("application/json", "text/*"))
        }
        findViewById<View>(R.id.btnExportZones).setOnClickListener {
            pendingZonesJson = PlaceStore(this).exportJson()
            exportZonesLauncher.launch("zones_transport_logger.json")
        }
    }

    override fun onResume() {
        super.onResume()
        Nav.sync(this, R.id.nav_settings)
        refreshLabels()      // the profile and the congés summary change behind us
    }

    private fun pickTime(isStart: Boolean) {
        val cur = if (isStart) Settings.startMinutes(this) else Settings.endMinutes(this)
        TimePickerDialog(this, { _, h, m ->
            val v = h * 60 + m
            var s = Settings.startMinutes(this)
            var e = Settings.endMinutes(this)
            if (isStart) s = v else e = v
            if (s >= e) {
                Toast.makeText(this, "Le début doit précéder la fin.", Toast.LENGTH_SHORT).show()
                return@TimePickerDialog
            }
            Settings.setWindow(this, s, e)
            refreshLabels()
            // Poke the service so the new window applies immediately.
            LocationService.ensureRunning(this)
        }, cur / 60, cur % 60, true).show()
    }

    private fun refreshLabels() {
        val profile = Profile.load(this)
        findViewById<TextView>(R.id.profileName).text = profile.name
        findViewById<TextView>(R.id.profileSummary).text =
            listOf("No d'employé ${profile.employeeNo}", profile.function, profile.service)
                .filter { it.isNotBlank() }.joinToString(" · ")
        btnStart.text = Settings.fmt(Settings.startMinutes(this))
        btnEnd.text = Settings.fmt(Settings.endMinutes(this))
        radiusLabel.text = "${Settings.defaultRadius(this)} m"
        dwellLabel.text = String.format(Fmt.FR, "%.1f min", Settings.dwellSec(this) / 60.0)
        findViewById<TextView>(R.id.marginLabel).text = "${Settings.zoneMarginM(this)} m"
        findViewById<TextView>(R.id.congesSummary).text = congesSummary()

        val mode = Settings.themeMode(this)
        mapOf(
            R.id.themeSystem to Settings.THEME_SYSTEM,
            R.id.themeLight to Settings.THEME_LIGHT,
            R.id.themeDark to Settings.THEME_DARK
        ).forEach { (id, m) ->
            val v = findViewById<TextView>(id)
            val selected = m == mode
            v.setBackgroundResource(if (selected) R.drawable.bg_segment_pill else 0)
            v.setTextColor(ContextCompat.getColor(this, if (selected) R.color.text else R.color.text_muted))
            v.typeface = ResourcesCompat.getFont(
                this, if (selected) R.font.instrument_bold else R.font.instrument_medium
            )
        }
    }

    /** "Week-ends + 2 jours en octobre": the days off in the current month. */
    private fun congesSummary(): String {
        val now = Calendar.getInstance()
        val prefix = SimpleDateFormat("yyyy-MM", Fmt.FR).format(now.time)
        val month = SimpleDateFormat("MMMM", Fmt.FR).format(now.time)
        val n = Settings.daysOff(this).count { it.startsWith(prefix) }
        val days = when (n) {
            0 -> "aucun jour"
            1 -> "1 jour"
            else -> "$n jours"
        }
        return if (Settings.weekendsOff(this)) "Week-ends + $days en $month" else "$days en $month"
    }
}
