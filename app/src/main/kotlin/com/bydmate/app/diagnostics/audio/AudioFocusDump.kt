package com.bydmate.app.diagnostics.audio

/**
 * Cuts `dumpsys audio` down to what the audio channel test (issue #6) needs: the focus stack and
 * every line that mentions ducking. The full dump runs to hundreds of KiB of volume tables and
 * player lists; these few lines are what say who holds focus and whether the framework ducked the
 * music player. Pure, so it runs both in the helper daemon (shell uid, the only side that can
 * read the dump) and in unit tests.
 */
object AudioFocusDump {

    /** Header of the focus stack in MediaFocusControl.dump (Android 8 through 14). */
    private const val STACK_HEADER = "Audio Focus stack entries"

    /** Caps, so a firmware that prints a pathological stack cannot flood the trip log. */
    private const val MAX_STACK_LINES = 16
    private const val MAX_DUCK_LINES = 12
    private const val MAX_LINE = 240

    const val NO_STACK = "(no focus stack in dumpsys audio)"

    /**
     * The focus stack header and its entries (up to the first blank line), then up to
     * [MAX_DUCK_LINES] other lines containing "duck" (PlaybackActivityMonitor's ducked players,
     * "Notify on duck"). Lines are trimmed and capped at [MAX_LINE] characters.
     */
    fun excerpt(dump: String): List<String> {
        val lines = dump.lines()
        val out = ArrayList<String>()
        val headerAt = lines.indexOfFirst { it.contains(STACK_HEADER) }
        val stackRange = if (headerAt < 0) {
            out += NO_STACK
            IntRange.EMPTY
        } else {
            val end = (headerAt + 1 until lines.size).firstOrNull { lines[it].isBlank() } ?: lines.size
            val last = minOf(end, headerAt + 1 + MAX_STACK_LINES)
            for (i in headerAt until last) out += clip(lines[i])
            headerAt until end
        }
        lines.withIndex()
            .filter { (i, line) -> i !in stackRange && line.contains("duck", ignoreCase = true) }
            .take(MAX_DUCK_LINES)
            .forEach { out += clip(it.value) }
        return out
    }

    private fun clip(line: String): String = line.trim().take(MAX_LINE)
}
