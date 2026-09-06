# Team report 3: openbyd 2.4.3 HUD/cluster output paths (jadx sources under openbyd-jadx/sources/com/sr/openbyd/)

CORRECTION (verified by lead): the agent reported a transaction-code mismatch (openbyd 4/5/6 vs BYDMate 3/4/5). It is NOT a mismatch:
BYDMate uses IBinder.FIRST_CALL_TRANSACTION (=1) + 3/4/5 = 4/5/6, identical to openbyd's literals. Only difference: BYDMate also registers a callback at tx 1; openbyd's server path does not.

## Design
Shipped build collapsed to a single top-level path: HudController.getStrategy() (HudController.java:106-117) always instantiates CanBydFidStrategy. It runs BOTH the CAN/FID path (updateNavigation, CanBydFidStrategy.java:126-324, gated by pref hud_send_can_messages, default true :140) and then always someIpHudHelper.updateNavigation (:325). HudController.updateNavigation also fires the Amap broadcast when hud_amap_broadcast_enabled (HudController.java:455-464). Three output families fire concurrently.

## SOME/IP sub-strategies (SomeIpHudHelper.getStrategy, :561-585) chosen ONLY by pref someip_hud_version (default LAUNCHER_MAP_CN), no auto-detect:
- LAUNCHER_MAP_CN -> LauncherMapCnStrategy (DiLink 5 "launcher map", ~11 service ids derived at runtime)
- ALTERNATIVE_UI7 -> AlternativeUi7Strategy (UI 7.0 / D6; single service id 0xB010A00010000, :24) == BYDMate's SERVICE_ID_NAVI
- ALTERNATIVE_CN_D5 -> AlternativeCnD5Strategy (7 hard-coded service ids, :27)
DiLink generation detection exists (DiLinkHelper.java:44-51: ro.vehicle.type contains "7.0UI" -> UI_7 else LEGACY; pref dilink_mode AUTO/FORCE_UI_7/FORCE_DILINK_5) but is NOT wired into HUD strategy choice; it drives cluster projection.
Other prefs: hud_amap_broadcast_enabled, hud_transliterator_enabled, hud_integration_enabled, hud_waze_multi_lane_enabled (defpackage/hj0.java:60-73).

## SOME/IP contract (SomeIpHudHelper.java:52-61)
pkg com.ts.car.someip.service, action com.ts.car.someip.SomeIpServerService, class ...manager.SomeIpServerService, descriptor ts.car.someip.sdk.ISomeIpServerInterface. TX_START=4, TX_STOP=5, TX_FIRE=6. startSomeIpService writes token+long serviceId (:1000-1024); fireEvent writes token, int 1, long topic, long 0, int len, byte[] (:508-541) — byte-identical to BYDMate.
Bind intent: UI7/Launcher use action+package (AlternativeUi7Strategy.java:92), CnD5 explicit component (:228), helper fallback to explicit component (:270-275).
Protobuf frame buildCleanFrame (:281-308) == BYDMate encoder (f2,f6,f7,f8,f9,f10,f11,f16,f26,f28,f33). gaodeToF28 differs (:543-555): openbyd maps roundabouts 24..34 -> 0 and defaults to 1; BYDMate sends 99 blank. CnD5/Launcher use larger field sets (f1-f33, guide-line strings, GPS doubles): AlternativeCnD5Strategy.buildRoadInfoCnD5, LauncherMapCnStrategy.updateNavigation.
Icon enum TURN_ICON_* HudController.java:50-98 (LEFT=1 .. TUNNEL=49), getIconName :334-437. LANE_ICON_* 0-25, INACTIVE_LANE=255.
PNG icons taken from com.byd.naviauto resources with drawn-vector fallback (SomeIpHudHelper.java:773-853).

## CAN/FID path (proxy/CarControlImpl.java, FID constants :56-140)
sendSimpleGuidanceInfo -> icon 1139806224 + dual 1139806256 + dist 1139806232 (:1599-1625); sendNextPathName -> bytes 1140461576 (:1470); sendRestRouteInfo -> mileage 1139810344, hour 1139810320, min 1139810328, sec 1139810334, ETA-min 1139839008 (:1504); sendSpeedLimitInfo -> 1083203616 + 754057272 (:1628); sendSecondaryGuidanceInfo -> 1139834896/1139834904; lanes -> 1285554200 (num) / 1285554184 (dist) / SET_LANE_STATES[] + instrument int-array 427827288/296/300 (:1400-1467); nav on/off sendAutoNaviStatus(2/4) writes 1138753594, screen 1276174357=3, statistic 1083203624 (:1235-1342). getNaviStatus reads 1138753594 (:475). Active values NAVI_STATUS_ACTIVE=2 / NAVI_SOME_IP_STATUS_ACTIVE=621 (CanBydFidStrategy.java:20,54). Each also calls the matching BYDAutoInstrumentDevice SDK method.

## SomeIpSniffer (utils/SomeIpSniffer.java)
Binds CLIENT interface: action com.ts.car.someip.SomeIpClientService, descriptors ISomeIpClientInterface / ISomeIpCallback (:46-49). registerCallback tx1, startClients tx6, per-topic startClient tx4 + subscribe tx8 over hard-coded topic list (:138-151). Callback code 1 reads long topic, long, int len(<4097), byte[]; hex dump + generic protobuf decoder parseProtobufFields (:347-493). Useful as a template for a Tang L topic sniffer.

## Privileges and keep-alive
Shell-uid proxy (Shizuku-style): ProxyManager.runShellCommandViaAdb (:548-592) via embedded ADB client to the device's own adbd; startProxy launches app_process with APK on CLASSPATH -> EntryPoint.main (EntryPoint.java:20-39) as uid 2000, builds CarControlImpl with ActivityThread.systemMain().getSystemContext() (SystemContext.java:15-26), broadcasts com.sr.openbyd.PROXY_CONNECTED with ProxyBinderParcelable. ProxyReceiver -> ProxyManager.onProxyConnected caches binder, grants via ShellCommandExecutor (appops SYSTEM_ALERT_WINDOW / PROJECT_MEDIA, cmd notification allow_listener; ShellCommandExecutor.java:84-103, HudSetupHelper.java:80-103). Same model as BYDMate's helper daemon.
Keep-alive: startKeepAliveJob (:232-240) re-emits last frame every 200 ms (sendKeepAliveUpdate :216-230), counter & 255. On reconnect re-calls startSomeIpService for all ids (:67-95). BYDMate: 300 ms loop + backoff bind.

## Verify on Tang L / DiLink 150
- ro.vehicle.type contains "7.0UI"? (DiLinkHelper.java:50)
- Which someip_hud_version works: LAUNCHER_MAP_CN (DiLink 5) vs ALTERNATIVE_UI7 (single id 0xB010A00010000, what BYDMate already sends)
- Gateway package/class present; fallback resolve (SomeIpHudHelper.java:52-53,270-275)
- Service id 0xB010A00010000 / topic 0x4010a00018001 valid on DiLink 150 AR-HUD
- f28 roundabout: prefer BYDMate's 99
- CAN FIDs exist on DiLink 150 instrument; keep hud_send_can_messages off if glitchy (CarControlImpl.java:1599-1655)
- getNaviStatus FID 1138753594 active values 2/621
- ADB self-connect + app_process bootstrap reachable
- com.byd.naviauto resources for PNG icons may differ
