package com.bydmate.app.navdata

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.bydmate.app.navdata.waze.WazeLaneSegmenter
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The lane strip's geometry: the container rectangle and the cell rectangles the accessibility
 * tree gives, which are what the pixel path crops.
 *
 * The 2026-09-15 drive could not say whether the widget handed to the classifier was the lane
 * strip or a small hint beside it, because nothing wrote the rectangle down. It does now.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
class WazeLaneGeometryTest {

    @Before fun setUp() {
        NaviPhraseFixtures.load()
        NavLaneState.clear()
    }

    @After fun tearDown() = NavLaneState.clear()

    @Test fun `the container and its cells are published with the display`() {
        WazeLaneReader.read(strip(Rect(100, 200, 400, 260), listOf(
            Rect(100, 200, 200, 260),
            Rect(200, 200, 300, 260),
            Rect(300, 200, 400, 260),
        )))

        val geometry = NavLaneState.geometry
        assertNotNull(geometry)
        assertEquals(NavLaneState.LaneRect(100, 200, 400, 260), geometry!!.container)
        assertEquals(3, geometry.cells.size)
        assertEquals(NavLaneState.LaneRect(200, 200, 300, 260), geometry.cells[1])
    }

    @Test fun `cells keep their left-to-right order whatever the tree's is`() {
        WazeLaneReader.read(strip(Rect(0, 0, 300, 60), listOf(
            Rect(200, 0, 300, 60),
            Rect(0, 0, 100, 60),
            Rect(100, 0, 200, 60),
        )))

        assertEquals(
            listOf(0, 100, 200),
            NavLaneState.geometry!!.cells.map { it.left },
        )
    }

    @Test fun `a strip without children still publishes its container for the segmenter`() {
        WazeLaneReader.read(strip(Rect(100, 200, 400, 260), emptyList()))

        val geometry = NavLaneState.geometry
        assertNotNull(geometry)
        assertTrue(geometry!!.cells.isEmpty())
    }

    @Test fun `no strip means no geometry`() {
        val root = node(Rect(0, 0, 1, 1))
        every { root.packageName } returns WAZE
        every { root.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()

        WazeLaneReader.read(root)

        assertNull(NavLaneState.geometry)
    }

    @Test fun `the log line names the display, the container and every cell`() {
        val line = WazeLaneReader.geometryLine(
            NavLaneState.LaneGeometry(
                displayId = 2,
                container = NavLaneState.LaneRect(100, 200, 400, 260),
                cells = listOf(
                    NavLaneState.LaneRect(100, 200, 200, 260),
                    NavLaneState.LaneRect(200, 200, 300, 260),
                ),
            ),
        )

        assertEquals(
            "display=2 container=100,200,400,260 cells=2 [100,200,200,260 200,200,300,260]",
            line,
        )
    }

    @Test fun `no geometry prints as dashes rather than nothing`() {
        assertEquals("display=- container=- cells=0", WazeLaneReader.geometryLine(null))
    }

    // ---- the brightness rule the cells are judged by ------------------------------------

    @Test fun `a brighter cell scores higher than a dimmer one`() {
        val w = 4
        val px = IntArray(w * 2) { if (it % w < 2) argb(255, 200, 200, 200) else argb(255, 40, 40, 40) }

        val bright = WazeLaneSegmenter.cellScore(px, w, 0, 0, 2, 2)
        val dim = WazeLaneSegmenter.cellScore(px, w, 2, 0, 2, 2)

        assertEquals(600f, bright, 0.01f)
        assertEquals(120f, dim, 0.01f)
        assertTrue(dim < bright * WazeLaneSegmenter.ON_ROUTE_FRACTION)
    }

    @Test fun `a crop outside the buffer scores zero rather than throwing`() {
        val px = IntArray(4)
        assertEquals(0f, WazeLaneSegmenter.cellScore(px, 2, 0, 0, 4, 4), 0f)
        assertEquals(0f, WazeLaneSegmenter.cellScore(px, 2, -1, 0, 2, 2), 0f)
    }

    @Test fun `an unmatched cell keeps its shape bits for the trip log`() {
        // A flat crop holds no shape at all, so the signature itself is null and the grid empty;
        // what matters is that the reader reports "not matched" rather than a confident zero.
        val verdict = WazeLaneSegmenter.classifyLaneDetailed(8, 8, IntArray(64) { argb(255, 90, 90, 90) })

        assertEquals(0, verdict.code)
        assertTrue(!verdict.matched)
    }

    // ---- fixtures ----------------------------------------------------------------------

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun strip(container: Rect, cells: List<Rect>): AccessibilityNodeInfo {
        val node = node(container, children = cells.map { node(it) })
        every { node.viewIdResourceName } returns "$WAZE:id/laneGuidanceView"
        val root = node(Rect(0, 0, 1920, 1080), children = listOf(node))
        every { root.packageName } returns WAZE
        every { root.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
        every { root.findAccessibilityNodeInfosByViewId("$WAZE:id/laneGuidanceView") } returns
            listOf(node)
        return root
    }

    private fun node(
        bounds: Rect,
        children: List<AccessibilityNodeInfo> = emptyList(),
    ): AccessibilityNodeInfo {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.viewIdResourceName } returns null
        every { node.text } returns null
        every { node.contentDescription } returns null
        every { node.isVisibleToUser } returns true
        every { node.childCount } returns children.size
        every { node.getBoundsInScreen(any()) } answers { firstArg<Rect>().set(bounds) }
        children.forEachIndexed { i, child -> every { node.getChild(i) } returns child }
        return node
    }

    private companion object {
        const val WAZE = "com.waze"
    }
}
