package com.bydmate.app.navdata

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavA11yFeedTest {

    private val contentChanged = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
    private val stateChanged = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
    private val clicked = AccessibilityEvent.TYPE_VIEW_CLICKED

    @Test fun `navigator content change passes`() {
        assertTrue(NavA11yFeed.shouldProcess("ru.yandex.yandexnavi", contentChanged, nowMs = 1000, lastMs = 0))
        assertTrue(NavA11yFeed.shouldProcess("ru.yandex.yandexnavi.inhouse", stateChanged, nowMs = 1000, lastMs = 0))
    }

    @Test fun `foreign package filtered`() {
        assertFalse(NavA11yFeed.shouldProcess("com.android.systemui", contentChanged, nowMs = 1000, lastMs = 0))
        assertFalse(NavA11yFeed.shouldProcess(null, contentChanged, nowMs = 1000, lastMs = 0))
    }

    @Test fun `irrelevant event types filtered`() {
        assertFalse(NavA11yFeed.shouldProcess("ru.yandex.yandexnavi", clicked, nowMs = 1000, lastMs = 0))
    }

    /** 200 ms, openbyd's window. Waze emits the arrow and the distance as separate events tens of
     *  milliseconds apart, and the old 500 ms floor swallowed one of the pair every time. */
    @Test fun `debounce blocks rapid events`() {
        assertFalse(NavA11yFeed.shouldProcess("ru.yandex.yandexnavi", contentChanged, nowMs = 1150, lastMs = 1000))
        assertTrue(NavA11yFeed.shouldProcess("ru.yandex.yandexnavi", contentChanged, nowMs = 1200, lastMs = 1000))
    }

    @Test fun `maps packages pass`() {
        assertTrue(NavA11yFeed.shouldProcess("ru.yandex.yandexmaps", contentChanged, nowMs = 1000, lastMs = 0))
        assertTrue(NavA11yFeed.shouldProcess("ru.yandex.yandexmaps.rustore", stateChanged, nowMs = 1000, lastMs = 0))
    }
}
