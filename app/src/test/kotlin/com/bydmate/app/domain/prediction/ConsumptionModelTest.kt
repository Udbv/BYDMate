package com.bydmate.app.domain.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsumptionModelTest {

    private fun trip(ts: Long, per100: Double, temp: Double?, weather: TripWeather?, speed: Double?) =
        sample(ts, per100, TripConditions(temp, weather, speed), km = 20.0)

    private fun sample(ts: Long, per100: Double, c: TripConditions, km: Double) = TripSample(ts, km, per100 * km / 100.0, c)

    @Test
    fun `wet when the wipers ran in a third of the minutes, snow at or below one degree`() {
        assertNull(tripWeather(0, 0, 5.0))
        assertEquals(TripWeather.DRY, tripWeather(1, 20, 5.0))
        assertEquals(TripWeather.RAIN, tripWeather(8, 20, 5.0))
        assertEquals(TripWeather.SNOW, tripWeather(8, 20, 1.0))
        assertEquals(TripWeather.RAIN, tripWeather(8, 20, null))
    }

    @Test
    fun `no history, no forecast`() {
        assertNull(ConsumptionModel(emptyList()).predict(TripConditions(5.0, TripWeather.DRY, 50.0)))
    }

    @Test
    fun `unknown conditions get the distance weighted average of all drives`() {
        val model = ConsumptionModel(
            listOf(sample(1, 10.0, TripConditions(20.0, null, 50.0), km = 30.0), sample(2, 20.0, TripConditions(-5.0, null, 50.0), km = 10.0))
        )
        val p = model.predict(TripConditions(null, null, null))!!
        assertEquals(PredictionLevel.ALL, p.level)
        assertEquals(12.5, p.per100, 1e-9)
    }

    @Test
    fun `a cold band pulls the forecast up, shrunk toward the average while small`() {
        val warm = (1..10L).map { trip(it, 15.0, 22.0, TripWeather.DRY, 50.0) }
        val cold = (11..12L).map { trip(it, 25.0, -5.0, TripWeather.DRY, 50.0) }
        val model = ConsumptionModel(warm + cold)
        val p = model.predict(TripConditions(-4.0, TripWeather.DRY, 50.0))!!
        assertEquals(PredictionLevel.FULL, p.level)
        assertEquals(2, p.trips)
        // Above the overall 16.7 but under the band's own 25: two drives do not get the last word.
        assertTrue(p.per100 > 17.0 && p.per100 < 25.0)
    }

    @Test
    fun `missing weather stops at temperature and speed`() {
        val model = ConsumptionModel((1..6L).map { trip(it, 18.0, 5.0, TripWeather.RAIN, 30.0) })
        val p = model.predict(TripConditions(5.0, null, 30.0))!!
        assertEquals(PredictionLevel.TEMP_SPEED, p.level)
        assertEquals(18.0, p.per100, 1e-9)
    }

    @Test
    fun `short and absurd drives are not learnt from`() {
        val model = ConsumptionModel(listOf(trip(1, 15.0, 10.0, null, 50.0), sample(2, 40.0, TripConditions(10.0, null, 50.0), km = 1.0), trip(3, 90.0, 10.0, null, 50.0)))
        assertEquals(1, model.size)
    }

    @Test
    fun `walk-forward accuracy beats the plain average when temperature matters`() {
        val history = (1..40L).map { i ->
            val cold = i % 2 == 0L
            trip(i, if (cold) 24.0 else 14.0, if (cold) -8.0 else 18.0, TripWeather.DRY, 50.0)
        }
        val acc = evaluate(history)
        assertNotNull(acc)
        acc!!
        assertEquals(30, acc.trips)
        assertTrue(acc.maePer100 < acc.baselineMaePer100)
    }

    @Test
    fun `too few drives give no accuracy`() {
        assertNull(evaluate((1..6L).map { trip(it, 15.0, 10.0, null, 50.0) }))
    }

    @Test
    fun `a climb costs its potential energy, a descent gives part back, noise costs nothing`() {
        assertEquals(0.0, climbKwh(null), 1e-9)
        assertEquals(0.0, climbKwh(8.0), 1e-9)
        val up = climbKwh(100.0)
        assertEquals(2750 * 9.81 * 100 / 3.6e6 / 0.88, up, 1e-9)
        val down = climbKwh(-100.0)
        assertTrue(down < 0 && -down < up)
    }

    @Test
    fun `the model learns the flat rate and adds each drive's own height`() {
        // Ten drives up a 100 m hill and ten down it: on the flat they all use 15 kWh/100.
        val km = 20.0
        val history = (1..20L).map { i ->
            val climb = if (i % 2 == 0L) 100.0 else -100.0
            TripSample(i, km, 15.0 * km / 100 + climbKwh(climb), TripConditions(10.0, TripWeather.DRY, 50.0, climb))
        }
        val p = ConsumptionModel(history).predict(TripConditions(10.0, TripWeather.DRY, 50.0, 100.0))!!
        assertEquals(15.0, p.per100, 1e-9)
        assertEquals(3.0 + climbKwh(100.0), p.kwhFor(km, 100.0), 1e-9)
    }

    @Test
    fun `a drive that warmed up on the way teaches each band its own part`() {
        // Half of every drive at -8 °C using 24/100, half at +18 °C using 14/100.
        val history = (1..10L).map { i ->
            val cold = Segment(10.0, 2.4, TripConditions(-8.0, TripWeather.DRY, 50.0))
            val warm = Segment(10.0, 1.4, TripConditions(18.0, TripWeather.DRY, 50.0))
            TripSample(i, 20.0, 3.8, TripConditions(5.0, TripWeather.DRY, 50.0), listOf(cold, warm))
        }
        val model = ConsumptionModel(history)
        assertTrue(model.predict(TripConditions(-8.0, TripWeather.DRY, 50.0))!!.per100 > 23.0)
        assertTrue(model.predict(TripConditions(18.0, TripWeather.DRY, 50.0))!!.per100 < 15.0)
        val trip = model.predictTrip(listOf(Segment(30.0, 0.0, TripConditions(-8.0, TripWeather.DRY, 50.0))))!!
        assertTrue(trip.kwh > 6.9)
    }
}
