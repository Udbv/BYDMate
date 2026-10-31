package com.bydmate.app.ui.map

import org.junit.Assert.assertEquals
import org.junit.Test

class ReachTest {

    @Test fun `the reach is the road range over the detour, the safe ring keeps a reserve`() {
        val r = Reach(250.0)
        assertEquals(200.0, r.reachKm, 1e-9)
        assertEquals(170.0, r.safeKm, 1e-9)
        assertEquals(125.0, r.roadKm(100.0), 1e-9)
    }

    @Test fun `verdicts follow the two rings`() {
        val r = Reach(250.0)
        assertEquals(Reach.Verdict.EASY, r.verdict(150.0))
        assertEquals(Reach.Verdict.TIGHT, r.verdict(190.0))
        assertEquals(Reach.Verdict.OUT, r.verdict(210.0))
    }

    @Test fun `haversine gives the straight distance`() {
        // One degree of latitude is about 111.2 km.
        assertEquals(111.2, haversineKm(50.0, 30.0, 51.0, 30.0), 0.2)
    }

    @Test fun `search results parse name, address and position`() {
        val json = """[{"lat":"50.45","lon":"30.52","name":"Майдан","display_name":"Майдан Незалежності, Київ"},
            {"lat":"x","lon":"30"},{"lat":"49.8","lon":"24.0","name":"","display_name":"Львів, Україна"}]"""
        val places = PlaceSearch.parse(json)
        assertEquals(2, places.size)
        assertEquals("Майдан", places[0].name)
        assertEquals(50.45, places[0].lat, 1e-9)
        assertEquals("Львів", places[1].name)
    }
}
