package com.bydmate.app.navdata

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Language-pack contract for the navigator phrase tables: every listed file exists, parses,
 *  uses known maneuver codes and carries the unit keys; the engine reads only from them. */
class NavPhraseTablesTest {

    @Before fun load() = NaviPhraseFixtures.load()

    private val required = setOf("km", "m", "mi", "ft", "hour", "minute")

    @Test fun `manifest lists every pack file and every pack file is listed`() {
        val listed = NavPhraseTables.current.languages.toSet()
        val onDisk = NaviPhraseFixtures.dir.listFiles { f -> f.extension == "json" && f.name != NavPhraseTables.MANIFEST }!!
            .map { it.nameWithoutExtension }.toSet()
        assertEquals(onDisk, listed)
        assertTrue("en" in listed && "ru" in listed && "uk" in listed)
    }

    @Test fun `every pack has the unit keys and only known maneuver codes`() {
        NavPhraseTables.current.tables.forEach { t ->
            assertEquals("units of ${t.lang}", required, t.units.keys)
            assertTrue("${t.lang} has no maneuvers", t.maneuvers.isNotEmpty())
            t.maneuvers.forEach { m ->
                assertTrue("${t.lang}: ${m.code}", NavManeuverCodes.codeName(m.code) != "UNKNOWN")
                assertTrue("${t.lang}: empty phrase list for ${m.code}", m.phrases.isNotEmpty())
                m.phrases.forEach { p -> assertEquals("${t.lang}: phrases must be lowercase", p, p.lowercase()) }
            }
        }
    }

    @Test fun `phrases are unique within a pack and never map one phrase to two codes`() {
        NavPhraseTables.current.tables.forEach { t ->
            val seen = HashMap<String, Int>()
            t.maneuvers.forEach { m ->
                m.phrases.forEach { p ->
                    val prev = seen.put(p, m.code)
                    assertTrue("${t.lang}: '$p' mapped to both $prev and ${m.code}", prev == null || prev == m.code)
                }
            }
        }
    }

    @Test fun `numbered exit regexes capture the exit number as group 1`() {
        val samples = mapOf("en" to "take the 2nd exit", "ru" to "2-й съезд", "uk" to "на 2-му з'їзді")
        NavPhraseTables.current.tables.filter { it.numberedExit != null }.forEach { t ->
            val sample = samples[t.lang] ?: return@forEach
            assertEquals(t.lang, "2", t.numberedExit!!.find(sample)?.groupValues?.get(1))
        }
    }

    @Test fun `mini json reader handles escapes nesting and unicode`() {
        val v = NavPhraseTables.MiniJson.parse("""{"a": ["x\"y", "з'ї", 1, -2.5, true, null], "b": {"c": []}}""") as Map<*, *>
        val a = v["a"] as List<*>
        assertEquals("x\"y", a[0])
        assertEquals("з'ї", a[1])
        assertEquals(1L, a[2])
        assertEquals(-2.5, a[3])
        assertEquals(true, a[4])
        assertEquals(null, a[5])
        assertEquals(emptyList<Any>(), (v["b"] as Map<*, *>)["c"])
    }

    @Test fun `a broken pack is rejected without replacing the loaded tables`() {
        val before = NavPhraseTables.current
        val failed = runCatching {
            NavPhraseTables.load("""{"languages": ["xx"]}""") { """{"lang": "xx", "maneuvers": [{"code": "NOPE", "phrases": ["a"]}]}""" }
        }.isFailure
        assertTrue(failed)
        assertTrue(before === NavPhraseTables.current)
        File(NaviPhraseFixtures.dir, NavPhraseTables.MANIFEST).also { assertTrue(it.exists()) }
    }
}
