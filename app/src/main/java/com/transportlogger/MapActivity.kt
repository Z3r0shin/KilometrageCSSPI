package com.transportlogger

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.TileSystem
import org.osmdroid.views.CustomZoomButtonsDisplay
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

/**
 * The "Carte" view of the Lieux tab (PlacesActivity is its "Liste" view).
 * Read-only, à la Google Maps:
 *  - Opens centred on the USER's position (fallback: fits all zones).
 *  - Zone shapes come from OSM automatically: first open runs a batched
 *    detection for every zone that hasn't been resolved yet, then reports
 *    the ones OSM doesn't know (shareable list -> hand-drawn quadrilaterals
 *    get compiled separately and imported via Réglages).
 *  - Tap a zone -> name + address + Itinéraire. Nothing editable.
 */
class MapActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var store: PlaceStore
    private lateinit var hint: TextView
    private var myLocation: MyLocationNewOverlay? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName

        setContentView(R.layout.activity_map)
        Ui.edgeToEdge(this, findViewById(R.id.root), padBottom = false)
        Nav.attach(this, R.id.nav_places)
        LieuxTab.bindHeader(this, mapSelected = true)

        store = PlaceStore(this)
        LieuxTab.showCount(this, store.places.size)
        hint = findViewById(R.id.mapHint)
        map = findViewById(R.id.map)
        map.addMapListener(object : MapListener {
            override fun onZoom(event: ZoomEvent?): Boolean { sizeHalos(); return false }
            override fun onScroll(event: ScrollEvent?): Boolean = false
        })
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        // Bottom right, stacked: the legend sits at the bottom left.
        map.zoomController.display.setPositions(
            false,
            CustomZoomButtonsDisplay.HorizontalPosition.RIGHT,
            CustomZoomButtonsDisplay.VerticalPosition.BOTTOM)
        map.controller.setZoom(15.5)
        map.controller.setCenter(GeoPoint(45.55, -73.62))   // fallback: Montréal

        drawZones()
        centerOnStart()
        autoDetectOsm()
    }

    // ---- start position: the user, like Google Maps ----

    @SuppressLint("MissingPermission")
    private fun centerOnStart() {
        val focus = intent.getStringExtra("focus")?.let { store.placeByName(it) }
        if (focus != null) {
            map.addOnFirstLayoutListener { _, _, _, _, _ ->
                map.controller.setZoom(17.0)
                map.controller.setCenter(GeoPoint(focus.lat, focus.lng))
            }
            map.post { detailSheet(focus) }
            return
        }

        val hasPerm = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPerm) {
            // blue "you are here" dot
            myLocation = MyLocationNewOverlay(GpsMyLocationProvider(this), map).apply {
                enableMyLocation()
            }
            map.overlays.add(myLocation)

            LocationServices.getFusedLocationProviderClient(this).lastLocation
                .addOnSuccessListener { loc ->
                    if (loc != null) {
                        map.controller.setZoom(15.5)
                        map.controller.setCenter(GeoPoint(loc.latitude, loc.longitude))
                    } else fitAllZones()
                }
                .addOnFailureListener { fitAllZones() }
        } else fitAllZones()
    }

    private fun fitAllZones() {
        if (store.places.isEmpty()) return
        val pts = store.places.map { GeoPoint(it.lat, it.lng) }
        try {
            if (pts.size == 1) {
                map.controller.setZoom(16.0); map.controller.setCenter(pts.first())
            } else {
                map.zoomToBoundingBox(
                    org.osmdroid.util.BoundingBox.fromGeoPoints(pts).increaseByScale(1.3f), false)
            }
        } catch (_: Exception) {}
    }

    // ---- automatic OSM detection on open ----

    private fun autoDetectOsm() {
        val todo = store.places.filter { it.polygon == null && !it.osmTried }
        val missingNow = store.places.count { it.polygon == null && it.osmTried }
        if (todo.isEmpty()) {
            hint.text = if (missingNow == 0)
                "Touchez un lieu pour son adresse et l'itinéraire"
            else
                "Touchez un lieu pour l'itinéraire · $missingNow lieu(x) sans terrain OSM"
            return
        }
        hint.text = "Recherche des terrains sur OpenStreetMap… (${LieuxTab.count(todo.size)})"
        Thread {
            val res = OsmFootprint.fetchAll(todo.map { Triple(it.name, it.lat, it.lng) }) { d, t ->
                runOnUiThread { hint.text = "Recherche des terrains OSM… $d / $t" }
            }
            runOnUiThread {
                res.found.forEach { (name, f) -> store.setPolygon(name, f.poly, f.school) }
                res.queried.filter { it !in res.found }.forEach { store.markOsmTried(it) }
                drawZones()

                val missing = store.places.filter { it.polygon == null && it.osmTried }
                hint.text = if (missing.isEmpty())
                    "Terrains OSM chargés · touchez un lieu pour l'itinéraire"
                else
                    "Terrains chargés · ${missing.size} lieu(x) sans terrain OSM"
                if (missing.isNotEmpty()) missingDialog(missing)
            }
        }.start()
    }

    /** OSM doesn't know these — export the list so the quadrilaterals can be hand-compiled. */
    private fun missingDialog(missing: List<Place>) {
        val txt = buildString {
            append("Zones sans terrain OSM (${missing.size}) — coordonnées du centre :\n\n")
            missing.forEach { append("• ${it.name} — ${it.lat}, ${it.lng}\n") }
        }
        AlertDialog.Builder(this)
            .setTitle("Terrains introuvables sur OSM")
            .setMessage(
                "OSM n'a pas de contour pour ${missing.size} lieu(x). " +
                "Partagez la liste, faites compiler les quadrilatères, puis " +
                "importez le JSON dans Réglages. En attendant, ces lieux " +
                "fonctionnent avec leur forme actuelle.")
            .setPositiveButton("Partager la liste") { _, _ ->
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, txt),
                    "Partager la liste"))
            }
            .setNegativeButton("Plus tard", null)
            .show()
    }

    // ---- drawing (read-only) ----

    private fun outlinePoints(p: Place): List<GeoPoint> {
        val o = p.outline()
        return o?.map { GeoPoint(it[0], it[1]) }
            ?: Polygon.pointsAsCircle(GeoPoint(p.lat, p.lng), p.radius)
    }

    /** The margin bands, kept so their width can follow the zoom. */
    private val halos = mutableListOf<Polygon>()

    /**
     * The detection margin, drawn as a translucent band around each zone: the
     * outline stroked twice the margin wide, so half of it lies outside.
     */
    private fun sizeHalos() {
        val metresPerPixel = TileSystem.GroundResolution(map.mapCenter.latitude, map.zoomLevelDouble)
        val width = (2 * Settings.zoneMarginM(this) / metresPerPixel).toFloat()
        halos.forEach { it.outlinePaint.strokeWidth = width }
    }

    private fun drawZones() {
        map.overlays.removeAll { it is Polygon || it is Marker }
        halos.clear()
        val margin = Settings.zoneMarginM(this)
        findViewById<TextView>(R.id.legendMargin).text = "Marge $margin m"
        findViewById<View>(R.id.legendMarginGroup).visibility =
            if (margin > 0) View.VISIBLE else View.GONE

        val fill = ContextCompat.getColor(this, R.color.map_zone_fill)
        val stroke = ContextCompat.getColor(this, R.color.map_zone_stroke)
        val homeFill = ContextCompat.getColor(this, R.color.map_home_fill)
        val homeStroke = ContextCompat.getColor(this, R.color.map_home_stroke)

        for (p in store.places) {
            if (margin > 0) {
                val halo = Polygon(map).apply {
                    points = outlinePoints(p)
                    fillPaint.color = 0
                    outlinePaint.color = if (p.polygon == null) homeFill else fill
                    outlinePaint.strokeJoin = Paint.Join.ROUND
                    infoWindow = null
                }
                halos.add(halo)
                map.overlays.add(halo)      // under the zone itself, which takes the taps
            }
            val shape = Polygon(map).apply {
                points = outlinePoints(p)
                fillPaint.color = if (p.polygon == null) homeFill else fill
                outlinePaint.color = if (p.polygon == null) homeStroke else stroke
                outlinePaint.strokeWidth = 3f
                setOnClickListener { _, _, _ -> detailSheet(p); true }
            }
            map.overlays.add(shape)

            val marker = Marker(map).apply {
                position = GeoPoint(p.lat, p.lng)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                // The marker's bubble follows the sheet: home is never named.
                title = LieuxTab.label(p)
                snippet = if (p.isHome) "" else p.address ?: ""
                setOnMarkerClickListener { m, _ -> m.showInfoWindow(); detailSheet(p); true }
            }
            map.overlays.add(marker)
        }
        sizeHalos()
        map.invalidate()
    }

    /** Read-only sheet: name, address, itinerary. */
    private fun detailSheet(p: Place) = LieuxTab.showPlace(this, store, p, fromList = false) { drawZones() }

    override fun onResume() {
        super.onResume()
        Nav.sync(this, R.id.nav_places)
        map.onResume(); myLocation?.enableMyLocation()
        drawZones()      // the margin may have been changed in Réglages
    }

    override fun onPause() { super.onPause(); map.onPause(); myLocation?.disableMyLocation() }
}
