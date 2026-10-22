package com.transportlogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneMarginTest {

    // A 100 m x 100 m outline centred on a point in Pointe-aux-Trembles.
    private val lat = 45.67
    private val lng = -73.50
    private val square = PlaceStore.rectCorners(lat, lng, 100.0, 100.0, 0.0)
    private val place = Place("ÉCOLE", lat, lng, 150.0, polygon = square)

    /** A point [metres] east of the outline's east edge. */
    private fun eastOfEdge(metres: Double): Double =
        lng + (50.0 + metres) / (111_320.0 * Math.cos(Math.toRadians(lat)))

    @Test
    fun aPointJustOutsideNeedsTheMargin() {
        val kerb = eastOfEdge(10.0)
        assertFalse(place.contains(lat, kerb))
        assertTrue(place.contains(lat, kerb, margin = 20.0))
    }

    @Test
    fun theMarginHasAnEnd() {
        assertFalse(place.contains(lat, eastOfEdge(30.0), margin = 20.0))
    }

    @Test
    fun insideIsInsideWhateverTheMargin() {
        assertTrue(place.contains(lat, lng))
        assertTrue(place.contains(lat, lng, margin = 20.0))
    }

    @Test
    fun distanceIsMeasuredToTheNearestEdge() {
        assertEquals(10.0, PlaceStore.distanceToOutline(square, lat, eastOfEdge(10.0)), 0.5)
    }

    @Test
    fun aCircleZoneGrowsByTheMargin() {
        val circle = Place("ATELIER", lat, lng, 50.0)
        val p = eastOfEdge(10.0)                       // 60 m from the centre
        assertFalse(circle.contains(lat, p))
        assertTrue(circle.contains(lat, p, margin = 20.0))
    }
}
