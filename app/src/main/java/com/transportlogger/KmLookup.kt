package com.transportlogger

import android.content.Context
import org.json.JSONObject
import java.text.Normalizer

/** Reads assets/km_lookup.json once; returns km for an origin→destination pair. */
object KmLookup {
    private var data: Map<String, Map<String, Double>>? = null

    private val MARKS = Regex("\\p{M}+")
    private val SPACES = Regex("\\s+")

    /**
     * The board's matrix spells a place differently on its two axes (accents,
     * doubled spaces, case), so names are compared with all of that removed.
     */
    internal fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(MARKS, "")
            .replace(SPACES, "")
            .uppercase()

    private fun ensure(ctx: Context): Map<String, Map<String, Double>> {
        data?.let { return it }
        val loaded = HashMap<String, Map<String, Double>>()
        try {
            val json = ctx.assets.open("km_lookup.json").bufferedReader().use { JSONObject(it.readText()) }
            for (origin in json.keys()) {
                val row = json.getJSONObject(origin)
                val out = HashMap<String, Double>()
                for (destination in row.keys()) out[norm(destination)] = row.getDouble(destination)
                loaded[norm(origin)] = out
            }
        } catch (e: Exception) {
            // missing or unreadable file -> km simply comes back null
        }
        data = loaded
        return loaded
    }

    fun km(ctx: Context, origin: String, destination: String): Double? =
        ensure(ctx)[norm(origin)]?.get(norm(destination))
}
