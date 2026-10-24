package com.bydmate.app.data.vehicle

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import com.bydmate.app.helper.HelperBinderProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Client side of the SDK verbs: what lands in the request parcel, and how the daemon's reply is
 * interpreted. Paired with HelperDaemonSdkWriteTest, which reads the same parcels back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HelperClientSdkWriteTest {

    private abstract class FakeIBinder : IBinder {
        override fun isBinderAlive(): Boolean = true
        override fun pingBinder(): Boolean = true
        override fun getInterfaceDescriptor(): String = HelperBinderProtocol.DESCRIPTOR
        override fun queryLocalInterface(descriptor: String): IInterface? = null
        @Suppress("OVERRIDE_DEPRECATION")
        override fun dump(fd: java.io.FileDescriptor, args: Array<String>?) {}
        override fun dumpAsync(fd: java.io.FileDescriptor, args: Array<String>?) {}
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {}
        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean = true
    }

    /**
     * Records the transaction code and reads the request body (everything after the interface
     * token) through [capture] while the parcel is still alive, then replies [status], 0.
     */
    private class CapturingFake(
        private val status: Int = 0,
        private val capture: (Parcel) -> Unit = {},
    ) : FakeIBinder() {
        var code: Int = 0
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            this.code = code
            data.setDataPosition(0)
            data.enforceInterface(HelperBinderProtocol.DESCRIPTOR)
            capture(data)
            reply!!.writeInt(status); reply.writeInt(0); reply.setDataPosition(0)
            return true
        }
    }

    private val oldDaemon: IBinder = object : FakeIBinder() {
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = false
    }

    private fun clientWith(binder: IBinder?): HelperClientImpl = object : HelperClientImpl() {
        override fun resolveBinder(): IBinder? = binder
    }

    // ---- TX_SDK_SET encoding ---------------------------------------------------------------

    @Test
    fun `sdkSetInt writes device, kind, fid and value`() = runBlocking {
        val seen = mutableListOf<Int>()
        val fake = CapturingFake { p -> repeat(4) { seen += p.readInt() } }
        val status = clientWith(fake)
            .sdkSetInt(HelperBinderProtocol.SDK_DEV_SETTING, 0x4C10E015, 3)
        assertEquals(0, status)
        assertEquals(HelperBinderProtocol.TX_SDK_SET, fake.code)
        assertEquals(
            listOf(HelperBinderProtocol.SDK_DEV_SETTING, HelperBinderProtocol.SDK_KIND_INT, 0x4C10E015, 3),
            seen,
        )
    }

    @Test
    fun `sdkSetDouble writes the double`() = runBlocking {
        var dev = -1; var kind = -1; var fid = 0; var value = -1.0
        val fake = CapturingFake { p ->
            dev = p.readInt(); kind = p.readInt(); fid = p.readInt(); value = p.readDouble()
        }
        clientWith(fake).sdkSetDouble(HelperBinderProtocol.SDK_DEV_STATISTIC, 754057264, 0.0)
        assertEquals(HelperBinderProtocol.SDK_DEV_STATISTIC, dev)
        assertEquals(HelperBinderProtocol.SDK_KIND_DOUBLE, kind)
        assertEquals(754057264, fid)
        assertEquals(0.0, value, 0.0)
    }

    @Test
    fun `sdkSetBytes writes the buffer`() = runBlocking {
        val bytes = "Садова".toByteArray(Charsets.UTF_16LE)
        var dev = -1; var kind = -1; var fid = 0; var seen: ByteArray? = null
        val fake = CapturingFake { p ->
            dev = p.readInt(); kind = p.readInt(); fid = p.readInt(); seen = p.createByteArray()
        }
        clientWith(fake).sdkSetBytes(HelperBinderProtocol.SDK_DEV_INSTRUMENT, 0x43FA1008, bytes)
        assertEquals(HelperBinderProtocol.SDK_DEV_INSTRUMENT, dev)
        assertEquals(HelperBinderProtocol.SDK_KIND_BYTES, kind)
        assertEquals(0x43FA1008, fid)
        assertArrayEquals(bytes, seen)
    }

    @Test
    fun `sdkSetIntArray writes both arrays in one transaction`() = runBlocking {
        val fids = IntArray(25) { 0x198020D8 + it }
        val values = IntArray(25) { it }
        var dev = -1; var kind = -1
        var seenFids: IntArray? = null; var seenValues: IntArray? = null
        val fake = CapturingFake { p ->
            dev = p.readInt(); kind = p.readInt()
            seenFids = p.createIntArray(); seenValues = p.createIntArray()
        }
        clientWith(fake).sdkSetIntArray(HelperBinderProtocol.SDK_DEV_INSTRUMENT, fids, values)
        assertEquals(HelperBinderProtocol.SDK_DEV_INSTRUMENT, dev)
        assertEquals(HelperBinderProtocol.SDK_KIND_INT_ARRAY, kind)
        assertArrayEquals(fids, seenFids)
        assertArrayEquals(values, seenValues)
    }

    @Test
    fun `sdkSetIntArray refuses a mismatched, empty or oversize batch without a transaction`() = runBlocking {
        val fake = CapturingFake()
        val client = clientWith(fake)
        assertNull(client.sdkSetIntArray(1, intArrayOf(1, 2), intArrayOf(1)))
        assertNull(client.sdkSetIntArray(1, IntArray(0), IntArray(0)))
        val n = HelperBinderProtocol.MAX_SDK_ARRAY + 1
        assertNull(client.sdkSetIntArray(1, IntArray(n), IntArray(n)))
        assertEquals(0, fake.code)   // binder never touched
    }

    // ---- TX_SDK_NAVI encoding --------------------------------------------------------------

    @Test
    fun `sdkNaviStatus writes method and status`() = runBlocking {
        val seen = mutableListOf<Int>()
        val fake = CapturingFake { p -> repeat(2) { seen += p.readInt() } }
        clientWith(fake).sdkNaviStatus(2)
        assertEquals(HelperBinderProtocol.TX_SDK_NAVI, fake.code)
        assertEquals(listOf(HelperBinderProtocol.SDK_NAVI_STATUS, 2), seen)
    }

    @Test
    fun `sdkSimpleGuidance writes icon then distance`() = runBlocking {
        val seen = mutableListOf<Int>()
        val fake = CapturingFake { p -> repeat(3) { seen += p.readInt() } }
        clientWith(fake).sdkSimpleGuidance(11, 350)
        assertEquals(listOf(HelperBinderProtocol.SDK_NAVI_SIMPLE_GUIDANCE, 11, 350), seen)
    }

    @Test
    fun `sdkNextPathName writes the string`() = runBlocking {
        var method = -1; var name: String? = null
        val fake = CapturingFake { p -> method = p.readInt(); name = p.readString() }
        clientWith(fake).sdkNextPathName("вул. Садова")
        assertEquals(HelperBinderProtocol.SDK_NAVI_NEXT_PATH_NAME, method)
        assertEquals("вул. Садова", name)
    }

    @Test
    fun `sdkRestRoute writes hour, minute and a long mileage`() = runBlocking {
        var method = -1; var hour = -1; var minute = -1; var mileage = -1L
        val fake = CapturingFake { p ->
            method = p.readInt(); hour = p.readInt(); minute = p.readInt(); mileage = p.readLong()
        }
        clientWith(fake).sdkRestRoute(1, 45, 4_294_967_294L)
        assertEquals(HelperBinderProtocol.SDK_NAVI_REST_ROUTE, method)
        assertEquals(1, hour); assertEquals(45, minute)
        assertEquals(4_294_967_294L, mileage)
    }

    @Test
    fun `sdkCameraGuidance writes type, distance and state`() = runBlocking {
        val seen = mutableListOf<Int>()
        val fake = CapturingFake { p -> repeat(4) { seen += p.readInt() } }
        clientWith(fake).sdkCameraGuidance(1, 0, 1)
        assertEquals(listOf(HelperBinderProtocol.SDK_NAVI_CAMERA_GUIDANCE, 1, 0, 1), seen)
    }

    // ---- status semantics ------------------------------------------------------------------

    @Test
    fun `an SDK failure code is forwarded, not collapsed`() = runBlocking {
        val busy = CapturingFake(status = -2147482647)
        assertEquals(-2147482647, clientWith(busy).sdkNaviStatus(2))
        assertFalse(HelperClient.sdkAccepted(-2147482647))
    }

    @Test
    fun `an old daemon that rejects the verb yields null`() = runBlocking {
        val client = clientWith(oldDaemon)
        assertNull(client.sdkSetInt(1, 1, 1))
        assertNull(client.sdkNaviStatus(2))
        assertFalse(HelperClient.sdkAccepted(null))
    }

    @Test
    fun `sdkAccepted is true only for the SDK success code`() {
        assertTrue(HelperClient.sdkAccepted(0))
        assertFalse(HelperClient.sdkAccepted(1))       // the raw-autoservice "real action" code
        assertFalse(HelperClient.sdkAccepted(-1))      // daemon reflection failure
        assertFalse(HelperClient.sdkAccepted(-3))      // no system context
        assertFalse(HelperClient.sdkAccepted(-4))      // malformed request
        assertFalse(HelperClient.sdkAccepted(null))    // daemon unreachable / outdated
    }
}
