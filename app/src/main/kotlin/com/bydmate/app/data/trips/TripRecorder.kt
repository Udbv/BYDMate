package com.bydmate.app.data.trips

import com.bydmate.app.data.local.EnergyDataDeadDetector
import com.bydmate.app.data.local.EnergyDataReader
import com.bydmate.app.data.local.dao.LastStateDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.entity.LastStateEntity
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.nativestack.VehicleKind
import com.bydmate.app.data.remote.DiParsData
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's own trips, from the polling ticks: open at ignition-on, close at ignition-off,
 * persisted in last_state so a process restart resumes them.
 *
 * Active (no energydata file, or it is marked dead): the closed trip goes to the trips table.
 * Passive (the energydata file is trusted to bring the trip): the same trip is kept as a shadow
 * ([shadowSink], ShadowTripResolver) and saved only when the next import brings no BYD record for
 * it (2026-11-14: a dead file wrongly healed, the morning's drives were lost).
 *
 * The trip starts at the first odometer movement, not at ignition-on: a warm-up (car on, maybe
 * plugged in, odometer still) is not part of the drive. The start values (time, SoC, energy
 * counters, outside temperature) are those of the last tick before the car moved.
 */
@Singleton
class TripRecorder @Inject @Suppress("LongParameterList") constructor( // injected dependencies + test clocks
    private val tripDao: TripDao,
    private val lastStateDao: LastStateDao,
    private val energyDataReader: EnergyDataReader,
    private val deadDetector: EnergyDataDeadDetector,
    private val batteryCapacityKwh: suspend () -> Double,
    private val now: () -> Long = { System.currentTimeMillis() },
    /** Car type; every DM-i branch below is behind HYBRID, EV and UNKNOWN take the old path. */
    private val kind: () -> VehicleKind = { VehicleKind.UNKNOWN },
    /** Passive mode: where a closed trip goes instead of the trips table. */
    private val shadowSink: (TripEntity) -> Unit = {},
) {
    // In-memory mirror of last_state.open_trip_*; rebuilt at cold-start from DAO.
    internal data class Open(
        val startTs: Long,
        val startSoc: Int?,
        val startMileage: Double?,
        val startTotalElec: Double? = null,
        /** Outside temperature when the trip opened, °C. */
        val startExteriorTemp: Int? = null,
        /** DM-i only: lifetime fuel counter at open, L. */
        val startFuelTotal: Double? = null,
        /** DM-i only: engine odometer at open, km. */
        val startMileageHev: Double? = null,
        /** The odometer the first movement is measured from; the first valid reading when the open one was not. */
        val refKm: Double? = startMileage?.takeIf { it >= TripMath.MIN_VALID_ODOMETER_KM },
        /** The odometer moved since the trip opened: its start is the departure. */
        val moved: Boolean = false,
        /** The last tick before the car moved: the values the trip starts with after a warm-up. */
        val still: DiParsData? = null,
        /** Ticks the car stood on since the open tick; none: the start already is the departure. */
        val stillTicks: Int = 0,
    )
    internal var open: Open? = null

    private val hybridEdge = HybridIgnitionEdge()
    /** DM-i: the trip's end edge, one ON tick with its time, counters and odometer. */
    private val endEdge = ReadableOnTick()

    private var nullPowerStreak = 0
    private suspend fun ignitionOn(data: DiParsData): Boolean {
        if (kind() == VehicleKind.HYBRID) {
            val nowMs = now()
            val on = hybridEdge.update(data, nowMs)
            if (endEdge.update(data, nowMs, isOnTick = on && hybridEdge.lastOnMs == nowMs)) {
                // Kept in last_state too, where the next cold start finds the trip's end edge.
                lastStateDao.setLastOn(nowMs, endEdge.fuelTotalL, endEdge.mileageHevKm, endEdge.odometerKm)
            }
            return on
        }
        if (data.powerState != null) { nullPowerStreak = 0; return data.powerState == 2 }
        nullPowerStreak++
        val fallback = nullPowerStreak >= 5
        return fallback && (data.gear == 4 || ((data.speed ?: 0) > 0))
    }

    /** Active: the app writes the trips itself. Passive: energydata should, the app keeps a shadow. */
    private fun isActive(): Boolean = !energyDataReader.isAvailable() || deadDetector.isDead()

    suspend fun consume(data: DiParsData) {
        val ignitionOn = ignitionOn(data)
        // Issue #63: feed the dead-leftover detector on every tick, even while passive —
        // it is the only component that can flip this device to native recording.
        deadDetector.onTick(ignitionOn, data.mileage)
        deadDetector.checkHealIfDue()
        val cur = open
        when {
            cur == null && ignitionOn -> openTrip(data)
            cur != null && !ignitionOn -> close(cur, data)
            cur != null -> trackDeparture(cur, data)
        }
    }

    private suspend fun openTrip(data: DiParsData) {
        val startTs = now()
        val hybrid = kind() == VehicleKind.HYBRID
        val startFuel = if (hybrid) data.fuelTotalL else null
        val startHev = if (hybrid) data.mileageHevKm?.toDouble() else null
        open = Open(startTs, data.soc, data.mileage, data.totalElecConsumption, data.exteriorTemp, startFuel, startHev)
        Log.i(TAG, "trip open: ts=$startTs soc=${data.soc} ext_temp=${data.exteriorTemp} active=${isActive()}")
        // Spec §96-100: persist open trip to last_state so cold-start can resume.
        val updated = lastStateDao.openTrip(
            startTs = startTs,
            startSoc = data.soc,
            startMileage = data.mileage,
            startTotalElec = data.totalElecConsumption,
            startExteriorTemp = data.exteriorTemp,
            now = startTs,
        )
        if (updated == 0) {
            // Very-first-ever tick before the loop has written a snapshot: create the row
            // (snapshot columns default/NULL -- the loop's own write for this same tick
            // fills them in) then retry the trip-columns-only UPDATE, so this fallback
            // cannot clobber a snapshot written concurrently by SharedAdaptiveLoop.
            lastStateDao.insertSnapshotRowIfAbsent(
                ts = startTs, soc = null, mileage = null, totalElec = null,
                ignition = null, energydataAvailable = 0,
            )
            lastStateDao.openTrip(
                startTs = startTs,
                startSoc = data.soc,
                startMileage = data.mileage,
                startTotalElec = data.totalElecConsumption,
                startExteriorTemp = data.exteriorTemp,
                now = startTs,
            )
        }
        if (hybrid) lastStateDao.setOpenTripCounters(startFuel, startHev)
    }

    /**
     * Until the odometer moves, the open trip only waits: each standing tick is remembered. At the
     * first movement after standing ticks (a warm-up), the trip is moved to start there.
     */
    private suspend fun trackDeparture(cur: Open, data: DiParsData) {
        if (cur.moved) return
        val km = data.mileage?.takeIf { it >= TripMath.MIN_VALID_ODOMETER_KM } ?: return
        val ref = cur.refKm
        when {
            ref == null -> open = cur.copy(refKm = km)
            km - ref < DEPARTURE_KM -> open = cur.copy(still = data, stillTicks = cur.stillTicks + 1)
            cur.stillTicks == 0 -> open = cur.copy(moved = true)
            else -> depart(cur, cur.still ?: data)
        }
    }

    /** The warm-up is cut off: the trip starts now, with the values of the last standing tick [base]. */
    private suspend fun depart(cur: Open, base: DiParsData) {
        val ts = now()
        val hybrid = kind() == VehicleKind.HYBRID
        val moved = cur.copy(
            startTs = ts,
            startSoc = base.soc ?: cur.startSoc,
            startTotalElec = base.totalElecConsumption ?: cur.startTotalElec,
            startExteriorTemp = base.exteriorTemp ?: cur.startExteriorTemp,
            startFuelTotal = if (hybrid) base.fuelTotalL ?: cur.startFuelTotal else cur.startFuelTotal,
            startMileageHev = if (hybrid) base.mileageHevKm?.toDouble() ?: cur.startMileageHev else cur.startMileageHev,
            moved = true,
            still = null,
        )
        open = moved
        lastStateDao.openTrip(
            startTs = ts,
            startSoc = moved.startSoc,
            startMileage = moved.startMileage,
            startTotalElec = moved.startTotalElec,
            startExteriorTemp = moved.startExteriorTemp,
            now = ts,
        )
        if (hybrid) lastStateDao.setOpenTripCounters(moved.startFuelTotal, moved.startMileageHev)
        val warmUpS = (ts - cur.startTs) / MS_PER_S
        Log.i(TAG, "trip start moved to the first odometer movement: warm-up ${warmUpS}s excluded " +
            "(soc ${cur.startSoc}->${moved.startSoc})")
        com.bydmate.app.diagnostics.TripDebugLog.event("TRIP", "departure: warm-up ${warmUpS}s excluded from the trip")
    }

    /** The closed trip: to the trips table when active, to the shadow sink when passive. */
    private suspend fun deliver(trip: TripEntity, active: Boolean) {
        if (active) tripDao.insert(trip) else shadowSink(trip.copy(source = TripSource.NATIVE_FALLBACK))
    }

    private suspend fun close(open: Open, end: DiParsData) {
        val hybrid = kind() == VehicleKind.HYBRID
        val kwh = TripMath.kwh(open.startTotalElec, end.totalElecConsumption, open.startSoc, end.soc, batteryCapacityKwh(), hybrid)
        val distance = TripMath.plausibleDistance(open.startMileage, end.mileage)
        val (odoStart, odoEnd) = TripMath.odometerPair(open.startMileage, end.mileage)
        // The end edge is one ON tick, whose time is also the trip's endTs: the confirming tick
        // comes OFF_AFTER_MS later, with the car off and its counters no longer the trip's.
        val edgeMs = endEdge.tickMs?.takeIf { it >= open.startTs }
        val fuel = TripMath.fuelColumns(
            open.startFuelTotal, edgeMs?.let { endEdge.fuelTotalL },
            open.startMileageHev, edgeMs?.let { endEdge.mileageHevKm }, distance, hybrid,
        )
        val active = isActive()
        deliver(
            TripEntity(
                startTs = open.startTs,
                // The hybrid edge drops OFF_AFTER_MS after the car stopped; the trip ends at the
                // last tick the car was on, not at the moment the silence was confirmed.
                endTs = if (hybrid) {
                    edgeMs ?: hybridEdge.lastOnMs.takeIf { it >= open.startTs } ?: now()  // resumed, no live tick
                } else {
                    now()
                },
                distanceKm = distance,
                kwhConsumed = kwh,
                kwhPer100km = TripMath.per100(kwh, distance),
                socStart = open.startSoc,
                socEnd = end.soc,
                exteriorTemp = open.startExteriorTemp,
                exteriorTempEnd = end.exteriorTemp,
                odometerStartKm = odoStart,
                odometerEndKm = odoEnd,
                source = TripSource.NATIVE_POLLING,
                fuelLiters = fuel.fuelLiters,
                hevKm = fuel.hevKm,
                fuelTotalStart = fuel.fuelTotalStart,
                fuelTotalEnd = fuel.fuelTotalEnd,
                mileageHevStart = fuel.mileageHevStart,
                mileageHevEnd = fuel.mileageHevEnd,
            ),
            active,
        )
        if (hybrid) {
            Log.i(TAG, "trip close fuel: l=${fuel.fuelLiters} hev=${fuel.hevKm} " +
                "total=${fuel.fuelTotalStart}/${fuel.fuelTotalEnd} hevOdo=${fuel.mileageHevStart}/${fuel.mileageHevEnd}")
        }
        Log.i(TAG, "trip close: km=$distance kwh=$kwh " +
            "ext_temp=${open.startExteriorTemp}/${end.exteriorTemp} odo=$odoStart/$odoEnd" +
            if (active) "" else " -> shadow (passive)")
        this.open = null
        endEdge.clear()
        lastStateDao.clearOpenTrip()
    }

    /** A trip the old process left open a moment ago goes on in this one. */
    private fun resume(state: LastStateEntity, startTs: Long) {
        val startKm = state.tripStartMileage
        val moved = startKm != null && state.mileage != null && state.mileage - startKm >= DEPARTURE_KM
        open = Open(
            startTs, state.tripStartSoc, state.tripStartMileage,
            state.tripStartTotalElec, state.tripStartExteriorTemp,
            state.tripStartFuelTotal, state.tripStartMileageHev,
            moved = moved,
            // Not moved yet: the car stood on before the restart, a later movement is a departure.
            stillTicks = if (moved) 0 else 1,
        )
        // The new edge starts off with an empty odometer window; without the seed the
        // first silent tick would close the trip that was running a moment ago.
        if (kind() == VehicleKind.HYBRID) resumeHybrid(hybridEdge, endEdge, state, now())
        Log.i(TAG, "trip resume (cold start): ts=$startTs moved=$moved ext_temp=${state.tripStartExteriorTemp}")
    }

    /** Call once before subscribing to the loop. */
    suspend fun reconcileColdStart() {
        val state = lastStateDao.getCurrent() ?: return
        if (state.openTripId == null || state.tripStartTs == null) return
        val gap = now() - state.ts
        val active = isActive()
        val staleGap = 5 * 60 * 1_000L
        logRestart(TripMath.plausibleDistance(state.tripStartMileage, state.mileage), gap, active, staleGap)
        if (gap < staleGap) {
            resume(state, state.tripStartTs)
            return
        }
        val hybrid = kind() == VehicleKind.HYBRID
        val kwh = TripMath.kwh(state.tripStartTotalElec, state.totalElec, state.tripStartSoc, state.soc, batteryCapacityKwh(), hybrid)
        val distance = TripMath.plausibleDistance(state.tripStartMileage, state.mileage)
        val (odoStart, odoEnd) = TripMath.odometerPair(state.tripStartMileage, state.mileage)
        // DM-i: the end edge is the last ON tick TripRecorder kept in last_state. One older than
        // the trip's start belongs to another trip: no end edge then, so no litres, never a guess.
        val lastOn = lastOnOfOpenTrip(state, hybrid)
        val fuel = TripMath.fuelColumns(
            state.tripStartFuelTotal, lastOn?.onFuelTotal, state.tripStartMileageHev, lastOn?.onMileageHev, distance, hybrid,
        )
        deliver(
            TripEntity(
                startTs = state.tripStartTs,
                endTs = lastOn?.onTs ?: state.ts,
                distanceKm = distance,
                kwhConsumed = kwh,
                kwhPer100km = TripMath.per100(kwh, distance),
                socStart = state.tripStartSoc,
                socEnd = state.soc,
                exteriorTemp = state.tripStartExteriorTemp,
                odometerStartKm = odoStart,
                odometerEndKm = odoEnd,
                source = TripSource.NATIVE_POLLING,
                fuelLiters = fuel.fuelLiters,
                hevKm = fuel.hevKm,
                fuelTotalStart = fuel.fuelTotalStart,
                fuelTotalEnd = fuel.fuelTotalEnd,
                mileageHevStart = fuel.mileageHevStart,
                mileageHevEnd = fuel.mileageHevEnd,
            ),
            active,
        )
        if (hybrid) {
            Log.i(TAG, "trip close fuel (cold start): onTs=${state.onTs} used=${lastOn != null} " +
                "l=${fuel.fuelLiters} hev=${fuel.hevKm} total=${fuel.fuelTotalStart}/${fuel.fuelTotalEnd}")
        }
        Log.i(TAG, "trip close (cold start): km=$distance " +
            "ext_temp=${state.tripStartExteriorTemp} odo=$odoStart/$odoEnd" + if (active) "" else " -> shadow (passive)")
        lastStateDao.clearOpenTrip()
    }

    private companion object {
        const val TAG = "TripRecorder"

        /** The odometer moved: one step of a 0.1 km odometer (half of it, for float noise). */
        const val DEPARTURE_KM = 0.05
    }
}

/** DM-i: [state] when its last ON tick belongs to the open trip; null on another car or for an older tick. */
private fun lastOnOfOpenTrip(state: LastStateEntity, hybrid: Boolean): LastStateEntity? =
    state.takeIf { hybrid && it.onTs != null && it.tripStartTs != null && it.onTs >= it.tripStartTs }


/**
 * DM-i resume after a process restart. The trip was running when the old process died: the
 * ignition edge starts on, so the OFF silence counts from [nowMs] instead of closing the trip on
 * the first tick. The end edge the old process saved comes back too: silent ticks alone then
 * close the trip at that tick with its litres instead of with none.
 */
private fun resumeHybrid(edge: HybridIgnitionEdge, endEdge: ReadableOnTick, state: LastStateEntity, nowMs: Long) {
    edge.seedOn(nowMs)
    val saved = lastOnOfOpenTrip(state, hybrid = true) ?: return
    val onTs = saved.onTs ?: return
    endEdge.seed(onTs, saved.onFuelTotal, saved.onMileageHev, saved.onMileage)
}

/**
 * One line in the trip log for every restart that finds a trip open (an app update in the middle
 * of a drive, #44): whether it goes on or is closed, after how long, how far. Passive: the trip is
 * the shadow that waits for the energydata import.
 */
private fun logRestart(km: Double?, gap: Long, active: Boolean, staleGap: Long) {
    val outcome = if (gap < staleGap) "continued" else "closed (gap over ${staleGap / MS_PER_MIN} min)"
    val mode = if (active) "" else " as shadow (passive)"
    com.bydmate.app.diagnostics.TripDebugLog.event(
        "TRIP",
        "open trip at restart: gap=${gap / MS_PER_S}s km=${km ?: "-"} -> $outcome$mode",
    )
}

private const val MS_PER_S = 1_000L
private const val MS_PER_MIN = 60_000L
