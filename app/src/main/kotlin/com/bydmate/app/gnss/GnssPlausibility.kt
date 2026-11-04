package com.bydmate.app.gnss

/**
 * Whether the car's position can be trusted right now (owner, 2026-11-04: GPS jamming and
 * spoofing near Kyiv threw the position tens of km, and Waze's limits and camera distances
 * with it). Pure: fed the fixes, the car's own speed and two of Waze's distances, all stamped
 * with one monotonic clock; answers [suspect] and reports every change to [onChange].
 *
 * Suspect on any of:
 *  - a fix further from the previous one than the car could have driven ([GnssFixChecks.jump]);
 *  - a fix whose error radius is absurd, or whose own clock jumped ([GnssFixChecks]);
 *  - a position standing still while the car drives fast ([GnssFixChecks.stuck]);
 *  - Waze's camera ahead receding by more than [CAMERA_RISE_M] between two reads while the car
 *    moves, unless the camera before was being passed (under [CAMERA_PASSED_M]);
 *  - Waze's distance to the destination rising by more than [TOTAL_RISE_M], or falling by
 *    [TOTAL_DROP_M] more than the car could have driven, within one route.
 *
 * Back to plausible only once [RECOVERY_MS] have passed since the last implausible event and at
 * least [MIN_GOOD_FIXES] plausible fixes came in meanwhile: no fixes, no recovery. Each fix is
 * compared with the one before it, so a position that stays wrong but moves consistently does
 * count as plausible again; nothing short of a map can tell that apart.
 */
class GnssPlausibility(private val onChange: (GnssTransition) -> Unit = {}) {
    private data class Reading(val atMs: Long, val meters: Int)

    private val speeds = CarSpeedTrack()
    /** Fixes of the last [GnssFixChecks.STUCK_MAX_WINDOW_MS], newest last; absurd ones left out. */
    private val recent = ArrayDeque<GnssFix>()
    private var camera: Reading? = null
    private var total: Reading? = null

    var suspect = false
        private set
    private var lastBadMs = 0L
    private var pausedSinceMs = 0L
    private var badEvents = 0
    private var goodFixes = 0

    fun onCarSpeed(atMs: Long, kmh: Int) = speeds.add(atMs, kmh)

    fun onFix(fix: GnssFix) {
        val why = judge(fix)
        if (why != null) bad(fix.atMs, why) else good(fix)
        if (GnssFixChecks.accuracy(fix) != null) return
        recent.addLast(fix)
        while (fix.atMs - recent.first().atMs > GnssFixChecks.STUCK_MAX_WINDOW_MS) recent.removeFirst()
    }

    fun onWazeCamera(atMs: Long, meters: Int) {
        val prev = camera
        camera = Reading(atMs, meters)
        if (prev == null || atMs - prev.atMs > CAMERA_GAP_MS || !moving(prev.atMs, atMs)) return
        if (prev.meters >= CAMERA_PASSED_M && meters - prev.meters > CAMERA_RISE_M) {
            bad(atMs, "Waze camera distance ${prev.meters} -> $meters m")
        }
    }

    /** [newRoute]: the reading starts a route, so it is not compared with the last route's. */
    fun onWazeTotal(atMs: Long, meters: Int, newRoute: Boolean) {
        val prev = total
        total = Reading(atMs, meters)
        if (newRoute || prev == null) return
        if (atMs - prev.atMs > TOTAL_GAP_MS || !moving(prev.atMs, atMs)) return
        val driven = speeds.maxDriven(prev.atMs, atMs)
        val rise = meters - prev.meters
        if (rise > TOTAL_RISE_M || -rise > driven * GnssFixChecks.SPEED_FACTOR + TOTAL_DROP_M) {
            bad(atMs, "Waze distance to destination ${prev.meters} -> $meters m")
        }
    }

    /** Why [fix] is implausible, or null. The first fix, and one long after the last, pass. */
    private fun judge(fix: GnssFix): String? {
        GnssFixChecks.accuracy(fix)?.let { return it }
        val prev = recent.lastOrNull() ?: return null
        if (fix.atMs - prev.atMs > GnssFixChecks.MAX_COMPARE_MS) return null
        GnssFixChecks.timeSkew(prev, fix)?.let { return it }
        GnssFixChecks.jump(prev, fix, speeds)?.let { return it }
        val old = recent.lastOrNull { fix.atMs - it.atMs >= GnssFixChecks.STUCK_MIN_WINDOW_MS } ?: return null
        return GnssFixChecks.stuck(old, fix, speeds)
    }

    /** The car's speed was seen above walking pace around [fromMs]..[toMs]; unknown is not moving. */
    private fun moving(fromMs: Long, toMs: Long): Boolean = (speeds.maxSpeed(fromMs, toMs) ?: 0.0) > MOVING_MPS

    private fun bad(atMs: Long, why: String) {
        lastBadMs = atMs
        goodFixes = 0
        badEvents++
        if (suspect) return
        suspect = true
        pausedSinceMs = atMs
        badEvents = 1
        onChange(GnssTransition(suspect = true, reason = why))
    }

    private fun good(fix: GnssFix) {
        if (!suspect) return
        goodFixes++
        val calm = fix.atMs - lastBadMs
        if (goodFixes < MIN_GOOD_FIXES || calm < RECOVERY_MS) return
        suspect = false
        onChange(
            GnssTransition(
                suspect = false,
                reason = "plausible for ${calm / 1000} s ($goodFixes fixes) after a pause of " +
                    "${(fix.atMs - pausedSinceMs) / 1000} s with $badEvents implausible events",
            ),
        )
    }

    companion object {
        const val RECOVERY_MS = 30_000L
        const val MIN_GOOD_FIXES = 5
        const val CAMERA_GAP_MS = 10_000L
        const val CAMERA_PASSED_M = 500
        const val CAMERA_RISE_M = 2_000
        const val TOTAL_GAP_MS = 60_000L
        const val TOTAL_RISE_M = 10_000
        const val TOTAL_DROP_M = 5_000
        private const val MOVING_MPS = 1.0
    }
}
