package com.bydmate.app.domain.calculator

/**
 * The consumption this car has really shown over long stretches, so the range estimate stays
 * within what a full battery has actually given. Three short winter trips or one gentle highway
 * leg otherwise swing the full-charge range below 200 or above 500 km, numbers the owner never saw.
 *
 * Trips are joined in time order into stretches of at least [STRETCH_KM] (about a third of a full
 * battery: one stretch is what a range number promises), and the bounds are the lowest and the
 * highest stretch average. A single trip is never a bound on its own: a 5 km drive with the heater
 * on is not a range.
 */
object ConsumptionBounds {

    data class Trip(val startTs: Long, val km: Double, val kwh: Double)

    /** Long enough that warm-up, a single hill or a stop at the lights average out. */
    const val STRETCH_KM = 100.0

    /** Fewer stretches say too little about the car: no bounds then. */
    const val MIN_STRETCHES = 4

    /** Recent enough to cover this season and the last one, not last year's tyres. */
    const val WINDOW_DAYS = 120L

    /**
     * Lowest and highest stretch average (kWh/100 km) of [trips], or null when there are fewer
     * than [MIN_STRETCHES] full stretches. A trailing stretch shorter than [STRETCH_KM] is dropped.
     */
    fun of(trips: List<Trip>): ClosedFloatingPointRange<Double>? {
        val averages = mutableListOf<Double>()
        var km = 0.0
        var kwh = 0.0
        for (t in trips.sortedBy { it.startTs }) {
            km += t.km
            kwh += t.kwh
            if (km >= STRETCH_KM) {
                averages += kwh / km * 100.0
                km = 0.0
                kwh = 0.0
            }
        }
        if (averages.size < MIN_STRETCHES) return null
        return averages.min()..averages.max()
    }
}
