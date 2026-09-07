# Could a third-party app feed the Tang L's driving computer from a commercial map (TomTom)?

Feasibility study requested by the owner after the safety concern was raised twice. Nothing here was
tried on the car; no code was written. Question: the factory map is a China-only Amap build, so NOA
never arms in Ukraine — could an app play the map's role on the SOME/IP bus using TomTom (or another
commercial map), the way BYDMate already drives the HUD?

Legend: **[FW]** = read out of the firmware/decompiled sources on this machine, with file:line.
**[FACT]** = a web page I fetched says this, URL given. **[SNIPPET]** = only from a search-engine
extract, source page not fetchable (JS-rendered or 403). **[INFERENCE]** = my reading.
**[unknown]** = not decidable from the evidence available.

Background, do not repeat here: [`noa-someip-contract.md`](noa-someip-contract.md) (message-by-message
contract), [`noa-map-app-analysis.md`](noa-map-app-analysis.md) (route hand-off chain, ODD),
[`noa-maps-ukraine.md`](noa-maps-ukraine.md) (consolidated assessment),
[`amap-car-vs-mobile-map-data.md`](amap-car-vs-mobile-map-data.md) (why foreign Amap data is
unobtainable), [`tang-l-hud-someip.md`](tang-l-hud-someip.md) (what BYDMate already sends).

## 0. Answer up front

Three independent walls, in order of hardness:

1. **Identity.** The route JSON on 0x2B carries `linkID` (AutoNavi 64-bit topology id), `adminCode`
   (Chinese GB/T 2260 adcode) and `URID` (AutoNavi city id), and the enums `roadclass`, `formway`,
   `linktype`, `mainAction` are AutoNavi's own value sets. TomTom has none of these and no mapping to
   them exists. The driving computer's *own* ODD list is published in AutoNavi URID numbering **[FW]**,
   which is the strongest single piece of evidence that the ADAS side lives in the same China
   identity space and has nothing for Ukraine.
2. **Licence.** TomTom's ADAS products are OEM/Tier-1 contract items ("tailor-made packages that
   enable OEMs", formats MultiNet-R and NDS) **[FACT]**; the self-serve Maps APIs define feeding an
   "In-Car System" — explicitly including "any automated driving or driver assistance system" — as
   *Automotive Usage*, i.e. outside the ordinary developer licence **[SNIPPET]**. HERE and Mapbox have
   equivalent or stricter gates. Only OSM has no licensing gate, and Ukrainian OSM attribute density
   is far too thin for the fields that matter.
3. **Safety.** Every message the map sends on 0x0007 / 0x8202 / 0x2B / 0x2D goes *into* the driving
   domain. There is no display-only subset on that side of the bus. The display-only channels are
   0x010A (HUD) and the instrument-panel feature ids — which is exactly what BYDMate already uses,
   and the only part of this idea with zero safety exposure.

Verdict in §9. The short version: the useful and safe work is already done; the route feed is not
buildable to a standard an individual can stand behind, and the one concrete action item is to turn
off (or delete) BYDMate's existing "factory-map context" channel, which today can emit fabricated
navigation data on two ADAS-facing services for no benefit on this car.

## 1. What the driving computer expects (one-paragraph recap)

**[FW]** Route JSON on **0x2B/0x8002** (`MessageSend{code,msg,mapProvider,RouteType,endPOIInfo,data}`,
chunked), path announcement on **0x2D/0x8001** (`pathID_Req`), per-second map-matched position on
**0x8202/0x8008** (`sdVehicleLocation{curSDRouteID,curStepId,curLinkId,linkOffset,…}`), navigation
state + traffic/speed-limit/ODD-distance on **0x0007**, and enrichment (lanes, lights, tunnels, SAPA,
facilities, weather, admin codes) on the rest of **0x8202**. The ADAS answers on **0x2C**:
`RouteInfo{pathID,totalNOADistance,routeBeginIdx,routeEndIdx,statusCode}` and a
`status_code == 200` confirm of the selected path. The map is a *client* of 0x2C and 0x000D; the ADAS
ECU is elsewhere on 192.168.195.x (head unit is 192.168.195.2, SD multicast 224.0.2.4:30490)
**[FW: `D:\BYD\DiLink\someip\someip_stack.json`]**.

Two details from this pass that were not in the earlier notes:

- **`NavigationStatus_LinkInfoNotifyStruct` has field 5 `MatchingTableStatus` and field 12
  `LinKArray[LinK{LinkID}]`** **[FW: `D:\BYD\DiLink\lib\proto-strings.txt:17641`]**. The interface was
  designed around a *matching table* between the navigation map's link ids and the ADAS's own, plus a
  live list of current link ids. This build fills neither — but the fields exist, which is the
  clearest hint that link identity is meant to be matched, not merely carried.
- **An `ADASISV2Notify{ADASIS_V2 string}` event exists on 0x0007/0x8002**, generated from BYD's
  platform-wide proprietary proto
  (`vendor/byd/someip/proprietary/.../ivi_di5.1/protobuf/someip.navigation.status_.link.info.service.proto`)
  **[FW: `proto-strings.txt:9980-10093`]**. The factory map never populates it, `libsomeipimpl.so` has
  no special handler for it, and whether the DiPilot 300 subscribes is **[unknown]**. It is the one
  place in the interface where a standards-based horizon could in principle be injected — see §8.

## 2. TomTom in 2026

**Products.** *Orbis Maps* is the current map platform, built by combining ODbL open data from
Overture/OSM with TomTom proprietary data, 235+ countries, each attribute carrying its own licence
**[FACT: https://developer.tomtom.com/tomtom-orbis-maps/documentation/introduction]**.
*Orbis Lane Model Maps* and the *ADAS SDK* both launched 2026-01-02
**[FACT: https://www.tomtom.com/newsroom/press-releases/general/129015776212780/tomtom-launches-adas-sdk-to-accelerate-driving-automation-and-regulatory-compliance/]**.

**Does TomTom ship an on-device SDK that emits ADASIS? Yes.** The ADAS SDK is "a lightweight software
development kit that delivers predictive map data directly to ADAS", "supports industry standards
like ADASIS v2/v2 mini and NDS.Live", computes a Most Probable Path with a 200+ km lookahead, runs
online via NDS with a configurable onboard cache or fully offline, and works with or without embedded
navigation **[FACT: https://www.tomtom.com/products/adas-sdk/]**. Attributes: speed limits, lane
connectivity, curvature, gradient, traffic signs, statistical speed profiles. **No ADASIS v3.**

**Can an individual get it? No.** The ADAS SDK page has no pricing and only a "Contact sales" form
**[FACT]**; TomTom's own pricing index lists ADAS SDK and Automotive Navigation App under "Contact
Sales" while Map Display / Routing / Traffic / Places are self-serve with perpetual free monthly
quotas (e.g. Routing 20K/month, no credit card)
**[FACT: https://docs.tomtom.com/pricing]**. The older ADAS Map product sheet is explicit about the
audience: "available with tailor-made packages that enable OEMs to develop Advanced Driving
Assistance Systems", delivered as **MultiNet-R** or **NDS**, coverage 5.5 M km / 73 countries,
attributes gradient, curvature on road, curvature at junction, lane at junction, traffic signs, speed
restriction **[FACT: https://download.tomtom.com/open/banners/ADAS-Map-Product-Info-Sheet.pdf]**.

**The licence wall on the self-serve side.** TomTom's developer terms define
*"In-Car System" = "any product installed in a vehicle by or on behalf of an automotive OEM as part
of the manufacturing process… Without limitation, this shall include an in-dash navigation system or
any automated driving or driver assistance system"*, and define *Automotive Usage* as the case where
the solution "is either integrated with a vehicle or is otherwise capable of interacting with any
In-Car System or After-Market Product in a manner that enables the Content to be utilized by such
In-Car System"; there is also a prohibition on use "in any high-risk systems, devices, products or
services that are critical to the health and safety or security of people and property"
**[SNIPPET — developer.tomtom.com/terms-and-conditions is JS-rendered and could not be fetched; these
are search-index extracts of that page. Verify in a browser before relying on the exact wording.]**
**[INFERENCE]** Feeding TomTom content into DiPilot is squarely Automotive Usage and arguably a
high-risk system; the free Routing API tier does not cover it.

**Ukraine coverage.** Routing API and real-time traffic: full coverage, both columns ✔
**[FACT: https://docs.tomtom.com/routing-api/documentation/tomtom-maps/v1/product-information/market-coverage]**.
Navigable street network: Mapfactor's redistributor sheet of TomTom Standard Maps (6/2024) lists
Ukraine at **100 / 100 / 100** (street / interconnecting / major road network)
**[FACT: https://www.mapfactor.com/wp-content/uploads/2024/08/TT_standard_maps_coverage_2024.pdf]**.
A legacy consumer-device page still shows "Ukraine 45%"
**[FACT: https://help.tomtom.com/hc/en-us/articles/360013961359-Available-Map-Zones-TomTom-HOME-]** —
an old PND map-zone figure, contradicted by the current data. **Attribute depth is the real question
and the answer is unfavourable**: Orbis lane-level geometry "can already be available… across
Germany", other countries "rapidly scaling"
**[FACT: https://www.tomtom.com/newsroom/press-releases/general/129429267311461/…]**, and no
per-country ADAS-attribute matrix is published **[not found]**. **[INFERENCE]** Ukraine is very
unlikely to have TomTom lane-level or ADAS-grade curvature/gradient today.

Route-level attributes an individual *can* get self-serve: the Extended Routing API returns lane
sections and per-section speed limits, but only with "an API key for the Routing API with extended
guidance" **[FACT: https://developer.tomtom.com/navigation/android/guides/routing/route-sections]** —
i.e. still an account-level entitlement, not the default key.

## 3. The realistic alternatives, briefly

| | ADASIS out? | Reachable by an individual? | Ukraine attribute depth |
|---|---|---|---|
| **TomTom** | ADAS SDK: ADASIS v2 / v2-mini, NDS.Live **[FACT]** | No — contact sales; ADAS Map sold as OEM packages **[FACT]** | Navigable 100 %; lane-level = Germany first **[FACT/INFERENCE]** |
| **HERE** | Yes in the embedded stack (founding ADASIS member); the *current public* HERE SDK "does not offer direct support for the ADASISv2 and ADASISv3 protocols" **[FACT: https://docs.here.com/here-sdk/docs/flutter-electronic-horizon]** | No — Electronic Horizon is Navigate-licence only and "Navigate… is only available for customers who have signed a contract with HERE" **[FACT: https://docs.here.com/here-sdk/docs/android-introduction-editions]**; freemium excludes "Safety Enhancement Alerts" **[FACT: https://www.here.com/get-started/pricing/base-plan-restrictions]**; AUP §7(a) bars high-risk use **[FACT: https://www.here.com/en-gb/terms/acceptable-use-policy-january-2022]** | Reseller country sheet for Ukraine lists only Advanced Map with Direction of Travel, Functional Class, **speed limits FC 1-2**, sign text, tolls, TMC — no lanes, slope or curvature **[FACT: https://www.europa.uk.com/map-data/here-maps/country/?cc=ua]** |
| **Mapbox** | Yes — the separate **ADAS SDK** emits "ADASIS v2 binary format for direct CAN bus integration" **[FACT: https://github.com/mapbox/mapbox-adas-sdk]** | Nearly — free EH tier, public repo, ~1 000 eval trips/month; **but** slope/curvature in the evaluation build are limited to the greater Munich area **[FACT]**, and the ToS requires a separate paid development *and* commercial licence for any "vehicle usage", defined to include "use in any vehicle system or component thereof (whether installed in a vehicle during manufacture or thereafter)" **[FACT: https://www.mapbox.com/legal/tos]** | Base data is OSM-derived **[FACT]**; no Ukraine figures published **[not found]** |
| **OSM + Valhalla** | No encoder exists; you would write one | Yes, no licensing gate. **ADASIS v2 spec through 2.0.5 is free to download** from adasis.org, and v3 3.1.0 became public 2025-12-31 **[FACT: https://adasis.org/specification/]** | Ukraine taginfo, snapshot 2026-09-06: `maxspeed` on 130 554 of 2 601 656 highway ways (**5.0 %**), `lanes` **3.7 %**, `turn:lanes` **0.14 %**; Germany `maxspeed` **17.6 %** **[FACT: https://taginfo.geofabrik.de/europe:ukraine/]** |

Valhalla's `trace_attributes` is the only open engine returning road class, lane count, speed limit,
per-edge headings and grade in one map-matched response
**[FACT: https://valhalla.github.io/valhalla/api/map-matching/api-reference/]**; GraphHopper's
`path_details` is close behind; OSRM does not expose `maxspeed` at all. None of them output curvature.
**[INFERENCE]** For Ukraine the binding constraint is not the engine: `turn:lanes` at 0.14 % makes
lane-level guidance impossible, and `maxspeed` at 5 % means speed limits would be **inferred from road
class**, i.e. guessed. **No mature open-source ADASIS provider fed from OSM exists** — GitHub has
`kashiwado/adasisv2` and `ReneMattheis/phosphor`, both single-commit stubs; the working
implementations (Elektrobit EB robinos Predictor, dSPACE Horizon Reconstructor, Mapbox ADAS SDK) are
commercial **[FACT]**.

## 4. Prior art

- **Exists:** a third party computing OSM speed limits and curvature and modulating a *production
  car's own* cruise controller — openpilot's OPKR fork does it on Hyundai/Kia by spamming the cruise
  buttons on CAN ("OSM integration for auto SCC speed adjust via button spamming and slow down on
  curve") **[FACT: https://github.com/openpilotkr/openpilot]**, with `pfeiferj/mapd` as the OSM
  horizon engine used by sunnypilot/FrogPilot **[FACT: https://github.com/pfeiferj/mapd]**.
- **Comma's own trajectory is the most relevant data point.** OSM speed limits added in 0.5.7
  (2018-12), *removed* in 0.5.13 (2019-05) "to improve stability"; Mapbox navigation added 0.8.7; map
  info fed to the model as a soft input in 0.9.4; the whole navigation stack deleted in PR #32773
  (2024-06); current `LIMITATIONS.md` says "openpilot does not detect speed limits at this time"
  **[FACT: https://github.com/commaai/openpilot/blob/master/RELEASES.md,
  https://github.com/commaai/openpilot/pull/32773]**. The reported failure mode is the one that
  matters here: issue #603, the car "suddenly attempting to slow to 35mph on a 70MPH interstate" from
  bad OSM data **[FACT: https://github.com/commaai/openpilot/issues/603]**.
- **Exists:** third-party nav app → factory HUD/cluster. DriveMods ship modified Kia/Hyundai head-unit
  firmware that renders Yandex Navigator maneuvers on the factory cluster and HUD **[SNIPPET — drive2
  403s to fetchers]**. This is the category BYDMate is already in.
- **[not found], after a broad search:** any hobbyist, commercial or academic instance of a third
  party *impersonating a factory navigation unit's ADAS input* (ADASIS-style horizon or equivalent) on
  a production car — including Scania Active Prediction, Daimler PPC and Volvo I-See, where only
  dealer retrofits are documented.
- **[not found]:** any project unlocking BYD/XPeng/NIO/Li Auto NOA outside China, or feeding a
  third-party route into any Chinese OEM's ADAS domain. BYD community work (e.g.
  `wheregoes/byd-dolphin-hacking`, which does document CAN injection from ADB) stops at the
  cluster/infotainment layer; ADAS is absent.
- **Adjacent attacks all go through GNSS or perception, not the map channel.** Regulus Cyber's 2019
  GPS spoof of a Tesla on Navigate on Autopilot caused an abrupt slow-down, indicator and a sharp turn
  off the road **[FACT: https://www.helpnetsecurity.com/2019/06/19/tesla-gps-spoofing-attacks/]**;
  McAfee's taped "35 → 85" sign made a Mobileye-equipped Tesla accelerate ~50 mph over the limit
  **[FACT: https://www.mcafee.com/blogs/other-blogs/mcafee-labs/model-hacking-adas-to-pave-safer-roads-for-autonomous-vehicles/]**.
  **[INFERENCE]** Bus-level injection of a car's map/horizon channel is an unexplored area, not a
  solved one — the absence of prior art here is a warning, not an opportunity.

## 5. Field-by-field: what TomTom could and could not fill

Target is `platformb.RouteInfo.linkInfos[]` on 0x2B/0x8002 **[FW:
`D:\BYD\DiLink\apk\launchermap-jadx-classes8\sources\k\k\d0\d\f\l.java:111-141`]**. Source column: a
self-serve TomTom Routing/Extended Routing response, since the ADAS SDK is out of reach.

| Field | From TomTom? | Note |
|---|---|---|
| `pathID` | synthesise | opaque 64-bit handle; only self-consistency is observable **[FW]** |
| `points[] (x,y)` | **yes** | route polyline; but factory data is GCJ-02 and TomTom is WGS-84 **[FW: `WGS84ToMGS` in `libAutoDice.so`]** — datum mismatch, and no GCJ-02 offset is defined outside China |
| `length`, `pntBegIdx/pntCnt`, `linkIndex`, `relatedSegmentIndex` | synthesise | pure bookkeeping over your own polyline |
| `roadName` | yes | cosmetic |
| **`linkID`** | **no** | `LinkInfo.get64TopoID()`, an AutoNavi tile-scoped topology id (`getTileID`/`get64TopoID` are native) **[FW: `…\common\path\option\LinkInfo.java:285,578`]**. No mapping from any TomTom id exists. The map itself writes `-1` on overflow, so `-1` is at least parseable **[FW: l.java:113]** — that is the only thing known about how the ADAS treats an unmatchable id |
| **`adminCode`** | **no** | `linkInfo.getAdcode()` = GB/T 2260 Chinese administrative code; no Ukrainian value exists |
| **`URID`** | **no** | AutoNavi city/region id; matched against the ADAS's ODD list **[FW: `m.java:199-218`]** |
| `roadClass` | **no, enum** | AutoNavi `RoadClass`: Freeway 0, National 1, Province 2, County 3, Rural 4, InCounty 5, **CitySpeedway 6**, MainRoad 7, Secondary 8, Common 9, NonNavi 10 **[FW: `…\common\path\model\RoadClass.java`]**. TomTom functional class 0-8 is a different taxonomy; any mapping is an invention, and 0/6 are exactly the two values the ODD rule keys on |
| `formway` | **no, enum** | AutoNavi `Formway`: Divised 1, Cross 2, JCT 3, RoundCircle 4, ServiceRoad 5, SlipRoad 6, SideRoad 7, SlipJCT 8, Exit 9, Entrance 10, turn lines 11-16, Common 15, Service* 53/56/58 **[FW: `Formway.java`]**. ODD rule: `roadClass ∈ {0,6}` or `formway ∈ {3,6,8,9,10}` **[FW: `m.java:796-802`]** |
| `linkType` | **no, enum** | Common 0, Ferry 1, Tunnel 2, Bridge 3, ElevatedRd 4 **[FW: `LinkType.java`]** — this one is mappable from TomTom section types |
| `mainAction` / `assistantAction` | **no, enum** | AutoNavi `MainAction` 0-69 (TurnLeft 1 … Continue 8, MergeLeft/Right 9/10, Entry/LeaveRing 11/12, Slow 13, indoor 65-69) **[FW: `MainAction.java`]**. BYDMate already maps Waze maneuvers into the Gaode code table for the HUD, so a mapping is possible — but this table is the *routing* action set, not the HUD arrow set |
| `ownership` | partial | PublicRoad 0, Internal 1, Private 2, UndergroundPark 3 **[FW: `Ownership.java`]**; the map collapses 1..4 → 1 |
| `laneNum` | **no** for Ukraine | TomTom Extended Routing gives lane sections behind an entitlement; Orbis lane model is Germany-first; OSM `lanes` covers 3.7 % of Ukrainian highway ways |
| `speedLimit` | n/a | the map hard-codes `0` here **[FW: l.java:132]** even though `LinkInfo.getSpeedLimitNative` exists — speed limits reach the ADAS on 0x0007/0x8003 `TrafficInfo.SpdLmtSpeedValue` instead |
| `hasTrafficLight`, `hasMultiOut`, `hasParallel`, `hasMixFork`, `direction`, `isOverHead`, `isAtService` | mostly **no** | AutoNavi topology predicates with no TomTom equivalent; fabricating them is fabricating road structure |
| 0x8202/0x8008 `curSDRouteID`/`curStepId`/`curLinkId`/`linkOffset` | synthesise | indices into your own route — consistent by construction |
| 0x8202/0x8008 matched lat/lon | risky | today these are AutoNavi map-matched; substituting raw GNSS changes the ADAS's lateral reference **[FW, flagged in the earlier analysis]** |
| 0x8202/0x800C lane arrays, 0x800D/0x800E JSON | **no** | AutoNavi lane model, schema not even fully recovered |
| 0x0007/0x8004 `noODDRegionDist` | forceable | `Integer.MAX_VALUE` = "no ODD boundary within 2 km"; the map already sends MAX when the city list is empty **[FW: `c\f.java:96-100`]** |

**Summary of §5.** Geometry, lengths, indices, road names and (with effort) maneuver actions are
synthesisable. **The three identity fields and four enum-valued attributes are not** — not "hard", but
*not defined outside AutoNavi's data*. Anything written into them is a guess dressed as a measurement.

## 6. The core question: would the ADAS accept a route it cannot match?

**What is actually known [FW]:**

- The ADAS answers on 0x2C with `RouteInfo{pathID, totalNOADistance, routeBeginIdx, routeEndIdx,
  statusCode}` — per-path *index ranges*. That is the ADAS telling the map which stretch of the
  supplied route it is willing to drive.
- `RouteCityOddController.k(list)` — the callback that consumes those ranges — **is an empty body in
  this build** (`d\d.java:130-132`). The head unit therefore does not need the ADAS to match anything;
  the ODD bar it shows the user is computed locally.
- The ADAS's ODD region list arrives as `ODDRegionCodeResp{ODDRegionCodeValue u32}` on 0x000D method
  2, and the map compares those integers against **AutoNavi `LinkInfo.getURID()`** (`m.java:210-215`).
  So the driving computer's own ODD whitelist is expressed in AutoNavi's Chinese city codespace.
- 0x000A `VehiclePositionInfo` carries `hd_link_id`, `hd_lane_id`, `hdmap_version`, `HDStatus`
  (`proto-strings.txt:19002-19013`) — the ADAS maintains its own map-referenced localisation state.
- The 0x0007 struct reserves `MatchingTableStatus` and a `LinKArray` of link ids that this map build
  never fills.
- The map emits `linkID = -1` when the topology id overflows int64, so the receiver must tolerate at
  least the *value* −1 in a link that is otherwise real.

**What is unknown:** whether the ADAS requires a successful link-id match to return a non-empty
`routeBeginIdx…routeEndIdx`; what `statusCode` values exist; whether `status_code == 200` on 0x2C/0x8002
is granted on geometry alone; whether the DiPilot's ODD is applied against `URID`, against its own
map, or against both; whether it subscribes to `ADASISV2Notify` at all. None of that is in any file on
this machine — the consumer is a separate ECU (Orin-X, Momenta stack) whose binaries we do not have.

**[INFERENCE], stated as such.** The presence of `totalNOADistance` and index ranges in the reply, of
a `MatchingTableStatus` field, and of an ODD whitelist in AutoNavi city numbering, together point to
an ADAS that resolves the incoming route against its own database and returns the drivable portion. On
a Ukrainian road that resolution has nothing to match, so the expected outcome is empty ranges,
`totalNOADistance = 0`, and NOA not arming — the same end state as today, reached later and after the
driving computer has ingested fabricated attributes. I would put this at "likely" (say 70-80 %), not
"certain". The alternative — that it map-matches on polyline alone and treats the attributes as
advisory — cannot be excluded and would be *worse*, because then the fabricated `roadClass` and
`laneNum` would be believed.

**Experiments that answer this while sending nothing to the ADAS**, ordered by value:

1. **Read the ADAS feature ids** through the helper daemon (pure autoservice reads, no bus traffic):
   `ADAS_HNP_CONFIG`, `ADAS_UNP_CONFIG`, `ADAS_E2E_CONFIG`, the three switch states,
   `ADAS_ASSIST_DRIVE_MODE_STATUS`, `ADAS_NOA_UI_TYPE`, `ADAS_SMART_DRIVE_SLEEP_MODE_SWITCH_STATUS`,
   and above all **`ADAS_FUNCTION_ALGORITHM_SUPPLIER`** (2 → `uke.SDRoute`, 6 → `platformb.RouteInfo`,
   else `NaviToEHPData`) **[FW: `a\r.java`, `d\f\k.java:134-176`]**. This settles whether NOA is
   configuration-blocked as well as route-starved, and which JSON schema the ADAS expects. Zero
   exposure. Highest information per unit of risk.
2. **Listen to SD multicast 224.0.2.4:30490** and record which services the ADAS *offers* (0x2C,
   0x000D, 0x000A/0x000C/0x000E) and their instances. Pure receive; a multicast join is not a message
   to the ADAS. Confirms the driving computer is awake and speaking at all in Ukraine.
3. **Subscribe read-only** to 0x2C and 0x000D with a sniffer (openbyd's `SomeIpSniffer` decodes
   protobuf payloads generically —
   `%LOCALAPPDATA%\Temp\bydw\openbyd-jadx\sources\com\sr\openbyd\utils\SomeIpSniffer.java`) and log for
   a few drives with the factory map in cruise mode. This is an SD SUBSCRIBE, not a payload — near-zero
   exposure — and it captures the ADAS's own `PilotStatus`, alarm and (if it ever emits one) path-match
   traffic and its enum values.
4. **`ODDRegionCodeReq` on 0x000D method 2.** A read-only RPC the factory map already issues
   routinely; it returns the ADAS's ODD city list. Low but *not* zero exposure (it is a request). If
   the list comes back non-empty and full of Chinese city codes, that closes §6's identity argument
   with the car's own data.
5. **Borrow a capture from China.** A 0x2B/0x2C recording from a Chinese Tang L with NOA armed would
   give the `statusCode` value set and the real `SDRoute`/`RouteInfo` variant — most of what is listed
   as unknown above — with no exposure on this car at all. Worth asking for in the BYD communities.

## 7. Risk, plainly

**Which messages have safety authority.** On the map → ADAS side, *all of them*. 0x2B/0x2D define the
route the driving computer plans on; 0x8202/0x8008 supplies the map-matched position it uses as a
lateral reference; 0x0007/0x8003 `TrafficInfo` carries the speed limit and camera speeds; 0x0007/0x8004
and /0x8006 carry the ODD distance; 0x8202's traffic lights, tunnels, service areas, tidal lanes,
ramps and lane arrays feed city-NOA behaviours including the ETC-gate and traffic-light logic added in
the Dec-2025 OTA. There is **no display-only subset**. The display-only channels are the other
direction (0x000A/0x000C/0x000E, which the head unit renders as the "God's Eye" scene) plus 0x010A HUD
and the instrument-panel feature ids.

**What fails, and how, if fabricated values reach it.** `roadClass 0/6` and `formway 3/6/8/9/10` are
the literal predicate for "this is a motorway/ramp" in the code we can read; asserting them on a
Ukrainian secondary road is asserting that a two-lane road with oncoming traffic is a divided
motorway. A wrong `laneNum` misplaces lane-change planning. A wrong `mainAction` puts a maneuver at
the wrong node. `noODDRegionDist = MAX` asserts no ODD boundary ahead. Curvature and speed limits
inferred from OSM road class produce exactly comma.ai's issue #603 failure — a hard deceleration on a
fast road, or worse, its inverse. And the classic realisation of this class of fault already exists in
the literature: the Regulus Tesla spoof, where wrong navigation state at a decision point produced an
abrupt slow-down and a sharp turn off the road.

**The compounding factor here is unfalsifiability.** With no ADAS-side binaries, no bus capture, no
test track and no way to replay a scenario, a builder cannot know which of the fabricated fields the
DiPilot believes, cannot bound the failure modes, and cannot regression-test a change. Every trial is
a live trial, on public roads, in a car whose Level-2 system will not warn you that its inputs are
fiction. That is the part I would not soften: this is not "risky if it goes wrong", it is "you would
have no way to find out that it had".

**Legal/insurance, briefly and without exaggeration.** I found no Ukrainian rule that addresses this
specifically, and this is not legal advice. But BYD's own position is that export NOA needs local road
testing and validation (Indonesia 2026-27, Brazil 2027), the supplier terms above put in-vehicle ADAS
use outside the self-serve licences, and a modified ADAS input is the kind of fact an insurer or an
investigator would find after an incident.

**What has zero safety exposure.** Everything BYDMate does today on 0x010A and the instrument-panel
feature ids: guidance to the AR-HUD glass and cluster. Reading ADAS state (feature ids,
0x000A/0x000C/0x000E, PilotStatus) for display or logging. Passive SOME/IP recording. None of these
write into the driving domain.

**One concrete finding about the current app.** `HudLauncherMapContext` (BYDMate's port of openbyd's
`LauncherMapCnStrategy`) opens and fires events on **0x0007 (`NavigationStatus`, `TrafficInfo`)** and
**0x8202 (`naviActionAndCamera`, `nextIntersectionLanesInfo`)** — two of the ADAS-facing services —
with BYDMate's guidance values and constants sniffed from the factory map
(`app/src/main/kotlin/com/bydmate/app/hud/HudLauncherMapContext.kt`). The dev.3/dev.4 bisection showed
it changes nothing on this car's glass, and since dev.6 it is off by default
(`tang-l-hud-someip.md` §5a). **Recommendation: delete it, or at minimum keep it off and label the
switch as ADAS-facing.** It is fabricated navigation state pointed at the driving domain, with a known
benefit of zero on this vehicle. (It also offers 0x000C/0x000D/0x000E as *server*, services the ADAS
itself offers — a duplicate service instance on the bus, which is its own hazard.)

## 8. Verdict and ranked options

1. **Safe and useful now — keep doing this.** Guidance to the AR-HUD/cluster from Waze or any other
   app over 0x010A and the instrument feature ids. Already works, no ADAS involvement. *Do:* finish
   the road-name byte write and the arrival-time features.
2. **Safe and informative — do this next.** The read-only experiments in §6 (1)-(3): ADAS feature-id
   dump, SD multicast observation, passive 0x2C/0x000D recording. These close the open questions with
   the car's own data and cost nothing. Add §6 (5), asking a Chinese owner for a capture.
3. **Low exposure, decide deliberately.** §6 (4), the ODD region-code request. It is a read, but it is
   a message to the ADAS. Worth it only after (1)-(3).
4. **Cleanup.** Remove or clearly quarantine `HudLauncherMapContext` (§7).
5. **Not recommended — a synthetic route provider on 0x2B/0x2D/0x8202.** Technically reachable (the
   gateway takes any client, the schema is known, BYDMate already offers services successfully), and
   it will almost certainly still not arm NOA, because the identity space is Chinese and the ADAS's
   own ODD list is keyed on AutoNavi city ids. The path to "it works" runs through fabricating road
   classification, lane counts and ODD claims for a driving system you cannot test. Do not build it.
6. **Would require capabilities not available to an individual — the "proper" version.** A TomTom
   ADAS-SDK-class electronic horizon fed into a validated interface: needs an OEM/Tier-1 data licence
   (contact-sales, MultiNet-R/NDS), TomTom ADAS coverage of Ukraine that does not yet exist,
   ADAS-side documentation BYD does not publish, and a validation programme with closed-course
   testing. This is the shape of BYD's own 2027 export-NOA plan. It is not a hobby project.
7. **Dead ends already established.** Foreign data into the factory Amap engine (signed, adcode-keyed,
   GCJ-02 — see `amap-car-vs-mobile-map-data.md`); memory NOA as a map-free path (compiled out);
   an export map app transplant (no NOA integration, no AutoNavi link ids).

**One honest caveat about the ADASIS slot.** `ADASISV2Notify` on 0x0007/0x8002 is the one place where
a standards-based horizon could enter this car without inventing AutoNavi identities, and TomTom,
HERE and Mapbox all speak ADASIS v2. If a passive capture ever showed the DiPilot subscribing to that
event, the identity problem (§5) would go away and only the licence and validation problems (§2, §7)
would remain. Those two are still decisive for an individual, so this changes the analysis, not the
verdict.

**Open items that would change the answer:** `ADAS_FUNCTION_ALGORITHM_SUPPLIER` on this car (unread);
whether the ADAS offers or emits on 0x2C in Ukraine (unobserved); the `statusCode` /
`NavigationStatus` enum value sets (need a China capture or ADAS binaries); whether `ADASISV2Notify`
has a subscriber (unknown); TomTom ADAS-attribute coverage for Ukraine (no published matrix — a sales
enquiry would answer it and confirm the licensing position in writing). Also: the TomTom
developer-terms wording quoted in §2 came from a search index, not a fetched page — re-read it in a
browser before treating it as settled.
