package com.transportlogger

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v0.5 — zones can now be three shapes, checked in this order:
 *  1. polygon  : real property outline fetched from OpenStreetMap (list of lat/lng)
 *  2. rectangle: centre + width (m) + height (m) + bearing (°), fully editable
 *  3. circle   : legacy fallback (radius in m) for untouched old zones
 * `address` is cached for display (reverse-geocoded lazily).
 */
data class Place(
    val name: String,
    val lat: Double,
    val lng: Double,
    val radius: Double,               // legacy circle fallback (m)
    val isHome: Boolean = false,
    val address: String? = null,
    val rw: Double? = null,           // rectangle width in metres
    val rh: Double? = null,           // rectangle height in metres
    val rb: Double = 0.0,             // rectangle bearing in degrees (0 = north-aligned)
    val polygon: List<DoubleArray>? = null,  // OSM footprint: [[lat,lng], ...]
    val school: Boolean = false,      // tagged amenity=school on OSM (or by name)
    val osmTried: Boolean = false     // OSM was queried and answered (found or not)
) {
    /** The drawable/checkable outline, or null if this zone is still a circle. */
    fun outline(): List<DoubleArray>? = when {
        polygon != null && polygon.size >= 3 -> polygon
        rw != null && rh != null -> PlaceStore.rectCorners(lat, lng, rw, rh, rb)
        else -> null
    }

    /**
     * [margin] widens the shape by that many metres. Outlines follow the
     * property line, or just the building, while GPS drifts indoors and people
     * park at the kerb: without tolerance a real stop reads as outside.
     */
    fun contains(pLat: Double, pLng: Double, margin: Double = 0.0): Boolean {
        val o = outline()
        return if (o != null) PlaceStore.polygonContains(o, pLat, pLng) ||
            (margin > 0 && PlaceStore.distanceToOutline(o, pLat, pLng) <= margin)
        else PlaceStore.distanceMeters(pLat, pLng, lat, lng) <= radius + margin
    }

    /** One-line shape description for the UI. */
    fun shapeLabel(): String = when {
        polygon != null -> "terrain OSM (${polygon.size} pts)"
        rw != null && rh != null -> "${rw.toInt()} × ${rh.toInt()} m"
        else -> "cercle ${radius.toInt()} m"
    }
}

class PlaceStore(private val ctx: Context) {

    private val file = File(ctx.filesDir, "places.json")
    val places = mutableListOf<Place>()

    /** Which version of the file [places] holds: its modification time and size. */
    private var loaded = 0L to -1L

    init { load() }

    /**
     * Re-read the file if another instance changed it since. The service holds
     * one store for its whole life, so a zone added or moved on a screen would
     * otherwise go unseen until it restarted.
     */
    fun refresh() {
        if (file.lastModified() to file.length() != loaded) load()
    }

    private fun load() {
        if (!file.exists()) seed()
        if (!file.exists()) { places.clear(); loaded = 0L to -1L; return }
        val stamp = file.lastModified() to file.length()
        val read = mutableListOf<Place>()
        try {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val poly = o.optJSONArray("polygon")?.let { pa ->
                    (0 until pa.length()).map { j ->
                        val pt = pa.getJSONArray(j)
                        doubleArrayOf(pt.getDouble(0), pt.getDouble(1))
                    }
                }
                read.add(
                    Place(
                        o.getString("name"),
                        o.getDouble("lat"),
                        o.getDouble("lng"),
                        o.optDouble("radius", Settings.defaultRadius(ctx).toDouble()),
                        o.optBoolean("isHome", false),
                        o.optString("address").ifBlank { null },
                        if (o.has("rw")) o.getDouble("rw") else null,
                        if (o.has("rh")) o.getDouble("rh") else null,
                        o.optDouble("rb", 0.0),
                        poly,
                        o.optBoolean("school", false),
                        o.optBoolean("osmTried", false)
                    )
                )
            }
        } catch (e: Exception) {
            // Corrupted, or caught halfway through a write: keep what we had
            // (nothing, at start-up) and try again on the next refresh.
            return
        }
        places.clear()
        places.addAll(read)
        loaded = stamp
    }

    // A new user starts with the board's places; nobody's home is among them.
    // Copied aside then renamed, so a launch killed mid-copy cannot leave a
    // truncated places.json that would never be seeded again.
    private fun seed() {
        val tmp = File(ctx.filesDir, "places.json.tmp")
        try {
            ctx.assets.open("places_seed.json").use { src -> tmp.outputStream().use { src.copyTo(it) } }
            tmp.renameTo(file)
        } catch (e: Exception) { /* no seed -> start empty */ }
    }

    private fun save() {
        val arr = JSONArray()
        places.forEach { p ->
            val o = JSONObject()
                .put("name", p.name)
                .put("lat", p.lat)
                .put("lng", p.lng)
                .put("radius", p.radius)
                .put("isHome", p.isHome)
                .put("rb", p.rb)
                .put("school", p.school)
                .put("osmTried", p.osmTried)
            p.address?.let { o.put("address", it) }
            p.rw?.let { o.put("rw", it) }
            p.rh?.let { o.put("rh", it) }
            p.polygon?.let { poly ->
                val pa = JSONArray()
                poly.forEach { pt -> pa.put(JSONArray().put(pt[0]).put(pt[1])) }
                o.put("polygon", pa)
            }
            arr.put(o)
        }
        file.writeText(arr.toString())
        loaded = file.lastModified() to file.length()
    }

    /** Pretty JSON dump of every zone — for export/backup. */
    fun exportJson(): String {
        save()
        return try { JSONArray(file.readText()).toString(2) } catch (e: Exception) { "[]" }
    }

    fun addOrReplace(p: Place) {
        refresh()
        val i = places.indexOfFirst { it.name.equals(p.name, ignoreCase = true) }
        if (i >= 0) places[i] = p else places.add(p)
        save()
    }

    fun remove(name: String) { refresh(); places.removeAll { it.name == name }; save() }

    private inline fun update(name: String, f: (Place) -> Place) {
        refresh()
        val i = places.indexOfFirst { it.name == name }
        if (i >= 0) { places[i] = f(places[i]); save() }
    }

    /** Manual rectangle: clears any OSM polygon (the user takes over). */
    fun setRect(name: String, w: Double, h: Double, bearing: Double) =
        update(name) { it.copy(rw = w, rh = h, rb = bearing, polygon = null) }

    fun setPolygon(name: String, poly: List<DoubleArray>, school: Boolean = false) =
        update(name) { it.copy(polygon = poly, school = it.school || school, osmTried = true) }

    fun markOsmTried(name: String) = update(name) { it.copy(osmTried = true) }

    /**
     * Move a zone to a corrected position: it becomes a default square there,
     * its old outline dropped, until its OSM footprint is fetched again.
     */
    fun move(name: String, lat: Double, lng: Double, address: String?, side: Double) =
        update(name) {
            it.copy(lat = lat, lng = lng, address = address, radius = side, rw = side, rh = side,
                rb = 0.0, polygon = null, school = false, osmTried = false)
        }

    /** Merge zones from an exported/hand-compiled JSON (same schema as places.json). */
    fun importJson(json: String): Int {
        val arr = org.json.JSONArray(json)
        var n = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val poly = o.optJSONArray("polygon")?.let { pa ->
                (0 until pa.length()).map { j ->
                    val pt = pa.getJSONArray(j)
                    doubleArrayOf(pt.getDouble(0), pt.getDouble(1))
                }
            }
            addOrReplace(
                Place(
                    o.getString("name"), o.getDouble("lat"), o.getDouble("lng"),
                    o.optDouble("radius", Settings.defaultRadius(ctx).toDouble()),
                    o.optBoolean("isHome", false),
                    o.optString("address").ifBlank { null },
                    if (o.has("rw")) o.getDouble("rw") else null,
                    if (o.has("rh")) o.getDouble("rh") else null,
                    o.optDouble("rb", 0.0),
                    poly,
                    o.optBoolean("school", false),
                    o.optBoolean("osmTried", poly != null)
                )
            )
            n++
        }
        return n
    }

    fun setAddress(name: String, address: String) =
        update(name) { it.copy(address = address) }

    /**
     * Bulk-resize: every zone WITHOUT an OSM footprint becomes a square of
     * side `size` m (old circles included — they finally become rectangles).
     */
    fun applySizeToAll(size: Double) {
        refresh()
        for (i in places.indices) {
            val p = places[i]
            if (p.polygon != null) continue
            places[i] = p.copy(rw = size, rh = size, radius = size)
        }
        save()
    }

    fun placeByName(name: String?): Place? = places.firstOrNull { it.name == name }

    /** Nearest zone (by centre) whose shape, widened by [margin] metres, contains the point, or null. */
    fun nearestContaining(lat: Double, lng: Double, margin: Double = 0.0): Pair<Place, Double>? {
        var best: Place? = null
        var bestD = Double.MAX_VALUE
        for (p in places) {
            if (!p.contains(lat, lng, margin)) continue
            val d = distanceMeters(lat, lng, p.lat, p.lng)
            if (d < bestD) { best = p; bestD = d }
        }
        return best?.let { it to bestD }
    }

    companion object {
        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                    Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                    Math.sin(dLon / 2) * Math.sin(dLon / 2)
            return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        }

        /** Corners of a rotated rectangle centred on (lat,lng). w/h metres, bearing degrees. */
        fun rectCorners(lat: Double, lng: Double, w: Double, h: Double, bearingDeg: Double): List<DoubleArray> {
            val t = Math.toRadians(bearingDeg)
            val cos = Math.cos(t); val sin = Math.sin(t)
            val mPerLat = 111_320.0
            val mPerLng = 111_320.0 * Math.cos(Math.toRadians(lat))
            val hw = w / 2.0; val hh = h / 2.0
            val local = listOf(
                doubleArrayOf(-hw, hh), doubleArrayOf(hw, hh),
                doubleArrayOf(hw, -hh), doubleArrayOf(-hw, -hh)
            )
            return local.map { (x, y) ->
                val e = x * cos - y * sin      // metres east
                val n = x * sin + y * cos      // metres north
                doubleArrayOf(lat + n / mPerLat, lng + e / mPerLng)
            }
        }

        /** Ray-casting point-in-polygon on lat/lng (fine at building scale). */
        fun polygonContains(poly: List<DoubleArray>, lat: Double, lng: Double): Boolean {
            var inside = false
            var j = poly.size - 1
            for (i in poly.indices) {
                val yi = poly[i][0]; val xi = poly[i][1]
                val yj = poly[j][0]; val xj = poly[j][1]
                if ((yi > lat) != (yj > lat) &&
                    lng < (xj - xi) * (lat - yi) / (yj - yi) + xi
                ) inside = !inside
                j = i
            }
            return inside
        }

        /** Metres from a point to the nearest edge of an outline (flat-earth, fine at this scale). */
        fun distanceToOutline(poly: List<DoubleArray>, lat: Double, lng: Double): Double {
            val mPerLat = 111_320.0
            val mPerLng = 111_320.0 * Math.cos(Math.toRadians(lat))
            var best = Double.MAX_VALUE
            var j = poly.size - 1
            for (i in poly.indices) {
                // The edge a -> b in metres, with the point at the origin.
                val ax = (poly[j][1] - lng) * mPerLng; val ay = (poly[j][0] - lat) * mPerLat
                val bx = (poly[i][1] - lng) * mPerLng; val by = (poly[i][0] - lat) * mPerLat
                val dx = bx - ax; val dy = by - ay
                val len2 = dx * dx + dy * dy
                val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
                best = minOf(best, Math.hypot(ax + t * dx, ay + t * dy))
                j = i
            }
            return best
        }

        /** Approx dimensions (w, h in metres) of an outline's bounding box — seeds the sliders. */
        fun approxDims(poly: List<DoubleArray>): Pair<Double, Double> {
            var minLat = 90.0; var maxLat = -90.0; var minLng = 180.0; var maxLng = -180.0
            for (p in poly) {
                if (p[0] < minLat) minLat = p[0]; if (p[0] > maxLat) maxLat = p[0]
                if (p[1] < minLng) minLng = p[1]; if (p[1] > maxLng) maxLng = p[1]
            }
            val midLat = (minLat + maxLat) / 2
            val w = (maxLng - minLng) * 111_320.0 * Math.cos(Math.toRadians(midLat))
            val h = (maxLat - minLat) * 111_320.0
            return w to h
        }
    }
}
