package com.bydmate.app.hud

/**
 * Camera / roadside-sign types of the instrument panel, i.e. `BYDAutoInstrumentDevice`'s
 * `CAMERA_TYPE_*` constants as openbyd 2.4.3 names them (`CarControlImpl.sendCameraInfo`).
 *
 * The panel takes a type, a distance in metres and a state; type 0 with distance -1 and state 1
 * is the donor's "no camera ahead" clear (`CanBydFidStrategy:263-271`). Only [SPEED_LIMITED] is
 * used by the live path so far — it is what carries the speed-limit roundel — and the rest exist
 * because nothing but a parked car and a look at the glass can say which of them this cluster
 * actually draws. That is what the HUD Tester's camera row is for.
 *
 * The misspellings (PECCANRY, FOBIDDEN) are BYD's own and are kept so a name here can be grepped
 * against the SDK.
 */
object HudCameraTypes {

    const val NONE = 0
    const val SPEED_LIMITED = 1
    const val TRAFFIC_LIGHT = 2
    const val PECCANRY = 3
    const val PRESS_PHOTO = 4
    const val INTERVAL_IN = 5
    const val NO_AUTO_LANE = 6
    const val SECURITY_MONITORING = 7
    const val BUS_LANE = 8
    const val INTERVAL_OUT = 9
    const val NO_PARKING = 10
    const val ONE_WAY_ROAD = 11
    const val LEFT_TURN_FOBIDDEN = 12
    const val RIGHT_TURN_FOBIDDEN = 13
    const val U_TURN_FOBIDDEN = 14
    const val NO_ADMITTANCE = 15
    const val VEHICLE_LIMITED = 16
    const val EMERGENCY_LANE = 17
    const val HOV_LANE = 18
    const val NO_PASS_GREEN_LIGHT = 19

    /** Distance the donor sends with the clear call; -1 means "no camera", not "0 m away". */
    const val CLEAR_DISTANCE = -1

    /** The state the donor sends with the clear call. */
    const val CLEAR_STATE = 1

    /** Every type in SDK order, as `type to name`; the tester's picker is built from this. */
    val ALL: List<Pair<Int, String>> = listOf(
        NONE to "CAMERA_TYPE_NONE",
        SPEED_LIMITED to "CAMERA_TYPE_SPEED_LIMITED",
        TRAFFIC_LIGHT to "CAMERA_TYPE_TRAFFIC_LIGHT",
        PECCANRY to "CAMERA_TYPE_PECCANRY",
        PRESS_PHOTO to "CAMERA_TYPE_PRESS_PHOTO",
        INTERVAL_IN to "CAMERA_TYPE_INTERVAL_IN",
        NO_AUTO_LANE to "CAMERA_TYPE_NO_AUTO_LANE",
        SECURITY_MONITORING to "CAMERA_TYPE_SECURITY_MONITORING",
        BUS_LANE to "CAMERA_TYPE_BUS_LANE",
        INTERVAL_OUT to "CAMERA_TYPE_INTERVAL_OUT",
        NO_PARKING to "CAMERA_TYPE_NO_PARKING",
        ONE_WAY_ROAD to "CAMERA_TYPE_ONE_WAY_ROAD",
        LEFT_TURN_FOBIDDEN to "CAMERA_TYPE_LEFT_TURN_FOBIDDEN",
        RIGHT_TURN_FOBIDDEN to "CAMERA_TYPE_RIGHT_TURN_FOBIDDEN",
        U_TURN_FOBIDDEN to "CAMERA_TYPE_U_TURN_FOBIDDEN",
        NO_ADMITTANCE to "CAMERA_TYPE_NO_ADMITTANCE",
        VEHICLE_LIMITED to "CAMERA_TYPE_VEHICLE_LIMITED",
        EMERGENCY_LANE to "CAMERA_TYPE_EMERGENCY_LANE",
        HOV_LANE to "CAMERA_TYPE_HOV_LANE",
        NO_PASS_GREEN_LIGHT to "CAMERA_TYPE_NO_PASS_GREEN_LIGHT",
    )

    private val NAMES: Map<Int, String> = ALL.toMap()

    /** SDK name of a type, or `UNKNOWN (n)` for a number the SDK does not define. */
    fun name(type: Int): String = NAMES[type] ?: "UNKNOWN ($type)"

    /** The picker's label: the number first, because that is what the log line carries. */
    fun label(type: Int): String = "$type · ${name(type)}"
}
