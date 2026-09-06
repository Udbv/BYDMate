# Team report 2: porting the VyacheslavRud fork's Waze layer into upstream 3.14.1

## 1. Fork files: verbatim vs adaptation
Near-verbatim new files:
- navdata/WazeAccessibilityReader.kt (502 L): needs NavPackages.isNavigationPackage (:132,:233,:263), NavManeuverCodes.fromInstructionText (:112,:484), parseInstructionText/ParseResult (:250,:322,:378), isDirectionalManeuver (:381), GAODE_STRAIGHT (:254)
- navdata/WazeVisualManeuverReader.kt (517 L): isNavigationPackage (:181), GAODE_LEFT/RIGHT/STRAIGHT (:478-480), AccessibilityService.takeScreenshot (:125)
- media/WazeNotificationCensus.kt (135 L): pure copy
- media/WazeRemoteViewsManeuverReader.kt (316 L): NavManeuverCodes + WazeVisualManeuverReader.classifyBitmap/Classification
- navigation/WazeNavigation.kt + WazeDeepLinkContract.kt: self-contained; only PACKAGE_NAME needed by readers

Needs adaptation (fork rewrote these Waze-only):
- NavPackages.kt: fork deletes YANDEX sets. Port additively: keep upstream :5-19, add WAZE, GUIDANCE_SOURCES = YANDEX_NAVI + YANDEX_MAPS + WAZE, isWazePackage().
- NavManeuverCodes.kt: fork replaces fromA11yDescription (upstream :27-57) with parseInstructionText/fromInstructionText/ParseResult/isDirectionalManeuver/codeName and drops NOTIFICATION_RES, fromNotificationRes, richPhraseGaode, richIconNameGaode, roadAlertFromRes, isServicePhrase. Add functions only; keep upstream :59-295.
- NavGuidance.kt: fork adds arrivalTime, mi/ft, EN/CS ETA regexes, parseDurationSeconds, normalizeArrivalTime; removes RawFields.distanceUnit/statusPanel and resolveDistance(distance, unit) (:68-75). Keep both, add arrivalTime.
- NavA11yExtractor.kt: fork delegates to WazeAccessibilityReader. Make a package switch at upstream :23.
- NavA11yFeed.kt: 484-line rewrite. Take only maneuverFromEvent wiring (:242), requestVisualManeuver (:402-412), applyVisualManeuver (:423-441). Strip DiagnosticEvidenceStore (:388).
- NavGuidanceHub.kt: fork adds hasRouteCandidate (:126), updateManeuverHint (:331), markRouteIndeterminate (:363), requestHudRefresh. Port only updateManeuverHint as a thin wrapper over upstream update() (:115-133).

## 2. Upstream touch points (Yandex-safe)
1. NavPackages.kt:19 — add WAZE to GUIDANCE_SOURCES + isWazePackage(). Re-gates NavA11yFeed.kt:185, SteeringWheelKeyService.kt:136,:147, MediaSessionListenerService.kt:54,:76, NaviRouteHolder.kt:33,:48, NaviScreenReader.kt:30.
2. NavA11yExtractor.kt:23 — if isWazePackage(pkg) return readWaze(root) via WazeAccessibilityReader.read(root, recordCensus=true); leave :25-33 for Yandex.
3. NavGuidance.kt:35 (parse) and :58 (resolveManeuver) — use fromInstructionText for Waze fields, fromA11yDescription for Yandex; add arrivalTime.
4. NavA11yFeed.kt:56 — Waze-only maneuverFromEvent hint before shouldProcess; at :87-95 in Guidance branch: if Waze && maneuverGaode==0 -> requestVisualManeuver(service, root).
5. NavGuidanceHub.kt:115 — add updateManeuverHint(gaode, Source.A11Y, nowMs) for the async screenshot callback.
6. MediaSessionListenerService.kt:143-148 — Waze branch: WazeRemoteViewsManeuverReader.inspect + WazeNotificationCensus.record before extras fallback. Keep NaviRichNotificationParser.kt:318 Yandex-only.
7. res/xml/accessibility_service_config.xml — add android:canTakeScreenshot="true" (fork's only diff there; upstream lacks it, takeScreenshot fails without it).

## 3. Screenshot-centroid arrow classifier (WazeVisualManeuverReader)
findTarget (:180-289): arrow rect by view-id list (navBarDirection..., :495-502), then regex-id scan, then navBarDistance parent subtree, then square crop above navBarDistance (:268-288). request() (:92) throttles to 1 screenshot / 900 ms, AccessibilityService.takeScreenshot (:125, API 30+). classifyPixels (:361): background from border pixels, three masks (border-contrast, bright-luma, dark-luma), largest 4-connected component (:420), compares mean x of upper 58% vs lower 68%; shift >= ±0.075 -> RIGHT/LEFT, else tall narrow head/tail ratio -> STRAIGHT (:466-482).

## 4. Tests
Verbatim: navdata/WazeAccessibilityReaderTest, WazeAccessibilityCensusTest, WazeVisualManeuverReaderTest, WazeVisualManeuverCountersTest, SeaLionWazeArrowRegressionTest, media/WazeRemoteViewsManeuverReaderTest, WazeNotificationCensusTest, navigation/WazeNavigationTest.
Merge: NavManeuverCodesTest, NavGuidanceParserTest, NavA11yExtractorTest, NaviNotificationParserTest, NaviScreenReaderTest.
Skip: SeaLionHudFlickerRegressionTest, NavGuidanceHubTest, NavA11yFeedTest/RootLossTest (fork hub/feed semantics).

## 5. Conflicts and risks
- NavPackages: fork's isNavigationPackage is Waze-only; copying it kills Yandex (SteeringWheelKeyService.kt:136-147, AgentTools.kt:2331).
- findNavigatorRoot returns first matching window; with two navigators it may hand Waze's root to the Yandex extractor. Use per-package or scored selection (fork guidanceScore, SteeringWheelKeyService.kt:122-128).
- Fork deletes markNoGuidance on unreachable window (upstream :92-93) for markRouteIndeterminate; the Waze visual path must not arm the no-guidance streak.
- fallbackManeuverScan rejects ARRIVE/FERRY/TUNNEL (:366-408, isDirectionalManeuver); porting parseInstructionText without DIRECTIONAL_CODES reintroduces the Sea Lion fake-ARRIVE(48) defect.
- WazeVisualManeuverReader is a singleton with global counters and one in-flight slot; needs API 30+ and a non-secure display.
