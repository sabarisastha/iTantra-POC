package com.itantra.utils

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class LocationInfo(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val timestampMs: Long,
    val isStale: Boolean
)

object LocationHelper {

    private const val MAX_STALE_AGE_MS = 15 * 60 * 1000L // 15 minutes

    @SuppressLint("MissingPermission")
    fun getLastKnownLocation(context: Context): Location? {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return try {
            val providers = locationManager.getProviders(true)
            var bestLocation: Location? = null
            val now = System.currentTimeMillis()

            for (provider in providers) {
                val l = locationManager.getLastKnownLocation(provider) ?: continue
                // Skip if older than max stale age unless no other location exists
                val age = now - l.time
                if (bestLocation == null) {
                    bestLocation = l
                } else {
                    val bestAge = now - bestLocation.time
                    // Prefer fresher location if current best is older than 5 minutes
                    if (bestAge > 5 * 60 * 1000L && age < bestAge) {
                        bestLocation = l
                    } else if (age < MAX_STALE_AGE_MS && l.accuracy < bestLocation.accuracy) {
                        bestLocation = l
                    }
                }
            }
            bestLocation
        } catch (e: Exception) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    fun getLocationInfo(context: Context): LocationInfo? {
        val loc = getLastKnownLocation(context) ?: return null
        val age = System.currentTimeMillis() - loc.time
        return LocationInfo(
            latitude = loc.latitude,
            longitude = loc.longitude,
            accuracyMeters = loc.accuracy,
            timestampMs = loc.time,
            isStale = age > MAX_STALE_AGE_MS
        )
    }

    /**
     * Calculates distance in meters between two coordinates.
     */
    fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0]
    }

    /**
     * Calculates the bearing (0-360 degrees) from point 1 to point 2.
     */
    fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val deltaLon = Math.toRadians(lon2 - lon1)

        val y = sin(deltaLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(deltaLon)
        val bearingRad = atan2(y, x)
        var bearingDeg = Math.toDegrees(bearingRad).toFloat()
        if (bearingDeg < 0) bearingDeg += 360f
        return bearingDeg
    }

    fun getBearingDirection(bearing: Float): String {
        val directions = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val index = Math.round(((bearing % 360) / 45)).toInt() % 8
        return directions[index]
    }
}
