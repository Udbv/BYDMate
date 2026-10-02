package com.bydmate.app.ui.widget

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydmate.app.R
import com.bydmate.app.domain.calculator.PowerSample
import com.bydmate.app.domain.calculator.powerScale
import com.bydmate.app.domain.calculator.powerXFraction
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import kotlin.math.roundToInt

/** Extra height the power row adds to the 108 dp widget (row + divider + spacing). */
internal const val POWER_GRAPH_EXTRA_HEIGHT_DP = 52

private val DrawColor = TextPrimary
private val RegenColor = AccentGreen

/**
 * Tesla-style power meter: the last two minutes of instantaneous power, draw above the zero line
 * in white, regen below it in green, the current value on the left.
 */
@Composable
internal fun RowPowerGraph(samples: List<PowerSample>) {
    val current = samples.lastOrNull()?.kw
    Row(
        modifier = Modifier.fillMaxWidth().height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(modifier = Modifier.width(58.dp), contentAlignment = Alignment.CenterStart) {
            Text(
                text = current?.let { stringResource(R.string.tech_value_kw, it.roundToInt()) } ?: "—",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = when {
                    current == null -> TextMuted
                    current < 0 -> RegenColor
                    else -> DrawColor
                },
                maxLines = 1,
            )
        }
        PowerGraphCanvas(samples, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun PowerGraphCanvas(samples: List<PowerSample>, modifier: Modifier) {
    Canvas(modifier = modifier) {
        val scale = powerScale(samples)
        val zeroY = scale.yFraction(0.0) * size.height
        drawLine(
            color = TextMuted.copy(alpha = 0.5f),
            start = Offset(0f, zeroY),
            end = Offset(size.width, zeroY),
            strokeWidth = 1.dp.toPx(),
        )
        if (samples.size < 2) return@Canvas

        val newest = samples.last().atMs
        val points = samples.map {
            Offset(powerXFraction(it.atMs, newest) * size.width, scale.yFraction(it.kw) * size.height)
        }
        val line = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        val area = Path().apply {
            addPath(line)
            lineTo(points.last().x, zeroY)
            lineTo(points.first().x, zeroY)
            close()
        }
        // One path, two colors: clip at the zero line so draw and regen each get their own.
        fun half(color: Color, clipOp: ClipOp) =
            clipRect(top = 0f, bottom = zeroY, clipOp = clipOp) {
                drawPath(area, color = color.copy(alpha = 0.25f))
                drawPath(line, color = color, style = Stroke(width = 1.5.dp.toPx()))
            }
        half(DrawColor, ClipOp.Intersect)
        half(RegenColor, ClipOp.Difference)
    }
}
