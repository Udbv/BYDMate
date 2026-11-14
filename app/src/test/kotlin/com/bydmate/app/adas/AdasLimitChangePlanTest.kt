package com.bydmate.app.adas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The parked limit change test's plan (#23), round 3: no route unless asked for. */
class AdasLimitChangePlanTest {

    private val plan = AdasLimitChangePlan(RoutePoint(49.0, 25.0), 7L, 0L)
    private val routePlan = AdasLimitChangePlan(RoutePoint(49.0, 25.0), 7L, 0L, AdasLimitChangePlan.ROUTE_VARIANTS)
    private val g = AdasLimitChangePlan.GAP_MS
    private val s = AdasLimitChangePlan.STEP_MS
    private val v = AdasLimitChangePlan.VARIANT_MS

    private fun traffic(p: AdasLimitChangePlan, ms: Long) =
        p.framesAt(ms).firstOrNull { it.topic == AdasShadowFrames.TOPIC_TRAFFIC_INFO }?.fields

    private fun limitAt(p: AdasLimitChangePlan, ms: Long) =
        traffic(p, ms)?.substringAfter("SpdLmtSpeedValue=")?.substringBefore(' ')?.toInt()

    private val routeTopics = AdasRouteFrames.ONCE_TOPICS + AdasRouteFrames.TOPIC_VEHICLE_LOCATION

    @Test fun `each variant opens idle, then changes the limit every step`() {
        assertEquals(v, plan.durationMs)
        assertEquals(null, limitAt(plan, 0))
        assertEquals(AdasShadowFrames.naviStatus(AdasShadowFrames.NAVI_STOPPED), plan.framesAt(0).first())
        assertEquals(listOf(70, 110, 70, 110), (0..3).map { limitAt(plan, g + it * s + 1) })
        assertEquals("class", plan.variantAt(g))
        assertEquals(AdasShadowFrames.naviStatus(AdasShadowFrames.NAVI_ACTIVE), plan.framesAt(g + 1).first())
    }

    @Test fun `the road class is never left out`() {
        assertTrue(traffic(plan, g + 1)!!.contains("RoadClass=2"))
        assertTrue(traffic(plan, g + s + 1)!!.contains("RoadClass=1"))
    }

    @Test fun `by default no route, no path select and no location, all the way through`() {
        assertEquals(listOf("class"), AdasLimitChangePlan.VARIANTS)
        val topics = (0 until plan.durationMs step 200).flatMap { ms -> plan.framesAt(ms).map { it.topic } }.toSet()
        assertTrue(topics.none { it in routeTopics })
    }

    @Test fun `without a position only the plan without a route can be made`() {
        val noPosition = AdasLimitChangePlan(null, 7L, 0L)
        assertEquals(limitAt(plan, g + 1), limitAt(noPosition, g + 1))
        val refused = runCatching { AdasLimitChangePlan(null, 7L, 0L, AdasLimitChangePlan.ROUTE_VARIANTS) }
        assertTrue("never a route at 0/0", refused.isFailure)
    }

    @Test fun `the route variant puts the car on a new link at each change, the route itself unchanged`() {
        assertEquals("link+class", routePlan.variantAt(g))
        val links = (0..3).map { i ->
            routePlan.framesAt(g + i * s + 1).single { it.topic == AdasRouteFrames.TOPIC_VEHICLE_LOCATION }.fields
                .substringAfter("link=").substringBefore(' ').toInt()
        }
        assertEquals(listOf(0, 1, 2, 3), links)
        val routes = (0..3).map { i -> routePlan.framesAt(g + i * s + 1).filter { it.topic in AdasRouteFrames.ONCE_TOPICS } }
        assertTrue("the same route frames every step, so the test sends them once", routes.distinct().size == 1)
        assertTrue(routePlan.framesAt(1).none { it.topic in routeTopics })
    }
}
