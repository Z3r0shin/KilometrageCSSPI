package com.transportlogger

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Calendar
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
        class Text(val s: String) : Value()
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
        // Pairing changes how many lines a month needs, so it runs before paging.
        val pages = lines(trips.sortedBy { it.ts }).chunked(ROWS_PER_FORM)

        return pages.mapIndexed { index, page ->
            val name = if (pages.size == 1) "formulaire_$monthKey.xlsx"
            else "formulaire_${monthKey}_${index + 1}-de-${pages.size}.xlsx"
            File(dir, name).also { it.writeBytes(fill(template, page, monthKey, profile)) }
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

    internal fun fill(template: ByteArray, page: List<Line>, monthKey: String, profile: Profile): ByteArray {
        val values = cellValues(page, monthKey, profile)
        val out = ByteArrayOutputStream(template.size + 8 * 1024)

        ZipInputStream(template.inputStream()).use { zin ->
            ZipOutputStream(out).use { zout ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    val bytes = zin.readBytes()
                    zout.putNextEntry(ZipEntry(entry.name))
                    zout.write(
                        if (entry.name == SHEET)
                            applyValues(bytes.toString(Charsets.UTF_8), values).toByteArray(Charsets.UTF_8)
                        else bytes
                    )
                    zout.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }

    /**
     * Columns, as the form names them: A JOUR, B POINT DE DÉPART,
     * E POINT D'ARRIVÉE, J Requête, L Nombre de kilomètres,
     * M 1 = aller / 2 = aller-retour, N Total.
     *
     * Above them, the header: C13 NOM, Prénom, J13 NO D'EMPLOYÉ, C14 ADRESSE,
     * C15 VILLE, C16 CODE POSTAL, J14 FONCTION, J15 SERVICE. A blank profile
     * field leaves its cell as the template has it.
     */
    private fun cellValues(page: List<Line>, monthKey: String, profile: Profile): Map<String, Value> {
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
            val attrs = m.groupValues[2].replace(Regex("""\s+t="[^"]*""""), "")
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
