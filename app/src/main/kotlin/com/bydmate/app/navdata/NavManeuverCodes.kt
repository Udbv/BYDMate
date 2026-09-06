package com.bydmate.app.navdata

/** Yandex Navigator maneuver -> GAODE code, three input forms: a11y balloon description,
 *  notification icon resource name, and back to a short Russian phrase for the voice agent.
 *  Ported from @rbgboost's YandexHUD (field-tested on DiLink 5); the RU phrase tables are
 *  kept verbatim, only camera/traffic-light paths were dropped. */
object NavManeuverCodes {
    const val GAODE_LEFT = 1
    const val GAODE_RIGHT = 2
    const val GAODE_SLIGHT_LEFT = 3
    const val GAODE_SLIGHT_RIGHT = 4
    const val GAODE_HARD_LEFT = 7
    const val GAODE_HARD_RIGHT = 8
    const val GAODE_UTURN = 9
    const val GAODE_UTURN_RIGHT = 10
    const val GAODE_STRAIGHT = 11
    const val GAODE_ROUNDABOUT_ENTER = 13
    const val GAODE_ROUNDABOUT_EXIT = 24
    const val GAODE_WAYPOINT = 45
    const val GAODE_FERRY = 46
    const val GAODE_ARRIVE = 48
    const val GAODE_TUNNEL = 49
    const val GAODE_TOLL = 47

    private val ROUNDABOUT_EXIT_RE = Regex("""(\d+)[-‑]й\s+съезд""")

    fun fromA11yDescription(text: String?): Int {
        if (text == null || text.isBlank()) return 0
        if (text == ">>>") return GAODE_STRAIGHT
        // NBSP via explicit escape: a literal NBSP is invisible and gets lost in copy/transcription
        val lower = text.lowercase().trim().replace('\u00A0', ' ')

        val exitNum = ROUNDABOUT_EXIT_RE.find(lower)?.groupValues?.get(1)?.toIntOrNull()
        // AutoNavi CCW_N_EXIT = 24+N (right-hand traffic); flat 24 only when the
        // exit number is missing or out of the 1..10 icon range.
        if (exitNum != null) return if (exitNum in 1..10) GAODE_ROUNDABOUT_EXIT + exitNum else GAODE_ROUNDABOUT_EXIT

        return when {
            "въезд на паром" in lower -> GAODE_FERRY
            "кольцевое" in lower || "круговое" in lower -> GAODE_ROUNDABOUT_ENTER
            "выезд с кольца" in lower || "съезд с кольца" in lower -> GAODE_ROUNDABOUT_EXIT
            "промежуточная точка" in lower -> GAODE_WAYPOINT
            "съезд с парома" in lower || "выезд с парома" in lower -> GAODE_STRAIGHT
            "прибытие" in lower || "маршрут окончен" in lower || "конечная" in lower || "достигнут" in lower -> GAODE_ARRIVE
            "тоннель" in lower || "туннель" in lower -> GAODE_TUNNEL
            "плавный поворот налево" in lower || "плавно налево" in lower || "держитесь левее" in lower -> GAODE_SLIGHT_LEFT
            "плавный поворот направо" in lower || "плавно направо" in lower || "держитесь правее" in lower -> GAODE_SLIGHT_RIGHT
            "резкий поворот налево" in lower || "резко налево" in lower -> GAODE_HARD_LEFT
            "резкий поворот направо" in lower || "резко направо" in lower -> GAODE_HARD_RIGHT
            "разворот" in lower || "развернитесь" in lower ->
                if ("направо" in lower) GAODE_UTURN_RIGHT else GAODE_UTURN
            "поверните налево" in lower || "поворот налево" in lower || "налево" in lower -> GAODE_LEFT
            "поверните направо" in lower || "поворот направо" in lower || "направо" in lower -> GAODE_RIGHT
            "прямо" in lower || "продолжайте" in lower || "двигайтесь" in lower -> GAODE_STRAIGHT
            else -> fromRussianTextFallback(lower)
        }
    }

    /** Notification maneuver icon resource name -> GAODE (donor YANDEX_MANEUVER_RES
     *  collapsed through toGaode; board_ferry variant seen on the 2025 Navigator). */
    private val NOTIFICATION_RES = mapOf(
        "notification_straight_sdl" to GAODE_STRAIGHT,
        "notification_left_sdl" to GAODE_LEFT,
        "notification_right_sdl" to GAODE_RIGHT,
        "notification_slight_left_sdl" to GAODE_SLIGHT_LEFT,
        "notification_slight_right_sdl" to GAODE_SLIGHT_RIGHT,
        "notification_hard_left_sdl" to GAODE_HARD_LEFT,
        "notification_hard_right_sdl" to GAODE_HARD_RIGHT,
        "notification_fork_left_sdl" to GAODE_SLIGHT_LEFT,
        "notification_fork_right_sdl" to GAODE_SLIGHT_RIGHT,
        "notification_uturn_left_sdl" to GAODE_UTURN,
        "notification_uturn_right_sdl" to GAODE_UTURN_RIGHT,
        "notification_exit_left_sdl" to GAODE_HARD_LEFT,
        "notification_exit_right_sdl" to GAODE_HARD_RIGHT,
        "notification_enter_roundabout_sdl" to GAODE_ROUNDABOUT_ENTER,
        "notification_leave_roundabout_sdl" to GAODE_ROUNDABOUT_EXIT,
        "notification_finish_sdl" to GAODE_ARRIVE,
        "notification_ferry_sdl" to GAODE_FERRY,
        "notification_board_ferry_sdl" to GAODE_FERRY,
    )

    fun fromNotificationRes(resName: String?): Int =
        resName?.let { NOTIFICATION_RES[it] } ?: 0

    /** GAODE -> short Russian phrase; used by get_route_info when only hub numerics exist. */
    private val PHRASES = mapOf(
        GAODE_LEFT to "налево",
        GAODE_RIGHT to "направо",
        GAODE_SLIGHT_LEFT to "левее",
        GAODE_SLIGHT_RIGHT to "правее",
        GAODE_HARD_LEFT to "резко налево",
        GAODE_HARD_RIGHT to "резко направо",
        GAODE_UTURN to "разворот",
        GAODE_UTURN_RIGHT to "разворот направо",
        GAODE_STRAIGHT to "прямо",
        GAODE_ROUNDABOUT_ENTER to "круговое движение",
        GAODE_ROUNDABOUT_EXIT to "съезд с кольца",
        GAODE_WAYPOINT to "промежуточная точка",
        GAODE_FERRY to "паром",
        GAODE_ARRIVE to "прибытие",
        GAODE_TUNNEL to "тоннель",
    )

    fun gaodePhrase(gaode: Int): String? = PHRASES[gaode]

    // -- fallback: donor's internal-enum phrase table, collapsed straight to GAODE --

    private val RU_PHRASES = linkedMapOf(
        "развернитесь направо" to GAODE_UTURN_RIGHT,
        "разворот направо" to GAODE_UTURN_RIGHT,
        "развернитесь налево" to GAODE_UTURN,
        "развернитесь" to GAODE_UTURN,
        "разворот" to GAODE_UTURN,
        "u-turn" to GAODE_UTURN,
        "резкий поворот налево" to GAODE_HARD_LEFT,
        "резко налево" to GAODE_HARD_LEFT,
        "резкий поворот направо" to GAODE_HARD_RIGHT,
        "резко направо" to GAODE_HARD_RIGHT,
        "плавный поворот налево" to GAODE_SLIGHT_LEFT,
        "плавно налево" to GAODE_SLIGHT_LEFT,
        "держитесь левее" to GAODE_SLIGHT_LEFT,
        "плавный поворот направо" to GAODE_SLIGHT_RIGHT,
        "плавно направо" to GAODE_SLIGHT_RIGHT,
        "держитесь правее" to GAODE_SLIGHT_RIGHT,
        "поверните налево" to GAODE_LEFT,
        "поворот налево" to GAODE_LEFT,
        "налево" to GAODE_LEFT,
        "левее" to GAODE_SLIGHT_LEFT,
        "правее" to GAODE_SLIGHT_RIGHT,
        "поверните направо" to GAODE_RIGHT,
        "поворот направо" to GAODE_RIGHT,
        "направо" to GAODE_RIGHT,
        "въезжайте на кольцо" to GAODE_ROUNDABOUT_ENTER,
        "войдите в кольцо" to GAODE_ROUNDABOUT_ENTER,
        "съезжайте с кольца" to GAODE_ROUNDABOUT_EXIT,
        "выезжайте из кольца" to GAODE_ROUNDABOUT_EXIT,
        "съезд с кольца" to GAODE_ROUNDABOUT_EXIT,
        "выезд с кольца" to GAODE_ROUNDABOUT_EXIT,
        "въезд на паром" to GAODE_FERRY,
        "вы прибыли" to GAODE_ARRIVE,
        "маршрут завершён" to GAODE_ARRIVE,
        "до конца маршрута" to GAODE_ARRIVE,
        "конец маршрута" to GAODE_ARRIVE,
        "конечная" to GAODE_ARRIVE,
        "достигнут" to GAODE_ARRIVE,
        "прибытие" to GAODE_ARRIVE,
        "прямо" to GAODE_STRAIGHT,
        "продолжайте прямо" to GAODE_STRAIGHT,
        "продолжить" to GAODE_STRAIGHT,
        "двигайтесь прямо" to GAODE_STRAIGHT,
    )

    private val WORD_BOUNDARY_PHRASES = linkedMapOf(
        "левый" to GAODE_LEFT,
        "правый" to GAODE_RIGHT,
        "паром" to GAODE_FERRY,
        "кольцо" to GAODE_ROUNDABOUT_ENTER,
        "круговое" to GAODE_ROUNDABOUT_ENTER,
        "туннель" to GAODE_TUNNEL,
        "тоннель" to GAODE_TUNNEL,
    )

    private fun fromRussianTextFallback(lower: String): Int {
        // lower is already NBSP-normalized by fromA11yDescription
        val norm = lower.replace(Regex("\\s+"), " ")
        for ((phrase, code) in RU_PHRASES) if (phrase in norm) return code
        for ((phrase, code) in WORD_BOUNDARY_PHRASES) {
            if (Regex("""(?:^|\s|[\p{Punct}])${Regex.escape(phrase)}(?:$|\s|[\p{Punct}])""").containsMatchIn(norm)) return code
        }
        return 0
    }

    // -- donor rich-notification mappings (RemoteViewsParser/ManeuverMapper port) --

    private val ROAD_ALERT_RES = mapOf(
        "road_alerts_camera_32" to "camera",
        "road_alerts_accident_32" to "accident",
        "road_alerts_road_works_32" to "roadworks",
        "road_alerts_other_32" to "other",
    )

    /** Yandex road-alert drawable name -> alert kind; "" when not an alert icon. */
    fun roadAlertFromRes(resName: String): String = ROAD_ALERT_RES[resName] ?: ""

    private val SERVICE_PHRASES = setOf(
        "камера контроля скорости", "направо", "налево",
        "почти на месте", "кольцевое движение",
    )

    /** Donor's service-phrase filter: such texts are never a street name. */
    fun isServicePhrase(text: String): Boolean = SERVICE_PHRASES.any { it in text.lowercase() }

    /** Donor word-boundary table for the rich path. Differs from WORD_BOUNDARY_PHRASES:
     *  a bare "съезд" deliberately maps to unknown (stops the scan), toll words map to 47. */
    private val RICH_WORD_BOUNDARY = linkedMapOf(
        "левый" to GAODE_LEFT,
        "правый" to GAODE_RIGHT,
        "съезд" to 0,
        "паром" to GAODE_FERRY,
        "кольцо" to GAODE_ROUNDABOUT_ENTER,
        "круговое" to GAODE_ROUNDABOUT_ENTER,
        "туннель" to GAODE_TUNNEL,
        "тоннель" to GAODE_TUNNEL,
        "платный" to GAODE_TOLL,
        "пошлина" to GAODE_TOLL,
    )

    /** Donor ManeuverMapper.fromRussianText collapsed straight to GAODE; 0 = not a maneuver.
     *  Used by the rich notification path only (fromA11yDescription stays byte-identical). */
    fun richPhraseGaode(text: String?): Int {
        if (text.isNullOrBlank()) return 0
        val norm = text.lowercase().trim().replace('\u00A0', ' ').replace(Regex("\\s+"), " ")
        for ((phrase, code) in RU_PHRASES) if (phrase in norm) return code
        for ((phrase, code) in RICH_WORD_BOUNDARY) {
            if (Regex("""(?:^|\s|[\p{Punct}])${Regex.escape(phrase)}(?:$|\s|[\p{Punct}])""").containsMatchIn(norm)) return code
        }
        return 0
    }

    /** Donor ManeuverMapper EN_ICON_NAMES collapsed through toGaode. */
    private val RICH_ICON_NAMES = mapOf(
        "notification_straight_sdl" to GAODE_STRAIGHT,
        "notification_go_ahead_sdl" to GAODE_STRAIGHT,
        "notification_left_sdl" to GAODE_LEFT,
        "notification_right_sdl" to GAODE_RIGHT,
        "notification_hard_left_sdl" to GAODE_HARD_LEFT,
        "notification_hard_right_sdl" to GAODE_HARD_RIGHT,
        "notification_slight_left_sdl" to GAODE_SLIGHT_LEFT,
        "notification_slight_right_sdl" to GAODE_SLIGHT_RIGHT,
        "notification_uturn_left_sdl" to GAODE_UTURN,
        "notification_uturn_right_sdl" to GAODE_UTURN_RIGHT,
        "notification_uturn_sdl" to GAODE_UTURN,
        "notification_fork_left_sdl" to GAODE_SLIGHT_LEFT,
        "notification_fork_right_sdl" to GAODE_SLIGHT_RIGHT,
        "notification_exit_left_sdl" to GAODE_HARD_LEFT,
        "notification_exit_right_sdl" to GAODE_HARD_RIGHT,
        "notification_enter_roundabout_sdl" to GAODE_ROUNDABOUT_ENTER,
        "notification_leave_roundabout_sdl" to GAODE_ROUNDABOUT_EXIT,
        "notification_finish_sdl" to GAODE_ARRIVE,
        "notification_arrive_sdl" to GAODE_ARRIVE,
        "notification_board_ferry_sdl" to GAODE_FERRY,
        "notification_leave_ferry_sdl" to GAODE_FERRY,
        "notification_ferry_sdl" to GAODE_FERRY,
        "direction_straight" to GAODE_STRAIGHT,
        "direction_left" to GAODE_LEFT,
        "direction_right" to GAODE_RIGHT,
        "direction_slight_left" to GAODE_SLIGHT_LEFT,
        "direction_slight_right" to GAODE_SLIGHT_RIGHT,
        "direction_hard_left" to GAODE_HARD_LEFT,
        "direction_hard_right" to GAODE_HARD_RIGHT,
        "direction_uturn" to GAODE_UTURN,
        "direction_roundabout" to GAODE_ROUNDABOUT_ENTER,
        "direction_arrive" to GAODE_ARRIVE,
        "direction_ferry" to GAODE_FERRY,
        "navigation_straight" to GAODE_STRAIGHT,
        "navigation_left" to GAODE_LEFT,
        "navigation_right" to GAODE_RIGHT,
        "navigation_slight_left" to GAODE_SLIGHT_LEFT,
        "navigation_slight_right" to GAODE_SLIGHT_RIGHT,
        "navigation_hard_left" to GAODE_HARD_LEFT,
        "navigation_hard_right" to GAODE_HARD_RIGHT,
        "navigation_uturn" to GAODE_UTURN,
        "navigation_roundabout" to GAODE_ROUNDABOUT_ENTER,
        "navigation_arrive" to GAODE_ARRIVE,
        "navigation_fork_left" to GAODE_SLIGHT_LEFT,
        "navigation_fork_right" to GAODE_SLIGHT_RIGHT,
    )

    /** Donor ManeuverMapper.fromIconName collapsed to GAODE; extras-fallback smallIcon path. */
    fun richIconNameGaode(name: String): Int {
        if (name.isEmpty()) return 0
        val lower = name.lowercase().removeSuffix(".xml")
        richPhraseGaode(lower).takeIf { it != 0 }?.let { return it }
        RICH_ICON_NAMES[lower]?.let { return it }
        return when {
            lower.contains("straight") || lower.contains("go_ahead") -> GAODE_STRAIGHT
            lower.contains("hard_left") -> GAODE_HARD_LEFT
            lower.contains("hard_right") -> GAODE_HARD_RIGHT
            lower.contains("slight_left") -> GAODE_SLIGHT_LEFT
            lower.contains("slight_right") -> GAODE_SLIGHT_RIGHT
            lower.contains("uturn_right") || lower.contains("right_uturn") -> GAODE_UTURN_RIGHT
            lower.contains("uturn") -> GAODE_UTURN
            lower.contains("fork_left") -> GAODE_SLIGHT_LEFT
            lower.contains("fork_right") -> GAODE_SLIGHT_RIGHT
            lower.contains("exit_left") -> GAODE_HARD_LEFT
            lower.contains("exit_right") -> GAODE_HARD_RIGHT
            lower.contains("roundabout") -> GAODE_ROUNDABOUT_ENTER
            lower.contains("finish") || lower.contains("arrive") || lower.contains("destination") -> GAODE_ARRIVE
            lower.contains("ferry") -> GAODE_FERRY
            lower.contains("left") -> GAODE_LEFT
            lower.contains("right") -> GAODE_RIGHT
            lower.contains("forward") || lower.contains("ahead") -> GAODE_STRAIGHT
            else -> 0
        }
    }

    // -- Waze instruction text (ported from the VyacheslavRud Sea Lion 07 fork) --------------
    // Waze exposes the maneuver as localized spoken text ("Turn right onto Main St"), as a short
    // arrow description ("LEFT"), or as a resource/test tag ("TURN_RIGHT"). The Yandex tables
    // above stay byte-identical; everything below is only reached for Waze windows.

    private val WAZE_WHITESPACE = Regex("""\s+""")
    private val WAZE_SYMBOLIC_TAG = Regex("""^[A-Za-z][A-Za-z0-9]*(?:[_-][A-Za-z0-9]+)+$""")

    /**
     * Codes that describe where the car must steer, as opposed to a route-lifecycle or road-feature
     * state. Only these may be inferred by an untargeted tree scan: ARRIVE, WAYPOINT, FERRY and
     * TUNNEL are legitimate on Waze's own maneuver node, but they are also ordinary words in ETA,
     * destination and progress panels ("Прибытие 19:12"), so a scan allowed to return them labels a
     * still-running route from unrelated screen text (Sea Lion 07 field defect).
     */
    private val DIRECTIONAL_CODES = setOf(
        GAODE_LEFT, GAODE_RIGHT,
        GAODE_SLIGHT_LEFT, GAODE_SLIGHT_RIGHT,
        GAODE_HARD_LEFT, GAODE_HARD_RIGHT,
        GAODE_UTURN, GAODE_UTURN_RIGHT,
        GAODE_STRAIGHT,
        GAODE_ROUNDABOUT_ENTER, GAODE_ROUNDABOUT_EXIT,
    )

    fun isDirectionalManeuver(gaode: Int): Boolean = gaode in DIRECTIONAL_CODES

    /** Privacy-safe parsing result used by diagnostics: it exposes only recognized directions,
     *  never Waze's raw instruction or road names. [recognizedCodes] is in textual order. */
    data class ParseResult(
        val gaode: Int,
        val recognizedCodes: List<Int>,
    ) {
        fun diagnosticSummary(): String {
            val recognized = recognizedCodes.joinToString(">") { codeName(it) }.ifEmpty { "UNKNOWN" }
            return "recognized=$recognized selected=${codeName(gaode)} gaode=$gaode"
        }
    }

    private data class ManeuverPattern(val regex: Regex, val code: Int)
    private data class Candidate(val code: Int, val start: Int, val endExclusive: Int, val rank: Int) {
        val length: Int get() = endExclusive - start
        fun overlaps(other: Candidate): Boolean = start < other.endExclusive && other.start < endExclusive
    }

    private val EN_NUMBERED_EXIT_RE = Regex(
        """(?:(?:(?:at|on)\s+)?(?:the\s+)?roundabout\b.{0,48}?)?(?:take\s+)?(?:the\s+)?(\d+)(?:st|nd|rd|th)?\s+exit\b""",
        RegexOption.IGNORE_CASE,
    )
    private val RU_NUMBERED_EXIT_RE = Regex(
        """(?:(?:кольц\p{L}*|кругов\p{L}*)(?!\p{L}).{0,48}?)?(\d+)[-‑ ]?(?:й|я|е)?\s+съезд(?!\p{L})""",
        RegexOption.IGNORE_CASE,
    )

    private fun literalRegex(value: String): Regex = Regex(
        """(?<!\p{L})${Regex.escape(value)}(?!\p{L})""",
        RegexOption.IGNORE_CASE,
    )

    private val MANEUVER_PATTERNS: List<ManeuverPattern> = buildList {
        fun add(code: Int, vararg phrases: String) {
            phrases.forEach { add(ManeuverPattern(literalRegex(it), code)) }
        }

        add(GAODE_UTURN_RIGHT,
            "развернитесь направо", "разворот направо",
            "make a u-turn to the right", "make a u turn to the right", "u-turn right", "u turn right",
            "otocte se doprava", "otočte se doprava",
            "向右掉头")
        add(GAODE_UTURN,
            "развернитесь налево", "разворот налево", "развернитесь", "разворот",
            "make a u-turn", "make a u turn", "u-turn", "u turn", "掉头")
        add(GAODE_UTURN, "otocte se", "otočte se")
        add(GAODE_HARD_LEFT, "резкий поворот налево", "резко налево", "sharp left", "ostře vlevo")
        add(GAODE_HARD_RIGHT, "резкий поворот направо", "резко направо", "sharp right", "ostře vpravo")
        add(GAODE_SLIGHT_LEFT,
            "плавный поворот налево", "плавно налево", "держитесь левее", "левее",
            "slight left", "keep left", "bear left", "fork left",
            "držte se vlevo", "mírně vlevo", "靠左")
        add(GAODE_SLIGHT_RIGHT,
            "плавный поворот направо", "плавно направо", "держитесь правее", "правее",
            "slight right", "keep right", "bear right", "fork right",
            "držte se vpravo", "mírně vpravo", "靠右")
        add(GAODE_ROUNDABOUT_EXIT,
            "выезд с кольца", "съезд с кольца", "съезжайте с кольца", "выезжайте из кольца",
            "exit the roundabout", "leave the roundabout")
        add(GAODE_ROUNDABOUT_ENTER,
            "кольцевое", "круговое", "въезжайте на кольцо", "войдите в кольцо", "кольцо", "roundabout", "环岛")
        add(GAODE_FERRY, "въезд на паром", "board the ferry", "паром")
        add(GAODE_STRAIGHT, "съезд с парома", "выезд с парома", "leave the ferry")
        add(GAODE_WAYPOINT, "промежуточная точка")
        add(GAODE_ARRIVE,
            "you have arrived", "arrive at your destination", "destination reached", "reached your destination",
            "вы прибыли", "прибытие", "маршрут окончен", "маршрут завершён", "до конца маршрута",
            "конец маршрута", "конечная", "достигнут")
        add(GAODE_TUNNEL, "тоннель", "туннель", "tunnel")
        add(GAODE_LEFT,
            "поверните налево", "поворот налево", "съезд налево", "налево",
            "take the left", "turn left", "exit left",
            "odbočte vlevo", "zahněte vlevo", "doleva", "vlevo", "向左转", "左转")
        add(GAODE_RIGHT,
            "поверните направо", "поворот направо", "съезд направо", "направо",
            "take the right", "turn right", "exit right",
            "odbočte vpravo", "zahněte vpravo", "doprava", "vpravo", "向右转", "右转")
        add(GAODE_STRAIGHT,
            "продолжайте прямо", "двигайтесь прямо", "продолжайте", "двигайтесь", "прямо",
            "keep straight", "continue straight", "continue", "straight",
            "pokračujte rovně", "jeďte rovně", "rovně", "直行")
    }

    fun parseInstructionText(text: String?): ParseResult {
        if (text.isNullOrBlank()) return ParseResult(0, emptyList())
        if (text.trim() == ">>>") return ParseResult(GAODE_STRAIGHT, listOf(GAODE_STRAIGHT))
        val trimmed = text.trim()
        val symbolicTag = WAZE_SYMBOLIC_TAG.matches(trimmed)
        // Treat only a compact resource-style tag as symbolic; underscores in ordinary street
        // text must not become direction instructions.
        val normalized = trimmed.lowercase()
            .replace(' ', ' ')   // NBSP
            .replace(' ', ' ')   // narrow NBSP
            .replace('‑', '-')   // non-breaking hyphen
            .let { value ->
                if (symbolicTag) value.replace('_', ' ').replace('-', ' ') else value
            }
            .replace(WAZE_WHITESPACE, " ")

        // Waze 5.x sometimes exposes only a short accessibility description for the arrow.
        // Keep these exact so a road/street containing the word cannot become a false maneuver.
        if (normalized == "left") return ParseResult(GAODE_LEFT, listOf(GAODE_LEFT))
        if (normalized == "right") return ParseResult(GAODE_RIGHT, listOf(GAODE_RIGHT))

        val candidates = mutableListOf<Candidate>()
        fun collect(regex: Regex, code: Int, rank: Int) {
            regex.findAll(normalized).forEach { match ->
                candidates += Candidate(code, match.range.first, match.range.last + 1, rank)
            }
        }
        fun collectNumberedExit(regex: Regex, rank: Int) {
            regex.findAll(normalized).forEach { match ->
                val exit = match.groupValues.getOrNull(1)?.toIntOrNull()
                if (exit in 1..10) {
                    candidates += Candidate(
                        GAODE_ROUNDABOUT_EXIT,
                        match.range.first,
                        match.range.last + 1,
                        rank,
                    )
                }
            }
        }
        collectNumberedExit(EN_NUMBERED_EXIT_RE, rank = -2)
        collectNumberedExit(RU_NUMBERED_EXIT_RE, rank = -1)
        MANEUVER_PATTERNS.forEachIndexed { index, pattern ->
            collect(pattern.regex, pattern.code, rank = index)
        }
        if (symbolicTag) {
            // Bare direction words are accepted only inside an explicit symbolic tag. In normal
            // prose they would misread road names such as "Left Bank Road" as a maneuver.
            collect(literalRegex("left"), GAODE_LEFT, rank = MANEUVER_PATTERNS.size)
            collect(literalRegex("right"), GAODE_RIGHT, rank = MANEUVER_PATTERNS.size + 1)
        }

        // Specific overlapping phrases win ("slight right" over "right"). Non-overlapping
        // matches remain in textual order, so compound Waze instructions select the maneuver
        // the driver must perform first rather than whichever branch happened to run first.
        val ordered = candidates.sortedWith(
            compareBy<Candidate> { it.start }
                .thenByDescending { it.length }
                .thenBy { it.rank },
        )
        val semantic = mutableListOf<Candidate>()
        ordered.forEach { candidate ->
            if (semantic.none(candidate::overlaps)) semantic += candidate
        }
        val codes = semantic.sortedBy { it.start }.map { it.code }
        return ParseResult(codes.firstOrNull() ?: 0, codes)
    }

    /** Waze instruction / arrow description / resource tag -> GAODE; 0 = not a maneuver. */
    fun fromInstructionText(text: String?): Int = parseInstructionText(text).gaode

    internal fun codeName(code: Int): String = when (code) {
        GAODE_LEFT -> "LEFT"
        GAODE_RIGHT -> "RIGHT"
        GAODE_SLIGHT_LEFT -> "SLIGHT_LEFT"
        GAODE_SLIGHT_RIGHT -> "SLIGHT_RIGHT"
        GAODE_HARD_LEFT -> "HARD_LEFT"
        GAODE_HARD_RIGHT -> "HARD_RIGHT"
        GAODE_UTURN -> "UTURN_LEFT"
        GAODE_UTURN_RIGHT -> "UTURN_RIGHT"
        GAODE_STRAIGHT -> "STRAIGHT"
        GAODE_ROUNDABOUT_ENTER -> "ROUNDABOUT_ENTER"
        GAODE_ROUNDABOUT_EXIT -> "ROUNDABOUT_EXIT"
        in (GAODE_ROUNDABOUT_EXIT + 1)..(GAODE_ROUNDABOUT_EXIT + 10) -> "ROUNDABOUT_EXIT_${code - GAODE_ROUNDABOUT_EXIT}"
        GAODE_WAYPOINT -> "WAYPOINT"
        GAODE_FERRY -> "FERRY"
        GAODE_ARRIVE -> "ARRIVE"
        GAODE_TUNNEL -> "TUNNEL"
        GAODE_TOLL -> "TOLL"
        else -> "UNKNOWN"
    }
}
