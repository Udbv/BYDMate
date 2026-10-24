package com.bydmate.app.hud

/**
 * The forty-nine demo maneuvers of openbyd's HUD Tester, copied verbatim from `n70.a`
 * (openbyd 2.4.3, `defpackage/n70.java:9`, each entry `r81(maneuver, iconId, distance, road)`).
 *
 * They are a tour of the instrument panel's own glyph table: icon 1..49 in order, with distances
 * and street names chosen to exercise the cluster's font - Latin, Cyrillic, accented Latin,
 * Arabic, Chinese, Thai and emoji - so a check on the glass while parked shows what the panel can
 * and cannot draw. Two distances are named constants in the donor (`AUTO_TYPE_SA2FL` = 110,
 * `AUTO_TYPE_SUE` = 150); they are plain numbers here.
 *
 * Nothing here is derived: changing a value changes what the test screen claims the donor sends.
 */
object HudPanelTestSteps {

    /** One demo frame: the panel glyph, how far away it is and the street it leads onto. */
    data class Step(
        /** The donor's own label for the glyph, shown as "Active Maneuver". */
        val maneuver: String,
        /** Panel icon id, written to `0x43F01010` / `0x43F01030`. */
        val iconId: Int,
        /** Metres to the maneuver. */
        val distanceMeters: Int,
        /** Next street name. */
        val road: String,
    )

    val STEPS: List<Step> = listOf(
        Step("Turn Left 90°", 1, 35, "Grand Avenue"),
        Step("Turn Right 90°", 2, 25, "Broadway"),
        Step("Slight Left 120°", 3, 45, "Constitución 🇪🇸"),
        Step("Slight Left 120° (Alt)", 4, 50, "Sunset Boulevard"),
        Step("Slight Right 120°", 5, 30, "Champs-Élysées"),
        Step("Slight Right 120° (Alt)", 6, 25, "Unter den Linden"),
        Step("Sharp Left 30°", 7, 15, "Fifth Avenue"),
        Step("Sharp Right 30°", 8, 18, "Abbey Road"),
        Step("U-Turn Left", 9, 60, "Автострада 🇷🇺"),
        Step("U-Turn Right", 10, 40, "Ocean Drive"),
        Step("Go Straight (Solid)", 11, 80, "Peachtree Street"),
        Step("Go Straight (Dotted)", 12, 75, "Lombard Street"),
        Step("Rightward Detour", 13, 60, "شارع الشيخ زايد 🇦🇪"),
        Step("Leftward Detour", 14, 20, "Gran Via"),
        Step("Roundabout 3/4 Left", 15, 90, "长安街 🇨🇳"),
        Step("1/4 Roundabout Left", 16, 80, "Rodeo Drive"),
        Step("Roundabout 3/4 Right", 17, 40, "Las Vegas Strip"),
        Step("1/4 Roundabout Right", 18, 35, "Michigan Avenue"),
        Step("Roundabout Straight (L)", 19, 70, "Bourbon Street"),
        Step("Roundabout Straight (R)", 20, 74, "Wall Street"),
        Step("Roundabout (L to R)", 21, 29, "The Mall"),
        Step("Roundabout (R to L)", 22, 19, "OpenBYD / 大道 🍀🇨🇳"),
        Step("Roundabout Straight Alt (L)", 23, 69, "La Rambla"),
        Step("Roundabout Straight Alt (R)", 24, 59, "Václavské Náměstí"),
        Step("Roundabout CCW (1 Lap)", 25, 72, "Невский Проспект 🇷🇺"),
        Step("Roundabout CCW (2 Laps)", 26, 57, "Kaufingerstraße"),
        Step("Roundabout CCW (3 Laps)", 27, 110, "Kurfürstendamm"),  // AUTO_TYPE_SA2FL
        Step("Roundabout CCW (4 Laps)", 28, 100, "Regent Street"),
        Step("Roundabout CCW (5 Laps)", 29, 90, "Oxford Street"),
        Step("Roundabout CCW (6 Laps)", 30, 80, "Krungthepmahanakhonamonrattanakosinmahintharaayuthayamahadilokphopnoppharatratchathaniburiromudomratchaniwetmahasathanamonpimanawatansathitsakkathattiyawitsanukamprasit"),
        Step("Roundabout CCW (7 Laps)", 31, 70, "Español: üáéíóú 🇪🇸"),
        Step("Roundabout CCW (8 Laps)", 32, 60, "Mulholland Drive"),
        Step("Roundabout CCW (9 Laps)", 33, 50, "Skyline Boulevard"),
        Step("Roundabout CCW (10 Laps)", 34, 40, "Blue Ridge Parkway"),
        Step("Roundabout CW (1 Lap)", 35, 150, "Lake Shore Drive"),  // AUTO_TYPE_SUE
        Step("Roundabout CW (2 Laps)", 36, 120, "St. Charles Avenue"),
        Step("Roundabout CW (3 Laps)", 37, 110, "OpenBYD / طريق 🇦🇪"),  // AUTO_TYPE_SA2FL
        Step("Roundabout CW (4 Laps)", 38, 100, "Beacon Street"),
        Step("Roundabout CW (5 Laps)", 39, 90, "Pennsylvania"),
        Step("Roundabout CW (6 Laps)", 40, 80, "Collins Avenue"),
        Step("Roundabout CW (7 Laps)", 41, 70, "北京路 🇨🇳"),
        Step("Roundabout CW (8 Laps)", 42, 60, "State Street"),
        Step("Roundabout CW (9 Laps)", 43, 50, "Kapiolani"),
        Step("Roundabout CW (10 Laps)", 44, 40, "Alia Expressway"),
        Step("Left-side Destination Stop", 45, 30, "улица Ленина 🇷🇺"),
        Step("Parking Area / Café Stop", 46, 25, "Victoria Embankment"),
        Step("Tollbooth / Gate Station", 47, 69, "Piccadilly"),
        Step("Destination Arrival (CN)", 48, 0, "OpenBYD / 广场 🇨🇳"),
        Step("Tunnel Entry / Passage", 49, 84, "Harbour Road"),
    )

    /** How many steps the automatic run cycles through. */
    val SIZE: Int get() = STEPS.size

    /** The step for a 1-based number, coerced into range like the donor (`gh0.w(n, 1, 49)`). */
    fun stepFor(number: Int): Step = STEPS[number.coerceIn(1, SIZE) - 1]
}
