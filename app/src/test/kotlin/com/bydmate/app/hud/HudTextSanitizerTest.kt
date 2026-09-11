package com.bydmate.app.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sanitizer has two paths and the difference matters on the car: ICU romanises Cyrillic,
 * the no-ICU fallback cannot. Both are pinned here, with the ICU step injected so the JVM test
 * does not depend on whether this runtime ships android.icu.
 */
class HudTextSanitizerTest {

    /** Stand-in for `Any-Latin; Latin-ASCII` over the characters these tests use. */
    private val fakeIcu: (String) -> String = { s ->
        val table = mapOf(
            'в' to "v", 'у' to "u", 'л' to "l", 'С' to "S", 'а' to "a", 'д' to "d",
            'о' to "o", 'Х' to "Kh", 'р' to "r", 'е' to "e", 'щ' to "shch", 'т' to "t",
            'и' to "i", 'к' to "k", 'é' to "e", 'ñ' to "n",
        )
        s.map { table[it] ?: it.toString() }.joinToString("")
    }

    private val icu = HudTextSanitizer(fakeIcu)
    private val noIcu = HudTextSanitizer(null)

    @Test fun `the icu path romanises cyrillic`() {
        assertEquals("vul. Sadova", icu.sanitize("вул. Садова"))
        assertEquals("Khreshchatik", icu.sanitize("Хрещатик"))
    }

    @Test fun `chinese passes through both paths untouched`() {
        // A Chinese-market map already draws correctly on this cluster; romanising it would
        // make it worse, so the CJK ranges are excluded from transliteration.
        assertEquals("长安街", icu.sanitize("长安街"))
        assertEquals("长安街", noIcu.sanitize("长安街"))
        assertTrue(HudTextSanitizer.isCjk('长'))
        assertTrue(HudTextSanitizer.isCjk('㐀'))
        assertTrue(HudTextSanitizer.isCjk('豈'))
        assertFalse(HudTextSanitizer.isCjk('в'))
    }

    @Test fun `the fallback strips accents but cannot romanise cyrillic`() {
        // NFD decomposition only reaches scripts that decompose to Latin. Cyrillic does not,
        // so without ICU the name goes to the panel as it arrived - which is exactly why the
        // ICU path is the default and this one is only a safety net.
        assertEquals("Cafe", noIcu.sanitize("Café"))
        assertEquals("Munchen", noIcu.sanitize("München"))
        assertEquals("Espana", noIcu.sanitize("España"))
        assertEquals("вул. Садова", noIcu.sanitize("вул. Садова"))
    }

    @Test fun `the fallback output carries no combining marks`() {
        val out = noIcu.sanitize("Ärzteweg Åre Čapek")
        assertTrue(out, out.none { it.code in 0x0300..0x036F })
        assertEquals("Arzteweg Are Capek", out)
    }

    @Test fun `blank and null become empty and the result is trimmed`() {
        assertEquals("", icu.sanitize(null))
        assertEquals("", icu.sanitize(""))
        assertEquals("", icu.sanitize("   "))
        assertEquals("Main Street", icu.sanitize("  Main Street  "))
    }

    @Test fun `a transliterator that throws falls back per character instead of losing the name`() {
        val exploding = HudTextSanitizer { s -> if (s == "é") error("boom") else s }
        assertEquals("Cafe", exploding.sanitize("Café"))
    }

    @Test fun `ascii is left alone`() {
        assertEquals("Main St / A1", icu.sanitize("Main St / A1"))
    }
}
