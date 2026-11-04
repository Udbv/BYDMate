package com.bydmate.app.gnss

import android.location.Location
import android.os.SystemClock
import android.util.Log
import com.bydmate.app.diagnostics.TripDebugLog
import com.bydmate.app.navdata.NavGuidanceHub
import com.bydmate.app.navdata.WazeReportsReader

/**
 * The process-wide [GnssPlausibility]: fed by the tracking service (fixes, the car's speed) and
 * by [NavGuidanceHub] (Waze's camera and destination distances), read by what BYDMate derives
 * from Waze's position-bound data. While [suspect]:
 *  - the ADAS limit sender sends nothing (one clear, the car is back on its own camera);
 *  - BYDMate's maneuver voice and the cruise advice say nothing;
 *  - the HUD and the instrument panel show no limit and no camera ([withheld]); the maneuver
 *    stays there, as it is on Waze's own screen beside it.
 * Nothing is written to the car for this: it only withholds. Every change is one log line under
 * [TAG] and the trip log's GNSS tag, without coordinates.
 */
object GnssGuard {
    const val TAG = "GnssGuard"

    /** Monotonic: the wall clock can itself be set from a spoofed GPS time. */
    internal var clock: () -> Long = { SystemClock.elapsedRealtime() }
    private var core = GnssPlausibility(::report)

    /** True while the position is implausible; read lock-free by the consumers' loops. */
    @Volatile var suspect = false
        private set

    @Synchronized
    fun onLocation(location: Location) {
        core.onFix(
            GnssFix(
                atMs = clock(),
                fixTimeMs = location.time,
                lat = location.latitude,
                lon = location.longitude,
                accuracyM = if (location.hasAccuracy()) location.accuracy else null,
                provider = location.provider ?: "?",
            ),
        )
    }

    /** The car's own speed, km/h, from the vehicle data; null (no reading) is skipped. */
    @Synchronized
    fun onCarSpeed(kmh: Int?) {
        if (kmh != null) core.onCarSpeed(clock(), kmh)
    }

    @Synchronized
    fun onWazeCamera(meters: Int) = core.onWazeCamera(clock(), meters)

    @Synchronized
    fun onWazeTotal(meters: Int, newRoute: Boolean) = core.onWazeTotal(clock(), meters, newRoute)

    /** [s] without what is wrong when the position is: the speed limit and the camera. */
    fun withheld(s: NavGuidanceHub.Snapshot): NavGuidanceHub.Snapshot = if (!suspect) s else s.copy(
        speedLimit = 0,
        cameraAlert = "",
        cameraDistanceMeters = 0,
        cameraIconPng = null,
        cameraKind = WazeReportsReader.Kind.OTHER,
        cameraAlertMs = 0L,
    )

    /** A fresh guard (tests). */
    @Synchronized
    internal fun reset() {
        core = GnssPlausibility(::report)
        suspect = false
    }

    private fun report(change: GnssTransition) {
        suspect = change.suspect
        val line = if (change.suspect) {
            "position implausible (${change.reason}): Waze limit and camera withheld, nav voice paused"
        } else {
            "position plausible again (${change.reason}): resumed"
        }
        Log.i(TAG, line)
        TripDebugLog.event("GNSS", line)
    }
}
