package com.bydmate.app.helper

import java.util.concurrent.Executor

/**
 * The daemon half of TX_KEEP_APPS_DISABLED: validates the masks, applies the rate limit and runs
 * [KeptDisabledApps.reconcile] on [executor], so the binder call returns in microseconds and the
 * shared client channel is never held behind `pm`. Passes run one after another, never in parallel.
 */
internal class KeepAppsDisabledRunner(
    private val shell: KeptDisabledApps.Shell,
    private val executor: Executor,
    private val nowMs: () -> Long,
    private val log: (String) -> Unit,
) {
    private val throttle = KeptDisabledApps.Throttle()

    fun request(keepMask: Int, restoreMask: Int): Int {
        if (!KeptDisabledApps.masksValid(keepMask, restoreMask)) {
            log("refused: masks keep=$keepMask restore=$restoreMask outside the allowlist")
            return KeptDisabledApps.STATUS_REFUSED
        }
        val run = synchronized(throttle) { throttle.shouldRun(keepMask, restoreMask, nowMs()) }
        if (!run) return KeptDisabledApps.STATUS_THROTTLED
        executor.execute {
            runCatching { KeptDisabledApps.reconcile(keepMask, restoreMask, shell, log) }
                .onFailure { log("pass failed: ${it.javaClass.simpleName}") }
        }
        return KeptDisabledApps.STATUS_STARTED
    }
}
