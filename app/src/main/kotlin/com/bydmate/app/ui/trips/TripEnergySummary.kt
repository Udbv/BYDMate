package com.bydmate.app.ui.trips

import com.bydmate.app.data.local.entity.TripMinuteEntity
import com.bydmate.app.domain.cost.TripEnergyPrice

/** A value and the minute it happened in. */
data class AtMinute<T>(val value: T, val minuteTs: Long)

/**
 * The trip page's numbers out of the trip's minutes, kept pure for the tests. Energies in kWh;
 * [motorsUnsplitKwh] is traction the car reported no axle split for. The cost is the trip's
 * energy at the price of the energy in the pack ([TripEnergyPrice]), the regen saving that price
 * times what the motors returned.
 */
data class TripEnergySummary(
    val frontKwh: Double,
    val rearKwh: Double,
    val motorsUnsplitKwh: Double,
    val climateKwh: Double,
    val otherKwh: Double,
    val regenKwh: Double,
    val peakKw: AtMinute<Double>?,
    val regenPeakKw: AtMinute<Double>?,
    val maxSpeed: AtMinute<Int>?,
    val costliestMinute: AtMinute<Double>?,
) {
    val usedKwh: Double get() = frontKwh + rearKwh + motorsUnsplitKwh + climateKwh + otherKwh
    val netKwh: Double get() = usedKwh + regenKwh
    val climateShare: Double? get() = if (usedKwh > 0) climateKwh / usedKwh else null

    fun cost(tripKwh: Double?, price: TripEnergyPrice?): Double? =
        price?.let { p -> (tripKwh ?: netKwh.takeIf { it > 0 })?.let { it * p.pricePerKwh } }

    fun regenSaving(price: TripEnergyPrice?): Double? =
        price?.let { -regenKwh * it.pricePerKwh }?.takeIf { it > 0 }

    companion object {
        fun of(minutes: List<TripMinuteEntity>): TripEnergySummary? {
            if (minutes.isEmpty()) return null
            val split = minutes.filter { it.frontKwh != null && it.rearKwh != null }
            return TripEnergySummary(
                frontKwh = split.sumOf { it.frontKwh ?: 0.0 },
                rearKwh = split.sumOf { it.rearKwh ?: 0.0 },
                motorsUnsplitKwh = minutes.filter { it.frontKwh == null || it.rearKwh == null }.sumOf { it.motorsKwh },
                climateKwh = minutes.sumOf { it.climateKwh },
                otherKwh = minutes.sumOf { it.otherKwh },
                regenKwh = minutes.sumOf { it.regenKwh },
                peakKw = minutes.maxBy { it.peakKw }.let { AtMinute(it.peakKw, it.minuteTs) }
                    .takeIf { it.value > 0 },
                regenPeakKw = minutes.minBy { it.minKw }.let { AtMinute(it.minKw, it.minuteTs) }
                    .takeIf { it.value < 0 },
                maxSpeed = minutes.filter { it.maxSpeed != null }.maxByOrNull { it.maxSpeed ?: 0 }
                    ?.let { AtMinute(it.maxSpeed ?: 0, it.minuteTs) },
                costliestMinute = minutes.maxBy { it.usedKw() }.let { AtMinute(it.usedKw(), it.minuteTs) },
            )
        }
    }
}

/** Average power a minute drew, not counting what the motors returned. */
internal fun TripMinuteEntity.usedKw(): Double =
    (motorsKwh + climateKwh + otherKwh) * MINUTES_PER_HOUR

/** Average battery power of a minute, regen included. */
internal fun TripMinuteEntity.netKw(): Double = usedKw() + regenKwh * MINUTES_PER_HOUR

internal const val MINUTES_PER_HOUR = 60.0

/** kW for the page: one decimal under 10, a real minus sign. */
internal fun kwText(kw: Double): String {
    val r = kotlin.math.round(kw).toInt()
    return (if (kotlin.math.abs(kw) < KW_DECIMALS_BELOW && r != 0) "%.1f".format(kw) else r.toString()).replace('-', '−')
}

private const val KW_DECIMALS_BELOW = 10.0
