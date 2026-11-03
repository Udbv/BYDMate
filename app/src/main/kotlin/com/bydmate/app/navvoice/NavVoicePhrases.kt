package com.bydmate.app.navvoice

import com.bydmate.app.navdata.NavManeuverCodes

/**
 * What BYDMate says for a Waze maneuver, in Russian, the language of the car's voice (as in
 * RoadPointPhrases). Only maneuvers worth a word are named; anything else (straight on, ferry,
 * tunnel, toll, a code we do not know) says nothing, so a wrong read never becomes a wrong order.
 */
object NavVoicePhrases {

    /** «Через 400 метров поверните направо на вул. Шевченка»; null when the maneuver is not voiced. */
    fun ahead(gaode: Int, exitNumber: Int?, distanceM: Int, road: String): String? {
        val action = action(gaode, exitNumber) ?: return null
        val onto = road.trim().takeIf { it.isNotEmpty() && gaode != NavManeuverCodes.GAODE_ARRIVE }
        return "Через ${distance(distanceM)} $action" + (onto?.let { " на $it" } ?: "")
    }

    /** «Сейчас налево»; null when the maneuver is not voiced. */
    fun now(gaode: Int, exitNumber: Int?): String? {
        roundaboutExit(gaode, exitNumber)?.let { return "Сейчас ${ordinal(it)} съезд" }
        return when (gaode) {
            NavManeuverCodes.GAODE_LEFT -> "Сейчас налево"
            NavManeuverCodes.GAODE_RIGHT -> "Сейчас направо"
            NavManeuverCodes.GAODE_SLIGHT_LEFT -> "Сейчас левее"
            NavManeuverCodes.GAODE_SLIGHT_RIGHT -> "Сейчас правее"
            NavManeuverCodes.GAODE_HARD_LEFT -> "Сейчас резко налево"
            NavManeuverCodes.GAODE_HARD_RIGHT -> "Сейчас резко направо"
            NavManeuverCodes.GAODE_UTURN, NavManeuverCodes.GAODE_UTURN_RIGHT -> "Сейчас разворот"
            NavManeuverCodes.GAODE_ROUNDABOUT_ENTER -> "Сейчас въезд на кольцо"
            NavManeuverCodes.GAODE_ARRIVE -> "Вы прибыли"
            else -> null
        }
    }

    /** True when [gaode] is spoken at all. */
    fun voiced(gaode: Int, exitNumber: Int?): Boolean = action(gaode, exitNumber) != null

    private fun action(gaode: Int, exitNumber: Int?): String? {
        roundaboutExit(gaode, exitNumber)?.let { return "на кольце ${ordinal(it)} съезд" }
        return when (gaode) {
            NavManeuverCodes.GAODE_LEFT -> "поверните налево"
            NavManeuverCodes.GAODE_RIGHT -> "поверните направо"
            NavManeuverCodes.GAODE_SLIGHT_LEFT -> "держитесь левее"
            NavManeuverCodes.GAODE_SLIGHT_RIGHT -> "держитесь правее"
            NavManeuverCodes.GAODE_HARD_LEFT -> "резко поверните налево"
            NavManeuverCodes.GAODE_HARD_RIGHT -> "резко поверните направо"
            NavManeuverCodes.GAODE_UTURN, NavManeuverCodes.GAODE_UTURN_RIGHT -> "развернитесь"
            NavManeuverCodes.GAODE_ROUNDABOUT_ENTER -> "въезжайте на кольцо"
            NavManeuverCodes.GAODE_ARRIVE -> "вы прибудете на место"
            else -> null
        }
    }

    /** The exit number of a roundabout maneuver: Waze's printed number, else the code (24 + N). */
    private fun roundaboutExit(gaode: Int, exitNumber: Int?): Int? {
        val fromCode = (gaode - NavManeuverCodes.GAODE_ROUNDABOUT_EXIT).takeIf { it in 1..MAX_EXIT }
        if (fromCode == null && gaode != NavManeuverCodes.GAODE_ROUNDABOUT_EXIT) return null
        return exitNumber?.takeIf { it in 1..MAX_EXIT } ?: fromCode
    }

    /** «километр» from 950 m; metres rounded to 100 from 300 m, to 50 below (all take «метров»). */
    internal fun distance(m: Int): String = when {
        m >= KM_FROM -> "километр"
        m >= ROUND_100_FROM -> "${(m + HALF_100) / STEP_100 * STEP_100} метров"
        else -> "${((m + HALF_50) / STEP_50 * STEP_50).coerceAtLeast(STEP_50)} метров"
    }

    private fun ordinal(n: Int): String = ORDINALS.getOrElse(n - 1) { "$n-й" }

    private const val MAX_EXIT = 10
    private const val KM_FROM = 950
    private const val ROUND_100_FROM = 300
    private const val STEP_100 = 100
    private const val HALF_100 = 50
    private const val STEP_50 = 50
    private const val HALF_50 = 25
    private val ORDINALS = listOf(
        "первый", "второй", "третий", "четвёртый", "пятый",
        "шестой", "седьмой", "восьмой", "девятый", "десятый",
    )
}
