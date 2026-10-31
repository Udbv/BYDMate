package com.bydmate.app.cluster

import androidx.compose.ui.graphics.Color
import com.bydmate.app.data.nativestack.MotorSplit
import com.bydmate.app.data.nativestack.motorSplitPercent
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.domain.tracker.powerParts

/**
 * What the cluster power card shows. [totalKw] is the battery power (+ traction, − regen); the
 * compressor ([climateKw]) and the 12 V side ([otherKw]) are measured, the motors get the rest.
 * [frontPercent] is the front motor's share of the drive current: on the Tang L the two motor
 * currents sum to ~1.4x the battery current (phase, not DC), so they give the split but not kW;
 * each axle's kW is the motors' power cut in that ratio. Null split = the car does not report
 * the pair; [idle] = neither motor draws current.
 */
internal data class PowerCardState(
    val totalKw: Double? = null,
    val frontPercent: Int? = null,
    val idle: Boolean = false,
    val climateKw: Double = 0.0,
    val otherKw: Double = 0.0,
) {
    val motorsKw: Double? get() = totalKw?.let { it - climateKw - otherKw }
    val frontKw: Double? get() = frontPercent?.let { pct -> motorsKw?.let { it * pct / PERCENT } }
    val rearKw: Double? get() = frontPercent?.let { pct -> motorsKw?.let { it * (PERCENT - pct) / PERCENT } }
    val auxKw: Double get() = climateKw + otherKw
    val regen: Boolean get() = (totalKw ?: 0.0) < REGEN_KW
}

internal fun powerCardState(data: DiParsData): PowerCardState {
    val parts = powerParts(data) ?: return PowerCardState()
    return when (val split = motorSplitPercent(data.motorCurrentFront, data.motorCurrentRear)) {
        is MotorSplit.Share -> PowerCardState(parts.totalKw, split.frontPercent, false, parts.climateKw, parts.otherKw)
        MotorSplit.Idle -> PowerCardState(parts.totalKw, null, true, parts.climateKw, parts.otherKw)
        null -> PowerCardState(parts.totalKw, null, false, parts.climateKw, parts.otherKw)
    }
}

/** One tick of the card's 2-minute graphs. */
internal data class CardSample(
    val atMs: Long,
    val frontKw: Double?,
    val rearKw: Double?,
    val climateKw: Double,
    val otherKw: Double,
)

/**
 * The card's last [WINDOW_MS] of axle and climate power, and the trip's traction and regen peaks
 * (kept until the car goes off). A silence over [GAP_RESET_MS] starts the graphs afresh.
 */
internal data class CardHistory(
    val samples: List<CardSample> = emptyList(),
    val peakKw: Double? = null,
    val regenPeakKw: Double? = null,
) {
    fun add(state: PowerCardState, nowMs: Long): CardHistory {
        val total = state.totalKw ?: return this
        val sample = CardSample(nowMs, state.frontKw, state.rearKw, state.climateKw, state.otherKw)
        val last = samples.lastOrNull()
        val kept = if (last != null && nowMs - last.atMs > GAP_RESET_MS) emptyList()
        else samples.filter { nowMs - it.atMs <= WINDOW_MS }
        return CardHistory(
            samples = kept + sample,
            peakKw = if (total > 0) maxOf(peakKw ?: total, total) else peakKw,
            regenPeakKw = if (total < 0) minOf(regenPeakKw ?: total, total) else regenPeakKw,
        )
    }

    companion object {
        const val WINDOW_MS = 120_000L
        const val GAP_RESET_MS = 10_000L
    }
}

/**
 * One colour scale for the total, each axle and the bars: regen teal/green, calm grey, then
 * yellow, orange and rose as the power climbs. The bands also differ in lightness.
 */
internal fun powerColor(kw: Double?): Color = when {
    kw == null -> IdleColor
    kw <= STRONG_REGEN_KW -> Color(0xFF2DD4BF)
    kw < REGEN_KW -> Color(0xFF86EFAC)
    kw < CALM_KW -> Color(0xFFCBD5E1)
    kw < MODERATE_KW -> Color(0xFFFCD34D)
    kw < INTENSE_KW -> Color(0xFFFB923C)
    else -> Color(0xFFFB7185)
}

internal val IdleColor = Color(0xFF3B4652)
internal val ClimateColor = Color(0xFFA78BFA)
internal val OtherColor = Color(0xFFDDD6FE)

private const val PERCENT = 100.0

/** Below this the battery is taking power back. */
private const val REGEN_KW = -0.5
private const val STRONG_REGEN_KW = -30.0
private const val CALM_KW = 20.0
private const val MODERATE_KW = 60.0
private const val INTENSE_KW = 120.0
