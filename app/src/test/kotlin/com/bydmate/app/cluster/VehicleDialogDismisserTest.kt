package com.bydmate.app.cluster

import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VehicleDialogDismisserTest {

    @Test fun `title match is a case-insensitive substring like the donor's dumpsys grep`() {
        assertTrue(VehicleDialogDismisser.matchesTitle("vehicledialog"))
        assertTrue(VehicleDialogDismisser.matchesTitle("com.byd.vehicledialog/.WarnActivity"))
        assertTrue(VehicleDialogDismisser.matchesTitle("VehicleDialog"))
        assertFalse(VehicleDialogDismisser.matchesTitle("com.waze/.MainActivity"))
        assertFalse(VehicleDialogDismisser.matchesTitle(null))
    }

    private fun node(
        clickable: Boolean,
        className: String = "android.widget.TextView",
        children: List<AccessibilityNodeInfo> = emptyList(),
    ): AccessibilityNodeInfo = mockk(relaxed = true) {
        every { isClickable } returns clickable
        every { isEnabled } returns true
        every { this@mockk.className } returns className
        every { childCount } returns children.size
        children.forEachIndexed { i, c -> every { getChild(i) } returns c }
    }

    @Test fun `the single button of the dialog is preferred over other clickable nodes`() {
        val button = node(clickable = true, className = "android.widget.Button")
        val clickableText = node(clickable = true)
        val root = node(clickable = false, children = listOf(node(clickable = false, children = listOf(clickableText, button))))

        assertEquals(button, VehicleDialogDismisser.pickButton(root))
    }

    @Test fun `any clickable node is the fallback when no Button class exists`() {
        val clickableText = node(clickable = true)
        val root = node(clickable = false, children = listOf(clickableText))

        assertEquals(clickableText, VehicleDialogDismisser.pickButton(root))
    }

    @Test fun `nothing clickable means nothing picked`() {
        val root = node(clickable = false, children = listOf(node(clickable = false)))

        assertNull(VehicleDialogDismisser.pickButton(root))
    }
}
