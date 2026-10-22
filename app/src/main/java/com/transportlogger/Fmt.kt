package com.transportlogger

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Display formats shared by every screen, in Canadian French. */
object Fmt {
    val FR: Locale = Locale.CANADA_FRENCH

    /** "4 km", "5,5 km", or a dash when the distance is unknown. */
    fun km(km: Double?): String = when {
        km == null -> "— km"
        km % 1.0 == 0.0 -> "${km.toLong()} km"
        else -> String.format(FR, "%.1f km", km)
    }

    /** The claim value of a distance at the form rate: "8,45 $". */
    fun money(km: Double): String =
        String.format(FR, "%.2f $", km * FormulaireExport.RATE_PER_KM)

    fun time(ts: Long): String = SimpleDateFormat("HH:mm", FR).format(Date(ts))

    /** "2026-10-06" -> "Mardi 6 octobre". */
    fun day(date: String): String = try {
        SimpleDateFormat("EEEE d MMMM", FR).format(SimpleDateFormat("yyyy-MM-dd", FR).parse(date)!!)
            .replaceFirstChar { it.uppercase(FR) }
    } catch (e: Exception) { date }

    /** "2026-10" -> "Octobre 2026". */
    fun month(key: String): String = try {
        SimpleDateFormat("MMMM yyyy", FR).format(SimpleDateFormat("yyyy-MM", FR).parse(key)!!)
            .replaceFirstChar { it.uppercase(FR) }
    } catch (e: Exception) { key }
}
