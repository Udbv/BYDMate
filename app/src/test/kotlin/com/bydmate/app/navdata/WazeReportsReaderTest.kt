package com.bydmate.app.navdata

import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Waze's reports list, read from a fake tree shaped like the one the 2026-09-14 drive recorded.
 *
 * The car runs Waze in Ukrainian, so the Ukrainian rows are the case that has to work; the words
 * come from the language packs, never from this file's expectations.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
class WazeReportsReaderTest {

    @Before fun setUp() = NaviPhraseFixtures.load()

    // ---- the parser halves ------------------------------------------------------------

    @Test fun `a camera row is recognised in ukrainian and english`() {
        assertEquals(WazeReportsReader.Kind.CAMERA, WazeReportsReader.kindOf("Камера"))
        assertEquals(WazeReportsReader.Kind.CAMERA, WazeReportsReader.kindOf("камери попереду"))
        assertEquals(WazeReportsReader.Kind.CAMERA, WazeReportsReader.kindOf("Speed camera"))
    }

    @Test fun `police and everything else are kept apart from the camera`() {
        assertEquals(WazeReportsReader.Kind.POLICE, WazeReportsReader.kindOf("Поліція"))
        assertEquals(WazeReportsReader.Kind.POLICE, WazeReportsReader.kindOf("Police"))
        assertEquals(WazeReportsReader.Kind.OTHER, WazeReportsReader.kindOf("Глухий затор"))
        assertEquals(WazeReportsReader.Kind.OTHER, WazeReportsReader.kindOf("Обмежувач швидкості"))
    }

    @Test fun `distances use the packs' units`() {
        assertEquals(1600, WazeReportsReader.distanceMetersOf("1.6 км попереду"))
        assertEquals(130, WazeReportsReader.distanceMetersOf("130 м попереду"))
        assertEquals(500, WazeReportsReader.distanceMetersOf("500 m ahead"))
    }

    @Test fun `a row that does not say ahead reports no distance`() {
        assertEquals(0, WazeReportsReader.distanceMetersOf("1.6 км"))
        assertEquals(0, WazeReportsReader.distanceMetersOf(null))
        assertEquals(0, WazeReportsReader.distanceMetersOf(""))
    }

    // ---- the tree ---------------------------------------------------------------------

    @Test fun `rows come back in the list's own order`() {
        val reports = WazeReportsReader.read(
            wazeRoot(
                row("Глухий затор", "1.6 км попереду"),
                row("Камера", "800 м попереду"),
                row("Поліція", "2 км попереду"),
            ),
        )

        assertEquals(3, reports.size)
        assertEquals(listOf("Глухий затор", "Камера", "Поліція"), reports.map { it.typeText })
        assertEquals(
            listOf(
                WazeReportsReader.Kind.OTHER,
                WazeReportsReader.Kind.CAMERA,
                WazeReportsReader.Kind.POLICE,
            ),
            reports.map { it.kind },
        )
        assertEquals(listOf(1600, 800, 2000), reports.map { it.distanceMeters })
    }

    @Test fun `a flat list pairs each type with the distance that follows it`() {
        val list = node(
            WazeReportsReader.ID_LIST,
            children = listOf(
                node(WazeReportsReader.ID_TYPE, text = "Камера"),
                node(WazeReportsReader.ID_DISTANCE, text = "300 м попереду"),
                node(WazeReportsReader.ID_TYPE, text = "Поліція"),
                node(WazeReportsReader.ID_DISTANCE, text = "900 м попереду"),
            ),
        )

        val reports = WazeReportsReader.read(rootWith(list))

        assertEquals(listOf("Камера", "Поліція"), reports.map { it.typeText })
        assertEquals(listOf(300, 900), reports.map { it.distanceMeters })
    }

    @Test fun `a type with no distance is still a row`() {
        val reports = WazeReportsReader.read(wazeRoot(row("Камера", null)))

        assertEquals(1, reports.size)
        assertEquals(WazeReportsReader.Kind.CAMERA, reports.single().kind)
        assertEquals(0, reports.single().distanceMeters)
    }

    @Test fun `an empty list reads as no reports`() {
        val root = node("root")
        every { root.packageName } returns WAZE
        every { root.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()

        assertTrue(WazeReportsReader.read(root).isEmpty())
    }

    @Test fun `a non-Waze window is never read`() {
        val root = wazeRoot(row("Камера", "300 м попереду"))
        every { root.packageName } returns "ru.yandex.yandexnavi"

        assertTrue(WazeReportsReader.read(root).isEmpty())
    }

    // ---- fixtures ---------------------------------------------------------------------

    private fun row(type: String, distance: String?): AccessibilityNodeInfo = node(
        "navListReportItem",
        children = listOfNotNull(
            node("navListReportItemImage"),
            distance?.let { node(WazeReportsReader.ID_DISTANCE, text = it) },
            node(WazeReportsReader.ID_TYPE, text = type),
        ),
    )

    private fun wazeRoot(vararg rows: AccessibilityNodeInfo): AccessibilityNodeInfo =
        rootWith(node(WazeReportsReader.ID_LIST, children = rows.toList()))

    private fun rootWith(list: AccessibilityNodeInfo): AccessibilityNodeInfo {
        val root = node("root", children = listOf(list))
        every { root.packageName } returns WAZE
        every { root.findAccessibilityNodeInfosByViewId("$WAZE:id/${WazeReportsReader.ID_LIST}") } returns
            listOf(list)
        return root
    }

    private fun node(
        id: String?,
        text: String? = null,
        children: List<AccessibilityNodeInfo> = emptyList(),
    ): AccessibilityNodeInfo {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.viewIdResourceName } returns id?.let { "$WAZE:id/$it" }
        every { node.text } returns text
        every { node.contentDescription } returns null
        every { node.childCount } returns children.size
        children.forEachIndexed { i, child -> every { node.getChild(i) } returns child }
        return node
    }

    private companion object {
        const val WAZE = "com.waze"
    }
}
