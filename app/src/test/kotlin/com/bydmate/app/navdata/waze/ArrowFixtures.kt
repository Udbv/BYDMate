package com.bydmate.app.navdata.waze

/**
 * Renders a 225-bit reference signature back into pixels, so the classifier can be driven end to
 * end on the JVM without a device or a bitmap.
 *
 * Cell boundaries use the same `(k * side) / 15` integer arithmetic as [ArrowSignature], which is
 * what makes every cell uniformly filled at any output size: a round trip through render/compute
 * has to be exact, otherwise a test failure says nothing about the port.
 */
object ArrowFixtures {

    const val INK = 0xFF000000.toInt()
    const val PAPER = 0xFFFFFFFF.toInt()

    /** Black arrow on white paper, fully opaque - the colours Waze's own drawables reduce to. */
    fun render(bits: String, side: Int, shiftCellsX: Int = 0, shiftCellsY: Int = 0): IntArray {
        val pixels = IntArray(side * side) { PAPER }
        for (row in 0 until BitGrid225.SIDE) {
            val sourceRow = row - shiftCellsY
            if (sourceRow < 0 || sourceRow >= BitGrid225.SIDE) continue
            val top = (row * side) / BitGrid225.SIDE
            val bottom = ((row + 1) * side) / BitGrid225.SIDE
            for (column in 0 until BitGrid225.SIDE) {
                val sourceColumn = column - shiftCellsX
                if (sourceColumn < 0 || sourceColumn >= BitGrid225.SIDE) continue
                if (bits[sourceRow * BitGrid225.SIDE + sourceColumn] != '1') continue
                val left = (column * side) / BitGrid225.SIDE
                val right = ((column + 1) * side) / BitGrid225.SIDE
                for (y in top until bottom) {
                    for (x in left until right) pixels[y * side + x] = INK
                }
            }
        }
        return pixels
    }
}
