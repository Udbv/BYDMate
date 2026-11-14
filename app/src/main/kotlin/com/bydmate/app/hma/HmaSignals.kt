package com.bydmate.app.hma

/**
 * One car signal the automatic high beam (HMA) watch reads: its short [key] (journal and dump
 * name), the autoservice [device], the Tang L [fid] (DiLink 150 catalog 2026-10-24) and the
 * catalog [symbol] the push channel resolves the real number by on each firmware.
 *
 * Read only. Nothing here is ever written: the HMA mode switch (`Adas.ADAS_HMA_STATE_SET`) and
 * the headlight knob write (`Instrument.INSTRUMENT_HEADLIGHT_CONTROL_SET`) are deliberately
 * absent, the knob is read back through its FEEDBACK symbol instead.
 */
data class HmaSignal(val key: String, val device: Int, val fid: Int, val symbol: String)

/** A signal as this car's catalog numbers it, in the subscription in force. */
data class HmaSubscription(val signal: HmaSignal, val fid: Int)

object HmaSignals {

    const val HMA_STATE = "hma-state"
    const val HMA_STATE_HAL = "hma-state-hal"
    const val HMA_GRAY = "hma-gray"
    const val HMA_PRESENT = "hma-present"
    const val HMA_CLUSTER_FAULT = "hma-cluster-fault"
    const val HEADLIGHT_KNOB = "headlight-knob"
    const val HIGH_BEAM_INDICATOR = "high-beam-indicator"
    const val LIGHT_INTENSITY = "light-intensity"
    const val RAIN_FALL = "rain-fall"
    const val RAIN_ERROR = "rain-sensor-error"
    const val ILLUMINANCE_ERROR = "light-sensor-error"
    const val MIST_ERROR = "mist-sensor-error"
    const val GLASS_HUMIDITY = "glass-humidity"
    const val GLASS_TEMP = "glass-temp"
    const val WIPER_LEVEL = "wiper-level"

    /**
     * Report `D:\BYD\specs\auto-high-beam-report.md`, section 1. Values: HMA state 0 OFF,
     * 1 PASSIVE, 2 STANDBY, 3 ACTIVE, 4 FAULT, 5 CAMERA_BLOCKED (BYD SDK); gray 1 = unavailable.
     * The high beam lamp, the 1..5 light level, the wiper relay and the auto-wiper switch are
     * FidMap fields already (lightHigh, lightLevel, wiperRelay, autoWipers) and are not repeated.
     */
    val ALL: List<HmaSignal> = listOf(
        HmaSignal(HMA_STATE, 1038, 828375048, "Adas.ADAS_HMA_STATE"),
        HmaSignal(HMA_STATE_HAL, 1038, 725614600, "Adas.ADAS_HMA_STATE_HAL"),
        HmaSignal(HMA_GRAY, 1038, 535826476, "Adas.ADAS_HMA_HIGH_BEAM_ASSIST_GRAY"),
        HmaSignal(HMA_PRESENT, 1038, 1126170635, "Adas.ADAS_HAS2_HMA"),
        HmaSignal(HMA_CLUSTER_FAULT, 1007, 1033228342, "Instrument.INSTRUMENT_B_M_HMA_SYSTEM"),
        // 1 off, 2 auto, 3 side lights, 4 low beam: the knob as the car reports it back.
        HmaSignal(HEADLIGHT_KNOB, 1007, 1011875880, "Instrument.INSTRUMENT_HEADLIGHT_CONTROL_FEEDBACK"),
        HmaSignal(HIGH_BEAM_INDICATOR, 1004, 985661472, "Light.LIGHT_HIGH_BEAM_INDICATOR"),
        HmaSignal(LIGHT_INTENSITY, 1043, 1003487560, "Sensor.SENSOR_LIGHT_INTENSITY_VALUE"),
        HmaSignal(RAIN_FALL, 1043, 994051138, "Sensor.SENSOR_RAIN_FALL_VALUE"),
        HmaSignal(RAIN_ERROR, 1043, 494927968, "Sensor.SENSOR_RAIN_SENSING_ERROR_SET"),
        HmaSignal(ILLUMINANCE_ERROR, 1043, 494927970, "Sensor.SENSOR_ILLUMINANCE_SENSING_ERROR_SET"),
        HmaSignal(MIST_ERROR, 1043, 494927974, "Sensor.SENSOR_MIST_SENSING_ERROR_SET"),
        HmaSignal(GLASS_HUMIDITY, 1043, 994050056, "Sensor.SENSOR_FRONT_WINDSHIELD_HUMIDITY_VALUE"),
        HmaSignal(GLASS_TEMP, 1043, 994050064, "Sensor.SENSOR_FRONT_WINDSHIELD_SURFACE_TEMP"),
        HmaSignal(WIPER_LEVEL, 1046, 321912848, "Wiper.WIPER_FRONT_WIPER_LEVEL"),
    )

    val SYMBOLS: List<String> = ALL.map { it.symbol }

    /**
     * The signals at the numbers this car's catalog gives them. A symbol the catalog does not
     * carry is skipped (another platform may not have the function at all), and so is a number
     * [taken] already holds: the daemon keys its tables by fid, so a shared one would take the
     * other subscription's row over. At most [room] signals, the HMA states first.
     */
    fun confirmed(fidOf: (String) -> Int?, taken: Set<Int>, room: Int): List<HmaSubscription> {
        val used = taken.toMutableSet()
        return ALL.asSequence()
            .mapNotNull { signal -> fidOf(signal.symbol)?.let { HmaSubscription(signal, it) } }
            .filter { used.add(it.fid) }
            .take(room.coerceAtLeast(0))
            .toList()
    }
}
