package com.bydmate.app.helper

import com.bydmate.app.helper.KeptDisabledApps.Outcome
import com.bydmate.app.helper.KeptDisabledApps.PkgState
import com.bydmate.app.helper.KeptDisabledApps.Want
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class KeptDisabledAppsTest {

    private val youku = KeptDisabledApps.ALLOWLIST[0]
    private val hms = KeptDisabledApps.ALLOWLIST[4]

    // --- allowlist ---------------------------------------------------------------------------

    @Test fun `the allowlist is exactly the five apps disabled by hand, bits fixed`() {
        assertEquals(
            listOf("com.byd.videoplay.youku", "com.byd.videoplay", "cmgyunting.vehicleplayer.cnr",
                "com.alipay.arome.app", "com.huawei.hwid"),
            KeptDisabledApps.ALLOWLIST.map { it.pkg },
        )
        assertEquals(listOf("youku", "iqiyi", "yunting", "alipay", "hms"), KeptDisabledApps.ALLOWLIST.map { it.id })
        assertEquals(0b11111, KeptDisabledApps.ALL_BITS)
    }

    @Test fun `no BYD infrastructure, ADAS, OTA or assistant package can be in the allowlist`() {
        val forbidden = listOf("ota", "adas", "autoservice", "launcher", "autovoice", "cluster", "avm",
            "settings", "bluetooth", "mediacenter", "accountservice", "systemui", "amapservice", "cota", "update")
        for (app in KeptDisabledApps.ALLOWLIST) for (word in forbidden) {
            assertFalse("${app.pkg} contains $word", app.pkg.contains(word))
        }
    }

    @Test fun `argFor refuses any package outside the allowlist or with shell characters`() {
        assertEquals("com.huawei.hwid", KeptDisabledApps.argFor(hms))
        for (bad in listOf(
            KeptDisabledApps.App("x", "com.byd.otaupdate", "x"),
            KeptDisabledApps.App("youku", "com.byd.videoplay.youku; reboot", "Youku"),
            KeptDisabledApps.App("youku", "\$(reboot)", "Youku"),
        )) {
            try { KeptDisabledApps.argFor(bad); fail("accepted ${bad.pkg}") } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun `command scripts are fixed and take the package only as a separate argument`() {
        for (script in listOf(KeptDisabledApps.DISABLE, KeptDisabledApps.ENABLE, KeptDisabledApps.DUMP)) {
            assertTrue(script, script.endsWith("\"\$1\""))
            for (app in KeptDisabledApps.ALLOWLIST) assertFalse(script.contains(app.pkg))
        }
        assertEquals("pm disable-user --user 0 \"\$1\"", KeptDisabledApps.DISABLE)
        assertEquals("pm enable --user 0 \"\$1\"", KeptDisabledApps.ENABLE)
    }

    // --- masks -------------------------------------------------------------------------------

    @Test fun `masks come from the stored choices, never-chosen apps are in neither`() {
        val (keep, restore) = KeptDisabledApps.masksOf(mapOf("youku" to "true", "hms" to "false", "iqiyi" to "", "evil" to "true"))
        assertEquals(0b00001, keep)
        assertEquals(0b10000, restore)
        assertEquals(0 to 0, KeptDisabledApps.masksOf(emptyMap()))
    }

    @Test fun `masks with bits outside the allowlist or overlapping are invalid`() {
        assertTrue(KeptDisabledApps.masksValid(0b11111, 0))
        assertFalse(KeptDisabledApps.masksValid(0b100000, 0))
        assertFalse(KeptDisabledApps.masksValid(0, -1))
        assertFalse(KeptDisabledApps.masksValid(0b1, 0b1))
    }

    // --- decide ------------------------------------------------------------------------------

    private fun st(enabled: Int, persistent: Boolean = false, uid: Int = 10057) = PkgState(enabled, persistent, uid)

    @Test fun `enabled app kept disabled is disabled`() {
        for (s in listOf(KeptDisabledApps.STATE_DEFAULT, KeptDisabledApps.STATE_ENABLED)) {
            val d = KeptDisabledApps.decide(Want.KEEP_DISABLED, st(s))
            assertEquals(Outcome.DISABLED, d.outcomeIfOk)
            assertEquals(KeptDisabledApps.DISABLE, d.script)
        }
    }

    @Test fun `already disabled app needs nothing`() {
        for (s in listOf(KeptDisabledApps.STATE_DISABLED, KeptDisabledApps.STATE_DISABLED_USER, KeptDisabledApps.STATE_DISABLED_UNTIL_USED)) {
            val d = KeptDisabledApps.decide(Want.KEEP_DISABLED, st(s))
            assertEquals(Outcome.UNCHANGED, d.outcomeIfOk)
            assertNull(d.script)
        }
    }

    @Test fun `persistent app is refused`() {
        val d = KeptDisabledApps.decide(Want.KEEP_DISABLED, st(0, persistent = true))
        assertEquals(Outcome.REFUSED_PERSISTENT, d.outcomeIfOk)
        assertNull(d.script)
    }

    @Test fun `system uid app is refused`() {
        val d = KeptDisabledApps.decide(Want.KEEP_DISABLED, st(0, uid = 1000))
        assertEquals(Outcome.REFUSED_SYSTEM_UID, d.outcomeIfOk)
        assertNull(d.script)
    }

    @Test fun `missing app is skipped, unreadable app is left alone`() {
        assertEquals(KeptDisabledApps.Decision(Outcome.MISSING, null), KeptDisabledApps.decide(Want.KEEP_DISABLED, null))
        assertEquals(KeptDisabledApps.Decision(Outcome.MISSING, null), KeptDisabledApps.decide(Want.RESTORE, null))
        assertEquals(KeptDisabledApps.Decision(Outcome.UNREADABLE, null), KeptDisabledApps.decide(Want.KEEP_DISABLED, st(-1)))
        assertEquals(KeptDisabledApps.Decision(Outcome.UNREADABLE, null), KeptDisabledApps.decide(Want.KEEP_DISABLED, st(0, uid = -1)))
    }

    @Test fun `unticked app disabled by the user is enabled, one disabled by the system stays`() {
        val d = KeptDisabledApps.decide(Want.RESTORE, st(KeptDisabledApps.STATE_DISABLED_USER))
        assertEquals(Outcome.ENABLED, d.outcomeIfOk)
        assertEquals(KeptDisabledApps.ENABLE, d.script)
        assertNull(KeptDisabledApps.decide(Want.RESTORE, st(KeptDisabledApps.STATE_DISABLED)).script)
        assertNull(KeptDisabledApps.decide(Want.RESTORE, st(KeptDisabledApps.STATE_ENABLED)).script)
    }

    @Test fun `unmanaged app is never touched`() {
        assertNull(KeptDisabledApps.decide(Want.UNMANAGED, st(0)).script)
    }

    // --- parsing (format as dumped on the Tang L, Android 13) --------------------------------

    private fun dump(pkg: String, flags: String, uid: Int, user0: String) = """
        Activity Resolver Table:
          Non-Data Actions:
              android.intent.action.MAIN:
                1234 $pkg/.Main filter 5678

        Packages:
          Package [$pkg] (b9de45a):
            userId=$uid
            pkg=Package{b9e5582 $pkg}
            codePath=/system/app/VideoPlay
            flags=[ $flags ]
            pkgFlags=[ $flags ]
            User 0: ceDataInode=1927 $user0 instant=false virtual=false
              gids=[3003]

        Hidden system packages:
          Package [$pkg] (aaaaaaa):
            userId=$uid
            pkgFlags=[ SYSTEM HAS_CODE PERSISTENT ]
            User 0: ceDataInode=0 installed=true enabled=0 instant=false
    """.trimIndent()

    @Test fun `dump of an enabled system app`() {
        val out = dump("com.byd.videoplay", "SYSTEM HAS_CODE ALLOW_CLEAR_USER_DATA ALLOW_BACKUP", 10071,
            "installed=true hidden=false suspended=false distractionFlags=0 stopped=false notLaunched=false enabled=0")
        assertEquals(PkgState(0, false, 10071), KeptDisabledApps.parseDump("com.byd.videoplay", out))
    }

    @Test fun `dump of a persistent user-disabled app`() {
        val out = dump("com.byd.autovoice.engine", "SYSTEM HAS_CODE PERSISTENT ALLOW_CLEAR_USER_DATA ALLOW_BACKUP", 10040,
            "installed=true hidden=false enabled=3")
        assertEquals(PkgState(3, true, 10040), KeptDisabledApps.parseDump("com.byd.autovoice.engine", out))
    }

    @Test fun `dump of a missing or uninstalled app is null`() {
        assertNull(KeptDisabledApps.parseDump("com.huawei.hwid", "Unable to find package: com.huawei.hwid"))
        val out = dump("com.huawei.hwid", "HAS_CODE", 10129, "installed=false hidden=false enabled=0")
        assertNull(KeptDisabledApps.parseDump("com.huawei.hwid", out))
    }

    @Test fun `disabled list parsing`() {
        assertEquals(setOf("com.byd.videoplay", "com.huawei.hwid"),
            KeptDisabledApps.parseDisabledList("package:com.byd.videoplay\r\npackage:com.huawei.hwid\n\n"))
    }

    // --- reconcile with a scripted shell -----------------------------------------------------

    private class FakeShell(
        val disabledList: String = "",
        val dumps: Map<String, String> = emptyMap(),
        val pmOk: Boolean = true,
    ) : KeptDisabledApps.Shell {
        val calls = mutableListOf<List<String>>()
        override fun exec(script: String, vararg args: String): Pair<Int, String> {
            calls += listOf(script) + args
            return when (script) {
                KeptDisabledApps.LIST_DISABLED -> 0 to disabledList
                KeptDisabledApps.DUMP -> 0 to (dumps[args[0]] ?: "Unable to find package: ${args[0]}")
                KeptDisabledApps.DISABLE -> if (pmOk) 0 to "Package ${args[0]} new state: disabled-user" else 0 to "Exception"
                KeptDisabledApps.ENABLE -> 0 to "Package ${args[0]} new state: enabled"
                else -> error("unexpected script $script")
            }
        }
    }

    private fun enabledDump(pkg: String, persistent: Boolean = false) =
        dump(pkg, if (persistent) "SYSTEM PERSISTENT" else "SYSTEM HAS_CODE", 10057, "installed=true enabled=0")

    @Test fun `enabled app is disabled with the package as its own argument`() {
        val shell = FakeShell(dumps = mapOf(youku.pkg to enabledDump(youku.pkg)))
        val r = KeptDisabledApps.reconcile(0b1, 0, shell) {}
        assertEquals(mapOf(0 to Outcome.DISABLED), r)
        assertEquals(listOf(
            listOf(KeptDisabledApps.LIST_DISABLED),
            listOf(KeptDisabledApps.DUMP, youku.pkg),
            listOf(KeptDisabledApps.DISABLE, youku.pkg),
        ), shell.calls)
    }

    @Test fun `steady state costs one list call`() {
        val shell = FakeShell(disabledList = KeptDisabledApps.ALLOWLIST.joinToString("\n") { "package:${it.pkg}" })
        val r = KeptDisabledApps.reconcile(KeptDisabledApps.ALL_BITS, 0, shell) {}
        assertTrue(r.values.all { it == Outcome.UNCHANGED })
        assertEquals(listOf(listOf(KeptDisabledApps.LIST_DISABLED)), shell.calls)
    }

    @Test fun `persistent app is never disabled`() {
        val shell = FakeShell(dumps = mapOf(youku.pkg to enabledDump(youku.pkg, persistent = true)))
        val r = KeptDisabledApps.reconcile(0b1, 0, shell) {}
        assertEquals(Outcome.REFUSED_PERSISTENT, r[0])
        assertTrue(shell.calls.none { it[0] == KeptDisabledApps.DISABLE })
    }

    @Test fun `missing app is skipped without any pm write`() {
        val shell = FakeShell()
        val r = KeptDisabledApps.reconcile(0b10000, 0, shell) {}
        assertEquals(Outcome.MISSING, r[4])
        assertTrue(shell.calls.none { it[0] == KeptDisabledApps.DISABLE || it[0] == KeptDisabledApps.ENABLE })
    }

    @Test fun `unticked app is enabled again`() {
        val disabledDump = dump(hms.pkg, "HAS_CODE", 10129, "installed=true enabled=3")
        val shell = FakeShell(disabledList = "package:${hms.pkg}", dumps = mapOf(hms.pkg to disabledDump))
        val r = KeptDisabledApps.reconcile(0, 0b10000, shell) {}
        assertEquals(Outcome.ENABLED, r[4])
        assertEquals(listOf(KeptDisabledApps.ENABLE, hms.pkg), shell.calls.last())
    }

    @Test fun `unticked app already enabled costs nothing more`() {
        val shell = FakeShell()
        assertEquals(mapOf(4 to Outcome.UNCHANGED), KeptDisabledApps.reconcile(0, 0b10000, shell) {})
        assertEquals(1, shell.calls.size)
    }

    @Test fun `pm without a confirmation is a failure`() {
        val shell = FakeShell(dumps = mapOf(youku.pkg to enabledDump(youku.pkg)), pmOk = false)
        assertEquals(Outcome.FAILED, KeptDisabledApps.reconcile(0b1, 0, shell) {}[0])
    }

    @Test fun `masks outside the allowlist run nothing at all`() {
        val shell = FakeShell()
        val logs = mutableListOf<String>()
        assertTrue(KeptDisabledApps.reconcile(1 shl 5, 0, shell) { logs += it }.isEmpty())
        assertTrue(shell.calls.isEmpty())
        assertTrue(logs.single().startsWith("refused"))
    }

    @Test fun `nothing managed sends nothing`() {
        val shell = FakeShell()
        assertTrue(KeptDisabledApps.reconcile(0, 0, shell) {}.isEmpty())
        assertTrue(shell.calls.isEmpty())
    }

    @Test fun `log lines carry only ids, packages and outcomes`() {
        val shell = FakeShell(dumps = mapOf(youku.pkg to enabledDump(youku.pkg)))
        val logs = mutableListOf<String>()
        KeptDisabledApps.reconcile(0b1, 0, shell) { logs += it }
        assertEquals(listOf("youku com.byd.videoplay.youku: DISABLED", "pass keep=1 restore=0: youku=DISABLED"), logs)
    }

    // --- throttle ----------------------------------------------------------------------------

    @Test fun `same masks run once a minute, a change runs at once`() {
        val t = KeptDisabledApps.Throttle(60_000L)
        assertTrue(t.shouldRun(1, 0, 0L))
        assertFalse(t.shouldRun(1, 0, 30_000L))
        assertTrue(t.shouldRun(3, 0, 31_000L))
        assertFalse(t.shouldRun(3, 0, 90_000L))
        assertTrue(t.shouldRun(3, 0, 91_000L))
    }
}
