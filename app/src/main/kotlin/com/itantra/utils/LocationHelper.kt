package com.itantra.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.util.Log
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private const val TAG = "LocationHelper"

/**
 * Encapsulates a single GPS fix with accuracy and optional movement properties.
 */
data class LocationInfo(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val timestampMs: Long,
    val isStale: Boolean = false,
    val speedMps: Float? = null,
    val bearingDeg: Float? = null,
)

/**
 * Movement classification of a device from GPS observations.
 */
enum class MovementState(val label: String) {
    STATIONARY("Stationary"),
    MOVING("Moving"),
    UNKNOWN("Unknown / GPS unavailable")
}

/**
 * Relative direction between two moving peers.
 */
enum class RelativeDirection(val label: String) {
    SAME_DIRECTION("Same direction"),
    OPPOSITE_DIRECTION("Opposite direction"),
    DIVERGING("Diverging"),
    UNAVAILABLE("Direction unavailable")
}

/**
 * Aggregated live location state between local user and connected peer.
 */
data class LiveLocationState(
    val localLocation: LocationInfo? = null,
    val peerLocation: LocationInfo? = null,
    val distanceMeters: Float? = null,
    val localMovementState: MovementState = MovementState.UNKNOWN,
    val peerMovementState: MovementState = MovementState.UNKNOWN,
    val localBearingDeg: Float? = null,
    val peerBearingDeg: Float? = null,
    val relativeDirection: RelativeDirection = RelativeDirection.UNAVAILABLE,
    val lastPeerUpdateMs: Long = 0L,
) {
    val hasPeerLocation: Boolean
        get() = peerLocation != null && peerLocation.latitude != 0.0 && peerLocation.longitude != 0.0
}

/**
 * Tracks successive GPS location fixes to determine movement state and heading.
 */
class MovementTracker(
    private val maxFixHistory: Int = 5,
    private val headingThresholdDeg: Float = DEFAULT_HEADING_THRESHOLD_DEG,
) {
    companion object {
        const val DEFAULT_HEADING_THRESHOLD_DEG = 30.0f
        private const val MIN_ACCURACY_THRESHOLD_M = 60.0f
        private const val MIN_SPEED_MOVING_MPS = 0.6f
        private const val MAX_SPEED_STATIONARY_MPS = 0.35f
    }

    private val fixHistory = ArrayDeque<LocationInfo>()
    var currentState: MovementState = MovementState.UNKNOWN
        private set
    var currentBearing: Float? = null
        private set

    fun reset() {
        fixHistory.clear()
        currentState = MovementState.UNKNOWN
        currentBearing = null
    }

    fun addFix(fix: LocationInfo) {
        // Discard fixes with very low accuracy unless history is empty
        if (fix.accuracyMeters > MIN_ACCURACY_THRESHOLD_M && fixHistory.isNotEmpty()) {
            return
        }

        fixHistory.addLast(fix)
        while (fixHistory.size > maxFixHistory) {
            fixHistory.removeFirst()
        }

        updateMovementState()
    }

    private fun updateMovementState() {
        if (fixHistory.size < 2) {
            val single = fixHistory.lastOrNull()
            if (single?.speedMps != null) {
                if (single.speedMps >= MIN_SPEED_MOVING_MPS) {
                    currentState = MovementState.MOVING
                    currentBearing = single.bearingDeg
                } else if (single.speedMps <= MAX_SPEED_STATIONARY_MPS) {
                    currentState = MovementState.STATIONARY
                    currentBearing = null
                } else {
                    currentState = MovementState.UNKNOWN
                }
            } else {
                currentState = MovementState.UNKNOWN
            }
            return
        }

        val newest = fixHistory.last()
        val oldest = fixHistory.first()

        val dtSec = (newest.timestampMs - oldest.timestampMs) / 1000.0
        val dist = LocationHelper.calculateDistanceMeters(
            oldest.latitude, oldest.longitude,
            newest.latitude, newest.longitude
        )

        // If fix timestamps are too far apart (> 3 minutes), clear history and reset
        if (dtSec > 180.0 || dtSec <= 0.0) {
            val last = fixHistory.last()
            fixHistory.clear()
            fixHistory.addLast(last)
            currentState = MovementState.UNKNOWN
            currentBearing = null
            return
        }

        val calcSpeed = (dist / dtSec).toFloat()
        val minDisplacement = maxOf(6.0f, (newest.accuracyMeters + oldest.accuracyMeters) * 0.4f)

        if (dist >= minDisplacement && (calcSpeed >= MIN_SPEED_MOVING_MPS || (newest.speedMps ?: 0f) >= MIN_SPEED_MOVING_MPS)) {
            currentState = MovementState.MOVING
            currentBearing = newest.bearingDeg ?: LocationHelper.calculateBearing(
                oldest.latitude, oldest.longitude,
                newest.latitude, newest.longitude
            )
        } else if (dist < minDisplacement * 0.6f || calcSpeed <= MAX_SPEED_STATIONARY_MPS) {
            currentState = MovementState.STATIONARY
            currentBearing = null
        } else {
            currentState = MovementState.UNKNOWN
            currentBearing = null
        }
    }
}

enum class LocationAcquisitionStatus(val label: String) {
    AVAILABLE("Location available"),
    WAITING_FOR_LOCATION("Waiting for location"),
    PERMISSION_DENIED("Permission denied"),
    LOCATION_SERVICES_DISABLED("Location services disabled"),
    UNAVAILABLE("Location unavailable")
}

data class LocationFetchResult(
    val status: LocationAcquisitionStatus,
    val locationInfo: LocationInfo? = null,
    val detailMessage: String = "",
)

object LocationHelper {

    private const val MAX_STALE_AGE_MS = 15 * 60 * 1000L // 15 minutes

    fun checkPermissionState(context: Context): Triple<Boolean, Boolean, Boolean> {
        val fine = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val any = fine || coarse
        return Triple(any, fine, coarse)
    }

    fun isLocationServicesEnabled(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return try {
            androidx.core.location.LocationManagerCompat.isLocationEnabled(locationManager)
        } catch (e: Exception) {
            val gps = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val network = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            gps || network
        }
    }

    fun toLocationInfo(loc: Location): LocationInfo {
        val age = System.currentTimeMillis() - loc.time
        return LocationInfo(
            latitude = loc.latitude,
            longitude = loc.longitude,
            accuracyMeters = loc.accuracy,
            timestampMs = loc.time,
            isStale = age > MAX_STALE_AGE_MS,
            speedMps = if (loc.hasSpeed()) loc.speed else null,
            bearingDeg = if (loc.hasBearing()) loc.bearing else null,
        )
    }

    @SuppressLint("MissingPermission")
    fun getLastKnownLocation(context: Context): Location? {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val (hasPerm, _, _) = checkPermissionState(context)
        if (!hasPerm) return null

        return try {
            val providers = locationManager.allProviders
            var bestLocation: Location? = null
            val now = System.currentTimeMillis()

            for (provider in providers) {
                val l = try {
                    locationManager.getLastKnownLocation(provider)
                } catch (e: Exception) {
                    null
                } ?: continue

                val age = now - l.time
                if (bestLocation == null) {
                    bestLocation = l
                } else {
                    val bestAge = now - bestLocation.time
                    if (bestAge > 2 * 60 * 1000L && age < bestAge) {
                        bestLocation = l
                    } else if (age < MAX_STALE_AGE_MS && l.accuracy < bestLocation.accuracy) {
                        bestLocation = l
                    }
                }
            }
            bestLocation
        } catch (e: Exception) {
            Log.w(TAG, "getLastKnownLocation failed: ${e.message}")
            null
        }
    }

    @SuppressLint("MissingPermission")
    fun getLocationInfo(context: Context): LocationInfo? {
        val loc = getLastKnownLocation(context) ?: return null
        return toLocationInfo(loc)
    }

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(context: Context, timeoutMs: Long = 2500L): LocationFetchResult {
        val (hasPerm, fineGranted, coarseGranted) = checkPermissionState(context)
        if (!hasPerm) {
            val reason = "Permission denied (fine=$fineGranted, coarse=$coarseGranted)"
            Log.w(TAG, "[LocationHelper Diagnostics] $reason")
            return LocationFetchResult(
                status = LocationAcquisitionStatus.PERMISSION_DENIED,
                detailMessage = reason
            )
        }

        val locationServicesEnabled = isLocationServicesEnabled(context)
        if (!locationServicesEnabled) {
            val reason = "Location services / GPS is disabled on device"
            Log.w(TAG, "[LocationHelper Diagnostics] $reason")
            return LocationFetchResult(
                status = LocationAcquisitionStatus.LOCATION_SERVICES_DISABLED,
                detailMessage = reason
            )
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager == null) {
            return LocationFetchResult(
                status = LocationAcquisitionStatus.UNAVAILABLE,
                detailMessage = "LocationManager service not available"
            )
        }

        // 1. Fast path: check if recent cached fix exists (< 15 seconds old)
        val lastLoc = getLastKnownLocation(context)
        val lastAge = if (lastLoc != null) System.currentTimeMillis() - lastLoc.time else Long.MAX_VALUE
        if (lastLoc != null && lastAge < 15_000L && lastLoc.accuracy <= 40f) {
            val info = toLocationInfo(lastLoc)
            Log.i(
                TAG,
                "[LocationHelper Diagnostics] permissionGranted=true, fineGranted=$fineGranted, coarseGranted=$coarseGranted, " +
                "locationServicesEnabled=true, lastLocationAvailable=true, freshLocationReceived=false (used fresh cache), " +
                "locationAccuracy=${info.accuracyMeters}, provider/error reason=Cached ${lastLoc.provider} fix (${lastAge}ms old)"
            )
            return LocationFetchResult(
                status = LocationAcquisitionStatus.AVAILABLE,
                locationInfo = info,
                detailMessage = "Cached ${lastLoc.provider} fix"
            )
        }

        // 2. Fresh GPS/Network fix acquisition with coroutine timeout
        var freshReceived = false
        var freshLocation: Location? = null

        try {
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                kotlinx.coroutines.suspendCancellableCoroutine<Location?> { cont ->
                    val listener = object : android.location.LocationListener {
                        override fun onLocationChanged(location: Location) {
                            freshReceived = true
                            freshLocation = location
                            if (cont.isActive) {
                                cont.resume(location) { _, _, _ -> }
                            }
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {}
                    }

                    val mainLooper = android.os.Looper.getMainLooper()
                    val registered = mutableListOf<String>()

                    if (fineGranted && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        try {
                            locationManager.requestLocationUpdates(
                                LocationManager.GPS_PROVIDER,
                                0L,
                                0f,
                                listener,
                                mainLooper
                            )
                            registered.add(LocationManager.GPS_PROVIDER)
                        } catch (e: Exception) {
                            Log.w(TAG, "GPS listener registration error: ${e.message}")
                        }
                    }

                    if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                        try {
                            locationManager.requestLocationUpdates(
                                LocationManager.NETWORK_PROVIDER,
                                0L,
                                0f,
                                listener,
                                mainLooper
                            )
                            registered.add(LocationManager.NETWORK_PROVIDER)
                        } catch (e: Exception) {
                            Log.w(TAG, "Network listener registration error: ${e.message}")
                        }
                    }

                    if (locationManager.allProviders.contains("fused") && locationManager.isProviderEnabled("fused")) {
                        try {
                            locationManager.requestLocationUpdates(
                                "fused",
                                0L,
                                0f,
                                listener,
                                mainLooper
                            )
                            registered.add("fused")
                        } catch (e: Exception) {
                            // ignore fused provider error
                        }
                    }

                    cont.invokeOnCancellation {
                        try {
                            locationManager.removeUpdates(listener)
                        } catch (e: Exception) {
                            // ignore
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception during fresh location acquisition: ${e.message}")
        }

        val finalLoc = freshLocation ?: lastLoc
        val finalAge = if (finalLoc != null) System.currentTimeMillis() - finalLoc.time else null

        // Log exact required diagnostic state
        Log.i(
            TAG,
            "[LocationHelper Diagnostics] permissionGranted=true, fineGranted=$fineGranted, coarseGranted=$coarseGranted, " +
            "locationServicesEnabled=$locationServicesEnabled, lastLocationAvailable=${lastLoc != null}, " +
            "freshLocationReceived=$freshReceived, locationAccuracy=${finalLoc?.accuracy}, " +
            "provider/error reason=${if (finalLoc != null) (if (freshReceived) "Fresh ${finalLoc.provider} fix" else "Fallback to last known ${finalLoc.provider} fix (${finalAge?.let { it / 1000 }}s old)") else "No location fix received within ${timeoutMs}ms"}"
        )

        return if (finalLoc != null) {
            val info = toLocationInfo(finalLoc)
            LocationFetchResult(
                status = LocationAcquisitionStatus.AVAILABLE,
                locationInfo = info,
                detailMessage = if (freshReceived) "Fresh fix from ${finalLoc.provider}" else "Last known fix from ${finalLoc.provider}"
            )
        } else {
            LocationFetchResult(
                status = LocationAcquisitionStatus.UNAVAILABLE,
                detailMessage = "Location unavailable (timeout after ${timeoutMs}ms)"
            )
        }
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

    /**
     * Compares two headings to determine if users are moving in the same or opposite directions.
     */
    fun calculateRelativeDirection(
        localBearing: Float?,
        peerBearing: Float?,
        localState: MovementState,
        peerState: MovementState,
        thresholdDeg: Float = MovementTracker.DEFAULT_HEADING_THRESHOLD_DEG,
    ): RelativeDirection {
        if (localState != MovementState.MOVING || peerState != MovementState.MOVING ||
            localBearing == null || peerBearing == null
        ) {
            return RelativeDirection.UNAVAILABLE
        }

        val diff = Math.abs((localBearing - peerBearing + 180 + 360) % 360 - 180)
        return when {
            diff <= thresholdDeg -> RelativeDirection.SAME_DIRECTION
            diff >= (180f - thresholdDeg) -> RelativeDirection.OPPOSITE_DIRECTION
            else -> RelativeDirection.DIVERGING
        }
    }

    fun formatDistance(meters: Float?): String {
        if (meters == null || meters.isNaN()) return "Distance unavailable"
        return if (meters < 1000f) {
            "${meters.toInt()} m"
        } else {
            String.format(Locale.US, "%.1f km", meters / 1000f)
        }
    }

    fun formatCoordinates(lat: Double?, lon: Double?): String {
        if (lat == null || lon == null) return "Location unavailable"
        return String.format(Locale.US, "%.4f, %.4f", lat, lon)
    }

    fun formatAccuracy(accuracyMeters: Float?): String {
        if (accuracyMeters == null || accuracyMeters <= 0f) return ""
        return String.format(Locale.US, "±%.0fm", accuracyMeters)
    }

    fun formatLocationAge(timestampMs: Long?): String {
        if (timestampMs == null || timestampMs <= 0L) return ""
        val ageSec = (System.currentTimeMillis() - timestampMs) / 1000
        return when {
            ageSec < 5 -> "just now"
            ageSec < 60 -> "${ageSec}s ago"
            ageSec < 3600 -> "${ageSec / 60}m ago"
            else -> "${ageSec / 3600}h ago"
        }
    }

    /**
     * Optional external Map viewer launcher using offline-compatible geo URI.
     */
    fun openMap(context: Context, latitude: Double, longitude: Double, label: String = "Alert Location") {
        try {
            val uri = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude(${Uri.encode(label)})")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot launch external map application: ${e.message}")
        }
    }
}
