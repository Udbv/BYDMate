package com.bydmate.app.navdata

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.bydmate.app.cluster.SteeringWheelKeyService

/** Passive a11y event feed: Navigator window events -> NavGuidanceHub.
 *  Gated by [enabled] (set by HudController) so that users without the HUD feature
 *  pay a single volatile read per event. Debounced: guidance widgets update ~1/s,
 *  a11y events fire far more often. */
@Suppress("TooManyFunctions") // the event, timer and source reads plus their diagnostic probes
object NavA11yFeed {
    private const val TAG = "NavA11yFeed"
    /** openbyd's Waze debounce. The arrow and the distance arrive as separate events a few tens of
     *  milliseconds apart, and at 500 ms one of the two was routinely dropped. */
    private const val DEBOUNCE_MS = 200L
    /** Cycle guard for the parent climb: a stale tree can hand back a looping chain. */
    private const val MAX_PARENT_HOPS = 64
    /** Lane-widget research dump: bounded so a deep tree cannot stall the a11y thread
     *  or flood logcat. Fires once per maneuver, i.e. about once a minute while driving. */
    private const val TREE_DUMP_MAX_NODES = 300
    private const val TREE_DUMP_MAX_CHARS = 3500
    /** Floor between two walks, independent of the maneuver code: the extractor can
     *  alternate codes faster than the driver passes intersections. */
    private const val TREE_DUMP_MIN_INTERVAL_MS = 30_000L
    private const val NO_MANEUVER = Int.MIN_VALUE
    /** Navigator windows described per no-guidance streak, so a stray window list stays short. */
    private const val TRACE_MAX_WINDOWS = 6
    /** A standing car changes nothing on screen, so no events come and the hub would expire
     *  the maneuver (MANEUVER_TIMEOUT_MS) while the navigator still shows it: the window is
     *  re-read when no read happened for this long, looked at on the same period. */
    private const val TIMER_READ_MS = 5_000L

    /** Re-enabling starts a fresh diagnostic episode: the transition-only flags below
     *  would otherwise survive a HUD off/on cycle and swallow the first edge log. */
    @Volatile var enabled: Boolean = false
        set(value) {
            if (value && !field) {
                rootReachable = true
                sourceFallbackWorking = false
                lastDumpedGaode = NO_MANEUVER
                lastDumpMs = 0L
                seenWazeIds.clear()
                newWazeIdsLogged = 0
                seenWazeTexts.clear()
                newWazeTextsLogged = 0
                lastThenText = null
                lastReportsLine = null
                unknownManeuvers.reset()
                noGuidanceTrace.reset()
                timerKeptAlive = false
                resetTimer(start = true)
            }
            // A blind spell does not outlive the feed it was seen by.
            if (value != field) NavGuidanceHub.a11yBlind(false)
            field = value
            if (!value) resetTimer(start = false)
        }

    /** Where the tree dump goes; logcat in production, a collector in tests. */
    internal var treeDumpSink: (String) -> Unit = { Log.i(TAG, it) }
    /** Where the no-guidance trace goes; logcat in production, a collector in tests. */
    internal var traceSink: (String) -> Unit = { Log.i(TAG, it) }

    @Volatile internal var lastProcessMs = 0L
    // Transition-only log guard: "events flowing but window unreachable" is exactly the
    // agent-blindness symptom, but it repeats every debounce tick — log edges, not ticks.
    @Volatile private var rootReachable = true
    // Same edge discipline for the event-source fallback, tracked separately so the
    // field logs say which of the two paths is feeding the hub.
    @Volatile private var sourceFallbackWorking = false
    // Maneuver the tree was last dumped for; NO_MANEUVER means "nothing dumped yet".
    @Volatile private var lastDumpedGaode = NO_MANEUVER
    @Volatile internal var lastDumpMs = 0L
    // Raw maneuver values logged as unrecognised and when; its floor is the walk's lastDumpMs.
    private val unknownManeuvers = UnknownManeuverGate(minIntervalMs = 0L)
    // Streak, walk floor and missing-window edge of the no-guidance trace (issue #199).
    private val noGuidanceTrace = NoGuidanceTrace()
    // Edge guard for the timer's keep-alive line: set by the first timer refresh of a quiet
    // episode, cleared by the next event read.
    @Volatile private var timerKeptAlive = false
    // Main-looper handler of the current enable; a tick from an older one stops itself.
    @Volatile private var timer: Handler? = null

    /** Where timer reads get the window from; the live a11y service in production. */
    internal var timerService: () -> SteeringWheelKeyService? = { SteeringWheelKeyService.instance }

    /** Waze event-carried maneuver (see [WazeAccessibilityReader.maneuverFromEvent]): several
     *  Waze layouts update the distance node first and emit the arrow as a separate event whose
     *  semantic value is gone by the time the debounced window read runs. Consumed by the next
     *  Guidance read that has no maneuver of its own; expires after [EVENT_MANEUVER_TTL_MS]. */
    private const val EVENT_MANEUVER_TTL_MS = 10_000L
    @Volatile private var pendingEventManeuverGaode = 0
    @Volatile private var pendingEventManeuverMs = 0L

    /** Field diagnostics at info level (release strips Log.d): one line whenever the
     *  maneuver, distance or road changes, at most every [READ_LOG_MIN_MS]. */
    private const val READ_LOG_MIN_MS = 3_000L
    @Volatile private var lastLoggedRead: NavGuidance? = null
    @Volatile private var lastLoggedReadMs = 0L

    private fun logRead(pkg: String?, data: NavGuidance, nowMs: Long) {
        val prev = lastLoggedRead
        val changed = prev == null || prev.maneuverGaode != data.maneuverGaode ||
            prev.distanceMeters != data.distanceMeters || prev.road != data.road
        if (!changed || (prev != null && nowMs - lastLoggedReadMs < READ_LOG_MIN_MS)) return
        lastLoggedRead = data
        lastLoggedReadMs = nowMs
        Log.i(TAG, "a11y read: $pkg gaode=${data.maneuverGaode} dist=${data.distanceMeters} " +
            "road='${data.road}' eta=${data.etaSeconds}s total=${data.totalDistMeters} limit=${data.speedLimit}")
        com.bydmate.app.diagnostics.TripDebugLog.event(
            "READ",
            "$pkg gaode=${data.maneuverGaode} (${NavManeuverCodes.codeName(data.maneuverGaode)}) " +
                "dist=${data.distanceMeters} road='${data.road}' eta=${data.etaSeconds}s " +
                "total=${data.totalDistMeters} limit=${data.speedLimit}",
        )
    }

    /** Where a "then" line goes; the trip log in production, a collector in tests. */
    internal var thenSink: (String) -> Unit = {
        com.bydmate.app.diagnostics.TripDebugLog.event("THEN", it)
    }

    @Volatile private var lastThenText: String? = null

    /**
     * One line per *change* of the `navBarThenDirection` glyph.
     *
     * The character is drawn from Waze's own icon font, so it is neither a word nor anything the
     * maneuver tables know; its code points are the key we need to map onto the panel's secondary
     * maneuver icons, and a drive's worth of them is the only way to build that table. Nothing is
     * sent to the panel from here.
     */
    internal fun logThen(text: String) {
        if (text.isEmpty()) return
        if (text == lastThenText) return
        lastThenText = text
        thenSink("glyph='$text' ${codePoints(text)}")
    }

    /** `U+XXXX` per code point, surrogate pairs folded into the one character they encode. */
    internal fun codePoints(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (out.isNotEmpty()) out.append(' ')
            out.append("U+%04X".format(cp))
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    fun onEvent(service: SteeringWheelKeyService, event: AccessibilityEvent?) {
        if (!enabled) return
        val nowMs = System.currentTimeMillis()
        val pkg = event?.packageName?.toString()
        if (NavPackages.isWazePackage(pkg) && (event?.eventType ?: 0) != 0) {
            // Runs BEFORE the debounce on purpose: the arrow event is often the one dropped by it.
            val hint = runCatching { WazeAccessibilityReader.maneuverFromEvent(event) }.getOrDefault(0)
            if (hint > 0) {
                pendingEventManeuverGaode = hint
                pendingEventManeuverMs = nowMs
            }
        }
        if (!shouldProcess(pkg, event?.eventType ?: 0, nowMs, lastProcessMs)) return
        lastProcessMs = nowMs
        timerKeptAlive = false
        // An unreachable window says NOTHING about the route: the navigator may be
        // minimized, covered by another pane, or projected onto a private VirtualDisplay
        // while guidance keeps running (field-confirmed, issue #144). A navigator that
        // really closed is caught reader-side: ACTIVE_TIMEOUT_MS drops active when no
        // source refreshes, MANEUVER_TIMEOUT_MS expires the arrow, and the notification
        // lane's removal grace deactivates too.
        val root = runCatching { service.findNavigatorRoot() }.getOrNull()
            ?: run {
                val readViaSource = readViaEventSource(event, nowMs)
                if (readViaSource) {
                    if (!sourceFallbackWorking) {
                        sourceFallbackWorking = true
                        Log.i(TAG, "navigator window hidden; guidance read via event source")
                        NavGuidanceHub.a11yBlind(false, nowMs)
                    }
                } else if (rootReachable || sourceFallbackWorking) {
                    sourceFallbackWorking = false
                    Log.w(TAG, "Navigator events flowing but window unreachable (a11y feed blind)")
                    NavGuidanceHub.a11yBlind(true, nowMs)
                }
                rootReachable = false
                return
            }
        if (!rootReachable) {
            rootReachable = true
            sourceFallbackWorking = false
            Log.i(TAG, "Navigator window reachable again")
            NavGuidanceHub.a11yBlind(false, nowMs)
        }
        readWindow(root, nowMs, "event", service)
    }

    /**
     * One short line saying why no guidance is reaching the hub.
     *
     * Silence used to be unreadable: a whole drive produced not one line from this feed or the
     * push loop, so "we never wrote anything" and "we wrote once and stopped" looked identical
     * afterwards. Every distinguishable state is named here, and [HudPushLoop] prints it while
     * the loop is idle.
     */
    fun idleReason(nowMs: Long = System.currentTimeMillis()): String {
        if (!enabled) return "feed disabled (HUD projection off?)"
        if (com.bydmate.app.cluster.SteeringWheelKeyService.instance == null) {
            return "accessibility service not bound - grant it in Android settings"
        }
        if (lastProcessMs == 0L) return "accessibility bound but no navigator event has ever arrived"
        val sinceS = (nowMs - lastProcessMs) / 1000
        if (!rootReachable) {
            return "navigator window unreachable for ${sinceS}s" +
                (if (sourceFallbackWorking) " (event-source fallback reading)" else " (no fallback either)")
        }
        return "reads happening (last ${sinceS}s ago) but they carry no guidance - route ended or not started"
    }

    /** Timer tick (main looper, like the events): the same window read as [onEvent] when a
     *  route is guided and neither events nor the timer read for [TIMER_READ_MS]. An
     *  unreachable window says nothing here either, and there is no event source to fall
     *  back to. */
    internal fun onTimer(service: SteeringWheelKeyService?, nowMs: Long) {
        if (!shouldTimerRead(enabled, NavGuidanceHub.snapshot(nowMs).active, nowMs, lastProcessMs)) return
        service ?: return
        lastProcessMs = nowMs
        val root = runCatching { service.findNavigatorRoot() }.getOrNull() ?: run {
            if (noGuidanceTrace.navigatorMissing()) traceSink("timer read: navigator window not found")
            return
        }
        if (readWindow(root, nowMs, "timer", service) && !timerKeptAlive) {
            timerKeptAlive = true
            Log.i(TAG, "timer re-read keeps maneuver alive")
        }
    }

    /** Reads a reachable navigator [root] into the hub and recycles it: widgets with text =
     *  guidance; widgets without text only keep the route alive, as in the donor; a navigator
     *  without them changes nothing (issue #199). Both of the latter are traced. True when
     *  guidance was read.
     *  [src] and [service] only feed the no-guidance trace. */
    private fun readWindow(
        root: AccessibilityNodeInfo,
        nowMs: Long,
        src: String,
        service: SteeringWheelKeyService,
    ): Boolean {
        noGuidanceTrace.navigatorFound()
        try {
            val pkg = runCatching { root.packageName?.toString() }.getOrNull() ?: "?"
            // Before the extractor, and whatever it finds: a camera alert can be on screen while
            // the guidance widgets are not.
            discoverNewIds(root)
            return when (val result = NavA11yExtractor.read(root)) {
                is NavA11yExtractor.ReadResult.Guidance -> {
                    val data = withWazeManeuverHint(root, result.data, nowMs)
                    NavGuidanceHub.update(data, NavGuidanceHub.Source.A11Y, nowMs)
                    logRead(pkg, data, nowMs)
                    logThen(data.thenText)
                    dumpTreeOnManeuverChange(root, data.maneuverGaode, nowMs)
                    logUnknownManeuver(root, data, nowMs)
                    traceGuidanceAgain(src, nowMs)
                    // Unconditionally, not only when the text path drew a blank: on this Waze the
                    // arrow IS the maneuver, and gating the classifier behind gaode==0 meant one
                    // bogus text match (an ARRIVE parsed off an unrelated panel) silenced it for
                    // the whole drive.
                    // Lanes before the screenshot request: the reader is what puts the lane
                    // strip's bounds where the classifier looks for them, and the classifier
                    // segments the strip before it falls back to the arrow crop.
                    readLanes(root)
                    readReports(root, nowMs)
                    requestWazeVisualManeuver(service, root, data.exitNumber)
                    true
                }
                is NavA11yExtractor.ReadResult.NoGuidance -> {
                    val kept = NavA11yExtractor.hasGuidanceNodes(root)
                    NavGuidanceHub.countA11yNoGuidance()
                    if (kept) NavGuidanceHub.keepAlive(nowMs)
                    traceNoGuidance(root, nowMs, src, service, kept)
                    if (lastLoggedRead != null) {
                        lastLoggedRead = null
                        Log.i(TAG, "a11y read: $pkg window without guidance widgets")
                    }
                    false
                }
                is NavA11yExtractor.ReadResult.NotNavigator -> false
            }
        } finally {
            @Suppress("DEPRECATION")
            runCatching { root.recycle() }
        }
    }

    /** Drops the current enable's timer; [start] posts a fresh one. */
    private fun resetTimer(start: Boolean) {
        timer?.removeCallbacksAndMessages(null)
        timer = null
        if (!start) return
        val handler = Handler(Looper.getMainLooper())
        timer = handler
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (timer !== handler) return
                onTimer(timerService(), System.currentTimeMillis())
                handler.postDelayed(this, TIMER_READ_MS)
            }
        }, TIMER_READ_MS)
    }

    /** Last resort when window enumeration cannot see the Navigator (projected onto a
     *  PRIVATE VirtualDisplay on DiLink 5.1, field-confirmed issue #134): the event still
     *  carries a live source node whose parent chain reaches the window root.
     *  ONLY a positive guidance read is accepted — that root may be a sub-window (balloon,
     *  dialog) where missing widgets say nothing about the route, so this path only adds
     *  data; widgets without text keep an active route alive, as in [readWindow]. Returns
     *  true when the hub was fed. */
    private fun readViaEventSource(event: AccessibilityEvent?, nowMs: Long): Boolean {
        val root = climbToWindowRoot(runCatching { event?.source }.getOrNull()) ?: return false
        try {
            // read() re-checks the package: the climb can land in a host window that merely
            // embeds the Navigator, and a foreign root reads as NotNavigator.
            val result = NavA11yExtractor.read(root)
            if (result is NavA11yExtractor.ReadResult.NoGuidance && NavA11yExtractor.hasGuidanceNodes(root)) {
                NavGuidanceHub.keepAlive(nowMs)
            }
            if (result !is NavA11yExtractor.ReadResult.Guidance) return false
            val data = withWazeManeuverHint(root, result.data, nowMs)
            NavGuidanceHub.update(data, NavGuidanceHub.Source.A11Y, nowMs)
            dumpTreeOnManeuverChange(root, data.maneuverGaode, nowMs)
            logUnknownManeuver(root, data, nowMs)
            traceGuidanceAgain("event", nowMs)
            return true
        } finally {
            @Suppress("DEPRECATION")
            runCatching { root.recycle() }
        }
    }

    /** Highest reachable ancestor of [node] (the node itself when it has no parent).
     *  Intermediate nodes are recycled on the way up; the returned one is the caller's. */
    private fun climbToWindowRoot(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node ?: return null
        repeat(MAX_PARENT_HOPS) {
            val parent = runCatching { current.parent }.getOrNull() ?: return current
            @Suppress("DEPRECATION")
            runCatching { current.recycle() }
            current = parent
        }
        return current
    }

    /** Research probe for the lane-guidance widget: on a new maneuver, list the ids the
     *  Navigator exposes to accessibility so a recorded log shows whether the lane hint is
     *  in the tree at all. [root] belongs to the caller and is never recycled here. */
    private fun dumpTreeOnManeuverChange(
        root: AccessibilityNodeInfo,
        maneuverGaode: Int,
        nowMs: Long,
    ) {
        // A blinked-out maneuver description reads as 0 while the route keeps running, so
        // it is neither dumped nor remembered: 2 -> 0 -> 2 must stay one dump.
        if (maneuverGaode <= 0) return
        if (maneuverGaode == lastDumpedGaode) return
        if (nowMs - lastDumpMs < TREE_DUMP_MIN_INTERVAL_MS) return
        lastDumpedGaode = maneuverGaode
        lastDumpMs = nowMs
        runCatching {
            val ids = StringBuilder()
            appendIds(root, ids, intArrayOf(TREE_DUMP_MAX_NODES))
            treeDumpSink("nav tree [gaode=$maneuverGaode]:${ids.take(TREE_DUMP_MAX_CHARS)}")
        }
    }

    // -- Waze node discovery ---------------------------------------------------------------
    // Camera and speed-limit alerts are drawn by Waze views nobody has named yet, and the
    // maneuver-change dump above is both rate-limited and text-free, so a view that appears for
    // ten seconds when a camera is announced never shows up in it. This walk runs on every Waze
    // read and writes one line the first time an id is seen in a session, with its text — this
    // is the diagnostics build, and the trip log already keeps street names and stays on the car.

    /** How many nodes one discovery walk may visit. 300 stopped short of the maneuver and
     *  report lists, which hang far down the tree and are exactly what needs naming. */
    private const val DISCOVERY_MAX_NODES = 800
    /** How many *new* ids a session may report; a Waze that recycles ids cannot flood the log. */
    internal const val DISCOVERY_MAX_NEW_IDS = 200
    /** How much of a node's text and description one line carries. */
    private const val DISCOVERY_MAX_TEXT = 60

    /** Where a discovery line goes; the trip log in production, a collector in tests. */
    internal var newIdSink: (String) -> Unit = {
        com.bydmate.app.diagnostics.TripDebugLog.event("TREE", it)
    }

    /**
     * Containers whose rows carry no view id at all - the maneuver list, the reports list and the
     * instruction panel. An id-only walk records the container and nothing inside it, so the rows
     * that actually say what Waze is showing were never written down. Inside these subtrees the
     * walk also records id-less nodes that carry text or a description.
     */
    private val DISCOVERY_TEXT_CONTAINERS = setOf(
        "routeDetailsRecycler",
        "eventsOnRouteContainer",
        "eventsOnRouteView",
        "instructionView",
    )

    /** How many id-less text lines a session may report; the lists scroll, the log must not. */
    internal const val DISCOVERY_MAX_NEW_TEXTS = 300

    private val seenWazeIds = HashSet<String>()
    @Volatile private var newWazeIdsLogged = 0
    /** Deduped by (text, desc) so a list redrawn on every frame is written once. */
    private val seenWazeTexts = HashSet<String>()
    @Volatile private var newWazeTextsLogged = 0

    /** One line per id never seen this session. Waze only; [root] belongs to the caller. */
    private fun discoverNewIds(root: AccessibilityNodeInfo) {
        if (!NavPackages.isWazePackage(runCatching { root.packageName?.toString() }.getOrNull())) return
        if (newWazeIdsLogged >= DISCOVERY_MAX_NEW_IDS &&
            newWazeTextsLogged >= DISCOVERY_MAX_NEW_TEXTS) return
        runCatching { walkNewIds(root, intArrayOf(DISCOVERY_MAX_NODES), under = null) }
    }

    private fun budgetSpent(budget: IntArray): Boolean = budget[0] <= 0 ||
        (newWazeIdsLogged >= DISCOVERY_MAX_NEW_IDS && newWazeTextsLogged >= DISCOVERY_MAX_NEW_TEXTS)

    /**
     * [under] is the id of the nearest enclosing [DISCOVERY_TEXT_CONTAINERS] ancestor, or null
     * outside them; it both switches the id-less text lines on and names them in the log.
     */
    private fun walkNewIds(node: AccessibilityNodeInfo, budget: IntArray, under: String?) {
        if (budgetSpent(budget)) return
        budget[0]--
        val id = runCatching { node.viewIdResourceName }.getOrNull()?.substringAfter(":id/")
        val text = clip(runCatching { node.text?.toString() }.getOrNull())
        val desc = clip(runCatching { node.contentDescription?.toString() }.getOrNull())
        val cls = runCatching { node.className?.toString() }.getOrNull()
            ?.substringAfterLast('.') ?: ""
        if (!id.isNullOrEmpty()) {
            if (seenWazeIds.add(id) && newWazeIdsLogged < DISCOVERY_MAX_NEW_IDS) {
                newWazeIdsLogged++
                newIdSink("new id=$id text='$text' desc='$desc' class=$cls bounds=${boundsOf(node)}")
            }
        } else if (under != null && (text.isNotEmpty() || desc.isNotEmpty()) &&
            newWazeTextsLogged < DISCOVERY_MAX_NEW_TEXTS
        ) {
            if (seenWazeTexts.add("$text\u0000$desc")) {
                newWazeTextsLogged++
                newIdSink("new text='$text' desc='$desc' class=$cls under=$under bounds=${boundsOf(node)}")
            }
        }
        val childUnder = if (!id.isNullOrEmpty() && id in DISCOVERY_TEXT_CONTAINERS) id else under
        val children = runCatching { node.childCount }.getOrDefault(0)
        for (i in 0 until children) {
            if (budgetSpent(budget)) return
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            try {
                walkNewIds(child, budget, childUnder)
            } finally {
                @Suppress("DEPRECATION")
                runCatching { child.recycle() }
            }
        }
    }

    /** A guided route with a distance but no recognised maneuver: one line with the raw
     *  maneuver node and one id walk per distinct value per 5 min, so a recorded log shows what the
     *  parse could not map. Waits for the same floor as [dumpTreeOnManeuverChange], checked
     *  before the probe so a read inside it costs no extra lookup. [root] is the caller's. */
    private fun logUnknownManeuver(root: AccessibilityNodeInfo, data: NavGuidance, nowMs: Long) {
        if (!UnknownManeuverGate.applies(data.distanceMeters, data.maneuverGaode) { NavGuidanceHub.snapshot(nowMs).active }) return
        if (nowMs - lastDumpMs < TREE_DUMP_MIN_INTERVAL_MS) return
        val node = NavA11yExtractor.probeManeuver(root)
        if (!unknownManeuvers.take(node, nowMs)) return
        lastDumpMs = nowMs
        runCatching {
            treeDumpSink("nav maneuver unknown [a11y]: $node")
            val ids = StringBuilder()
            appendIds(root, ids, intArrayOf(TREE_DUMP_MAX_NODES))
            treeDumpSink("nav tree [gaode=0]:${ids.take(TREE_DUMP_MAX_CHARS)}")
        }
    }

    /** Issue #199: a window read without guidance that starts a streak while a route is guided
     *  logs what the read saw (window, navigator windows, widget counts, no text), at most once
     *  per 5 s, then the id walk with its node count behind its own 60 s floor. The route state
     *  is read without expiries, so the trace writes nothing into the hub. [kept]: the read kept
     *  the route alive (widgets without text). [root] is the caller's. */
    private fun traceNoGuidance(
        root: AccessibilityNodeInfo,
        nowMs: Long,
        src: String,
        service: SteeringWheelKeyService,
        kept: Boolean,
    ) {
        val skipped = noGuidanceTrace.startStreak(nowMs) { NavGuidanceHub.isActiveNow() } ?: return
        val windows = runCatching { service.navigatorWindowRoots() }.getOrNull()
        try {
            runCatching {
                traceSink("no-guidance read: src=$src ${NavA11yExtractor.windowFacts(root)} " +
                    "navWindows=${windows?.size ?: "?"} skipped=$skipped kept=$kept ${NavA11yExtractor.countIds(root)}")
                traceWindows(root, windows.orEmpty())
                if (!noGuidanceTrace.takeDump(nowMs)) return@runCatching
                val ids = StringBuilder()
                val budget = intArrayOf(TREE_DUMP_MAX_NODES)
                appendIds(root, ids, budget)
                val walked = TREE_DUMP_MAX_NODES - budget[0]
                val nodes = if (budget[0] <= 0) "$walked+" else "$walked"
                traceSink("nav tree [no-guidance nodes=$nodes]:${ids.take(TREE_DUMP_MAX_CHARS)}")
            }
        } finally {
            @Suppress("DEPRECATION")
            windows?.forEach { runCatching { it.recycle() } }
        }
    }

    /** One line per Navigator window (at most [TRACE_MAX_WINDOWS]): the guidance widgets may
     *  live in another window than the one [SteeringWheelKeyService.findNavigatorRoot] handed
     *  to the read (issue #199). [read] and [windows] stay the caller's. */
    private fun traceWindows(read: AccessibilityNodeInfo, windows: List<AccessibilityNodeInfo>) {
        val readId = runCatching { read.windowId }.getOrNull()
        for (window in windows.take(TRACE_MAX_WINDOWS)) {
            runCatching {
                val isRead = readId != null && runCatching { window.windowId }.getOrNull() == readId
                traceSink("no-guidance window: ${NavA11yExtractor.windowFacts(window)} read=$isRead " +
                    NavA11yExtractor.countIds(window))
            }
        }
    }

    /**
     * `l,t,r,b` in screen coordinates, `-` when the node has none.
     *
     * One number per side is what answers "which widget is this" from a recorded drive: the lane
     * strip, the hint widget beside it and the reports list are indistinguishable by id alone.
     */
    private fun boundsOf(node: AccessibilityNodeInfo): String {
        val rect = android.graphics.Rect()
        runCatching { node.getBoundsInScreen(rect) }.getOrElse { return "-" }
        if (rect.width() <= 0 && rect.height() <= 0) return "-"
        return "${rect.left},${rect.top},${rect.right},${rect.bottom}"
    }

    private fun clip(value: String?): String =
        (value ?: "").replace('\n', ' ').take(DISCOVERY_MAX_TEXT)

    /** The guidance read that ends a no-guidance streak says how long the streak lasted. */
    private fun traceGuidanceAgain(src: String, nowMs: Long) {
        val lasted = noGuidanceTrace.endStreak(nowMs) ?: return
        traceSink("guidance read again after $lasted ms (src=$src)")
    }

    /** Depth-first, [budget] nodes at most; only ids are collected, text is reduced to its
     *  length (screen text is the driver's route, not diagnostic data). */
    private fun appendIds(node: AccessibilityNodeInfo, out: StringBuilder, budget: IntArray) {
        if (budget[0] <= 0) return
        budget[0]--
        runCatching { node.viewIdResourceName }.getOrNull()?.let { id ->
            out.append(' ').append(id.substringAfter(":id/"))
            val textLength = runCatching { node.text?.length }.getOrNull() ?: 0
            if (textLength > 0) out.append(":t").append(textLength)
        }
        val children = runCatching { node.childCount }.getOrDefault(0)
        for (i in 0 until children) {
            if (budget[0] <= 0) return
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            try {
                appendIds(child, out, budget)
            } finally {
                @Suppress("DEPRECATION")
                runCatching { child.recycle() }
            }
        }
    }

    // -- Waze arrow fallbacks -------------------------------------------------------------
    // Waze draws its arrow as an image; the tree read often yields distance + street but no
    // maneuver. Two fallbacks, both Waze-only and both hints for an ALREADY active route:
    // (1) a maneuver carried by a recent a11y event, (2) a bounded screenshot of the arrow
    // ImageView classified by shape (WazeVisualManeuverReader, needs canTakeScreenshot).

    /** Fills a missing Waze maneuver from the pending event hint when it is still fresh. */
    private fun withWazeManeuverHint(
        root: AccessibilityNodeInfo,
        data: NavGuidance,
        nowMs: Long,
    ): NavGuidance {
        if (data.maneuverGaode != 0) return data
        if (!NavPackages.isWazePackage(runCatching { root.packageName?.toString() }.getOrNull())) return data
        val hint = pendingEventManeuverGaode
        if (hint <= 0 || nowMs - pendingEventManeuverMs > EVENT_MANEUVER_TTL_MS) return data
        return data.copy(maneuverGaode = hint)
    }

    private fun requestWazeVisualManeuver(
        service: SteeringWheelKeyService,
        root: AccessibilityNodeInfo,
        exitNumber: Int?,
    ) {
        if (!NavPackages.isWazePackage(runCatching { root.packageName?.toString() }.getOrNull())) return
        // The exit number goes into the hub BEFORE the request: the screenshot completes
        // asynchronously, and by the time a roundabout arrow is recognised the read that carried
        // its number is long gone. openbyd keeps the same single "last exit number" for exactly
        // this reason.
        NavGuidanceHub.setExitNumber(exitNumber)
        // request() reads the tree synchronously (the caller recycles root afterwards) and
        // classifies on the service executor; only the resulting glyph reaches the hub.
        runCatching {
            WazeVisualManeuverReader.request(service, root, exitNumber) { classification ->
                if (enabled) applyWazeArrow(classification, exitNumber)
            }
        }
    }

    /** Applies one classified arrow. Logs edges only: the classifier re-reads the same arrow
     *  every two seconds for the whole approach to a turn. */
    internal fun applyWazeArrow(
        classification: WazeVisualManeuverReader.Classification,
        exitNumber: Int?,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (NavGuidanceHub.updateWazeArrow(classification.panelIcon, exitNumber, nowMs)) {
            Log.i(
                TAG,
                "Waze arrow '${classification.matchedName}' hamming=${classification.hamming} " +
                    "icon=${classification.panelIcon} exit=$exitNumber",
            )
        }
    }

    /** Lane strip for the junction ahead; Waze only, and only while the HUD wants it. Logged on
     *  change so a field dump shows whether the strip was seen at all. */
    @Volatile private var lastLaneCount = -1

    private fun readLanes(root: AccessibilityNodeInfo) {
        if (!NavPackages.isWazePackage(runCatching { root.packageName?.toString() }.getOrNull())) return
        val lanes = runCatching { WazeLaneReader.read(root) }.getOrDefault(NavLanes.NONE)
        NavLaneState.update(lanes)
        if (lanes.lanes.size != lastLaneCount) {
            lastLaneCount = lanes.lanes.size
            WazeLaneReader.logRead(lanes)
        }
    }

    /**
     * Waze's own reports list (`navListReportsList`), for the panel's camera sign.
     *
     * The list is plain text - a type and a distance per row - which is the one camera source on
     * this build that is neither a drawable nor a notification. The nearest camera row ahead goes
     * into the hub; [NavGuidanceHub] clears it by time when the list stops showing one.
     */
    @Volatile private var lastReportsLine: String? = null

    private fun readReports(root: AccessibilityNodeInfo, nowMs: Long) {
        if (!NavPackages.isWazePackage(runCatching { root.packageName?.toString() }.getOrNull())) return
        val reports = runCatching { WazeReportsReader.read(root) }.getOrDefault(emptyList())
        NavGuidanceHub.updateReports(reports, nowMs)
        val line = reports.joinToString(" | ") { "${it.kind}:'${it.typeText}'@${it.distanceMeters}" }
        if (line == lastReportsLine) return
        lastReportsLine = line
        reportSink(if (line.isEmpty()) "none" else line)
    }

    /** Where a reports line goes; the trip log in production, a collector in tests. */
    internal var reportSink: (String) -> Unit = {
        com.bydmate.app.diagnostics.TripDebugLog.event("REPORT", it)
    }
    /** Pure gate of the timer read: the feed on, a route guided, no read for [TIMER_READ_MS]. */
    fun shouldTimerRead(enabled: Boolean, guidanceActive: Boolean, nowMs: Long, lastMs: Long): Boolean =
        enabled && guidanceActive && nowMs - lastMs >= TIMER_READ_MS

    /** Pure gate, unit-tested separately from the framework-bound onEvent. */
    fun shouldProcess(pkg: String?, eventType: Int, nowMs: Long, lastMs: Long): Boolean {
        if (pkg == null || pkg !in NavPackages.GUIDANCE_SOURCES) return false
        if (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return false
        return nowMs - lastMs >= DEBOUNCE_MS
    }
}
