package com.transportlogger

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.materialswitch.MaterialSwitch
import java.text.SimpleDateFormat
import java.util.Calendar

/**
 * Calendrier des congés. Toucher un jour le bascule en congé : la détection
 * ne tourne pas ces jours-là (vacances, fériés, journées pédagogiques…).
 * Les week-ends sont en congé par défaut (désactivable). La semaine commence
 * le dimanche.
 */
class CalendarActivity : AppCompatActivity() {

    private lateinit var grid: GridLayout
    private lateinit var monthLabel: TextView
    private val cal = Calendar.getInstance()   // first day of the shown month
    private val density get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calendar)
        Ui.edgeToEdge(this, findViewById(R.id.root))
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        grid = findViewById(R.id.calendarGrid)
        monthLabel = findViewById(R.id.monthLabel)
        cal.set(Calendar.DAY_OF_MONTH, 1)

        findViewById<View>(R.id.btnPrev).setOnClickListener { cal.add(Calendar.MONTH, -1); render() }
        findViewById<View>(R.id.btnNext).setOnClickListener { cal.add(Calendar.MONTH, 1); render() }

        val sw = findViewById<MaterialSwitch>(R.id.switchWeekends)
        sw.isChecked = Settings.weekendsOff(this)
        sw.setOnCheckedChangeListener { _, checked ->
            Settings.setWeekendsOff(this, checked); render()
        }

        render()
    }

    private fun key(y: Int, m: Int, d: Int) = String.format("%04d-%02d-%02d", y, m + 1, d)

    private fun render() {
        monthLabel.text = SimpleDateFormat("MMMM yyyy", Fmt.FR)
            .format(cal.time).replaceFirstChar { it.uppercase(Fmt.FR) }

        grid.removeAllViews()
        for (h in listOf("D", "L", "M", "M", "J", "V", "S")) grid.addView(header(h))

        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH)
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        // Sunday-first: DAY_OF_WEEK is Sun=1..Sat=7, so day 1 sits after that many blanks.
        repeat(cal.get(Calendar.DAY_OF_WEEK) - 1) { grid.addView(day("", null)) }

        val today = Settings.todayKey()
        val weekendsOff = Settings.weekendsOff(this)
        val marked = Settings.daysOff(this)

        for (d in 1..daysInMonth) {
            val k = key(y, m, d)
            val dow = Calendar.getInstance().apply { set(y, m, d) }.get(Calendar.DAY_OF_WEEK)
            val isWeekend = dow == Calendar.SATURDAY || dow == Calendar.SUNDAY
            val state = when {
                marked.contains(k) -> State.OFF
                isWeekend && weekendsOff -> State.WEEKEND
                k == today -> State.TODAY
                else -> State.PLAIN
            }
            val cell = day(d.toString(), state, bold = k == today || state == State.OFF)
            cell.contentDescription = Fmt.day(k) + when (state) {
                State.OFF -> ", en congé"
                State.WEEKEND -> ", week-end"
                else -> ""
            }
            cell.setOnClickListener { Settings.toggleDayOff(this, k); render() }
            grid.addView(cell)
        }

        renderOffList(String.format("%04d-%02d", y, m + 1), marked)
    }

    /** The marked days of the shown month, each removable without hunting for it in the grid. */
    private fun renderOffList(monthKey: String, marked: Set<String>) {
        val days = marked.filter { it.startsWith(monthKey) }.sorted()
        val label = findViewById<TextView>(R.id.offListLabel)
        val list = findViewById<LinearLayout>(R.id.offList)
        label.visibility = if (days.isEmpty()) View.GONE else View.VISIBLE
        list.visibility = label.visibility
        label.text = "Congés en " + SimpleDateFormat("MMMM", Fmt.FR).format(cal.time)

        list.removeAllViews()
        days.forEachIndexed { i, k ->
            if (i > 0) list.addView(View(this).apply {
                setBackgroundColor(ContextCompat.getColor(this@CalendarActivity, R.color.divider))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                ).apply { marginStart = dp(16); marginEnd = dp(16) }
            })
            val row = LayoutInflater.from(this).inflate(R.layout.item_day_off, list, false)
            row.findViewById<TextView>(R.id.dayOffLabel).text = Fmt.day(k)
            row.findViewById<View>(R.id.dayOffRemove).setOnClickListener {
                Settings.toggleDayOff(this, k); render()
            }
            list.addView(row)
        }
    }

    private enum class State { PLAIN, WEEKEND, OFF, TODAY }

    private fun dp(v: Int) = (v * density).toInt()

    private fun cellParams() = GridLayout.LayoutParams(
        GridLayout.spec(GridLayout.UNDEFINED, 1f),
        GridLayout.spec(GridLayout.UNDEFINED, 1f)
    ).apply { width = 0 }

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = 12f
        setTextColor(ContextCompat.getColor(context, R.color.text_muted))
        typeface = ResourcesCompat.getFont(context, R.font.instrument_semibold)
        layoutParams = cellParams().apply { height = dp(32) }
    }

    /** A 46dp cell holding a 38dp circle; [state] null is a blank before the 1st. */
    private fun day(text: String, state: State?, bold: Boolean = false): FrameLayout {
        val circle = TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = ResourcesCompat.getFont(
                context, if (bold) R.font.instrument_bold else R.font.instrument_regular
            )
            setTextColor(
                ContextCompat.getColor(
                    context, when (state) {
                        State.OFF -> R.color.on_amber
                        State.WEEKEND -> R.color.text_muted
                        State.TODAY -> R.color.green_text
                        else -> R.color.text
                    }
                )
            )
            when (state) {
                State.OFF -> setBackgroundResource(R.drawable.bg_day_off)
                State.WEEKEND -> setBackgroundResource(R.drawable.bg_day_weekend)
                State.TODAY -> setBackgroundResource(R.drawable.bg_day_today)
                else -> {}
            }
            layoutParams = FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER)
        }
        return FrameLayout(this).apply {
            addView(circle)
            layoutParams = cellParams().apply { height = dp(46) }
        }
    }
}
