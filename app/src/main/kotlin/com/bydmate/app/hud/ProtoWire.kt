package com.bydmate.app.hud

import java.io.ByteArrayOutputStream

/** Minimal protobuf wire writer shared by the HUD encoders (varint, length-delimited,
 *  fixed64 doubles) plus the gateway's outer envelope: every event payload is the inner
 *  message wrapped as field 1 of an outer message. */
internal object ProtoWire {
    fun varint(out: ByteArrayOutputStream, fieldNo: Int, value: Long) {
        writeVarint(out, (fieldNo.toLong() shl 3) or 0L)
        writeVarint(out, value)
    }

    fun bytes(out: ByteArrayOutputStream, fieldNo: Int, bytes: ByteArray) {
        writeVarint(out, (fieldNo.toLong() shl 3) or 2L)
        writeVarint(out, bytes.size.toLong())
        out.write(bytes)
    }

    fun string(out: ByteArrayOutputStream, fieldNo: Int, value: String) =
        bytes(out, fieldNo, value.toByteArray(Charsets.UTF_8))

    fun double(out: ByteArrayOutputStream, fieldNo: Int, value: Double) {
        writeVarint(out, (fieldNo.toLong() shl 3) or 1L)
        val bits = value.toRawBits()
        repeat(8) { i -> out.write(((bits ushr (8 * i)) and 0xFF).toInt()) }
    }

    /** Outer envelope: field 1, length-delimited. */
    fun wrap(inner: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(inner.size + 8)
        out.write(0x0A)
        writeVarint(out, inner.size.toLong())
        out.write(inner)
        return out.toByteArray()
    }

    inline fun message(build: (ByteArrayOutputStream) -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        build(out)
        return out.toByteArray()
    }

    fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            if ((v and 0x7F.inv().toLong()) == 0L) {
                out.write(v.toInt())
                return
            }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }
}
