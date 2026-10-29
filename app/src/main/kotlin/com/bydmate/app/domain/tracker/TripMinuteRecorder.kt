package com.bydmate.app.domain.tracker

import android.util.Log
import com.bydmate.app.data.local.dao.TripMinuteDao
import com.bydmate.app.data.local.entity.TripMinuteEntity
import com.bydmate.app.data.nativestack.MotorSplit
import com.bydmate.app.data.nativestack.motorSplitPercent
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.domain.calculator.powerKw
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the battery's power goes at one poll tick, in kW. The battery power is the whole draw;
 * the compressor and the 12 V side are measured, the motors get the rest, split between the axles
 * by their currents (phase currents, so they give the ratio, not the kW: see PowerCardState).
 */
internal data class PowerParts(
    val totalKw: Double,
    val climateKw: Double,
    val otherKw: Double,
    val motorsKw: Double,
    /** Front share of the motors 0..100; null when the car reports no split or the motors idle. */
    val frontPercent: Int?,
)

internal fun powerParts(data: DiParsData): PowerParts? {
    val total = powerKw(data) ?: return null
    val climate = (data.compressorW ?: 0) / WATTS_PER_KW
    val other = data.lvCurrentA?.let { amps -> data.voltage12v?.let { volts -> amps * volts / WATTS_PER_KW / DCDC_EFFICIENCY } }
        ?: 0.0
    val split = motorSplitPercent(data.motorCurrentFront, data.motorCurrentRear)
    return PowerParts(
        totalKw = total,
        climateKw = climate,
        otherKw = other,
        motorsKw = total - climate - other,
        frontPercent = (split as? MotorSplit.Share)?.frontPercent,
    )
}

/**
 * Folds poll ticks into clock minutes. [add] returns the minute that just ended when a tick
 * lands in a new one; [flush] the one in progress (the car went off). Pure: the clock comes in.
 */
internal class MinuteAggregator {

    private var minuteTs: Long? = null
    private var lastAt: Long? = null
    private var samples = 0
    private var motors = 0.0
    private var front = 0.0
    private var rear = 0.0
    private var splitKnown = true
    private var regen = 0.0
    private var climate = 0.0
    private var other = 0.0
    private var peak = Double.NEGATIVE_INFINITY
    private var min = Double.POSITIVE_INFINITY
    private var speedSum = 0.0
    private var speedCount = 0
    private var maxSpeed: Int? = null
    private var soc: Int? = null
    private var wiperTicks = 0
    private var wiperKnown = false
    private var tempSum = 0.0
    private var tempCount = 0

    fun add(now: Long, data: DiParsData): TripMinuteEntity? {
        val parts = powerParts(data) ?: return null
        val minute = now - Math.floorMod(now, MINUTE_MS)
        val finished = if (minuteTs != null && minute != minuteTs) flush() else null
        if (minuteTs == null) minuteTs = minute
        // Energy of this tick: the power held since the last one, a gap counting as one tick.
        val dtMs = lastAt?.let { (now - it).coerceIn(0L, MAX_TICK_MS) } ?: FIRST_TICK_MS
        lastAt = now
        val hours = dtMs / MS_PER_HOUR
        samples++
        climate += parts.climateKw * hours
        other += parts.otherKw * hours
        if (parts.motorsKw < 0) {
            regen += parts.motorsKw * hours
        } else {
            motors += parts.motorsKw * hours
            val pct = parts.frontPercent
            if (pct == null) {
                if (parts.motorsKw > IDLE_MOTORS_KW) splitKnown = false
            } else {
                front += parts.motorsKw * hours * pct / PERCENT
                rear += parts.motorsKw * hours * (PERCENT - pct) / PERCENT
            }
        }
        peak = maxOf(peak, parts.totalKw)
        min = minOf(min, parts.totalKw)
        data.speed?.let { speed ->
            speedSum += speed
            speedCount++
            maxSpeed = maxOf(maxSpeed ?: speed, speed)
        }
        data.soc?.let { soc = it }
        addWeather(data)
        return finished
    }

    private fun addWeather(data: DiParsData) {
        data.wiperRelay?.let { relay ->
            wiperKnown = true
            if (relay != 0) wiperTicks++
        }
        data.exteriorTemp?.let { temp ->
            tempSum += temp
            tempCount++
        }
    }

    /** The minute in progress as a row, and a fresh start; null when it holds no tick. */
    fun flush(): TripMinuteEntity? {
        val ts = minuteTs ?: return null
        val row = TripMinuteEntity(
            minuteTs = ts,
            samples = samples,
            motorsKwh = motors,
            frontKwh = front.takeIf { splitKnown },
            rearKwh = rear.takeIf { splitKnown },
            regenKwh = regen,
            climateKwh = climate,
            otherKwh = other,
            peakKw = peak,
            minKw = min,
            avgSpeed = if (speedCount > 0) speedSum / speedCount else null,
            maxSpeed = maxSpeed,
            soc = soc,
            wiperSamples = wiperTicks.takeIf { wiperKnown },
            extTemp = if (tempCount > 0) tempSum / tempCount else null,
        )
        reset()
        return row
    }

    private fun reset() {
        minuteTs = null
        samples = 0
        motors = 0.0
        front = 0.0
        rear = 0.0
        splitKnown = true
        regen = 0.0
        climate = 0.0
        other = 0.0
        peak = Double.NEGATIVE_INFINITY
        min = Double.POSITIVE_INFINITY
        speedSum = 0.0
        speedCount = 0
        maxSpeed = null
        soc = null
        wiperTicks = 0
        wiperKnown = false
        tempSum = 0.0
        tempCount = 0
    }

    companion object {
        const val MINUTE_MS = 60_000L

        /** A longer silence is a gap (process paused, power-off): it counts as one tick, not as power held. */
        const val MAX_TICK_MS = 5_000L
        const val FIRST_TICK_MS = 1_000L
    }
}

/** The trip page's per-minute rows: fed every poll tick while the car is on. */
@Singleton
class TripMinuteRecorder @Inject constructor(
    private val dao: TripMinuteDao,
) {
    private val aggregator = MinuteAggregator()

    suspend fun onTick(carOn: Boolean, data: DiParsData?, now: Long = System.currentTimeMillis()) {
        val row = if (carOn && data != null) aggregator.add(now, data) else aggregator.flush()
        row?.let { save(it) }
    }

    @Suppress("TooGenericExceptionCaught") // a lost minute must never stop the poll loop
    private suspend fun save(row: TripMinuteEntity) {
        try {
            dao.upsert(row)
        } catch (e: Exception) {
            Log.w(TAG, "minute ${row.minuteTs} not saved: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "TripMinutes"
    }
}

private const val WATTS_PER_KW = 1000.0
private const val PERCENT = 100.0
private const val MS_PER_HOUR = 3_600_000.0

/** The DC-DC's own losses: the 12 V load costs the battery a little more than it uses. */
private const val DCDC_EFFICIENCY = 0.92

/** Motors drawing less than this with no split are standing still, not an unknown split. */
private const val IDLE_MOTORS_KW = 0.5
