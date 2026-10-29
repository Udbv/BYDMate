package com.bydmate.app.cluster

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydmate.app.R
import com.bydmate.app.ui.theme.TextMuted
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

private val CardBackground = Color(0xE6101418)
private val PlotBackground = Color(0xFF0C1014)
private val AxisColor = Color(0xFF33404C)
private val GridColor = Color(0xFF222C35)
private val BodyColor = Color(0xFF161D24)
private val LabelColor = Color(0xFFAEB9C4)

/**
 * The card: the total kW coloured by the power scale with the trip's peaks, the car seen from
 * above with each axle coloured by its own kW, the front axle's 2-minute graph above its line and
 * the rear one's below (one kW axis for both), and what is not the motors (climate, 12 V) with its
 * own graph. Built to shrink: the graphs take what height is left.
 */
@Composable
internal fun ClusterPowerCard(state: PowerCardState, history: CardHistory) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBackground)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Header(state, history)
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AxleIcon(state, Modifier.fillMaxHeight().aspectRatio(ICON_ASPECT, matchHeightConstraintsFirst = true))
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                val scale = AxleScale.of(history.samples)
                AxleGraph(history.samples, scale, front = true, Modifier.weight(1f))
                AxleLine(R.string.cluster_power_front, state.frontKw, state.frontPercent, state.idle)
                AxleLine(R.string.cluster_power_rear, state.rearKw, state.frontPercent?.let { 100 - it }, state.idle)
                AxleGraph(history.samples, scale, front = false, Modifier.weight(1f))
            }
        }
        AuxBlock(state, history)
    }
}

@Composable
private fun Header(state: PowerCardState, history: CardHistory) {
    val totalColor = powerColor(state.totalKw)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = state.totalKw?.let { kwText(it) } ?: "—",
                color = totalColor, fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
            )
            Text(
                text = stringResource(
                    when {
                        state.regen -> R.string.cluster_power_regen
                        state.idle -> R.string.cluster_power_idle
                        else -> R.string.cluster_power_kw
                    },
                ),
                color = if (state.regen) totalColor else TextMuted, fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 5.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            history.peakKw?.let { Text("▲ ${kwText(it)}", color = powerColor(INTENSE_SAMPLE_KW), fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
            history.regenPeakKw?.let { Text("▼ ${kwText(it)}", color = powerColor(it), fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
            if (history.peakKw != null || history.regenPeakKw != null) {
                Text(stringResource(R.string.cluster_power_trip_peak), color = TextMuted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun AxleLine(label: Int, kw: Double?, percent: Int?, idle: Boolean) {
    val value = when {
        percent == null && idle -> "0"
        percent == null -> "—"
        else -> "${kw?.let { kwText(it) } ?: "—"} · $percent%"
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(label), color = LabelColor, fontSize = 13.sp)
        Text(value, color = if (percent == null) IdleColor else powerColor(kw), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

/** The car from above, front up: body, cabin, and each axle (line, wheels, motor) in its kW colour. */
@Composable
private fun AxleIcon(state: PowerCardState, modifier: Modifier) {
    val split = state.frontPercent != null
    val front = if (split) powerColor(state.frontKw) else IdleColor
    val rear = if (split) powerColor(state.rearKw) else IdleColor
    Canvas(modifier) {
        val u = size.width / ICON_W
        drawRoundRect(BodyColor, Offset(15 * u, 4 * u), Size(34 * u, 96 * u), CornerRadius(15 * u))
        drawRoundRect(AxisColor, Offset(15 * u, 4 * u), Size(34 * u, 96 * u), CornerRadius(15 * u), style = Stroke(2 * u))
        drawRoundRect(AxisColor, Offset(21 * u, 44 * u), Size(22 * u, 18 * u), CornerRadius(5 * u), style = Stroke(1.5f * u))
        axle(26f * u, u, front)
        axle(80f * u, u, rear)
    }
}

private fun DrawScope.axle(y: Float, u: Float, color: Color) {
    drawLine(color, Offset(9 * u, y), Offset(55 * u, y), strokeWidth = 4 * u, cap = StrokeCap.Round)
    drawRoundRect(color, Offset(3 * u, y - 11 * u), Size(11 * u, 22 * u), CornerRadius(3.5f * u))
    drawRoundRect(color, Offset(50 * u, y - 11 * u), Size(11 * u, 22 * u), CornerRadius(3.5f * u))
    drawCircle(PlotBackground, 6 * u, Offset(32 * u, y))
    drawCircle(color, 6 * u, Offset(32 * u, y), style = Stroke(3 * u))
}

/** Both axles' graphs share one kW axis, rounded up to a step, so their heights compare. */
internal data class AxleScale(val hi: Double, val lo: Double) {
    val span: Double get() = (hi - lo).takeIf { it > 0 } ?: 1.0

    companion object {
        fun of(samples: List<CardSample>): AxleScale {
            val values = samples.flatMap { listOfNotNull(it.frontKw, it.rearKw) } + 0.0
            return AxleScale(nice(values.max()), -nice(-values.min()))
        }

        /** 5, 10, 20, 25, 50, 100, 150, 200, 250, then by 50. */
        fun nice(x: Double): Double {
            if (x <= 0) return 0.0
            return NICE_STEPS.firstOrNull { it >= x } ?: (ceil(x / NICE_TAIL) * NICE_TAIL)
        }

        private val NICE_STEPS = listOf(5.0, 10.0, 20.0, 25.0, 50.0, 100.0, 150.0, 200.0, 250.0)
        private const val NICE_TAIL = 50.0
    }
}

/** One axle's last two minutes as bars from zero, the kW axis on the left, the peaks outlined. */
@Composable
private fun AxleGraph(samples: List<CardSample>, scale: AxleScale, front: Boolean, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val style = TextStyle(color = TextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    Canvas(modifier.fillMaxWidth()) {
        val axis = AXIS_DP.dp.toPx()
        val w = size.width - axis
        val h = size.height
        val zero = (scale.hi / scale.span * h).toFloat()
        drawRect(PlotBackground, Offset(axis, 0f), Size(w, h))
        drawLine(GridColor, Offset(axis, 1f), Offset(size.width, 1f), pathEffect = DASH)
        drawLine(GridColor, Offset(axis, h - 1f), Offset(size.width, h - 1f), pathEffect = DASH)
        drawLine(GridColor, Offset(axis + w / 2, 0f), Offset(axis + w / 2, h), pathEffect = DASH)
        drawLine(AxisColor, Offset(axis, zero), Offset(size.width, zero))
        if (scale.hi > 0) drawText(measurer, scale.hi.roundToInt().toString(), Offset(0f, 0f), style)
        if (scale.lo < 0) drawText(measurer, kwText(scale.lo), Offset(0f, h - LABEL_H_DP.dp.toPx()), style)
        val values = samples.map { if (front) it.frontKw else it.rearKw }
        if (values.all { it == null }) return@Canvas
        val barW = w / BARS
        val peak = values.filterNotNull().maxOrNull()
        val low = values.filterNotNull().minOrNull()
        val start = samples.last().atMs - CardHistory.WINDOW_MS
        samples.forEachIndexed { i, s ->
            val v = values[i] ?: return@forEachIndexed
            val x = axis + ((s.atMs - start).toFloat() / CardHistory.WINDOW_MS * w).coerceIn(0f, w - barW)
            val bh = (abs(v) / scale.span * h).toFloat().coerceAtLeast(1f)
            val top = if (v >= 0) zero - bh else zero
            drawRect(powerColor(v), Offset(x, top), Size(barW * BAR_FILL, bh))
            val extreme = if (v >= 0) v == peak && v > PEAK_MIN_KW else v == low && v < -PEAK_MIN_KW
            if (extreme) {
                drawRect(Color.White, Offset(x, top), Size(barW * BAR_FILL, bh), style = Stroke(1.dp.toPx()))
            }
        }
    }
}

/** Climate and the 12 V side: the figures, then a stacked graph on its own kW axis, then the time axis. */
@Composable
private fun ColumnScope.AuxBlock(state: PowerCardState, history: CardHistory) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("${stringResource(R.string.cluster_power_not_motors)} ${kwText(state.auxKw)}", color = LabelColor, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("■ ${stringResource(R.string.cluster_power_climate)} ${kwText(state.climateKw)}", color = ClimateColor, fontSize = 12.sp)
            Text("■ ${stringResource(R.string.cluster_power_other)} ${kwText(state.otherKw)}", color = OtherColor, fontSize = 12.sp)
        }
    }
    val measurer = rememberTextMeasurer()
    val style = TextStyle(color = TextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    Canvas(Modifier.fillMaxWidth().weight(AUX_WEIGHT)) {
        val axis = AXIS_DP.dp.toPx()
        val w = size.width - axis
        val h = size.height
        drawRect(PlotBackground, Offset(axis, 0f), Size(w, h))
        val samples = history.samples
        val top = AxleScale.nice(samples.maxOfOrNull { it.climateKw + it.otherKw } ?: 0.0).takeIf { it > 0 } ?: AUX_MIN_TOP
        drawText(measurer, top.roundToInt().toString(), Offset(0f, 0f), style)
        drawLine(AxisColor, Offset(axis, h - 1f), Offset(size.width, h - 1f))
        if (samples.isEmpty()) return@Canvas
        val barW = w / BARS
        val start = samples.last().atMs - CardHistory.WINDOW_MS
        for (s in samples) {
            val x = axis + ((s.atMs - start).toFloat() / CardHistory.WINDOW_MS * w).coerceIn(0f, w - barW)
            val ch = (s.climateKw / top * h).toFloat()
            val oh = (s.otherKw / top * h).toFloat()
            drawRect(ClimateColor, Offset(x, h - ch), Size(barW * BAR_FILL, ch))
            drawRect(OtherColor, Offset(x, h - ch - oh), Size(barW * BAR_FILL, oh))
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = AXIS_DP.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        val style2 = TextStyle(color = TextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Text(stringResource(R.string.cluster_power_minutes_ago, 2), style = style2)
        Text(stringResource(R.string.cluster_power_minutes_ago, 1), style = style2)
        Text(stringResource(R.string.cluster_power_now), style = style2)
    }
}

internal fun kwText(kw: Double): String {
    val rounded = kw.roundToInt()
    val text = if (abs(kw) < DECIMALS_BELOW_KW && rounded != 0) "%.1f".format(kw) else rounded.toString()
    return text.replace('-', '−')
}

private val DASH = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
private const val ICON_W = 64f
private const val ICON_ASPECT = 64f / 104f
private const val AXIS_DP = 28
private const val LABEL_H_DP = 11
private const val BARS = 60f
private const val BAR_FILL = 0.8f
private const val AUX_WEIGHT = 0.35f
private const val AUX_MIN_TOP = 5.0
private const val PEAK_MIN_KW = 0.5
private const val DECIMALS_BELOW_KW = 10.0

/** The ▲ peak is always shown in the intense band's colour, whatever its value. */
private const val INTENSE_SAMPLE_KW = 100.0
