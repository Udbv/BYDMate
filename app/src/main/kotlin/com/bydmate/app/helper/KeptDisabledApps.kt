package com.bydmate.app.helper

/**
 * Stock multimedia apps BYDMate keeps disabled across firmware updates (`pm disable-user`), the
 * five the owner switched off by hand on 2026-11-04 (specs/cn-apps-disable-report.md). The
 * shell-uid daemon does the work; the app holds the per-app choice and hands it over as two bit
 * masks over [ALLOWLIST], so no package name and no free text ever travels to a `pm` command.
 *
 * Shared by the app (masks, labels) and the daemon (reading the state, deciding, the commands).
 * Pure Kotlin: everything that touches the system goes through [Shell].
 */
object KeptDisabledApps {

    /** Tag of every line about this feature, app and daemon alike: `logcat -s KeepDisabled`. */
    const val TAG = "KeepDisabled"

    /**
     * [id] is stable: it names the settings key and fixes the bit. Append only; never reorder.
     * [label] is the brand as the driver knows it, untranslated.
     */
    data class App(val id: String, val pkg: String, val label: String)

    /**
     * The only packages this feature may ever touch. None is persistent, none runs as the system
     * uid and no other BYD service binds to them (specs/cn-apps-cleanup-report.md). The daemon
     * re-checks persistent and the uid before every disable anyway.
     */
    val ALLOWLIST: List<App> = listOf(
        App("youku", "com.byd.videoplay.youku", "Youku"),
        App("iqiyi", "com.byd.videoplay", "iQIYI"),
        App("yunting", "cmgyunting.vehicleplayer.cnr", "云听 (CNR radio)"),
        App("alipay", "com.alipay.arome.app", "Alipay"),
        App("hms", "com.huawei.hwid", "Huawei HMS Core"),
    )

    /** Every bit that names an app of [ALLOWLIST]; anything above it is refused. */
    val ALL_BITS: Int = (1 shl ALLOWLIST.size) - 1

    /** A package name as `pm` knows it: dotted lowercase segments, nothing a shell would read. */
    private val SAFE_NAME = Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+$")

    // Fixed scripts. The package is always "$1", a separate argv entry for `sh -c`, never spliced.
    const val LIST_DISABLED = "pm list packages -d"
    const val DUMP = "dumpsys package \"\$1\""
    const val DISABLE = "pm disable-user --user 0 \"\$1\""
    const val ENABLE = "pm enable --user 0 \"\$1\""

    // PackageManager.COMPONENT_ENABLED_STATE_*, as `dumpsys package` prints them (enabled=N).
    const val STATE_DEFAULT = 0
    const val STATE_ENABLED = 1
    const val STATE_DISABLED = 2
    const val STATE_DISABLED_USER = 3
    const val STATE_DISABLED_UNTIL_USED = 4

    /** First uid of an ordinary app; anything below runs as a system service. */
    private const val FIRST_APPLICATION_UID = 10_000

    /** What the driver chose for one app. */
    enum class Want { UNMANAGED, KEEP_DISABLED, RESTORE }

    /** One app's state read from `dumpsys package` under shell uid. */
    data class PkgState(val enabledState: Int, val persistent: Boolean, val uid: Int)

    enum class Outcome {
        /** Already in the wanted state: no command sent. */
        UNCHANGED,
        DISABLED,
        ENABLED,
        /** Not on this car: skipped silently. */
        MISSING,
        REFUSED_PERSISTENT,
        REFUSED_SYSTEM_UID,
        /** The state could not be read: nothing is done on a guess. */
        UNREADABLE,
        FAILED,
    }

    /** What to do for one app; [script] is null when nothing is sent. */
    data class Decision(val outcomeIfOk: Outcome, val script: String?)

    fun interface Shell {
        /** Runs `sh -c [script] sh args...`; returns exit code and stdout. */
        fun exec(script: String, vararg args: String): Pair<Int, String>
    }

    // --- app side: settings <-> masks ---------------------------------------------------------

    /** Settings key holding the choice for [app]: "" never chosen, "true" keep disabled, "false" restore. */
    fun settingsKey(app: App): String = "keep_disabled_app_${app.id}"

    /**
     * (keepMask, restoreMask) from the stored choices, keyed by [App.id]. A never-chosen app is in
     * neither mask, so a driver who never opened the setting sends nothing at all.
     */
    fun masksOf(choices: Map<String, String>): Pair<Int, Int> {
        var keep = 0
        var restore = 0
        ALLOWLIST.forEachIndexed { i, app ->
            when (choices[app.id]) {
                "true" -> keep = keep or (1 shl i)
                "false" -> restore = restore or (1 shl i)
            }
        }
        return keep to restore
    }

    // --- daemon side ---------------------------------------------------------------------------

    /** The masks are well formed: only allowlist bits, and no app both kept and restored. */
    fun masksValid(keepMask: Int, restoreMask: Int): Boolean =
        keepMask and ALL_BITS.inv() == 0 && restoreMask and ALL_BITS.inv() == 0 && keepMask and restoreMask == 0

    /** What the driver wants for the app at [index] of [ALLOWLIST]. */
    fun wantAt(index: Int, keepMask: Int, restoreMask: Int): Want = when {
        keepMask and (1 shl index) != 0 -> Want.KEEP_DISABLED
        restoreMask and (1 shl index) != 0 -> Want.RESTORE
        else -> Want.UNMANAGED
    }

    /**
     * The single place a `pm` argument is produced: only an [App] of [ALLOWLIST] with a safe name.
     * Anything else throws, so a bug can never turn into a command on a foreign package.
     */
    fun argFor(app: App): String {
        require(app in ALLOWLIST) { "not in the allowlist" }
        require(SAFE_NAME.matches(app.pkg)) { "unsafe package name" }
        return app.pkg
    }

    /** Package names listed by `pm list packages -d` ("package:x" per line). */
    fun parseDisabledList(out: String): Set<String> =
        out.lineSequence().map { it.trim() }.filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }.filter { it.isNotEmpty() }.toSet()

    /**
     * Reads [pkg]'s block of `dumpsys package <pkg>`: null when the package is not installed for
     * user 0 (`Unable to find package`, no block, `installed=false`); a [PkgState] with
     * enabledState -1 when the block is there but the state line is missing.
     */
    fun parseDump(pkg: String, out: String): PkgState? {
        val lines = out.lines()
        val start = lines.indexOfFirst { it.trim().startsWith("Package [$pkg]") }
        if (start < 0) return null
        // The block runs until the next "Package [" header (or a section header at indent 0/1).
        var end = lines.size
        for (i in start + 1 until lines.size) {
            val t = lines[i]
            if (t.trim().startsWith("Package [") || (t.isNotBlank() && !t.startsWith("  "))) { end = i; break }
        }
        val block = lines.subList(start + 1, end)
        val persistent = block.any { l ->
            val t = l.trim()
            (t.startsWith("pkgFlags=[") || t.startsWith("flags=[")) && " PERSISTENT " in "$t "
        }
        val uid = block.firstNotNullOfOrNull { Regex("^\\s*(?:userId|appId)=(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: -1
        val user0 = block.firstOrNull { Regex("^\\s*User 0: .*enabled=").containsMatchIn(it) }
        if (user0 != null && Regex("\\binstalled=false\\b").containsMatchIn(user0)) return null
        val enabled = user0?.let { Regex("\\benabled=(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: -1
        return PkgState(enabled, persistent, uid)
    }

    /**
     * The rule. KEEP_DISABLED: an enabled, non-persistent, ordinary-uid app is disabled; one
     * already disabled (by us, the driver or the system) is left alone. RESTORE: only a
     * user-disabled app is enabled again, a system-disabled one stays as the firmware set it.
     * A missing app is skipped, an unreadable one is never acted on.
     */
    fun decide(want: Want, state: PkgState?): Decision {
        if (want == Want.UNMANAGED) return Decision(Outcome.UNCHANGED, null)
        if (state == null) return Decision(Outcome.MISSING, null)
        if (state.enabledState < 0) return Decision(Outcome.UNREADABLE, null)
        return when (want) {
            Want.KEEP_DISABLED -> when {
                state.enabledState != STATE_DEFAULT && state.enabledState != STATE_ENABLED ->
                    Decision(Outcome.UNCHANGED, null)
                state.persistent -> Decision(Outcome.REFUSED_PERSISTENT, null)
                state.uid in 0 until FIRST_APPLICATION_UID -> Decision(Outcome.REFUSED_SYSTEM_UID, null)
                state.uid < 0 -> Decision(Outcome.UNREADABLE, null)
                else -> Decision(Outcome.DISABLED, DISABLE)
            }
            Want.RESTORE ->
                if (state.enabledState == STATE_DISABLED_USER) Decision(Outcome.ENABLED, ENABLE)
                else Decision(Outcome.UNCHANGED, null)
            Want.UNMANAGED -> Decision(Outcome.UNCHANGED, null)
        }
    }

    /** `pm` confirms a change with "new state: ..."; an exit code alone is not trusted. */
    fun pmSucceeded(code: Int, out: String): Boolean = code == 0 && "new state:" in out

    /**
     * One pass over the managed apps. Steady state costs one `pm list packages -d`: an app the
     * driver keeps disabled that is already in that list needs nothing more, and an app to restore
     * that is not in it is already enabled. Only the rest are read with `dumpsys package` and,
     * when the rule says so, changed. Returns the outcome per allowlist index for managed apps.
     */
    fun reconcile(keepMask: Int, restoreMask: Int, shell: Shell, log: (String) -> Unit): Map<Int, Outcome> {
        if (!masksValid(keepMask, restoreMask)) {
            log("refused: masks keep=$keepMask restore=$restoreMask outside the allowlist")
            return emptyMap()
        }
        if (keepMask == 0 && restoreMask == 0) return emptyMap()
        val (listCode, listOut) = shell.exec(LIST_DISABLED)
        val disabled = if (listCode == 0) parseDisabledList(listOut) else null
        val result = LinkedHashMap<Int, Outcome>()
        ALLOWLIST.forEachIndexed { i, app ->
            val want = wantAt(i, keepMask, restoreMask)
            if (want == Want.UNMANAGED) return@forEachIndexed
            val pkg = argFor(app)
            if (disabled != null) {
                if (want == Want.KEEP_DISABLED && pkg in disabled) { result[i] = Outcome.UNCHANGED; return@forEachIndexed }
                if (want == Want.RESTORE && pkg !in disabled) { result[i] = Outcome.UNCHANGED; return@forEachIndexed }
            }
            val (dumpCode, dumpOut) = shell.exec(DUMP, pkg)
            val state = if (dumpCode == 0) parseDump(pkg, dumpOut) else PkgState(-1, false, -1)
            val decision = decide(want, state)
            val outcome = if (decision.script == null) decision.outcomeIfOk else {
                val (code, out) = shell.exec(decision.script, pkg)
                if (pmSucceeded(code, out)) decision.outcomeIfOk else Outcome.FAILED
            }
            result[i] = outcome
            if (outcome != Outcome.UNCHANGED) log("${app.id} $pkg: $outcome")
        }
        log("pass keep=$keepMask restore=$restoreMask: " +
            result.entries.joinToString(" ") { (i, o) -> "${ALLOWLIST[i].id}=$o" })
        return result
    }

    /**
     * Rate limit of the daemon: the same masks run at most once per [MIN_INTERVAL_MS]; a change
     * of masks (the driver flipped a switch) always runs. Not thread-safe; the caller locks.
     */
    class Throttle(private val minIntervalMs: Long = MIN_INTERVAL_MS) {
        private var lastKeep = -1
        private var lastRestore = -1
        private var lastRunMs = Long.MIN_VALUE / 2

        fun shouldRun(keepMask: Int, restoreMask: Int, nowMs: Long): Boolean {
            val changed = keepMask != lastKeep || restoreMask != lastRestore
            if (!changed && nowMs - lastRunMs < minIntervalMs) return false
            lastKeep = keepMask; lastRestore = restoreMask; lastRunMs = nowMs
            return true
        }
    }

    const val MIN_INTERVAL_MS = 60_000L

    // TX_KEEP_APPS_DISABLED reply status.
    const val STATUS_STARTED = 0
    const val STATUS_THROTTLED = 1
    const val STATUS_REFUSED = -1
    /** Client side only: the daemon is too old to know the transaction. */
    const val STATUS_NOT_HANDLED = -2
}
