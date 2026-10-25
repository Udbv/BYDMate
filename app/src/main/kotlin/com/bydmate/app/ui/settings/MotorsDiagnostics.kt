package com.bydmate.app.ui.settings

import com.bydmate.app.data.vehicle.BatchReadItem

/**
 * READ-side snapshot of the two drive motors (#8) for the diagnostic dump: speed and torque per
 * axle, the bus voltage each inverter sees, and the motor power the Power device reports. Speed
 * x torque gives each axle's mechanical power, so a dump taken while driving shows the front/rear
 * split the issue asks about. Addresses are the Tang L catalog ones (dump 2026-10-24); the speeds
 * are the fids the poll loop already reads as motorRpmFront/Rear. The torques are float fids:
 * read as ints on the Tang L (2026-10-25) the four below answered -10013.
 */
internal object MotorsDiagnostics {

    /** [tx] 7 = getFloat: the Tang L answered -10013 to every torque fid read as an int. */
    private class Row(val name: String, val dev: Int, val fid: Int, val tx: Int = TX_GET_INT)

    private val FIDS: List<Row> = listOf(
        Row("front_speed", DEV_ENGINE, 1141899272),        // Engine.ENGINE_FRONT_MOTOR_SPEED
        Row("rear_speed", DEV_ENGINE, 621805576),          // Engine.ENGINE_REAR_MOTOR_SPEED
        Row("front_torque", DEV_ENGINE, 1141899288, TX_GET_FLOAT),       // Engine.ENGINE_FRONT_MOTOR_TORQUE
        Row("rear_torque", DEV_ENGINE, 621805592),         // Engine.ENGINE_REAR_MOTOR_TORQUE (int: answered 65535)
        Row("rear_torque_f", DEV_ENGINE, 1169162280, TX_GET_FLOAT),      // Engine.ENGINE_REAR_MOTOR_TORQUE_F
        Row("indicated_torque", DEV_ENGINE, 283115528, TX_GET_FLOAT),    // Engine.ENGINE_INDICATED_TORQUE
        Row("rear_target_torque", DEV_GB, 622854184, TX_GET_FLOAT),      // Gb.GB_REAR_MOTOR_TARGET_TORQUE
        Row("front_bus_voltage", DEV_GB, 1169162248),      // Gb.GB_FRONT_MOTOR_BUS_VOLTAGE
        Row("rear_bus_voltage", DEV_GB, 1169162264),       // Gb.GB_REAR_MOTOR_BUS_VOLTAGE
        Row("motor_power", DEV_POWER, 521526),             // Power.POWER_MOTOR_POWER
    )

    fun batchItems(): List<BatchReadItem> = FIDS.map { BatchReadItem(it.tx, it.dev, it.fid) }

    /**
     * One line per fid from raw (status, value) pairs in [FIDS] order. A null [readings]
     * (daemon unreachable, timeout, old daemon) or a length mismatch collapses to one line.
     */
    fun format(readings: List<Pair<Int, Int>>?): List<String> {
        if (readings == null || readings.size != FIDS.size) return listOf("(unavailable)")
        return FIDS.mapIndexed { i, row ->
            val (status, value) = readings[i]
            val rendered = when {
                status != 0 -> "(status=$status)"
                row.tx == TX_GET_FLOAT -> java.lang.Float.intBitsToFloat(value).toString()
                else -> value.toString()
            }
            "${row.name}[dev=${row.dev} fid=${row.fid}]=$rendered"
        }
    }

    private const val TX_GET_INT = 5
    private const val TX_GET_FLOAT = 7
    private const val DEV_POWER = 1005
    private const val DEV_ENGINE = 1012
    private const val DEV_GB = 1039
}
