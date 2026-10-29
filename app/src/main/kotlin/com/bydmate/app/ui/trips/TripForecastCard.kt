package com.bydmate.app.ui.trips

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.bydmate.app.R
import com.bydmate.app.domain.prediction.MIN_TRAIN_KM
import com.bydmate.app.domain.prediction.PredictionLevel
import com.bydmate.app.domain.prediction.SpeedClass
import com.bydmate.app.domain.prediction.TripConditions
import com.bydmate.app.domain.prediction.TripForecast
import com.bydmate.app.domain.prediction.TripForecaster
import com.bydmate.app.domain.prediction.TripWeather
import com.bydmate.app.domain.prediction.climbKwh
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.AccentOrange
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary
import kotlin.math.abs
import kotlin.math.roundToInt

/** «Forecast vs actual»: what the model expected from the conditions, what the drive used, and how the model does lately. */
@Composable
internal fun ForecastCard(forecast: TripForecast) {
    Panel {
        Text(stringResource(R.string.forecast_title), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        LabeledValue(stringResource(R.string.forecast_conditions), conditionsText(forecast.conditions))
        val prediction = forecast.prediction
        val expected = forecast.expectedKwh
        if (prediction == null || expected == null) {
            val reason = if (forecast.trainedOn < TripForecaster.MIN_TRAINED) {
                stringResource(R.string.forecast_not_enough, TripForecaster.MIN_TRAINED, MIN_TRAIN_KM.roundToInt())
            } else {
                stringResource(R.string.forecast_too_short, MIN_TRAIN_KM.roundToInt())
            }
            Text(reason, color = TextMuted, fontSize = 13.sp)
            return@Panel
        }
        val distance = forecast.distanceKm ?: return@Panel
        val expectedPer100 = expected / distance * PER_KM
        LabeledValue(stringResource(R.string.forecast_expected), stringResource(R.string.forecast_value, expected, expectedPer100))
        val climb = climbKwh(forecast.conditions.climbM)
        if (climb != 0.0) {
            Text(stringResource(R.string.forecast_climb_share, climb), color = TextMuted, fontSize = 12.sp)
        }
        val actual = forecast.actualKwh
        val actualPer100 = forecast.actualPer100
        if (actual != null && actualPer100 != null) {
            val diff = ((actualPer100 / expectedPer100 - 1) * PERCENT).roundToInt()
            LabeledValue(
                stringResource(R.string.forecast_actual),
                stringResource(R.string.forecast_value, actual, actualPer100),
                diffColor(diff),
                stringResource(R.string.forecast_diff, diff),
            )
        }
        Text(basisText(prediction.level, prediction.trips), color = TextSecondary, fontSize = 13.sp)
        forecast.accuracy?.let {
            Text(
                stringResource(R.string.forecast_accuracy, it.trips, it.maePer100, it.biasPer100, it.baselineMaePer100),
                color = TextMuted,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
internal fun conditionsText(c: TripConditions): String = listOfNotNull(
    c.tempC?.let { stringResource(R.string.forecast_temp, it.roundToInt()) },
    c.climbM?.takeIf { abs(it) >= MIN_SHOWN_CLIMB_M }?.let { stringResource(R.string.forecast_climb, it.roundToInt()) },
    stringResource(
        when (c.weather) {
            TripWeather.DRY -> R.string.weather_dry
            TripWeather.RAIN -> R.string.weather_rain
            TripWeather.SNOW -> R.string.weather_snow
            null -> R.string.weather_unknown
        }
    ),
    c.speedClass?.let {
        stringResource(
            when (it) {
                SpeedClass.CITY -> R.string.speed_city
                SpeedClass.MIXED -> R.string.speed_mixed
                SpeedClass.HIGHWAY -> R.string.speed_highway
            }
        )
    },
).joinToString(" · ")

@Composable
private fun basisText(level: PredictionLevel, trips: Int): String = stringResource(
    when (level) {
        PredictionLevel.FULL -> R.string.forecast_basis_full
        PredictionLevel.TEMP_SPEED -> R.string.forecast_basis_temp_speed
        PredictionLevel.TEMP -> R.string.forecast_basis_temp
        PredictionLevel.ALL -> R.string.forecast_basis_all
    },
    trips,
)

/** Within [CLOSE_PERCENT] the forecast hit; above it the drive cost more than expected. */
private fun diffColor(diff: Int): Color = when {
    abs(diff) <= CLOSE_PERCENT -> AccentGreen
    diff > 0 -> AccentOrange
    else -> TextPrimary
}

private const val PERCENT = 100.0
private const val PER_KM = 100.0
private const val MIN_SHOWN_CLIMB_M = 10.0
private const val CLOSE_PERCENT = 10
