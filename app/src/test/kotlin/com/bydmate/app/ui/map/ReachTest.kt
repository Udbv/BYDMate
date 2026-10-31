package com.bydmate.app.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReachTest {

    @Test fun `scenarios come from the energy left and the history's best and worst`() {
        val s = ReachScenarios.of(normalKm = 300.0, remainKwh = 60.0, bounds = 15.0..30.0)
        assertEquals(200.0, s.heavyKm, 1e-9)
        assertEquals(300.0, s.normalKm, 1e-9)
        assertEquals(400.0, s.ecoKm, 1e-9)
    }

    @Test fun `without history the outer scenarios are a fixed share of the estimate`() {
        val s = ReachScenarios.of(normalKm = 300.0, remainKwh = null, bounds = null)
        assertEquals(240.0, s.heavyKm, 1e-9)
        assertEquals(345.0, s.ecoKm, 1e-9)
    }

    @Test fun `the outer scenarios never cross the estimate`() {
        val s = ReachScenarios.of(normalKm = 300.0, remainKwh = 30.0, bounds = 5.0..8.0)
        assertTrue(s.heavyKm <= s.normalKm)
        assertTrue(s.ecoKm >= s.normalKm)
    }

    @Test fun `a route is cut where each scenario's charge runs out`() {
        // A straight line north, 0.1 degree of latitude per point: about 11.1 km each, 100 km in all.
        val route = (0..9).map { LatLon(50.0 + it * 0.1, 30.0) }
        val s = ReachScenarios(heavyKm = 20.0, normalKm = 50.0, ecoKm = 80.0)
        val pieces = RouteBands.split(route, s)
        assertEquals(
            listOf(ReachScenarios.Band.SURE, ReachScenarios.Band.LIKELY, ReachScenarios.Band.ECO_ONLY, ReachScenarios.Band.OUT),
            pieces.map { it.band },
        )
        // Pieces join end to start and keep the whole route.
        for (i in 1 until pieces.size) assertEquals(pieces[i - 1].points.last(), pieces[i].points.first())
        val limits = RouteBands.limitPoints(route, s)
        assertEquals(3, limits.size)
        val heavyEnd = limits.getValue(ReachScenarios.Band.SURE)
        assertEquals(20.0, haversineKm(50.0, 30.0, heavyEnd.lat, heavyEnd.lon), 0.05)
    }

    @Test fun `a short route stays in one band`() {
        val route = listOf(LatLon(50.0, 30.0), LatLon(50.05, 30.0))
        val pieces = RouteBands.split(route, ReachScenarios(100.0, 200.0, 300.0))
        assertEquals(1, pieces.size)
        assertEquals(ReachScenarios.Band.SURE, pieces.single().band)
        assertTrue(RouteBands.limitPoints(route, ReachScenarios(100.0, 200.0, 300.0)).isEmpty())
    }

    @Test fun `the OSRM answer gives length, time and shape`() {
        val json = """{"code":"Ok","routes":[{"distance":12345.6,"duration":900,
            "geometry":{"coordinates":[[30.5,50.4],[30.6,50.5]],"type":"LineString"}}]}"""
        val plan = RouteClient.parse(json)
        assertEquals(12.3456, plan.distanceKm, 1e-9)
        assertEquals(15.0, plan.durationMin, 1e-9)
        assertEquals(LatLon(50.4, 30.5), plan.points.first())
    }

    @Test fun `search results parse name, address and position`() {
        val json = """[{"lat":"50.45","lon":"30.52","name":"Майдан","display_name":"Майдан Незалежності, Київ"},
            {"lat":"x","lon":"30"},{"lat":"49.8","lon":"24.0","name":"","display_name":"Львів, Україна"}]"""
        val places = PlaceSearch.parse(json)
        assertEquals(2, places.size)
        assertEquals("Львів", places[1].name)
    }
}
