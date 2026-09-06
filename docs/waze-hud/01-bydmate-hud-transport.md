# Team report 1: BYDMate HUD transport and self-grant mechanism (upstream 3.14.1)

## SOME/IP HUD channel (hud/HudSomeIpBridge.kt)
- Package `com.ts.car.someip.service` (:37); action `com.ts.car.someip.SomeIpServerService` (:38); class `com.ts.car.someip.service.manager.SomeIpServerService` (:39).
- Descriptors: `ts.car.someip.sdk.ISomeIpServerInterface` (:40), callback `ts.car.someip.sdk.ISomeIpCallback` (:41).
- Raw Binder transactions (no AIDL stub): registerCb = FIRST_CALL_TRANSACTION (:47), +3 startService (:48), +4 stopService (:49), +5 fireEvent (:50).
- TOPIC_NAVI = 0x4010a00018001 (:52), SERVICE_ID_NAVI = 0xB010A00010000 (:53).
- fireEvent parcel (:252-261): writeInterfaceToken(DESC), writeInt(1), writeLong(topic), writeLong(0), writeInt(size), writeByteArray(payload).
- Probe isServicePresent = pm.getPackageInfo("com.ts.car.someip.service") (:57-58).
- Bind must be explicit + setType(ctx.packageName) (:196-206); typeless bind crashes the gateway process (hosts factory ARHUD). Fallback resolveService(action, setPackage) (:214-222). Backoff 0/1/3/7 s, 15 s connect each.

## Protobuf frame (hud/HudProtobufBuilder.kt)
Outer 0x0A + varint(len) + inner. Inner order: f2=2, f6=6 if speed sign else 1, f7 speed-sign PNG, f8 maneuver PNG, f9 distance (floor 11 m), f10 road, f11 speed limit, f16=2, f26 ETA "HH:MM", f28 chevron, f33 fixed64 progress. Clear frame: f2=counter, f6=255, f16=1. Never emit f3/f4/f12/f17/f18/f21..25/f30/f31. Caps 65536 B, road 200 chars, f7 dropped first.
f28 table: 0->0, 1->1, 2->2, 3->3, 4->5, 7->1, 8->2, 9->7, 10->8, 11/12->11, 13..44->99, else 99. Native enum: 1 left, 2 right, 3 slight left, 5 slight right, 7 U-turn left, 8 U-turn right, 11 straight, 99 blank.

## Self-granting
HudController.startSequence :135 -> HelperBootstrap.ensureRunning() -> HelperClient.enableAccessibilityService() (data/vehicle/HelperClient.kt:508, TX +15, helper/HelperBinderProtocol.kt:108) -> helper/HelperDaemon.kt:366.
Daemon spawn via on-device ADB 127.0.0.1:5555 (data/autoservice/AdbOnDeviceClient.kt:154-190):
`CLASSPATH=<base.apk> setsid app_process /system/bin --nice-name=bydmate_helper com.bydmate.app.helper.HelperDaemon <uid> <token>`
A11y grant (HelperDaemon.kt:1453-1471): `settings put secure enabled_accessibility_services ...` remove-pause-readd, then `accessibility_enabled 1`. Component com.bydmate.app/com.bydmate.app.cluster.SteeringWheelKeyService.
Notification listener (HelperDaemon.kt:1489-1513): `cmd notification allow_listener <component>`, fallback via enabled_notification_listeners. Component com.bydmate.app/com.bydmate.app.media.MediaSessionListenerService. Self-heal from TrackingService.kt:209-225.

## Tang L / DiLink 150 probe checklist
1. Package com.ts.car.someip.service exists (else HUD silently OFF) — HudSomeIpBridge.kt:37,57; HudController.kt:126-131
2. Class ...manager.SomeIpServerService exists or action resolves — HudSomeIpBridge.kt:39,214-222
3. Descriptor string unchanged — :40
4. Transaction codes +0/+3/+4/+5 unchanged (dump real AIDL from gateway APK) — :47-50
5. SERVICE_ID_NAVI / TOPIC_NAVI valid for DiLink 150 ARHUD — :52-53
6. setType(packageName) still required — :196-206
7. Frame field order + f28 enum match Tang L HUD firmware — HudProtobufBuilder.kt:7,13,32-52
8. adbd listens on 127.0.0.1:5555 and grant persists — AdbOnDeviceClient.kt:154; HelperBootstrap.kt:225
9. app_process, setsid, settings, cmd notification present; shell uid may write Secure keys — AdbOnDeviceClient.kt:182; HelperDaemon.kt:1464-1467,1499
10. Daemon can publish its binder (addService may be refused -> broadcast fallback) — HelperBootstrap.kt:168-175
11. NavPackages has no Waze entry; findNavigatorRoot (SteeringWheelKeyService.kt:133) and NavA11yExtractor are Yandex-shaped — navdata/NavPackages.kt:6-15
12. Optional Amap receiver com.byd.amapservice — HudAmapBroadcaster.kt:20
13. 300 ms push cadence accepted — HudPushLoop.kt:27
