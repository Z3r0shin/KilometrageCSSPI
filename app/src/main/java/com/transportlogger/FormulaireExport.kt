package com.transportlogger

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Fills the paper claim form with logged trips.
 *
 * assets/formulaire_template.xlsx is the real workbook with its matrix sheet,
 * dropdowns and lookup formulas stripped out (see tools/strip_template.py).
 * Everything that makes it look like the form -- logo, fonts, borders, merged
 * cells, row heights, print setup -- is left untouched, so a printed export is
 * indistinguishable from a printed blank.
 *
 * Export therefore only has to put values into cells: every zip entry is copied
 * verbatim except xl/worksheets/sheet1.xml, where each target cell keeps its own
 * style attribute and gains a value. Text goes in as an inline string rather
 * than a shared-string index, so xl/sharedStrings.xml never has to be touched
 * and its existing indices cannot drift.
 *
 * The template names nobody: the header -- who claims, employee number,
 * address, function, service -- is written from the saved [Profile] on every
 * export, which is what lets the same app serve the whole team.
 *
 * The form holds 25 lines. A month with more spills into further files, the way
 * a second blank would be taken off the pile and filled by hand.
 *
 * A line is one trip, or an aller-retour: X -> Y directly followed by Y -> X
 * goes on a single line marked 2, the way it is written by hand. trips.json
 * keeps both legs; the pairing only exists on the form.
 *
 * At the bottom, the requester's DATE box gets the day of the export, and the
 * signature box beside it the saved [Signature]: typed block letters go in the
 * cell as text, a drawing goes on the sheet's drawing layer as a picture,
 * standing on the signature line.
 */
object FormulaireExport {

    const val ROWS_PER_FORM = 25

    private const val TEMPLATE = "formulaire_template.xlsx"
    private const val SHEET = "xl/worksheets/sheet1.xml"
    private const val FIRST_ROW = 23

    /** Cell holding the month name, and the one holding the year. */
    private const val CELL_MONTH = "C18"
    private const val CELL_YEAR = "E18"

    /** The claimant's header, top left and top right of the form. */
    private const val CELL_NAME = "C13"
    private const val CELL_EMPLOYEE_NO = "J13"
    private const val CELL_ADDRESS = "C14"
    private const val CELL_CITY = "C15"
    private const val CELL_POSTAL_CODE = "C16"
    private const val CELL_FUNCTION = "J14"
    private const val CELL_SERVICE = "J15"

    /**
     * The requester's sign-off, bottom left: DATE (A56:B56, a D/M/YYYY date
     * cell) and the signature box beside it (C56:G56). The box is styled in a
     * script font; typed block letters borrow the plain style of the
     * "SIGNATURE" caption under it (C57), which has the same border.
     */
    private const val CELL_SIGNED_ON = "A56"
    private const val CELL_SIGNATURE = "C56"
    private const val CELL_SIGNATURE_CAPTION = "C57"
    private const val SIGNATURE_ROW = 56
    /**
     * The box itself is one short row. A drawn signature may rise above it, over
     * the form (the PNG is transparent), up to the row under the
     * "STATIONNEMENT" line.
     */
    private const val SIGNATURE_TOP_ROW = 53
    private const val SIGNATURE_FIRST_COL = 3   // C
    private const val SIGNATURE_LAST_COL = 7    // G

    private const val DRAWING = "xl/drawings/drawing1.xml"
    private const val DRAWING_RELS = "xl/drawings/_rels/drawing1.xml.rels"
    private const val CONTENT_TYPES = "[Content_Types].xml"
    private const val SIGNATURE_MEDIA = "xl/media/signature.png"
    private const val SIGNATURE_REL = "rIdSignature"

    /**
     * The form's employee-number cell is numeric. A leading zero would be lost
     * as a number, and past 15 digits a double stops being exact, so those go
     * in as text like any other non-numeric entry.
     */
    private val WHOLE_NUMBER = Regex("[1-9][0-9]{0,14}")

    /**
     * Taux d'indemnité, written on every export rather than left to the
     * template, so a rate change is one edit here instead of a template rebuild.
     * The form's own M50 multiplies it by the total kilometres.
     */
    private const val CELL_RATE = "H50"
    const val RATE_PER_KM = 0.68

    private val MONTHS = arrayOf(
        "Janvier", "Février", "Mars", "Avril", "Mai", "Juin",
        "Juillet", "Août", "Septembre", "Octobre", "Novembre", "Décembre"
    )

    /** Matches one cell, self-closing or not, capturing its reference and attributes. */
    private val CELL = Regex("""<c r="([A-Z]+\d+)"([^>]*?)(?:/>|>.*?</c>)""", RegexOption.DOT_MATCHES_ALL)

    private sealed class Value {
        /** [style], when set, replaces the cell's own style index. */
        class Text(val s: String, val style: String? = null) : Value()
        class Number(val d: Double) : Value()
    }

    /**
     * One line of the form. [legs] is column M: 1 = aller, 2 = aller-retour,
     * in which case [back] is the return leg folded into the line.
     */
    internal class Line(
        val trip: Trip, val po: String?, val km: Double?, val legs: Int, val back: Trip? = null
    ) {
        /** Kilometres claimed for the line: column N. */
        val total: Double? get() = km?.let { it * legs }
    }

    /**
     * Collapse chronological trips into form lines. Only the trip directly
     * after an outbound can be its return, and only on the same day: with
     * X -> Y, Y -> Z, Z -> Y, Y -> A, A -> X the middle two pair up and the
     * rest stay single. An aller-retour carries one P.O., so two legs given
     * different numbers are separate trips and are left unpaired.
     */
    internal fun lines(ordered: List<Trip>): List<Line> {
        val out = ArrayList<Line>()
        var i = 0
        while (i < ordered.size) {
            val t = ordered[i]
            val back = ordered.getOrNull(i + 1)
            if (back != null && isReturn(t, back)) {
                // The line reads as the outbound leg, so it carries that leg's km.
                out.add(Line(t, t.po ?: back.po, t.km ?: back.km, 2, back))
                i += 2
            } else {
                out.add(Line(t, t.po, t.km, 1))
                i += 1
            }
        }
        return out
    }

    private fun isReturn(out: Trip, back: Trip): Boolean =
        out.date == back.date &&
            out.depart != out.arrivee &&
            out.depart == back.arrivee && out.arrivee == back.depart &&
            (out.po == null || back.po == null || out.po == back.po)

    /**
     * Write one file per 25 lines into filesDir/exports, oldest first, and
     * return them in order. Existing exports for the month are replaced so
     * repeated exports do not pile up.
     */
    fun export(ctx: Context, trips: List<Trip>, monthKey: String): List<File> {
        val dir = File(ctx.filesDir, "exports").apply { mkdirs() }
        dir.listFiles { f -> f.name.startsWith("formulaire_$monthKey") }?.forEach { it.delete() }

        val template = ctx.assets.open(TEMPLATE).use { it.readBytes() }
        val profile = Profile.load(ctx)
        val signature = Signature.load(ctx)
        val today = SimpleDateFormat("yyyy-MM-dd", Fmt.FR).format(Date())
        // Pairing changes how many lines a month needs, so it runs before paging.
        val pages = lines(trips.sortedBy { it.ts }).chunked(ROWS_PER_FORM)

        return pages.mapIndexed { index, page ->
            val name = if (pages.size == 1) "formulaire_$monthKey.xlsx"
            else "formulaire_${monthKey}_${index + 1}-de-${pages.size}.xlsx"
            File(dir, name).also {
                it.writeBytes(fill(template, page, monthKey, profile, signature, today))
            }
        }
    }

    /** Export, then hand the files to the share sheet (Drive, mail, whatever). */
    fun exportAndShare(ctx: Context, trips: List<Trip>, monthKey: String): Int {
        val files = export(ctx, trips, monthKey)
        if (files.isEmpty()) return 0

        val uris = ArrayList<Uri>(files.map {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", it)
        })
        val type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND)
                .putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        send.type = type
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Envoyer le formulaire"))
        return files.size
    }

    // ---- Workbook ----

    /**
     * [signedOn] (yyyy-MM-dd) goes in the DATE box beside the signature; null
     * leaves it blank, as does a null [signature] its box.
     */
    internal fun fill(
        template: ByteArray, page: List<Line>, monthKey: String, profile: Profile,
        signature: Signature? = null, signedOn: String? = null
    ): ByteArray {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(template.inputStream()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                entries[entry.name] = zin.readBytes()
            }
        }

        val sheet = entries.getValue(SHEET).toString(Charsets.UTF_8)
        val values = cellValues(page, monthKey, profile)
        signedOn?.let(::excelDate)?.let { values[CELL_SIGNED_ON] = Value.Number(it.toDouble()) }
        when (signature) {
            is Signature.Typed -> if (signature.text.isNotBlank()) {
                values[CELL_SIGNATURE] = Value.Text(signature.text, styleOf(sheet, CELL_SIGNATURE_CAPTION))
            }
            is Signature.Drawn -> addPicture(entries, sheet, signature)
            null -> {}
        }
        entries[SHEET] = applyValues(sheet, values).toByteArray(Charsets.UTF_8)

        val out = ByteArrayOutputStream(template.size + 8 * 1024)
        ZipOutputStream(out).use { zout ->
            for ((name, bytes) in entries) {
                zout.putNextEntry(ZipEntry(name))
                zout.write(bytes)
                zout.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Excel's day number for a yyyy-MM-dd date: days since 1899-12-30. */
    internal fun excelDate(date: String): Long? {
        val p = date.split("-").map { it.toIntOrNull() ?: return null }
        if (p.size != 3) return null
        val utc = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(p[0], p[1] - 1, p[2])
        }
        return utc.timeInMillis / 86_400_000L + 25_569L
    }

    private fun styleOf(sheet: String, ref: String): String? =
        CELL.findAll(sheet).firstOrNull { it.groupValues[1] == ref }
            ?.groupValues?.get(2)?.let { Regex("""\bs="(\d+)"""").find(it)?.groupValues?.get(1) }

    // ---- Signature picture ----

    private const val EMU_PER_PX = 9_525L
    private const val EMU_PER_PT = 12_700L

    /** Arial 10's widest digit, in pixels: what spreadsheet column widths are counted in. */
    private const val DIGIT_PX = 7.0

    /**
     * Put the drawn signature on the sheet: a PNG in xl/media, a relationship
     * from the drawing to it, and an anchor that centres it over the signature
     * box, standing on the line under it and as tall as the room up to
     * [SIGNATURE_TOP_ROW] allows (or as wide as the box, if it is wider).
     */
    private fun addPicture(entries: MutableMap<String, ByteArray>, sheet: String, sig: Signature.Drawn) {
        val drawing = entries[DRAWING]?.toString(Charsets.UTF_8) ?: return
        val rels = entries[DRAWING_RELS]?.toString(Charsets.UTF_8) ?: return

        val cols = (SIGNATURE_FIRST_COL..SIGNATURE_LAST_COL).map { columnEmu(sheet, it) }
        val rows = (SIGNATURE_TOP_ROW..SIGNATURE_ROW).map { rowEmu(sheet, it) }
        val boxWidth = cols.sum()
        val room = rows.sum()
        val pad = EMU_PER_PT * 3 / 2

        var cy = room - 2 * pad
        var cx = cy * sig.width / sig.height
        if (cx > boxWidth - 2 * pad) {
            cx = boxWidth - 2 * pad
            cy = cx * sig.height / sig.width
        }

        // Centre it, bottom on the line, then say which cell its top left
        // corner falls in.
        var x = (boxWidth - cx) / 2
        var col = 0
        while (col < cols.size - 1 && x >= cols[col]) x -= cols[col++]
        var y = room - pad - cy
        var row = 0
        while (row < rows.size - 1 && y >= rows[row]) y -= rows[row++]

        val id = (Regex("""cNvPr[^>]*\bid="(\d+)"""").findAll(drawing)
            .maxOfOrNull { it.groupValues[1].toInt() } ?: 0) + 1
        val anchor = """<xdr:oneCellAnchor><xdr:from>""" +
            """<xdr:col>${SIGNATURE_FIRST_COL - 1 + col}</xdr:col><xdr:colOff>$x</xdr:colOff>""" +
            """<xdr:row>${SIGNATURE_TOP_ROW - 1 + row}</xdr:row><xdr:rowOff>$y</xdr:rowOff></xdr:from>""" +
            """<xdr:ext cx="$cx" cy="$cy"/><xdr:pic><xdr:nvPicPr>""" +
            """<xdr:cNvPr id="$id" name="Signature" descr="Signature"/>""" +
            """<xdr:cNvPicPr><a:picLocks noChangeAspect="1"/></xdr:cNvPicPr></xdr:nvPicPr>""" +
            """<xdr:blipFill><a:blip r:embed="$SIGNATURE_REL"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill>""" +
            """<xdr:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm>""" +
            """<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic>""" +
            """<xdr:clientData/></xdr:oneCellAnchor>"""
        val rel = """<Relationship Id="$SIGNATURE_REL" """ +
            """Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" """ +
            """Target="../media/signature.png"/>"""

        entries[DRAWING] = drawing.replace("</xdr:wsDr>", "$anchor</xdr:wsDr>").toByteArray(Charsets.UTF_8)
        entries[DRAWING_RELS] = rels.replace("</Relationships>", "$rel</Relationships>").toByteArray(Charsets.UTF_8)
        entries[SIGNATURE_MEDIA] = sig.png
        entries[CONTENT_TYPES]?.toString(Charsets.UTF_8)?.let { types ->
            if (!types.contains("Extension=\"png\"")) {
                entries[CONTENT_TYPES] = types.replace(
                    "</Types>", """<Default Extension="png" ContentType="image/png"/></Types>"""
                ).toByteArray(Charsets.UTF_8)
            }
        }
    }

    /** Width of a column (1 = A), read from the sheet's own <cols>. */
    private fun columnEmu(sheet: String, col: Int): Long {
        val width = Regex("""<col\b[^>]*>""").findAll(sheet).map { it.value }.firstOrNull {
            val min = attr(it, "min")?.toIntOrNull() ?: return@firstOrNull false
            val max = attr(it, "max")?.toIntOrNull() ?: return@firstOrNull false
            col in min..max
        }?.let { attr(it, "width")?.toDoubleOrNull() }
            ?: Regex("""<sheetFormatPr\b[^>]*>""").find(sheet)?.value
                ?.let { attr(it, "defaultColWidth")?.toDoubleOrNull() }
            ?: 8.43
        return Math.round(width * DIGIT_PX) * EMU_PER_PX
    }

    /** Height of a row, read from the sheet. */
    private fun rowEmu(sheet: String, row: Int): Long {
        val pt = Regex("""<row r="$row"[^>]*>""").find(sheet)?.value
            ?.let { attr(it, "ht")?.toDoubleOrNull() } ?: 15.0
        return Math.round(pt * EMU_PER_PT)
    }

    private fun attr(tag: String, name: String): String? =
        Regex("""\b$name="([^"]*)"""").find(tag)?.groupValues?.get(1)

    /**
     * Columns, as the form names them: A JOUR, B POINT DE DÉPART,
     * E POINT D'ARRIVÉE, J Requête, L Nombre de kilomètres,
     * M 1 = aller / 2 = aller-retour, N Total.
     *
     * Above them, the header: C13 NOM, Prénom, J13 NO D'EMPLOYÉ, C14 ADRESSE,
     * C15 VILLE, C16 CODE POSTAL, J14 FONCTION, J15 SERVICE. A blank profile
     * field leaves its cell as the template has it.
     */
    private fun cellValues(page: List<Line>, monthKey: String, profile: Profile): MutableMap<String, Value> {
        val values = HashMap<String, Value>()
        values[CELL_RATE] = Value.Number(RATE_PER_KM)

        fun text(cell: String, s: String) {
            if (s.isNotBlank()) values[cell] = Value.Text(s)
        }
        text(CELL_NAME, profile.name)
        text(CELL_ADDRESS, profile.address)
        text(CELL_CITY, profile.city)
        text(CELL_POSTAL_CODE, profile.postalCode)
        text(CELL_FUNCTION, profile.function)
        text(CELL_SERVICE, profile.service)
        val no = profile.employeeNo
        if (WHOLE_NUMBER.matches(no)) values[CELL_EMPLOYEE_NO] = Value.Number(no.toDouble())
        else text(CELL_EMPLOYEE_NO, no)

        monthKey.split("-").let { parts ->
            val year = parts.getOrNull(0)?.toIntOrNull()
            val month = parts.getOrNull(1)?.toIntOrNull()
            if (month != null && month in 1..12) values[CELL_MONTH] = Value.Text(MONTHS[month - 1])
            if (year != null) values[CELL_YEAR] = Value.Number(year.toDouble())
        }

        page.forEachIndexed { i, line ->
            val row = FIRST_ROW + i
            val t = line.trip
            values["A$row"] = Value.Number(dayOfMonth(t).toDouble())
            values["B$row"] = Value.Text(t.depart)
            values["E$row"] = Value.Text(t.arrivee)
            line.po?.let { values["J$row"] = Value.Text(it) }
            values["M$row"] = Value.Number(line.legs.toDouble())
            line.km?.let {
                values["L$row"] = Value.Number(it)
                values["N$row"] = Value.Number(it * line.legs)
            }
        }
        return values
    }

    private fun dayOfMonth(t: Trip): Int =
        t.date.split("-").getOrNull(2)?.toIntOrNull()
            ?: Calendar.getInstance().apply { timeInMillis = t.ts }.get(Calendar.DAY_OF_MONTH)

    /** Rewrite only the cells we have values for, keeping each one's style. */
    private fun applyValues(xml: String, values: Map<String, Value>): String =
        CELL.replace(xml) { m ->
            val ref = m.groupValues[1]
            val value = values[ref] ?: return@replace m.value
            // Drop any cached type; we set our own.
            var attrs = m.groupValues[2].replace(Regex("""\s+t="[^"]*""""), "")
            if (value is Value.Text && value.style != null) {
                attrs = attrs.replace(Regex("""\bs="\d+""""), """s="${value.style}"""")
            }
            when (value) {
                is Value.Text -> """<c r="$ref"$attrs t="inlineStr"><is><t>${escape(value.s)}</t></is></c>"""
                is Value.Number -> """<c r="$ref"$attrs><v>${number(value.d)}</v></c>"""
            }
        }

    private fun number(d: Double): String =
        if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
