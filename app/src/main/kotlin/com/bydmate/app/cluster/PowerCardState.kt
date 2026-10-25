package com.bydmate.app.cluster

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydmate.app.R
import com.bydmate.app.data.nativestack.MotorSplit
import com.bydmate.app.data.nativestack.motorSplitPercent
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.domain.calculator.PowerSample
import com.bydmate.app.domain.calculator.powerKw
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.widget.RowPowerGraph
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the cluster power card shows. [totalKw] is the battery power (+ traction, − regen).
 * [frontPercent] is the front motor's share of the drive current: on the Tang L the two motor
 * currents sum to ~1.4x the battery current (phase, not DC), so they give the split but not kW;
 * each axle's kW is the battery power cut in that ratio. Null split = the car does not report
 * the pair; [idle] = neither motor draws current.
 */
internal data class PowerCardState(
    val totalKw: Double? = null,
    val frontPercent: Int? = null,
    val idle: Boolean = false,
) {
    val frontKw: Double? get() = frontPercent?.let { pct -> totalKw?.let { it * pct / PERCENT } }
    val rearKw: Double? get() = frontPercent?.let { pct -> totalKw?.let { it * (PERCENT - pct) / PERCENT } }
    val regen: Boolean get() = (totalKw ?: 0.0) < REGEN_KW
}

internal fun powerCardState(data: DiParsData): PowerCardState =
    when (val split = motorSplitPercent(data.motorCurrentFront, data.motorCurrentRear)) {
        is MotorSplit.Share -> PowerCardState(powerKw(data), split.frontPercent)
        MotorSplit.Idle -> PowerCardState(powerKw(data), idle = true)
        null -> PowerCardState(powerKw(data))
    }

private const val PERCENT = 100.0

/** Below this the battery is taking power back: the card turns green. */
private const val REGEN_KW = -0.5

private val CardBackground = Color(0xE6101418)
private val TractionColor = Color(0xFFE6E6E6)
private val FrontColor = Color(0xFF4FA3FF)
private val RearColor = Color(0xFFFFB13B)

/** The card itself: total kW, the front/rear bar with each axle's kW, the 2-minute graph. */
@Composable
internal fun ClusterPowerCard(state: PowerCardState, samples: List<PowerSample>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBackground)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = state.totalKw?.let { kwText(it) } ?: "—",
                color = if (state.regen) AccentGreen else TractionColor,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = stringResource(
                    if (state.regen) R.string.cluster_power_regen else R.string.cluster_power_kw,
                ),
                color = TextMuted,
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        SplitBar(state)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            AxleLabel(R.string.cluster_power_front, state.frontKw, state.frontPercent, state.idle, FrontColor)
            AxleLabel(
                R.string.cluster_power_rear, state.rearKw,
                state.frontPercent?.let { 100 - it }, state.idle, RearColor,
            )
        }
        if (samples.size > 1) RowPowerGraph(samples)
    }
}

@Composable
private fun SplitBar(state: PowerCardState) {
    val front = state.frontPercent
    Row(
        modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))
            .background(TextMuted.copy(alpha = 0.3f)),
    ) {
        if (front != null && !state.idle) {
            if (front > 0) Box(Modifier.weight(front.toFloat()).height(10.dp).background(FrontColor))
            if (front < 100) Box(Modifier.weight((100 - front).toFloat()).height(10.dp).background(RearColor))
        }
    }
}

@Composable
private fun AxleLabel(label: Int, kw: Double?, percent: Int?, idle: Boolean, color: Color) {
    val value = when {
        percent == null -> "—"
        idle -> "0"
        else -> "${kw?.let { kwText(it) } ?: "—"} · $percent%"
    }
    Text(
        text = "${stringResource(label)} $value",
        color = color,
        fontSize = 15.sp,
        fontFamily = FontFamily.Monospace,
    )
}

private fun kwText(kw: Double): String {
    val rounded = kw.roundToInt()
    return if (abs(kw) < 10 && rounded != 0) "%.1f".format(kw) else rounded.toString()
}
