package com.bydmate.app.adas

import android.content.Context
import com.bydmate.app.hud.HudSomeIpBridge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One binding to the SOME/IP gateway for every ADAS sender of the process (the parked tests and
 * the drive-time sender), each holding a [lease] of it.
 *
 * Why: the gateway (`SomeIpServerService.onUnbind`) stops on the HAL every service our package
 * started once the last binding with our intent goes, and the factory map offers the same
 * services past the gateway (HIDL), so that stop takes the map's offer off the bus too. That is
 * what froze the head unit's ADAS view in dev.10-15. Every bridge of ours binds the same intent
 * (the type is our package), so it is enough that one binding stays: once a lease has started a
 * service on it ([pinned]) the shared binding is never released for the life of the process, and
 * the HUD output's own stop (`HudController.stopSequence`: stopService + unbind) is no longer the
 * last unbind. Before that, the binding goes when its last lease does, as each sender's own
 * bridge did.
 *
 * What it cannot prevent: the death of our process ends every binding, and the gateway then
 * stops our services as before (see the PR's notes).
 */
class AdasSharedGateway(private val open: (onLost: () -> Unit) -> AdasGateway) {

    private val bindMutex = Mutex()
    private val lock = Any()
    private var gateway: AdasGateway? = null
    private var bound = false
    private var users = 0
    private var pinned = false

    /** A user's handle: its [AdasGateway.unbind] lets go of the lease, not of the shared binding. */
    fun lease(): AdasGateway = Lease()

    /** Leases bound now. */
    val consumers: Int get() = synchronized(lock) { users }

    /** The shared binding is up. */
    val isBound: Boolean get() = synchronized(lock) { bound }

    /** A service was started on it: kept bound for the life of the process. */
    val isPinned: Boolean get() = synchronized(lock) { pinned }

    /** `bound=true users=1 pinned=true`, for the senders' log lines. */
    fun describe(): String = synchronized(lock) { "bound=$bound users=$users pinned=$pinned" }

    private suspend fun acquire(): Boolean = bindMutex.withLock {
        val reuse = synchronized(lock) {
            if (bound) users++
            bound
        }
        if (reuse) return@withLock true
        val g = synchronized(lock) { gateway ?: open { lost() }.also { gateway = it } }
        val ok = g.bind()
        if (ok) synchronized(lock) {
            bound = true
            users++
        }
        ok
    }

    private fun release() {
        val g = synchronized(lock) {
            users = (users - 1).coerceAtLeast(0)
            if (users > 0 || pinned || !bound) return
            bound = false
            gateway
        }
        g?.unbind()
    }

    /** The bridge lost the gateway and let go of it itself; the next lease to bind binds again. */
    private fun lost() {
        synchronized(lock) { bound = false }
    }

    private inner class Lease : AdasGateway {
        @Volatile private var held = false

        override suspend fun bind(): Boolean {
            if (!held) held = acquire()
            return held
        }

        override fun startService(serviceId: Long): Int {
            val g = current() ?: return NOT_BOUND
            synchronized(lock) { pinned = true }
            return g.startService(serviceId)
        }

        override fun stopService(serviceId: Long): Int = current()?.stopService(serviceId) ?: NOT_BOUND

        override fun fireEvent(topic: Long, payload: ByteArray): Int = current()?.fireEvent(topic, payload) ?: NOT_BOUND

        override fun unbind() {
            if (!held) return
            held = false
            release()
        }

        private fun current(): AdasGateway? = if (held) synchronized(lock) { gateway } else null
    }

    companion object {
        /** What the bridge answers without a binding. */
        private const val NOT_BOUND = -1

        @Volatile private var process: AdasSharedGateway? = null

        /** The process's one shared binding, over a [HudSomeIpBridge]. */
        fun process(context: Context): AdasSharedGateway =
            process ?: synchronized(this) {
                process ?: AdasSharedGateway { onLost ->
                    BridgeGateway(HudSomeIpBridge(context.applicationContext, onLost))
                }.also { process = it }
            }
    }
}
