# Tang L (DiLink 150) HUD over SOME/IP: what the car actually speaks

Field investigation 2026-09-06 on the user's BYD Tang L EV 2025 (DiLink150_7.0UI, DiLink 5.1,
Android 13, AR-HUD) after BYDMate Waze v3.15.0-dev.1 bound the gateway successfully but nothing
appeared on the glass. Sources pulled from the car live in `D:\BYD\DiLink` (not in the repo):
`/system/etc/someip/someip_stack.json`, `/system/lib64/libsomeipimpl{,_proto}.so`,
`/system/app/SomeIpService/SomeIpService.apk`, `/system/app/BydLaunchermap/BydLaunchermap.apk`
(571 MB, the factory map), `/system/priv-app/AmapService/AmapService.apk`,
`/system/priv-app/VehicleDialog/VehicleDialog.apk`.

## What worked out of the box

- `com.ts.car.someip.service` exists; BYDMate binds it, `registerCallback rc=0`,
  `startService(0xb010a00010000) rc=0`, status "HUD output active".
- The helper daemon self-grants the accessibility service; the a11y feed reads Waze while
  Waze is in the foreground (a maneuver was held in the hub and expired 30 s after BYDMate's own
  window covered Waze). `ro.vehicle.type = DiLink150_7.0UI`.

## The service is right, the key and the field semantics are not

`someip_stack.json` defines service **266 (0x010A) instance 1**, UDP 52001, events
0x8001..0x8003 in eventgroup 0x1101 - the `HudNaviInfoService` (application ids 0x0101 server /
0x0106 client). The proto (`someip.hud.navi.info.service.proto`, compiled into
`libsomeipimpl_proto.so` and as Java lite classes inside the map app) is the schema upstream
already hand-encodes:

| field | name | type | Tang L map fills it with |
|---|---|---|---|
| 1 | checksum | int | never |
| 2 | counter | int | constant 2 (1 in one branch) |
| 3 | car_2_dest | int | route remaining, m |
| 4 | time_of_car_2_dest | int | route remaining, s |
| 5 | num_of_lanes | int | lanes |
| 6 | current_road_level | int | road class |
| 7 | permissible_direction | bytes | lane image |
| 8 | recommended_driving_directions_for_ajotp | bytes | maneuver PNG |
| 9 | distance_2_intersection | int | m to maneuver |
| 10 | next_road_name | string | next road |
| 11 / 15 | current_max_speed_limit / speed_limit | int | limit |
| 12 / 21 | current_speed / vehicle_speed | int | speed |
| 16 | navigating_status | int | 2 navigating, 1 idle |
| 19 / 20 / 22 / 32 | lon / lat / altitude / heading | double, int | GNSS |
| 26 | eta_info_time | string | arrival "HH:mm" |
| 27 | eta_info_remain_time | string | remaining time text |
| 28 | recommendedDrivingDirectionsId | int | **raw Gaode maneuver code** |
| 29 / 30 / 31 | lanesPermissibleDirectionId / guideline / guidepoint | string | JSON |
| 33 | navigatingRatio | double | progress 0..1 |

Event topic for this struct: `0x4010a00018001` (`HudRoadInfo_EG`, event 0x8001) - identical to
BYDMate's `TOPIC_NAVI`. Event 0x8003 (`HudNavigationmap`) carries a base64 JPEG/PNG of the map
for the AR overlay, event 0x8002 the path info.

### Difference 1: the service key

The gateway's `startService(long)` takes a key `0xB << 48 | (service << 16 | low) << 16`.
Upstream (Atto 3 / Seal / Sea Lion, byd-hud donor) opens `0xB010A00010000` (low word 1). The
Tang L map (`BydLaunchermap`, class `SomeIPDataHudManager`, log tag "Launcher150Pro :
PlatformHudImpl") opens **`0xB010A00020000`** (low word 2) before firing on the same topic, and
closes it with `stopSomeIpService` when guidance ends. Which SOME/IP attribute the low word is
(instance, major version) could not be read from the closed daemon; the fix mirrors the car.

### Difference 2: the arrow

Upstream maps Gaode codes to the classic glass enum (`gaodeToF28`: 4 -> 5, 7/8 -> 1/2,
9/10 -> 7/8, roundabouts and destination -> 99 blank). The Tang L map sends the Gaode code itself,
via table `k.h.j.e.b.a` (Amap icon type -> Gaode code):
`{0,0,1,2,3,5,7,8,9,11,45,13,24,46,47,48,49,14,23,10,12,15,18,20,22,16,17,19,21}`,
i.e. 1 left, 2 right, 3 slight left, 5 slight right, 7/8 sharp, 9 U-turn, 11 straight, 13 enter
roundabout, 24 exit roundabout, 45 waypoint, 46 service area, 47 toll, 48 destination, 49 tunnel.
The AR-HUD has glyphs for roundabouts and the destination.

### Difference 3: the speed-sign trick

Upstream puts a rendered speed-limit PNG into f7 with f6 = 6. On the Tang L f7 is the lane
image and f6 the road class; the glass draws its own limit from f11/f15. The AR-HUD dialect omits
both and sends the limit twice.

### Clear frame

Upstream: f2 counter, f6 = 255, f16 = 1. Tang L map: all fields at defaults, f16 = 1, f2 = 2
(`clearAndSendNullData`), then `stopSomeIpService`.

## What BYDMate does now (v3.15.0-dev.2)

`HudDialect` (Settings -> Display -> HUD -> HUD type: Auto / Classic / AR-HUD):

- Auto resolves AR-HUD when `ro.vehicle.type` contains `DiLink150`.
- AR-HUD opens both keys (`0x...0002...` first, then the classic one), builds the Tang L field
  set (`HudProtobufBuilder.buildArHudFrame`, `gaodeToArHudId`), and sends the Tang L clear frame.
- Field diagnostics at info level survive the release build: `HudPushLoop` logs the first frame
  of a session and every 33rd frame with rc, dialect, code, distance and road; `NavA11yFeed`
  logs each changed Waze read; `HudController` logs the resolved dialect and the keys opened.

Verify on the car with Waze navigating in the foreground:

```bash
adb -s 192.168.0.166:5555 logcat -v time HudController:I HudSomeIpBridge:I HudPushLoop:I NavA11yFeed:I NavGuidanceHub:I VehicleDialogDismisser:I *:S
```

## Export warning ("This car is not from official export")

`com.byd.vehicledialog` (`NotOfficialExportedManager`): an `AlertDialog` from `BydAlertBuilder`,
window type 2008 (`TYPE_SYSTEM_DIALOG`), cancelable, closed-on-touch-outside, one positive button
that only dismisses. Shown when `BODYWORK_POWER_LEVEL` becomes 2 (power on) and the SIM's MCC is
not in `assets/mccWhitelist.properties`; `DialogManager` dismisses it itself when AVC/ADS comes to
the foreground. The a11y window therefore has the app's label as title and
`com.byd.vehicledialog` as root package - matched by `VehicleDialogDismisser` since 9feea63
(label, package or warning text), which clicks the button or sends BACK (the dialog is cancelable).

## Open questions

- Whether the low word of the service key is the SOME/IP instance or the major version.
- Whether the AR-HUD also wants `HudNavigationmap` (0x8003) frames to show the guidance strip at
  all, or renders the TBT block from `HudRoadInfo_EG` alone (the map sends both while navigating).
- `arhud_open_status` (map setting) and `getHudConfig() == 2` gate the map's sender; BYDMate
  ignores both.
