package com.bydmate.app.adas

/**
 * The parked limit change test (#23). On the 3.11 drive the car took the first limit it was sent
 * and ignored every later one. The factory map never sends a bare limit: on every position update
 * it sends the car's place on its route (`sdVehicleLocation`: path, step, link, offsets) together
 * with `TrafficInfo` (limit, road class, distance to the destination). The variants of [VARIANTS], each
 * [VARIANT_MS] long, change the limit 70 -> 110 -> 70 -> 110 every [STEP_MS] inside one session:
 * - `plain`: the limit alone, as the drive did (round 1 only);
 * - `link`: plus a route and the car on it, the link index going up at each change, as a limit
 *   changes where a new road piece begins;
 * - `class`: plus the road class (AutoNavi: 1 national road for 110, 2 provincial road for 70) and the
 *   distance and time to the destination, the fields the map fills;
 * - `link+class`: both.
 * Each variant opens with [GAP_MS] of "stopped" so it starts like a fresh navigation; what it
 * tells is whether the changes after its first value reach the car.
 *
 * Which variants run is [variants]: [VARIANTS] (no route) unless the route ones are asked for
 * ([ROUTE_VARIANTS]), and those need [here], the car's real position: a route is never put at 0/0.
 * The route and path select frames are the same through the run; the test sends them once
 * ([AdasRouteFrames.ONCE_TOPICS]), only the car's place on the route goes with every frame.
 */
class AdasLimitChangePlan(
    private val here: RoutePoint?,
    private val pathId: Long,
    private val nowMs: Long,
    private val variants: List<String> = VARIANTS,
) {
    init {
        require(here != null || variants.none { it.contains("link") }) { "a route variant needs the car's position" }
    }

    private val routeFrames: List<ShadowFrame> by lazy {
        val from = checkNotNull(here)
        AdasRouteFrames.of(AdasRouteFrames.straight(from, ROUTE_M), pathId, nowMs, from).take(2)
    }

    val durationMs: Long = variants.size * VARIANT_MS

    fun variantAt(elapsedMs: Long): String = variants[(elapsedMs / VARIANT_MS).toInt().coerceIn(0, variants.lastIndex)]

    fun framesAt(elapsedMs: Long): List<ShadowFrame> {
        val variant = variantAt(elapsedMs)
        val inVariant = elapsedMs % VARIANT_MS
        if (inVariant < GAP_MS) return listOf(AdasShadowFrames.naviStatus(AdasShadowFrames.NAVI_STOPPED), marker(variant))
        val step = ((inVariant - GAP_MS) / STEP_MS).toInt().coerceIn(0, LIMITS.lastIndex)
        val limit = LIMITS[step]
        val withLink = variant.contains("link")
        val withClass = variant.contains("class")
        val traffic = if (withClass) {
            AdasShadowFrames.trafficInfo(limit, 0, totalDist = ROUTE_M - step * LINK_M, etaSeconds = ETA_S, roadClass = classOf(limit))
        } else {
            AdasShadowFrames.trafficInfo(limit, 0)
        }
        val frames = mutableListOf(AdasShadowFrames.naviStatus(AdasShadowFrames.NAVI_ACTIVE), traffic, marker(variant))
        if (withLink) {
            frames += routeFrames
            frames += AdasRouteFrames.location(
                checkNotNull(here), pathId, link = step, linkRemainM = LINK_M.toLong(), stepRemainM = (ROUTE_M - step * LINK_M).toLong(),
            )
        }
        return frames
    }

    /** Never sent: the variant's name rides on the frame list so the log and the screen can name it. */
    private fun marker(variant: String) = ShadowFrame(MARKER_TOPIC, "variant", variant, byteArrayOf())

    companion object {
        /**
         * Round 2 (3.11 21:37, dev.15): in round 1 the car took nothing in `plain` and `link`,
         * and took 70 once in `class`, 14 s after it was first sent; 110 went out with road class 0
         * (motorway), which proto3 leaves out, and was never taken. So now the road class is never
         * 0 and each value is held twice as long.
         *
         * Round 3 (4.11, after the second freeze): the default run has no route at all, `class`
         * alone; `link+class` is a separate run ([ROUTE_VARIANTS]) the owner starts on purpose.
         */
        val VARIANTS = listOf("class")
        val ROUTE_VARIANTS = listOf("link+class")
        val LIMITS = listOf(70, 110, 70, 110)
        const val GAP_MS = 2_000L
        const val STEP_MS = 12_000L
        const val VARIANT_MS = GAP_MS + 4 * STEP_MS
        private const val ROUTE_M = 2_000
        private const val LINK_M = 200
        private const val ETA_S = 120
        private const val CLASS_NATIONAL = 1
        private const val CLASS_PROVINCIAL = 2
        private const val FAST_KPH = 100
        /** A topic no service has: frames on it are bookkeeping, never fired. */
        const val MARKER_TOPIC = 0L

        private fun classOf(limit: Int) = if (limit >= FAST_KPH) CLASS_NATIONAL else CLASS_PROVINCIAL

        fun variantOf(frames: List<ShadowFrame>): String? = frames.firstOrNull { it.topic == MARKER_TOPIC }?.fields
    }
}
