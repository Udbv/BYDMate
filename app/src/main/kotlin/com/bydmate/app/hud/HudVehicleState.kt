package com.bydmate.app.hud

import android.location.Location

/**
 * Last GNSS fix for the HUD channels. The AR-HUD field set (`HudRoadInfoNotifyStruct`
 * f19/f20/f21/f22/f32) and the launcher-map context topics carry the vehicle position; the
 * factory map fills them from its own positioning, openbyd from the last known location.
 * Fed by TrackingService.onLocationChanged; read by the push loop every tick.
 */
object HudVehicleState {
    data class Fix(
        val lat: Double,
        val lon: Double,
        /** Degrees, 0..360, course over ground. */
        val bearing: Double,
        val speedKmh: Int,
        val altitudeM: Int,
        val timeMs: Long,
    )

    @Volatile var fix: Fix? = null
        private set

    fun update(location: Location) {
        fix = Fix(
            lat = location.latitude,
            lon = location.longitude,
            bearing = if (location.hasBearing()) location.bearing.toDouble() else fix?.bearing ?: 0.0,
            speedKmh = if (location.hasSpeed()) Math.round(location.speed * 3.6f) else fix?.speedKmh ?: 0,
            altitudeM = if (location.hasAltitude()) Math.round(location.altitude).toInt() else 0,
            timeMs = location.time,
        )
    }

    /** Tests and the clear path. */
    fun set(fix: Fix?) { this.fix = fix }
}
