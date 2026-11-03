package com.bydmate.app.adas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The parked limit change test's plan (#23), round 2. */
class AdasLimitChangePlanTest {

    private val plan = AdasLimitChangePlan(RoutePoint(49.0, 25.0), 7L, 0L)
    private val g = AdasLimitChangePlan.GAP_MS
    private val s = AdasLimitChangePlan.STEP_MS
    private val v = AdasLimitChangePlan.VARIANT_MS

    private fun traffic(ms: Long) = plan.framesAt(ms).firstOrNull { it.topic == AdasShadowFrames.TOPIC_TRAFFIC_INFO }?.fields

    private fun limitAt(ms: Long) = traffic(ms)?.substringAfter("SpdLmtSpeedValue=")?.substringBefore(' ')?.toInt()

    @Test fun `each variant opens stopped, then changes the limit every step`() {
        assertEquals(2 * v, plan.durationMs)
        assertEquals(null, limitAt(0))
        assertEquals(AdasShadowFrames.naviStatus(AdasShadowFrames.NAVI_STOPPED), plan.framesAt(0).first())
        assertEquals(listOf(70, 110, 70, 110), (0..3).map { limitAt(g + it * s + 1) })
        assertEquals(listOf("class", "link+class"), (0..1).map { plan.variantAt(it * v + g) })
    }

    @Test fun `the road class is never left out`() {
        assertTrue(traffic(g + 1)!!.contains("RoadClass=2"))
        assertTrue(traffic(g + s + 1)!!.contains("RoadClass=1"))
    }

    @Test fun `the link variant puts the car on a new link at each change`() {
        val links = (0..3).map { i ->
            plan.framesAt(v + g + i * s + 1).single { it.topic == AdasRouteFrames.TOPIC_VEHICLE_LOCATION }.fields
                .substringAfter("link=").substringBefore(' ').toInt()
        }
        assertEquals(listOf(0, 1, 2, 3), links)
        assertTrue(plan.framesAt(g + 1).none { it.topic == AdasRouteFrames.TOPIC_VEHICLE_LOCATION })
    }
}
