package com.transportlogger

import android.content.Context
import android.location.Geocoder
import java.util.Locale

/** Resolves a street address to lat/lng using the on-device geocoder. Call OFF the main thread. */
object Geocoding {

    fun isAvailable(): Boolean = Geocoder.isPresent()

    @Suppress("DEPRECATION")
    fun resolve(ctx: Context, address: String): Pair<Double, Double>? {
        if (address.isBlank()) return null
        return try {
            val results = Geocoder(ctx, Locale.CANADA).getFromLocationName(address, 1)
            results?.firstOrNull()?.let { it.latitude to it.longitude }
        } catch (e: Exception) {
            null   // no backend, throttled, or not found
        }
    }

    /** lat/lng -> street address (one line), or null. Call OFF the main thread. */
    @Suppress("DEPRECATION")
    fun reverse(ctx: Context, lat: Double, lng: Double): String? {
        return try {
            Geocoder(ctx, Locale.CANADA).getFromLocation(lat, lng, 1)
                ?.firstOrNull()?.getAddressLine(0)
        } catch (e: Exception) { null }
    }
}
