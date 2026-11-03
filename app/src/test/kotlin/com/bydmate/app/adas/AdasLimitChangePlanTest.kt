package com.bydmate.app.adas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The parked limit change test's plan (#23). */
class AdasLimitChangePlanTest {

    private val plan = AdasLimitChangePlan(RoutePoint(49.0, 25.0), 7L, 0L)

    private fun limitAt(ms: Long) = plan.framesAt(ms)
        .firstOrNull { it.topic == AdasShadowFrames.TOPIC_TRAFFIC_INFO }
        ?.fields?.substringAfter("SpdLmtSpeedValue=")?.substringBefore(' ')?.toInt()

    @Test fun `each variant opens stopped, then changes the limit every step`() {
        val v = AdasLimitChangePlan.VARIANT_MS
        assertEquals(4 * v, plan.durationMs)
        assertEquals(null, limitAt(0))
        assertEquals(AdasShadowFrames.naviStatus(AdasShadowFrames.NAVI_STOPPED), plan.framesAt(0).first())
        val g = AdasLimitChangePlan.GAP_MS
        val s = AdasLimitChangePlan.STEP_MS
        assertEquals(listOf(70, 110, 70, 110), (0..3).map { limitAt(g + it * s + 1) })
        assertEquals(listOf("plain", "link", "class", "link+class"), (0..3).map { plan.variantAt(it * v + g) })
    }

    @Test fun `link variants put the car on a new link at each change, plain ones send no route`() {
        val g = AdasLimitChangePlan.GAP_MS
        val s = AdasLimitChangePlan.STEP_MS
        val v = AdasLimitChangePlan.VARIANT_MS
        assertFalse(plan.framesAt(g + 1).any { it.topic == AdasRouteFrames.TOPIC_VEHICLE_LOCATION })
        val links = (0..3).map { i ->
            plan.framesAt(v + g + i * s + 1).single { it.topic == AdasRouteFrames.TOPIC_VEHICLE_LOCATION }.fields
                .substringAfter("link=").substringBefore(' ').toInt()
        }
        assertEquals(listOf(0, 1, 2, 3), links)
        assertTrue(plan.framesAt(v + g + 1).any { it.topic == AdasRouteFrames.TOPIC_SD_ROUTE })
    }

    @Test fun `class variants carry the road class`() {
        val g = AdasLimitChangePlan.GAP_MS
        val s = AdasLimitChangePlan.STEP_MS
        val v = AdasLimitChangePlan.VARIANT_MS
        // 70 on a provincial road (2); 110 on a motorway (0, which proto3 leaves out).
        val slow = plan.framesAt(2 * v + g + 1).single { it.topic == AdasShadowFrames.TOPIC_TRAFFIC_INFO }
        assertTrue(slow.fields, slow.fields.contains("RoadClass=2") && slow.fields.contains("DistToDsttn=2000"))
        val plain = plan.framesAt(g + 1).single { it.topic == AdasShadowFrames.TOPIC_TRAFFIC_INFO }
        assertFalse(plain.fields.contains("RoadClass"))
    }
}
