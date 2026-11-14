package com.bydmate.app.data.trips

import com.bydmate.app.data.local.entity.TripEntity
import kotlin.math.max
import kotlin.math.min

/**
 * Does one trip already stand for another drive? Shared by the shadow-trip resolution and the
 * anomaly detector, so both call the same drive "covered" (2026-11-14 missing trips).
 *
 * The odometer decides when both trips carry it: their ranges must share at least half of the
 * shorter one. Otherwise the time decides: the two windows, the other one widened by
 * [CLOCK_SLACK_MS] for the head unit's clock, must share at least half of the shorter one. Half and
 * not "any": a drive that ends a minute before the next one starts is another drive.
 * A trip without distance (a BYD idle-drain row) never covers a drive.
 */
object TripCoverage {

    /** "± a few minutes": skew between BYD's timestamps and ours. */
    const val CLOCK_SLACK_MS = 3 * 60_000L

    private const val SHARE = 0.5
    private const val MIN_ODO_OVERLAP_KM = 0.1

    fun covers(other: TripEntity, drive: TripEntity): Boolean {
        if ((other.distanceKm ?: 0.0) <= 0.0) return false
        val a = odometerOf(other)
        val b = odometerOf(drive)
        if (a != null && b != null) {
            val overlap = (min(a.endInclusive, b.endInclusive) - max(a.start, b.start)).coerceAtLeast(0.0)
            val shorter = min(a.endInclusive - a.start, b.endInclusive - b.start)
            return overlap >= max(MIN_ODO_OVERLAP_KM, shorter * SHARE)
        }
        return coversInTime(other, drive)
    }

    /** The time half of [covers]: the windows (the other one widened by [CLOCK_SLACK_MS]) share half of the shorter. */
    fun coversInTime(other: TripEntity, drive: TripEntity): Boolean {
        val driveEnd = drive.endTs ?: return false
        val otherEnd = other.endTs ?: other.startTs
        val overlap = min(otherEnd + CLOCK_SLACK_MS, driveEnd) - max(other.startTs - CLOCK_SLACK_MS, drive.startTs)
        if (overlap <= 0) return false
        val shorter = min(otherEnd - other.startTs, driveEnd - drive.startTs).coerceAtLeast(1L)
        return overlap >= shorter * SHARE
    }

    /** The trip's odometer range; null when either end is unknown or the range runs backwards. */
    fun odometerOf(t: TripEntity): ClosedFloatingPointRange<Double>? {
        val start = t.odometerStartKm ?: return null
        val end = t.odometerEndKm ?: return null
        return if (end >= start) start..end else null
    }

    /** Length of the union of [ranges] clipped to [from]..[to], km. */
    fun unionKm(ranges: List<ClosedFloatingPointRange<Double>>, from: Double, to: Double): Double {
        var covered = 0.0
        var reach = from
        for (r in ranges.sortedBy { it.start }) {
            val start = max(r.start, reach)
            val end = min(r.endInclusive, to)
            if (end > start) {
                covered += end - start
                reach = end
            }
        }
        return covered
    }
}
