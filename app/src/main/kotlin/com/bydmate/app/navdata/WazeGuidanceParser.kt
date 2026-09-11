package com.bydmate.app.navdata

/**
 * Pure parser: Waze route-bar strings ([WazeAccessibilityReader.Fields]) -> [NavGuidance].
 *
 * Kept separate from [NavGuidanceParser] on purpose: the Yandex parser is field-tested and
 * Russian-first, while Waze labels are localized to the head unit's language and may be
 * imperial ("0.3 mi", "500 ft", "1 hr 5 min"). Every field is optional and fails soft to 0/"".
 * Ported from the VyacheslavRud Sea Lion 07 fork with the roundabout exit number mapped to the
 * upstream 24+N convention so the per-exit HUD icons and the Amap ROUNG_ABOUT_NUM extra work.
 */
object WazeGuidanceParser {
    private const val MAX_DISTANCE_METERS = 20_000_000
    private const val MAX_ETA_SECONDS = 30 * 24 * 60 * 60
    private const val MIN_SPEED_LIMIT = 5
    private const val MAX_SPEED_LIMIT = 250

    // Unit tokens come from the language packs (assets/navi/phrases/<lang>.json, "units");
    // NavPhraseTables compiles the alternations once per load, for all languages at once.
    private val DIST_KM: Regex get() = NavPhraseTables.current.distKm
    private val DIST_M: Regex get() = NavPhraseTables.current.distM
    private val DIST_MI: Regex get() = NavPhraseTables.current.distMi
    private val DIST_FT: Regex get() = NavPhraseTables.current.distFt
    private val ETA_HR_MIN: Regex get() = NavPhraseTables.current.etaHourMin
    private val ETA_HR: Regex get() = NavPhraseTables.current.etaHour
    private val ETA_MIN: Regex get() = NavPhraseTables.current.etaMin
    private val SPEED_LIMIT = Regex("""(?<![-+\d])(\d{1,4})(?!\d)""")
    private val DIGITS = Regex("""\d+""")

    /** Null when no maneuver-bar field is visible: ETA alone (preview/search UI) is not guidance. */
    fun parse(f: WazeAccessibilityReader.Fields): NavGuidance? {
        if (f.maneuver == null && f.maneuverDistance == null && f.street == null) return null
        val maneuverGaode = resolveManeuver(f.maneuver, f.textExitNumber)
        if (maneuverGaode == 0 && !f.maneuver.isNullOrBlank()) {
            // Field diagnostic (no text logged): a maneuver string the packs did not recognize.
            val t = NavPhraseTables.current
            android.util.Log.i("WazeGuidanceParser", "maneuver text unrecognized: len=${f.maneuver.length} " +
                "packs=${t.languages} phrases=${t.phrasePatterns.size} loadedFrom=${NavPhraseTables.loadedFrom} " +
                "probe=${NavManeuverCodes.fromInstructionText("keep right")}")
        }
        return NavGuidance(
            maneuverGaode = maneuverGaode,
            distanceMeters = resolveDistance(f.maneuverDistance),
            road = f.street.orEmpty(),
            etaSeconds = parseDurationSeconds(f.remainingTime),
            totalDistMeters = parseDistanceText(f.remainingDistance),
            speedLimit = parseSpeedLimit(f.speedLimit),
            // Passed through, never turned into a maneuver here: on its own a bare number says
            // nothing about the shape of the junction. It becomes a code only once the arrow
            // classifier has said the arrow IS a roundabout (openbyd applies it the same way).
            exitNumber = f.exitNumber,
        )
    }

    /** A numbered exit (1..10) is a sufficient roundabout signal even when Waze phrases the
     *  instruction as a plain turn; AutoNavi CCW_N_EXIT = 24+N (right-hand traffic). */
    internal fun resolveManeuver(maneuverText: String?, exitNumber: String?): Int {
        val exitNum = exitNumber?.let { DIGITS.find(it)?.value }?.toIntOrNull()
        if (exitNum != null && exitNum in 1..10) return NavManeuverCodes.GAODE_ROUNDABOUT_EXIT + exitNum
        return NavManeuverCodes.fromInstructionText(maneuverText)
    }

    /** Combined metric or imperial Waze distance -> meters; the first unit in the text wins. */
    fun parseDistanceText(text: String?): Int {
        val s = text?.trim() ?: return 0
        data class DistanceMatch(val start: Int, val meters: Int)
        val matches = buildList {
            DIST_KM.findAll(s).forEach { add(DistanceMatch(it.range.first, scaledMeters(it.groupValues[1], 1000.0))) }
            DIST_MI.findAll(s).forEach { add(DistanceMatch(it.range.first, scaledMeters(it.groupValues[1], 1609.344))) }
            DIST_FT.findAll(s).forEach { add(DistanceMatch(it.range.first, scaledMeters(it.groupValues[1], 0.3048))) }
            DIST_M.findAll(s).forEach { add(DistanceMatch(it.range.first, scaledMeters(it.groupValues[1], 1.0))) }
        }
        return matches.filter { it.meters > 0 }.minByOrNull { it.start }?.meters ?: 0
    }

    private fun resolveDistance(distance: String?): Int {
        val rawText = distance?.trim() ?: return 0
        parseDistanceText(rawText).takeIf { it > 0 }?.let { return it }
        // A unit-less Waze accessibility value is treated as metres, matching its UI contract.
        return scaledMeters(rawText, 1.0)
    }

    /** Waze duration label -> remaining seconds. A wall-clock time ("15:40") returns zero. */
    fun parseDurationSeconds(etaTime: String?): Int {
        val s = etaTime ?: return 0
        ETA_HR_MIN.find(s)?.let {
            val hours = it.groupValues[1].toLongOrNull() ?: return 0
            val minutes = it.groupValues[2].toLongOrNull() ?: return 0
            if (hours > MAX_ETA_SECONDS / 3600L || minutes > MAX_ETA_SECONDS / 60L) return 0
            return plausibleEtaSeconds(hours * 3600L + minutes * 60L)
        }
        ETA_HR.find(s)?.let {
            val hours = it.groupValues[1].toLongOrNull() ?: return 0
            if (hours > MAX_ETA_SECONDS / 3600L) return 0
            return plausibleEtaSeconds(hours * 3600L)
        }
        ETA_MIN.find(s)?.let {
            val minutes = it.groupValues[1].toLongOrNull() ?: return 0
            if (minutes > MAX_ETA_SECONDS / 60L) return 0
            return plausibleEtaSeconds(minutes * 60L)
        }
        return 0
    }

    private fun plausibleEtaSeconds(seconds: Long): Int =
        seconds.takeIf { it in 1..MAX_ETA_SECONDS.toLong() }?.toInt() ?: 0

    private fun scaledMeters(value: String, scale: Double): Int {
        val numeric = value.replace(',', '.').toDoubleOrNull() ?: return 0
        val meters = numeric * scale
        if (!meters.isFinite() || meters !in 1.0..MAX_DISTANCE_METERS.toDouble()) return 0
        return meters.toInt()
    }

    private fun parseSpeedLimit(value: String?): Int = value
        ?.let { SPEED_LIMIT.find(it)?.groupValues?.get(1) }
        ?.toIntOrNull()
        ?.takeIf { it in MIN_SPEED_LIMIT..MAX_SPEED_LIMIT }
        ?: 0
}
