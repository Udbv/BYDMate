package com.bydmate.app.hma

import com.bydmate.app.data.autoservice.SentinelDecoder
import java.util.Locale

/** The automatic high beam state as the BYD SDK names it (`BYDAutoADASDevice`). */
enum class HmaMode(val code: Int) {
    OFF(0), PASSIVE(1), STANDBY(2), ACTIVE(3), FAULT(4), CAMERA_BLOCKED(5);

    /** The function is switched on (whatever it is doing right now). */
    val enabled: Boolean get() = this != OFF

    companion object {
        fun of(raw: Int?): HmaMode? = raw?.let { code -> entries.firstOrNull { it.code == code } }

        /** Journal name of a raw state: the enum name, the bare number when unknown, `-` when absent. */
        fun label(raw: Int?): String = when {
            raw == null -> "-"
            else -> of(raw)?.name ?: raw.toString()
        }
    }
}

/**
 * Everything the HMA watch knows at one moment: the pushed HMA and sensor signals ([HmaSignals])
 * and the few poll fields it lays them against. Every value is already decoded: null means "not
 * reported yet" or a sentinel.
 */
data class HmaReading(
    val hmaState: Int? = null,
    val hmaStateHal: Int? = null,
    val gray: Int? = null,
    val present: Int? = null,
    val clusterFault: Int? = null,
    val knob: Int? = null,
    val indicator: Int? = null,
    val lightIntensity: Double? = null,
    val rainFall: Double? = null,
    val rainError: Int? = null,
    val illuminanceError: Int? = null,
    val mistError: Int? = null,
    val glassHumidity: Double? = null,
    val glassTemp: Double? = null,
    val wiperLevel: Int? = null,
    // FidMap fields (poll and push)
    val highBeam: Int? = null,
    val lightLevel: Int? = null,
    val wiperRelay: Int? = null,
    val autoWipers: Int? = null,
    val speedKph: Int? = null,
) {
    /**
     * The state DiCarServer itself uses: the HAL copy first, the plain one when the HAL copy is
     * absent (`IntelligentHighBeamSwitchModel`).
     */
    val effectiveState: Int? get() = hmaStateHal ?: hmaState

    val mode: HmaMode? get() = HmaMode.of(effectiveState)

    /** Applies one pushed HMA signal; an unknown key changes nothing. */
    fun with(key: String, intValue: Int, doubleValue: Double): HmaReading {
        val i = SentinelDecoder.decodeInt(intValue)
        val d = number(intValue, doubleValue)
        return when (key) {
            HmaSignals.HMA_STATE -> copy(hmaState = i)
            HmaSignals.HMA_STATE_HAL -> copy(hmaStateHal = i)
            HmaSignals.HMA_GRAY -> copy(gray = i)
            HmaSignals.HMA_PRESENT -> copy(present = i)
            HmaSignals.HMA_CLUSTER_FAULT -> copy(clusterFault = i)
            HmaSignals.HEADLIGHT_KNOB -> copy(knob = i)
            HmaSignals.HIGH_BEAM_INDICATOR -> copy(indicator = i)
            HmaSignals.LIGHT_INTENSITY -> copy(lightIntensity = d)
            HmaSignals.RAIN_FALL -> copy(rainFall = d)
            HmaSignals.RAIN_ERROR -> copy(rainError = i)
            HmaSignals.ILLUMINANCE_ERROR -> copy(illuminanceError = i)
            HmaSignals.MIST_ERROR -> copy(mistError = i)
            HmaSignals.GLASS_HUMIDITY -> copy(glassHumidity = d)
            HmaSignals.GLASS_TEMP -> copy(glassTemp = d)
            HmaSignals.WIPER_LEVEL -> copy(wiperLevel = i)
            HIGH_BEAM_FIELD -> copy(highBeam = i)
            else -> this
        }
    }

    /** Sensor error flags that are raised (non-zero), by journal name; empty when none is. */
    val sensorErrors: List<String>
        get() = listOfNotNull(
            "rain".takeIf { (rainError ?: 0) != 0 },
            "light".takeIf { (illuminanceError ?: 0) != 0 },
            "mist".takeIf { (mistError ?: 0) != 0 },
        )

    companion object {
        /** The FidMap field of the high beam lamp: pushed too, so a flash is not lost between polls. */
        const val HIGH_BEAM_FIELD = "lightHigh"

        /**
         * A pushed value as a number: the double when the event carries one, else the int
         * (sentinels dropped). Analogue sensors (lux, humidity) arrive as either, by firmware.
         */
        fun number(intValue: Int, doubleValue: Double): Double? = when {
            doubleValue != 0.0 && !doubleValue.isNaN() -> doubleValue
            else -> SentinelDecoder.decodeInt(intValue)?.toDouble()
        }

        /** `12`, `12.5`, or `-` — short, locale-free. */
        fun fmt(v: Double?): String = when {
            v == null -> "-"
            v == Math.floor(v) && kotlin.math.abs(v) < 1e9 -> v.toLong().toString()
            else -> String.format(Locale.ROOT, "%.1f", v)
        }
    }
}
