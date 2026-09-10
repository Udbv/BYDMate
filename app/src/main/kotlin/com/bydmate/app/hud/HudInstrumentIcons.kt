package com.bydmate.app.hud

import com.bydmate.app.navdata.NavManeuverCodes

/**
 * Maneuver glyphs of the instrument panel, and the mapping from BYDMate's internal Gaode codes
 * onto them.
 *
 * These are two different numbering spaces and they only look alike. They agree for the plain
 * turns and diverge everywhere else, which is why writing a Gaode code straight into
 * `INSTRUMENT_GUIDE_INFO_SIMPLE_SET` produced the wrong picture on the car:
 *
 * | Gaode (ours) | what the panel draws for the same number |
 * |---|---|
 * | 4 slight right | 4 = a second *slight left* glyph |
 * | 13 enter roundabout | 13 = detour right |
 * | 24 exit roundabout | 24 = roundabout straight, variant 2 |
 * | 45 waypoint | 45 = stop, left |
 * | 46 ferry | 46 = parking / services |
 * | 48 arrive | 48 = destination drawn as Chinese characters |
 *
 * The panel table is openbyd 2.4.3's `TURN_ICON_*` (field-tested on BYD clusters); the feature
 * ids it is written to are confirmed against BYD's own catalogue on this car. Ukraine drives on
 * the right, so roundabouts are travelled counter-clockwise and the CCW exit glyphs apply.
 */
object HudInstrumentIcons {

    // Panel glyph numbers, named as the donor names them.
    const val NONE = 0
    const val LEFT = 1
    const val RIGHT = 2
    const val SLIGHT_LEFT = 3
    const val SLIGHT_RIGHT = 5
    const val SHARP_LEFT = 7
    const val SHARP_RIGHT = 8
    const val UTURN_LEFT = 9
    const val UTURN_RIGHT = 10
    const val STRAIGHT = 11
    const val STRAIGHT_DOTTED = 12

    /** Roundabout glyphs. 25..34 are the counter-clockwise exits, one per exit number. */
    const val ROUNDABOUT_CCW_FIRST_EXIT = 25
    const val ROUNDABOUT_CCW_LAST_EXIT = 34
    const val ROUNDABOUT_STRAIGHT = 23

    const val TOLLBOOTH = 47
    const val TUNNEL = 49

    /** Destination. The panel draws this one as Chinese characters, so it is deliberately not
     *  used; the arrival is shown by clearing the glyph instead. */
    const val DESTINATION_CHINESE = 48

    /** Highest glyph the panel knows; anything above is not a picture. */
    const val MAX_GLYPH = 49

    /**
     * Gaode maneuver code -> panel glyph. Returns [NONE] for anything the panel cannot draw
     * sensibly, which blanks the icon rather than showing a wrong or foreign-language one.
     */
    fun fromGaode(gaode: Int): Int = when (gaode) {
        NavManeuverCodes.GAODE_LEFT -> LEFT
        NavManeuverCodes.GAODE_RIGHT -> RIGHT
        NavManeuverCodes.GAODE_SLIGHT_LEFT -> SLIGHT_LEFT
        // The single most damaging mismatch: Gaode 4 is a slight RIGHT, panel 4 is a slight LEFT.
        NavManeuverCodes.GAODE_SLIGHT_RIGHT -> SLIGHT_RIGHT
        NavManeuverCodes.GAODE_HARD_LEFT -> SHARP_LEFT
        NavManeuverCodes.GAODE_HARD_RIGHT -> SHARP_RIGHT
        NavManeuverCodes.GAODE_UTURN -> UTURN_LEFT
        NavManeuverCodes.GAODE_UTURN_RIGHT -> UTURN_RIGHT
        NavManeuverCodes.GAODE_STRAIGHT -> STRAIGHT
        12 -> STRAIGHT_DOTTED
        // Entering a roundabout with no exit number known yet, and the generic exit: a plain
        // roundabout rather than the detour glyph the raw code used to select.
        NavManeuverCodes.GAODE_ROUNDABOUT_ENTER -> ROUNDABOUT_STRAIGHT
        NavManeuverCodes.GAODE_ROUNDABOUT_EXIT -> ROUNDABOUT_STRAIGHT
        // Numbered exits: our 24+N lines up with the panel's CCW exit glyphs one for one.
        in (ROUNDABOUT_CCW_FIRST_EXIT)..(ROUNDABOUT_CCW_LAST_EXIT) -> gaode
        NavManeuverCodes.GAODE_TOLL -> TOLLBOOTH
        NavManeuverCodes.GAODE_TUNNEL -> TUNNEL
        // Waypoint, ferry and arrival: the panel's glyphs at those numbers mean something else
        // (stop, parking, Chinese destination text), so draw nothing.
        else -> NONE
    }

    /** True when the panel has a real picture for this Gaode code. */
    fun hasGlyph(gaode: Int): Boolean = fromGaode(gaode) != NONE
}
