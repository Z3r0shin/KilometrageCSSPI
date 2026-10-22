package com.transportlogger

import android.view.View
import android.widget.TextView

/**
 * Fills one `item_trip_line` row. Aujourd'hui and Trajets both list trips the
 * way the claim form does: an aller-retour is a single line.
 */
internal object TripRow {

    /** "A → B", or "A ⇄ B" for an aller-retour. */
    fun route(line: FormulaireExport.Line): String =
        "${line.trip.depart} ${if (line.legs == 2) "⇄" else "→"} ${line.trip.arrivee}"

    /**
     * @param onPo tapped on the row or on "Ajouter le P.O."
     * @param onMore tapped on the overflow button; null hides the button
     */
    fun bind(
        v: View,
        line: FormulaireExport.Line,
        onPo: (FormulaireExport.Line) -> Unit,
        onMore: ((View, FormulaireExport.Line) -> Unit)? = null
    ) {
        val paired = line.legs == 2
        v.findViewById<TextView>(R.id.lineTime).text = Fmt.time(line.trip.ts)
        v.findViewById<TextView>(R.id.lineRoute).text = route(line)

        v.findViewById<TextView>(R.id.lineTag).visibility = if (paired) View.VISIBLE else View.GONE
        val po = v.findViewById<TextView>(R.id.linePo)
        po.text = line.po?.let { "P.O. $it" } ?: ""
        po.visibility = if (line.po != null) View.VISIBLE else View.GONE
        v.findViewById<View>(R.id.lineMeta).visibility =
            if (paired || line.po != null) View.VISIBLE else View.GONE

        val add = v.findViewById<TextView>(R.id.lineAddPo)
        add.visibility = if (line.po == null) View.VISIBLE else View.GONE
        add.setOnClickListener { onPo(line) }
        v.setOnClickListener { onPo(line) }

        v.findViewById<TextView>(R.id.lineKm).text = Fmt.km(line.total)
        val detail = v.findViewById<TextView>(R.id.lineKmDetail)
        val km = line.km
        detail.visibility = if (paired && km != null) View.VISIBLE else View.GONE
        if (km != null) detail.text = "2 × ${Fmt.km(km).removeSuffix(" km")}"

        val more = v.findViewById<View>(R.id.lineMore)
        more.visibility = if (onMore != null) View.VISIBLE else View.GONE
        more.setOnClickListener { onMore?.invoke(it, line) }
    }
}
