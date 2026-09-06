# BYDMate + Waze → HUD: investigation and extension plan

Date: 2026-09-06. Target car: BYD Tang L EV 2025 (DiLink 150, optional AR-HUD).

## 1. BYDMate (github.com/AndyShaman/BYDMate, v3.14.1)

Kotlin + Jetpack Compose, Hilt, minSdk 29 / targetSdk 29 / compileSdk 34, license PolyForm Noncommercial.
Tested on Leopard 3, Sea Lion 07, Song, Atto 3, Seal, Han; DiLink 3.0 / 5.0 / 5.1 / UI7. Tang L is not mentioned anywhere (README, changelog, issues).

### HUD pipeline (already exists, Yandex only)

```
Yandex Navigator / Yandex Maps
  ├─ a11y feed  : SteeringWheelKeyService(AccessibilityService) → NavA11yFeed → NavA11yExtractor → NavGuidanceParser
  └─ notification: MediaSessionListenerService(NotificationListenerService) → NaviRichNotificationParser / NaviNotificationParser → NaviRichPostProcessor
                       ↓
              NavGuidanceHub (single merged snapshot: maneuverGaode, distanceMeters, road, etaSeconds,
                              totalDistMeters, speedLimit, maneuverPng, camera*)
                       ↓ every 300 ms
              HudPushLoop
                ├─ HudProtobufBuilder → HudSomeIpBridge.fireEvent(TOPIC_NAVI)   (factory HUD, com.ts.car.someip)
                └─ HudAmapBroadcaster  (AUTONAVI_STANDARD_BROADCAST_SEND → com.byd.amapservice, "like OpenBYD")
```

Key files (app/src/main/kotlin/com/bydmate/app/):
- navdata/NavPackages.kt: the ONLY list of guidance packages (Yandex Navi + Maps). Used in 9 places (listener, a11y feed/extractor, route holder, screen reader, window lookup).
- navdata/NavA11yExtractor.kt: reads Yandex view ids (`image_maneuverballoon_maneuver`, `text_maneuverballoon_distance`, `text_nextstreet`, `textview_eta_time`, `text_speedlimit`, ...).
- navdata/NavGuidance.kt (NavGuidanceParser): regexes are Russian-first (`км|km`, `м|m`, `мин`, `ч`). No `mi`, `ft`, `min`, `h`.
- navdata/NavManeuverCodes.kt: Russian phrase → GAODE maneuver code (1 left, 2 right, 3/4 slight, 7/8 sharp, 9/10 U-turn, 11 straight, 13 roundabout enter, 24(+N) roundabout exit N, 45 waypoint, 46 ferry, 47 toll, 48 arrive, 49 tunnel).
- navdata/NavGuidanceHub.kt: merge rules, 90 s active timeout, 30 s maneuver/speed-limit freshness, a11y has 10 s priority over notification.
- hud/HudProtobufBuilder.kt: hand-rolled protobuf frame (f8 = maneuver PNG, f9 distance, f10 road, f11 speed limit, f26 ETA, f28 animated chevron, f7 speed sign PNG). Lane fields f5/f29 reserved, unused.
- hud/HudIconLoader.kt + assets/navi/0x<gaode>.png: 48 icons, 38x41 px.
- hud/HudController.kt: probes SOME/IP gateway package; self-enables the a11y service through the helper daemon (ADB on-device); persists `hud_supported`.
- ui/settings/SettingsScreen.kt ~line 1271 and res/values/strings.xml lines 1309-1319: HUD toggles, Yandex-only wording.

Issue #108 ("add Google Maps or Waze to HUD", July 2026) was closed with no visible maintainer reply, so nothing upstream is in progress.

## 2. openbyd 2.4.3 (E:\apk\openbyd-2.4.3.apk, package com.sr.openbyd)

Closed source (no public repo found). Analysis was done from dex/manifest strings only: no Java/jadx/apktool is installed on this machine, so exact logic is inferred from class names, log lines, regexes and pref keys.

Navigation sources: `YandexManager`, `GoogleMapsManager`, `WazeManager` (WAZE_PACKAGE = com.waze), fed by
`MapNotificationListenerService` (NotificationListenerService, RemoteViews.mActions reflection, `getLargeIcon`) and `BydAccessibilityService`.

How Waze is read:
1. Accessibility tree of the Waze window. View ids present in the dex that belong to Waze:
   `navBarDirection`, `navBarDirectionText`, `navBarDistance`, `navBarStreetLine`, `lblArrivalTime`,
   `lblDistanceToDestination`, `lblTimeToDestination`, `laneGuidanceView`, `roadsign_container`, `speed_limit`.
   (Google Maps ids also present: `step_instruction_container`, `next_step_instruction_container`,
   `navigation_time_remaining_label`, `top_cue_text`, `bottom_cue_text`, `distance_text`.)
2. English regexes: distance `(\d+[.,]?\d*)\s*(km|m|mi|ft)`, time `(\d+)\s*(min|mins|h|hr|hours)`, and
   instruction cleanup `^(then )?(turn|keep|merge|take the exit/ramp) (left|right|slight|sharp)... onto` → street name.
   Maneuver from text: `getTurnIconFromManeuverText`, `cleanNextStepInstruction`.
3. Arrow classification: Waze draws the arrow in a SurfaceView with no content description, so openbyd
   (a) hashes the notification `largeIcon` bitmap ("Maneuver Icon Perceptual Signature", "Signature not matched in registry"), and
   (b) `WazeArrowCaptureService`: MediaProjection + mirroring VirtualDisplay + PixelCopy of the Waze SurfaceView
       (`captureDefaultArrowFromSurfaceView`, `captureLaneGuidanceFromSurfaceView`) for the arrow and for multi-lane
       guidance (`hud_waze_multi_lane_enabled`, LANE_ICON_* enum, "Active/Full Lane Signature NOT matched").
   It has its own icon set in assets `hud_arrows/` and a `HudTextSanitizer` + `hud_transliterator_enabled` (Cyrillic → Latin for the glass).

HUD output: `HudOutputStrategy` with `SomeIpHudStrategy` (uses the ts.car.someip SDK AIDL: `sendSimpleGuidanceInfo(iconId..)`,
`sendSecondaryGuidanceInfo`, `sendLaneGuidanceInfo`, `sendNextPathName`, `sendRestRouteInfo`, `sendSpeedLimitInfo`,
`sendCameraGuidanceInfo`, `sendSafeGuidanceInfo`), `CanBydFidStrategy` (`hud_send_can_messages`, direct CAN fids for
speedLimit / laneNum / etaMinute), `LauncherMapCnStrategy`, `AlternativeCnD5Strategy`, `AlternativeUi7Strategy`,
plus the Amap broadcast (`hud_amap_broadcast_enabled`). Pref `someip_hud_version` selects the SDK variant.

Difference that matters for Tang L: BYDMate writes a raw protobuf frame through `fireEvent`; openbyd calls the
typed SDK methods of the same SOME/IP service. Both bind `com.ts.car.someip`. Whether DiLink 150 on Tang L exposes
that service in the same shape is unverified; BYDMate's `HudSomeIpBridge.isServicePresent` probe will answer that on the car.

## 3. Extension plan: Waze as a guidance source in BYDMate

Phase 1 (core, no screen capture):
1. `NavPackages`: add `WAZE = setOf("com.waze")`, include in `GUIDANCE_SOURCES`. This alone makes the listener,
   a11y feed and window lookup accept Waze windows/notifications.
2. `NavA11yExtractor`: branch on package. For Waze read `navBarDirectionText` (instruction), `navBarDistance`,
   `navBarStreetLine`, `lblTimeToDestination`, `lblDistanceToDestination`, `lblArrivalTime`, `speed_limit`.
   Verify ids first with BYDMate's existing a11y tree dump (NavA11yFeed dumps the tree once per maneuver to logcat).
3. `NavGuidanceParser`: locale-aware units (`km|m|mi|ft`, `min|h|hr`, plus existing RU). Convert mi/ft to meters.
4. `NavManeuverCodes.fromWazeInstruction(text)`: EN (+RU/UK if Waze UI language is set so) phrases →
   GAODE codes: "turn left/right", "keep left/right" → slight, "sharp", "make a U-turn", "at the roundabout take the Nth exit" → 24+N,
   "exit left/right", "continue/straight", "arrive/destination" → 48.
5. Notification lane: Waze posts an ongoing notification with text extras and a `largeIcon` arrow bitmap. Add a
   `WazeNotificationParser` that fills a `RichUpdate` from extras and passes the largeIcon as `maneuverPng`
   (HudPushLoop already prefers the built-in icon and falls back to `maneuverPng`, so an unclassified arrow still shows).
6. Settings/strings: rename "Yandex Navigator" wording to "navigator", add Waze to the hint; optional source picker.
7. Tests: extend NavManeuverCodesTest, NavGuidanceParserTest, NavA11yExtractorTest; add WazeNotificationParserTest.

Phase 2 (optional, what openbyd does extra): perceptual-hash registry for Waze arrows, lane guidance through
PixelCopy screen capture (needs MediaProjection consent and f5/f29 frame fields that BYDMate has not enabled).

Risks / unknowns:
- Tang L / DiLink 150 is untested by BYDMate; the SOME/IP gateway, helper daemon (ADB) and a11y self-enable may differ.
- Waze view ids come from string evidence, not decompiled code; confirm on-car.
- Waze on a GMS-less head unit: you already run it with openbyd, so it is presumably installed and working.
- License: PolyForm Noncommercial allows a personal fork; openbyd is closed source, so port behaviour, not code.

Useful next tools: install a JDK + jadx to decompile openbyd for exact regexes, view ids and the SomeIp SDK AIDL;
install Android Studio to build BYDMate (`./gradlew :app:testDebugUnitTest`).

## 4. Existing fork: github.com/VyacheslavRud/BYDMate (added after first pass)

Branched from upstream v3.6.1 (2026-07-16); last commit 2026-07-22; versionName 3.6.38.
26 commits ahead of upstream, 118 commits behind (upstream is at 3.14.1). Target car: 2025 Sea Lion 07 EV (China), DiLink 5.0, Android 12.

What it did:
- Replaced Yandex with Waze as the ONLY guidance source (`NavPackages` contains just `com.waze`); voice/automation
  "navigate" actions open Waze via `WazeDeepLinkContract`/`WazeNavigation`.
- `navdata/WazeAccessibilityReader.kt` (502 lines): reads Waze 5.21 view ids `navBarDirectionText`, `navBarInstructionText`,
  `navBarDirection`, `navBarDistance`, `navBarStreetLine`, `navBarTowardStreetLine`, `etaBar*/minimizedEtaBar*/lbl*`
  (distance/time/arrival), `speedLimitWarn`; scores multiple Waze windows; bounded fallback tree scan; numbered roundabout exits.
- `navdata/NavManeuverCodes.fromInstructionText`: EN/RU/CS/ZH phrase tables ("turn left", "keep right", "make a u-turn",
  "at the roundabout take the Nth exit", "you have arrived", ...).
- `navdata/NavGuidance.kt`: km/m/mi/ft, min/h, 12h/24h arrival regexes.
- `media/WazeRemoteViewsManeuverReader.kt` + `WazeNotificationCensus.kt`: notification RemoteViews / icon inspection for the arrow.
- `navdata/WazeVisualManeuverReader.kt`: last-resort arrow classification by `AccessibilityService.takeScreenshot` of the
  arrow ImageView rectangle (pixel centroid shift → left/right/straight). Same idea as openbyd's capture, but without MediaProjection.
- Production HUD frame on Sea Lion deliberately minimal and field-confirmed: f9 distance, f10 road, f28 chevron
  (2 right, 3 left, 7 U-turn left, 10 U-turn right, 11 straight). No f7/f8 PNGs, no f11 speed, no f26 ETA, no f33 progress.
  Note: this f28 table differs from upstream's (upstream: 1 left, 2 right, 3/5 slight, 7/8 U-turn). Which one is right is
  firmware-specific and must be verified on the Tang L.
- Big diagnostics additions: HUD Lab / Cluster Lab screens, HudIncidentRecorder, system-stack collector script, three
  sanitized docs under docs/guides (compatibility passport, cluster architecture, in-car test plan).

Assessment for the Tang L goal:
- The Waze reading layer (reader + phrase tables + unit parsers + tests) is directly reusable and saves most of Phase 1.
- It cannot be used as-is: Yandex was ripped out, it is 6 weeks / 118 commits behind upstream, and everything is tuned
  to one Sea Lion firmware. Merging upstream into it will conflict heavily in NavGuidanceHub, NavA11yFeed, HudController,
  HudPushLoop, HudProtobufBuilder, SettingsScreen/ViewModel and strings (all rewritten on both sides).
- Recommended: start from upstream 3.14.1 and cherry-pick / port the fork's Waze files as an additional source next to
  Yandex, keeping upstream's hub, push loop and frame builder; take the fork's f28 findings as a hypothesis to test.

## 5. Team findings (2026-09-06, four parallel analyses; full reports in team-reports/)

Toolchain now installed: Temurin JDK 17, jadx 1.5.6, apktool 3.0.3 (%LOCALAPPDATA%\re-tools). openbyd decompiled to
C:\Users\Bohda\AppData\Local\Temp\bydw\openbyd-jadx (53 app source files) and openbyd-apktool (manifest/res/smali).

Key conclusions that change or sharpen the plan:
1. BYDMate and openbyd use the SAME HUD channel, byte for byte: package com.ts.car.someip.service, descriptor
   ts.car.someip.sdk.ISomeIpServerInterface, transactions start=4/stop=5/fire=6, service id 0xB010A00010000, same parcel layout
   and same protobuf field order. (A team report claimed a 3/4/5 vs 4/5/6 mismatch; verified false: FIRST_CALL_TRANSACTION=1.)
   openbyd's UI7 strategy == what BYDMate already sends. So the Tang L question is purely "does DiLink 150 expose this gateway",
   answerable with BYDMate's existing probe or openbyd's SomeIpSniffer approach.
2. Both apps get privileges the same way: on-device ADB (127.0.0.1:5555) spawns an app_process helper as shell uid which runs
   settings put secure enabled_accessibility_services, cmd notification allow_listener, appops. Since openbyd works on the
   user's Tang L, ADB is presumably already reachable there.
3. openbyd does NOT use Waze notifications at all. Waze data = accessibility text (navBarDistance, navBarStreetLine,
   lblTimeToDestination, lblDistanceToDestination, navBarDirectionText = exit number) + PIXELS of the navBarDirection bounds
   captured via MediaProjection every 2 s, classified by a 15x15 binary signature against a 44-entry registry of Waze
   drawables (Hamming <= 4 exact, <= 18 with +-2 cell translation). Lanes: same capture on laneGuidanceView bounds.
4. The fork solves the same arrow problem with AccessibilityService.takeScreenshot (no MediaProjection consent dialog) and a
   centroid heuristic (left/right/straight only). Best path: fork's screenshot plumbing + openbyd's signature approach ->
   full maneuver set (roundabout exits, U-turns, exits, arrive) without a consent dialog.
   Requires android:canTakeScreenshot="true" in accessibility_service_config.xml (missing upstream).
5. Port plan is additive and Yandex-safe: 7 upstream touch points (NavPackages, NavA11yExtractor, NavGuidance, NavA11yFeed,
   NavGuidanceHub.updateManeuverHint, MediaSessionListenerService Waze branch, a11y config). Five fork files copy verbatim,
   eight fork tests copy verbatim. Watch: NavPackages must stay a union; findNavigatorRoot needs per-package selection when
   two navigators are installed; keep isDirectionalManeuver gating to avoid the fake-ARRIVE defect.
6. f28 chevron: upstream 1=left/2=right/3=slight left/5=slight right/7,8=U-turns/11=straight/99=blank (Leopard 3);
   fork says 2=right/3=left/7,10=U-turn (Sea Lion 07); openbyd maps roundabouts to 0/1 (phantom chevron). Firmware-specific:
   test on the Tang L with the fork's HUD Lab before trusting any table.

Recommended implementation order:
  A. On-car probes on Tang L (no code): gateway package present, ADB reachable, ro.vehicle.type, Waze view ids via a11y dump.
  B. Port fork Waze reader + phrase tables + unit parsers + tests into upstream 3.14.1 (Phase 1 from section 3).
  C. Arrow: fork takeScreenshot plumbing + Kotlin reimplementation of a 15x15 signature matcher with the registry
     regenerated from Waze's own drawables (not copied from openbyd).
  D. Verify f28 table and PNG (f8) rendering on the Tang L HUD; then lanes (f5/f29) as Phase 2.
