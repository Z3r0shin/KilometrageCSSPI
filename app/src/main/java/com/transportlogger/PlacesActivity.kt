package com.transportlogger

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.LocationServices
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.text.Normalizer

/**
 * The "Liste" view of the Lieux tab (MapActivity is its "Carte" view):
 *  - search on name or address, and a Tous / Écoles / Entreprises filter;
 *  - the home place pinned on top as "Domicile", never by name or address;
 *  - sections Écoles, then Entreprises et autres, one row per place;
 *  - touching a place opens its sheet: Itinéraire, Voir sur la carte, Supprimer;
 *  - the + button adds a place: current position, an address, or a CSV.
 */
class PlacesActivity : AppCompatActivity() {

    private lateinit var store: PlaceStore
    private lateinit var list: RecyclerView
    private lateinit var info: TextView
    private lateinit var search: EditText
    private lateinit var empty: View

    private enum class Filter(val chip: Int, val label: Int) {
        ALL(R.id.chipAll, R.id.chipAllLabel),
        SCHOOLS(R.id.chipSchools, R.id.chipSchoolsLabel),
        OTHERS(R.id.chipOthers, R.id.chipOthersLabel)
    }

    private sealed class Row
    private class Header(val label: String) : Row()
    private class Home(val place: Place) : Row()
    private class Item(val place: Place, val first: Boolean, val last: Boolean) : Row()

    private val rows = mutableListOf<Row>()
    private var filter = Filter.ALL

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importCsv(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_places)
        Ui.edgeToEdge(this, findViewById(R.id.root), padBottom = false)
        Nav.attach(this, R.id.nav_places)
        LieuxTab.bindHeader(this, mapSelected = false)

        store = PlaceStore(this)
        info = findViewById(R.id.info)
        // The header already shows the count; this line only appears for progress and results.
        info.doAfterTextChanged { info.visibility = if (it.isNullOrEmpty()) View.GONE else View.VISIBLE }
        search = findViewById(R.id.search)
        empty = findViewById(R.id.empty)
        list = findViewById(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<View>(R.id.btnAdd).setOnClickListener { addSheet() }
        Filter.values().forEach { f ->
            findViewById<View>(f.chip).setOnClickListener { filter = f; refresh() }
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = refresh()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    override fun onResume() {
        super.onResume()
        Nav.sync(this, R.id.nav_places)
        refresh()
    }

    // ---- grouping ----

    private fun isSchool(p: Place): Boolean {
        if (p.school) return true    // tagged amenity=school on OpenStreetMap
        val n = norm(p.name)
        return listOf("ecole", "college", "academ", "polyvalente", "secondaire", "primaire", "pavillon")
            .any { n.contains(it) }
    }

    private fun refresh() {
        val q = norm(search.text?.toString() ?: "")
        fun matches(vararg texts: String?) =
            q.isBlank() || texts.any { it != null && norm(it).contains(q) }

        // Home belongs to neither section: it only shows, pinned, under "Tous".
        val (homes, regular) = store.places.partition { it.isHome }
        val (schools, others) = regular.sortedBy { norm(it.name) }.partition { isSchool(it) }

        rows.clear()
        if (filter == Filter.ALL) {
            homes.filter { matches(LieuxTab.HOME_LABEL, it.name, it.address) }.forEach { rows.add(Home(it)) }
        }
        if (filter != Filter.OTHERS) addSection("Écoles", schools.filter { matches(it.name, it.address) })
        if (filter != Filter.SCHOOLS) {
            addSection("Entreprises et autres", others.filter { matches(it.name, it.address) })
        }

        val total = store.places.size
        chip(Filter.ALL, "Tous · $total")
        chip(Filter.SCHOOLS, "Écoles · ${schools.size}")
        chip(Filter.OTHERS, "Entreprises · ${others.size}")
        LieuxTab.showCount(this, total)
        info.text = ""
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
        resolveMissingAddresses()
    }

    private fun addSection(label: String, places: List<Place>) {
        if (places.isEmpty()) return
        rows.add(Header(label))
        places.forEachIndexed { i, p -> rows.add(Item(p, i == 0, i == places.lastIndex)) }
    }

    private fun chip(f: Filter, text: String) {
        val label = findViewById<TextView>(f.label)
        label.text = text
        LieuxTab.select(findViewById(f.chip), label, f == filter)
    }

    // ---- list ----

    private val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemViewType(position: Int) = when (rows[position]) {
            is Header -> R.layout.item_place_header
            is Home -> R.layout.item_place_home
            is Item -> R.layout.item_place
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(
                LayoutInflater.from(parent.context).inflate(viewType, parent, false)
            ) {}

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val v = h.itemView
            when (val row = rows[position]) {
                is Header -> v.findViewById<TextView>(R.id.headerLabel).text = row.label
                is Home -> v.setOnClickListener { detailSheet(row.place) }
                is Item -> {
                    val p = row.place
                    // A section is one card: its rows share the border and the corners.
                    v.setBackgroundResource(when {
                        row.first && row.last -> R.drawable.bg_place_row_single
                        row.first -> R.drawable.bg_place_row_top
                        row.last -> R.drawable.bg_place_row_bottom
                        else -> R.drawable.bg_place_row_middle
                    })
                    v.findViewById<TextView>(R.id.placeName).text = p.name
                    v.findViewById<TextView>(R.id.placeAddress).text = p.address ?: "…"
                    LieuxTab.bindTag(v.findViewById(R.id.placeTag), p)
                    v.findViewById<View>(R.id.placeDivider).visibility =
                        if (row.last) View.GONE else View.VISIBLE
                    v.setOnClickListener { detailSheet(p) }
                }
            }
        }
    }

    @Volatile private var resolving = false

    /** Quietly reverse-geocode a few missing addresses per pass, then refresh. */
    private fun resolveMissingAddresses() {
        if (resolving) return
        val missing = store.places.filter { it.address == null }.take(8)
        if (missing.isEmpty()) return
        resolving = true
        Thread {
            var changed = false
            for (p in missing) {
                val a = Geocoding.reverse(this, p.lat, p.lng) ?: continue
                store.setAddress(p.name, a); changed = true
                Thread.sleep(350)   // stay polite with the geocoder
            }
            resolving = false
            if (changed) runOnUiThread { refresh() }
        }.start()
    }

    private fun detailSheet(p: Place) = LieuxTab.showPlace(this, store, p, fromList = true) { refresh() }

    // ---- add (single + button) ----

    private fun addSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_place_add, null)
        dialog.setContentView(view)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        fun option(id: Int, action: () -> Unit) =
            view.findViewById<View>(id).setOnClickListener { dialog.dismiss(); action() }
        option(R.id.addHere) { addZoneHere() }
        option(R.id.addAddress) { addByAddress() }
        option(R.id.addCsv) {
            importLauncher.launch(
                arrayOf("text/*", "text/csv", "application/csv", "text/comma-separated-values"))
        }
        dialog.show()
    }

    @SuppressLint("MissingPermission")
    private fun addZoneHere() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Permission de localisation requise.", Toast.LENGTH_LONG).show()
            return
        }
        LocationServices.getFusedLocationProviderClient(this).lastLocation.addOnSuccessListener { loc ->
            if (loc == null) {
                Toast.makeText(this, "Position indisponible.", Toast.LENGTH_SHORT).show()
                return@addOnSuccessListener
            }
            nameDialog("Ajouter un lieu ici") { name ->
                addWithOsm(name, loc.latitude, loc.longitude)
            }
        }
    }

    private fun addByAddress() {
        val box = layoutInflater.inflate(R.layout.dialog_place_add, null)
        val name = box.findViewById<EditText>(R.id.addPlaceName)
        val addr = box.findViewById<EditText>(R.id.addPlaceAddress)
        AlertDialog.Builder(this).setTitle("Ajouter par adresse").setView(box)
            .setPositiveButton("Géocoder") { _, _ ->
                info.text = "Géocodage…"
                Thread {
                    val c = Geocoding.resolve(this, addr.text.toString())
                    runOnUiThread {
                        if (c == null) {
                            Toast.makeText(this, "Adresse introuvable.", Toast.LENGTH_LONG).show()
                            refresh()
                        } else {
                            addWithOsm(name.text.toString().ifBlank { "ZONE" },
                                c.first, c.second, addr.text.toString())
                        }
                    }
                }.start()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** Adds a square zone, then upgrades it to its OSM footprint in the background. */
    private fun addWithOsm(name: String, lat: Double, lng: Double, address: String? = null) {
        val side = Settings.defaultRadius(this).toDouble()
        store.addOrReplace(Place(name, lat, lng, side, false, address, rw = side, rh = side))
        refresh()
        Thread {
            val f = OsmFootprint.fetch(lat, lng)
            runOnUiThread {
                if (f != null) {
                    store.setPolygon(name, f.poly, f.school)
                    Toast.makeText(this, "Terrain OSM appliqué à « $name ».", Toast.LENGTH_SHORT).show()
                } else store.markOsmTried(name)
                refresh()
            }
        }.start()
    }

    private fun nameDialog(title: String, onOk: (String) -> Unit) {
        val box = layoutInflater.inflate(R.layout.dialog_place_add, null)
        val name = box.findViewById<EditText>(R.id.addPlaceName)
        box.findViewById<View>(R.id.addPlaceAddress).visibility = View.GONE
        AlertDialog.Builder(this).setTitle(title).setView(box)
            .setPositiveButton("Ajouter") { _, _ ->
                onOk(name.text.toString().ifBlank { "ZONE" })
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ---- CSV import (header-mapped; geocodes rows missing coords) ----

    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKD)
        .replace("\\p{Mn}+".toRegex(), "").trim().lowercase()

    private fun splitCsvLine(line: String): List<String> {
        val out = mutableListOf<String>(); val sb = StringBuilder(); var inQ = false; var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && inQ && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> inQ = !inQ
                ch == ',' && !inQ -> { out.add(sb.toString()); sb.clear() }
                else -> sb.append(ch)
            }
            i++
        }
        out.add(sb.toString())
        return out.map { it.trim() }
    }

    private fun importCsv(uri: Uri) {
        info.text = "Lecture du fichier…"
        Thread {
            try {
                val lines = contentResolver.openInputStream(uri)!!
                    .bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotBlank() }
                if (lines.isEmpty()) { runOnUiThread { info.text = "Fichier vide." }; return@Thread }

                val header = splitCsvLine(lines[0]).map { norm(it) }
                fun col(vararg names: String) =
                    names.map { norm(it) }.firstNotNullOfOrNull { n ->
                        header.indexOf(n).takeIf { it >= 0 }
                    } ?: -1

                val iName = col("nom", "name", "lieu", "place")
                val iAddr = col("adresse", "address")
                val iLat = col("lat", "latitude")
                val iLng = col("lng", "lon", "longitude")
                if (iName < 0) { runOnUiThread { info.text = "Colonne « nom » introuvable." }; return@Thread }

                var ok = 0; var geo = 0; var fail = 0
                val side = Settings.defaultRadius(this).toDouble()

                for (li in 1 until lines.size) {
                    val c = splitCsvLine(lines[li])
                    fun g(i: Int) = if (i in c.indices) c[i] else ""
                    val name = g(iName); if (name.isBlank()) continue
                    var lat = g(iLat).replace(',', '.').toDoubleOrNull()
                    var lng = g(iLng).replace(',', '.').toDoubleOrNull()
                    val address = g(iAddr).ifBlank { null }

                    if (lat == null || lng == null) {
                        if (address == null) { fail++; continue }
                        runOnUiThread { info.text = "Géocodage $li/${lines.size - 1} : $name…" }
                        val r = Geocoding.resolve(this, address)
                        if (r == null) { fail++; continue }
                        lat = r.first; lng = r.second; geo++
                        Thread.sleep(400)   // stay polite with the geocoder
                    }
                    store.addOrReplace(Place(name, lat, lng, side, false, address, rw = side, rh = side))
                    ok++
                }
                runOnUiThread {
                    refresh()
                    // After refresh(), which puts the plain count back on this line.
                    info.text = "Import : ${LieuxTab.count(ok)} ($geo géocodés, $fail échecs). " +
                        "Astuce : ouvrez la carte pour détecter les terrains."
                }
            } catch (e: Exception) {
                runOnUiThread { info.text = "Échec de l'import : ${e.message}" }
            }
        }.start()
    }
}

/** What the two views of the Lieux tab share: its header and a place's sheet. */
internal object LieuxTab {

    /** The home place is always shown under this label, never by name or address. */
    const val HOME_LABEL = "Domicile"

    fun label(p: Place) = if (p.isHome) HOME_LABEL else p.name

    fun count(n: Int) = if (n == 1) "1 lieu" else "$n lieux"

    fun showCount(activity: Activity, n: Int) {
        activity.findViewById<TextView>(R.id.placesCount).text = count(n)
    }

    /** Wires the Liste | Carte switch; the two activities replace each other. */
    fun bindHeader(activity: Activity, mapSelected: Boolean) {
        val listSide = activity.findViewById<View>(R.id.segList)
        val mapSide = activity.findViewById<View>(R.id.segMap)
        select(listSide, activity.findViewById(R.id.segListLabel), !mapSelected)
        select(mapSide, activity.findViewById(R.id.segMapLabel), mapSelected)
        if (mapSelected) listSide.setOnClickListener { open(activity, PlacesActivity::class.java) }
        else mapSide.setOnClickListener { open(activity, MapActivity::class.java) }
    }

    /** Marks one side of the switch, or one filter chip, as the chosen one. */
    fun select(target: View, label: TextView, selected: Boolean) {
        target.isSelected = selected
        label.isSelected = selected
        label.typeface = ResourcesCompat.getFont(
            label.context, if (selected) R.font.instrument_bold else R.font.instrument_semibold)
    }

    /** Swaps to the other view without animation, so only one of the two is ever on the stack. */
    fun open(from: Activity, target: Class<out Activity>, focus: String? = null) {
        val intent = Intent(from, target).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        if (focus != null) intent.putExtra("focus", focus)
        from.startActivity(intent)
        from.finish()
        @Suppress("DEPRECATION")
        from.overridePendingTransition(0, 0)
    }

    /** "Terrain" for a place that has its OSM footprint, otherwise its default shape. */
    fun bindTag(tag: TextView, p: Place) {
        val terrain = p.polygon != null
        tag.text = if (terrain) "Terrain" else p.shapeLabel()
        tag.setBackgroundResource(if (terrain) R.drawable.bg_tag_green else R.drawable.bg_tag_amber)
        tag.setTextColor(ContextCompat.getColor(
            tag.context, if (terrain) R.color.green_text else R.color.amber_tint_text))
    }

    /**
     * A place's sheet: name, address, shape and Itinéraire. From the list it also
     * offers the map and deletion; [onChanged] runs once the place's address has
     * been found or the place deleted.
     */
    fun showPlace(
        activity: AppCompatActivity, store: PlaceStore, pIn: Place, fromList: Boolean,
        onChanged: () -> Unit = {}
    ) {
        val p = store.placeByName(pIn.name) ?: return
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_place, null)
        dialog.setContentView(view)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        view.findViewById<TextView>(R.id.sheetPlaceName).text = label(p)
        val addr = view.findViewById<TextView>(R.id.sheetPlaceAddress)
        addr.text = p.address ?: "Recherche de l'adresse…"
        addr.visibility = if (p.isHome) View.GONE else View.VISIBLE
        bindTag(view.findViewById(R.id.sheetPlaceTag), p)

        view.findViewById<View>(R.id.btnPlaceRoute).setOnClickListener {
            val uri = Uri.parse("geo:${p.lat},${p.lng}?q=${p.lat},${p.lng}(${Uri.encode(p.name)})")
            try { activity.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            catch (e: Exception) {
                Toast.makeText(activity, "Aucune app de navigation trouvée.", Toast.LENGTH_SHORT).show()
            }
        }
        val btnMap = view.findViewById<View>(R.id.btnPlaceMap)
        val btnDelete = view.findViewById<View>(R.id.btnPlaceDelete)
        btnMap.visibility = if (fromList) View.VISIBLE else View.GONE
        btnDelete.visibility = btnMap.visibility
        btnMap.setOnClickListener {
            dialog.dismiss()
            open(activity, MapActivity::class.java, p.name)
        }
        btnDelete.setOnClickListener {
            AlertDialog.Builder(activity)
                .setMessage("Supprimer le lieu « ${label(p)} » ?")
                .setPositiveButton("Supprimer") { _, _ -> store.remove(p.name); dialog.dismiss(); onChanged() }
                .setNegativeButton("Annuler", null)
                .show()
        }
        // Home's address stays off screen, so it has nothing to correct here.
        val btnMove = view.findViewById<View>(R.id.btnPlaceMove)
        btnMove.visibility = if (p.isHome) View.GONE else View.VISIBLE
        btnMove.setOnClickListener { dialog.dismiss(); moveDialog(activity, store, p, onChanged) }
        dialog.show()

        if (p.address == null) {
            Thread {
                val a = Geocoding.reverse(activity, p.lat, p.lng)
                activity.runOnUiThread {
                    if (a != null) { store.setAddress(p.name, a); addr.text = a; onChanged() }
                    else addr.text = "Adresse inconnue"
                }
            }.start()
        }
    }

    private val LAT_LNG = Regex("""^\s*(-?\d{1,2}\.\d+)\s*,\s*(-?\d{1,3}\.\d+)\s*$""")

    /**
     * Moves a zone whose position is wrong to a corrected address, or to
     * "lat, lng" typed as is, then looks up its OSM footprint there. The name
     * stays, so its kilometres in the board's matrix still apply.
     */
    private fun moveDialog(activity: AppCompatActivity, store: PlaceStore, p: Place, onChanged: () -> Unit) {
        val box = LayoutInflater.from(activity).inflate(R.layout.dialog_place_add, null)
        box.findViewById<View>(R.id.addPlaceName).visibility = View.GONE
        val field = box.findViewById<EditText>(R.id.addPlaceAddress)
        field.hint = "Adresse, ou lat, lng"
        field.setText(p.address ?: "")
        AlertDialog.Builder(activity).setTitle("Corriger l'adresse de « ${p.name} »").setView(box)
            .setPositiveButton("Déplacer") { _, _ ->
                val text = field.text.toString().trim()
                Toast.makeText(activity, "Recherche de l'adresse…", Toast.LENGTH_SHORT).show()
                Thread {
                    val typed = LAT_LNG.find(text)?.destructured?.let { (la, ln) -> la.toDouble() to ln.toDouble() }
                    val at = typed ?: Geocoding.resolve(activity, text)
                    if (at == null) {
                        activity.runOnUiThread {
                            Toast.makeText(activity, "Adresse introuvable.", Toast.LENGTH_LONG).show()
                        }
                        return@Thread
                    }
                    val side = Settings.defaultRadius(activity).toDouble()
                    activity.runOnUiThread {
                        store.move(p.name, at.first, at.second, if (typed == null) text else null, side)
                        onChanged()
                    }
                    val f = OsmFootprint.fetch(at.first, at.second)
                    activity.runOnUiThread {
                        if (f != null) store.setPolygon(p.name, f.poly, f.school) else store.markOsmTried(p.name)
                        onChanged()
                        Toast.makeText(activity,
                            if (f != null) "« ${p.name} » déplacé, terrain OSM appliqué."
                            else "« ${p.name} » déplacé (carré de ${side.toInt()} m, aucun terrain OSM).",
                            Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
