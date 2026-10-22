package com.transportlogger

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.text.buildSpannedString
import androidx.core.text.inSpans
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date

/**
 * The Trajets tab: one month at a time, read the way the claim form will show
 * it. The month's kilometres and claim value, how much of the 25-line form it
 * fills and the two exports, then its lines day by day, newest day first.
 */
class TripsActivity : AppCompatActivity() {

    companion object {
        private const val STATE_MONTH = "month"
        private const val STATE_NO_PO_ONLY = "noPoOnly"
        private const val STATE_CSV_MONTH = "csvMonth"
    }

    private lateinit var store: TripStore
    private val poSheet = PoSheet(this)
    private val tripSheet = TripSheet(this)

    /** The month on screen; null until the first trip exists, then the most recent. */
    private var month: String? = null
    private var noPoOnly = false

    /** What the CSV being saved covers: a month key, or null for every trip. */
    private var csvMonth: String? = null

    private val csvLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            val message = try {
                store.reload()
                val out = contentResolver.openOutputStream(uri) ?: throw IOException("fichier inaccessible")
                out.use { it.write(store.toCsv(csvMonth).toByteArray(Charsets.UTF_8)) }
                "CSV exporté."
            } catch (e: Exception) {
                "Échec de l'export : ${e.message}"
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trips)
        Ui.edgeToEdge(this, findViewById(R.id.root), padBottom = false)
        Nav.attach(this, R.id.nav_trips)

        store = TripStore(this)
        savedInstanceState?.let {
            month = it.getString(STATE_MONTH)
            noPoOnly = it.getBoolean(STATE_NO_PO_ONLY)
            csvMonth = it.getString(STATE_CSV_MONTH)
        }

        findViewById<TextView>(R.id.rateCaption).text =
            String.format(Fmt.FR, "à %.2f $/km", FormulaireExport.RATE_PER_KM)
        findViewById<View>(R.id.btnPrevMonth).setOnClickListener { step(1) }
        findViewById<View>(R.id.btnNextMonth).setOnClickListener { step(-1) }
        findViewById<View>(R.id.btnExportForm).setOnClickListener { shareFormulaire() }
        findViewById<View>(R.id.btnExportCsv).setOnClickListener { csvDialog() }
        findViewById<View>(R.id.btnAddTrip).setOnClickListener { addTrip() }
        findViewById<View>(R.id.chipAll).setOnClickListener { noPoOnly = false; render() }
        findViewById<View>(R.id.chipNoPo).setOnClickListener { noPoOnly = true; render() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_MONTH, month)
        outState.putBoolean(STATE_NO_PO_ONLY, noPoOnly)
        outState.putString(STATE_CSV_MONTH, csvMonth)
    }

    /** A leg can be logged while this screen is open; show it straight away. */
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = reload()
    }

    override fun onResume() {
        super.onResume()
        Nav.sync(this, R.id.nav_trips)
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(LocationService.ACTION_TRIPS_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        reload()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    /** The service and the P.O. sheet write through their own stores. */
    private fun reload() {
        store.reload()
        render()
    }

    // ---- Month ----

    /** Months run most recent first, so +1 is the month before. */
    private fun step(delta: Int) {
        val months = store.months()
        month = months.getOrNull(months.indexOf(month) + delta) ?: return
        render()
    }

    private fun render() {
        val months = store.months()
        val shown = month
        val key = when {
            months.isEmpty() -> SimpleDateFormat("yyyy-MM", Fmt.FR).format(Date())
            shown == null -> months.first()
            shown in months -> shown
            // Its last trip was just deleted: fall back on the month before it.
            else -> months.firstOrNull { it < shown } ?: months.last()
        }
        month = key.takeIf { months.isNotEmpty() }

        val index = months.indexOf(key)
        findViewById<TextView>(R.id.monthLabel).text = Fmt.month(key)
        setArrow(R.id.btnPrevMonth, index in 0 until months.lastIndex)
        setArrow(R.id.btnNextMonth, index > 0)

        // Pairing never crosses days, so the form's lines can be built a day at a time.
        val days = store.tripsForMonth(key).groupBy { it.date }.toSortedMap(reverseOrder())
            .mapValues { (_, trips) -> FormulaireExport.lines(trips.sortedBy { it.ts }) }
        val lines = days.values.flatten()

        val hasTrips = months.isNotEmpty()
        findViewById<View>(R.id.summary).visibility = if (hasTrips) View.VISIBLE else View.GONE
        findViewById<View>(R.id.filters).visibility = if (hasTrips) View.VISIBLE else View.GONE
        renderSummary(key, lines)
        chip(R.id.chipAll, "Tous · ${lines.size}", selected = !noPoOnly, amber = false)
        val noPo = lines.count { it.po == null }
        chip(R.id.chipNoPo, "Sans P.O. · $noPo", selected = noPoOnly, amber = noPo > 0)
        val nothingShown = renderDays(days) == 0

        val empty = findViewById<TextView>(R.id.empty)
        empty.text =
            if (hasTrips) "Tous les trajets de ce mois ont un P.O."
            else "Aucun trajet enregistré. Les arrêts confirmés apparaîtront ici."
        empty.visibility = if (nothingShown) View.VISIBLE else View.GONE
    }

    private fun setArrow(id: Int, enabled: Boolean) {
        findViewById<View>(id).apply {
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.38f
        }
    }

    // ---- Summary ----

    private fun renderSummary(key: String, lines: List<FormulaireExport.Line>) {
        val km = lines.sumOf { it.total ?: 0.0 }
        val n = lines.size
        val rows = FormulaireExport.ROWS_PER_FORM
        findViewById<TextView>(R.id.monthKm).text = Fmt.km(km)
        findViewById<TextView>(R.id.monthMoney).text = Fmt.money(km)
        findViewById<TextView>(R.id.monthCaption).text =
            "${Fmt.month(key).substringBefore(' ').lowercase(Fmt.FR)} · ${count(n, "trajet")}"

        val formLines = findViewById<TextView>(R.id.formLines)
        val formFree = findViewById<TextView>(R.id.formFree)
        if (n > rows) {
            formLines.text = strong("${count(n, "ligne")} · ${(n + rows - 1) / rows} formulaires", "")
            formFree.text = ""
        } else {
            formLines.text = strong("${count(n, "ligne")} sur $rows", " du formulaire")
            formFree.text = count(rows - n, "libre")
        }
        findViewById<View>(R.id.formFill).apply {
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                weight = n.coerceAtMost(rows) / rows.toFloat()
            }
        }
    }

    private fun count(n: Int, noun: String) = "$n $noun${if (n > 1) "s" else ""}"

    /** [lead] stands out in the text colour, [rest] stays a muted caption. */
    private fun strong(lead: String, rest: String): CharSequence = buildSpannedString {
        inSpans(
            StyleSpan(Typeface.BOLD),
            ForegroundColorSpan(ContextCompat.getColor(this@TripsActivity, R.color.text))
        ) { append(lead) }
        append(rest)
    }

    private fun chip(id: Int, label: String, selected: Boolean, amber: Boolean) {
        val (background, color) = when {
            selected -> R.drawable.bg_pill_green to R.color.green_text
            amber -> R.drawable.bg_pill_amber to R.color.amber_tint_text
            else -> R.drawable.bg_pill_outline to R.color.text_muted
        }
        val chip = findViewById<View>(id)
        chip.isSelected = selected
        chip.findViewById<View>(R.id.chipPill).setBackgroundResource(background)
        chip.findViewById<View>(R.id.chipCheck).visibility = if (selected) View.VISIBLE else View.GONE
        chip.findViewById<TextView>(R.id.chipText).apply {
            text = label
            setTextColor(ContextCompat.getColor(context, color))
            typeface = ResourcesCompat.getFont(
                context, if (selected) R.font.instrument_bold else R.font.instrument_semibold
            )
        }
    }

    // ---- Days ----

    /** Rebuilds the day groups and returns how many are shown. */
    private fun renderDays(days: Map<String, List<FormulaireExport.Line>>): Int {
        val list = findViewById<LinearLayout>(R.id.days)
        val inflater = LayoutInflater.from(this)
        list.removeAllViews()
        for ((date, dayLines) in days) {
            val shown = if (noPoOnly) dayLines.filter { it.po == null } else dayLines
            if (shown.isEmpty()) continue

            val group = inflater.inflate(R.layout.item_trips_day, list, false)
            group.findViewById<TextView>(R.id.dayLabel).text = Fmt.day(date)
            group.findViewById<TextView>(R.id.dayKm).text = Fmt.km(dayLines.sumOf { it.total ?: 0.0 })
            val card = group.findViewById<LinearLayout>(R.id.dayCard)
            // Keeps the first and last rows' ripples inside the rounded corners.
            card.clipToOutline = true
            val rows = ArrayList<View>()
            shown.forEachIndexed { i, line ->
                if (i > 0) inflater.inflate(R.layout.item_trips_divider, card, true)
                val row = inflater.inflate(R.layout.item_trip_line, card, false)
                TripRow.bind(row, line, onPo = { poSheet.show(it) { reload() } }, onMore = ::showMenu)
                card.addView(row)
                rows.add(row)
            }
            // With the filter on, hidden lines would sit between the visible ones.
            if (!noPoOnly && rows.size > 1) RowDrag(date, card, rows, shown).attach()
            list.addView(group)
        }
        return list.childCount
    }

    private fun showMenu(anchor: View, line: FormulaireExport.Line) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, if (line.po == null) "Ajouter un P.O." else "Modifier le P.O.")
        popup.menu.add(0, 3, 1, "Modifier le trajet")
        popup.menu.add(0, 2, 2, "Supprimer")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> poSheet.show(line) { reload() }
                3 -> tripSheet.edit(line) { day -> month = day.substring(0, 7); reload() }
                else -> confirmDelete(line)
            }
            true
        }
        popup.show()
    }

    /** Opens on today in the current month, on the 1st in an earlier one, then shows the trip's month. */
    private fun addTrip() {
        val today = SimpleDateFormat("yyyy-MM-dd", Fmt.FR).format(Date())
        val shown = month
        val start = if (shown == null || today.startsWith(shown)) today else "$shown-01"
        tripSheet.add(start) { day ->
            month = day.substring(0, 7)
            reload()
        }
    }

    /**
     * Hold a line and slide it among the day's others. Dropped, it takes a
     * time between its new neighbours: the time is what orders a day, and so
     * the form. An aller-retour moves as one.
     */
    private inner class RowDrag(
        private val date: String,
        private val card: LinearLayout,
        private val rows: List<View>,
        private val lines: List<FormulaireExport.Line>
    ) {
        private val density = resources.displayMetrics.density
        /** Where each other row has been sent, so an animation is not restarted. */
        private val offsets = FloatArray(rows.size)
        private var from = -1
        private var target = -1
        private var startY = 0f

        @SuppressLint("ClickableViewAccessibility")
        fun attach() = rows.forEachIndexed { i, row ->
            row.setOnLongClickListener {
                from = i; target = i
                row.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                row.parent.requestDisallowInterceptTouchEvent(true)
                row.elevation = 8 * density
                row.setBackgroundColor(ContextCompat.getColor(this@TripsActivity, R.color.surface))
                // Rows of different heights slide past dividers that stay put.
                for (c in 0 until card.childCount) card.getChildAt(c).let { if (it !in rows) it.alpha = 0f }
                true
            }
            row.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { startY = e.rawY; false }
                    MotionEvent.ACTION_MOVE -> if (from == i) { follow(e.rawY - startY); true } else false
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        if (from == i) { drop(); true } else false
                    else -> from == i
                }
            }
        }

        private fun follow(rawDy: Float) {
            val dragged = rows[from]
            val dy = rawDy.coerceIn(
                (rows.first().top - dragged.top).toFloat(), (rows.last().bottom - dragged.bottom).toFloat())
            dragged.translationY = dy
            val center = dragged.top + dragged.height / 2f + dy
            val slot = dragged.height + density   // the row and its 1dp divider
            var moved = 0
            rows.forEachIndexed { j, other ->
                if (j == from) return@forEachIndexed
                val mid = other.top + other.height / 2f
                val shift = when {
                    j > from && mid < center -> -slot
                    j < from && mid > center -> slot
                    else -> 0f
                }
                if (shift != 0f) moved += if (j > from) 1 else -1
                if (offsets[j] != shift) {
                    offsets[j] = shift
                    other.animate().translationY(shift).setDuration(120).start()
                }
            }
            target = from + moved
        }

        private fun drop() {
            val moving = lines[from]
            val order = lines.toMutableList().apply { removeAt(from); add(target, moving) }
            from = -1
            if (order == lines) { render(); return }

            fun lastTs(l: FormulaireExport.Line) = l.back?.ts ?: l.trip.ts
            val dayStart = SimpleDateFormat("yyyy-MM-dd", Fmt.FR).parse(date)!!.time
            val prev = order.getOrNull(target - 1)?.let(::lastTs)
            val next = order.getOrNull(target + 1)?.trip?.ts
            // First or last of the day: a minute before or after its neighbour.
            val lo = prev ?: (next!! - 120_000).coerceAtLeast(dayStart - 1)
            val hi = next ?: (lo + 120_000).coerceAtMost(dayStart + 86_400_000)
            val legs = if (moving.back != null) 2 else 1
            if (hi - lo <= legs) {
                Toast.makeText(this@TripsActivity, "Pas de place entre ces deux trajets.", Toast.LENGTH_SHORT).show()
                render(); return
            }
            val at = lo + (hi - lo) / 2
            val moves = mutableMapOf(moving.trip.ts to at)
            moving.back?.let { moves[it.ts] = at + 1 }
            store.retime(moves)
            render()
        }
    }

    private fun confirmDelete(line: FormulaireExport.Line) {
        val back = line.back
        val message =
            if (back == null) "Supprimer le trajet ${TripRow.route(line)} ?"
            else "Supprimer l'aller-retour ${TripRow.route(line)} ? Les deux trajets seront supprimés."
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("Supprimer") { _, _ ->
                store.delete(line.trip.ts)
                back?.let { store.delete(it.ts) }
                render()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ---- Export ----

    /** One filled form per 25 lines, straight to the share sheet. */
    private fun shareFormulaire() {
        val key = month ?: return
        try {
            FormulaireExport.exportAndShare(this, store.tripsForMonth(key), key)
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setMessage("Export impossible : ${e.message}")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun csvDialog() {
        val key = month ?: return
        AlertDialog.Builder(this)
            .setTitle("Exporter en CSV")
            .setItems(arrayOf("Ce mois", "Tous les trajets")) { _, which ->
                csvMonth = if (which == 0) key else null
                csvLauncher.launch(if (which == 0) "trajets_$key.csv" else "trajets_tous.csv")
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
