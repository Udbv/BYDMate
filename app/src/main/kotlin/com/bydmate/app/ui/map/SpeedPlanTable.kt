package com.bydmate.app.ui.map

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydmate.app.R
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary
import kotlin.math.roundToInt

/**
 * «Faster or further»: per cruise speed the stops on 160 kW chargers, the time with them, the
 * energy the trip uses and what the road charging costs; the fastest is green.
 */
@Composable
internal fun SpeedPlanTable(plans: List<SpeedPlan>, currency: String) {
    if (plans.isEmpty()) return
    val fastest = plans.minBy { it.totalMin }
    Column {
        Text(stringResource(R.string.map_speed_title), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        for (p in plans) {
            val color = if (p == fastest) AccentGreen else TextSecondary
            Row(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.map_speed_kph, p.cruiseKph), color = color, fontSize = 13.sp, modifier = Modifier.width(76.dp))
                Text(hoursMinutes(p.totalMin), color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(56.dp))
                Text(
                    if (p.stops == 0) stringResource(R.string.map_speed_no_stops)
                    else pluralStringResource(R.plurals.map_speed_stops, p.stops, p.stops, p.chargeMin.roundToInt()),
                    color = color, fontSize = 13.sp, modifier = Modifier.width(150.dp),
                )
                Text(
                    listOfNotNull(
                        stringResource(R.string.map_speed_kwh, p.energyKwh.roundToInt()),
                        p.cost?.takeIf { p.chargeKwh > 0 }?.let { stringResource(R.string.map_speed_cost, it.roundToInt(), currency) },
                    ).joinToString(" · "),
                    color = color, fontSize = 13.sp,
                )
            }
        }
        Text(
            stringResource(R.string.map_speed_note, SpeedPlanner.CHARGER_KW.roundToInt()),
            color = TextMuted, fontSize = 11.sp,
        )
    }
}

private fun hoursMinutes(min: Double): String {
    val m = min.roundToInt()
    return "%d:%02d".format(m / MIN_PER_HOUR, m % MIN_PER_HOUR)
}

private const val MIN_PER_HOUR = 60
