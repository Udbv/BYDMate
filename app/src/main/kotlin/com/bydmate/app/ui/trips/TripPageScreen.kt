package com.bydmate.app.ui.trips

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydmate.app.R
import com.bydmate.app.ui.components.formatTime
import com.bydmate.app.ui.theme.AccentBlue
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.AccentOrange
import com.bydmate.app.ui.theme.AccentTeal
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.NavyDark
import com.bydmate.app.ui.theme.NavyDeep
import com.bydmate.app.ui.theme.SocYellow
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary
import kotlin.math.roundToInt


/** The trip page: the detail card's map and stats, and beside them where the energy went minute by minute. */
@Composable
fun TripPageScreen(onBack: () -> Unit, viewModel: TripPageViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val trip = state.trip
    Box(Modifier.fillMaxSize().background(NavyDark)) {
        when {
            state.loading -> Unit
            trip == null -> Text(
                stringResource(R.string.trip_page_not_found), color = TextMuted, fontSize = 16.sp,
                modifier = Modifier.align(Alignment.Center),
            )
            else -> TripDetailContent(
                trip = trip,
                points = state.points,
                currencySymbol = state.currencySymbol,
                tileSource = state.tileSource,
                hybrid = state.hybrid,
                onDismiss = onBack,
                analysis = { TripAnalysis(state, trip.kwhConsumed, viewModel::select) },
            )
        }
    }
}

@Composable
private fun RowScope.TripAnalysis(state: TripPageUiState, tripKwh: Double?, onSelect: (Int) -> Unit) {
    Column(
        modifier = Modifier.weight(1.4f).fillMaxHeight().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val summary = state.summary
        CostCard(state, summary, tripKwh)
        state.forecast?.let { ForecastCard(it) }
        if (summary == null) {
            Panel { Text(stringResource(R.string.trip_page_no_minutes), color = TextSecondary, fontSize = 14.sp) }
            return@Column
        }
        EnergyCard(summary)
        Panel {
            Text(stringResource(R.string.trip_page_minutes_title), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Legend()
            MinuteChart(state.minutes, state.selected, onSelect, Modifier.fillMaxWidth().height(240.dp))
        }
        SelectedMinute(state)
        Extremes(summary)
    }
}

@Composable
internal fun Panel(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardSurface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
internal fun LabeledValue(label: String, value: String, color: Color = TextPrimary, note: String? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextSecondary, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            note?.let { Text(it, color = TextMuted, fontSize = 12.sp) }
            Text(value, color = color, fontSize = 15.sp, style = TextStyle(fontFeatureSettings = "tnum"))
        }
    }
}

@Composable
private fun CostCard(state: TripPageUiState, summary: TripEnergySummary?, tripKwh: Double?) {
    val price = state.price ?: return
    val cost = summary?.cost(tripKwh, price) ?: tripKwh?.let { it * price.pricePerKwh } ?: return
    val symbol = state.currencySymbol
    Panel {
        LabeledValue(
            stringResource(R.string.trip_page_cost_title),
            stringResource(R.string.trip_page_money, cost, symbol),
            AccentGreen,
        )
        Text(
            listOfNotNull(
                stringResource(R.string.trip_page_price, price.pricePerKwh, symbol),
                price.dcShare?.let { stringResource(R.string.trip_page_dc_share, (it * PERCENT).roundToInt()) }
                    ?: stringResource(R.string.trip_page_dc_unknown),
                summary?.regenSaving(price)?.let { stringResource(R.string.trip_page_regen_saving, it, symbol) },
            ).joinToString(" · "),
            color = TextSecondary,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun EnergyCard(summary: TripEnergySummary) {
    Panel {
        Text(stringResource(R.string.trip_page_energy_title), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        val total = summary.usedKwh.takeIf { it > 0 } ?: 1.0
        val rows = listOf(
            Triple(R.string.trip_page_rear, summary.rearKwh, AccentOrange),
            Triple(R.string.trip_page_front, summary.frontKwh, AccentBlue),
            Triple(R.string.trip_page_motors, summary.motorsUnsplitKwh, UnsplitColor),
            Triple(R.string.trip_page_climate, summary.climateKwh, ClimateColor),
            Triple(R.string.trip_page_other, summary.otherKwh, OtherColor),
        ).filter { it.second > 0.0 }
        for ((label, kwh, color) in rows) EnergyRow(stringResource(label), kwh, (kwh / total).toFloat(), color)
        if (summary.regenKwh < 0) {
            EnergyRow(stringResource(R.string.trip_page_regen), summary.regenKwh, (-summary.regenKwh / total).toFloat(), AccentTeal)
        }
    }
}

@Composable
private fun EnergyRow(label: String, kwh: Double, share: Float, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LabeledValue(label, stringResource(R.string.trip_page_kwh, kwh), color)
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(NavyDeep)) {
            Box(Modifier.fillMaxWidth(share.coerceIn(0f, 1f)).fillMaxHeight().background(color))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend() {
    val items = listOf(
        stringResource(R.string.trip_page_rear) to AccentOrange,
        stringResource(R.string.trip_page_front) to AccentBlue,
        stringResource(R.string.trip_page_climate) to ClimateColor,
        stringResource(R.string.trip_page_other) to OtherColor,
        stringResource(R.string.trip_page_regen) to AccentTeal,
        stringResource(R.string.trip_page_legend_peak) to TextPrimary,
        stringResource(R.string.trip_page_legend_speed) to SocYellow,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((label, color) in items) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("■", color = color, fontSize = 11.sp)
                Text(label, color = TextSecondary, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun SelectedMinute(state: TripPageUiState) {
    val i = state.selected
    val m = i?.let { state.minutes.getOrNull(it) }
    Panel {
        if (m == null) {
            Text(stringResource(R.string.trip_page_minute_hint), color = TextMuted, fontSize = 13.sp)
            return@Panel
        }
        Text(
            stringResource(R.string.trip_page_minute_title, formatTime(m.minuteTs)),
            color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
        )
        LabeledValue(stringResource(R.string.trip_page_avg), stringResource(R.string.trip_page_kw, kwText(m.netKw())))
        LabeledValue(stringResource(R.string.trip_page_peak), stringResource(R.string.trip_page_kw, kwText(m.peakKw)))
        val front = m.frontKwh
        val rear = m.rearKwh
        if (front != null && rear != null) {
            LabeledValue(
                stringResource(R.string.trip_page_split),
                "${kwText(front * MINUTES_PER_HOUR)} / ${kwText(rear * MINUTES_PER_HOUR)}",
            )
        }
        LabeledValue(stringResource(R.string.trip_page_climate), stringResource(R.string.trip_page_kw, kwText(m.climateKwh * MINUTES_PER_HOUR)), ClimateColor)
        LabeledValue(stringResource(R.string.trip_page_other), stringResource(R.string.trip_page_kw, kwText(m.otherKwh * MINUTES_PER_HOUR)), OtherColor)
        LabeledValue(stringResource(R.string.trip_page_regen), stringResource(R.string.trip_page_kw, kwText(m.regenKwh * MINUTES_PER_HOUR)), AccentTeal)
        m.avgSpeed?.let { LabeledValue(stringResource(R.string.trip_page_speed), stringResource(R.string.trip_page_kmh, it.roundToInt()), SocYellow) }
    }
}

@Composable
private fun Extremes(summary: TripEnergySummary) {
    Panel {
        Text(stringResource(R.string.trip_page_extremes_title), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        summary.peakKw?.let {
            LabeledValue(stringResource(R.string.trip_page_peak_traction), stringResource(R.string.trip_page_kw, kwText(it.value)), AccentOrange, formatTime(it.minuteTs))
        }
        summary.regenPeakKw?.let {
            LabeledValue(stringResource(R.string.trip_page_peak_regen), stringResource(R.string.trip_page_kw, kwText(it.value)), AccentTeal, formatTime(it.minuteTs))
        }
        summary.maxSpeed?.let {
            LabeledValue(stringResource(R.string.trip_detail_max_speed_label), stringResource(R.string.trip_page_kmh, it.value), SocYellow, formatTime(it.minuteTs))
        }
        summary.costliestMinute?.let {
            LabeledValue(stringResource(R.string.trip_page_costliest), stringResource(R.string.trip_page_kw, kwText(it.value)), TextPrimary, formatTime(it.minuteTs))
        }
        summary.climateShare?.let {
            LabeledValue(stringResource(R.string.trip_page_climate_share), stringResource(R.string.trip_page_percent_energy, (it * PERCENT).roundToInt()), ClimateColor)
        }
    }
}


private const val PERCENT = 100.0
