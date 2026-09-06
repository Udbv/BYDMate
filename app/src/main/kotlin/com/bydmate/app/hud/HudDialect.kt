package com.bydmate.app.hud

import android.content.Context

/**
 * Which head-up-display firmware family receives the SOME/IP frames. Both share the
 * `HudNaviInfoService` schema (service 0x010A, topic 0x4010a00018001), but the firmwares
 * read the fields differently.
 *
 * - [CLASSIC]: Atto 3 / Seal / Sea Lion glass (byd-hud donor). Service key
 *   0xB010A00010000, chevron enum in f28 (see [HudProtobufBuilder.gaodeToF28]), speed sign
 *   rendered as a PNG in f7 with f6 = 6.
 * - [AR_HUD]: DiLink 150 AR-HUD (Tang L 2025). The car's own map app
 *   (`BydLaunchermap`, `PlatformHudImpl`) starts service key 0xB010A00020000 and sends the
 *   raw Gaode maneuver code in f28 (`recommendedDrivingDirectionsId`), the maneuver PNG in
 *   f8, remaining distance/time in f3/f4, ETA strings in f26/f27 and a constant counter 2.
 *   Investigation: docs/investigations/tang-l-hud-someip.md.
 *
 * The pref value "auto" resolves from `ro.vehicle.type` (DiLink150_* -> AR-HUD).
 */
enum class HudDialect(val prefValue: String) {
    CLASSIC("classic"),
    AR_HUD("arhud");

    companion object {
        /** Stored in [HudController.PREFS_NAME]. Values: [PREF_AUTO] or a [prefValue]. */
        const val KEY = "hud_dialect"
        const val PREF_AUTO = "auto"
        private const val VEHICLE_TYPE_PROP = "ro.vehicle.type"

        fun fromPref(value: String?): HudDialect? = entries.firstOrNull { it.prefValue == value }

        fun stored(context: Context): String =
            context.getSharedPreferences(HudController.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY, PREF_AUTO) ?: PREF_AUTO

        fun resolve(context: Context): HudDialect = fromPref(stored(context)) ?: detect()

        /** Pure: the firmware family implied by the vehicle-type property. */
        fun detect(vehicleType: String = readVehicleType()): HudDialect =
            if (vehicleType.contains("DiLink150", ignoreCase = true)) AR_HUD else CLASSIC

        fun readVehicleType(): String = runCatching {
            val cls = Class.forName("android.os.SystemProperties")
            cls.getMethod("get", String::class.java).invoke(null, VEHICLE_TYPE_PROP) as? String
        }.getOrNull().orEmpty()
    }
}
