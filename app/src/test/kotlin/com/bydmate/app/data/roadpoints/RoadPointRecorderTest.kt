package com.bydmate.app.data.roadpoints

import android.location.Location
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.dao.RoadPointDao
import com.bydmate.app.data.local.entity.RoadPointEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The review on the map and the blacklist: a rejected place stays rejected when marked again (#22). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RoadPointRecorderTest {

    private val dao = mockk<RoadPointDao>(relaxed = true)
    private val recorder = RoadPointRecorder(ApplicationProvider.getApplicationContext(), dao, mockk(relaxed = true), mockk(relaxed = true))
        .also { it.nowMs = { NOW } }

    /** ~1 m of latitude. */
    private val m = 1.0 / 111_320.0

    private fun known(status: RoadPointStatus, lat: Double = 50.0, heading: Double? = 90.0, id: Long = 7) =
        RoadPointEntity(id, 1_000, 1_000, lat, 30.0, heading, "SPEED_BUMP", 2, status.name)

    private fun fix(lat: Double, bearing: Float? = null) = Location("gps").apply {
        time = NOW
        latitude = lat
        longitude = 30.0
        accuracy = 5f
        if (bearing != null) {
            this.bearing = bearing
            speed = 10f
        }
    }

    @Test fun `a mark at a rejected place counts the pass and keeps it rejected`() = runBlocking {
        coEvery { dao.inBox("SPEED_BUMP", any(), any(), any(), any()) } returns listOf(known(RoadPointStatus.REJECTED))
        val saved = slot<RoadPointEntity>()
        coEvery { dao.update(capture(saved)) } returns Unit

        val result = recorder.markAt(RoadPointType.SPEED_BUMP, fix(50.0 + 5 * m, bearing = 95f))

        assertEquals(MarkResult.Rejected(RoadPointType.SPEED_BUMP, 3), result)
        assertEquals(RoadPointStatus.REJECTED.name, saved.captured.status)
        assertEquals(3, saved.captured.passes)
        assertEquals(NOW, saved.captured.lastTs)
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test fun `a confirmed place stays confirmed and answers as a pass`() = runBlocking {
        coEvery { dao.inBox(any(), any(), any(), any(), any()) } returns listOf(known(RoadPointStatus.CONFIRMED))
        val saved = slot<RoadPointEntity>()
        coEvery { dao.update(capture(saved)) } returns Unit

        val result = recorder.markAt(RoadPointType.SPEED_BUMP, fix(50.0))

        assertEquals(MarkResult.Passed(RoadPointType.SPEED_BUMP, 3), result)
        assertEquals(RoadPointStatus.CONFIRMED.name, saved.captured.status)
    }

    @Test fun `the blacklist holds only its own spot and course`() = runBlocking {
        // The opposite course and a place 20 m on are other points: a new candidate is created.
        coEvery { dao.inBox(any(), any(), any(), any(), any()) } returns
            listOf(known(RoadPointStatus.REJECTED, heading = 270.0), known(RoadPointStatus.REJECTED, lat = 50.0 + 20 * m, id = 8))
        val added = slot<RoadPointEntity>()
        coEvery { dao.insert(capture(added)) } returns 9

        val result = recorder.markAt(RoadPointType.SPEED_BUMP, fix(50.0, bearing = 90f))

        assertEquals(MarkResult.Added(RoadPointType.SPEED_BUMP), result)
        assertEquals(RoadPointStatus.CANDIDATE.name, added.captured.status)
        coVerify(exactly = 0) { dao.update(any()) }
    }

    @Test fun `the nearest point wins when a rejected and a candidate are both in range`() = runBlocking {
        coEvery { dao.inBox(any(), any(), any(), any(), any()) } returns
            listOf(known(RoadPointStatus.CANDIDATE, lat = 50.0 + 12 * m, id = 1), known(RoadPointStatus.REJECTED, lat = 50.0 + 2 * m, id = 2))

        val result = recorder.markAt(RoadPointType.SPEED_BUMP, fix(50.0))

        assertTrue(result.toString(), result is MarkResult.Rejected)
        coVerify { dao.update(match { it.id == 2L }) }
    }

    @Test fun `the review sets the status by name`() = runBlocking {
        recorder.setStatus(7, RoadPointStatus.CONFIRMED)
        recorder.setStatus(8, RoadPointStatus.REJECTED)
        coVerify { dao.setStatus(7, "CONFIRMED") }
        coVerify { dao.setStatus(8, "REJECTED") }
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
