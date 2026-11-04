package com.bydmate.app.gnss

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The checks one fix goes through, each answering why it is implausible or null. The reasons
 * carry distances, times and speeds only, never a coordinate: they go to the trip log.
 */
object GnssFixChecks {
    /** A fix may land this many times as far as the car could have driven ... */
    const val SPEED_FACTOR = 1.5
    /** ... plus this much and both fixes' error radii: GPS noise, a parked car's wander. */
    const val HEADROOM_M = 150.0
    /** An error radius wider than this is no position at all. */
    const val ABSURD_ACCURACY_M = 500f
    /** The fix's own clock may run off the arrival clock by this much between two fixes. */
    const val TIME_SKEW_MS = 60_000L
    /** Fixes further apart than this are not compared: the car may have done anything since. */
    const val MAX_COMPARE_MS = 10 * 60_000L

    /** The position-stuck check: the car at least this fast all the way (40 km/h) ... */
    const val STUCK_MIN_MPS = 40 / CarSpeedTrack.KMH_PER_MPS
    /** ... over at least this long ... */
    const val STUCK_MIN_WINDOW_MS = 10_000L
    const val STUCK_MAX_WINDOW_MS = 60_000L
    /** ... and the fixes, error radii added, moved less than this share of the least it drove. */
    const val STUCK_FACTOR = 0.25

    fun accuracy(fix: GnssFix): String? {
        val acc = fix.accuracyM ?: return null
        return if (acc > ABSURD_ACCURACY_M) "accuracy ${acc.roundToInt()} m" else null
    }

    /** The fix's clock jumped against the arrival clock since [prev] (a spoofed time, a reset). */
    fun timeSkew(prev: GnssFix, fix: GnssFix): String? {
        val skew = (fix.fixTimeMs - prev.fixTimeMs) - (fix.atMs - prev.atMs)
        return if (abs(skew) > TIME_SKEW_MS) "fix time jumped ${skew / 1000} s" else null
    }

    /** The fix is further from [prev] than the car could have driven, with a generous margin. */
    fun jump(prev: GnssFix, fix: GnssFix, speeds: CarSpeedTrack): String? {
        val dt = fix.atMs - prev.atMs
        val driven = speeds.maxDriven(prev.atMs, fix.atMs)
        val allowed = driven * SPEED_FACTOR + HEADROOM_M + prev.errorM + fix.errorM
        val moved = distanceMeters(prev, fix)
        if (moved <= allowed) return null
        return "jump ${moved.roundToInt()} m in ${dt / 1000} s, the car drove at most ${driven.roundToInt()} m"
    }

    /**
     * The position hardly moved from [old] while the car drove fast all the way: a spoofer's
     * standing point, jittering past the listener's distance filter. Only when [old] is
     * [STUCK_MIN_WINDOW_MS]..[STUCK_MAX_WINDOW_MS] back and the car's speed covers all of it.
     */
    fun stuck(old: GnssFix, fix: GnssFix, speeds: CarSpeedTrack): String? {
        val dt = fix.atMs - old.atMs
        if (dt < STUCK_MIN_WINDOW_MS || dt > STUCK_MAX_WINDOW_MS) return null
        val slowest = speeds.minSpeed(old.atMs, fix.atMs) ?: return null
        if (slowest < STUCK_MIN_MPS) return null
        val moved = distanceMeters(old, fix)
        if (moved + old.errorM + fix.errorM >= slowest * dt / MS_PER_S * STUCK_FACTOR) return null
        return "position stuck: ${moved.roundToInt()} m in ${dt / 1000} s " +
            "with the car at ${(slowest * CarSpeedTrack.KMH_PER_MPS).roundToInt()} km/h or more"
    }

    private const val MS_PER_S = 1000.0
}
