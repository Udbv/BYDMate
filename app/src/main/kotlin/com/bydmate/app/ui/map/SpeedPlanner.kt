package com.bydmate.app.ui.map

import kotlin.math.ceil
import kotlin.math.max

/** One cruise speed over a route: what it uses, how often it charges, how long it all takes. */
data class SpeedPlan(
    val cruiseKph: Int,
    val per100: Double,
    val energyKwh: Double,
    val stops: Int,
    val chargeKwh: Double,
    val driveMin: Double,
    val chargeMin: Double,
    val cost: Double?,
) {
    val totalMin: Double get() = driveMin + chargeMin
}

/** What the car starts the trip with: the energy in the battery, its size, the weather, the DC price. */
data class PlanStart(val startKwh: Double, val capacityKwh: Double, val tempC: Double?, val dcPrice: Double?)

/**
 * Faster or further? For a route it compares cruise speeds: consumption from the physics of a
 * Tang L (air drag grows with the square of the speed, rolling stays, climate draws by the hour
 * and more in the cold), the charging stops the extra energy needs on the road's 160 kW DC
 * chargers, and the time with them. Starting from the energy the battery holds now, arriving
 * with [RESERVE] of the capacity, charging each stop from 10 to at most 80 %.
 *
 * The constants are a Tang L with a driver at road speeds; the trip forecast tunes the real
 * consumption, this table is for the choice between speeds, where the shape matters most.
 */
object SpeedPlanner {

    val CRUISE_SPEEDS = listOf(90, 110, 130)

    const val CHARGER_KW = 160.0
    /** A 160 kW charger averages about this from 10 to 80 % (the curve tapers, the cable shares). */
    const val EFFECTIVE_CHARGE_KW = 135.0
    /** Pulling in, plugging, paying, pulling out. */
    const val STOP_OVERHEAD_MIN = 8.0
    const val RESERVE = 0.10
    /** One stop charges at most from 10 to 80 %: past it the curve is too slow to pay. */
    const val STOP_WINDOW = 0.70

    /** The average over a road is lower than the cruise speed: towns, trucks, works. */
    const val AVERAGE_OF_CRUISE = 0.9

    /** kWh/100 km at [cruiseKph] in [tempC] outside. */
    fun per100(cruiseKph: Int, tempC: Double?): Double {
        val t = tempC ?: MILD_C
        val v = cruiseKph / KPH_PER_MPS
        val airDensity = AIR_DENSITY_0C * KELVIN_0C / (KELVIN_0C + t)
        val aeroJ = 0.5 * airDensity * CDA_M2 * v * v * METERS_PER_100KM
        val rollJ = ROLLING * MASS_KG * GRAVITY * METERS_PER_100KM
        val wheelsKwh = (aeroJ + rollJ) / JOULES_PER_KWH / DRIVE_EFFICIENCY
        val auxKw = AUX_KW + HEAT_KW_PER_DEG * max(0.0, COMFORT_C - t)
        val hoursPer100 = KM_PER_100 / (cruiseKph * AVERAGE_OF_CRUISE)
        return (wheelsKwh + auxKw * hoursPer100)
    }

    fun plan(distanceKm: Double, cruiseKph: Int, start: PlanStart): SpeedPlan {
        val per100 = per100(cruiseKph, start.tempC)
        val energy = per100 * distanceKm / KM_PER_100
        val need = energy + RESERVE * start.capacityKwh - start.startKwh
        val stops = if (need <= 0) 0 else ceil(need / (STOP_WINDOW * start.capacityKwh)).toInt()
        val charge = max(0.0, need)
        val chargeMin = charge / EFFECTIVE_CHARGE_KW * MIN_PER_HOUR + stops * STOP_OVERHEAD_MIN
        val driveMin = distanceKm / (cruiseKph * AVERAGE_OF_CRUISE) * MIN_PER_HOUR
        return SpeedPlan(cruiseKph, per100, energy, stops, charge, driveMin, chargeMin, start.dcPrice?.let { it * charge })
    }

    private const val KPH_PER_MPS = 3.6
    private const val AIR_DENSITY_0C = 1.293
    private const val KELVIN_0C = 273.15
    private const val CDA_M2 = 0.78
    private const val ROLLING = 0.009
    private const val MASS_KG = 2_750.0
    private const val GRAVITY = 9.81
    private const val DRIVE_EFFICIENCY = 0.88
    private const val AUX_KW = 0.8
    private const val HEAT_KW_PER_DEG = 0.12
    private const val COMFORT_C = 18.0
    private const val MILD_C = 10.0
    private const val METERS_PER_100KM = 100_000.0
    private const val JOULES_PER_KWH = 3_600_000.0
    private const val KM_PER_100 = 100.0
    private const val MIN_PER_HOUR = 60.0
}
