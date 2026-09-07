# Speed bumps and speed limits on a Chinese-market Tang L outside China

Question (2026-09-07): the owner drives a Chinese-market Tang L EV 2025 (DiLink 150, God's Eye B /
DiPilot 300 with LiDAR, DiSus-C suspension) in Ukraine, where the built-in Amap has no coverage.
He uses lane centering and adaptive cruise. Can speed bumps (лежачий поліцейський / 减速带) and
speed limits be recognised by the car, or supplied to it, so the assistance handles them better?

Companion reports: [noa-someip-contract.md](noa-someip-contract.md) (message/field tables),
[tang-l-hud-someip.md](tang-l-hud-someip.md) (HUD and instrument channels),
[noa-maps-ukraine.md](noa-maps-ukraine.md) (why NOA itself is unavailable here).

**Short answer.** The car already handles speed bumps, on its own, with the forward camera — that
is a perception function that needs no map and therefore works in Ukraine. What it cannot do here
is know speed limits, because on this car the limit is map-supplied and the map is blank. The
owner's app can fix the *information* half of that, and should not touch the rest.

---

## 1. What the bus already carries for limits, cameras and road facilities

All field lists below are read from the protobuf descriptors in
`D:\BYD\DiLink\lib\libsomeipimpl_proto.so` (strings in `proto-strings.txt`); senders are from the
factory map's `SomeIPMatrixManager` (`D:\BYD\DiLink\apk\launchermap-jadx7\sources\k\k\a0\c\c\a\f0.java`)
and its feeder `e0.java`.

### 1.1 Speed limits — map → HUD (populated)

`HudRoadInfoNotifyStruct` (service 0x010A, event 0x8001) carries four limit-related fields plus a
camera pair:

| field | name | meaning |
|---|---|---|
| 11 | `Current_max_speed_limit` | limit for the current road |
| 15 | `speed_limit` | limit (the map fills both 11 and 15 with the same value) |
| 13 | `Distance_2_speed_limit_zone` | metres to the start of a limit zone |
| 14 | `length_of_speed_limit` | length of that zone |
| 17 / 18 | `camera_ahead_status` / `The_distance_2_camera` | speed camera ahead |
| 23 | `Danger_signs` | generic danger-sign slot |

This is the channel BYDMate already writes. `HudProtobufBuilder.buildArHudFrame` sends f11 and f15
for the DiLink 150 glass; the CLASSIC path additionally renders a PNG sign into f7 with f6=6.
**On the Tang L f7 is the lane image and f6 the road class**, so the PNG trick is the older glass's
convention only — this car draws its own sign from the numeric limit.

### 1.2 Speed limits and cameras — map → driving computer (populated)

`TrafficInfo` (0x0007/0x8003, sent by `f0.java:210`) is the richest populated limit message:
`SpdLmtSpeedValue` (road limit), `SpdLmtEleEyeSpeedValue` + `SpdLmtEleEyeDist` (电子眼, the posted
limit at a speed camera and its distance), `IntervalCameraStart/EndPointLon/Lat` +
`IntervalCameraSpeedValue` (average-speed enforcement sections), `RoadClass`. The log line at
`f0.java:213` names them `roadSpeed / cameraSpeed / iCamSpeed / cameraDist`.

`naviActionAndCamera` (0x8202/0x800B, `f0.java:82`) adds `cameraType` + `cameraDistance`.
`genernalNavigation` (0x0007/0x8006, `f0.java:53`) adds interval-camera distance-to-start/end.

### 1.3 Defined but never populated in this build

- `NavigationDataInfo` (0x0007/0x8005): `CurrentRoadSpeed`, `NaviCameraLimitSpeed`,
  `IntervalCameraLimitSpeed(+Dist/+RemainDist)`, `CameraType/CameraDist`,
  **`FacilityType` / `FacilityDist`**, `RoadClass_RoadType`, `RoadPoiType1..4`. No sender found.
- `ADASISV2Notify` (0x0007/0x8002): a single string field `ADASIS_V2` — an ADASIS v2 electronic
  horizon blob. No sender found. `HudMappathInfoNotifyStruct` (0x010A/0x8002) has a matching
  `all_EHP_v2_info` plus `road_slope` / `road_angle`.

So the car is *wired* for a full ADASIS electronic horizon, but this firmware never fills it.

### 1.4 Road facilities (populated, semantics unresolved)

`roadFacilities` (0x8202/0x8005, `f0.java:123-129`):

| field | name |
|---|---|
| 1 | `naviFacilityType` |
| 2 | `cruiseFacilityType` |
| 3 | `distance` |
| 4 / 5 | `boardSignLocationLon` / `boardSignLocationLat` |

The feeder is `e0.i0` (`e0.java:697-710`): it takes `arrayList.get(0)` of Amap's
`NaviRoadFacility`, sends `type` as `naviFacilityType`, recomputes the distance from the current
GNSS fix, and **hardcodes `cruiseFacilityType` to 0**. `GaoDeSignalManager.onUpdateCruiseFacility`
(:466) converts Amap `CruiseFacilityInfo` into the same `NaviRoadFacility` shape, so cruise-mode
facilities also arrive as `naviFacilityType`.

**The type enum is not in the decompiled sources.** `NaviRoadFacility` / `CruiseFacilityInfo` come
from the Amap GBL binary, which is not in the jadx output. I cannot say from local evidence
whether Amap's facility list includes 减速带. See §6.

### 1.5 Traffic-sign recognition — camera → bus (this is the important one)

`someip.obstacle_.lane.line.info.service` (service 0x000C) defines a **`TSR`** message inside
`NewLanelineDataNotifyStruct`, alongside lane lines and traffic-light detections:

| field | name |
|---|---|
| 1 | `New_TSRID_i` |
| 2-4 | `New_TSR_Distance_X` / `_Y` / `_Z` |
| 5 | `New_TSRPosition_confidence` |
| 6 | **`New_TSR_Type`** |
| 7 | **`New_Speed_Limit`** |

This lives in the *perception* domain, not the map domain: a detected sign with a 3D position, a
confidence and a recognised limit value. The car therefore has camera traffic-sign recognition as
a hardware/software capability, independent of any map. BYD's own manuals describe TSR as a
windscreen camera *fused with* navigation speed-limit data
([BYD Europe Service](https://x.com/bydeuservice/status/1988177378410111125) — "detects signs,
shows them on your instrument cluster, and alerts you if you go over").

Caveat: `NewLaneLineDataNotify`'s event id is not in `someip_stack.json` (the contract report lists
0x8001/0x8002 for the older `ObstacleInfoNotify`/`LaneLineDataNotify` only).

---

## 2. Does anything on this car represent a speed bump?

**Not on the road side. Every speed-bump identifier on the entire bus belongs to the parking
domain.** An exhaustive extraction of every `Bump` token in `libsomeipimpl_proto.so`:

| identifier | service | what it is |
|---|---|---|
| `SpeedBumps` + `SpeedBumpsID_i`, `x/y/z_i_Left`, `x/y/z_i_Right`, `SpeedBumpsWidth`, `speedBumpsParkinglotLevel5Info` | `someip.hpa.map.data.service` (0x0018 `hpaPMapData`) | bump geometry in a **learned home-zone parking map**, next to `Rampway`, `UprightColumn`, `HPAMapSlot`, `parkinglotLevelInfo` |
| `SpeedBumpNumber` | `parking.real.time` `ParkingStaticInfo` | count of bumps on a learned parking route |
| `Speed_Bump_Number` | `parking.real.time` `NewParkingStaticInfo` | same, newer struct |
| `SpeedBumps` as an object class | `someip.header.info.service` (0x0016) | one of ~22 parking-lot object counters, next to `Car`, `Truck`, `Pedestrian`, `Cone`, `WaterHorse`, `ShoppingTrolley`, `Parking_Lock` |

There is **no road-level speed-bump message, field or enum value anywhere**, and the Chinese string
`减速带` does not appear anywhere in the DiLink extraction (firmware, APK sources or resources).

**But the car does handle bumps — with the camera, in the chassis domain, off the bus we can see.**
The Tang L ships as standard with DiSus-C (云辇-C) including 云辇预瞄, the road-preview function
([news.cn launch coverage](https://www.news.cn/auto/20250411/1312bc3c10984338aba6a0dc5f38b238/c.html),
[163.com](https://www.163.com/dy/article/JSOFIEOS0519QIKK.html) — "标配天神之眼B智驾、云辇预瞄底盘").
DiSus preview is explicitly a **sensor** function, not a map function: it reads the ADAS sensors
directly (camera, mm-wave radar, IMU, LiDAR), with a claimed range up to 150 m, 3 mm resolution and
>99% accuracy within 15 m ([云辇 technical explainer, 知乎](https://zhuanlan.zhihu.com/p/621440645)).
It stiffens or softens the dampers *before* the front wheel reaches the bump. God's Eye C's
trinocular front camera markets the same idea as "路面不平提前预瞄，减速通行安全伴行"
([IT之家](https://www.ithome.com/0/829/899.htm)).

Consequence for Ukraine: **this already works here.** It needs no map, no route and no country
data. Owner reports of the Tang L describe bumps being absorbed with the cabin barely affected
([唐L EV owner review](https://youjia-pc.bdstatic.com/article/9525571128392943994.html)).

What is *not* established is whether God's Eye B **brakes** for a bump under ACC/LCC, as opposed to
only adjusting the suspension. The "减速通行" phrasing is marketing copy for God's Eye C, and no
source I found describes ACC deceleration for bumps on God's Eye B. See §6.

---

## 3. Third-party data for bumps and limits, and Ukraine coverage

### 3.1 Waze — the best available source, and the app already reads it

Waze alerts drivers to eight **road features**, and speed bumps are one of them:
"Railroad crossings, Toll booths, **Speed bumps**, Sharp curves, **Speed limit changes**, Complex
intersections, Multi lane merging, School zones"
([Waze Help](https://support.google.com/waze/answer/14964557?hl=en)). Alerts can be shown on the
map and/or announced by voice ("Show on map" / "Alert while driving"). The feature rolled out
around 2024 ([autoevolution](https://www.autoevolution.com/news/waze-starts-rolling-out-speed-bump-and-sharp-curve-alerts-228956.html),
[TechCrunch](https://techcrunch.com/2024/03/05/waze-now-helps-you-navigate-roundabouts-alerts-you-about-speed-limit-changes-and-more)).
Waze states these come "from reports from our partners and map editors", and warns that an
unreported feature is simply unknown to Waze.

This matters because BYDMate already scrapes Waze through accessibility
(`navdata/WazeAccessibilityReader.kt`) and already reads `speedLimitWarn` /
`speedLimitWarnUsOverlay`.

### 3.2 OpenStreetMap — measured Ukraine coverage

`traffic_calming=bump|hump|table|cushion` is the standard tagging
([OSM wiki](https://wiki.openstreetmap.org/wiki/Key:traffic_calming)). Global counts from
[taginfo](https://taginfo.openstreetmap.org/keys/traffic_calming) (fetched 2026-09-07):
bump 478,278 / hump 421,894 / table 322,676 / cushion 67,619 / rumble_strip 55,991.

Ukraine, measured live via Overpass (data timestamp 2026-09-07T19:35Z):

| area | `traffic_calming` nodes |
|---|---|
| Ukraine (whole country) | **5,201** |
| Kyiv city | **1,289** |

Kyiv alone is 25% of the national total. For a country with hundreds of thousands of kilometres of
road, 5,201 mapped bumps is **sparse** — usable in central Kyiv, close to worthless elsewhere.

Speed limits, same source, Kyiv city, drivable ways
(`motorway|trunk|primary|secondary|tertiary|unclassified|residential`):

| metric | count |
|---|---|
| drivable ways | 12,320 |
| of those with an explicit `maxspeed` | **6,569 (53.3%)** |

The Ukraine-wide `maxspeed` query timed out on all three Overpass endpoints tried; I do not have a
national figure. Note OSM's implied-limit convention: an unsigned urban road in Ukraine is 50 km/h
by default and is often left untagged rather than tagged, so 53% understates real *knowability* —
but a consumer would have to implement Ukraine's default rules itself.

### 3.3 Commercial ADAS maps

TomTom and HERE both sell ADAS map products feeding ADASIS v2 virtual horizons
([TomTom ADAS Map](https://tomtom.com/automotive/automotive-solutions/automated-driving/tomtom-advanced-driving-attributes),
[HERE on adasis.org](https://adasis.org/members/here/)). Published attribute lists cover speed
limits, signs, **gradient**, curvature, lanes and traffic lights. **I found no published claim
that either includes speed bumps or traffic-calming devices**, and no licensing or free-tier
information. These are OEM products; there is no realistic path to one here.

### 3.4 Google Maps

Google Maps has had speed-bump alerts in some markets since ~2019, but nothing exposed through any
public API. Not usable.

---

## 4. Information versus intervention — where the line is

This report keeps the boundary set in [noa-maps-ukraine.md](noa-maps-ukraine.md) §2: feeding
fabricated or third-party-derived map data into a Level-2 driving system is not acceptable, however
technically reachable the gateway is.

**Safe class — display and warning, which the owner's app already owns.** Anything that lands on
the HUD or instrument cluster as a number, icon or chime, and changes nothing the driving computer
decides. The driver stays the actuator. Concretely: the HUD service 0x010A is a *display* channel —
the map app writes to it, the glass renders it, no ADAS function reads it. Writing a speed limit or
a bump warning there is exactly as consequential as a phone mount showing the same thing.

**Unsafe class — anything that reaches a planner or an actuator.** This covers:

- `TrafficInfo` (0x0007/0x8003) and `roadFacilities` (0x8202/0x8005): these go **to the driving
  computer**, not to a screen. `SpdLmtSpeedValue` is a limit the ADAS may act on, and
  `naviFacilityType` describes the road ahead. Injecting Waze- or OSM-derived values here means
  telling a Level-2 system facts about a road it has no map for.
- Anything touching braking, ACC set speed, or DiSus damper commands.
- Reconstructing the ADASIS v2 / EHP channel (0x0007/0x8002, `all_EHP_v2_info`). It is unpopulated
  and the format is unspecified in anything we hold; filling it is the same act at a lower level.

The distinction is not about difficulty. Both are reachable — the gateway accepts any client. It is
about who is responsible for the consequences of wrong data. A wrong number on a HUD is a wrong
number; a wrong `SpdLmtSpeedValue` is an input to a system controlling the car.

---

## 5. What is actually implementable in BYDMate, ranked

Current state: `WazeAccessibilityReader` reads `speedLimitWarn`; `NavGuidanceHub` holds
`speedLimit` with a 30 s freshness timeout; `HudProtobufBuilder` writes f11+f15 (AR-HUD) or
f7+f11 (classic); `HudInstrumentFids` writes navi status, guide icon, distance, trip time and
mileage — **no speed-limit fid**. `HudSomeIpBridge` is send-only by design (the callback binder
exists and the gateway pushes events, but payloads are deliberately discarded).

| # | Item | Effort | Status |
|---|---|---|---|
| 1 | **Verify the existing Waze speed limit actually reaches the AR-HUD.** f11/f15 are already sent whenever `speedLimit > 0`. This may already work and simply be untested — the owner should check whether a limit appears on the glass while Waze shows one. | none (observation) | ready |
| 2 | **Waze road-feature alerts → HUD/cluster warning.** Waze announces speed bumps, sharp curves, school zones and speed-limit changes. Extend `WazeAccessibilityReader` to capture the alert banner (view id and text unknown — needs a live a11y dump with alerts enabled in Waze settings), map it to an icon, and surface it. This is the single highest-value item and it is squarely in the safe class. | medium | **blocked on a live Waze a11y dump**; no car access |
| 3 | **Port openbyd's instrument speed-limit path.** `sendSpeedLimitInfo` writes CAN fids `1083203616` (`CAN_SEGMENT_SPEED_LIMIT_SET`) and `754057272` (`CAN_SEGMENT_SPEED_TWO_SET`) plus `BYDAutoInstrumentDevice.sendCameraGuidanceInfo(1,0,flag)`. This would put a limit on the *cluster*, not just the HUD. | small | **unverified on the Tang L** — never probed; the "SEGMENT" naming suggests it may be the 区间测速 interval-camera display rather than a general limit |
| 4 | **OSM bump warning from a local extract.** Ship a Ukraine `traffic_calming` extract (5,201 nodes ≈ trivial size), match against GNSS, warn at ~150 m. | medium | possible, but §3.2 shows coverage is too sparse to be trustworthy outside Kyiv; a warning system that misses most bumps trains the driver to ignore it |
| 5 | **Read the car's own TSR off the bus** (0x000C `New_TSR_Type` / `New_Speed_Limit`) and display it. This would be the *correct* source — the car's own camera, no third-party data, no map. | large | **blocked twice**: `NewLaneLineDataNotify`'s event id is unknown, and it is unproven that the gateway will let a third-party app subscribe to a non-HUD service. Needs live probing. |
| 6 | Anything writing `TrafficInfo`, `roadFacilities` or EHP | — | **out of scope** (§4) |
| 7 | On-device bump/sign detection from the car's cameras | — | not viable: `AvmCameraProbe` reaches only the `android.hardware.AVMCamera` surround-view fisheyes (parking cameras), not the forward ADAS camera |

Recommended order: **1 → 2 → 3**. Item 1 costs nothing and may close the speed-limit question
outright. Item 2 is where the real gain is, and it needs one accessibility dump from the car with
Waze's "Permanent Features" alerts turned on.

---

## 6. Uncertainty — what this report does not establish

1. **Whether the Tang L's TSR works in Ukraine.** The `TSR` message proves the *capability* exists.
   It does not prove the deployed model recognises Ukrainian signs, nor that the cluster shows a
   camera-derived limit when the map is blank. GB 5768 harmonised Chinese signage with the 1968
   Vienna Convention (circular regulatory signs), and Ukraine is a Vienna signatory, so the
   geometry matches — but that is **inference, not a tested result**. BYD documents TSR as camera
   *fused with* navigation data, so a blank map may suppress the display even when the camera sees
   the sign. **The owner can settle this by looking at the cluster next to a posted limit sign.**
2. **Amap's road-facility type enum.** Not in the decompiled sources; I cannot say whether
   `naviFacilityType` has a bump value. Moot here (no Ukraine map data) but it is why §1.4 stops short.
3. **Whether God's Eye B decelerates for bumps** under ACC/LCC, or only adjusts the suspension.
   Only marketing copy for God's Eye C was found.
4. **`NewLaneLineDataNotify`'s event id**, and whether the SOME/IP gateway permits a third-party
   app to subscribe to service 0x000C at all.
5. **Whether fids 1083203616 / 754057272 do anything on this car.** Never probed.
6. **The Waze alert view ids.** Item 2 in §5 cannot be designed without a live dump; I have no
   evidence about how Waze exposes road-feature alerts to the accessibility tree, or whether it
   does at all.
7. **National Ukrainian `maxspeed` coverage.** Overpass timed out; only the Kyiv figure (53.3%) is
   measured. Kyiv is Ukraine's best-mapped city, so the national figure is likely lower.
8. **HERE/TomTom bump attribution.** Absence of a published claim is not proof of absence; their
   full ADAS attribute catalogues are behind commercial agreements.
9. Every §2 finding rests on the DiLink 150 build in `D:\BYD\DiLink`. A later OTA could add
   messages. Nothing here was verified against the running car — it is offline.
