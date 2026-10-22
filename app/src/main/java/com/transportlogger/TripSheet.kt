package com.transportlogger

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Filter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

/**
 * The bottom sheet that adds a trip by hand (a stop the phone missed, or a
 * place it has no zone for) or corrects a logged one. Either end can be a
 * place of the list or any name typed in; the kilometres come from the board's
 * table when both ends are in it, and are typed otherwise. An aller-retour
 * keeps its return leg right after the outbound, so the form folds the two
 * into one line.
 */
internal class TripSheet(private val activity: AppCompatActivity) {

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Fmt.FR)

    /** A new trip on [date] (yyyy-MM-dd); [onSaved] gets the day it went to. */
    fun add(date: String, onSaved: (String) -> Unit) = open(null, date, onSaved)

    /** Corrects [line], both legs of an aller-retour; its P.O. and status stay. */
    fun edit(line: FormulaireExport.Line, onSaved: (String) -> Unit) = open(line, line.trip.date, onSaved)

    private fun open(line: FormulaireExport.Line?, date: String, onSaved: (String) -> Unit) {
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_trip, null)
        dialog.setContentView(view)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        if (line != null) {
            view.findViewById<TextView>(R.id.tripTitle).text = "Modifier le trajet"
            view.findViewById<TextView>(R.id.btnSaveTrip).text = "Enregistrer"
        }

        // A new trip opens on the current time today, on noon another day.
        val at = Calendar.getInstance()
        if (line != null) at.timeInMillis = line.trip.ts
        else if (date != dayFormat.format(Date())) {
            dayFormat.parse(date)?.let { at.time = it }
            at.set(Calendar.HOUR_OF_DAY, 12)
            at.set(Calendar.MINUTE, 0)
        }
        if (line == null) {
            at.set(Calendar.SECOND, 0)
            at.set(Calendar.MILLISECOND, 0)
        }
        // An edited trip keeps its exact time, and so its place in the day,
        // unless the date or the time is changed.
        var retimed = false

        val dateField = view.findViewById<TextView>(R.id.tripDate)
        val timeField = view.findViewById<TextView>(R.id.tripTime)
        fun showWhen() {
            dateField.text = Fmt.day(dayFormat.format(at.time))
            timeField.text = Fmt.time(at.timeInMillis)
        }
        showWhen()
        dateField.setOnClickListener {
            DatePickerDialog(activity, { _, y, m, d -> at.set(y, m, d); retimed = true; showWhen() },
                at.get(Calendar.YEAR), at.get(Calendar.MONTH), at.get(Calendar.DAY_OF_MONTH)).show()
        }
        timeField.setOnClickListener {
            TimePickerDialog(activity, { _, h, min ->
                at.set(Calendar.HOUR_OF_DAY, h); at.set(Calendar.MINUTE, min)
                at.set(Calendar.SECOND, 0); at.set(Calendar.MILLISECOND, 0)
                retimed = true; showWhen()
            }, at.get(Calendar.HOUR_OF_DAY), at.get(Calendar.MINUTE), true).show()
        }

        // Home legs are never logged, so home is not offered.
        val names = PlaceStore(activity).places.filterNot { it.isHome }.map { it.name }
            .sortedBy { KmLookup.norm(it) }
        val from = view.findViewById<AutoCompleteTextView>(R.id.tripFrom)
        val to = view.findViewById<AutoCompleteTextView>(R.id.tripTo)
        from.setAdapter(PlaceAdapter(activity, names))
        to.setAdapter(PlaceAdapter(activity, names))

        val km = view.findViewById<EditText>(R.id.tripKm)
        val kmHint = view.findViewById<TextView>(R.id.tripKmHint)
        val back = view.findViewById<CheckBox>(R.id.tripReturn)
        val save = view.findViewById<View>(R.id.btnSaveTrip)

        // A name typed with other accents or case is taken as the listed place.
        fun canonical(field: TextView): String {
            val typed = field.text.toString().trim()
            return names.firstOrNull { KmLookup.norm(it) == KmLookup.norm(typed) } ?: typed
        }

        // The table fills the kilometres until they are typed by hand.
        var kmTyped = false
        var filling = false
        fun setKm(value: Double?) {
            filling = true
            km.setText(value?.let { Fmt.km(it).removeSuffix(" km") } ?: "")
            filling = false
        }
        fun validate() {
            val a = canonical(from); val b = canonical(to)
            save.isEnabled = a.isNotEmpty() && b.isNotEmpty() &&
                KmLookup.norm(a) != KmLookup.norm(b) &&
                km.text.toString().replace(',', '.').toDoubleOrNull()?.let { it >= 0 } == true
        }
        fun lookUp() {
            val a = canonical(from); val b = canonical(to)
            val table = if (a.isEmpty() || b.isEmpty()) null else KmLookup.km(activity, a, b)
            if (!kmTyped) setKm(table)
            kmHint.text = when {
                a.isEmpty() || b.isEmpty() -> "Rempli d'après la table des distances quand elle a les deux lieux."
                table == null -> "Hors de la table des distances : entrez les kilomètres."
                kmTyped -> "La table donne ${Fmt.km(table)}."
                else -> "D'après la table des distances."
            }
            validate()
        }

        if (line != null) {
            from.setText(line.trip.depart, false)
            to.setText(line.trip.arrivee, false)
            back.isChecked = line.back != null
            // Kilometres that differ from the table were typed: keep them.
            val table = KmLookup.km(activity, line.trip.depart, line.trip.arrivee)
            kmTyped = line.km != null && line.km != table
            setKm(line.km ?: table)
        }
        from.doAfterTextChanged { lookUp() }
        to.doAfterTextChanged { lookUp() }
        km.doAfterTextChanged {
            if (!filling) kmTyped = it.toString().isNotBlank()
            if (!filling && !kmTyped) lookUp() else validate()
        }
        lookUp()

        view.findViewById<View>(R.id.btnCancelTrip).setOnClickListener { dialog.dismiss() }
        save.setOnClickListener {
            val a = canonical(from); val b = canonical(to)
            val outKm = km.text.toString().replace(',', '.').toDoubleOrNull() ?: return@setOnClickListener
            val day = dayFormat.format(at.time)
            val ts = if (line != null && !retimed) line.trip.ts else at.timeInMillis
            val status = line?.trip?.status ?: "CONFIRMÉ"
            val out = Trip(ts, day, a, b, outKm, status, line?.po)
            val legs = if (back.isChecked) {
                // The form reads an aller-retour by its outbound km; the return
                // keeps the table's own figure unless the km were typed. It
                // follows straight after, so no other trip comes between.
                val backKm = if (kmTyped) outKm else KmLookup.km(activity, b, a) ?: outKm
                val backTs = line?.back?.ts?.takeIf { !retimed } ?: (ts + 1)
                arrayOf(out, Trip(backTs, day, b, a, backKm, status, line?.po))
            } else arrayOf(out)
            val store = TripStore(activity)
            if (line == null) store.insert(*legs)
            else store.replace(listOfNotNull(line.trip.ts, line.back?.ts), *legs)
            dialog.dismiss()
            onSaved(day)
        }
        dialog.show()
    }

    /** Suggests listed places containing what is typed, accents, case and spaces aside. */
    private class PlaceAdapter(ctx: Context, private val all: List<String>) :
        ArrayAdapter<String>(ctx, R.layout.item_trip_suggestion, ArrayList(all)) {

        private val filter = object : Filter() {
            override fun performFiltering(q: CharSequence?): FilterResults {
                val key = KmLookup.norm(q?.toString().orEmpty())
                val hits = if (key.isEmpty()) all else all.filter { KmLookup.norm(it).contains(key) }
                return FilterResults().apply { values = hits; count = hits.size }
            }

            override fun publishResults(q: CharSequence?, r: FilterResults) {
                clear()
                @Suppress("UNCHECKED_CAST")
                addAll(r.values as List<String>)
                notifyDataSetChanged()
            }
        }

        override fun getFilter(): Filter = filter
    }
}
