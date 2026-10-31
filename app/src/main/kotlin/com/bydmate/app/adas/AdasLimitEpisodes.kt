package com.bydmate.app.adas

/**
 * Who knew a speed limit first, Waze or the car's camera, over a drive (#23, shadow mode).
 *
 * The car keeps the last sign it read until it reads the next one, and a town's name board is not
 * a number: entering a town (50) or leaving it (90) changes the limit without a sign the camera
 * reads, while Waze has it from its map. To weigh sending Waze's limit to the car, every stretch
 * where the two differ is an *episode*, and each is told by who changed first and who gave in:
 * - Waze changed, then the camera came to the same value: Waze knew first ([Side.WAZE] >
 *   [Side.CAR]); its distance and time are how long the car drove on a stale limit;
 * - the camera changed, then Waze followed: Waze was late;
 * - anything else (both changed, one went silent, still apart at the end).
 * Distance is integrated from the speed, so a standstill adds nothing. Positions are never kept.
 */
class AdasLimitEpisodes(private val log: (String) -> Unit = {}) {

    enum class Side { WAZE, CAR, BOTH, LOST }

    private class Episode(val startMs: Long, val startedBy: Side, val waze: Int, val car: Int) {
        var meters = 0.0
    }

    private class Tally {
        var count = 0
        var meters = 0.0
        var ms = 0L
    }

    private var lastMs: Long? = null
    private var lastWaze: Int? = null
    private var lastCar: Int? = null
    private var open: Episode? = null

    /** Metres driven with the two agreeing, differing, only Waze knowing, only the car knowing. */
    private val meters = DoubleArray(BUCKETS)
    private val tallies = LinkedHashMap<String, Tally>()

    fun add(nowMs: Long, speedKph: Int?, waze: Int?, car: Int?) {
        val meters = lastMs?.let { last ->
            val dt = (nowMs - last).coerceIn(0L, MAX_GAP_MS)
            (speedKph ?: 0).coerceAtLeast(0) / KPH_PER_MPS * dt / MS_PER_S
        } ?: 0.0
        bucket(waze, car)?.let { this.meters[it] += meters }
        open?.let { it.meters += meters }

        val wazeChanged = waze != lastWaze
        val carChanged = car != lastCar
        when {
            waze == null || car == null -> open?.let { close(it, Side.LOST, nowMs) }
            waze != car -> if (open == null) {
                open = Episode(nowMs, sideOf(wazeChanged, carChanged), waze, car)
            }
            else -> open?.let { close(it, sideOf(wazeChanged, carChanged), nowMs) }
        }
        lastMs = nowMs
        lastWaze = waze
        lastCar = car
    }

    /** The drive is over: an episode still open ends here, unresolved. */
    fun finish(nowMs: Long) {
        open?.let { close(it, Side.LOST, nowMs) }
    }

    fun summary(): String {
        fun k(i: Int) = fmt(meters[i] / M_PER_KM)
        val dist = "km agree=${k(AGREE)} differ=${k(DIFFER)} wazeOnly=${k(WAZE_ONLY)} carOnly=${k(CAR_ONLY)}"
        val eps = tallies.entries.joinToString(" ") { (k, t) ->
            "$k=${t.count}(${fmt(t.meters / M_PER_KM)}km,${t.ms / MS_PER_S}s)"
        }
        return "$dist episodes[${eps.ifEmpty { "-" }}]"
    }

    private fun close(e: Episode, endedBy: Side, nowMs: Long) {
        val key = when {
            e.startedBy == Side.WAZE && endedBy == Side.CAR -> "wazeFirst"
            e.startedBy == Side.CAR && endedBy == Side.WAZE -> "carFirst"
            else -> "${e.startedBy.name.lowercase()}>${endedBy.name.lowercase()}"
        }
        val t = tallies.getOrPut(key) { Tally() }
        t.count++
        t.meters += e.meters
        t.ms += nowMs - e.startMs
        log(
            "limit episode $key waze=${e.waze} car=${e.car} " +
                "lasted ${fmt(e.meters / M_PER_KM)}km ${(nowMs - e.startMs) / MS_PER_S}s",
        )
        open = null
    }

    private fun sideOf(wazeChanged: Boolean, carChanged: Boolean): Side = when {
        wazeChanged && !carChanged -> Side.WAZE
        carChanged && !wazeChanged -> Side.CAR
        else -> Side.BOTH
    }

    private fun bucket(waze: Int?, car: Int?): Int? = when {
        waze != null && car != null -> if (waze == car) AGREE else DIFFER
        waze != null -> WAZE_ONLY
        car != null -> CAR_ONLY
        else -> null
    }

    private fun fmt(v: Double) = "%.1f".format(java.util.Locale.ROOT, v)

    private companion object {
        const val AGREE = 0
        const val DIFFER = 1
        const val WAZE_ONLY = 2
        const val CAR_ONLY = 3
        const val BUCKETS = 4
        const val KPH_PER_MPS = 3.6
        const val MS_PER_S = 1000L
        const val M_PER_KM = 1000.0
        /** A longer gap between readings (the car did not answer) adds no distance past this. */
        const val MAX_GAP_MS = 10_000L
    }
}
