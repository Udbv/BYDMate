package com.bydmate.app.helper.push

/**
 * Detail on demand for the fast signals of a drive (body motion, wheels, steering, pedals, motors):
 * every sample, up to [MIN_GAP_MS] apart, goes into an in-memory ring of the last [PRE_MS]; the file
 * only gets what matters.
 *
 * - Normally each signal is written through its own [LineCompressor] at the tolerance of
 *   [FID_REC_FAST_TOLERANCES]: a smooth stretch costs its two ends.
 * - A trigger ([FID_REC_TRIGGERS] crossed, or a change of a [FID_REC_EVENT_SYMBOLS] fid such as the
 *   driver's volume-button marker) writes a note, then the whole ring of every fast signal raw, and
 *   keeps them raw until [POST_MS] after the last trigger. So a speed bump, a hard brake or a marked
 *   spot lands in full detail, including the seconds before it was noticed.
 *
 * Not thread-safe: [FidRecorder] calls it under its own lock.
 */
internal class FastTrack {

    private class Signal(val tolerance: Double) {
        var compressor = LineCompressor<RecEvent>(tolerance)
        val ring = ArrayDeque<RecEvent>()
        var lastOffered: Long? = null
        var pending: RecEvent? = null
        var lastWrittenAt = Long.MIN_VALUE
        var lastWritten: RecEvent? = null
        var detailed = false
    }

    private val signals = HashMap<Int, Signal>()
    private var detailUntil = Long.MIN_VALUE

    fun clear() {
        signals.clear()
        detailUntil = Long.MIN_VALUE
    }

    fun adopt(fid: Int, symbol: String) {
        val tolerance = FID_REC_FAST_TOLERANCES[symbol] ?: return
        signals.getOrPut(fid) { Signal(tolerance) }
    }

    fun isFast(fid: Int): Boolean = fid in signals

    /** A changed value of a fast fid; what is to be written goes to [out]. */
    fun sample(e: RecEvent, out: MutableList<RecEvent>) {
        val s = signals[e.fid] ?: return
        val last = s.lastOffered
        if (last != null && e.atMs - last < MIN_GAP_MS) {
            s.pending = e
            return
        }
        take(s, e, out)
        val threshold = FID_REC_TRIGGERS[e.symbol]
        val value = fidRecValue(e.intValue, e.doubleValue)
        if (threshold != null && kotlin.math.abs(value) >= threshold) {
            trigger(e.atMs, "${e.symbol}=$value", out)
        }
    }

    /**
     * Something worth seeing in full happened at [now]: the note, the ring of every fast signal
     * not written yet, and raw lines until [POST_MS] after it.
     */
    fun trigger(now: Long, why: String, out: MutableList<RecEvent>) {
        val opening = now >= detailUntil
        detailUntil = maxOf(detailUntil, now + POST_MS)
        if (!opening) return
        out += RecEvent(now, 0, 0, "#detail", 0, 0.0).apply { note = "detail from -$PRE_MS ms: $why" }
        for (s in signals.values) {
            // The ring holds every sample, the compressor's held one included: written raw instead.
            s.ring.filter { it.atMs > s.lastWrittenAt }.forEach { write(s, it, out) }
            s.detailed = true
        }
    }

    /** Housekeeping: a burst's held-back end, and the last value of a signal that went quiet. */
    fun tick(now: Long, out: MutableList<RecEvent>) {
        for (s in signals.values) {
            val pending = s.pending
            if (pending != null && now - (s.lastOffered ?: 0L) >= MIN_GAP_MS) take(s, pending, out)
            val heldAt = s.compressor.heldAt
            if (!s.detailed && heldAt != null && now - heldAt >= QUIET_MS) {
                s.compressor.flush()?.let { write(s, it, out) }
            }
        }
    }

    /** The run is ending: everything still held or pending lands. */
    fun drain(out: MutableList<RecEvent>) {
        for (s in signals.values) {
            s.pending?.let { take(s, it, out) }
            // In a window the held sample was already written raw from the ring.
            if (!s.detailed) s.compressor.flush()?.let { write(s, it, out) }
        }
    }

    private fun take(s: Signal, e: RecEvent, out: MutableList<RecEvent>) {
        s.lastOffered = e.atMs
        s.pending = null
        s.ring.addLast(e)
        while (s.ring.isNotEmpty() && s.ring.first().atMs < e.atMs - PRE_MS) s.ring.removeFirst()
        val v = fidRecValue(e.intValue, e.doubleValue)
        if (e.atMs < detailUntil) {
            write(s, e, out)
            return
        }
        if (s.detailed) {
            // Back from a detail window: the next line starts where the raw lines ended.
            s.detailed = false
            s.compressor = LineCompressor(s.tolerance)
            s.lastWritten?.let { s.compressor.restartAt(it.atMs, fidRecValue(it.intValue, it.doubleValue)) }
        }
        s.compressor.offer(e.atMs, v, e)?.let { write(s, it, out) }
    }

    private fun write(s: Signal, e: RecEvent, out: MutableList<RecEvent>) {
        if (e === s.lastWritten) return
        s.lastWrittenAt = e.atMs
        s.lastWritten = e
        out += e
    }

    companion object {
        /** How far back a detail window reaches: a volume-button marker comes after the bump. */
        const val PRE_MS = 8_000L

        /** How long a detail window stays open after its last trigger. */
        const val POST_MS = 3_000L

        /** 50 Hz per signal at most: enough for a bump, and a firmware flood cannot fill the file. */
        const val MIN_GAP_MS = 20L

        /** A compressed signal's held-back sample is written once no change came for this long. */
        const val QUIET_MS = 300L
    }
}

/**
 * The fast signals and the tolerance (the signal's own unit, about its noise) their compressed
 * lines keep outside detail windows. Matched on the catalog symbol.
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

/**
 * A fast signal at or beyond this magnitude opens a detail window. Tuned on the 2026-10-25 highway
 * recording (at rest: accelerations ±0.02 g, rates ±0.15 °/s): these open ~100 windows in 2 h, about
 * 14 % of the drive; half these values opened 250 (39 %). Bumps the thresholds miss are still caught
 * by the driver's volume-button marker.
 */
internal val FID_REC_TRIGGERS: Map<String, Double> = mapOf(
    "Adas.ADAS_X_AXIS_ACCELERATION" to 0.25,
    "Adas.ADAS_Y_AXIS_ACCELERATION" to 0.3,
    "Adas.ADAS_WY_PITCH_RATE" to 5.0,
    "Adas.ADAS_WX_ROLL_RATE" to 6.0,
    "Speed.SPEED_BRAKE_S" to 30.0,
    "Bodywork.BODYWORK_DISUS_IMU_MAX_VERTICAL_ACCELERATION" to 0.000_001,
    "Bodywork.BODYWORK_DISUS_FLANK_VERTICAL_ACCELERATION" to 0.000_001,
)

/**
 * Any change of these opens a detail window: the volume buttons (the driver's "here" marker) and the
 * traffic light ahead turning.
 */
internal val FID_REC_EVENT_SYMBOLS: Set<String> = setOf(
    "Audio.AUDIO_VOLUME",
    "Adas.ADAS_TLA_STRAIGHT_LIGHT_STATUS",
)

/** The number a fast fid is judged on: its double, or its int when the double is unused. */
internal fun fidRecValue(intValue: Int, doubleValue: Double): Double =
    if (doubleValue != 0.0) doubleValue else intValue.toDouble()
