# Team report 4: how openbyd 2.4.3 reads Waze (jadx sources)

## 1. Accessibility (services/BydAccessibilityService.java)
onAccessibilityEvent (:1131-1156) -> handleWazeEvent only when pref hud_navigation_app == WAZE and pkg == com.waze. Event types 2048 / 32, debounce 200 ms (:633-638). Config: typeAllMask + flagReportViewIds|flagRetrieveInteractiveWindows.
Ids (findAccessibilityNodeInfosByViewId, first node getText, :652-676):
- navBarDistance -> distance to maneuver
- navBarStreetLine -> street
- lblArrivalTime -> ETA clock (stored, never sent to HUD)
- lblTimeToDestination -> remaining time
- lblDistanceToDestination -> remaining distance
- navBarDirectionText -> toIntOrNull = roundabout exit number
- navBarDirection -> BOUNDS ONLY (getBoundsInScreen) -> WazeManager.updateArrowBounds (:704-719) = arrow crop rect
- laneGuidanceView -> BOUNDS ONLY -> updateLaneContainerBounds (:725-739) = lane strip crop rect
Proceeds only if distance/street/timeToDest/distToDest non-blank (:701). No speed-limit id for Waze (only Google Maps path :610).

## 2. Text parsing (defpackage/f70.java cases 2-4, defpackage/un0.java)
- distance `([0-9]+(?:[\.,][0-9]+)?)[\s ]*(m|м|km|км|ft|mi|ми)` -> un0.e (:302-356): km x1000, mi x1609.34, ft x0.3048 -> Int meters
- hours `(\d+)[\s ]*(?:h|hr|hrs|hour|hours|ч|ч\.)`, minutes `(\d+)[\s ]*(?:min|mins|m|мин|м)` -> un0.f (:360-406) -> Int seconds
- Street: no regex; raw navBarStreetLine -> utils/RoadNameCache (retains last name 1000 ms past blank). HudTextSanitizer.sanitize (ICU Any-Latin; Latin-ASCII, CJK passthrough) when hud_transliterator_enabled. The "then turn right onto" PREFIX_REGEXES (GoogleMapsManager.java:39-45) are Maps-only.

## 3. Notification: NOT used for Waze at all
MapNotificationListenerService has zero Waze references; largeIcon/RemoteViews paths serve Maps/Yandex only. Only Waze notification is the capture service's own foreground notification (waze_capture_channel, id 202).

## 4. Arrow classifier: 15x15 binary signature + Hamming registry
un0.b (:55-204): ARGB -> gray (R+G+B)/3; alpha composited over 255 (light) or 47 (dark) by median/mean luma; range = max-min, range < 30 -> no signature; invert = autoInvert && median >= 128; threshold = min + (invert ? (1-f)*range : f*range); downsample to 15x15 = 225 cells, cell set when >50% pixels pass -> defpackage/ij0 225-bit bitset; distance = Hamming.
Matching un0.d (:224-299): exact hit; else nearest Hamming <= 4; else 25 translated copies (dx,dy in -2..2 cells), nearest Hamming <= 18; else null.
Registry defpackage/zn1.java:19-33: 44 literal 225-char 0/1 strings -> Waze drawable names. zn1.a (:36-66) uses f=0.8, autoInvert=true. zn1.b (:68-131) strips prefixes car_dark_big_, car_big_trans_, big_trans_, big_ then maps: left=1, right=2, exit_left=3, exit_right=5, u_turn=9, u_turn_lhs/uk=10, roundabout_l=15, _r_lhs=16, _r_uk/_l_lhs=17, _r=18, _s_lhs/_uk_s=19, _s=20, _u_lhs/_u_uk=21, _u=22, roundabout=25, roundabout_lhs/_uk=35, stop=45, end=48, default/forward=11. Roundabout override (:51-56): if name contains directions_roundabout and exit number known -> exit+34 (lhs/uk) else exit+24. zn1.c rasterizes openbyd's own copy of the drawable as iconBitmap.

## 5. Screen capture (services/WazeArrowCaptureService.java, WazeManager.java)
Consent: WazeManager.startCaptureService (:106-121) reuses MediaProjectionHolder data, else launches CaptureRequestActivity (createScreenCaptureIntent) at most once per 30 s.
Service (foregroundServiceType=mediaProjection): ImageReader RGBA_8888 x2 + createVirtualDisplay(flags=16 AUTO_MIRROR) (:936-943). Loop: captureArrow() every 2000 ms (:131-144).
Two sources (:178-197): if Waze is projected to the cluster (ClusterOverlayManager.projectedSurfaceView != null && projected pkg == com.waze) -> release VirtualDisplay and PixelCopy.request(surfaceView, rect, ...); else mirror display + read Image plane (extractPixelsFromBuffer :405-418).
Rect: laneContainerBounds if present else arrowBounds (default Rect(26,115,209,298), WazeManager.java:39).
Lane segmentation (:686-784): per-column score sum over every 2nd row of |R|+|G|+|B|+|A-255| / (h/2); columns >= 30 form runs; split at gap > 8 dp; width in [8 dp, 120 dp]; each -> square of side = strip height. findActiveLane (:420-445): maxStrength > 50, first lane >= max*f (0.9 buffer / 0.75 PixelCopy).
Multi-lane (hud_waze_multi_lane_enabled, default true, :496-658): each lane classified twice, f=0.3 -> laneCodes[i], f=0.8 -> frontLanes[i]; lanes below max*f -> frontLanes=255; invalid -> laneCodes=13, frontLanes=255. LANE_ICON map zn1.d (:162-249): keep_left/fork_left/direction_left/direction_exit_left=1, straight_left_exit=2, right/fork_right/keep_right/exit_right=3, straight_right_exit=4, u_turn=5, t_junction=6, u_turn_lhs/uk=8, forward/unmatched=0. First active lane's pixels re-fed as the main arrow.

## 6. HUD data model
defpackage/c70 = HudNavigationData(iconId, distanceMeters?, roadName, remainingDistanceMeters?, remainingTimeSeconds?, secondaryRoadName, camera{Type,Distance,State}, safety{...}, trafficLight*, iconBitmap, speedLimit, currentSpeed). Waze fills only iconId, distanceMeters, roadName, remainingDistanceMeters, remainingTimeSeconds, iconBitmap (WazeManager.java:86).
defpackage/a70 = HudLaneData(distanceToSplitMeters, laneCodes IntArray, frontLanes IntArray). Units meters/seconds.

## 7. Timing
a11y debounce 200 ms; capture loop 2000 ms; consent re-prompt 30 s; expiry: checkExpirationRunnable every 1000 ms, now - lastUpdateTime > 40 s -> clearNavigation (WazeManager.java:47-65); dedupe when data equals lastSentData (:87); isProcessing AtomicBoolean drops overlapping classifications (:168); RoadNameCache 1000 ms; first text update while capture down pushes placeholder iconId=11 (:257-261).
