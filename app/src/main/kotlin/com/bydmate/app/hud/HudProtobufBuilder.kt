package com.bydmate.app.hud

import java.io.ByteArrayOutputStream

/** Hand-rolled protobuf encoder for the BYD HUD frame (discope reference, donor stage 6).
 *  Field ORDER inside the inner message is significant for the HUD firmware:
 *  f2 -> f6 -> f7 -> f8 -> f9 -> f10 -> f11 -> f16 -> f26 -> f28 -> f33.
 *  f7 carries the speed-limit sign PNG and f6 switches to 6 while it is present: byd-hud
 *  reads f7 as a lane band instead, but the field says otherwise - the glass draws nothing
 *  from the f11 number alone (v3.11.6 regression, both Sea Lion 07 and Leopard 3), while
 *  the v3.11.5 PNG rendered on both. f5 (lane count) and f29 (lane markup) are the rest of
 *  the lane bank and stay reserved until a real lane source exists.
 *  Never emit f3/f4/f12/f17/f18/f21..f25/f30/f31 (verified to glitch the HUD).
 *  Outer wrapper: 0x0A + varint(len) + inner bytes. */
object HudProtobufBuilder {

    const val MAX_PAYLOAD_BYTES = 65536
    const val MAX_ROAD_CHARS = 200

    /** Below 11 m the glass glitches the distance readout, so byd-hud
     *  (HudDisplayPolicy) reports 0..10 m as 11 m. */
    private const val MIN_DISTANCE_METERS = 11

    /** GAODE maneuver -> f28, the animated chevron the glass draws natively.
     *  Real enum (byd-hud `GMapsDirectManeuverMap.nativeFor`, field-tested donor):
     *  1=left, 2=right, 3=slight left, 5=slight right, 7=U-turn left, 8=U-turn right,
     *  11=straight, 99=blank. Values outside the enum render as a phantom left chevron,
     *  so anything without a glyph (roundabouts, destination, waypoints) is sent as 99.
     *  The earlier discope-derived table used a different, wrong enum: it sent 1 for the
     *  destination and 0 for roundabouts, which the glass drew as an animated left turn
     *  (issue #94). */
    fun gaodeToF28(gaode: Int): Int = when (gaode) {
        // No maneuver known (expired or unmapped): raw 0 clears the arrow on the glass
        // (donor buildNew). Mapping it to "straight" left a passed turn hanging there.
        0 -> 0
        1 -> 1
        2 -> 2
        3 -> 3
        4 -> 5
        // No sharp-turn glyph on the glass; byd-hud collapses sharp to the normal turn.
        7 -> 1
        8 -> 2
        9 -> 7
        10 -> 8
        // Straight, solid and dotted.
        11, 12 -> 11
        // Roundabout family (13 enter, 24 exit, 25..34 per-exit, 35..44 left-hand traffic):
        // no glyph exists, direction is carried by the per-exit f8 icon instead.
        in 13..44 -> 99
        // Destination, waypoint, ferry, toll, tunnel, unknown: blank, never a phantom arrow.
        else -> 99
    }

    /** GAODE maneuver -> f28 for the DiLink 150 AR-HUD (Tang L). The car's own map app
     *  sends the Gaode HUD code itself (`PlatformHudImpl.setDirectionIconAndSendData`, table
     *  `k.h.j.e.b`): 1 left, 2 right, 3 slight left, 5 slight right, 7/8 sharp, 9 U-turn,
     *  11 straight, 13 enter / 24 exit roundabout, 45 waypoint, 46 service area, 47 toll,
     *  48 destination, 49 tunnel. Our synthetic per-exit codes collapse to 24; the codes the
     *  table never produces (U-turn right, dotted straight) fall back to their nearest glyph. */
    fun gaodeToArHudId(gaode: Int): Int = when (gaode) {
        0 -> 0
        1, 2, 3 -> gaode
        4 -> 5
        7, 8, 9 -> gaode
        10 -> 9
        11, 12 -> 11
        13 -> 13
        24, in 25..44 -> 24
        in 45..49 -> gaode
        else -> 0
    }

    fun buildFrame(
        maneuverGaode: Int,
        distanceMeters: Int,
        road: String,
        etaString: String?,
        totalDistMeters: Int,
        speedLimit: Int,
        maneuverIconPng: ByteArray?,
        speedSignPng: ByteArray?,
        suppressArrow: Boolean = false,
        dialect: HudDialect = HudDialect.CLASSIC,
        etaSeconds: Int = 0,
        remainString: String? = null,
    ): ByteArray {
        if (dialect == HudDialect.AR_HUD) {
            return buildArHudFrame(maneuverGaode, distanceMeters, road, etaString, totalDistMeters,
                speedLimit, maneuverIconPng, suppressArrow, etaSeconds, remainString)
        }
        val inner = ByteArrayOutputStream()
        // f2 is the constant 2 in every reference guidance frame (donor stage 6,
        // 1779/1779 discope events); only the clear frame carries a counter here.
        writeVarintField(inner, 2, 2L)
        writeVarintField(inner, 6, if (speedSignPng != null) 6L else 1L)
        if (speedSignPng != null) writeBytesField(inner, 7, speedSignPng)
        if (maneuverIconPng != null) writeBytesField(inner, 8, maneuverIconPng)
        writeVarintField(inner, 9, displayDistance(distanceMeters).toLong())
        if (road.isNotEmpty()) writeBytesField(inner, 10, road.toByteArray(Charsets.UTF_8))
        if (speedLimit > 0) writeVarintField(inner, 11, speedLimit.toLong())
        writeVarintField(inner, 16, 2L)
        if (etaString != null) writeBytesField(inner, 26, etaString.toByteArray(Charsets.UTF_8))
        // Camera takeover (donor): f28=0 keeps the HUD from drawing a stale arrow.
        writeVarintField(inner, 28, if (suppressArrow) 0L else gaodeToF28(maneuverGaode).toLong())
        writeFixed64Field(inner, 33, progress(distanceMeters, totalDistMeters).toRawBits())
        return wrap(inner.toByteArray())
    }

    /** DiLink 150 AR-HUD frame, the field set the Tang L map app fills
     *  (`HudRoadInfoNotifyStruct`, non-zero fields only, ascending order like its protobuf
     *  builder): f2 counter 2, f3 route remaining m, f4 route remaining s, f8 maneuver PNG,
     *  f9 distance, f10 next road, f11 + f15 speed limit, f16 navigating status 2, f26 arrival
     *  time, f27 remaining time, f28 Gaode code, f33 progress. No f6/f7 speed-sign trick:
     *  that glass draws its own sign from the limit, and f7 is the lane image there. */
    private fun buildArHudFrame(
        maneuverGaode: Int,
        distanceMeters: Int,
        road: String,
        etaString: String?,
        totalDistMeters: Int,
        speedLimit: Int,
        maneuverIconPng: ByteArray?,
        suppressArrow: Boolean,
        etaSeconds: Int,
        remainString: String?,
        vehicle: HudVehicleState.Fix? = HudVehicleState.fix,
    ): ByteArray {
        val inner = ByteArrayOutputStream()
        val glassCode = if (suppressArrow) 0 else gaodeToArHudId(maneuverGaode)
        writeVarintField(inner, 2, 2L)
        if (totalDistMeters > 0) writeVarintField(inner, 3, totalDistMeters.toLong())
        if (etaSeconds > 0) writeVarintField(inner, 4, etaSeconds.toLong())
        if (maneuverIconPng != null) writeBytesField(inner, 8, maneuverIconPng)
        writeVarintField(inner, 9, displayDistance(distanceMeters).toLong())
        if (road.isNotEmpty()) writeBytesField(inner, 10, road.toByteArray(Charsets.UTF_8))
        if (speedLimit > 0) writeVarintField(inner, 11, speedLimit.toLong())
        if (vehicle != null && vehicle.speedKmh > 0) writeVarintField(inner, 12, vehicle.speedKmh.toLong())
        if (speedLimit > 0) writeVarintField(inner, 15, speedLimit.toLong())
        writeVarintField(inner, 16, 2L)
        // Vehicle position block (f19..f22, f30..f32): the factory map and openbyd both send it;
        // an AR glass may refuse guidance without a position to anchor it to.
        if (vehicle != null) {
            writeFixed64Field(inner, 19, vehicle.lon.toRawBits())
            writeFixed64Field(inner, 20, vehicle.lat.toRawBits())
            if (vehicle.speedKmh > 0) writeVarintField(inner, 21, vehicle.speedKmh.toLong())
            if (vehicle.altitudeM != 0) writeVarintField(inner, 22, vehicle.altitudeM.toLong())
        }
        writeBytesField(inner, 24, "[]".toByteArray())
        writeBytesField(inner, 25, "[]".toByteArray())
        if (etaString != null) writeBytesField(inner, 26, etaString.toByteArray(Charsets.UTF_8))
        if (remainString != null) writeBytesField(inner, 27, remainString.toByteArray(Charsets.UTF_8))
        writeVarintField(inner, 28, glassCode.toLong())
        if (vehicle != null) {
            writeBytesField(inner, 30, HudGeometry.guideLine(maneuverGaode, vehicle.lat, vehicle.lon, vehicle.bearing).toByteArray())
            writeBytesField(inner, 31, HudGeometry.guidePoint(distanceMeters, glassCode, vehicle.lat, vehicle.lon, vehicle.bearing).toByteArray())
            writeFixed64Field(inner, 32, vehicle.bearing.toRawBits())
        }
        writeFixed64Field(inner, 33, progress(distanceMeters, totalDistMeters).toRawBits())
        return wrap(inner.toByteArray())
    }

    /** buildFrame with the road cap and the donor's size fallback: past MAX_PAYLOAD_BYTES the
     *  speed-sign PNG (f7) is dropped first; the maneuver icon (f8) is never dropped. */
    fun buildFrameSafe(
        maneuverGaode: Int,
        distanceMeters: Int,
        road: String,
        etaString: String?,
        totalDistMeters: Int,
        speedLimit: Int,
        maneuverIconPng: ByteArray?,
        speedSignPng: ByteArray?,
        suppressArrow: Boolean = false,
        dialect: HudDialect = HudDialect.CLASSIC,
        etaSeconds: Int = 0,
        remainString: String? = null,
    ): ByteArray {
        // The road string is the only unbounded input (a11y screen text); cap it so a
        // corrupted read can never push the frame past MAX_PAYLOAD_BYTES (Codex audit fix 4).
        val safeRoad = if (road.length > MAX_ROAD_CHARS) road.take(MAX_ROAD_CHARS) else road
        val full = buildFrame(maneuverGaode, distanceMeters, safeRoad, etaString,
            totalDistMeters, speedLimit, maneuverIconPng, speedSignPng, suppressArrow,
            dialect, etaSeconds, remainString)
        if (full.size <= MAX_PAYLOAD_BYTES || speedSignPng == null) return full
        return buildFrame(maneuverGaode, distanceMeters, safeRoad, etaString,
            totalDistMeters, speedLimit, maneuverIconPng, speedSignPng = null,
            suppressArrow = suppressArrow, dialect = dialect, etaSeconds = etaSeconds,
            remainString = remainString)
    }

    /** Clear frame. Classic glass: render class 255 + f16=1 wipes the navigation area.
     *  AR-HUD: what the Tang L map sends from `clearAndSendNullData` - every field at its
     *  default, navigating status 1 (idle), counter 2. */
    fun buildClearFrame(counter: Int, dialect: HudDialect = HudDialect.CLASSIC): ByteArray {
        val inner = ByteArrayOutputStream()
        if (dialect == HudDialect.AR_HUD) {
            writeVarintField(inner, 2, 2L)
            writeVarintField(inner, 16, 1L)
            return wrap(inner.toByteArray())
        }
        writeVarintField(inner, 2, counter.toLong())
        writeVarintField(inner, 6, 255L)
        writeVarintField(inner, 16, 1L)
        return wrap(inner.toByteArray())
    }

    /** f9 as the glass wants it: 0..10 m lifted to 11 m, everything else untouched. */
    private fun displayDistance(distanceMeters: Int): Int =
        if (distanceMeters in 0 until MIN_DISTANCE_METERS) MIN_DISTANCE_METERS else distanceMeters

    private fun progress(distanceMeters: Int, totalDistMeters: Int): Double {
        if (totalDistMeters <= 0) return 0.0
        return (1.0 - distanceMeters.toDouble() / totalDistMeters).coerceIn(0.0, 1.0)
    }

    private fun wrap(inner: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(inner.size + 6)
        out.write(0x0A)
        writeVarint(out, inner.size.toLong())
        out.write(inner)
        return out.toByteArray()
    }

    private fun writeVarintField(out: ByteArrayOutputStream, fieldNo: Int, value: Long) {
        writeVarint(out, (fieldNo.toLong() shl 3) or 0L)
        writeVarint(out, value)
    }

    private fun writeBytesField(out: ByteArrayOutputStream, fieldNo: Int, bytes: ByteArray) {
        writeVarint(out, (fieldNo.toLong() shl 3) or 2L)
        writeVarint(out, bytes.size.toLong())
        out.write(bytes)
    }

    private fun writeFixed64Field(out: ByteArrayOutputStream, fieldNo: Int, bits: Long) {
        writeVarint(out, (fieldNo.toLong() shl 3) or 1L)
        repeat(8) { i -> out.write(((bits ushr (8 * i)) and 0xFF).toInt()) }
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            if (v and 0x7F.inv().toLong() == 0L) {
                out.write(v.toInt())
                return
            }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }
}
