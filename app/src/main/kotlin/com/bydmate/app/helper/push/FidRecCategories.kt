package com.bydmate.app.helper.push

/**
 * Recorder category «Світло/HMA»: the automatic high beam, the lamp it drives, the light/rain
 * sensors behind the windscreen and the wipers — what a night drive recording is for. A run
 * already records every device these live on (1004, 1007, 1038, 1043, 1046), so the category does
 * not add registrations; it names the signals in the file header and lets the discrete ones skip
 * the per-fid throttle, so a quick flip of the HMA state or a flash of the high beam is never
 * folded into the next value. Matched on the catalog symbol. Read only, like the whole recorder.
 *
 * Kept in step with `com.bydmate.app.hma.HmaSignals` and the FidMap fields the HMA watch reads
 * (FidRecCategoriesTest).
 */
internal const val FID_REC_HMA_CATEGORY = "Світло/HMA"

/** Every change of these is written at once (two-state or small-enum signals that change rarely). */
internal val FID_REC_HMA_DISCRETE: Set<String> = setOf(
    "Adas.ADAS_HMA_STATE",
    "Adas.ADAS_HMA_STATE_HAL",
    "Adas.ADAS_HMA_HIGH_BEAM_ASSIST_GRAY",
    "Adas.ADAS_HAS2_HMA",
    "Instrument.INSTRUMENT_B_M_HMA_SYSTEM",
    "Instrument.INSTRUMENT_HEADLIGHT_CONTROL_FEEDBACK",
    "Light.LIGHT_HIGH_BEAM_LIGHT",
    "Light.LIGHT_HIGH_BEAM_INDICATOR",
    "Light.LIGHT_LOW_BEAM_LIGHT",
    "Sensor.SENSOR_RAIN_SENSING_ERROR_SET",
    "Sensor.SENSOR_ILLUMINANCE_SENSING_ERROR_SET",
    "Sensor.SENSOR_MIST_SENSING_ERROR_SET",
    "Wiper.WIPER_RELAY_STATE",
    "Wiper.WIPER_FRONT_WIPER_LEVEL",
    "Setting.SET_AUTO_RAIN_WIPER_SWITCH",
)

/** Analogue members of the category: recorded under the usual throttle (they change constantly). */
internal val FID_REC_HMA_ANALOGUE: Set<String> = setOf(
    "Sensor.SENSOR_LIGHT",
    "Sensor.SENSOR_LIGHT_INTENSITY_VALUE",
    "Sensor.SENSOR_RAIN_FALL_VALUE",
    "Sensor.SENSOR_FRONT_WINDSHIELD_HUMIDITY_VALUE",
    "Sensor.SENSOR_FRONT_WINDSHIELD_SURFACE_TEMP",
)

internal val FID_REC_HMA_SYMBOLS: Set<String> = FID_REC_HMA_DISCRETE + FID_REC_HMA_ANALOGUE

/** The header line that names the category in the file. */
internal fun fidRecHmaHeader(): String =
    "hma=${FID_REC_HMA_SYMBOLS.size} signals ($FID_REC_HMA_CATEGORY): every change of " +
        "${FID_REC_HMA_DISCRETE.size} discrete ones, sensors at the usual rate; grep ADAS_HMA_ / HIGH_BEAM / SENSING_ERROR"
