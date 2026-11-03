package com.bydmate.app.data.homeassistant

import com.bydmate.app.data.remote.DiParsData
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric for the real org.json: the android.jar stubs return defaults on the JVM.
@RunWith(RobolectricTestRunner::class)
class HaPayloadsTest {

    @Test fun `registration carries the mobile_app fields`() {
        val json = HaPayloads.registration(HaDeviceInfo("dev-1", "4.0.1", "DiLink", "13"))
        assertEquals("dev-1", json.getString("device_id"))
        assertEquals("bydmate", json.getString("app_id"))
        assertEquals("BYDMate", json.getString("app_name"))
        assertEquals("4.0.1", json.getString("app_version"))
        assertEquals("BYDMate", json.getString("device_name"))
        assertEquals("BYD", json.getString("manufacturer"))
        assertEquals("DiLink", json.getString("model"))
        assertEquals("Android", json.getString("os_name"))
        assertFalse(json.getBoolean("supports_encryption"))
    }

    @Test fun `register_sensor has metadata and the first state`() {
        val soc = HaSensorCatalog.ALL.first { it.uniqueId == "bydmate_soc" }
        val json = HaPayloads.registerSensor(soc, HaSnapshot(data(soc = 67), 300.0))
        assertEquals("register_sensor", json.getString("type"))
        val d = json.getJSONObject("data")
        assertEquals("bydmate_soc", d.getString("unique_id"))
        assertEquals("sensor", d.getString("type"))
        assertEquals("battery", d.getString("device_class"))
        assertEquals("%", d.getString("unit_of_measurement"))
        assertEquals("measurement", d.getString("state_class"))
        assertEquals(67, d.getInt("state"))
    }

    @Test fun `register_sensor without a reading sends null state`() {
        val charging = HaSensorCatalog.ALL.first { it.uniqueId == "bydmate_charging" }
        val d = HaPayloads.registerSensor(charging, null).getJSONObject("data")
        assertEquals("binary_sensor", d.getString("type"))
        assertTrue(d.isNull("state"))
    }

    @Test fun `update leaves out sensors without a reading`() {
        val json = HaPayloads.updateStates(HaSnapshot(data(soc = 55, powerState = 2, chargingStatus = 2), 287.6))!!
        assertEquals("update_sensor_states", json.getString("type"))
        val states = json.getJSONArray("data")
        val byId = (0 until states.length()).associate { i ->
            states.getJSONObject(i).let { it.getString("unique_id") to it }
        }
        assertEquals(setOf("bydmate_soc", "bydmate_range", "bydmate_power_state", "bydmate_charging", "bydmate_plugged"), byId.keys)
        assertEquals(288, byId.getValue("bydmate_range").getInt("state"))
        assertEquals("drive", byId.getValue("bydmate_power_state").getString("state"))
        assertTrue(byId.getValue("bydmate_charging").getBoolean("state"))
        assertEquals("binary_sensor", byId.getValue("bydmate_charging").getString("type"))
    }

    @Test fun `update is null when nothing has a reading`() {
        assertNull(HaPayloads.updateStates(HaSnapshot(data(), null)))
    }

    @Test fun `not_registered ids are picked from the update answer`() {
        val body = """{"bydmate_soc":{"success":true},
            "bydmate_range":{"success":false,"error":{"code":"not_registered","message":"x"}},
            "bydmate_12v":{"success":false,"error":{"code":"invalid_format"}}}"""
        assertEquals(listOf("bydmate_range"), HaPayloads.notRegisteredIds(body))
        assertTrue(HaPayloads.notRegisteredIds("not json").isEmpty())
        assertTrue(HaPayloads.notRegisteredIds("{}").isEmpty())
    }

    @Test fun `registration answer parses, null cloudhook stays null`() {
        val reg = HaRegistration.parse(JSONObject().put("webhook_id", "abc").put("cloudhook_url", JSONObject.NULL).toString())!!
        assertEquals("abc", reg.webhookId)
        assertNull(reg.cloudhookUrl)
        assertNull(reg.remoteUiUrl)
        assertNull(HaRegistration.parse("{}"))
        assertNull(HaRegistration.parse("<html>"))
    }

    @Test fun `doors windows lock and trunk map to binary states`() {
        fun state(id: String, d: DiParsData) = HaSensorCatalog.ALL.first { it.uniqueId == id }.state(HaSnapshot(d, null))
        assertEquals(true, state("bydmate_doors", data(doorRR = 1)))
        assertEquals(false, state("bydmate_doors", data(doorRR = 0)))
        assertEquals(true, state("bydmate_windows", data(windowFL = 30)))
        assertEquals(true, state("bydmate_locked", data(lockFL = 1)))
        assertEquals(false, state("bydmate_locked", data(lockFL = 2)))
        assertEquals(false, state("bydmate_trunk", data(trunk = 2)))
        assertEquals(true, state("bydmate_trunk", data(trunk = 3)))
        assertNull(state("bydmate_doors", data()))
    }

    @Suppress("LongParameterList")
    private fun data(
        soc: Int? = null,
        powerState: Int? = null,
        chargingStatus: Int? = null,
        doorRR: Int? = null,
        windowFL: Int? = null,
        lockFL: Int? = null,
        trunk: Int? = null,
    ) = DiParsData(
        soc = soc, speed = null, mileage = null, power = null, chargeGunState = null,
        maxBatTemp = null, avgBatTemp = null, minBatTemp = null, chargingStatus = chargingStatus,
        batteryCapacityKwh = null, totalElecConsumption = null, voltage12v = null,
        maxCellVoltage = null, minCellVoltage = null, exteriorTemp = null, gear = null,
        powerState = powerState, insideTemp = null, acStatus = null, acTemp = null, fanLevel = null,
        acCirc = null, doorFL = null, doorFR = null, doorRL = null, doorRR = doorRR,
        windowFL = windowFL, windowFR = null, windowRL = null, windowRR = null, sunroof = null,
        trunk = trunk, hood = null, seatbeltFL = null, lockFL = lockFL, tirePressFL = null,
        tirePressFR = null, tirePressRL = null, tirePressRR = null, driveMode = null,
        workMode = null, autoPark = null, rain = null, lightLow = null, drl = null,
    )
}
