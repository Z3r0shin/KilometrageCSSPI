package com.transportlogger

import org.junit.Assert.assertEquals
import org.junit.Test

class FormulaireLinesTest {

    private var clock = 0L

    private fun trip(from: String, to: String, km: Double? = 5.0, po: String? = null, date: String = "2026-10-05") =
        Trip(++clock, date, from, to, km, "CONFIRMÉ", po)

    private fun summary(trips: List<Trip>) =
        FormulaireExport.lines(trips).map { "${it.trip.depart}>${it.trip.arrivee}x${it.legs}" }

    @Test
    fun onlyTheDirectReturnPairs() {
        val day = listOf(
            trip("X", "Y"), trip("Y", "Z"), trip("Z", "Y"), trip("Y", "A"), trip("A", "X")
        )
        assertEquals(listOf("X>Yx1", "Y>Zx2", "Y>Ax1", "A>Xx1"), summary(day))
    }

    @Test
    fun aLegIsUsedOnce() {
        val day = listOf(trip("X", "Y"), trip("Y", "X"), trip("X", "Y"))
        assertEquals(listOf("X>Yx2", "X>Yx1"), summary(day))
    }

    @Test
    fun differentDaysDoNotPair() {
        val trips = listOf(trip("X", "Y"), trip("Y", "X", date = "2026-10-06"))
        assertEquals(listOf("X>Yx1", "Y>Xx1"), summary(trips))
    }

    @Test
    fun differentPoNumbersDoNotPair() {
        val trips = listOf(trip("X", "Y", po = "A1"), trip("Y", "X", po = "B2"))
        assertEquals(listOf("X>Yx1", "Y>Xx1"), summary(trips))
    }

    @Test
    fun thePairTakesWhicheverPoWasEntered() {
        val line = FormulaireExport.lines(listOf(trip("X", "Y"), trip("Y", "X", po = "B2"))).single()
        assertEquals("B2", line.po)
        assertEquals(2, line.legs)
    }

    @Test
    fun thePairCarriesTheOutboundKm() {
        val line = FormulaireExport.lines(listOf(trip("X", "Y", km = 3.0), trip("Y", "X", km = 6.0))).single()
        assertEquals(3.0, line.km!!, 0.0)
    }
}
