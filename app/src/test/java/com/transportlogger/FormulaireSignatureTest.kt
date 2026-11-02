package com.transportlogger

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.awt.BasicStroke
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory

/** The sign-off at the bottom of the form: the date, and the signature beside it. */
class FormulaireSignatureTest {

    private val sheetName = "xl/worksheets/sheet1.xml"
    private val drawingName = "xl/drawings/drawing1.xml"
    private val relsName = "xl/drawings/_rels/drawing1.xml.rels"
    private val mediaName = "xl/media/signature.png"
    private val template = File("src/main/assets/formulaire_template.xlsx").readBytes()

    private val profile = Profile(name = "TREMBLAY, Alex", employeeNo = "12345")
    private val trips = listOf(Trip(1L, "2026-10-05", "ATELIER", "ÉCOLE A", 12.4, "CONFIRMÉ"))

    private fun fill(signature: Signature? = null, signedOn: String? = null): ByteArray =
        FormulaireExport.fill(
            template, FormulaireExport.lines(trips), "2026-10", profile, signature, signedOn
        )

    private fun entries(xlsx: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(xlsx.inputStream()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                out[entry.name] = zin.readBytes()
            }
        }
        return out
    }

    private fun xml(bytes: ByteArray) = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }.newDocumentBuilder().parse(bytes.inputStream())

    private fun cells(xlsx: ByteArray): Map<String, Element> {
        val nodes = xml(entries(xlsx).getValue(sheetName)).getElementsByTagNameNS("*", "c")
        return (0 until nodes.length).map { nodes.item(it) as Element }.associateBy { it.getAttribute("r") }
    }

    /** A wide stroke of ink on a transparent background, like the pad saves. */
    private fun png(w: Int = 300, h: Int = 80): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        img.createGraphics().apply {
            color = Color(0x16, 0x23, 0x3F)
            stroke = BasicStroke(4f)
            drawLine(5, h - 10, w - 5, 10)
            dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    @Test
    fun excelCountsDaysFrom1899() {
        assertEquals(25_569L, FormulaireExport.excelDate("1970-01-01"))
        assertEquals(46_023L, FormulaireExport.excelDate("2026-01-01"))
        assertNull(FormulaireExport.excelDate("2026-13"))
    }

    @Test
    fun theDateGoesBesideTheSignature() {
        val before = cells(template).getValue("A56")
        val after = cells(fill(signedOn = "2026-11-02")).getValue("A56")
        assertEquals(FormulaireExport.excelDate("2026-11-02").toString(), after.textContent)
        assertFalse(after.hasAttribute("t"))
        // The cell's own D/M/YYYY style shows it as a date.
        assertEquals(before.getAttribute("s"), after.getAttribute("s"))
    }

    @Test
    fun noDateLeavesTheBoxBlank() {
        assertEquals("", cells(fill()).getValue("A56").textContent)
    }

    @Test
    fun typedSignatureIsBlockLettersInThePlainStyle() {
        val c = cells(fill(Signature.Typed("ALEX TREMBLAY")))
        val cell = c.getValue("C56")
        assertEquals("inlineStr", cell.getAttribute("t"))
        assertEquals("ALEX TREMBLAY", cell.textContent)
        // Not the box's script font: the caption's plain style, same border.
        assertEquals(c.getValue("C57").getAttribute("s"), cell.getAttribute("s"))
        assertFalse(entries(fill(Signature.Typed("ALEX TREMBLAY"))).containsKey(mediaName))
    }

    @Test
    fun drawnSignatureIsAPictureOnTheSignatureLine() {
        val image = png()
        val out = entries(fill(Signature.Drawn(image)))
        assertArrayEquals(image, out.getValue(mediaName))
        assertTrue(out.getValue(relsName).toString(Charsets.UTF_8).contains("Target=\"../media/signature.png\""))
        assertEquals("", cells(fill(Signature.Drawn(image))).getValue("C56").textContent)

        val drawing = xml(out.getValue(drawingName))
        val pics = drawing.getElementsByTagNameNS("*", "pic")
        val sig = (0 until pics.length).map { pics.item(it) as Element }.single {
            (it.getElementsByTagNameNS("*", "blip").item(0) as Element)
                .getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "embed") ==
                "rIdSignature"
        }
        val anchor = sig.parentNode as Element
        val from = anchor.getElementsByTagNameNS("*", "from").item(0) as Element
        fun part(tag: String) = from.getElementsByTagNameNS("*", tag).item(0).textContent.toLong()
        assertTrue(part("col") in 2L..6L)         // within C..G
        val ext = anchor.getElementsByTagNameNS("*", "ext").item(0) as Element
        val cx = ext.getAttribute("cx").toLong()
        val cy = ext.getAttribute("cy").toLong()
        assertEquals(300.0 / 80.0, cx.toDouble() / cy, 0.01)          // not distorted

        // Rows 53 to 56 (0-based 52..55): from under the STATIONNEMENT line
        // down to the signature line.
        val sheet = entries(template).getValue(sheetName).toString(Charsets.UTF_8)
        fun rowEmu(r: Int) = Math.round(
            Regex("""<row r="$r"[^>]*\bht="([\d.]+)"""").find(sheet)!!.groupValues[1].toDouble() * 12_700
        )
        val room = (53..56).sumOf { rowEmu(it) }
        val row = part("row")
        assertTrue(row in 52L..55L)
        val top = (53 until row.toInt() + 1).sumOf { rowEmu(it) } + part("rowOff")
        assertTrue("starts below the STATIONNEMENT line", top >= 0)
        assertTrue("ends on the signature line", top + cy <= room)
        assertTrue("uses the room", cy > room * 3 / 4)
    }

    @Test
    fun thePictureTakesAnUnusedShapeId() {
        fun ids(drawing: ByteArray) = xml(drawing).getElementsByTagNameNS("*", "cNvPr").let { n ->
            (0 until n.length).map { (n.item(it) as Element).getAttribute("id") }
        }
        val before = ids(entries(template).getValue(drawingName))
        val after = ids(entries(fill(Signature.Drawn(png()))).getValue(drawingName))
        assertEquals(before.size + 1, after.size)
        assertFalse(after.last() in before)
    }

    @Test
    fun onlyTheDrawingChangesForADrawnSignature() {
        val before = entries(template)
        val after = entries(fill(Signature.Drawn(png())))
        assertEquals(before.keys + mediaName, after.keys)
        for ((name, bytes) in before) {
            if (name !in setOf(sheetName, drawingName, relsName)) assertArrayEquals(name, bytes, after.getValue(name))
        }
    }

    @Test
    fun aDrawingMustBeAPng() {
        val r = runCatching { Signature.Drawn(byteArrayOf(1, 2, 3)) }
        assertTrue(r.isFailure)
    }

    @Test
    fun theSuggestionPutsTheFirstNameFirst() {
        assertEquals("ALEX TREMBLAY", Signature.suggestion("TREMBLAY, Alex"))
        assertEquals("ZOÉ D'AMOURS", Signature.suggestion("  Zoé   D'Amours "))
    }
}
