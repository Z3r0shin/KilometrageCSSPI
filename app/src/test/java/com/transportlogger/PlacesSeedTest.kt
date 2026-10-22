package com.transportlogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The places a fresh install starts with. Android's org.json is a stub in JVM
 * tests, so the file is checked as text: it is a flat array of objects whose
 * only nesting is the polygon's arrays, which a brace match takes apart.
 */
class PlacesSeedTest {

    private val json = File("src/main/assets/places_seed.json").readText()
    private val place = Regex("""\{[^{}]*\}""")
    private val places = place.findAll(json).map { it.value }.toList()

    private fun number(obj: String, key: String): Double? =
        Regex(""""$key"\s*:\s*(-?\d+(?:\.\d+)?)""").find(obj)?.groupValues?.get(1)?.toDouble()

    @Test
    fun itIsAnArrayOfPlacesAndNothingElse() {
        val rest = place.replace(json, "").filterNot { it.isWhitespace() }
        assertTrue(rest.startsWith("[") && rest.endsWith("]"))
        assertTrue(rest.substring(1, rest.length - 1).all { it == ',' })
        assertTrue("only ${places.size} places", places.size >= 100)
    }

    @Test
    fun everyPlaceHasANameAndSitsAroundMontreal() {
        for (p in places) {
            val name = Regex(""""name"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(p)?.groupValues?.get(1)
            assertTrue(p.take(80), !name.isNullOrBlank())
            val lat = number(p, "lat")
            val lng = number(p, "lng")
            assertTrue("$name lat $lat", lat != null && lat in 45.0..46.0)
            assertTrue("$name lng $lng", lng != null && lng in -74.0..-73.0)
        }
    }

    /**
     * The seed must agree with the corrected reference list: three zones once
     * shipped sitting on a neighbour's building because only the list was fixed.
     */
    @Test
    fun everyPlaceSitsWhereTheReferenceListPutsIt() {
        val row = Regex("""^(.*?),"(?:[^"]*)",(-?\d+\.\d+),(-?\d+\.\d+)$""")
        val reference = File("src/main/assets/adresses_corrigees.csv").readLines()
            .drop(1).mapNotNull { row.find(it.trim().removePrefix("﻿")) }
            .associate { it.groupValues[1] to (it.groupValues[2].toDouble() to it.groupValues[3].toDouble()) }
        val seeded = places.associate { p ->
            val name = Regex(""""name"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(p)!!.groupValues[1]
            name to (number(p, "lat")!! to number(p, "lng")!!)
        }
        assertEquals(reference.keys, seeded.keys)
        for ((name, at) in seeded) {
            val (lat, lng) = reference.getValue(name)
            val off = PlaceStore.distanceMeters(at.first, at.second, lat, lng)
            assertTrue("$name is ${off.toInt()} m from the reference list", off <= 60.0)
        }
    }

    @Test
    fun nobodysHomeIsAmongThem() {
        assertFalse(Regex(""""isHome"\s*:\s*true""").containsMatchIn(json))
    }
}
