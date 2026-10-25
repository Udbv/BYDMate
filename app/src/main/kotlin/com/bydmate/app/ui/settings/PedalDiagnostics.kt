package com.bydmate.app.ui.settings

import com.bydmate.app.data.vehicle.BatchReadItem

/**
 * READ-side snapshot of what the Tang L firmware offers for a gentler pedal and a one-pedal city
 * mode: e-pedal, the regen ("energy feedback") level, the power-response curve, the learner and
 * rapid-acceleration limits, Custom drive mode and auto hold. Names and numbers are the Tang L
 * catalog ones (dump 2026-10-24); none of them reached the 2026-10-25 recorder, so the first dump
 * says which exist (`*_CONFIG*` flags, 0 = present like the drive-mode support fids) and what the
 * current settings read. Reads only: nothing here is written.
 */
internal object PedalDiagnostics {

    private class Row(val name: String, val dev: Int, val fid: Int)

    private val FIDS: List<Row> = listOf(
        Row("e_pedal_config", DEV_SETTING, 888143928),            // Setting.SET_E_PEDAL_MODE_CONFIG
        Row("e_pedal_state", DEV_SETTING, 888143930),             // Setting.SET_E_PEDAL_MODE_STATE
        Row("feedback_gear_config", DEV_SETTING, 812660774),      // Setting.SETTING_FEEDBACK_GEAR_FUNCTION_CONFG_STATUS
        Row("custom_feedback_config", DEV_SETTING, 795871253),    // Setting.SETTING_CUSTOM_ENERGY_FEEDBACK_CONFIG_FLAG
        Row("feedback_option_config", DEV_ADAS, 898668606),       // Adas.ADAS_ENERGY_FEEDBACK_CUSTOMIZATION_OPTION_CONFIG_FLAG
        Row("feedback_actual_gear", DEV_ADAS, 898668608),         // Adas.ADAS_ENERGY_FEEDBACK_CUSTOMIZE_ACTUAL_GEAR
        Row("feedback_ability_level", DEV_ENGINE, 681574568),     // Engine.ENGINE_FEEDBACK_ABILITY_TARGET_LEVEL
        Row("feedback_ability_switch", DEV_ENGINE, 681574687),    // Engine.ENGINE_FEEDBACK_ABILITY_TARGET_LEVEL_SWITCH
        Row("custom_response_config", DEV_SETTING, 795871255),    // Setting.SETTING_CUSTOM_POWER_RESPONSE_CONFIG_FLAG
        Row("response_setting", DEV_SETTING, 795871286),          // Setting.SETTING_POWER_RESPONSE_ACTUAL_STATUS
        Row("response_engine", DEV_ENGINE, 681574643),            // Engine.ENGINE_POWER_RESPONSE_ACTUAL_STATUS
        Row("response_gear_position", DEV_ENERGY, 898668612),     // Energy.ENERGY_CUSTOMIZE_POWER_RESPONSE_ACTUAL_GEAR_POSITION
        Row("custom_mode_flag", DEV_SETTING, 1219518509),         // Engine.ENGINE_CUSTOM_MODE_CONFIGURATION_FLAG (drive-mode support range)
        Row("max_torque_mode_flag", DEV_SETTING, 1219518510),     // Setting.SETTING_MAX_TORQUE_MODE_CONFIGURATION_FLAG
        Row("learner_config", DEV_SETTING, 888143916),            // Setting.SET_LEARNER_DRIVER_MODE_CONIG
        Row("learner_state", DEV_SETTING, 888143909),             // Setting.SET_LEARNER_DRIVER_MODE_STATE
        Row("learner_acc_limit", DEV_SETTING, 888143912),         // Setting.SET_LEARNER_DRIVER_ACC_LIMIT_STATE
        Row("quick_start_limit_config", DEV_SETTING, 888143917),  // Setting.SETTING_QUICK_START_ACCELERATION_LIMIT_CONFIG
        Row("quick_start_limit_status", DEV_SETTING, 888143913),  // Setting.SETTING_QUICK_START_ACCELERATION_LIMIT_STATUS
        Row("rapid_accel_limit", DEV_SETTING, 182452237),         // Setting.SETTING_ACTIVATE_RAPID_ACCELERATION_LIMIT
        Row("has_avh", DEV_ADAS, 839909432),                      // Adas.ADAS_HAS_AVH
        Row("avh_state", DEV_ADAS, 304087110),                    // Adas.ADAS_AVH_STATE
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
            val rendered = if (status != 0) "(status=$status)" else value.toString()
            "${row.name}[dev=${row.dev} fid=${row.fid}]=$rendered"
        }
    }

    private const val TX_GET_INT = 5
    private const val DEV_ENERGY = 1006
    private const val DEV_ENGINE = 1012
    private const val DEV_SETTING = 1023
    private const val DEV_ADAS = 1038
}
