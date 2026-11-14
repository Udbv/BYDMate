package com.bydmate.app.hma

import org.junit.Assert.assertEquals
import org.junit.Test

/** When the driver hears about the auto high beam, and how rarely. */
class HmaWarningTest {

    private val drivingAtNight = HmaReading(hmaStateHal = 2, gray = 0, lightLevel = 1, speedKph = 60)

    // --- rule ---

    @Test fun `a blocked camera and a fault are reasons while moving`() {
        assertEquals(setOf(HmaReason.CAMERA_BLOCKED), HmaWarningRule.reasons(drivingAtNight.copy(hmaStateHal = 5)))
        assertEquals(setOf(HmaReason.FAULT), HmaWarningRule.reasons(drivingAtNight.copy(hmaStateHal = 4)))
    }

    @Test fun `nothing is a reason while standing`() {
        val parked = drivingAtNight.copy(hmaStateHal = 5, gray = 1, rainError = 1, speedKph = 0)
        assertEquals(emptySet<HmaReason>(), HmaWarningRule.reasons(parked))
        assertEquals(emptySet<HmaReason>(), HmaWarningRule.reasons(parked.copy(speedKph = null)))
    }

    @Test fun `gray is a reason only at night, moving, with the function on`() {
        val gray = drivingAtNight.copy(gray = 1)
        assertEquals(setOf(HmaReason.SUPPRESSED), HmaWarningRule.reasons(gray))
        assertEquals(emptySet<HmaReason>(), HmaWarningRule.reasons(gray.copy(lightLevel = 5)))   // daylight
        assertEquals(emptySet<HmaReason>(), HmaWarningRule.reasons(gray.copy(lightLevel = null)))
        assertEquals(emptySet<HmaReason>(), HmaWarningRule.reasons(gray.copy(hmaStateHal = 0)))  // switched off
        assertEquals(emptySet<HmaReason>(), HmaWarningRule.reasons(gray.copy(hmaStateHal = null)))
    }

    @Test fun `a light, rain or mist sensor error is a reason`() {
        assertEquals(setOf(HmaReason.SENSOR_ERROR), HmaWarningRule.reasons(drivingAtNight.copy(illuminanceError = 1)))
        assertEquals(setOf(HmaReason.SENSOR_ERROR), HmaWarningRule.reasons(drivingAtNight.copy(mistError = 1)))
    }

    @Test fun `a healthy drive has no reason`() {
        assertEquals(emptySet<HmaReason>(), HmaWarningRule.reasons(drivingAtNight))
        assertEquals(emptySet<HmaReason>(), HmaWarningRule.reasons(drivingAtNight.copy(hmaStateHal = 3, highBeam = 1)))
    }

    // --- gate ---

    private val blocked = setOf(HmaReason.CAMERA_BLOCKED)

    @Test fun `a reason must hold for the hold time before it is shown`() {
        val gate = HmaWarningGate(holdMs = 10_000, gapMs = 5_000)
        assertEquals(emptySet<HmaReason>(), gate.update(blocked, 0))
        assertEquals(emptySet<HmaReason>(), gate.update(blocked, 9_000))
        assertEquals(blocked, gate.update(blocked, 10_000))
    }

    @Test fun `a flicker shorter than the hold time is never shown`() {
        val gate = HmaWarningGate(holdMs = 10_000, gapMs = 5_000)
        gate.update(blocked, 0)
        gate.update(emptySet(), 6_000)                  // gone longer than the gap: count restarts
        assertEquals(emptySet<HmaReason>(), gate.update(blocked, 12_000))
        assertEquals(emptySet<HmaReason>(), gate.update(blocked, 21_000))
        assertEquals(blocked, gate.update(blocked, 22_000))
    }

    @Test fun `a short dropout inside the gap keeps the count running`() {
        val gate = HmaWarningGate(holdMs = 10_000, gapMs = 5_000)
        gate.update(blocked, 0)
        gate.update(emptySet(), 3_000)
        gate.update(blocked, 5_000)
        assertEquals(blocked, gate.update(blocked, 10_000))
    }

    @Test fun `each reason is shown once per trip, whatever happens after`() {
        val gate = HmaWarningGate(holdMs = 1_000, gapMs = 500)
        gate.update(blocked, 0)
        assertEquals(blocked, gate.update(blocked, 1_000))
        assertEquals(emptySet<HmaReason>(), gate.update(blocked, 60_000))
        gate.update(emptySet(), 120_000)
        gate.update(blocked, 180_000)
        assertEquals(emptySet<HmaReason>(), gate.update(blocked, 190_000))
        assertEquals(blocked, gate.shown())
    }

    @Test fun `different reasons are counted and shown independently`() {
        val gate = HmaWarningGate(holdMs = 1_000, gapMs = 500)
        gate.update(blocked, 0)
        assertEquals(blocked, gate.update(blocked, 1_000))
        val both = setOf(HmaReason.CAMERA_BLOCKED, HmaReason.SENSOR_ERROR)
        gate.update(both, 1_200)
        assertEquals(setOf(HmaReason.SENSOR_ERROR), gate.update(both, 2_200))
    }

    @Test fun `a new trip may show a reason again`() {
        val gate = HmaWarningGate(holdMs = 1_000, gapMs = 500)
        gate.update(blocked, 0)
        gate.update(blocked, 1_000)
        gate.newTrip()
        assertEquals(emptySet<HmaReason>(), gate.update(blocked, 2_000))
        assertEquals(blocked, gate.update(blocked, 3_000))
    }
}
