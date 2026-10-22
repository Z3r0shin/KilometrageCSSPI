package com.transportlogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class FormulaireHeaderTest {

    private val sheetName = "xl/worksheets/sheet1.xml"
    private val template = File("src/main/assets/formulaire_template.xlsx").readBytes()

    // Function and service differ from the template's defaults, to see them replaced.
    private val profile = Profile(
        name = "TREMBLAY, Alex", employeeNo = "12345", address = "100 rue de l'Exemple",
        city = "Montréal", postalCode = "H1A 1A1", function = "MEC", service = "SRI"
    )

    private val trips = listOf(
        Trip(1L, "2026-10-05", "ATELIER", "ÉCOLE A", 12.4, "CONFIRMÉ", "778899"),
        Trip(2L, "2026-10-06", "ÉCOLE A", "ÉCOLE B", 3.0, "CONFIRMÉ")
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

    private fun fill(p: Profile = profile): ByteArray =
        FormulaireExport.fill(template, FormulaireExport.lines(trips), "2026-10", p)

    private fun sheet(xlsx: ByteArray): ByteArray = entries(xlsx).getValue(sheetName)

    /** Every cell of the sheet by reference, read the way a spreadsheet would: as XML. */
    private fun cells(xlsx: ByteArray): Map<String, Element> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(sheet(xlsx).inputStream()).getElementsByTagName("c")
        return (0 until nodes.length).map { nodes.item(it) as Element }.associateBy { it.getAttribute("r") }
    }

    private fun Element.child(tag: String): String? = getElementsByTagName(tag).item(0)?.textContent

    /** The cell's inline string, or null if it holds anything else. */
    private fun Element.text(): String? = if (getAttribute("t") == "inlineStr") child("t") else null

    /** The cell's value as written, or null if the cell is not a plain number. */
    private fun Element.number(): String? = if (hasAttribute("t")) null else child("v")

    /** One cell exactly as it sits in the file. */
    private fun raw(xlsx: ByteArray, ref: String): String =
        Regex("""<c r="$ref"[^>]*?(?:/>|>.*?</c>)""", RegexOption.DOT_MATCHES_ALL)
            .find(sheet(xlsx).toString(Charsets.UTF_8))!!.value

    @Test
    fun theHeaderCarriesTheProfile() {
        val c = cells(fill())
        assertEquals("TREMBLAY, Alex", c.getValue("C13").text())
        assertEquals("100 rue de l'Exemple", c.getValue("C14").text())
        assertEquals("Montréal", c.getValue("C15").text())
        assertEquals("H1A 1A1", c.getValue("C16").text())
        assertEquals("MEC", c.getValue("J14").text())
        assertEquals("SRI", c.getValue("J15").text())
    }

    @Test
    fun theEmployeeNumberIsANumberWithoutDecimals() {
        val xlsx = fill()
        assertEquals("12345", cells(xlsx).getValue("J13").number())
        assertTrue(raw(xlsx, "J13").endsWith("><v>12345</v></c>"))
    }

    @Test
    fun anEmployeeNumberThatIsNotANumberIsText() {
        val cell = cells(fill(profile.copy(employeeNo = "A-778"))).getValue("J13")
        assertEquals("A-778", cell.text())
        assertNull(cell.number())
    }

    @Test
    fun aLeadingZeroIsKept() {
        assertEquals("00123", cells(fill(profile.copy(employeeNo = "00123"))).getValue("J13").text())
    }

    @Test
    fun headerCellsKeepTheirStyle() {
        val before = cells(template)
        val after = cells(fill())
        for (ref in listOf("C13", "J13", "C14", "C15", "C16", "J14", "J15")) {
            assertEquals(ref, before.getValue(ref).getAttribute("s"), after.getValue(ref).getAttribute("s"))
        }
    }

    @Test
    fun aBlankFieldLeavesTheTemplateCell() {
        val xlsx = fill(profile.copy(address = "", function = " "))
        assertEquals(raw(template, "C14"), raw(xlsx, "C14"))
        assertEquals(raw(template, "J14"), raw(xlsx, "J14"))
        assertEquals("TREMBLAY, Alex", cells(xlsx).getValue("C13").text())
    }

    @Test
    fun xmlSpecialCharactersAreEscaped() {
        val odd = profile.copy(name = "D'AMOURS & FILS, Zoé", address = "12 rue <A> & \"B\"")
        val xlsx = fill(odd)
        assertTrue(raw(xlsx, "C13").contains("<t>D'AMOURS &amp; FILS, Zoé</t>"))
        assertTrue(raw(xlsx, "C14").contains("12 rue &lt;A&gt; &amp; "))
        // Parsing it back is the real check: the sheet is still well-formed XML.
        val c = cells(xlsx)
        assertEquals(odd.name, c.getValue("C13").text())
        assertEquals(odd.address, c.getValue("C14").text())
    }

    @Test
    fun theTripRowsAreStillWritten() {
        val c = cells(fill())
        assertEquals("5", c.getValue("A23").number())
        assertEquals("ATELIER", c.getValue("B23").text())
        assertEquals("ÉCOLE A", c.getValue("E23").text())
        assertEquals("778899", c.getValue("J23").text())
        assertEquals("12.4", c.getValue("L23").number())
        assertEquals("1", c.getValue("M23").number())
        assertEquals("12.4", c.getValue("N23").number())
        assertEquals("6", c.getValue("A24").number())
        assertEquals("Octobre", c.getValue("C18").text())
        assertEquals("2026", c.getValue("E18").number())
        assertEquals(FormulaireExport.RATE_PER_KM.toString(), c.getValue("H50").number())
    }

    @Test
    fun everyTemplateEntryIsCopied() {
        val before = entries(template)
        val after = entries(fill())
        assertEquals(before.keys.toList(), after.keys.toList())
        for ((name, bytes) in before) {
            if (name != sheetName) assertArrayEquals(name, bytes, after.getValue(name))
        }
    }

    /** A rebuilt template that brings somebody's details back must fail the build. */
    @Test
    fun theTemplateNamesNobody() {
        val c = cells(template)
        for (ref in listOf("C13", "J13", "C14", "C15", "C16")) {
            assertEquals(ref, "", c.getValue(ref).textContent)
        }
        // The two the team shares still have to exist for an export to fill them.
        assertTrue(c.containsKey("J14") && c.containsKey("J15"))
    }

    /** The repo is public: no entry of the template may carry anyone's details. */
    @Test
    fun noEntryCarriesPersonalData() {
        // Patterns only: listing a real person's details here would publish them.
        val leaks = listOf("Maison ", "access_token", "sharepoint")
        val email = Regex("""[\w.+-]+@[\w-]+\.[\w.-]+""")
        for ((name, bytes) in entries(template)) {
            // Latin-1 maps every byte to one char, so binary parts are searched too.
            val text = bytes.toString(Charsets.UTF_8) + bytes.toString(Charsets.ISO_8859_1)
            for (leak in leaks) {
                assertTrue("$name contains \"$leak\"", !text.contains(leak, ignoreCase = true))
            }
            if (name.startsWith("docProps/")) {
                assertNull("$name holds an email address", email.find(text)?.value)
            }
        }
    }
}
