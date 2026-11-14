package com.bydmate.app.data.trips

import com.bydmate.app.data.local.dao.LastStateDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.dao.TripMinuteDao
import com.bydmate.app.data.local.dao.TripPointDao
import com.bydmate.app.data.local.entity.LastStateEntity
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.local.entity.TripMinuteEntity
import com.bydmate.app.data.repository.OdometerMarks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Missing-trip detector on the 2026-11-14 morning (docs/logs/INDEX.md 3.12): a warm-up process,
 * two drives in two processes and a third drive, none of them in the trip list.
 */
class TripAnomalyDetectorTest {

    private class MemoryCheckpoint(var value: Pair<Long, Double?>? = null) : AnomalyCheckpoint {
        override fun load() = value
        override fun save(ts: Long, km: Double?) { value = ts to km }
    }

    private val min = 60_000L
    private val day0 = 1_794_000_000_000L
    private fun at(h: Int, m: Int, s: Int = 0) = day0 + h * 3_600_000L + m * min + s * 1000L

    private var marks: List<OdometerMarks.Mark> = emptyList()
    private var stored: List<TripEntity> = emptyList()
    private var minutes: List<TripMinuteEntity> = emptyList()
    private var shadows: List<TripEntity> = emptyList()
    private var lastState: LastStateEntity? = null
    private var nowMs = at(12, 1)
    private val inserted = mutableListOf<TripEntity>()
    private val checkpoint = MemoryCheckpoint()
    private val notes = RecoveredTripNotes()

    private val tripDao = mockk<TripDao>(relaxed = true).also { dao ->
        coEvery { dao.getStartingBetween(any(), any()) } answers { stored }
        coEvery { dao.insert(any()) } answers { inserted += firstArg<TripEntity>(); 100L + inserted.size }
    }
    private val tripPointDao = mockk<TripPointDao>(relaxed = true)
    private val minuteDao = mockk<TripMinuteDao>().also { dao ->
        coEvery { dao.getInRange(any(), any()) } answers {
            val from = firstArg<Long>()
            val to = secondArg<Long>()
            minutes.filter { it.minuteTs in from..to }
        }
    }
    private val lastStateDao = mockk<LastStateDao>().also { coEvery { it.getCurrent() } answers { lastState } }

    private fun detector() = TripAnomalyDetector(
        marks = { marks }, tripDao = tripDao, tripPointDao = tripPointDao, tripMinuteDao = minuteDao,
        lastStateDao = lastStateDao, shadows = { shadows }, checkpoint = checkpoint, notes = notes,
        capacityKwh = { 72.9 }, now = { nowMs },
    )

    @Suppress("LongParameterList")
    private fun mark(firstTs: Long, firstKm: Double, lastTs: Long, lastKm: Double, lastChangeTs: Long = lastTs) =
        OdometerMarks.Mark(processStartTs = firstTs, firstTs = firstTs, firstKm = firstKm, lastTs = lastTs, lastKm = lastKm, lastChangeTs = lastChangeTs)

    /** One driving minute per minute of [fromTs]..[toTs), SoC falling linearly [socFrom] -> [socTo], 0.33 kWh each. */
    private fun drive(fromTs: Long, toTs: Long, socFrom: Int, socTo: Int): List<TripMinuteEntity> {
        val n = ((toTs - fromTs) / min).toInt()
        return (0 until n).map { i ->
            TripMinuteEntity(
                minuteTs = fromTs + i * min, samples = 20, motorsKwh = 0.30, frontKwh = null, rearKwh = null,
                regenKwh = -0.02, climateKwh = 0.04, otherKwh = 0.01, peakKw = 40.0, minKw = -10.0,
                avgSpeed = 45.0, maxSpeed = 70, soc = socFrom - (socFrom - socTo) * i / (n - 1).coerceAtLeast(1),
                extTemp = 1.0,
            )
        }
    }

    private fun morning() {
        marks = listOf(
            // pid 16494: the warm-up, plugged in, odometer still.
            mark(at(8, 58, 40), 35193.4, at(9, 40, 50), 35193.4, lastChangeTs = at(8, 58, 40)),
            // pid 1634: drive 1.
            mark(at(9, 41, 3), 35193.4, at(10, 19, 57), 35222.6, lastChangeTs = at(10, 19, 50)),
            // pid 31547: drive 2.
            mark(at(10, 21, 10), 35222.6, at(10, 37, 18), 35249.6, lastChangeTs = at(10, 37, 10)),
            // pid 27017: drive 3, still going at 12:00.
            mark(at(11, 0, 8), 35249.6, at(12, 0, 11), 35345.9, lastChangeTs = at(12, 0, 5)),
        )
        minutes = drive(at(9, 50), at(10, 20), 99, 88) + drive(at(10, 22), at(10, 37), 87, 77) +
            drive(at(11, 1), at(12, 0), 77, 42)
        // The last trip in the list: the night before.
        stored = listOf(
            TripEntity(id = 1, startTs = at(-2, -3), endTs = at(-1, -46), distanceKm = 14.7,
                odometerStartKm = 35178.7, odometerEndKm = 35193.4, source = TripSource.NATIVE_POLLING),
        )
    }

    @Test
    fun `the first check recovers the drives lost before the update`() = runTest {
        morning()
        val recovered = detector().check("service_start")

        assertEquals(3, recovered)
        val d1 = inserted[0]
        assertEquals(TripSource.RECOVERED, d1.source)
        assertEquals(at(9, 50), d1.startTs)                     // first driving minute, not the warm-up
        assertEquals(at(10, 19, 50), d1.endTs)                  // the last odometer change
        assertEquals(29.2, d1.distanceKm!!, 1e-6)
        assertEquals(35193.4, d1.odometerStartKm!!, 1e-6)
        assertEquals(35222.6, d1.odometerEndKm!!, 1e-6)
        assertEquals(99, d1.socStart)
        assertEquals(88, d1.socEnd)
        assertEquals(30 * 0.33, d1.kwhConsumed!!, 1e-6)          // per-minute energy, 30 minutes
        assertEquals(1, d1.exteriorTemp)
        assertEquals(27.0, inserted[1].distanceKm!!, 1e-6)
        assertEquals(87, inserted[1].socStart)
        assertEquals(77, inserted[1].socEnd)
        assertEquals(96.3, inserted[2].distanceKm!!, 1e-6)
        // GPS points attach by time, the report gets its line.
        coVerify(exactly = 1) { tripPointDao.attachToTrip(101L, at(9, 50), at(10, 19, 50)) }
        assertEquals(3, notes.all().size)
        assertEquals(35345.9, checkpoint.value!!.second!!, 1e-6)
    }

    @Test
    fun `the next check starts where the last one stopped`() = runTest {
        morning()
        detector().check("service_start")
        inserted.clear()
        nowMs += 5 * min
        assertEquals(0, detector().check("service_start"))
        assertTrue(inserted.isEmpty())
    }

    @Test
    fun `movement covered by a stored trip, a shadow or the open trip is no anomaly`() = runTest {
        morning()
        stored = stored + TripEntity(
            id = 2, startTs = at(9, 50), endTs = at(10, 20), distanceKm = 29.2,
            odometerStartKm = 35193.4, odometerEndKm = 35222.6, source = TripSource.NATIVE_POLLING,
        )
        // Drive 2 is a shadow trip still waiting for the import; BYD's record of it has no odometer.
        shadows = listOf(
            TripEntity(startTs = at(10, 22), endTs = at(10, 37), distanceKm = 27.0,
                odometerStartKm = 35222.6, odometerEndKm = 35249.6, source = TripSource.NATIVE_FALLBACK),
        )
        // Drive 3 is under way.
        lastState = LastStateEntity(
            ts = nowMs, mileage = 35345.9, openTripId = at(11, 1), tripStartTs = at(11, 1), tripStartMileage = 35249.6,
        )
        assertEquals(0, detector().check("drive_end"))
        assertTrue(inserted.isEmpty())
    }

    @Test
    fun `a trip without odometer covers by time`() = runTest {
        morning()
        marks = marks.take(2)
        stored = stored + TripEntity(id = 3, startTs = at(9, 46), endTs = at(10, 21), distanceKm = 29.0, source = TripSource.ENERGYDATA)
        assertEquals(0, detector().check("service_start"))
    }

    @Test
    fun `only the uncovered part of a drive is recovered`() = runTest {
        morning()
        marks = marks.take(2)
        // The app recorded the first 16.6 km, then lost the rest (a process restart it never closed).
        stored = stored + TripEntity(
            id = 4, startTs = at(9, 50), endTs = at(10, 4), distanceKm = 16.6,
            odometerStartKm = 35193.4, odometerEndKm = 35210.0, source = TripSource.NATIVE_POLLING,
        )
        assertEquals(1, detector().check("service_start"))
        val t = inserted.single()
        assertEquals(12.6, t.distanceKm!!, 1e-6)
        assertNull(t.odometerStartKm)
        assertTrue(t.startTs >= at(10, 4))
    }

    @Test
    fun `one km or less of uncovered odometer is no anomaly, more is`() = runTest {
        checkpoint.value = at(9, 0) to 35193.4
        marks = listOf(mark(at(9, 41), 35193.4, at(9, 50), 35194.4, lastChangeTs = at(9, 48)))
        assertEquals(0, detector().check("service_start"))

        checkpoint.value = at(9, 0) to 35193.4
        marks = listOf(mark(at(9, 41), 35193.4, at(9, 50), 35194.6, lastChangeTs = at(9, 48)))
        assertEquals(1, detector().check("service_start"))
        assertEquals(1.2, inserted.single().distanceKm!!, 1e-6)
    }

    @Test
    fun `a drive while the app was not running is recovered from the gap between two processes`() = runTest {
        checkpoint.value = at(8, 0) to 35100.0
        marks = listOf(
            mark(at(8, 0), 35100.0, at(8, 30), 35100.0),
            mark(at(9, 30), 35160.0, at(9, 45), 35160.0),
        )
        minutes = drive(at(7, 50), at(8, 30), 90, 90) + drive(at(9, 31), at(9, 45), 80, 80)
        assertEquals(1, detector().check("service_start"))
        val t = inserted.single()
        assertEquals(at(8, 30), t.startTs)
        assertEquals(at(9, 30), t.endTs)
        assertEquals(60.0, t.distanceKm!!, 1e-6)
        assertEquals(90, t.socStart)                             // the readings just around the gap
        assertEquals(80, t.socEnd)
        assertEquals(10 / 100.0 * 72.9, t.kwhConsumed!!, 1e-6)    // no minutes inside: the SoC drop
    }

    @Test
    fun `an odometer jump faster than a car can drive is a glitch, not a trip`() = runTest {
        checkpoint.value = at(8, 0) to 35100.0
        marks = listOf(
            mark(at(8, 0), 35100.0, at(8, 30), 35100.0),
            mark(at(8, 31), 35900.0, at(8, 45), 35900.0),       // 800 km in a minute
        )
        assertEquals(0, detector().check("service_start"))
    }

    @Test
    fun `a SoC drop while standing (warm-up, charging) is never a trip`() = runTest {
        checkpoint.value = at(8, 0) to 35193.4
        marks = listOf(mark(at(8, 58), 35193.4, at(9, 40), 35193.4, lastChangeTs = at(8, 58)))
        minutes = drive(at(8, 59), at(9, 40), 100, 94).map { it.copy(maxSpeed = 0, avgSpeed = 0.0) }
        assertEquals(0, detector().check("service_start"))
        assertTrue(inserted.isEmpty())
    }

    @Test
    fun `the end of a drive is the step out of DRIVE, once`() {
        val d = detector()
        assertFalse(d.isDriveEndEdge(1, 0L))
        assertFalse(d.isDriveEndEdge(2, 1_000L))
        assertTrue(d.isDriveEndEdge(1, 2_000L))
        assertFalse(d.isDriveEndEdge(0, 3_000L))
        assertFalse(d.isDriveEndEdge(2, 4_000L))
        assertFalse("debounced", d.isDriveEndEdge(1, 5_000L))
    }
}
