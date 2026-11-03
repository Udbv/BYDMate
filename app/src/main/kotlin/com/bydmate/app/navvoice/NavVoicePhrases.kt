package com.bydmate.app.navvoice

import com.bydmate.app.navdata.NavManeuverCodes
import com.bydmate.app.voice.TtsLang

/**
 * What BYDMate says for a Waze maneuver, in the language of the selected voice: Ukrainian for a
 * Ukrainian voice (the owner's ask, 2026-11-03), Russian otherwise. Only maneuvers worth a word
 * are named; anything else (straight on, ferry, tunnel, toll, a code we do not know) says
 * nothing, so a wrong read never becomes a wrong order.
 */
object NavVoicePhrases {

    /** «Через 400 метрів поверніть праворуч на вул. Шевченка»; null when the maneuver is not voiced. */
    fun ahead(gaode: Int, exitNumber: Int?, distanceM: Int, road: String, lang: TtsLang = TtsLang.RU): String? {
        val w = words(lang)
        val action = action(gaode, exitNumber, w) ?: return null
        val onto = road.trim().takeIf { it.isNotEmpty() && gaode != NavManeuverCodes.GAODE_ARRIVE }
        return "${w.glue.through} ${distance(distanceM, lang)} $action" + (onto?.let { " ${w.glue.onto} $it" } ?: "")
    }

    /** «Зараз ліворуч»; null when the maneuver is not voiced. */
    fun now(gaode: Int, exitNumber: Int?, lang: TtsLang = TtsLang.RU): String? {
        val w = words(lang)
        roundaboutExit(gaode, exitNumber)?.let { return "${w.glue.now} ${w.ordinals[it - 1]} ${w.glue.exit}" }
        if (gaode == NavManeuverCodes.GAODE_ARRIVE) return w.glue.arrived
        return w.nowActions[gaode]?.let { "${w.glue.now} $it" }
    }

    /** True when [gaode] is spoken at all. */
    fun voiced(gaode: Int, exitNumber: Int?): Boolean = action(gaode, exitNumber, RU) != null

    private fun action(gaode: Int, exitNumber: Int?, w: Words): String? {
        roundaboutExit(gaode, exitNumber)?.let { return "${w.glue.onRing} ${w.ordinals[it - 1]} ${w.glue.exit}" }
        return w.actions[gaode]
    }

    /** The exit number of a roundabout maneuver: Waze's printed number, else the code (24 + N). */
    private fun roundaboutExit(gaode: Int, exitNumber: Int?): Int? {
        val fromCode = (gaode - NavManeuverCodes.GAODE_ROUNDABOUT_EXIT).takeIf { it in 1..MAX_EXIT }
        if (fromCode == null && gaode != NavManeuverCodes.GAODE_ROUNDABOUT_EXIT) return null
        return exitNumber?.takeIf { it in 1..MAX_EXIT } ?: fromCode
    }

    /** «километр» / «кілометр» from 950 m; metres rounded to 100 from 300 m, to 50 below. */
    internal fun distance(m: Int, lang: TtsLang = TtsLang.RU): String {
        val w = words(lang)
        return when {
            m >= KM_FROM -> w.km
            m >= ROUND_100_FROM -> "${(m + HALF_100) / STEP_100 * STEP_100} ${w.metres}"
            else -> "${((m + HALF_50) / STEP_50 * STEP_50).coerceAtLeast(STEP_50)} ${w.metres}"
        }
    }

    private class Glue(
        val through: String, val onto: String, val now: String, val arrived: String, val onRing: String, val exit: String,
    )

    private class Words(
        val glue: Glue, val km: String, val metres: String, val ordinals: List<String>,
        val actions: Map<Int, String>, val nowActions: Map<Int, String>,
    )

    private fun words(lang: TtsLang) = if (lang == TtsLang.UK) UK else RU

    private val RU = Words(
        glue = Glue(through = "Через", onto = "на", now = "Сейчас", arrived = "Вы прибыли", onRing = "на кольце", exit = "съезд"),
        km = "километр", metres = "метров",
        ordinals = listOf("первый", "второй", "третий", "четвёртый", "пятый", "шестой", "седьмой", "восьмой", "девятый", "десятый"),
        actions = mapOf(
            NavManeuverCodes.GAODE_LEFT to "поверните налево", NavManeuverCodes.GAODE_RIGHT to "поверните направо",
            NavManeuverCodes.GAODE_SLIGHT_LEFT to "держитесь левее", NavManeuverCodes.GAODE_SLIGHT_RIGHT to "держитесь правее",
            NavManeuverCodes.GAODE_HARD_LEFT to "резко поверните налево", NavManeuverCodes.GAODE_HARD_RIGHT to "резко поверните направо",
            NavManeuverCodes.GAODE_UTURN to "развернитесь", NavManeuverCodes.GAODE_UTURN_RIGHT to "развернитесь",
            NavManeuverCodes.GAODE_ROUNDABOUT_ENTER to "въезжайте на кольцо", NavManeuverCodes.GAODE_ARRIVE to "вы прибудете на место",
        ),
        nowActions = mapOf(
            NavManeuverCodes.GAODE_LEFT to "налево", NavManeuverCodes.GAODE_RIGHT to "направо",
            NavManeuverCodes.GAODE_SLIGHT_LEFT to "левее", NavManeuverCodes.GAODE_SLIGHT_RIGHT to "правее",
            NavManeuverCodes.GAODE_HARD_LEFT to "резко налево", NavManeuverCodes.GAODE_HARD_RIGHT to "резко направо",
            NavManeuverCodes.GAODE_UTURN to "разворот", NavManeuverCodes.GAODE_UTURN_RIGHT to "разворот",
            NavManeuverCodes.GAODE_ROUNDABOUT_ENTER to "въезд на кольцо",
        ),
    )

    private val UK = Words(
        glue = Glue(through = "Через", onto = "на", now = "Зараз", arrived = "Ви прибули", onRing = "на колі", exit = "з'їзд"),
        km = "кілометр", metres = "метрів",
        ordinals = listOf("перший", "другий", "третій", "четвертий", "п'ятий", "шостий", "сьомий", "восьмий", "дев'ятий", "десятий"),
        actions = mapOf(
            NavManeuverCodes.GAODE_LEFT to "поверніть ліворуч", NavManeuverCodes.GAODE_RIGHT to "поверніть праворуч",
            NavManeuverCodes.GAODE_SLIGHT_LEFT to "тримайтеся лівіше", NavManeuverCodes.GAODE_SLIGHT_RIGHT to "тримайтеся правіше",
            NavManeuverCodes.GAODE_HARD_LEFT to "різко поверніть ліворуч", NavManeuverCodes.GAODE_HARD_RIGHT to "різко поверніть праворуч",
            NavManeuverCodes.GAODE_UTURN to "розверніться", NavManeuverCodes.GAODE_UTURN_RIGHT to "розверніться",
            NavManeuverCodes.GAODE_ROUNDABOUT_ENTER to "в'їжджайте на коло", NavManeuverCodes.GAODE_ARRIVE to "ви прибудете на місце",
        ),
        nowActions = mapOf(
            NavManeuverCodes.GAODE_LEFT to "ліворуч", NavManeuverCodes.GAODE_RIGHT to "праворуч",
            NavManeuverCodes.GAODE_SLIGHT_LEFT to "лівіше", NavManeuverCodes.GAODE_SLIGHT_RIGHT to "правіше",
            NavManeuverCodes.GAODE_HARD_LEFT to "різко ліворуч", NavManeuverCodes.GAODE_HARD_RIGHT to "різко праворуч",
            NavManeuverCodes.GAODE_UTURN to "розворот", NavManeuverCodes.GAODE_UTURN_RIGHT to "розворот",
            NavManeuverCodes.GAODE_ROUNDABOUT_ENTER to "в'їзд на коло",
        ),
    )

    private const val MAX_EXIT = 10
    private const val KM_FROM = 950
    private const val ROUND_100_FROM = 300
    private const val STEP_100 = 100
    private const val HALF_100 = 50
    private const val STEP_50 = 50
    private const val HALF_50 = 25
}
