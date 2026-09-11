package com.bydmate.app.navdata.waze

/**
 * The fixed-length bit set openbyd uses for a Waze arrow shape (`ij0`): a 15x15 grid of
 * foreground/background cells, read row by row, packed into longs.
 *
 * Bit *i* lives in `words[i / 64]` at position `63 - (i % 64)` - the high end of the word, not the
 * low end. That ordering is load-bearing: the 45 reference signatures are strings of '0'/'1' in
 * this exact order, and the shifted-variant search in [ArrowSignature] indexes cells as
 * `row * 15 + column`. Two grids compare by Hamming distance, which for a packed bit set is the
 * population count of the XOR.
 *
 * Instances are values: [equals] and [hashCode] are content-based so a grid can key a map, which is
 * how the table in [WazeArrowTable] is looked up for an exact hit before any distance search runs.
 */
class BitGrid225(val length: Int = SIZE) {

    private val words = LongArray((length + 63) / 64)

    operator fun get(index: Int): Boolean {
        if (index < 0 || index >= length) return false
        return (words[index / 64] ushr (63 - (index % 64))) and 1L == 1L
    }

    fun set(index: Int) {
        if (index < 0 || index >= length) return
        words[index / 64] = words[index / 64] or (1L shl (63 - (index % 64)))
    }

    /**
     * Number of differing cells, or 0 when the lengths do not match - the donor logs and returns 0
     * there as well, and every caller compares equal-length grids.
     */
    fun hamming(other: BitGrid225): Int {
        if (length != other.length) return 0
        var total = 0
        for (i in words.indices) total += java.lang.Long.bitCount(words[i] xor other.words[i])
        return total
    }

    /** The '0'/'1' rendering [parse] accepts. Shape bits only - never pixels, text or colours. */
    fun asString(): String {
        val out = StringBuilder(length)
        for (i in 0 until length) out.append(if (this[i]) '1' else '0')
        return out.toString()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BitGrid225) return false
        return length == other.length && words.contentEquals(other.words)
    }

    override fun hashCode(): Int = words.contentHashCode() * 31 + length

    override fun toString(): String = asString()

    companion object {
        /** Cells per row/column of the grid. */
        const val SIDE = 15

        /** Total cells, and the length of every signature in the reference table. */
        const val SIZE = SIDE * SIDE

        /** Reads a '0'/'1' string; any character other than '1' counts as a clear cell. */
        fun parse(bits: String): BitGrid225 = BitGrid225(bits.length).apply {
            for (i in bits.indices) if (bits[i] == '1') set(i)
        }
    }
}
