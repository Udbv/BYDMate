package com.bydmate.app.data.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.TriggerDef
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DefaultRulesSeederTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val seeder = DefaultRulesSeeder(context, mockk(relaxed = true), mockk(relaxed = true))

    @Test fun `seeds exactly the two start-up rules`() {
        val rules = seeder.build()
        assertEquals(2, rules.size)
        assertTrue(rules.any { it.name.contains("Spotify") })
        assertTrue(rules.any { it.name.contains("Waze") })
    }

    @Test fun `both fire on service start, once per trip`() {
        for (rule in seeder.build()) {
            val triggers = TriggerDef.listFromJson(rule.triggers)
            assertEquals(1, triggers.size)
            assertEquals("service_start", triggers.first().kind)
            assertTrue("${rule.name} must not repeat within a drive", rule.fireOncePerTrip)
        }
    }

    @Test fun `spotify launches then plays, with a warm-up in between`() {
        val spotify = seeder.build().first { it.name.contains("Spotify") }
        val actions = ActionDef.listFromJson(spotify.actions)
        assertEquals(listOf("app_launch", "delay", "media_play"), actions.map { it.kind })
        assertTrue(actions[0].payload!!.contains(DefaultRulesSeeder.SPOTIFY_PACKAGE))
        assertEquals(DefaultRulesSeeder.PLAYER_WARMUP_MS.toString(), actions[1].payload)
    }

    @Test fun `waze only opens, it has nothing to play`() {
        val waze = seeder.build().first { it.name.contains("Waze") }
        val actions = ActionDef.listFromJson(waze.actions)
        assertEquals(1, actions.size)
        assertEquals("app_launch", actions.first().kind)
        assertTrue(actions.first().payload!!.contains(DefaultRulesSeeder.WAZE_PACKAGE))
    }

    @Test fun `a rule for an app that is not installed is seeded disabled, not skipped`() {
        // Neither package exists in the Robolectric package manager, so both come out disabled
        // but present, which is what makes them one tap away once the app is installed.
        val rules = seeder.build()
        assertEquals(2, rules.size)
        assertTrue(rules.none { it.enabled })
    }
}
