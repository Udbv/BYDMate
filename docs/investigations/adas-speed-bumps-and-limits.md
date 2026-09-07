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
is know speed limits: on this car the limit is camera *plus map*, the map is blank, and the camera
half is documented to read European signage badly. The owner's app can fix the *information* half
of that, and should not touch the rest.

**Evidence classes used below.** *Confirmed* = read from the firmware/APK on disk, from a live
query, or from a primary published source. *Anecdote* = owner/forum report, one car, one country.
*Inference* = my reasoning over the two, labelled as such.

---

## 1. What the bus already carries for limits, cameras and road facilities

All field lists below are read from the protobuf descriptors in
`D:\BYD\DiLink\lib\libsomeipimpl_proto.so` (strings in `proto-strings.txt`); senders are from the
factory map's `SomeIPMatrixManager` (`D:\BYD\DiLink\apk\launchermap-jadx7\sources\k\k\a0\c\c\a\f0.java`)
and its feeder `e0.java`.

### 1.1 Speed limits — map → HUD (populated)

`HudRoadInfoNotifyStruct` (service 0x010A, event 0x8001) carries four limit-related fields plus a
camera pair:

f11 `Current_max_speed_limit` and f15 `speed_limit` (the map fills both with the same value),
f13 `Distance_2_speed_limit_zone` (metres to the start of a limit zone), f14 `length_of_speed_limit`,
f17/f18 `camera_ahead_status` / `The_distance_2_camera`, and f23 `Danger_signs` (generic slot).

This is the channel BYDMate already writes. `HudProtobufBuilder.buildArHudFrame` sends f11 and f15
for the DiLink 150 glass; the CLASSIC path additionally renders a PNG sign into f7 with f6=6.
**On the Tang L f7 is the lane image and f6 the road class**, so the PNG trick is the older glass's
convention only — this car draws its own sign from the numeric limit.

### 1.2 Speed limits and cameras — map → driving computer (populated)

`TrafficInfo` (0x0007/0x8003, sent by `f0.java:210`) is the richest populated limit message:
`SpdLmtSpeedValue` (road limit), `SpdLmtEleEyeSpeedValue` + `SpdLmtEleEyeDist` (电子眼, the posted
limit at a speed camera and its distance), `IntervalCameraStart/EndPointLon/Lat` +
`IntervalCameraSpeedValue` (average-speed enforcement sections), `RoadClass`; the log line at
`f0.java:213` names them `roadSpeed / cameraSpeed / iCamSpeed / cameraDist`.
`naviActionAndCamera` (0x8202/0x800B, `f0.java:82`) adds `cameraType` + `cameraDistance`;
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

`roadFacilities` (0x8202/0x8005, `f0.java:123-129`): f1 `naviFacilityType`, f2 `cruiseFacilityType`,
f3 `distance`, f4/f5 `boardSignLocationLon`/`Lat`. The feeder is `e0.i0` (`e0.java:697-710`): it
takes `arrayList.get(0)` of Amap's `NaviRoadFacility`, sends `type` as `naviFacilityType`, recomputes
the distance from the current GNSS fix, and **hardcodes `cruiseFacilityType` to 0**.
`GaoDeSignalManager.onUpdateCruiseFacility` (:466) converts Amap `CruiseFacilityInfo` into the same
`NaviRoadFacility` shape, so cruise-mode facilities also arrive as `naviFacilityType`.

**The type enum is not in the decompiled sources.** `NaviRoadFacility` / `CruiseFacilityInfo` come
from the Amap GBL binary, not in the jadx output. I cannot say from local evidence whether Amap's
facility list includes 减速带. See §6.

### 1.5 Traffic-sign recognition — camera → bus

`someip.obstacle_.lane.line.info.service` (service 0x000C) defines a **`TSR`** message inside
`NewLanelineDataNotifyStruct`, alongside lane lines and traffic-light detections: f1 `New_TSRID_i`,
f2-4 `New_TSR_Distance_X/_Y/_Z`, f5 `New_TSRPosition_confidence`, f6 **`New_TSR_Type`**,
f7 **`New_Speed_Limit`**.

This lives in the *perception* domain, not the map domain: a detected sign with a 3D position, a
confidence and a recognised limit value. The car therefore has camera traffic-sign recognition as
a hardware/software capability, independent of any map. Caveat: `NewLaneLineDataNotify`'s event id is
not in `someip_stack.json` (the contract report lists 0x8001/0x8002 for the older
`ObstacleInfoNotify`/`LaneLineDataNotify` only).

### 1.6 BYD's TSR is camera *fused with navigation* — and the camera half reads Vienna signs badly

**Confirmed — it is a fusion function, not pure vision.** BYD Europe Service describes TSR as
detecting signs, showing them on the cluster and alerting on overspeed
([BYD Europe Service](https://x.com/bydeuservice/status/1988177378410111125)). Euro NCAP's datasheet
for the BYD Seal classifies its Speed Limit Information Function as **"Camera & Map, subsigns
supported"**, scoring 2.6/3 ([Euro NCAP BYD Seal](https://www.euroncap.com/assessments/byd/seal/1044/)).
A third-party ADAS guide words it as "uses the front camera and navigation data" to feed ISLC
([BYD Accessories guide](https://www.bydaccessories.store/blogs/news/byd-seal-adas-features)) —
useful phrasing, but a retailer's summary, not BYD documentation.
**Inference:** in Ukraine the map half is inert (no Amap coverage), so whatever the Tang L shows
must come from the camera alone, with nothing to cross-check or fill gaps.

**Anecdote — the single most relevant owner report found.** An owner of a Chinese-market **BYD Yuan
Up** in Belarus — which, like Ukraine, uses 1968 Vienna Convention signage — reports the sign camera
performing badly ([forum.onliner.by, user `hb_`](https://forum.onliner.by/viewtopic.php?t=26006811)):
it "не понимает знак скорости только на полосу или всю дорогу", "не видит знак отмены ограничений",
"не видит знак начало/конец населенного пункта", "не видит перекрестка", and so "выставлять на круизе
соблюдение скоростных знаков не имеет смысла". **Why this matters specifically:** the
settlement-begin/end and end-of-restriction signs are exactly the ones that establish and clear
Ukraine's *implicit* 50 km/h urban limit. A camera that misses them cannot derive the limit that
applies on most roads here. One owner, one model, one country — a strong hint, not proof.

**Corroboration — independent and formal.** Euro NCAP graded the 2022 model-year BYD Atto 3's
assisted driving **"Not Recommended"** in October 2024, finding speed assistance did not interpret
road signs correctly and treated temporary/conditional limits as established ones
([Euro NCAP BYD Atto 3](https://www.euroncap.com/assessments/byd/atto+3/a033/),
[press release](https://www.euroncap.com/press-media/byd-improves-assisted-driving-system-in-response-to-euro-ncap-s-atto-3-grading/)).
BYD revised it; the retested 2025 Atto 3 grades "Good" overall but speed assistance remains its
weakest area (11.4/25), sign recognition still called inconsistent. The Belarusian anecdote is
consistent with the only formal European testing of BYD sign recognition that exists.

**Regulatory context — why European BYDs must do this and this car need not.** Regulation (EU)
2019/2144 makes Intelligent Speed Assistance mandatory for new type-approvals from 6 July 2022 and
all new registrations from 7 July 2024; Delegated Regulation (EU) 2021/1958 sets the test and is
deliberately **technology-neutral** — camera, electronic map data, or both
([EUR-Lex 2021/1958](https://eur-lex.europa.eu/eli/reg_del/2021/1958/oj)). The bar: correct limit
**within 2.0 s** of passing a sign, correct for **≥90% of total distance and ≥80% per road type**,
over a **400 km** public-road drive across urban/non-urban/motorway with a darkness share
([SLIF digest](https://www.atic-ts.com/key-points-of-slif-of-eu-20211958-intelligent-speed-assistance-system/);
the EUR-Lex full text exceeds the fetch size limit and was not read end to end).
**Inference:** a Chinese-market Tang L was never subject to this test — no European validation of
its SLIF exists.

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
([news.cn](https://www.news.cn/auto/20250411/1312bc3c10984338aba6a0dc5f38b238/c.html),
[163.com](https://www.163.com/dy/article/JSOFIEOS0519QIKK.html) — "标配天神之眼B智驾、云辇预瞄底盘").
DiSus preview is explicitly a **sensor** function, not a map function: it fuses mono/stereo cameras
and LiDAR to read surface features and the elevation profile **5–150 m ahead** at **±3 mm** accuracy,
>99% recognition within 15 m, presetting the dampers before the wheel arrives
([云辇 explainer, 知乎](https://zhuanlan.zhihu.com/p/621440645),
[云辇 comparison, 知乎](https://zhuanlan.zhihu.com/p/621616480)). God's Eye C's trinocular camera
markets the same idea as "路面不平提前预瞄，减速通行安全伴行" ([IT之家](https://www.ithome.com/0/829/899.htm)).

**Consequence for Ukraine, stated plainly: this already works here.** Bump handling is pure
perception — no map, no route, no country data, no navigation licence — and it is standard equipment.
Owner reports of the Tang L describe bumps absorbed with the cabin barely affected
([唐L EV owner review](https://youjia-pc.bdstatic.com/article/9525571128392943994.html)).
What is *not* established is whether God's Eye B **brakes** for a bump under ACC/LCC as opposed to
only adjusting the suspension; "减速通行" is marketing copy for God's Eye C. See §6.

### 2.1 How every other manufacturer solves the same problem — and what it implies

Is a map the normal way to do this? No. Camera preview dominates; map-based road-roughness is a rare
and coarse supplement.

| approach | who | what they actually do |
|---|---|---|
| **Camera preview** | Mercedes-Benz ROAD SURFACE SCAN, feeding MAGIC BODY CONTROL and E-ACTIVE BODY CONTROL | stereo camera scans up to **15 m** ahead, resolving surface variation to about **3 mm**, and presets each corner ([phys.org on the S-Class system](https://phys.org/news/2013-09-mercedes-benz-s-class-stability-sensors-stereo.html)) |
| | Audi A8 predictive active suspension | front camera samples road-surface properties **18 times per second**, 48 V actuators respond near-real-time ([Audi Technology Portal](https://www.audi-technology-portal.de/en/chassis/suspension-control-systems/audi-s8-predictive-active-suspension), [Green Car Congress](https://www.greencarcongress.com/2019/07/20190719-a8.html)) |
| | Rolls-Royce Ghost "Flagbearer" | windscreen stereo camera reads the road ahead and primes the Planar suspension, up to ~100 km/h ([autoevolution](https://www.autoevolution.com/news/2021-rolls-royce-ghost-uses-cameras-and-satellites-to-adapt-for-the-road-ahead-147218.html)) |
| | **BYD 云辇预瞄 (this car)** | cameras + LiDAR, 5–150 m, ±3 mm — see above |
| **Camera + navigation** | BMW Executive Drive Pro | BMW describes the inputs as driving-style analysis, the navigation system and a stereo camera; the nav half is used mainly to anticipate *bends* ([BMW dealer technology page](https://www.edmontonbmw.com/bmw-technology-bmw-engine-driving-technologies-executive-drive-pro/)) |
| **Map-based (fleet-generated)** | Tesla adaptive suspension | raises ride height for upcoming rough segments from **rough-road map data generated by Tesla vehicles**, plus a separate private per-location ride-height memory for repeated locations such as a steep driveway ([Tesla owner's manual, Air Suspension](https://www.tesla.com/ownersmanual/modelx/en_us/GUID-F1B6801A-8946-41AD-8CF9-7A963CDA38E4.html), [rollout coverage](https://insideevs.com/news/596332/tesla-model-s-x-now-raising-adaptive-suspension-over-rough-roads/)) |
| | NIO ET7 "4D intelligent body control" | claims HD maps alongside cameras/LiDAR to detect road bumpiness and preset the chassis ([NIO ET7 launch release](https://www.nio.com/news/nio-launches-first-autonomous-driving-model-et7)) |
| **Reactive only** | Ford Continuously Controlled Damping "pothole mitigation" | 12 sensors, 2 ms loop; detects the *edge* of a pothole as the wheel reaches it and stiffens to stop the wheel dropping in — no look-ahead ([Ford/fleetnews](https://www.fleetnews.co.uk/news/fleet-industry-news/2016/02/18/ford-developing-pothole-mitigation-technology)) |
| **No evidence found** | Rivian, Lucid | no published road-preview or camera-preview suspension function located |

Two things follow. First, **Tesla's map layer is segment roughness, not a shared discrete
speed-bump database** — it says "this stretch is rough", not "there is a hump at this point", and
the per-location memory is private to one car. Nobody in this survey ships a shared bump-point map
to the chassis. Second, **the Tang L is in the best-supported camp**, and that camp's method is
country-independent. Nothing about Ukraine degrades it.

---

## 3. Third-party data for bumps and limits, and Ukraine coverage

### 3.1 Waze — the best available source, and the app already reads it

Waze alerts drivers to eight **road features**, speed bumps among them: "Railroad crossings, Toll
booths, **Speed bumps**, Sharp curves, **Speed limit changes**, Complex intersections, Multi lane
merging, School zones" ([Waze Help](https://support.google.com/waze/answer/14964557?hl=en)); alerts
can be shown on the map and/or announced by voice, rolled out around 2024
([autoevolution](https://www.autoevolution.com/news/waze-starts-rolling-out-speed-bump-and-sharp-curve-alerts-228956.html),
[TechCrunch](https://techcrunch.com/2024/03/05/waze-now-helps-you-navigate-roundabouts-alerts-you-about-speed-limit-changes-and-more)).
Waze says these come "from reports from our partners and map editors", and an unreported feature is
simply unknown to it. **A speed bump in Waze is a first-class, permanently alertable road feature** —
more than any commercial ADAS map offers (§3.4).

**Speed limits in Waze.** The limit is a per-segment editable attribute in the Waze Map Editor; the
client shows it next to the driver's speed and can alert on exceeding it
([Wazeopedia segment attribute](https://www.waze.com/discuss/t/speed-limit/374952),
[Wazeopedia speed limits](https://www.waze.com/discuss/t/speed-limits/378820)). The 2016 rollout
named 18 countries (Austria, Belgium, Brazil, Colombia, Czech Republic, El Salvador, France, Hungary,
Italy, Latvia, Liechtenstein, Netherlands, New Zealand, Sweden, Switzerland, Trinidad and Tobago,
Uruguay) and **Ukraine was not among them**
([Waze announcement](https://www.waze.com/forum/viewtopic.php?t=183204),
[9to5Mac](https://9to5mac.com/2016/03/29/waze-speed-limit-warnings/)). Waze said it would go global
without a timeline, and **I could not confirm the current status in Ukraine from any source.** This
is the single most important open question in this report and is answerable in one drive — see §6.

BYDMate already scrapes Waze through accessibility (`navdata/WazeAccessibilityReader.kt`) and reads
`speedLimitWarn` / `speedLimitWarnUsOverlay`.

### 3.2 OpenStreetMap — the tagging, and what Ukraine actually has

`traffic_calming=bump|hump|table|cushion` is the standard tagging
([OSM wiki](https://wiki.openstreetmap.org/wiki/Key:traffic_calming)). Global counts from
[taginfo](https://taginfo.openstreetmap.org/keys/traffic_calming) (fetched 2026-09-07):
bump 478,278 / hump 421,894 / table 322,676 / cushion 67,619 / rumble_strip 55,991.

### 3.3 OSM coverage compared — Ukraine against well-mapped neighbours

**Method.** One Overpass query per area, run 2026-09-07 against `overpass-api.de` (data timestamps
2026-09-07T20:05–20:23Z), counting (a) nodes tagged `traffic_calming`, (b) ways whose `highway` is in
`motorway|trunk|primary|secondary|tertiary(+_link)|unclassified|residential|living_street`, and (c)
how many of those carry an explicit `maxspeed`; drivable **length** from a separate
`make stat len=sum(length())` over the same way set. Raw counts from the live database, not estimates.

| | Ukraine | Kyiv city | Poland | Czechia | Germany |
|---|---|---|---|---|---|
| `traffic_calming` nodes | **5,201** | **1,289** | **59,464** | **10,798** | **56,141** |
| drivable OSM road length | 491,911 km | 2,842 km | 427,935 km | 117,791 km | *query failed* |
| **bumps per 1,000 km of drivable road** | **10.6** | **453.5** | **138.9** | **91.7** | *n/a* |
| drivable ways | 867,782 | 13,185 | 1,259,897 | 507,443 | 4,074,221 |
| of those with explicit `maxspeed` | 103,822 | 7,028 | 451,009 | 176,566 | 2,545,769 |
| **`maxspeed` coverage** | **12.0%** | **53.3%** | **35.8%** | **34.8%** | **62.5%** |

**Read-out.** Poland has **13× Ukraine's traffic-calming density** per kilometre of drivable road,
Czechia 8.7×. Kyiv on its own is 25% of Ukraine's national bump total on 0.6% of its road length —
i.e. **Ukrainian traffic-calming mapping is a Kyiv phenomenon**, and outside it the layer is close
to empty. On limits, Ukraine's 12.0% national `maxspeed` coverage is a third of Poland's and a fifth
of Germany's; Kyiv's 53.3% is a different world from the rest of the country.

**Limits of this method, stated rather than hidden.**
- Comparison countries chosen as well-mapped and comparable in network scale: Poland (428k km) is the
  closest analogue to Ukraine's 492k km, Czechia the small control, Germany the ceiling.
- **Germany's length query failed on all nine attempts** — "Query ran out of memory… would need at
  least 530 MB" first, then dispatcher timeouts across `overpass-api.de` and `overpass.kumi.systems`.
  Its *counts* row completed; its *length* row never did, so Germany has no bumps-per-km figure.
  That is a gap, not a silent estimate.
- Kyiv's boundary is `area[name="Київ"][admin_level=4]`, the city including its outer districts. Only
  `traffic_calming` **nodes** are counted; Ukraine also has 476 ways and 1 relation with the key
  ([taginfo Ukraine](https://taginfo.geofabrik.de/europe:ukraine/), 2026-09-06), which changes nothing.
- `maxspeed` coverage **understates knowability** — OSM leaves implicit limits untagged and an
  unsigned urban road in Ukraine is 50 km/h by law, so a consumer must implement the default rules
  itself — and overstates slightly the other way, since the counts include `none` and conditionals.
- Way *counts* are not comparable across countries in isolation (splitting habits differ); the
  percentage and the per-kilometre rows are the meaningful ones.

**Conclusion, now with a scale for it:** 5,201 mapped bumps for a country this size is sparse —
usable in central Kyiv, close to worthless elsewhere.

### 3.4 Commercial ADAS maps

TomTom and HERE both sell ADAS map products feeding ADASIS v2 virtual horizons
([TomTom ADAS Map](https://tomtom.com/automotive/automotive-solutions/automated-driving/tomtom-advanced-driving-attributes),
[HERE on adasis.org](https://adasis.org/members/here/)).

**HERE does carry something, and it is too coarse to be useful.** HERE's Map Content Schema defines a
Special Speed Situation Type value **"Speed Bumps or Chicanes"**, indicating that for a *stretch of
road* bumps or chicanes are present that effectively reduce the posted speed — with **no speed value
indicated**. A road-stretch attribute, no point geometry, no number
([HERE Map Content Schema](https://developer.here.com/documentation/here-map-content-schema/dev_guide/format-agnostic-data/format_agnostic_data_specification/topics/special-speed-situation-type.html)
— that URL now 301s to here.com/docs; the text is from the indexed page). **Inference:** "this
stretch has bumps" cannot time a damper and cannot place a HUD warning.

**TomTom: no public speed-bump attribute found.** Its published ADAS Map attribute list is speed
limits, traffic signs, gradient, curvature, lane information and traffic lights
([product sheet](https://download.tomtom.com/open/banners/ADAS-Map-Product-Info-Sheet.pdf)). Absence
of a published claim is not proof of absence — see §6. Either way these are OEM products with no
licensing or free-tier path for an individual.

**Google Maps** has had speed-bump alerts in some markets since ~2019, but exposes nothing through
any public API. Not usable.

### 3.5 Everything else — national open data, other apps, datasets, and self-sensing

Researched for Ukraine and for an individual developer with no OEM contract.

**(a) Ukrainian open data — effectively nothing.** Direct CKAN queries against
[data.gov.ua](https://data.gov.ua/) return **zero datasets** for "лежач…" and zero for "штучна
нерівність" (the official term for a road hump); no national road-sign register is published as open
data. The only Ukrainian dataset claiming to hold speed bumps is **Dubno city council's "Засоби
регулювання дорожнього руху"** (GeoJSON, CC-BY, id `24438265-6a0a-4a6d-bbbe-3df6db8fc0a4`, modified
June 2021) — one town of ~37k people, and its host CKAN refused connections, so it may be dead.
Municipal "схема організації дорожнього руху" datasets are **JPEG scans**. Kyiv's portal
([data.kyivcity.gov.ua](https://data.kyivcity.gov.ua/dataset)) has no road-infrastructure category;
its transport department's 16 datasets are schedules, finance and legal acts. Служба відновлення
(ex-Ukravtodor) publishes 27 datasets, none on signs, calming or limits, its road-centreline API
endpoints recorded as timing out. **Nor will this improve on its own:** the Resolution 835 list of
datasets local authorities are *obliged* to publish has no road-infrastructure item
([Resolution 835](https://zakon.rada.gov.ua/laws/show/en/835-2015-%D0%BF/comp20240827)). Diia is not
a separate source — `diia.data.gov.ua` is the competence centre, the catalogue is data.gov.ua.
**One genuinely useful find, for the adjacent problem:** the National Police dataset
**"Місцезнаходження камер автоматичної фіксації швидкості руху"** — automatic speed-camera locations,
CSV, CC-BY, updated 28 August 2026 and actively maintained
([data.gov.ua/dataset/b7b6349c…](https://data.gov.ua/dataset/b7b6349c-d109-45e7-af37-b73310f73cf5)) —
which maps onto the HUD's existing `camera_ahead_status` / `The_distance_2_camera` fields.

**(b) Other crowd-sourced driving apps — the data exists and is unshippable.** The iGO Primo /
Navitel `speedcam.txt` ecosystem *does* encode speed bumps as a POI type and Ukrainian community
files exist, but the type codes are unofficial conventions that disagree with each other, the files
carry **no licence and no provenance**, and they are largely scraped from commercial products — do
not ship them. [SCDB.info](https://www.scdb.info/en/plugin-csv/) (114k points, 113 countries incl.
Ukraine, €9.95/yr) is **cameras only, no bumps**, and a personal download is not a redistribution
licence. Radarbot advertises bump alerts but encrypts its database with the key inside the APK and
has no developer programme. Sygic's SDK is enterprise-licensed and forbids sub-licensing. TomTom's
freemium tier is genuinely self-serve (50k tile + 2,500 non-tile requests/day, commercial use
allowed) but **Ukraine coverage is ~45%** and AmiGO's community reports have no API. HERE has a
freemium tier with speed-limit access; whether any traffic-calming layer is reachable there is
**unconfirmed**.

**(c) Dashcam / telematics / research datasets — no geolocated bump data covers Ukraine.** Mapillary
is the only near-miss and is blocked twice over: its sign vocabulary does include
`warning--road-bump--*`, but its Terms of Use (§5(1), effective 15 Feb 2024) **prohibit use in
connection with real-time navigation or route guidance** ([terms](https://www.mapillary.com/terms)),
and it **disabled viewing and download of all Ukrainian imagery in March 2022**, no reversal found.
KartaView is open (CC BY-SA, keyless read API) but raw imagery with no anomaly layer. Roboflow/Kaggle
speed-bump sets (largest ~3.2k images, CC BY 4.0) are **image-only, no geolocation** — training data
for an onboard detector, not a map. RDD2022 (47k images, CC BY-SA) has no Ukraine, no bump class and
no GPS in the released annotations; StreetSurfaceVis is geolocated but Germany-only and labels
surface quality. Bee Maps/Hivemapper is self-serve but has **no bump features** and is US/EU/UK only;
Nexar is enterprise-only.

**(d) Building the map yourself from the car's own motion — viable, clean, inherently late.** The one
route with no licensing problem, and the car already logs its motion. The literature is unambiguous
on the fundamental limit: IMU methods *"are inherently reactive, as they measure excitations only
after the vehicle has encountered the disturbance"*
([SBP-YOLO, arXiv:2508.01339](https://arxiv.org/html/2508.01339)) — accelerometer sensing can only
**build** the map; the warning goes to the *next* driver. Parameters: **50 Hz floor, 100 Hz safe
target** (foundational threshold detectors used 310–380 Hz —
[Pothole Patrol](https://www.cs.uic.edu/~jakob/papers/p2-mobisys08.pdf),
[Nericell](https://www.microsoft.com/en-us/research/wp-content/uploads/2016/02/nericell-nericell-sensys.pdf));
**k≈4 independent passes** matched within ~15 m before reporting; GPS is the hard accuracy cap —
Pothole Patrol measured a **3.3 m standard deviation** and noted GPS error exceeds the size of the
feature mapped. Discount the 97–99% headline accuracies of recent ML papers heavily (tiny same-drive
datasets, leaky splits, 3–4 s windows ≈ ±110 m at highway speed); the honest numbers are Nericell's
**20–30% false negatives** and a 106 km study's precision 88.5% / recall 75%. Yield is sobering:
Pothole Patrol's **9,730 km of Boston taxi driving produced 48 confirmed clusters**. No permissively
licensed accelerometer bump dataset was found — the best match (PVS, 100 Hz, explicit speed-bump
labels) is **CC BY-NC-ND**, barring use in a shipped product. **Inference:** ±5 m is fine for a "bump
in ~50 m" chime and useless for anything finer, and with one car the map only covers roads whose
bumps the driver already knows. Interesting only at scale.

---

## 4. Information versus intervention — where the line is

This report keeps the boundary set in [noa-maps-ukraine.md](noa-maps-ukraine.md) §2: feeding
fabricated or third-party-derived map data into a Level-2 driving system is not acceptable, however
technically reachable the gateway is.

**Safe class — display and warning, which the owner's app already owns.** Anything landing on the HUD
or cluster as a number, icon or chime that changes nothing the driving computer decides. HUD service
0x010A is a *display* channel: the map app writes, the glass renders, no ADAS function reads it. A
limit or bump warning there is as consequential as a phone mount showing the same thing.

**Unsafe class — anything reaching a planner or an actuator.** `TrafficInfo` (0x0007/0x8003) and
`roadFacilities` (0x8202/0x8005) go **to the driving computer**, not a screen: `SpdLmtSpeedValue` is
a limit the ADAS may act on and `naviFacilityType` describes the road ahead, so injecting Waze- or
OSM-derived values there means telling a Level-2 system facts about a road it has no map for. Also
out: braking, ACC set speed, DiSus damper commands, and reconstructing the ADASIS v2 / EHP channel
(0x0007/0x8002, `all_EHP_v2_info`) — unpopulated, format unspecified in anything we hold, and filling
it is the same act one level down. The distinction is not difficulty; both are reachable, the gateway
accepts any client. It is who is responsible for wrong data: a wrong number on a HUD is a wrong
number, a wrong `SpdLmtSpeedValue` is an input to a system controlling the car.

---

## 5. What is actually implementable in BYDMate, ranked

Current state: `WazeAccessibilityReader` reads `speedLimitWarn`; `NavGuidanceHub` holds
`speedLimit` with a 30 s freshness timeout; `HudProtobufBuilder` writes f11+f15 (AR-HUD) or
f7+f11 (classic); `HudInstrumentFids` writes navi status, guide icon, distance, trip time and
mileage — **no speed-limit fid**. `HudSomeIpBridge` is send-only by design.

| # | Item | Effort | Status |
|---|---|---|---|
| 0 | **Establish whether Waze even has speed limits in Ukraine.** Drive with Waze open and look at the speedometer widget. Ukraine was not in the 2016 rollout (§3.1) and current status is unconfirmed. Everything below that involves limits depends on this. | none (observation) | **do this first** |
| 1 | **Verify the existing Waze speed limit reaches the AR-HUD.** f11/f15 are already sent whenever `speedLimit > 0`. This may already work and simply be untested. | none (observation) | ready, gated on #0 |
| 2 | **Waze road-feature alerts → HUD/cluster warning.** Waze treats speed bumps as first-class alertable features (§3.1) and it is the only bump source that is both licensable-by-use and actually populated in Ukraine. Extend `WazeAccessibilityReader` to capture the alert banner (view id and text unknown), map it to an icon, surface it. Squarely in the safe class. | medium | **blocked on a live Waze a11y dump**; no car access |
| 3 | **Port openbyd's instrument speed-limit path.** `sendSpeedLimitInfo` writes CAN fids `1083203616` (`CAN_SEGMENT_SPEED_LIMIT_SET`) and `754057272` (`CAN_SEGMENT_SPEED_TWO_SET`) plus `BYDAutoInstrumentDevice.sendCameraGuidanceInfo(1,0,flag)`. Puts a limit on the *cluster*, not just the HUD. | small | **unverified on the Tang L** — the "SEGMENT" naming suggests it may be the 区间测速 interval-camera display rather than a general limit |
| 4 | **Speed-camera warning from the National Police open dataset.** CC-BY CSV, actively maintained (§3.5a), maps onto the HUD's existing camera fields. The only Ukrainian government road dataset that is both fresh and relevant. | small | new — data confirmed available |
| 5 | **OSM bump warning from a local extract.** Ship a Ukraine `traffic_calming` extract (5,201 nodes ≈ trivial size), match against GNSS, warn at ~150 m. | medium | possible, but §3.3 measures the coverage at **10.6 bumps per 1,000 km nationally vs 138.9 in Poland**; outside Kyiv (453/1,000 km) a warning system that misses most bumps trains the driver to ignore it |
| 6 | **Read the car's own TSR off the bus** (0x000C `New_TSR_Type` / `New_Speed_Limit`) and display it. Would be the *correct* source — the car's own camera, no third-party data, no map. | large | **blocked twice**: event id unknown, and third-party subscription to a non-HUD service is unproven. And §1.6 suggests the underlying recogniser may be weak on Vienna signage anyway |
| 7 | Self-built accelerometer bump map (§3.5d) | large | technically clean, but retrospective by construction, needs k≈4 passes and ±5 m GPS, and with one car it only maps roads the driver already knows |
| 8 | Anything writing `TrafficInfo`, `roadFacilities` or EHP | — | **out of scope** (§4) |
| 9 | On-device bump/sign detection from the car's cameras | — | not viable: `AvmCameraProbe` reaches only the `android.hardware.AVMCamera` surround-view fisheyes, not the forward ADAS camera |

Recommended order: **0 → 1 → 2 → 4**. Item 0 costs one drive and decides whether the limit half of
this report is live at all. Item 2 is where the real gain is, and it needs one accessibility dump
from the car with Waze's "Permanent Features" alerts turned on.

**What needs no work at all:** bump *handling*. §2 and §2.1 establish that DiSus preview is a
camera/LiDAR function, standard on this car, matching how Mercedes, Audi and Rolls-Royce do it, and
map-independent. It works in Ukraine today. Do not build anything for it.

---

## 6. Uncertainty — what this report does not establish

**The one question answerable in a single drive:** *does Waze show a speed limit in Ukraine, and does
the Tang L's cluster show a camera-derived limit next to a posted sign?* Ukraine was absent from
Waze's 2016 speed-limit country list and I found no source confirming or denying it since (§3.1);
separately, the `TSR` message proves the *capability* but not that the deployed model reads Ukrainian
signs, nor that the cluster displays a camera limit with a blank map. Two glances settle both.

Everything else, ranked by how much it matters:

1. **Whether the Tang L's TSR is usable on Vienna-Convention signage.** GB 5768 harmonised Chinese
   signage with the 1968 Convention and Ukraine is a signatory, so sign *geometry* matches — but that
   is inference. Against it: a Belarusian Yuan Up owner reports the camera missing exactly the
   settlement and end-of-restriction signs that set Ukraine's implicit urban limit (§1.6, anecdote),
   and Euro NCAP twice found BYD speed-sign interpretation deficient (§1.6, confirmed). Since BYD
   documents TSR as camera *fused with* navigation, a blank map may suppress the display outright.
2. **Amap's road-facility type enum.** Not in the decompiled sources; I cannot say whether
   `naviFacilityType` has a bump value. Moot here, but it is why §1.4 stops short.
3. **Whether God's Eye B decelerates for bumps** under ACC/LCC, or only adjusts the suspension. Only
   marketing copy for God's Eye C was found.
4. **`NewLaneLineDataNotify`'s event id**, and whether the SOME/IP gateway permits a third-party app
   to subscribe to service 0x000C at all.
5. **Whether fids 1083203616 / 754057272 do anything on this car.** Never probed.
6. **The Waze alert view ids.** Item 2 in §5 cannot be designed without a live dump; I have no
   evidence how Waze exposes road-feature alerts to the accessibility tree, or whether it does.
7. **Germany's bumps-per-kilometre figure.** The Overpass length query failed on all nine attempts
   across two endpoints (out-of-memory, then dispatcher timeouts). Germany's counts are measured, its
   density is not, and I have not estimated it (§3.3).
8. **HERE/TomTom bump attribution.** HERE's "Speed Bumps or Chicanes" is confirmed but too coarse
   (§3.4); TomTom's full catalogue is behind commercial agreements, so its absence is unproven, and
   whether HERE's freemium tier exposes any traffic-calming layer is unconfirmed.
9. **The EU 2021/1958 numeric thresholds** (2.0 s, 90%/80%, 400 km) come from a homologation
   consultancy's digest cross-checked against a search-indexed excerpt of the EUR-Lex text; the full
   official text exceeded the fetch size limit and was not read end to end.
10. **The Dubno GeoJSON** (§3.5a) — the only Ukrainian open dataset claiming to hold speed bumps —
    could not be downloaded; its host refused connections.
11. Every §2 finding rests on the DiLink 150 build in `D:\BYD\DiLink`. A later OTA could add
    messages. Nothing here was verified against the running car — it is offline.
