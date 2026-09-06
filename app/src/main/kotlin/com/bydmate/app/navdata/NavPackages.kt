package com.bydmate.app.navdata

/** Navigation packages whose window/notification data may feed the guidance hub, the HUD and
 *  the voice agent. Yandex sets come from the donor (YANDEX_PKGS split in two); Waze is read by
 *  its own extractor (WazeAccessibilityReader) because its widget tree is unrelated. */
object NavPackages {
    val YANDEX_NAVI = setOf(
        "ru.yandex.yandexnavi",
        "ru.yandex.yandexnavi.inhouse",
        "ru.yandex.yandexnavi.rustore",
    )

    val YANDEX_MAPS = setOf(
        "ru.yandex.yandexmaps",
        "ru.yandex.yandexmaps.beta",
        "ru.yandex.yandexmaps.inhouse",
        "ru.yandex.yandexmaps.rustore",
    )

    val WAZE = setOf("com.waze")

    /** All packages treated as guidance sources (a11y feed, notification mirror, HUD). */
    val GUIDANCE_SOURCES = YANDEX_NAVI + YANDEX_MAPS + WAZE

    fun isWazePackage(packageName: String?): Boolean = packageName in WAZE

    fun isYandexPackage(packageName: String?): Boolean =
        packageName in YANDEX_NAVI || packageName in YANDEX_MAPS
}
