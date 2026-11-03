package com.bydmate.app.data.homeassistant

import com.bydmate.app.data.remote.DiParsData
import kotlin.math.roundToInt

/** One live reading as Home Assistant gets it: the poll snapshot plus the app's own range estimate. */
data class HaSnapshot(val data: DiParsData, val rangeKm: Double?)

/**
 * A Home Assistant mobile_app sensor. [uniqueId] is the stable key HA files the entity under;
 * renaming it would orphan the user's entity, so the ids below are frozen. [name] stays English:
 * HA derives the entity id from it (`sensor.bydmate_soc`), and the docs point at those ids.
 */
data class HaSensorSpec(
    val uniqueId: String,
    val name: String,
    val binary: Boolean = false,
    val deviceClass: String? = null,
    val unit: String? = null,
    val stateClass: String? = null,
    val icon: String? = null,
    val state: (HaSnapshot) -> Any?,
)

/** The sensors this app publishes. A null state means "no reading this tick". */
object HaSensorCatalog {

    /** Bump when a sensor is added or its metadata changes: it re-runs register_sensor once. */
    const val SCHEMA_VERSION = 1

    private const val MEASUREMENT = "measurement"
    private const val CELSIUS = "°C"
    private const val KM = "km"
    private const val KPA = "kPa"

    // DiPars encodings (see DiParsData): lock 1=unlocked/2=locked, trunk 2=closed/1=open/3=moving,
    // chargingStatus 0=none/1=plugged/2=charging, powerState 0=off/1=on/2=drive.
    private const val LOCK_UNLOCKED = 1
    private const val TRUNK_CLOSED = 2
    private const val CHARGING_PLUGGED = 1
    private const val CHARGING_ACTIVE = 2
    private const val POWER_ON = 1
    private const val POWER_DRIVE = 2
    private const val ODOMETER_SCALE = 10.0

    val ALL: List<HaSensorSpec> = listOf(
        HaSensorSpec("bydmate_soc", "SoC", deviceClass = "battery", unit = "%", stateClass = MEASUREMENT) {
            it.data.soc
        },
        HaSensorSpec("bydmate_range", "Range", deviceClass = "distance", unit = KM, icon = "mdi:map-marker-distance") {
            it.rangeKm?.takeIf(Double::isFinite)?.roundToInt()
        },
        HaSensorSpec("bydmate_odometer", "Odometer", deviceClass = "distance", unit = KM, stateClass = "total_increasing") {
            it.data.mileage?.let { km -> (km * ODOMETER_SCALE).roundToInt() / ODOMETER_SCALE }
        },
        HaSensorSpec("bydmate_charging", "Charging", binary = true, deviceClass = "battery_charging") {
            it.data.chargingStatus?.let { s -> s == CHARGING_ACTIVE }
        },
        HaSensorSpec("bydmate_plugged", "Plugged in", binary = true, deviceClass = "plug") {
            it.data.chargingStatus?.let { s -> s >= CHARGING_PLUGGED }
        },
        temperature("bydmate_outside_temp", "Outside temperature") { it.data.exteriorTemp },
        temperature("bydmate_cabin_temp", "Cabin temperature") { it.data.insideTemp },
        temperature("bydmate_battery_temp", "Battery temperature") { it.data.avgBatTemp },
        HaSensorSpec("bydmate_12v", "12V battery", deviceClass = "voltage", unit = "V", stateClass = MEASUREMENT) {
            it.data.voltage12v
        },
        HaSensorSpec("bydmate_power_state", "Power state", icon = "mdi:car-key") { powerStateName(it.data.powerState) },
        HaSensorSpec("bydmate_locked", "Lock", binary = true, deviceClass = "lock") {
            // HA's lock class reads "on" as unlocked.
            it.data.lockFL?.let { l -> l == LOCK_UNLOCKED }
        },
        HaSensorSpec("bydmate_doors", "Doors", binary = true, deviceClass = "door") { anyDoorOpen(it.data) },
        HaSensorSpec("bydmate_trunk", "Trunk", binary = true, deviceClass = "opening") {
            it.data.trunk?.let { t -> t != TRUNK_CLOSED }
        },
        HaSensorSpec("bydmate_hood", "Hood", binary = true, deviceClass = "opening") { it.data.hood?.let { h -> h != 0 } },
        HaSensorSpec("bydmate_windows", "Windows", binary = true, deviceClass = "window") { anyWindowOpen(it.data) },
        tyre("bydmate_tyre_fl", "Tyre pressure FL") { it.data.tirePressFL },
        tyre("bydmate_tyre_fr", "Tyre pressure FR") { it.data.tirePressFR },
        tyre("bydmate_tyre_rl", "Tyre pressure RL") { it.data.tirePressRL },
        tyre("bydmate_tyre_rr", "Tyre pressure RR") { it.data.tirePressRR },
    )

    fun powerStateName(powerState: Int?): String? = when (powerState) {
        null -> null
        0 -> "off"
        POWER_ON -> "on"
        POWER_DRIVE -> "drive"
        else -> "unknown"
    }

    /** Null only when no door reports at all: one readable door is enough to say "open". */
    fun anyDoorOpen(d: DiParsData): Boolean? {
        val doors = listOfNotNull(d.doorFL, d.doorFR, d.doorRL, d.doorRR)
        return if (doors.isEmpty()) null else doors.any { it != 0 }
    }

    fun anyWindowOpen(d: DiParsData): Boolean? {
        val windows = listOfNotNull(d.windowFL, d.windowFR, d.windowRL, d.windowRR, d.sunroof)
        return if (windows.isEmpty()) null else windows.any { it > 0 }
    }

    private fun temperature(id: String, name: String, read: (HaSnapshot) -> Int?) = HaSensorSpec(
        id, name, deviceClass = "temperature", unit = CELSIUS, stateClass = MEASUREMENT, state = read,
    )

    private fun tyre(id: String, name: String, read: (HaSnapshot) -> Int?) = HaSensorSpec(
        id, name, deviceClass = "pressure", unit = KPA, stateClass = MEASUREMENT, state = read,
    )
}
