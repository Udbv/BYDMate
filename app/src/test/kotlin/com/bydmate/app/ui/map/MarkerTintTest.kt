package com.bydmate.app.ui.map

import android.content.Context
import android.graphics.drawable.GradientDrawable
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MarkerTintTest {

    private val res = ApplicationProvider.getApplicationContext<Context>().resources

    // osmdroid gives every marker the same cached pin; each tint must land on a private copy,
    // otherwise all pins end up in the colour of the last status drawn.
    @Test fun `each marker gets its own drawable, detached from the shared pin`() {
        val shared = GradientDrawable()
        val green = ownTintedCopy(shared, res, Color.Green)!!
        val orange = ownTintedCopy(shared, res, Color.Red)!!
        assertNotSame(shared, green)
        assertNotSame(shared, orange)
        assertNotSame(green, orange)
        assertNotSame(shared.constantState, green.constantState)
        assertNotSame(green.constantState, orange.constantState)
    }

    @Test fun `no pin, nothing to tint`() {
        assertNull(ownTintedCopy(null, res, Color.Green))
    }
}
