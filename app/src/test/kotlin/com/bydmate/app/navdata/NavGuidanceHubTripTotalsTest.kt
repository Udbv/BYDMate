package com.bydmate.app.navdata

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The trip totals (ETA, distance to the destination) live and die with their route. */
class NavGuidanceHubTripTotalsTest {

    @Before fun reset() = NavGuidanceHub.reset()

    private fun data(eta: Int = 0, total: Int = 0, dist: Int = 0) =
        NavGuidance(distanceMeters = dist, etaSeconds = eta, totalDistMeters = total)

    @Test fun `a new route does not inherit the last route's total after silence`() {
        NavGuidanceHub.update(data(eta = 60, total = 300), NavGuidanceHub.Source.A11Y, nowMs = 1_000)
        val ended = 2_000 + NavGuidanceHub.ACTIVE_TIMEOUT_MS
        NavGuidanceHub.snapshot(nowMs = ended)
        NavGuidanceHub.update(data(dist = 400), NavGuidanceHub.Source.A11Y, nowMs = ended + 1_000)
        val s = NavGuidanceHub.snapshot(nowMs = ended + 1_000)
        assertEquals(0, s.totalDistMeters)
        assertEquals(0, s.etaSeconds)
        assertEquals(0L, s.totalDistMs)
    }

    @Test fun `a new notification route does not inherit the last route's total after grace`() {
        NavGuidanceHub.updateFromNotification(
            NavGuidanceHub.RichUpdate(etaSeconds = 60, totalDistMeters = 300), nowMs = 1_000,
        )
        val ended = 1_000 + NavGuidanceHub.ACTIVE_TIMEOUT_MS
        NavGuidanceHub.deactivateFromNotificationGrace(nowMs = ended)
        NavGuidanceHub.updateFromNotification(NavGuidanceHub.RichUpdate(distanceMeters = 400), nowMs = ended + 1_000)
        val s = NavGuidanceHub.snapshot(nowMs = ended + 1_000)
        assertEquals(0, s.totalDistMeters)
        assertEquals(0, s.etaSeconds)
    }

    @Test fun `the total's read time follows reads that carry it`() {
        NavGuidanceHub.update(data(total = 5_000), NavGuidanceHub.Source.A11Y, nowMs = 1_000)
        NavGuidanceHub.update(data(dist = 200), NavGuidanceHub.Source.A11Y, nowMs = 5_000)
        assertEquals(1_000L, NavGuidanceHub.snapshot(nowMs = 5_000).totalDistMs)
        NavGuidanceHub.updateFromNotification(NavGuidanceHub.RichUpdate(totalDistMeters = 4_800), nowMs = 20_000)
        val s = NavGuidanceHub.snapshot(nowMs = 20_000)
        assertEquals(4_800, s.totalDistMeters)
        assertEquals(20_000L, s.totalDistMs)
    }
}
