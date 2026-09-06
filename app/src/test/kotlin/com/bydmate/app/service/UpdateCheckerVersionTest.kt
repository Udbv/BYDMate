package com.bydmate.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Release-tag ordering for the stable and development update channels. */
class UpdateCheckerVersionTest {

    @Test fun `higher numeric triple is newer`() {
        assertTrue(UpdateChecker.isNewer("3.16.0", "3.15.0"))
        assertTrue(UpdateChecker.isNewer("v3.15.1", "3.15.0"))
        assertTrue(UpdateChecker.isNewer("4.0.0", "3.99.9"))
        assertFalse(UpdateChecker.isNewer("3.15.0", "3.15.0"))
        assertFalse(UpdateChecker.isNewer("3.14.9", "3.15.0"))
    }

    @Test fun `two-part versions compare as x_y_0`() {
        assertTrue(UpdateChecker.isNewer("3.16", "3.15.2"))
        assertFalse(UpdateChecker.isNewer("3.15", "3.15.0"))
    }

    @Test fun `final release beats a pre-release of the same triple`() {
        assertTrue(UpdateChecker.isNewer("3.15.0", "3.15.0-dev.3"))
        assertFalse(UpdateChecker.isNewer("3.15.0-dev.3", "3.15.0"))
        assertTrue(UpdateChecker.isNewer("3.15.1-dev.1", "3.15.0"))
    }

    @Test fun `pre-releases order by their build number`() {
        assertTrue(UpdateChecker.isNewer("3.15.1-dev.2", "3.15.1-dev.1"))
        assertFalse(UpdateChecker.isNewer("3.15.1-dev.1", "3.15.1-dev.2"))
        assertFalse(UpdateChecker.isNewer("3.15.1-dev.1", "3.15.1-dev.1"))
        assertTrue(UpdateChecker.isNewer("3.15.1-beta.5", "3.15.1-dev.1"))
    }

    @Test fun `garbage is never newer, unparsable local always updates`() {
        assertFalse(UpdateChecker.isNewer("latest", "3.15.0"))
        assertFalse(UpdateChecker.isNewer("", "3.15.0"))
        assertTrue(UpdateChecker.isNewer("3.15.0", "unknown"))
    }
}
