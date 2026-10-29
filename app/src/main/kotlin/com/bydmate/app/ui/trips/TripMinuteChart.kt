package com.bydmate.app.ui.trips

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydmate.app.data.local.entity.TripMinuteEntity
import com.bydmate.app.ui.components.formatTime
import com.bydmate.app.ui.theme.AccentBlue
import com.bydmate.app.ui.theme.AccentOrange
import com.bydmate.app.ui.theme.AccentTeal
import com.bydmate.app.ui.theme.CardBorder
import com.bydmate.app.ui.theme.ChartGrid
import com.bydmate.app.ui.theme.SocYellow
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

internal val ClimateColor = Color(0xFFA78BFA)
internal val OtherColor = Color(0xFFDDD6FE)
internal val UnsplitColor = Color(0xFF94A3B8)

/**
 * Stacked minutes: rear, front (or the unsplit traction), climate and other above zero, regen
 * below; a dot at each minute's peak; the speed as a line on its own 0..max scale. A tap picks
 * the minute under the finger.
 */
@Composable
internal fun MinuteChart(minutes: List<TripMinuteEntity>, selected: Int?, onSelect: (Int) -> Unit, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = TextMuted, fontSize = 10.sp)
    Canvas(
        modifier.pointerInput(minutes.size) {
            detectTapGestures { offset ->
                val plotLeft = AXIS_WIDTH_DP.dp.toPx()
                val w = (size.width - plotLeft) / minutes.size.coerceAtLeast(1)
                val i = floor((offset.x - plotLeft) / w).toInt()
                if (i in minutes.indices) onSelect(i)
            }
        },
    ) {
        if (minutes.isEmpty()) return@Canvas
        val scale = ChartScale.of(minutes)
        val plotLeft = AXIS_WIDTH_DP.dp.toPx()
        val plotW = size.width - plotLeft
        val h = size.height - LABEL_ROOM_DP.dp.toPx()
        val k = h / (scale.hi - scale.lo).toFloat()
        val zeroY = (scale.hi * k).toFloat()
        fun y(kw: Double) = zeroY - (kw * k).toFloat()
        // Grid and the kW labels.
        var v = scale.lo
        while (v <= scale.hi + 1e-6) {
            val gy = y(v)
            drawLine(
                if (v == 0.0) CardBorder else ChartGrid, Offset(plotLeft, gy), Offset(size.width, gy),
                strokeWidth = 1f, pathEffect = if (v == 0.0) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
            )
            drawText(measurer, v.roundToInt().toString(), Offset(0f, gy - 7.dp.toPx()), labelStyle)
            v += scale.step
        }
        val colW = plotW / minutes.size
        val barW = colW * BAR_FILL
        selected?.let {
            drawRect(TextPrimary.copy(alpha = 0.08f), Offset(plotLeft + it * colW, 0f), Size(colW, h))
        }
        minutes.forEachIndexed { i, m ->
            val x = plotLeft + i * colW + (colW - barW) / 2
            var base = 0.0
            fun stack(kwh: Double?, color: Color) {
                val kw = (kwh ?: 0.0) * MINUTES_PER_HOUR
                if (kw <= 0.0) return
                drawRect(color, Offset(x, y(base + kw)), Size(barW, (kw * k).toFloat()))
                base += kw
            }
            stack(m.rearKwh, AccentOrange)
            stack(m.frontKwh, AccentBlue)
            if (m.frontKwh == null || m.rearKwh == null) stack(m.motorsKwh, UnsplitColor)
            stack(m.climateKwh, ClimateColor)
            stack(m.otherKwh, OtherColor)
            val regenKw = m.regenKwh * MINUTES_PER_HOUR
            if (regenKw < 0) drawRect(AccentTeal, Offset(x, zeroY), Size(barW, (-regenKw * k).toFloat()))
            if (m.peakKw > 0) drawCircle(TextPrimary, radius = 3.dp.toPx(), center = Offset(x + barW / 2, y(m.peakKw.coerceAtMost(scale.hi))))
        }
        drawSpeed(minutes, plotLeft, colW, h, scale.maxSpeed)
        // Minute labels: start, middle, end.
        listOf(0, minutes.size / 2, minutes.size - 1).distinct().forEach { i ->
            val text = formatTime(minutes[i].minuteTs)
            val x = (plotLeft + i * colW).coerceAtMost(size.width - TIME_LABEL_DP.dp.toPx())
            drawText(measurer, text, Offset(x, h + 4.dp.toPx()), labelStyle)
        }
    }
}

private fun DrawScope.drawSpeed(minutes: List<TripMinuteEntity>, left: Float, colW: Float, h: Float, maxSpeed: Double) {
    var prev: Offset? = null
    minutes.forEachIndexed { i, m ->
        val s = m.avgSpeed ?: run { prev = null; return@forEachIndexed }
        val p = Offset(left + (i + 0.5f) * colW, h - (s / maxSpeed * h).toFloat())
        prev?.let { drawLine(SocYellow, it, p, strokeWidth = 2.dp.toPx()) }
        prev = p
    }
}

/** The chart's kW range rounded to its step, and the top of the speed scale. */
internal data class ChartScale(val lo: Double, val hi: Double, val step: Double, val maxSpeed: Double) {
    companion object {
        fun of(minutes: List<TripMinuteEntity>): ChartScale {
            val top = minutes.maxOf { maxOf(it.peakKw, it.usedKw()) }.coerceAtLeast(MIN_TOP_KW)
            val bottom = minutes.minOf { it.regenKwh * MINUTES_PER_HOUR }.coerceAtMost(0.0)
            val step = when {
                top > BIG_TOP_KW -> BIG_STEP
                top > MID_TOP_KW -> MID_STEP
                else -> SMALL_STEP
            }
            return ChartScale(
                lo = floor(bottom / step) * step,
                hi = ceil(top / step) * step,
                step = step,
                maxSpeed = (minutes.maxOf { it.maxSpeed ?: 0 }.toDouble()).coerceAtLeast(MIN_SPEED_SCALE),
            )
        }

        private const val MIN_TOP_KW = 10.0
        private const val BIG_TOP_KW = 120.0
        private const val MID_TOP_KW = 60.0
        private const val BIG_STEP = 50.0
        private const val MID_STEP = 25.0
        private const val SMALL_STEP = 10.0
        private const val MIN_SPEED_SCALE = 60.0
    }
}

private const val AXIS_WIDTH_DP = 34
private const val LABEL_ROOM_DP = 18
private const val TIME_LABEL_DP = 36
private const val BAR_FILL = 0.76f
