package com.bydmate.app.ui.dashboard

import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.local.entity.TripMinuteEntity
import com.bydmate.app.domain.tracker.looksLikeCharging

/**
 * The trip still under way, for Главная (#42): finished trips get their row and analysis when
 * they end, and until then the drive in progress was nowhere on the screen. It is drawn as one
 * more trip row on top of the list (start, time so far, km, kWh, kWh/100), with what a row has
 * no column for, the energy won back and the temperature outside, under it.
 */
data class LiveTripUi(val trip: TripEntity, val regenKwh: Double?, val extTemp: Int?)

/** The session as Главная already has it: start, odometer km, consumption and outside temperature. */
data class LiveSession(val start: Long?, val km: Double?, val per100: Double?, val extTemp: Int?)

object LiveTrip {
    /** Below this the session is the car standing (climate, charging), not a drive. */
    const val MIN_KM = 0.1
    private const val PER_100 = 100.0
    private const val MINUTE_MS = 60_000L

    /**
     * The session's km and consumption are its own odometer distance and consumption, the numbers the
     * widget shows; the minutes add the regeneration, charging minutes left out.
     */
    fun of(session: LiveSession, nowMs: Long, minutes: List<TripMinuteEntity>): LiveTripUi? {
        val sessionStart = session.start
        val km = session.km
        val per100 = session.per100
        val extTemp = session.extTemp
        if (sessionStart == null || km == null || km < MIN_KM) return null
        val driving = minutes.filter { it.minuteTs >= sessionStart - MINUTE_MS && !it.looksLikeCharging() }
        val netFromMinutes = driving.takeIf { it.isNotEmpty() }
            ?.sumOf { it.motorsKwh + it.climateKwh + it.otherKwh + it.regenKwh }
        val kwh = per100?.let { it * km / PER_100 } ?: netFromMinutes
        val trip = TripEntity(
            startTs = sessionStart,
            endTs = nowMs,
            distanceKm = km,
            kwhConsumed = kwh,
            kwhPer100km = per100 ?: kwh?.let { it / km * PER_100 },
            exteriorTemp = extTemp,
        )
        val regen = driving.sumOf { it.regenKwh }.takeIf { it < 0.0 }
        return LiveTripUi(trip, regen, extTemp)
    }
}
