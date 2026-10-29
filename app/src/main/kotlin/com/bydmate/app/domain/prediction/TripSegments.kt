package com.bydmate.app.domain.prediction

import com.bydmate.app.data.local.entity.TripMinuteEntity

/**
 * One minute of a drive's track: GPS height (m), outside temperature (°C) and whether the wipers
 * ran, from the minute's points. They stand in where the minute row lacks them (minutes recorded
 * before the row kept the weather) and give the height, which only the track knows.
 */
data class MinuteTrack(
    val minuteTs: Long,
    val altitude: Double? = null,
    val extTemp: Double? = null,
    val wiping: Int? = null,
)

/**
 * What a drive went through, for the trip page: the outside temperature from its coldest to its
 * warmest minute, and the height it gained and lost on the way (not only start against finish).
 */
data class TripProfile(
    val tempMinC: Double?,
    val tempMaxC: Double?,
    val ascentM: Double,
    val descentM: Double,
)

/**
 * Cuts a drive into minute segments, each with its own outside temperature, weather, speed and
 * height change, so a cold morning start or a mountain pass counts where it happened. The
 * minutes' own energies and distances only set the shares: they are scaled to the drive's
 * measured kWh and km, which stay the truth. A drive whose minutes are missing or disagree with
 * its totals stays one segment with [overall] conditions.
 */
object TripSegments {

    fun of(
        distanceKm: Double,
        kwh: Double,
        overall: TripConditions,
        minutes: List<TripMinuteEntity>,
        track: List<MinuteTrack>,
    ): List<Segment> {
        val whole = listOf(Segment(distanceKm, kwh, overall, climbKwh(overall.climbM)))
        if (minutes.size < MIN_MINUTES) return whole
        val rawKm = minutes.map { (it.avgSpeed ?: 0.0) / MINUTES_PER_HOUR }
        val rawKwh = minutes.map { it.motorsKwh + it.regenKwh + it.climateKwh + it.otherKwh }
        val kmScale = distanceKm / rawKm.sum()
        val kwhScale = kwh / rawKwh.sum()
        if (!kmScale.isSane() || !kwhScale.isSane()) return whole
        val byMinute = track.associateBy { it.minuteTs }
        val points = minutes.map { byMinute[it.minuteTs] }
        val climbs = minuteClimbs(minutes, track)
        val wipers = minutes.mapIndexed { i, m -> m.wiperSamples ?: points[i]?.wiping }
        return minutes.mapIndexed { i, m ->
            val temp = m.extTemp ?: points[i]?.extTemp ?: overall.tempC
            Segment(
                km = rawKm[i] * kmScale,
                kwh = rawKwh[i] * kwhScale,
                conditions = TripConditions(temp, weatherAround(wipers, i, temp), m.avgSpeed, climbs[i]),
                climbKwh = climbKwh(climbs[i], MINUTE_DEADBAND_M),
            )
        }
    }

    fun profile(segments: List<Segment>): TripProfile {
        val temps = segments.mapNotNull { it.conditions.tempC }
        val climbs = segments.mapNotNull { it.conditions.climbM }
        return TripProfile(
            tempMinC = temps.minOrNull(),
            tempMaxC = temps.maxOrNull(),
            ascentM = climbs.filter { it > 0 }.sum(),
            descentM = climbs.filter { it < 0 }.sum(),
        )
    }

    /**
     * Height gained in each minute from a smoothed track: each minute's height is averaged with
     * its neighbours (GPS height jumps by metres fix to fix), then minute minus the one before.
     * Null where the track has no height.
     */
    internal fun minuteClimbs(minutes: List<TripMinuteEntity>, track: List<MinuteTrack>): List<Double?> {
        val byMinute = track.associate { it.minuteTs to it.altitude }
        val raw = minutes.map { byMinute[it.minuteTs] }
        val smooth = raw.indices.map { i ->
            val window = (i - 1..i + 1).mapNotNull { raw.getOrNull(it) }
            if (raw[i] == null || window.isEmpty()) null else window.average()
        }
        return smooth.indices.map { i ->
            val now = smooth[i]
            val before = smooth.getOrNull(i - 1)
            if (now != null && before != null) now - before else null
        }
    }

    /**
     * Wet when the wipers ran within two minutes either side: intermittent wipers skip minutes
     * in light rain. Snow when wet at or below [SNOW_MAX_C]; null when no minute near knew the wipers.
     */
    private fun weatherAround(wipers: List<Int?>, i: Int, tempC: Double?): TripWeather? {
        val near = (i - WET_WINDOW..i + WET_WINDOW).mapNotNull { wipers.getOrNull(it) }
        if (near.isEmpty()) return null
        return when {
            near.none { it > 0 } -> TripWeather.DRY
            tempC != null && tempC <= SNOW_MAX_C -> TripWeather.SNOW
            else -> TripWeather.RAIN
        }
    }

    /** Minutes and the car's counters agree within a factor of two, or the minutes are not trusted. */
    private fun Double.isSane() = isFinite() && this in MIN_SCALE..MAX_SCALE

    private const val MIN_MINUTES = 2
    private const val MINUTES_PER_HOUR = 60.0
    private const val MINUTE_DEADBAND_M = 1.0
    private const val WET_WINDOW = 2
    private const val SNOW_MAX_C = 1.0
    private const val MIN_SCALE = 0.5
    private const val MAX_SCALE = 2.0
}
