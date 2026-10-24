package com.bydmate.app.domain.calculator

import com.bydmate.app.data.remote.DiParsData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.ceil
import kotlin.math.max

/** One instantaneous-power reading: kW, + traction draw, − regen. */
data class PowerSample(val atMs: Long, val kw: Double)

/**
 * Rolling window of instantaneous power for the widget's Tesla-style graph. Fed from the
 * TrackingService poll (≈1 Hz in DRIVE), kept here rather than in the widget so the graph
 * already has history when the overlay is (re)attached.
 */
object PowerHistory {

    const val WINDOW_MS = 120_000L

    /** A gap this long (power-off, a parked 30 s poll) starts a fresh graph instead of a ramp. */
    const val GAP_RESET_MS = 10_000L

    private val _samples = MutableStateFlow<List<PowerSample>>(emptyList())
    val samples: StateFlow<List<PowerSample>> = _samples

    fun onTick(data: DiParsData, nowMs: Long) {
        val kw = powerKw(data) ?: return
        _samples.value = appendPowerSample(_samples.value, PowerSample(nowMs, kw))
    }
}

/**
 * Battery power (HV voltage × current) is finer than the whole-kW ENG_POW reading and is what a
 * Tesla power meter shows; ENG_POW is the fallback on cars that do not report the HV pair.
 */
internal fun powerKw(data: DiParsData): Double? {
    val kw = data.batteryPowerW?.let { it / 1000.0 } ?: data.power ?: return null
    return kw.takeIf { it in POWER_SANE_MIN_KW..POWER_SANE_MAX_KW }
}

private const val POWER_SANE_MIN_KW = -300.0
private const val POWER_SANE_MAX_KW = 600.0

internal fun appendPowerSample(
    history: List<PowerSample>,
    sample: PowerSample,
    windowMs: Long = PowerHistory.WINDOW_MS,
    gapResetMs: Long = PowerHistory.GAP_RESET_MS,
): List<PowerSample> {
    val last = history.lastOrNull()
    val base = if (last == null || sample.atMs - last.atMs > gapResetMs || sample.atMs < last.atMs) {
        emptyList()
    } else {
        history.dropWhile { it.atMs < sample.atMs - windowMs }
    }
    return base + sample
}

/**
 * Vertical range of the graph: draw on top, regen below, each with a floor so idle noise does
 * not fill the box, rounded up to a 10 kW step so the scale does not twitch with every sample.
 */
internal data class PowerScale(val topKw: Double, val bottomKw: Double) {
    val spanKw: Double get() = topKw + bottomKw

    /** 0 at the top edge, 1 at the bottom edge. */
    fun yFraction(kw: Double): Float =
        ((topKw - kw.coerceIn(-bottomKw, topKw)) / spanKw).toFloat()
}

internal const val POWER_SCALE_MIN_TOP_KW = 20.0
internal const val POWER_SCALE_MIN_BOTTOM_KW = 10.0
private const val POWER_SCALE_STEP_KW = 10.0

internal fun powerScale(samples: List<PowerSample>): PowerScale {
    val maxDraw = samples.maxOfOrNull { it.kw }?.coerceAtLeast(0.0) ?: 0.0
    val maxRegen = samples.maxOfOrNull { -it.kw }?.coerceAtLeast(0.0) ?: 0.0
    fun roundUp(v: Double) = ceil(v / POWER_SCALE_STEP_KW) * POWER_SCALE_STEP_KW
    return PowerScale(
        topKw = roundUp(max(maxDraw, POWER_SCALE_MIN_TOP_KW)),
        bottomKw = roundUp(max(maxRegen, POWER_SCALE_MIN_BOTTOM_KW)),
    )
}

/** Horizontal position: the newest sample sits at the right edge, the window spans the width. */
internal fun powerXFraction(sampleAtMs: Long, newestAtMs: Long, windowMs: Long = PowerHistory.WINDOW_MS): Float =
    (1f - (newestAtMs - sampleAtMs).toFloat() / windowMs).coerceIn(0f, 1f)
