package com.bydmate.app.adas

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One gateway binding per process for the ADAS senders: the gateway stops every service of our
 * package on the HAL when our last binding goes, so a binding that started services never goes.
 */
class AdasSharedGatewayTest {

    private class FakeGateway(private val binds: Boolean = true) : AdasGateway {
        var bindCalls = 0
        var unbindCalls = 0
        val started = mutableListOf<Long>()
        val fired = mutableListOf<Long>()
        override suspend fun bind(): Boolean = binds.also { bindCalls++ }
        override fun startService(serviceId: Long): Int = 0.also { started += serviceId }
        override fun stopService(serviceId: Long): Int = 0
        override fun fireEvent(topic: Long, payload: ByteArray): Int = 0.also { fired += topic }
        override fun unbind() { unbindCalls++ }
    }

    private var lost: () -> Unit = {}
    private var opened = 0

    private fun shared(gateway: FakeGateway) = AdasSharedGateway { onLost ->
        opened++
        lost = onLost
        gateway
    }

    @Test fun `two users share one binding and the first to leave does not unbind it`() = runTest {
        val gateway = FakeGateway()
        val share = shared(gateway)
        val a = share.lease()
        val b = share.lease()
        assertTrue(a.bind())
        assertTrue(b.bind())
        assertEquals(1, gateway.bindCalls)
        assertEquals(1, opened)
        assertEquals(2, share.consumers)
        a.unbind()
        assertEquals(0, gateway.unbindCalls)
        assertEquals(0, b.fireEvent(1L, byteArrayOf()))
        b.unbind()
        assertEquals("no services started: the last user lets the binding go", 1, gateway.unbindCalls)
        assertFalse(share.isBound)
    }

    @Test fun `a binding that started services is never released while the process lives`() = runTest {
        val gateway = FakeGateway()
        val share = shared(gateway)
        val a = share.lease()
        assertTrue(a.bind())
        a.startService(5L)
        a.unbind()
        assertEquals(0, gateway.unbindCalls)
        assertTrue(share.isBound && share.isPinned)
        assertEquals(0, share.consumers)
        val b = share.lease()
        assertTrue(b.bind())
        assertEquals("the next user reuses it", 1, gateway.bindCalls)
    }

    @Test fun `a released lease sends nothing and unbinding it twice counts once`() = runTest {
        val gateway = FakeGateway()
        val share = shared(gateway)
        val a = share.lease()
        val b = share.lease()
        a.bind()
        b.bind()
        a.unbind()
        a.unbind()
        assertEquals(-1, a.fireEvent(1L, byteArrayOf()))
        assertEquals(-1, a.startService(5L))
        assertTrue(gateway.fired.isEmpty() && gateway.started.isEmpty())
        assertEquals(1, share.consumers)
        assertFalse(share.isPinned)
    }

    @Test fun `a failed bind holds nothing`() = runTest {
        val gateway = FakeGateway(binds = false)
        val share = shared(gateway)
        val a = share.lease()
        assertFalse(a.bind())
        a.unbind()
        assertEquals(0, share.consumers)
        assertEquals(0, gateway.unbindCalls)
    }

    @Test fun `a lost gateway is bound again by the next user`() = runTest {
        val gateway = FakeGateway()
        val share = shared(gateway)
        val a = share.lease()
        a.bind()
        a.startService(5L)
        lost()
        assertFalse(share.isBound)
        val b = share.lease()
        assertTrue(b.bind())
        assertEquals(2, gateway.bindCalls)
        assertEquals("one bridge per process, rebound", 1, opened)
    }
}
