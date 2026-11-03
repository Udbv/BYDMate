package com.bydmate.app.ui.widget

import androidx.annotation.StringRes
import com.bydmate.app.R
import kotlin.math.ceil
import kotlin.math.floor

/** How the arrival chip is colored: plenty left, little left, or the route is out of reach. */
enum class ArrivalLevel { OK, LOW, SHORT }

/**
 * What is left of the range estimate at the end of the guided route (#6C).
 * [km] is never negative: the reserve for [ArrivalLevel.OK] and [ArrivalLevel.LOW],
 * the shortfall for [ArrivalLevel.SHORT].
 */
data class ArrivalReserve(val km: Int, val level: ArrivalLevel)

/** Under this share of the current range the reserve turns yellow: a detour or a headwind eats it. */
internal const val ARRIVAL_LOW_RESERVE_FRACTION = 0.15

private const val METERS_PER_KM = 1000.0

/**
 * The chip's content, or null when it has nothing honest to say: no route guided, the navigator
 * shows no distance to the destination, or no usable range estimate.
 *
 * The reserve is rounded down and the shortfall up, so the "~" never promises more than the
 * estimate gives.
 */
internal fun arrivalReserve(rangeKm: Double?, routeActive: Boolean, routeMeters: Int): ArrivalReserve? {
    if (!routeActive || routeMeters <= 0) return null
    if (rangeKm == null || !rangeKm.isFinite() || rangeKm < 0.0) return null
    val leftover = rangeKm - routeMeters / METERS_PER_KM
    return when {
        leftover < 0.0 -> ArrivalReserve(ceil(-leftover).toInt(), ArrivalLevel.SHORT)
        leftover < rangeKm * ARRIVAL_LOW_RESERVE_FRACTION -> ArrivalReserve(floor(leftover).toInt(), ArrivalLevel.LOW)
        else -> ArrivalReserve(floor(leftover).toInt(), ArrivalLevel.OK)
    }
}

/** The string template for [ArrivalReserve.km]: a reserve reads differently from a shortfall. */
@StringRes
internal fun arrivalReserveTextRes(reserve: ArrivalReserve): Int =
    if (reserve.level == ArrivalLevel.SHORT) R.string.widget_arrival_short else R.string.widget_arrival_reserve
