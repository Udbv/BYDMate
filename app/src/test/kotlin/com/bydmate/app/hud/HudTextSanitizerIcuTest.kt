package com.bydmate.app.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The real `android.icu` transliterator, which only exists on an Android runtime. This is the
 * path the car takes; [HudTextSanitizerTest] covers the fallback with ICU injected out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HudTextSanitizerIcuTest {

    @Test fun `the real icu transliterator romanises cyrillic and keeps chinese`() {
        val step = HudTextSanitizer.icuTransliterator()
        assertNotNull("android.icu must be present on an Android runtime", step)
        val s = HudTextSanitizer(step)
        // ICU's Any-Latin romanisation of Ukrainian: в->v, у->u, л->l, С->S, ...
        assertEquals("vul. Sadova", s.sanitize("вул. Садова"))
        assertEquals("长安街", s.sanitize("长安街"))
        assertEquals("Main St", s.sanitize(" Main St "))
    }
}
