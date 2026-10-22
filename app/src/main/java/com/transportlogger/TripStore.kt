package com.transportlogger

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Trip(
    val ts: Long,
    val date: String,      // yyyy-MM-dd
    val depart: String,
    val arrivee: String,
    val km: Double?,
    val status: String,    // "CONFIRMÉ" | "PAR DÉFAUT"
    val po: String? = null // work-request number, scanned or typed; null until assigned
) {
    /** "2026-07" — grouping key for the monthly view/export. */
    val monthKey: String get() = if (date.length >= 7) date.substring(0, 7) else date
}

/**
 * Persists logged trips as JSON in filesDir.
 * v0.4 adds: delete a single trip, toggle a trip's status, list of months,
 * and CSV export filtered by month (the Formulaire is monthly).
 */
class TripStore(ctx: Context) {

    private companion object {
        /** One lock for every instance: they all share trips.json. */
        val LOCK = Any()
    }

    private val file = File(ctx.filesDir, "trips.json")
    val trips = mutableListOf<Trip>()

    init { reload() }

    /** Re-read the file, picking up what other instances have written. */
    fun reload() = synchronized(LOCK) { load() }

    /**
     * The service and each screen hold their own instance over the same file,
     * so a change re-reads it first instead of saving this instance's snapshot
     * over a trip another one logged in the meantime.
     */
    private inline fun mutate(change: () -> Unit) = synchronized(LOCK) {
        load()
        change()
        save()
    }

    private fun load() {
        trips.clear()
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                trips.add(
                    Trip(
                        o.optLong("ts"),
                        o.getString("date"),
                        o.getString("depart"),
                        o.getString("arrivee"),
                        if (o.isNull("km")) null else o.optDouble("km"),
                        o.getString("status"),
                        // optString() turns a JSON null into the text "null".
                        if (o.isNull("po")) null else o.optString("po").ifBlank { null }
                    )
                )
            }
        } catch (e: Exception) { /* corrupted -> empty */ }
    }

    private fun save() {
        val arr = JSONArray()
        trips.forEach {
            arr.put(
                JSONObject()
                    .put("ts", it.ts)
                    .put("date", it.date)
                    .put("depart", it.depart)
                    .put("arrivee", it.arrivee)
                    .put("km", it.km ?: JSONObject.NULL)
                    .put("status", it.status)
                    .put("po", it.po ?: JSONObject.NULL)
            )
        }
        file.writeText(arr.toString())
    }

    fun add(depart: String, arrivee: String, km: Double?, status: String) {
        val now = Date()
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.CANADA).format(now)
        mutate { trips.add(Trip(now.time, date, depart, arrivee, km, status)) }
    }

    /**
     * Trips entered by hand. Deleting and the P.O. find a trip by its ts, so
     * each is nudged off any ts already taken.
     */
    fun insert(vararg added: Trip) = mutate { added.forEach(::addUnique) }

    /** An edited line: the trips at [oldTs] give way to [added], one or two legs. */
    fun replace(oldTs: Collection<Long>, vararg added: Trip) = mutate {
        trips.removeAll { it.ts in oldTs }
        added.forEach(::addUnique)
    }

    /** New times for some trips, old ts to new ts. The time is what orders a day. */
    fun retime(moves: Map<Long, Long>) = mutate {
        for (i in trips.indices) moves[trips[i].ts]?.let { trips[i] = trips[i].copy(ts = it) }
    }

    private fun addUnique(t: Trip) {
        var ts = t.ts
        while (trips.any { it.ts == ts }) ts++
        trips.add(t.copy(ts = ts))
    }

    fun delete(ts: Long) = mutate { trips.removeAll { it.ts == ts } }

    /** Assign (or clear, with null/blank) the work-request number of one trip. */
    fun setPo(ts: Long, po: String?) = mutate {
        val i = trips.indexOfFirst { it.ts == ts }
        if (i >= 0) trips[i] = trips[i].copy(po = po?.trim()?.ifBlank { null })
    }

    fun toggleStatus(ts: Long) = mutate {
        val i = trips.indexOfFirst { it.ts == ts }
        if (i >= 0) {
            val t = trips[i]
            trips[i] = t.copy(status = if (t.status == "CONFIRMÉ") "PAR DÉFAUT" else "CONFIRMÉ")
        }
    }

    fun clear() = mutate { trips.clear() }

    /** The last few distinct P.O. numbers used, newest first. */
    fun recentPos(limit: Int = 3): List<String> =
        trips.sortedByDescending { it.ts }.mapNotNull { it.po }.distinct().take(limit)

    /** Distinct months, most recent first, e.g. ["2026-07", "2026-06"]. */
    fun months(): List<String> =
        trips.map { it.monthKey }.distinct().sortedDescending()

    fun tripsForMonth(monthKey: String): List<Trip> =
        trips.filter { it.monthKey == monthKey }

    /** CSV in the exact format csv_to_formulaire.py expects. Null month = everything. */
    fun toCsv(monthKey: String? = null): String {
        val rows = if (monthKey == null) trips else tripsForMonth(monthKey)
        val sb = StringBuilder("date,depart,arrivee,km,statut\n")
        for (t in rows.sortedBy { it.ts }) {
            sb.append(esc(t.date)).append(',')
                .append(esc(t.depart)).append(',')
                .append(esc(t.arrivee)).append(',')
                .append(t.km?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() } ?: "").append(',')
                .append(esc(t.status)).append('\n')
        }
        return sb.toString()
    }

    private fun esc(s: String): String =
        if (s.contains(',') || s.contains('"')) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
