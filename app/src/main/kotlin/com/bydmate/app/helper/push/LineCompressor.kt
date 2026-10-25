package com.bydmate.app.helper.push

/**
 * Piecewise-linear compression of one fid's samples (a "swinging door"): a sample is dropped while
 * the straight line from the last written point to the newest sample passes within [tolerance] of
 * every sample dropped in between. So a ramp or a steady value costs two lines, while a bump, a step or a peak
 * is kept exactly. A reader rebuilds the signal by joining the written points with straight lines.
 *
 * [P] is whatever the caller writes for a sample; the compressor only decides which one, and when.
 * Not thread-safe: the recorder calls it under its own lock.
 */
internal class LineCompressor<P>(private val tolerance: Double) {

    private var anchored = false
    private var anchorT = 0L
    private var anchorV = 0.0

    private var held: P? = null
    private var heldT = 0L
    private var heldV = 0.0

    /** Bounds of the slopes from the anchor that still fit every sample since it. */
    private var low = 0.0
    private var high = 0.0

    /** When the sample that is held back arrived; null when none is held. */
    val heldAt: Long? get() = if (held != null) heldT else null

    /**
     * Feeds one sample at [t] ms with value [v]. Returns the payload to write now: the very first
     * sample, or the held one when this sample no longer fits the line through it. Otherwise null.
     */
    fun offer(t: Long, v: Double, payload: P): P? {
        if (!anchored) {
            anchor(t, v)
            return payload
        }
        val previous = held
        if (previous == null) {
            hold(t, v, payload)
            return null
        }
        val dt = (t - anchorT).coerceAtLeast(1L).toDouble()
        val newLow = maxOf(low, (v - tolerance - anchorV) / dt)
        val newHigh = minOf(high, (v + tolerance - anchorV) / dt)
        // The line written is anchor → this sample itself, so its own slope must fit the corridor
        // of every sample dropped before it, not just overlap it.
        val slope = (v - anchorV) / dt
        if (slope in newLow..newHigh) {
            low = newLow
            high = newHigh
            held = payload
            heldT = t
            heldV = v
            return null
        }
        // The line broke: the last sample that still fitted becomes the new anchor.
        anchor(heldT, heldV)
        hold(t, v, payload)
        return previous
    }

    /** Writes out the held sample, if any: the signal went quiet or the run is ending. */
    fun flush(): P? {
        val previous = held ?: return null
        anchor(heldT, heldV)
        return previous
    }

    private fun anchor(t: Long, v: Double) {
        anchored = true
        anchorT = t
        anchorV = v
        held = null
    }

    private fun hold(t: Long, v: Double, payload: P) {
        held = payload
        heldT = t
        heldV = v
        val dt = (t - anchorT).coerceAtLeast(1L).toDouble()
        low = (v - tolerance - anchorV) / dt
        high = (v + tolerance - anchorV) / dt
    }
}

/**
 * The fast signals of a drive, recorded at the firmware's own rate instead of two lines a second,
 * with [LineCompressor] at the tolerance given here (the signal's own unit). These are what a
 * speed bump, a brake or a lane change shows in: body motion, wheel speeds, steering, pedals,
 * motors and the suspension's own accelerometers. Matched on the catalog symbol.
 */
internal val FID_REC_FAST_TOLERANCES: Map<String, Double> = mapOf(
    "Adas.ADAS_X_AXIS_ACCELERATION" to 0.02,
    "Adas.ADAS_Y_AXIS_ACCELERATION" to 0.02,
    "Adas.ADAS_WX_ROLL_RATE" to 0.3,
    "Adas.ADAS_WY_PITCH_RATE" to 0.3,
    "Adas.ADAS_WZ_YAW_RATE" to 0.3,
    "Sensor.SENSOR_AX_223" to 5.0,
    "Bodywork.BODYWORK_DISUS_IMU_MAX_VERTICAL_ACCELERATION" to 0.0,
    "Bodywork.BODYWORK_DISUS_IMU_MAX_DIFFERENCE_VERTICAL_ACCELERATION" to 0.0,
    "Bodywork.BODYWORK_DISUS_FLANK_VERTICAL_ACCELERATION" to 0.0,
    "Special.SPECIAL_CMD_WHEEL_SPEED_FL" to 0.2,
    "Special.SPECIAL_CMD_WHEEL_SPEED_FR" to 0.2,
    "Special.SPECIAL_CMD_WHEEL_SPEED_RL" to 0.2,
    "Special.SPECIAL_CMD_WHEEL_SPEED_RR" to 0.2,
    "Speed.SPEED_AUTO_SPEED_121" to 0.1,
    "Bodywork.BODYWORK_STEERING_WHEEL_ANGEL" to 0.5,
    "Speed.SPEED_ACCELERATOR_S" to 0.5,
    "Speed.SPEED_BRAKE_S" to 0.5,
    "Engine.ENGINE_FRONT_MOTOR_SPEED" to 20.0,
    "Engine.ENGINE_REAR_MOTOR_SPEED" to 20.0,
    "Engine.ENGINE_FRONT_MOTOR_TORQUE" to 1.0,
    "Engine.ENGINE_REAR_MOTOR_TORQUE_F" to 1.0,
    "Charging.CHARGING_CHARGE_CURRENT" to 1.0,
)

/** The number a fast fid's line is compressed on: its double, or its int when the double is unused. */
internal fun fidRecValue(intValue: Int, doubleValue: Double): Double =
    if (doubleValue != 0.0) doubleValue else intValue.toDouble()
