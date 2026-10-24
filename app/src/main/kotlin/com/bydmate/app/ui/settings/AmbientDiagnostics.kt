package com.bydmate.app.ui.settings

import com.bydmate.app.data.vehicle.BatchReadItem

/**
 * READ-side snapshot of the interior ambient light (#4) for the diagnostic dump. On the Tang L
 * the `ambient_light_on` write (SET_INTERIOR_ATMOSPHERE_LAMP_BRIGHTNESS_SET) answers status OK,
 * but its readback is -10011: the SET fid is write-only, so nothing says whether the light came
 * on. These are the readable counterparts the Tang L catalog (dump 2026-10-24) names; one dump
 * with the light on and one with it off tells which of them follows it.
 */
internal object AmbientDiagnostics {

    private class Row(val name: String, val dev: Int, val fid: Int)

    private val FIDS: List<Row> = listOf(
        Row("has_interior_lamp", DEV_SETTING, 1072693248),       // Setting.SET_HAS_INTERIOR_ATMOSPHERE_LAMP
        Row("interior_brightness", DEV_SETTING, 522414),         // Setting.SET_INTERIOR_ATMOSPHERE_LAMP_BRIGHTNESS
        Row("interior_color", DEV_SETTING, 283586),              // Setting.SET_INTERIOR_ATMOSPHERE_LAMP_COLOR
        Row("interior_area", DEV_SETTING, 1121976352),           // Setting.SET_INTERIOR_ATMOSPHERE_LAMP_AREA
        Row("panel_state", DEV_SETTING, 993001480),              // Setting.SET_ATMOSPHERE_LAMP_PANEL_STATE
        Row("main_switch", DEV_LIGHT, 1060110406),               // Light.LIGHT_ATMOSPHERE_MAIN_SWITCH
        Row("master_switch_flag", DEV_LIGHT, 1121976388),        // Light.LIGHT_ATMOSPHERE_LAMP_MASTER_SWITCH_FLAG
        Row("custom_brightness", DEV_LIGHT, 657457175),          // Light.LIGHT_ATMOSPHERE_CUSTOM_BRIGHTNESS
        Row("environmental_mode_switch", DEV_LIGHT, 679477714),  // Light.LIGHT_ATMOSPHERE_ENVIRNMENTAL_MODE_SWITCH
    )

    fun batchItems(): List<BatchReadItem> = FIDS.map { BatchReadItem(TX_GET_INT, it.dev, it.fid) }

    /**
     * One line per fid from raw (status, value) pairs in [FIDS] order. A null [readings]
     * (daemon unreachable, timeout, old daemon) or a length mismatch collapses to one line.
     */
    fun format(readings: List<Pair<Int, Int>>?): List<String> {
        if (readings == null || readings.size != FIDS.size) return listOf("(unavailable)")
        return FIDS.mapIndexed { i, row ->
            val (status, value) = readings[i]
            val rendered = if (status == 0) value.toString() else "(status=$status)"
            "${row.name}[dev=${row.dev} fid=${row.fid}]=$rendered"
        }
    }

    private const val TX_GET_INT = 5
    private const val DEV_LIGHT = 1004
    private const val DEV_SETTING = 1023
}
