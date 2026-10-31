package com.bydmate.app.adas

import com.bydmate.app.navdata.NavGuidanceHub
import com.bydmate.app.navdata.WazeReportsReader
import java.io.ByteArrayOutputStream

/**
 * One message BYDMate would offer the driving computer, built and never sent: the SOME/IP topic,
 * the message name, its fields in words and its protobuf bytes as the factory map's descriptors
 * lay them out (docs/investigations/noa-someip-contract.md §2). Shadow mode logs these next to
 * what the car's own ADAS reports, so the Waze side can be judged without touching the planner.
 */
data class ShadowFrame(val topic: Long, val name: String, val fields: String, val bytes: ByteArray) {
    val hex: String get() = bytes.joinToString("") { "%02x".format(it) }

    override fun equals(other: Any?): Boolean =
        other is ShadowFrame && topic == other.topic && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * topic.hashCode() + bytes.contentHashCode()
}

/**
 * The map -> ADAS messages a Waze source could fill, from the guidance snapshot:
 * - 0x0007/0x8001 `NavigationStatus_LinkInfoNotify`: navigating (8) or stopped (9), the map's
 *   own "map state" values (uncertain, §3);
 * - 0x0007/0x8003 `TrafficInfoNotify`: the road's speed limit, the camera ahead and the distance
 *   and time to the destination; Waze names no camera speed, so that field stays 0;
 * - 0x8202/0x800B `naviActionAndCamera`: the next manoeuvre (AutoNavi icon code, distance) and
 *   the camera. Camera type 1 = "a camera" is our placeholder: the AutoNavi enum is unknown.
 * Speed bumps and crossings (0x8202/0x8005 `roadFacilities`) need Waze's alert banner read first.
 */
object AdasShadowFrames {

    const val TOPIC_NAVI_STATUS = 0x0004000700078001L
    const val TOPIC_TRAFFIC_INFO = 0x0004000700078003L
    const val TOPIC_ACTION_CAMERA = 0x000482028202800BL

    const val NAVI_ACTIVE = 8
    const val NAVI_STOPPED = 9
    private const val CAMERA_PLACEHOLDER_TYPE = 1

    fun of(snap: NavGuidanceHub.Snapshot): List<ShadowFrame> {
        val camera = snap.cameraKind == WazeReportsReader.Kind.CAMERA && snap.cameraDistanceMeters > 0
        val cameraDist = if (camera) snap.cameraDistanceMeters else 0
        if (!snap.active) return listOf(naviStatus(NAVI_STOPPED))
        return listOf(
            naviStatus(NAVI_ACTIVE),
            trafficInfo(snap.speedLimit, cameraDist, snap.totalDistMeters, snap.etaSeconds),
            actionAndCamera(snap.maneuverGaode, snap.distanceMeters, cameraDist),
        )
    }

    fun naviStatus(status: Int) = ShadowFrame(
        TOPIC_NAVI_STATUS, "NavigationStatus_LinkInfoNotify", "NavigationStatus=$status",
        wrap(Proto().varint(4, status.toLong()).bytes()),
    )

    fun trafficInfo(limitKph: Int, cameraDist: Int, totalDist: Int = 0, etaSeconds: Int = 0) = ShadowFrame(
        TOPIC_TRAFFIC_INFO, "TrafficInfoNotify",
        "SpdLmtSpeedValue=$limitKph SpdLmtEleEyeDist=$cameraDist DistToDsttn=$totalDist TimeToDsttn=$etaSeconds",
        wrap(
            Proto()
                .varint(8, limitKph.toLong())
                .varint(10, cameraDist.toLong())
                .varint(17, totalDist.toLong())
                .varint(18, etaSeconds.toLong())
                .bytes(),
        ),
    )

    /** [cameraDist] > 0 puts a camera there ([CAMERA_PLACEHOLDER_TYPE]); 0 means none. */
    fun actionAndCamera(icon: Int, distance: Int, cameraDist: Int): ShadowFrame {
        val cameraType = if (cameraDist > 0) CAMERA_PLACEHOLDER_TYPE else 0
        return ShadowFrame(
            TOPIC_ACTION_CAMERA, "naviActionAndCamera",
            "iconType=$icon distance=$distance cameraType=$cameraType cameraDistance=$cameraDist",
            wrap(
                Proto()
                    .varint(1, icon.toLong())
                    .varint(4, distance.toLong())
                    .varint(5, cameraType.toLong())
                    .varint(6, cameraDist.toLong())
                    .bytes(),
            ),
        )
    }

    /** Every `xNotify` wraps its struct as field 1. */
    private fun wrap(inner: ByteArray): ByteArray = Proto().message(1, inner).bytes()
}

/** A minimal proto3 writer: zero values are left out, as proto3 encoders do. */
internal class Proto {
    private val out = ByteArrayOutputStream()

    fun varint(field: Int, value: Long): Proto {
        if (value == 0L) return this
        tag(field, WIRE_VARINT)
        raw(value)
        return this
    }

    fun message(field: Int, bytes: ByteArray): Proto {
        tag(field, WIRE_LEN)
        raw(bytes.size.toLong())
        out.write(bytes)
        return this
    }

    fun bytes(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wire: Int) = raw(((field shl 3) or wire).toLong())

    private fun raw(value: Long) {
        var v = value
        while (v and SEVEN_BITS.inv() != 0L) {
            out.write(((v and SEVEN_BITS) or CONTINUE).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    private companion object {
        const val WIRE_VARINT = 0
        const val WIRE_LEN = 2
        const val SEVEN_BITS = 0x7FL
        const val CONTINUE = 0x80L
    }
}
