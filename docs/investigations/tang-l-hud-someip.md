# Tang L (DiLink 150) HUD: what the car speaks, what openbyd sends, what BYDMate sends

Field investigation 2026-09-06/07 on the user's BYD Tang L EV 2025 (`ro.vehicle.type =
DiLink150_7.0UI`, DiLink 5.1, Android 13, AR-HUD). BYDMate Waze v3.15.0-dev.1 and dev.2 bound
the SOME/IP gateway and pushed frames, yet the glass stayed empty, while openbyd 2.4.3's HUD test
page renders on the same glass. Sources pulled from the car live in `D:\BYD\DiLink` (not in the
repo): `/system/etc/someip/someip_stack.json`, `/system/lib64/libsomeipimpl{,_proto}.so`,
`SomeIpService.apk` (gateway), `BydLaunchermap.apk` (571 MB factory map), `AmapService.apk`,
`VehicleDialog.apk`. openbyd's decompiled sources: `%LOCALAPPDATA%\Temp\bydw\openbyd-jadx`.

## 1. Verified in the car (dev.2 logs, streamed)

| step | result |
|---|---|
| bind `com.ts.car.someip.service` | ok, `registerCallback rc=0` |
| `startService(0xb010a00010000)` | rc=0; the daemon logs `OFFER(0101): [010a.0001:1.0]` and `REGISTER EVENT [010a.0001.8001/8002/8003]` |
| a11y read of Waze (foreground, Ukrainian) | `gaode=0 dist=80 road='вул. Центральна Садова …' eta=540s total=6500`, then `Waze visual maneuver=RIGHT gaode=2` |
| frames | `frame #33 rc=0 dialect=AR_HUD bytes=1392 gaode=2 f28=2 dist=80 icon=1244` every 300 ms for two minutes |
| glass | nothing |
| export warning | closes by itself (dismisser fix 9feea63) - confirmed by the user |

So the accessibility side, the gateway binding and the bus offer all work. The daemon even parses
our protobuf: it re-encodes `HudRoadInfoNotifyStruct` into CommonAPI-SomeIP for the bus (the
`HudNaviInfoService_server` plugin in `libsomeipimpl.so`), so field order does not matter there.
What was not seen: any `REMOTE SUBSCRIBE … [010a.0001.1101]` from the HUD unit (192.168.195.x)
in the two-second windows captured; the full-buffer grep did not finish before the car locked.

**Correction of an earlier draft:** I claimed the Tang L map opens a different service key
(`0xB010A00020000`). That was an arithmetic slip: `3097367205183488` is `0xB010A00010000`, the
key the SDK publishes as `HUD_NAVI_INFO_SERVICE_SERVICE_ID` (`ts.car.someip.plugin.SomeIpTopic`)
and the one the map app, openbyd and BYDMate all use. The invented key came back `rc=5` with no
offer, which is exactly what an unknown key should do. dev.2 tried it first (harmless); dev.3
does not.

## 2. The HUD service and its schema

`someip_stack.json`: service **266 (0x010A) instance 1**, UDP 52001, events 0x8001..0x8003 in
eventgroup 0x1101; applications `HudNaviInfoService_server` (0x0101) and `_client` (0x0106). The
proto `someip.hud.navi.info.service.proto` (`libsomeipimpl_proto.so`, Java lite classes in the
map app) is what upstream hand-encodes:

| field | name | type | Tang L map (`PlatformHudImpl`) fills it with |
|---|---|---|---|
| 1 | checksum | int | never |
| 2 | counter | int | constant 2 (1 in one branch) |
| 3 / 4 | car_2_dest / time_of_car_2_dest | int | route remaining m / s |
| 5 | num_of_lanes | int | lanes |
| 6 | current_road_level | int | road class |
| 7 | permissible_direction | bytes | lane image |
| 8 | recommended_driving_directions_for_ajotp | bytes | maneuver PNG |
| 9 | distance_2_intersection | int | m to maneuver |
| 10 | next_road_name | string | next road |
| 11 / 15 | current_max_speed_limit / speed_limit | int | limit |
| 12 / 21 | current_speed / vehicle_speed | int | speed |
| 16 | navigating_status | int | 2 navigating, 1 idle |
| 19 / 20 / 22 / 32 | lon / lat / altitude / heading | double, int, double | GNSS |
| 24 / 25 | poi_information / reach_the_destination | string | JSON, "" |
| 26 / 27 | eta_info_time / eta_info_remain_time | string | arrival "HH:mm" / remaining text |
| 28 | recommendedDrivingDirectionsId | int | **raw Gaode maneuver code** |
| 29 / 30 / 31 | lanesPermissibleDirectionId / guideline / guidepoint | string | JSON |
| 33 | navigatingRatio | double | progress 0..1 |

Topics: `0x4010a00018001` HudRoadInfo_EG (this struct), `…8002` HudMappathInfo_EG,
`…8003` HudNavigationmap (base64 JPEG/PNG of the map for the AR overlay; the daemon has a special
`hanldeHudNavigationmap` path for it).

Arrow codes: the map sends the Gaode code itself through table `k.h.j.e.b.a`
`{0,0,1,2,3,5,7,8,9,11,45,13,24,46,47,48,49,14,23,10,12,15,18,20,22,16,17,19,21}`: 1 left, 2
right, 3 slight left, 5 slight right, 7/8 sharp, 9 U-turn, 11 straight, 13 enter / 24 exit
roundabout, 45 waypoint, 46 service area, 47 toll, 48 destination, 49 tunnel. Upstream's classic
`gaodeToF28` (4 -> 5, 7/8 -> 1/2, 9/10 -> 7/8, roundabouts -> 99) is the older glass's enum.

Speed sign: upstream puts a rendered PNG into f7 with f6 = 6; on the Tang L f7 is the lane image
and f6 the road class. Clear frame: upstream f2 counter / f6 255 / f16 1; the map sends every
field at its default with f16 = 1, f2 = 2, then `stopSomeIpService`.

## 3. What openbyd 2.4.3 actually sends (the app that works on this glass)

Its HUD test page calls `HudController.updateNavigation`, which always runs
`CanBydFidStrategy`: **two channels at once**.

**Channel A - instrument-panel features over the autoservice ("CAN")**, pref
`hud_send_can_messages` default on (`CarControlImpl`, `BYDAutoInstrumentDevice`):
navi status 1138753594 = 2 (active) / 4 (stopped); guide icon 1139806224 and dual icon
1139806256 = Gaode code; distance 1139806232; next road name 1140461576 (bytes); rest route
mileage 1139810344 / hour 1139810320 / minute 1139810328; expected arrival minute 1139839008;
secondary icon 1139834896 / distance 1139834904.

**Channel B - SOME/IP**, pref `someip_hud_version`, default `LAUNCHER_MAP_CN`:

- `LauncherMapCnStrategy` (default) never fires the HUD road-info topic. It replays the
  launcher-map context sniffed from the factory map: NavigationStatus_LinkInfo (service 7:
  state 101, traffic info with route remaining and lon/lat), SdMapInform (0x8202:
  naviActionAndCamera = icon, main action, distance; lanes), Obstacle_LaneLine (0xC),
  PilotStatus_AlarmInfo (0xD), PlanningLine (0xE, route id), HeaderInfo (0xF). Session markers
  are constants (2641158014, 1729875789, 3592003832, 3817498742, 4073768758, six doubles).
- `AlternativeUi7Strategy`: road-info frame only, with an incrementing counter, lon/lat (f19/f20),
  guide line (f30, a synthetic 10-point polyline) and guide point (f31), lanes (f5/f29).
- `AlternativeCnD5Strategy`: road-info with every field (f1..f33) plus the six context services.

Every strategy opens the same HUD key. openbyd ignores `startService` return codes.

## 4. Why BYDMate showed nothing: the candidates

The HUD frame we sent (dev.1: classic layout; dev.2: Tang L layout) lacked the vehicle position
block and the guide line, and we sent nothing on the other channels. On an AR glass the TBT
widget may be anchored to the position, or the visible guidance may be rendered by the scene
renderer (`com.byd.sr`, GritPlayer, which consumes the SdMapInform / NavigationStatus data) rather
than by the HUD unit from HudRoadInfo, or the glass may take its TBT from the instrument features.
Any of the three explains openbyd's success; the field test decides.

## 5. What BYDMate v3.15.0-dev.3 does

`HudDialect` AR-HUD (auto on `DiLink150`) now has three switchable sub-channels, all on by
default so the first run reproduces openbyd's full behaviour:

1. **HUD frame** (`HudProtobufBuilder.buildArHudFrame`): Tang L field set plus f12/f21 speed,
   f19/f20 lon/lat, f22 altitude, f24/f25 "[]", f30 guide line, f31 guide point, f32 heading from
   the last GNSS fix (`HudVehicleState`, fed by TrackingService).
2. **Factory-map context** (`HudLauncherMapContext`): port of openbyd's `LauncherMapCnStrategy`
   with BYDMate's guidance values; opens the six extra service keys.
3. **Instrument-panel features** (`HudInstrumentFids`): port of openbyd's CAN channel through the
   helper daemon (`HelperClient.write(1007, fid, value)`), values written on change; the road-name
   bytes feature is skipped until the daemon offers byte writes.

Settings -> Display -> HUD shows the three switches under the AR-HUD type. Logs (info level,
survive release): `HudPushLoop` frame lines now include `roadInfo=… ctx=on/events/rc fids=on/writes/fail fix=…`;
`HudLauncherMapCtx` and `HudInstrumentFids` log session start/stop.

## 5a. Result of the bisection (2026-09-07 morning, dev.3/dev.4)

The user switched the three channels one by one with Waze navigating: **only "Guidance to the
instrument panel" puts anything on the glass**. The SOME/IP road-info frame and the launcher-map
context change nothing on this car (the daemon offers the service and the launcher-map events
return rc=0, but the HUD unit evidently takes its TBT block from the instrument-panel features,
i.e. from the vehicle network, not from `HudNaviInfoService`). Since dev.6 the instrument channel
is the only one on by default; the other two stay as switches for other DiLink 150 trims.

Two more findings from the same session, fixed in dev.4/dev.5: switching the projection off left
the last arrow on the glass (the instrument features were never reset), and the arrow blinked off
after 30 s whenever the car stood still (no Waze redraw, no accessibility event, no re-read, hub
expires the maneuver; now a keep-alive re-read runs after 20 s of silence).

Open: the road name (feature 1140461576) is a byte-array write the helper daemon does not offer
yet; the arrival/remaining time features are written and should be checked on the glass.

## 6. Car test plan (bisection, as run)

```bash
adb connect 192.168.0.166:5555
adb -s 192.168.0.166:5555 logcat -G 8M
adb -s 192.168.0.166:5555 logcat -v time HudController:I HudSomeIpBridge:I HudPushLoop:I HudLauncherMapCtx:I HudInstrumentFids:I NavA11yFeed:I NavGuidanceHub:I *:S
```

1. Waze route in the foreground, all three switches on -> does the glass show the turn?
2. If yes: switch off "Factory-map context", wait 10 s, check; switch it back on, switch off
   "Guidance to the instrument panel", check; then "HUD frame". The one whose removal blanks the
   glass is the channel it reads.
3. If no: in openbyd, set `someip_hud_version` to `ALTERNATIVE_UI7` and turn off
   `hud_send_can_messages`, run its test; then the other way round. Report which combination
   shows, and capture `logcat | grep -E 'OFFER|SUBSCRIBE|010a'` during a BYDMate session to see
   whether the HUD unit ever subscribes to `[010a.0001.1101]`.

Note: the gateway app crashed once (`SomeIpServerService.onUnbind` NPE, `intent: null`) at
00:02:38 while both apps were bound; it restarted by itself and BYDMate re-registered. Avoid
toggling the HUD type repeatedly while openbyd is bound.

## 7. Export warning ("This car is not from official export")

`com.byd.vehicledialog` (`NotOfficialExportedManager`): an `AlertDialog` (`BydAlertBuilder`,
window type 2008 `TYPE_SYSTEM_DIALOG`, cancelable, one button that only dismisses), shown when
`BODYWORK_POWER_LEVEL` becomes 2 (power on) and the MCC is not whitelisted. The a11y window has
the app label as title and `com.byd.vehicledialog` as root package; `VehicleDialogDismisser`
(9feea63) matches label, package or warning text and clicks the button or sends BACK. Confirmed
working in the car.

Why it cannot simply be whitelisted: `isWhitelistMcc()` compares `persist.radio.byd.last_mcc`
(255 = Ukraine on this car, written by the radio stack from the network; the SIM itself is
Chinese, 46009) against `assets/mccWhitelist.properties` inside the signed system APK on the
read-only `/system` partition (China 460, HK/Macau 454-457, Russia 250, Belarus 257, Kazakhstan
401, Uzbekistan 434, Azerbaijan 400, Georgia 282, Armenia 283, Middle East and North Africa
4xx/6xx). Editing the asset needs root; the property belongs to the radio SELinux domain
(`setprop` from the ADB shell user fails), and the modem rewrites it on every registration. The
check passes only when the property is absent (-1) or listed.
