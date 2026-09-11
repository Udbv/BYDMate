package com.bydmate.app.hud

import com.bydmate.app.navdata.NavManeuverCodes

/**
 * Maneuver glyphs of the instrument panel (openbyd 2.4.3's `TURN_ICON_*`, field-tested on BYD
 * clusters), and the mapping from BYDMate's internal Gaode codes onto them.
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
 * | 46 ferry | 46 = parking / services |
 *
 * The donor's rule for anything it cannot place is [STRAIGHT], never a blank: an arrow pointing
 * the way you are already going is a harmless picture, while a suddenly empty box reads as "the
 * HUD died". BYDMate used to blank those; that is the one behaviour this table changed.
 *
 * When the guidance source can name the panel glyph directly (the Waze arrow-signature path
 * yields a `TURN_ICON_*` number, including the clockwise roundabout exits 35..44 that have no
 * Gaode equivalent), that number is used as-is and this mapping is not consulted.
 *
 * Ukraine drives on the right, so roundabouts are travelled counter-clockwise and the CCW exit
 * glyphs (25..34) apply.
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

    /** Roundabout glyphs. 25..34 are the counter-clockwise exits, one per exit number; 25 is
     *  also the plain roundabout, drawn when the exit number is not known. */
    const val ROUNDABOUT_CCW_FIRST_EXIT = 25
    const val ROUNDABOUT_CCW_LAST_EXIT = 34
    const val ROUNDABOUT_PLAIN = 25
    const val ROUNDABOUT_CW_FIRST_EXIT = 35
    const val ROUNDABOUT_CW_LAST_EXIT = 44

    const val STOP_LEFT = 45
    const val PARKING_CAFE = 46
    const val TOLLBOOTH = 47
    const val DESTINATION_CHINESE = 48
    const val TUNNEL = 49

    /** Highest glyph the panel knows; anything above is not a picture. */
    const val MAX_GLYPH = 49

    /**
     * Gaode maneuver code -> panel glyph. Anything the panel has no sensible picture for
     * becomes [STRAIGHT], as in the donor.
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
        // Entering a roundabout, and leaving one with no exit number known: the donor's plain
        // roundabout glyph, not the detour arrow the raw code used to select.
        NavManeuverCodes.GAODE_ROUNDABOUT_ENTER -> ROUNDABOUT_PLAIN
        NavManeuverCodes.GAODE_ROUNDABOUT_EXIT -> ROUNDABOUT_PLAIN
        // Numbered exits: our 24+N lines up with the panel's CCW exit glyphs one for one.
        in ROUNDABOUT_CCW_FIRST_EXIT..ROUNDABOUT_CCW_LAST_EXIT -> gaode
        NavManeuverCodes.GAODE_WAYPOINT -> STOP_LEFT
        NavManeuverCodes.GAODE_TOLL -> TOLLBOOTH
        // The panel spells the destination in Chinese characters. openbyd shows it anyway and
        // has done so on real cars for years; a recognisable glyph in the wrong script beats an
        // empty box at the moment the driver most needs to know they have arrived.
        NavManeuverCodes.GAODE_ARRIVE -> DESTINATION_CHINESE
        NavManeuverCodes.GAODE_TUNNEL -> TUNNEL
        // Ferry (46 = parking/cafe on the panel), 0 = no maneuver, and everything unmapped.
        else -> STRAIGHT
    }

    /** The donor's own name for a panel glyph, for logs and the trip journal (HudController
     *  `getIconName`). Unknown codes are reported, not swallowed. */
    fun name(code: Int): String = when (code) {
        1 -> "TURN_ICON_LEFT"
        2 -> "TURN_ICON_RIGHT"
        3 -> "TURN_ICON_SLIGHT_LEFT"
        4 -> "TURN_ICON_SLIGHT_LEFT_ALT"
        5 -> "TURN_ICON_SLIGHT_RIGHT"
        6 -> "TURN_ICON_SLIGHT_RIGHT_ALT"
        7 -> "TURN_ICON_SHARP_LEFT"
        8 -> "TURN_ICON_SHARP_RIGHT"
        9 -> "TURN_ICON_U_TURN_LEFT"
        10 -> "TURN_ICON_U_TURN_RIGHT"
        11 -> "TURN_ICON_STRAIGHT_SOLID"
        12 -> "TURN_ICON_STRAIGHT_DOTTED"
        13 -> "TURN_ICON_DETOUR_RIGHT"
        14 -> "TURN_ICON_DETOUR_LEFT"
        15 -> "TURN_ICON_ROUNDABOUT_3_4_LEFT"
        16 -> "TURN_ICON_ROUNDABOUT_1_4_LEFT"
        17 -> "TURN_ICON_ROUNDABOUT_3_4_RIGHT"
        18 -> "TURN_ICON_ROUNDABOUT_1_4_RIGHT"
        19 -> "TURN_ICON_ROUNDABOUT_STRAIGHT_L"
        20 -> "TURN_ICON_ROUNDABOUT_STRAIGHT_R"
        21 -> "TURN_ICON_ROUNDABOUT_L_TO_R"
        22 -> "TURN_ICON_ROUNDABOUT_R_TO_L"
        23 -> "TURN_ICON_ROUNDABOUT_STRAIGHT_ALT1"
        24 -> "TURN_ICON_ROUNDABOUT_STRAIGHT_ALT2"
        in 25..34 -> "TURN_ICON_ROUNDABOUT_CCW_${code - 24}_EXIT"
        in 35..44 -> "TURN_ICON_ROUNDABOUT_CW_${code - 34}_EXIT"
        45 -> "TURN_ICON_STOP_LEFT"
        46 -> "TURN_ICON_PARKING_CAFE"
        47 -> "TURN_ICON_TOLLBOOTH"
        48 -> "TURN_ICON_DESTINATION_CHINESE"
        49 -> "TURN_ICON_TUNNEL"
        else -> "UNKNOWN ($code)"
    }
}
