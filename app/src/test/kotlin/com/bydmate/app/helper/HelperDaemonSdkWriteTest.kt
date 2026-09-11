package com.bydmate.app.helper

import android.os.Parcel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Parcel layout and dispatch of TX_SDK_SET / TX_SDK_NAVI. The reflective SDK call itself is
 * replaced by a recording seam — no BYD device class exists off the car — so what is asserted
 * here is exactly what the daemon would hand the SDK.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HelperDaemonSdkWriteTest {

    private data class SetCall(val sdkDev: Int, val kind: Int, val fids: IntArray, val value: Any)

    private class RecordingSet(private val status: Int = 0) : SdkSetInvoker {
        val calls = mutableListOf<SetCall>()
        override fun invoke(sdkDev: Int, kind: Int, fids: IntArray, value: Any): Int {
            calls += SetCall(sdkDev, kind, fids, value)
            return status
        }
    }

    private class RecordingNavi(private val status: Int = 0) : SdkNaviInvoker {
        val calls = mutableListOf<Pair<Int, List<Any>>>()
        override fun invoke(method: Int, args: List<Any>): Int {
            calls += method to args
            return status
        }
    }

    private fun request(write: (Parcel) -> Unit): Parcel =
        Parcel.obtain().also { write(it); it.setDataPosition(0) }

    /** Runs a handler and returns the reply status, asserting the trailing 0 is there. */
    private fun runSet(data: Parcel, invoker: SdkSetInvoker): Int {
        val reply = Parcel.obtain()
        try {
            handleSdkSet(data, reply, invoker)
            reply.setDataPosition(0)
            val status = reply.readInt()
            assertEquals(0, reply.readInt())
            assertEquals(0, reply.dataAvail())
            return status
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    private fun runNavi(data: Parcel, invoker: SdkNaviInvoker): Int {
        val reply = Parcel.obtain()
        try {
            handleSdkNavi(data, reply, invoker)
            reply.setDataPosition(0)
            val status = reply.readInt()
            assertEquals(0, reply.readInt())
            assertEquals(0, reply.dataAvail())
            return status
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    // ---- device / value tables -------------------------------------------------------------

    @Test
    fun `each device selector names its SDK class and nothing else does`() {
        assertEquals(
            "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice",
            sdkDeviceClassName(HelperBinderProtocol.SDK_DEV_INSTRUMENT),
        )
        assertEquals(
            "android.hardware.bydauto.setting.BYDAutoSettingDevice",
            sdkDeviceClassName(HelperBinderProtocol.SDK_DEV_SETTING),
        )
        assertEquals(
            "android.hardware.bydauto.statistic.BYDAutoStatisticDevice",
            sdkDeviceClassName(HelperBinderProtocol.SDK_DEV_STATISTIC),
        )
        assertNull(sdkDeviceClassName(0))
        assertNull(sdkDeviceClassName(4))
    }

    @Test
    fun `the legacy bytes device number still resolves to the instrument class`() {
        assertEquals(HelperBinderProtocol.SDK_DEV_INSTRUMENT, sdkDevForAutoserviceDev(1007))
        assertNull(sdkDevForAutoserviceDev(1014))
        assertEquals(
            "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice",
            deviceClassFor(1007),
        )
        assertNull(deviceClassFor(1014))
    }

    @Test
    fun `writeBytes on an unknown device never reaches the SDK`() {
        assertEquals(-1, writeBytesViaSdk(null, 1014, 1, ByteArray(0)))
    }

    @Test
    fun `no system context is reported as -3, not as a reflection failure`() {
        assertEquals(
            -3,
            sdkSetViaReflection(
                null, HelperBinderProtocol.SDK_DEV_INSTRUMENT,
                HelperBinderProtocol.SDK_KIND_INT, intArrayOf(1), 2,
            ),
        )
        assertEquals(-3, sdkNaviViaReflection(null, HelperBinderProtocol.SDK_NAVI_STATUS, listOf(2)))
        assertEquals(-3, writeBytesViaSdk(null, 1007, 1, ByteArray(0)))
    }

    // ---- TX_SDK_SET ------------------------------------------------------------------------

    @Test
    fun `int write carries device, fid and value`() {
        val rec = RecordingSet(status = 0)
        val data = request {
            it.writeInt(HelperBinderProtocol.SDK_DEV_SETTING)
            it.writeInt(HelperBinderProtocol.SDK_KIND_INT)
            it.writeInt(0x4C10E015)
            it.writeInt(3)
        }
        assertEquals(0, runSet(data, rec))
        val call = rec.calls.single()
        assertEquals(HelperBinderProtocol.SDK_DEV_SETTING, call.sdkDev)
        assertEquals(HelperBinderProtocol.SDK_KIND_INT, call.kind)
        assertArrayEquals(intArrayOf(0x4C10E015), call.fids)
        assertEquals(3, call.value)
    }

    @Test
    fun `double write carries the double value`() {
        val rec = RecordingSet()
        val data = request {
            it.writeInt(HelperBinderProtocol.SDK_DEV_STATISTIC)
            it.writeInt(HelperBinderProtocol.SDK_KIND_DOUBLE)
            it.writeInt(754057264)
            it.writeDouble(0.0)
        }
        runSet(data, rec)
        val call = rec.calls.single()
        assertEquals(HelperBinderProtocol.SDK_DEV_STATISTIC, call.sdkDev)
        assertArrayEquals(intArrayOf(754057264), call.fids)
        assertEquals(0.0, call.value as Double, 0.0)
    }

    @Test
    fun `bytes write carries the buffer verbatim`() {
        val rec = RecordingSet()
        val bytes = "Садова".toByteArray(Charsets.UTF_16LE)
        val data = request {
            it.writeInt(HelperBinderProtocol.SDK_DEV_INSTRUMENT)
            it.writeInt(HelperBinderProtocol.SDK_KIND_BYTES)
            it.writeInt(0x43FA1008)
            it.writeByteArray(bytes)
        }
        runSet(data, rec)
        val call = rec.calls.single()
        assertArrayEquals(intArrayOf(0x43FA1008), call.fids)
        assertArrayEquals(bytes, call.value as ByteArray)
    }

    @Test
    fun `int array write carries both arrays in one call`() {
        val rec = RecordingSet()
        val fids = IntArray(25) { 0x198020D8 + it }
        val values = IntArray(25) { it }
        val data = request {
            it.writeInt(HelperBinderProtocol.SDK_DEV_INSTRUMENT)
            it.writeInt(HelperBinderProtocol.SDK_KIND_INT_ARRAY)
            it.writeIntArray(fids)
            it.writeIntArray(values)
        }
        runSet(data, rec)
        val call = rec.calls.single()
        assertEquals(HelperBinderProtocol.SDK_KIND_INT_ARRAY, call.kind)
        assertArrayEquals(fids, call.fids)
        assertArrayEquals(values, call.value as IntArray)
    }

    @Test
    fun `the SDK status is forwarded unchanged`() {
        val rec = RecordingSet(status = -2147482647)   // BUSY
        val data = request {
            it.writeInt(HelperBinderProtocol.SDK_DEV_INSTRUMENT)
            it.writeInt(HelperBinderProtocol.SDK_KIND_INT)
            it.writeInt(1); it.writeInt(1)
        }
        assertEquals(-2147482647, runSet(data, rec))
    }

    @Test
    fun `int array length mismatch is refused with -4 before the SDK`() {
        val rec = RecordingSet()
        val data = request {
            it.writeInt(HelperBinderProtocol.SDK_DEV_INSTRUMENT)
            it.writeInt(HelperBinderProtocol.SDK_KIND_INT_ARRAY)
            it.writeIntArray(intArrayOf(1, 2, 3))
            it.writeIntArray(intArrayOf(1, 2))
        }
        assertEquals(-4, runSet(data, rec))
        assertEquals(emptyList<SetCall>(), rec.calls)
    }

    @Test
    fun `oversize int array is refused with -4`() {
        val rec = RecordingSet()
        val n = HelperBinderProtocol.MAX_SDK_ARRAY + 1
        val data = request {
            it.writeInt(HelperBinderProtocol.SDK_DEV_INSTRUMENT)
            it.writeInt(HelperBinderProtocol.SDK_KIND_INT_ARRAY)
            it.writeIntArray(IntArray(n)); it.writeIntArray(IntArray(n))
        }
        assertEquals(-4, runSet(data, rec))
        assertEquals(emptyList<SetCall>(), rec.calls)
    }

    @Test
    fun `empty int array is refused with -4`() {
        val rec = RecordingSet()
        val data = request {
            it.writeInt(HelperBinderProtocol.SDK_DEV_INSTRUMENT)
            it.writeInt(HelperBinderProtocol.SDK_KIND_INT_ARRAY)
            it.writeIntArray(IntArray(0)); it.writeIntArray(IntArray(0))
        }
        assertEquals(-4, runSet(data, rec))
        assertEquals(emptyList<SetCall>(), rec.calls)
    }

    @Test
    fun `unknown device selector and unknown kind are refused with -4`() {
        val rec = RecordingSet()
        val badDev = request {
            it.writeInt(9); it.writeInt(HelperBinderProtocol.SDK_KIND_INT); it.writeInt(1); it.writeInt(1)
        }
        assertEquals(-4, runSet(badDev, rec))
        val badKind = request {
            it.writeInt(HelperBinderProtocol.SDK_DEV_INSTRUMENT); it.writeInt(7)
        }
        assertEquals(-4, runSet(badKind, rec))
        assertEquals(emptyList<SetCall>(), rec.calls)
    }

    @Test
    fun `a null byte array is refused with -4`() {
        val rec = RecordingSet()
        val data = request {
            it.writeInt(HelperBinderProtocol.SDK_DEV_INSTRUMENT)
            it.writeInt(HelperBinderProtocol.SDK_KIND_BYTES)
            it.writeInt(0x43FA1008)
            it.writeByteArray(null)
        }
        assertEquals(-4, runSet(data, rec))
        assertEquals(emptyList<SetCall>(), rec.calls)
    }

    // ---- TX_SDK_NAVI -----------------------------------------------------------------------

    @Test
    fun `navi status carries one int`() {
        val rec = RecordingNavi()
        val data = request { it.writeInt(HelperBinderProtocol.SDK_NAVI_STATUS); it.writeInt(2) }
        assertEquals(0, runNavi(data, rec))
        assertEquals(HelperBinderProtocol.SDK_NAVI_STATUS to listOf<Any>(2), rec.calls.single())
    }

    @Test
    fun `simple guidance carries icon and distance in order`() {
        val rec = RecordingNavi()
        val data = request {
            it.writeInt(HelperBinderProtocol.SDK_NAVI_SIMPLE_GUIDANCE); it.writeInt(11); it.writeInt(350)
        }
        runNavi(data, rec)
        assertEquals(
            HelperBinderProtocol.SDK_NAVI_SIMPLE_GUIDANCE to listOf<Any>(11, 350),
            rec.calls.single(),
        )
    }

    @Test
    fun `next path name carries the string`() {
        val rec = RecordingNavi()
        val data = request {
            it.writeInt(HelperBinderProtocol.SDK_NAVI_NEXT_PATH_NAME); it.writeString("вул. Садова")
        }
        runNavi(data, rec)
        assertEquals(
            HelperBinderProtocol.SDK_NAVI_NEXT_PATH_NAME to listOf<Any>("вул. Садова"),
            rec.calls.single(),
        )
    }

    @Test
    fun `rest route carries hour, minute and a long mileage`() {
        val rec = RecordingNavi()
        val data = request {
            it.writeInt(HelperBinderProtocol.SDK_NAVI_REST_ROUTE)
            it.writeInt(1); it.writeInt(45); it.writeLong(4_294_967_294L)
        }
        runNavi(data, rec)
        assertEquals(
            HelperBinderProtocol.SDK_NAVI_REST_ROUTE to listOf<Any>(1, 45, 4_294_967_294L),
            rec.calls.single(),
        )
    }

    @Test
    fun `camera guidance carries type, distance and state`() {
        val rec = RecordingNavi()
        val data = request {
            it.writeInt(HelperBinderProtocol.SDK_NAVI_CAMERA_GUIDANCE)
            it.writeInt(1); it.writeInt(0); it.writeInt(1)
        }
        runNavi(data, rec)
        assertEquals(
            HelperBinderProtocol.SDK_NAVI_CAMERA_GUIDANCE to listOf<Any>(1, 0, 1),
            rec.calls.single(),
        )
    }

    @Test
    fun `unknown navi method is refused with -4`() {
        val rec = RecordingNavi()
        val data = request { it.writeInt(99); it.writeInt(1) }
        assertEquals(-4, runNavi(data, rec))
        assertEquals(emptyList<Pair<Int, List<Any>>>(), rec.calls)
    }

    @Test
    fun `a null path name is refused with -4`() {
        val rec = RecordingNavi()
        val data = request {
            it.writeInt(HelperBinderProtocol.SDK_NAVI_NEXT_PATH_NAME); it.writeString(null)
        }
        assertEquals(-4, runNavi(data, rec))
        assertEquals(emptyList<Pair<Int, List<Any>>>(), rec.calls)
    }
}
