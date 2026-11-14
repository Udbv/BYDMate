package com.bydmate.app.data.trips

/**
 * Stable wire values for [TripEntity.source]. Magic strings replaced here.
 */
object TripSource {
    const val LIVE = "live"
    const val ENERGYDATA = "energydata"
    const val NATIVE_POLLING = "native_polling"

    /**
     * TripRecorder's own trip of a passive device (the energydata file was trusted), saved because
     * the next import brought no BYD record for it (2026-11-14 missing trips).
     */
    const val NATIVE_FALLBACK = "native_fallback"

    /**
     * Rebuilt by TripAnomalyDetector from the odometer marks, the per-minute rows and SoC for a
     * drive no trip covered: the recovered flag of a trip.
     */
    const val RECOVERED = "recovered"
    val all = listOf(LIVE, ENERGYDATA, NATIVE_POLLING, NATIVE_FALLBACK, RECOVERED)

    /** Trips the app wrote itself: a BYD record arriving later for the same drive takes them over. */
    val OWN = listOf(NATIVE_POLLING, NATIVE_FALLBACK, RECOVERED)
}
