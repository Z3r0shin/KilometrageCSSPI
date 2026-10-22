package com.transportlogger

import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * v0.5 — fetches real property outlines from OpenStreetMap (Overpass API).
 * For a school, OSM usually maps BOTH the building and the whole grounds
 * (amenity=school area). We prefer the grounds — parking included — which is
 * exactly what geofencing wants. No API key required; be polite (batched
 * queries, ~1 req/s). Call OFF the main thread.
 *
 * Every returned polygon is inflated by BUFFER_M metres around its centroid
 * so ordinary GPS jitter at the curb still counts as "inside".
 */
object OsmFootprint {

    private const val ENDPOINT = "https://overpass-api.de/api/interpreter"
    private const val BUFFER_M = 12.0
    private const val NEAR_M = 80.0     // fallback: nearest footprint within this distance

    class Found(val poly: List<DoubleArray>, val school: Boolean)
    class Result(val found: Map<String, Found>, val queried: Set<String>)

    /** Footprint for one point, or null. */
    fun fetch(lat: Double, lng: Double): Found? =
        fetchAll(listOf(Triple("x", lat, lng))).found["x"]

    /**
     * Footprints for many zones in few requests (chunks of 20).
     * `queried` = zones whose chunk got a real answer from Overpass (so a
     * miss there means "OSM has nothing", not "network failed").
     * onProgress(done, total) is called from the worker thread.
     */
    fun fetchAll(
        centers: List<Triple<String, Double, Double>>,
        onProgress: ((Int, Int) -> Unit)? = null
    ): Result {
        val out = mutableMapOf<String, Found>()
        val queried = mutableSetOf<String>()
        val chunks = centers.chunked(20)
        var done = 0
        for ((ci, chunk) in chunks.withIndex()) {
            try {
                val ways = query(chunk)
                for ((name, lat, lng) in chunk) {
                    queried.add(name)
                    val w = pickFootprint(ways, lat, lng)
                    if (w != null) out[name] = Found(inflate(w.pts), w.isSchoolGrounds)
                }
            } catch (e: Exception) { /* network/rate-limit: chunk not marked queried */ }
            done += chunk.size
            onProgress?.invoke(done, centers.size)
            if (ci < chunks.size - 1) Thread.sleep(1500)   // stay polite with Overpass
        }
        return Result(out, queried)
    }

    // ---- internals ----

    private class Way(val tags: JSONObject, val pts: List<DoubleArray>) {
        val isSchoolGrounds get() =
            tags.optString("amenity") == "school" || tags.optString("landuse") == "education"
        val isBuilding get() = tags.has("building")
        fun centroid(): DoubleArray {
            var la = 0.0; var lo = 0.0
            pts.forEach { la += it[0]; lo += it[1] }
            return doubleArrayOf(la / pts.size, lo / pts.size)
        }
    }

    private fun query(chunk: List<Triple<String, Double, Double>>): List<Way> {
        val sb = StringBuilder("[out:json][timeout:40];(")
        for ((_, lat, lng) in chunk) {
            sb.append("way(around:100,$lat,$lng)[\"amenity\"=\"school\"];")
            sb.append("way(around:100,$lat,$lng)[\"landuse\"=\"education\"];")
            sb.append("way(around:35,$lat,$lng)[\"building\"];")
        }
        sb.append(");out geom;")

        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 50_000
        conn.setRequestProperty("User-Agent", "TransportLogger/0.5")
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
            it.write("data=" + java.net.URLEncoder.encode(sb.toString(), "UTF-8"))
        }
        val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        conn.disconnect()

        val elements = JSONObject(body).optJSONArray("elements") ?: return emptyList()
        val ways = mutableListOf<Way>()
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            if (el.optString("type") != "way") continue
            val geom = el.optJSONArray("geometry") ?: continue
            if (geom.length() < 3) continue
            val pts = (0 until geom.length()).map { j ->
                val g = geom.getJSONObject(j)
                doubleArrayOf(g.getDouble("lat"), g.getDouble("lon"))
            }
            ways.add(Way(el.optJSONObject("tags") ?: JSONObject(), pts))
        }
        return ways
    }

    /**
     * Best footprint for a point: school grounds containing it, else a
     * building containing it, else the nearest school grounds within NEAR_M,
     * else the nearest building within NEAR_M.
     */
    private fun pickFootprint(ways: List<Way>, lat: Double, lng: Double): Way? {
        val containing = ways.filter { PlaceStore.polygonContains(it.pts, lat, lng) }
        containing.firstOrNull { it.isSchoolGrounds }?.let { return it }
        containing.firstOrNull { it.isBuilding }?.let { return it }

        fun nearest(pred: (Way) -> Boolean): Way? = ways.filter(pred).minByOrNull {
            val c = it.centroid(); PlaceStore.distanceMeters(lat, lng, c[0], c[1])
        }?.takeIf {
            val c = it.centroid(); PlaceStore.distanceMeters(lat, lng, c[0], c[1]) <= NEAR_M
        }
        nearest { it.isSchoolGrounds }?.let { return it }
        nearest { it.isBuilding }?.let { return it }
        return null
    }

    /** Push every vertex BUFFER_M metres away from the centroid (GPS-jitter margin). */
    private fun inflate(poly: List<DoubleArray>): List<DoubleArray> {
        var cla = 0.0; var clo = 0.0
        poly.forEach { cla += it[0]; clo += it[1] }
        cla /= poly.size; clo /= poly.size
        val mPerLat = 111_320.0
        val mPerLng = 111_320.0 * Math.cos(Math.toRadians(cla))
        return poly.map { p ->
            val dn = (p[0] - cla) * mPerLat
            val de = (p[1] - clo) * mPerLng
            val d = Math.hypot(dn, de)
            if (d < 1.0) p
            else {
                val f = (d + BUFFER_M) / d
                doubleArrayOf(cla + dn * f / mPerLat, clo + de * f / mPerLng)
            }
        }
    }
}
