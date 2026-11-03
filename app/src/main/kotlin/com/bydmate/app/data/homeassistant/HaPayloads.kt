package com.bydmate.app.data.homeassistant

import org.json.JSONArray
import org.json.JSONObject

/** Who registers: the fields of the mobile_app registration that describe this head unit. */
data class HaDeviceInfo(
    val deviceId: String,
    val appVersion: String,
    val model: String,
    val osVersion: String,
)

/**
 * JSON bodies of the Home Assistant Mobile App API
 * (developers.home-assistant.io/docs/api/native-app-integration). Pure builders so the wire
 * format is pinned by tests.
 */
object HaPayloads {

    const val APP_ID = "bydmate"
    const val APP_NAME = "BYDMate"

    /** Also the HA device name, so entity ids read `sensor.bydmate_*`, matching the docs. */
    const val DEVICE_NAME = "BYDMate"

    fun registration(device: HaDeviceInfo): JSONObject = JSONObject()
        .put("device_id", device.deviceId)
        .put("app_id", APP_ID)
        .put("app_name", APP_NAME)
        .put("app_version", device.appVersion)
        .put("device_name", DEVICE_NAME)
        .put("manufacturer", "BYD")
        .put("model", device.model)
        .put("os_name", "Android")
        .put("os_version", device.osVersion)
        // Encryption needs libsodium; the transport is https already.
        .put("supports_encryption", false)
        .put("app_data", JSONObject())

    /** register_sensor carries the first state too; a missing reading registers as unknown. */
    fun registerSensor(spec: HaSensorSpec, snapshot: HaSnapshot?): JSONObject {
        val data = JSONObject()
            .put("unique_id", spec.uniqueId)
            .put("name", spec.name)
            .put("type", sensorType(spec))
            .put("state", snapshot?.let(spec.state) ?: JSONObject.NULL)
        spec.deviceClass?.let { data.put("device_class", it) }
        spec.unit?.let { data.put("unit_of_measurement", it) }
        spec.stateClass?.let { data.put("state_class", it) }
        spec.icon?.let { data.put("icon", it) }
        return JSONObject().put("type", "register_sensor").put("data", data)
    }

    /**
     * Sensors without a reading this tick are left out instead of sent as null: a field the
     * poll missed once would otherwise flash "unknown" in HA between two good values.
     * Null when nothing at all has a reading.
     */
    fun updateStates(snapshot: HaSnapshot, sensors: List<HaSensorSpec> = HaSensorCatalog.ALL): JSONObject? {
        val states = JSONArray()
        for (spec in sensors) {
            val value = spec.state(snapshot) ?: continue
            val entry = JSONObject()
                .put("unique_id", spec.uniqueId)
                .put("type", sensorType(spec))
                .put("state", value)
            spec.icon?.let { entry.put("icon", it) }
            states.put(entry)
        }
        if (states.length() == 0) return null
        return JSONObject().put("type", "update_sensor_states").put("data", states)
    }

    /**
     * Unique ids HA answered with `not_registered` (e.g. the user deleted an entity, or HA lost
     * its store). Seeing any means the sensors must be registered again.
     */
    fun notRegisteredIds(responseBody: String): List<String> {
        val json = runCatching { JSONObject(responseBody) }.getOrNull() ?: return emptyList()
        return json.keys().asSequence().filter { key ->
            val entry = json.optJSONObject(key) ?: return@filter false
            !entry.optBoolean("success", true) &&
                entry.optJSONObject("error")?.optString("code") == "not_registered"
        }.toList()
    }

    private fun sensorType(spec: HaSensorSpec) = if (spec.binary) "binary_sensor" else "sensor"
}
