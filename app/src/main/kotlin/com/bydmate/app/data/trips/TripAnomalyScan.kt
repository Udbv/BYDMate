package com.bydmate.app.data.trips

import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.local.entity.TripMinuteEntity
import com.bydmate.app.data.repository.OdometerMarks
import com.bydmate.app.domain.tracker.looksLikeCharging
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The pure part of TripAnomalyDetector (2026-11-14 missing trips): where the odometer moved, how
 * much of it the trips cover, and the trip rebuilt for what they do not.
 *
 * Movement comes from the odometer marks (one entry per app process, kept 14 days): inside a
 * process from its first reading to its last odometer change, and between two processes (the car
 * drove while the app was not running) from the last reading of one to the first of the next.
 * A stretch faster than [MAX_PLAUSIBLE_KMH] is an odometer glitch or a scale change, not a drive.
 */
internal object TripAnomalyScan {

    /** Uncovered odometer distance from which a drive is missing. */
    const val MIN_UNCOVERED_KM = 1.0

    /** SoC drop (points) without movement that is worth a log line (standing consumption). */
    const val MIN_SOC_DROP = 3

    const val MAX_PLAUSIBLE_KMH = 200.0
    private const val PLAUSIBLE_SLACK_KM = 2.0
    private const val MS_PER_HOUR = 3_600_000.0
    const val MINUTE_MS = 60_000L

    /** Covered less than this: the segment is the missing drive whole, odometer included. */
    private const val NOTHING_COVERED_KM = 0.1

    /**
     * Odometer movement [fromKm]..[toKm] between [startTs] and [endTs]; [aliveTo] is the last
     * reading of the process ([gap]: the first reading of the next one), for the SoC window.
     */
    data class Segment(
        val startTs: Long,
        val endTs: Long,
        val aliveTo: Long,
        val fromKm: Double,
        val toKm: Double,
        val gap: Boolean,
    ) {
        val km: Double get() = toKm - fromKm

        /** The segment as a trip, for [TripCoverage]. */
        fun asTrip(): TripEntity = TripEntity(
            startTs = startTs, endTs = endTs, distanceKm = km, odometerStartKm = fromKm, odometerEndKm = toKm,
        )
    }

    data class Finding(val segment: Segment, val uncoveredKm: Double, val coveredKm: Double, val covering: List<TripEntity>)

    /**
     * Segments of [marks] after the last check ([sinceTs], [sinceKm]: the time and odometer it
     * reached; a segment it already saw counts only from [sinceKm] on). Implausible ones dropped.
     */
    fun segments(marks: List<OdometerMarks.Mark>, sinceTs: Long, sinceKm: Double?): List<Segment> {
        val sorted = marks.sortedBy { it.firstTs }
        val own = sorted.map {
            Segment(it.firstTs, max(it.firstTs, it.lastChangeTs), it.lastTs, it.firstKm, it.lastKm, gap = false)
        }
        val gaps = sorted.zipWithNext { a, b -> Segment(a.lastTs, b.firstTs, b.firstTs, a.lastKm, b.firstKm, gap = true) }
        return (own + gaps)
            .filter { it.aliveTo > sinceTs }
            .map { seg -> if (sinceKm != null && sinceKm > seg.fromKm) seg.copy(fromKm = min(sinceKm, seg.toKm)) else seg }
            .filter { plausible(it) }
            .sortedBy { it.startTs }
    }

    private fun plausible(seg: Segment): Boolean {
        if (seg.km < 0.0) return false
        val hours = (seg.aliveTo - seg.startTs).coerceAtLeast(0L) / MS_PER_HOUR
        return seg.km <= hours * MAX_PLAUSIBLE_KMH + PLAUSIBLE_SLACK_KM
    }

    /**
     * How much of [seg] the [trips] (stored, shadow and open ones) cover. The odometer decides for
     * trips that carry it; a trip without one, or one whose odometer is on another scale (further
     * than a drive away), counts with its distance when its time covers the segment.
     */
    fun finding(seg: Segment, trips: List<TripEntity>): Finding {
        val driving = trips.filter { (it.distanceKm ?: 0.0) > 0.0 }
        val asTrip = seg.asTrip()
        val byOdometer = driving.filter { t ->
            TripCoverage.odometerOf(t)?.let { it.endInclusive > seg.fromKm && it.start < seg.toKm } == true
        }
        val odoKm = TripCoverage.unionKm(byOdometer.mapNotNull { TripCoverage.odometerOf(it) }, seg.fromKm, seg.toKm)
        val byTime = driving.filter { t ->
            val odo = TripCoverage.odometerOf(t)
            val otherScale = odo != null && kotlin.math.abs(odo.start - seg.fromKm) > TripMath.MAX_PLAUSIBLE_TRIP_KM
            (odo == null || otherScale) && TripCoverage.coversInTime(t, asTrip)
        }
        val covered = min(seg.km, odoKm + byTime.sumOf { it.distanceKm ?: 0.0 })
        return Finding(seg, (seg.km - covered).coerceAtLeast(0.0), covered, byOdometer + byTime)
    }

    /**
     * First and last SoC of [minutes] inside [from]..[to]; for a [gap] (the app was not running, no
     * minutes inside) and when none is inside, the readings just before [from] and just after [to].
     */
    fun socAround(minutes: List<TripMinuteEntity>, from: Long, to: Long, gap: Boolean = false): Pair<Int?, Int?> {
        val withSoc = minutes.filter { it.soc != null }
        val inside = if (gap) emptyList() else withSoc.filter { it.minuteTs >= from - MINUTE_MS && it.minuteTs <= to }
        if (inside.isNotEmpty()) return inside.first().soc to inside.last().soc
        return withSoc.lastOrNull { it.minuteTs <= from }?.soc to withSoc.firstOrNull { it.minuteTs >= to }?.soc
    }

    /**
     * The trip rebuilt for [f]: its window is the driving minutes of the segment that no covering
     * trip holds (the segment's own bounds without such minutes), the distance the uncovered km.
     * The odometer is set only when nothing covered the segment (then it is the segment's).
     * Energy: the per-minute energy when minutes exist, else the SoC drop times [capacityKwh]
     * (not for a DM-i, [hybrid]: the engine charges the pack).
     */
    fun recoveredTrip(f: Finding, minutes: List<TripMinuteEntity>, capacityKwh: Double, hybrid: Boolean): TripEntity {
        val seg = f.segment
        val (start, end) = window(f, minutes)
        val ownMinutes = minutes.filter { it.minuteTs >= start - MINUTE_MS + 1 && it.minuteTs <= end && !it.looksLikeCharging() }
        val (socStart, socEnd) = socAround(minutes, start, end, seg.gap)
        val kwh = minuteKwh(ownMinutes) ?: socKwh(socStart, socEnd, capacityKwh, hybrid)
        val km = TripMath.round1(f.uncoveredKm)
        val whole = f.coveredKm < NOTHING_COVERED_KM
        val hours = (end - start) / MS_PER_HOUR
        return TripEntity(
            startTs = start,
            endTs = end,
            distanceKm = km,
            kwhConsumed = kwh,
            kwhPer100km = TripMath.per100(kwh, km),
            socStart = socStart,
            socEnd = socEnd,
            avgSpeedKmh = if (hours > 0) km / hours else null,
            exteriorTemp = ownMinutes.firstOrNull { it.extTemp != null }?.extTemp?.roundToInt(),
            exteriorTempEnd = ownMinutes.lastOrNull { it.extTemp != null }?.extTemp?.roundToInt(),
            odometerStartKm = if (whole) TripMath.round1(seg.fromKm) else null,
            odometerEndKm = if (whole) TripMath.round1(seg.toKm) else null,
            source = TripSource.RECOVERED,
        )
    }

    /** Driving minutes of the segment outside the covering trips; the segment's bounds without any. */
    private fun window(f: Finding, minutes: List<TripMinuteEntity>): Pair<Long, Long> {
        val seg = f.segment
        if (seg.gap) return seg.startTs to seg.endTs
        val driving = minutes.filter { m ->
            (m.maxSpeed ?: 0) > 0 && m.minuteTs + MINUTE_MS > seg.startTs && m.minuteTs <= seg.endTs &&
                f.covering.none { t -> m.minuteTs >= t.startTs - MINUTE_MS && m.minuteTs <= (t.endTs ?: t.startTs) }
        }
        if (driving.isEmpty()) return seg.startTs to seg.endTs
        val start = max(seg.startTs, driving.first().minuteTs)
        val end = if (f.covering.isEmpty()) seg.endTs else min(seg.endTs, driving.last().minuteTs + MINUTE_MS)
        return start to max(start, end)
    }

    private fun minuteKwh(minutes: List<TripMinuteEntity>): Double? {
        if (minutes.isEmpty()) return null
        val sum = minutes.sumOf { it.motorsKwh + it.regenKwh + it.climateKwh + it.otherKwh }
        return sum.takeIf { it > 0.0 }?.let { (it * 1000.0).roundToInt() / 1000.0 }
    }

    private fun socKwh(start: Int?, end: Int?, capacityKwh: Double, hybrid: Boolean): Double? {
        if (hybrid || start == null || end == null) return null
        return if (start > end) (start - end) / 100.0 * capacityKwh else null
    }
}
