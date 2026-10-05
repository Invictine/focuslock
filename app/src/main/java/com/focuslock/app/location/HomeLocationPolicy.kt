package com.focuslock.app.location

import android.location.Location

/** A saved place used to pause location-bound rules when the user is away from home. */
@kotlinx.serialization.Serializable
data class HomePlace(
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float = 150f,
)

enum class HomeLocationStatus { DISABLED, AT_HOME, AWAY, UNAVAILABLE }

/** Pure location policy so stale, imprecise, and borderline readings remain unclassified. */
object HomeLocationPolicy {
    /** Permanent commitments apply everywhere, even without a known location. */
    fun shouldEnforceBlock(permanent: Boolean, homeEnforcementAllowed: Boolean): Boolean =
        permanent || homeEnforcementAllowed

    /** Only a reliable away reading can pause rules; unknown location keeps blocking active. */
    fun shouldEnforce(homeOnlyEnabled: Boolean, precisePermission: Boolean, backgroundPermission: Boolean, status: HomeLocationStatus): Boolean =
        !homeOnlyEnabled || !precisePermission || !backgroundPermission || status != HomeLocationStatus.AWAY

    fun isUsable(
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float,
        fixTimeMillis: Long,
        nowMillis: Long,
        maxAgeMillis: Long,
    ): Boolean = latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
        accuracyMeters.isFinite() && accuracyMeters >= 0f &&
        fixTimeMillis > 0L && fixTimeMillis <= nowMillis &&
        nowMillis - fixTimeMillis <= maxAgeMillis

    /** Returns null if the reading cannot reliably establish either side of the boundary. */
    fun isInside(place: HomePlace, location: Location): Boolean? {
        return isInside(place, location.latitude, location.longitude,
            if (location.hasAccuracy()) location.accuracy else Float.NaN)
    }

    fun isInside(place: HomePlace, latitude: Double, longitude: Double, accuracyMeters: Float): Boolean? {
        if (!place.latitude.isFinite() || place.latitude !in -90.0..90.0 ||
            !place.longitude.isFinite() || place.longitude !in -180.0..180.0 ||
            !place.radiusMeters.isFinite() || place.radiusMeters <= 0f ||
            !latitude.isFinite() || latitude !in -90.0..90.0 || !longitude.isFinite() || longitude !in -180.0..180.0 ||
            !accuracyMeters.isFinite() || accuracyMeters < 0f
        ) return null
        val earthRadius = 6_371_000.0
        val lat1 = Math.toRadians(place.latitude)
        val lat2 = Math.toRadians(latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(longitude - place.longitude)
        val a = (kotlin.math.sin(dLat / 2).let { it * it } +
            kotlin.math.cos(lat1) * kotlin.math.cos(lat2) * kotlin.math.sin(dLon / 2).let { it * it }
            ).coerceIn(0.0, 1.0)
        val distance = earthRadius * 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        // A radius that intersects the uncertainty circle is unknown, so blocking stays active.
        return when {
            distance + accuracyMeters <= place.radiusMeters -> true
            distance - accuracyMeters > place.radiusMeters -> false
            else -> null
        }
    }
}
