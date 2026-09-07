package com.bydmate.app.hud

import android.util.Log
import com.bydmate.app.navdata.NavGuidanceHub
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 300 ms push loop: NavGuidanceHub snapshot -> protobuf frame -> SOME/IP fireEvent.
 *  When guidance ends (hub goes inactive) exactly one clear frame wipes the HUD.
 *  [speedSignEnabled] gates the rendered speed-limit sign (f7) and is read every tick,
 *  so the settings toggle applies within one period without restarting the loop. */
class HudPushLoop(
    private val sink: HudEventSink,
    private val speedSignEnabled: () -> Boolean = { true },
    private val nowMsProvider: () -> Long = { System.currentTimeMillis() },
    /** Channel A broadcaster; null keeps all existing tests unchanged. */
    internal val amap: HudAmapBroadcaster? = null,
    /** Maneuver-change journal for the diagnostic dump; null = no journalling (tests). */
    private val maneuvers: HudManeuverJournal? = null,
    /** Frame layout for the connected glass; read every tick (settings switch, no restart). */
    private val dialect: () -> HudDialect = { HudDialect.CLASSIC },
    /** Remaining minutes -> localized "9 min" for the AR-HUD f27 slot; null = omit. */
    private val remainFormatter: (Int) -> String? = { null },
    /** AR-HUD sub-channels (docs/investigations/tang-l-hud-someip.md): the road-info frame on
     *  the HUD service and the instrument-panel display features. Each gate is read per tick so
     *  the settings switches apply without a restart.
     *
     *  Both targets are display surfaces. BYDMate never emits on the services the driving
     *  computer consumes for guidance (NavigationStatus_LinkInfo, SDMapInform, the SD route):
     *  a channel that did was removed before 3.15.0 — it changed nothing on the Tang L glass
     *  and those services feed the driving domain. */
    private val roadInfoEnabled: () -> Boolean = { true },
    internal val instrumentFids: HudInstrumentFids? = null,
    private val instrumentFidsEnabled: () -> Boolean = { false },
) {
    companion object {
        private const val TAG = "HudPushLoop"
        const val PERIOD_MS = 300L
        private const val NO_MANEUVER = Int.MIN_VALUE
        /** Field-diagnostics cadence: one info line per ~10 s of frames (release keeps Log.i). */
        private const val LOG_EVERY_FRAMES = 33L
    }

    private var job: Job? = null
    private var counter = 0   // clear frames only; guidance frames carry the constant 2 in f2

    // Last journalled maneuver state; NO_MANEUVER means "nothing recorded yet in this guidance
    // session", so the first frame of a new session is always written.
    private var journalledGaode = NO_MANEUVER
    private var journalledSuppress = false

    /** §5 diagnostics, read by the settings dump via HudController.diag(). */
    @Volatile var framesSent: Long = 0L; private set
    @Volatile var lastFrameTs: Long = 0L; private set
    @Volatile var lastRc: Int = 0; private set
    @Volatile var nonZeroRcCount: Long = 0L; private set

    fun start(scope: CoroutineScope, periodMs: Long = PERIOD_MS) {
        if (job?.isActive == true) return
        job = scope.launch {
            var wasActive = false
            while (isActive) {
                wasActive = runCatching { tick(wasActive) }.getOrDefault(wasActive)
                delay(periodMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        amap?.onStop()
        // Switch-off while guidance is active: the controller sends the SOME/IP clear frame,
        // but the instrument panel keeps whatever was written last until the navigation status
        // goes back to "stopped" (field-confirmed on the Tang L: the arrow stayed on the glass).
        instrumentFids?.stop()
        journalledGaode = NO_MANEUVER
    }

    /** One tick; returns whether guidance was active (input for the next tick). */
    internal fun tick(wasActive: Boolean): Boolean {
        val s = NavGuidanceHub.snapshot(nowMsProvider())
        val d = dialect()
        if (!s.active) {
            if (wasActive) {
                val rc = sink.fireEvent(HudSomeIpBridge.TOPIC_NAVI, HudProtobufBuilder.buildClearFrame(counter++, d))
                Log.i(TAG, "guidance ended, clear frame sent rc=$rc dialect=$d after $framesSent frames")
                instrumentFids?.stop()
            }
            amap?.onSnapshot(null)
            journalledGaode = NO_MANEUVER
            return false
        }
        // The classic glass gets the sign as a PNG in f7; the AR-HUD draws its own from f11/f15.
        val signPng = if (d == HudDialect.CLASSIC && speedSignEnabled() && s.speedLimit > 0) HudSpeedSign.render(s.speedLimit) else null
        // Camera takeover (donor LoopRunner): while a camera alert is active the icon
        // slot (f8) shows the camera, f9 counts down to the camera, and the reference
        // arrow (f28) is suppressed so the HUD doesn't draw a stale maneuver arrow.
        val cameraActive = s.cameraAlert.isNotEmpty()
        val baseIcon = HudIconLoader.iconFor(s.maneuverGaode) ?: s.maneuverPng
        val frame = HudProtobufBuilder.buildFrameSafe(
            maneuverGaode = s.maneuverGaode,
            distanceMeters = if (cameraActive && s.cameraDistanceMeters > 0) s.cameraDistanceMeters else s.distanceMeters,
            road = runningLine(s),
            etaString = etaString(s.etaSeconds),
            totalDistMeters = s.totalDistMeters,
            speedLimit = s.speedLimit,
            maneuverIconPng = if (cameraActive) s.cameraIconPng ?: baseIcon else baseIcon,
            speedSignPng = signPng,
            suppressArrow = cameraActive,
            dialect = d,
            etaSeconds = s.etaSeconds,
            remainString = if (s.etaSeconds > 0) remainFormatter((s.etaSeconds + 59) / 60) else null,
        )
        val roadInfo = d == HudDialect.CLASSIC || roadInfoEnabled()
        val rc = if (roadInfo) sink.fireEvent(HudSomeIpBridge.TOPIC_NAVI, frame) else 0
        framesSent++
        lastFrameTs = System.currentTimeMillis()
        lastRc = rc
        if (rc != 0) nonZeroRcCount++
        if (d == HudDialect.AR_HUD) {
            if (instrumentFidsEnabled()) instrumentFids?.update(s)
        }
        if (!wasActive || framesSent % LOG_EVERY_FRAMES == 0L) {
            Log.i(TAG, "frame #$framesSent rc=$rc dialect=$d bytes=${frame.size} gaode=${s.maneuverGaode} " +
                "f28=${if (d == HudDialect.AR_HUD) HudProtobufBuilder.gaodeToArHudId(s.maneuverGaode) else HudProtobufBuilder.gaodeToF28(s.maneuverGaode)} " +
                "dist=${s.distanceMeters} total=${s.totalDistMeters} eta=${s.etaSeconds}s limit=${s.speedLimit} " +
                "icon=${(if (cameraActive) s.cameraIconPng ?: baseIcon else baseIcon)?.size ?: 0} road='${runningLine(s)}'" +
                (if (d == HudDialect.AR_HUD) " roadInfo=$roadInfo " +
                    "fids=${instrumentFidsEnabled()}/${instrumentFids?.writes ?: 0}/fail${instrumentFids?.failures ?: 0} fix=${HudVehicleState.fix != null}" else ""))
        }
        amap?.onSnapshot(s)
        journalManeuver(s, cameraActive)
        return true
    }

    /** Records what both channels carried, but only when the maneuver code or the
     *  arrow-suppression flag changed — the loop itself runs twice a second (#94). */
    private fun journalManeuver(s: NavGuidanceHub.Snapshot, suppressArrow: Boolean) {
        val journal = maneuvers ?: return
        if (s.maneuverGaode == journalledGaode && suppressArrow == journalledSuppress) return
        journalledGaode = s.maneuverGaode
        journalledSuppress = suppressArrow
        val amapBroadcasting = amap?.capable == true
        journal.append(
            maneuverGaode = s.maneuverGaode,
            distanceMeters = s.distanceMeters,
            f28 = if (suppressArrow) 0 else HudProtobufBuilder.gaodeToF28(s.maneuverGaode),
            amapIcon = if (amapBroadcasting) HudAmapBroadcaster.gaodeToAmapIcon(s.maneuverGaode) else null,
            roundaboutNum = if (amapBroadcasting && s.maneuverGaode in 25..34) s.maneuverGaode - 24 else null,
            suppressArrow = suppressArrow,
        )
    }

    /** Donor running line (f10): beyond 3 km to go, enrich the street with remaining
     *  time and wall-clock arrival: "<road> | ЧЧ:ММ мин | ЧЧ:ММ". */
    internal fun runningLine(s: NavGuidanceHub.Snapshot, d: HudDialect = dialect()): String {
        // The AR-HUD has its own ETA slots (f26/f27): f10 stays the plain road name.
        if (d == HudDialect.AR_HUD) return s.road
        if (s.totalDistMeters <= 3000 || s.etaSeconds <= 0 || s.road.isEmpty()) return s.road
        val etaTotalMin = s.etaSeconds / 60
        val remStr = String.format(Locale.US, "%02d:%02d", etaTotalMin / 60, etaTotalMin % 60)
        return "${s.road} | $remStr мин | ${etaString(s.etaSeconds)}"
    }

    /** Remaining seconds -> wall-clock arrival "HH:MM" (f26); null when unknown. */
    internal fun etaString(etaSeconds: Int): String? {
        if (etaSeconds <= 0) return null
        val cal = Calendar.getInstance().apply { timeInMillis = nowMsProvider() + etaSeconds * 1000L }
        return String.format(Locale.US, "%02d:%02d",
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    }
}
