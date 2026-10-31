package com.bydmate.app.adas

import com.bydmate.app.data.vehicle.BatchReadItem

/**
 * What the car's own driver assistance shows at one moment, read only (dev 1007 instrument,
 * dev 1038 ADAS; ids from the 2026-10-24 dump, meanings from the 2026-10-25 recording,
 * docs/investigations/tang-l-fidrec-20261025-analysis.md §3.7):
 * - [signKph]: the speed sign the camera recognised (`INSTRUMENT_TRAFFIC_SIGN_IDENTIFY_VALUE`,
 *   255 = none);
 * - [slaKph]: the limit the speed-limit assist uses (`ADAS_SLA_OUTPUT_SPEED_LIMIT`, coded
 *   limit / 5 + 1), [offsetKph] its allowance;
 * - [accMode], [cruiseKph]: adaptive cruise mode and set speed; [ica], [noaGray]: lane assist
 *   state and NOA availability, raw (their values are not decoded yet).
 * Null = the car did not answer or reported "none".
 */
data class AdasCarReading(
    val signKph: Int? = null,
    val slaKph: Int? = null,
    val offsetKph: Int? = null,
    val accMode: Int? = null,
    val cruiseKph: Int? = null,
    val ica: Int? = null,
    val noaGray: Int? = null,
) {
    /** The camera's limit: the sign, or the assist's limit when only that answered. */
    val carLimitKph: Int? get() = signKph ?: slaKph

    fun describe(): String =
        "sign=${signKph ?: "-"} sla=${slaKph ?: "-"} offset=${offsetKph ?: "-"} acc=${accMode ?: "-"} " +
            "cruise=${cruiseKph ?: "-"} ica=${ica ?: "-"} noaGray=${noaGray ?: "-"}"

    companion object {
        private const val DEV_INSTRUMENT = 1007
        private const val DEV_ADAS = 1038
        private const val TX_INT = 5

        /** In read order; [decode] takes the replies in the same order. */
        val ITEMS: List<BatchReadItem> = listOf(
            BatchReadItem(TX_INT, DEV_INSTRUMENT, 597688336), // INSTRUMENT_TRAFFIC_SIGN_IDENTIFY_VALUE
            BatchReadItem(TX_INT, DEV_ADAS, 760217632),       // ADAS_SLA_OUTPUT_SPEED_LIMIT
            BatchReadItem(TX_INT, DEV_ADAS, 535838752),       // ADAS_SPEED_LIMIT_ASSIST_OFFSET_KPH
            BatchReadItem(TX_INT, DEV_ADAS, 852492340),       // ADAS_ACC_MODE
            BatchReadItem(TX_INT, DEV_INSTRUMENT, 597688344), // INSTRUMENT_ACC_CRUISE_SPEED
            BatchReadItem(TX_INT, DEV_ADAS, 828375084),       // ADAS_TJA_ICA_STATE
            BatchReadItem(TX_INT, DEV_ADAS, 535826458),       // ADAS_NOA_GRAY_STATE
        )

        private const val NO_SIGN = 255
        private const val SLA_STEP_KPH = 5
        private const val SLA_MAX_CODE = 40
        private const val MAX_KPH = 200

        fun decode(replies: List<Pair<Int, Int>>): AdasCarReading {
            fun value(i: Int): Int? = replies.getOrNull(i)?.takeIf { it.first == 0 }?.second
            return AdasCarReading(
                signKph = value(0)?.takeIf { it in 1..MAX_KPH && it != NO_SIGN },
                slaKph = value(1)?.takeIf { it in 2..SLA_MAX_CODE }?.let { (it - 1) * SLA_STEP_KPH },
                offsetKph = value(2),
                accMode = value(3),
                cruiseKph = value(4)?.takeIf { it in 1..MAX_KPH },
                ica = value(5),
                noaGray = value(6),
            )
        }
    }
}

/**
 * A drive's comparison of Waze's limit with the camera's, one count per sample while moving:
 * both known and equal, both known and different (with the pairs), only Waze, only the car.
 * Its summary is the shadow log's last line of the drive.
 */
class AdasShadowStats {
    var samples = 0
        private set
    var agree = 0
        private set
    var differ = 0
        private set
    var wazeOnly = 0
        private set
    var carOnly = 0
        private set
    private val pairs = HashMap<Pair<Int, Int>, Int>()

    fun add(wazeKph: Int?, carKph: Int?) {
        samples++
        when {
            wazeKph != null && carKph != null && wazeKph == carKph -> agree++
            wazeKph != null && carKph != null -> {
                differ++
                pairs.merge(wazeKph to carKph, 1, Int::plus)
            }
            wazeKph != null -> wazeOnly++
            carKph != null -> carOnly++
        }
    }

    fun summary(): String {
        val both = agree + differ
        val agreePct = if (both > 0) agree * PERCENT / both else 0
        val top = pairs.entries.sortedByDescending { it.value }.take(TOP_PAIRS)
            .joinToString(" ") { (p, n) -> "${p.first}/${p.second}x$n" }
        return "samples=$samples both=$both agree=$agree ($agreePct%) differ=$differ " +
            "wazeOnly=$wazeOnly carOnly=$carOnly topDiffer(waze/car)=[${top.ifEmpty { "-" }}]"
    }

    private companion object {
        const val PERCENT = 100
        const val TOP_PAIRS = 6
    }
}
