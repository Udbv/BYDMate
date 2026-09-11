package com.bydmate.app.navdata

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.bydmate.app.cluster.SteeringWheelKeyService
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Waze node discovery: one line the first time a view id is seen in a session.
 *
 * This is what has to name the camera and speed-limit views — they appear for a few seconds and
 * the maneuver-change dump is both rate-limited and text-free, so it never catches them.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
class NavA11yFeedNewIdsTest {

    private val lines = mutableListOf<String>()
    private val realSink = NavA11yFeed.newIdSink

    @Before fun installSink() {
        NavA11yFeed.enabled = false          // a fresh session: the seen-id set starts empty
        NavA11yFeed.newIdSink = { lines.add(it) }
        NavGuidanceHub.reset()
    }

    @After fun tearDown() {
        NavA11yFeed.newIdSink = realSink
        NavA11yFeed.enabled = false
        NavGuidanceHub.reset()
    }

    @Test fun `a new id is reported once with its text, description and class`() {
        deliver(wazeRoot(listOf(
            node("alerter_title", text = "Speed camera", desc = "camera ahead", cls = "android.widget.TextView"),
        )))

        assertEquals(2, lines.size)   // the root plus the child
        val line = lines.single { it.startsWith("new id=alerter_title") }
        assertTrue(line, "text='Speed camera'" in line)
        assertTrue(line, "desc='camera ahead'" in line)
        assertTrue(line, "class=TextView" in line)
    }

    @Test fun `an id already seen is not reported again`() {
        val child = { node("alerter_title", text = "Speed camera") }
        deliver(wazeRoot(listOf(child())))
        deliver(wazeRoot(listOf(child())))

        assertEquals(1, lines.count { it.startsWith("new id=alerter_title") })
    }

    @Test fun `only ids that are new are reported on a later read`() {
        deliver(wazeRoot(listOf(node("alerter_title"))))
        lines.clear()
        deliver(wazeRoot(listOf(node("alerter_title"), node("alerter_distance", text = "300 m"))))

        assertEquals(1, lines.size)
        assertTrue(lines.single(), lines.single().startsWith("new id=alerter_distance"))
    }

    @Test fun `text and description are clipped`() {
        deliver(wazeRoot(listOf(node("long_one", text = "x".repeat(200)))))

        val line = lines.single { it.startsWith("new id=long_one") }
        assertTrue(line, "text='${"x".repeat(60)}'" in line)
    }

    @Test fun `a node without an id costs nothing`() {
        deliver(wazeRoot(listOf(node(null, text = "unnamed"))))

        assertEquals(1, lines.size)   // only the root
        assertTrue(lines.single(), lines.single().startsWith("new id=root_container"))
    }

    @Test fun `the session cap holds`() {
        deliver(wazeRoot((1..NavA11yFeed.DISCOVERY_MAX_NEW_IDS + 50).map { node("v$it") }))

        assertEquals(NavA11yFeed.DISCOVERY_MAX_NEW_IDS, lines.size)
    }

    @Test fun `a non-Waze navigator is not walked`() {
        val root = node("root_container", children = listOf(node("lane_sign")))
        every { root.packageName } returns "ru.yandex.yandexnavi"
        every { root.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
        deliver(root, pkg = "ru.yandex.yandexnavi")

        assertTrue(lines.toString(), lines.isEmpty())
    }

    private fun deliver(root: AccessibilityNodeInfo, pkg: String = WAZE) {
        NavA11yFeed.enabled = true
        NavA11yFeed.lastProcessMs = 0L
        val service = mockk<SteeringWheelKeyService> {
            every { findNavigatorRoot() } returns root
        }
        val event = mockk<AccessibilityEvent>(relaxed = true)
        every { event.eventType } returns AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        every { event.packageName } returns pkg
        NavA11yFeed.onEvent(service, event)
    }

    /** A Waze root with no guidance widgets: discovery must not depend on a live route. */
    private fun wazeRoot(children: List<AccessibilityNodeInfo>): AccessibilityNodeInfo {
        val root = node("root_container", children = children)
        every { root.packageName } returns WAZE
        every { root.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
        return root
    }

    private fun node(
        id: String?,
        text: String? = null,
        desc: String? = null,
        cls: String? = null,
        children: List<AccessibilityNodeInfo> = emptyList(),
    ): AccessibilityNodeInfo {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.viewIdResourceName } returns id?.let { "$WAZE:id/$it" }
        every { node.text } returns text
        every { node.contentDescription } returns desc
        every { node.className } returns cls
        every { node.childCount } returns children.size
        children.forEachIndexed { i, child -> every { node.getChild(i) } returns child }
        return node
    }

    private companion object {
        const val WAZE = "com.waze"
    }
}
