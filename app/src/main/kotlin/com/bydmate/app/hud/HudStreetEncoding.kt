package com.bydmate.app.hud

import java.nio.charset.Charset

/**
 * Character encoding for the instrument panel's next-street name.
 *
 * The panel takes a raw byte array and decodes it with whatever charset its firmware was built
 * for; nothing in the write tells us which. The first car test wrote UTF-8 and the panel drew a
 * run of Chinese characters under the arrow — the exact shape of Cyrillic UTF-8 bytes read as
 * GBK. That is a good sign in one way (the bytes reach the glass) and useless in another: the
 * write status is positive for every encoding, so the panel cannot be asked which one it wanted.
 *
 * So it is a setting. [AUTO] applies the only rule that is more than a guess — BYD ships two
 * next-street features, a domestic one and an `_OVERASEA_` one, and a firmware that exposes the
 * domestic feature is a Chinese-market build whose strings are GBK — and the rest let the owner
 * settle it by looking at the panel, one option per attempt.
 */
object HudStreetEncoding {

    const val AUTO = "auto"
    const val UTF8 = "utf8"
    const val GBK = "gbk"
    const val UTF16LE = "utf16le"
    const val UTF16BE = "utf16be"

    /** Every value the setting accepts, in the order the picker shows them. */
    val ALL = listOf(AUTO, UTF8, GBK, UTF16LE, UTF16BE)

    /**
     * Bytes for [text] under [pref], for the feature the panel accepted.
     *
     * [overseasFeature] selects the AUTO branch: the overseas feature belongs to an export
     * firmware and takes UTF-8, the domestic one to a Chinese build and takes GBK. A charset the
     * device does not ship (GBK is present on every Android built for China, but not guaranteed
     * elsewhere) falls back to UTF-8 rather than throwing on a driving car.
     */
    fun encode(text: String, pref: String, overseasFeature: Boolean): Pair<ByteArray, String> {
        val chosen = if (pref == AUTO) (if (overseasFeature) UTF8 else GBK) else pref
        val charset = charsetFor(chosen) ?: return text.toByteArray(Charsets.UTF_8) to "$chosen>utf8"
        return runCatching { text.toByteArray(charset) to chosen }
            .getOrDefault(text.toByteArray(Charsets.UTF_8) to "$chosen>utf8")
    }

    private fun charsetFor(value: String): Charset? = when (value) {
        UTF8 -> Charsets.UTF_8
        GBK -> runCatching { Charset.forName("GBK") }.getOrNull()
        UTF16LE -> Charsets.UTF_16LE
        UTF16BE -> Charsets.UTF_16BE
        else -> null
    }

    /** Cuts on a character boundary so the panel never receives half a code point. */
    fun truncate(text: String, pref: String, overseasFeature: Boolean, maxBytes: Int): Pair<ByteArray, String> {
        var end = text.length
        while (end > 0) {
            val (bytes, name) = encode(text.substring(0, end), pref, overseasFeature)
            if (bytes.size <= maxBytes) return bytes to name
            end--
        }
        return ByteArray(0) to pref
    }

    /** First bytes as hex, for the trip log: the one piece of evidence that survives the drive. */
    fun preview(bytes: ByteArray, count: Int = 8): String =
        bytes.take(count).joinToString(" ") { "%02x".format(it) }
}
