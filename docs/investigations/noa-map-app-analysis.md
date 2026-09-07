# NOA (智驾领航) in the factory map app — what the ADAS side needs from navigation

Scope: static reading of the jadx output of the DiLink 150 Amap-based launcher (`launchermap-*`), Tang L 2025.
No car, no bus captures. Class names are obfuscated; everything below is anchored on Logger tags.
Paths are relative to `D:\BYD\DiLink\apk\`. `c8` = `launchermap-jadx-classes8\sources`, `c16` = `launchermap-jadx-classes16\sources`,
`j7` = `launchermap-jadx7\sources`, `j11` = `launchermap-jadx11\sources` (generated protobuf).

Key classes (obfuscated -> tag):

| File | Logger tag / role |
|---|---|
| `c8\k\k\d0\a\r.java` | `BydAutoNoaProxy` – reads/listens BYDAutoFeatureIds.Adas.* |
| `c8\k\k\d0\b\f.java` | `NoaStateManager` – derives the global flags |
| `c8\k\k\d0\c\j\i0\b\a.java` | static flag holder (`a`=DnpSwitch, `b`=DnpService/config, `c`=hasCNoa, `d`=cNoa on, `e`=hNoa on, `o`=downtime, `p`=memory, `m`=noODD dist) |
| `c8\k\k\d0\b\d.java` | `NoaCanSomeIpLink` – SOME/IP client/server lifecycle |
| `c8\k\k\d0\c\j\j0\d.java` | `AutoPilotHub` – topic -> proxy map |
| `c8\k\k\d0\c\j\e0.java` | `SendSomeIpDataManager` – all outgoing NOA messages |
| `c8\k\k\d0\d\d.java` | `RouteCityOddController` |
| `c8\k\k\d0\d\f\k.java`, `l.java`, `n.java` | `MomentaUtil`, `NaviToEHPUtil`, `SDRouteUtil` – route serialisation |
| `c8\k\k\d0\d\f\m.java` | `RouteOddSegUtil` – ODD segments from Amap PathInfo |
| `c8\k\k\d0\c\f.java` | `DnpOddDistanceController` – "distance to leave ODD" |
| `c8\k\k\d0\c\g.java` | `DriveRouteManager` – route change / path-select glue |
| `c8\k\k\d0\e\c1.java` | `PathRecordController` – memory NOA (记忆领航) |
| `j7\k\k\a0\c\c\a\f0.java` | `SomeIPMatrixManager` – per-second nav status / SD map inform |

SOME/IP topic longs decode as `0x000T_SSSS_SSSS_EEEE` (T: 1 = availability event, 2 = method, 4 = event, B = start service; S = service id; E = event/method id).
Service ids seen: `0x07` NavigationStatus_LinkInfo, `0x0A` vehicle position, `0x0C` obstacle/lane-line, `0x0D` PilotStatusAlarmInfo, `0x0E` (SR ui), `0x2B` NavigationSdLink2, `0x2C` NavigationPathMatchStatus, `0x2D` NaviPathUserSelectSts, `0x8202` SdMapInform.
The map is SOME/IP *server* for 0x07, 0x2B, 0x2D, 0x8202 (`NoaCanSomeIpLink.o()` `b\d.java:208-213`) and *client* of 0x0A/0x0C/0x0D/0x0E/0x2C.

---

## 1. NOA availability model

### 1.1 Feature ids read from the ADAS domain (`BydAutoNoaProxy`, `a\r.java`)

All values come from `BYDAutoADASDevice.get(...)` (autoservice), nothing is written back; there is no `set(` in the file.

| Getter | Feature id | Meaning as used |
|---|---|---|
| `p()` :397 | `ADAS_HNP_CONFIG` | highway NOA configured (0 = equipped, 3/4 = new-UI variants) |
| `u()` :437 | `ADAS_UNP_CONFIG` | city NOA configured (0 = equipped) |
| `n()` :377 | `ADAS_E2E_CONFIG` | end-to-end NOA configured (0 = equipped) |
| `q()` :405 | `ADAS_HNP_SWITCH_STATE` | HNP user switch (1 = on) |
| `v()` :445 | `ADAS_UNP_SWITCH_STATE` | UNP user switch (1 = on) |
| `o()` :385 | `ADAS_E2E_SWITCH` | E2E user switch (1 = on) |
| `F()` :199 | `ADAS_ASSIST_DRIVE_MODE_STATUS` | "new" combined NOA switch: 3 = highway NOA, 4 = city(+highway) NOA |
| `I()` :224 | `ADAS_NOA_UI_TYPE` | 2 = new NOA UI semantics for the values above |
| `r()` :413 | `ADAS_DNP_STATE` | live DNP state (forwarded to the SR/HUD card) |
| `s()` :421 | `ADAS_SMART_DRIVE_SLEEP_MODE_SWITCH_STATUS` | 1 = ADAS in sleep mode -> NOA hidden |
| `t()` :429 | `ADAS_FUNCTION_ALGORITHM_SUPPLIER` | 2 or 6 select the route JSON schema (see §2.2) |
| `B()` :164 | raw id `-1728052359` ("ADAS_DISCONNECTION_STATUS") | value 2 = `isDowntimeState` (ADAS ECU down/upgrading) |
| `C()` :172 | raw id `1161822231` ("ADAS_MNOA_SELF_LEARNING_SIGNAL") | 1 = `memoryConfigState` |
| `J()` :232 | raw id `481296738` ("PARKING_SPACE_TO_PARKING_SPACE") | 2 = AVP equipped |
| `G()` | raw id `230686742` ("ADAS_PROMPT_ISLAND_ADAPTATION_STATUS") | 2 = "tip island" (灵动岛-style prompt UI), not geography |
| listener :34-46 | raw id `-1728052379` ("INTELLIGENT_DRIVE_PROMPT_INLAND_PROMPT_FUNCTION") | 10-byte prompt buffer for the island UI |

Derived booleans (all in `a\r.java`):

```java
// getHNoaConfigState  :480-486
boolean z = (I != 2 ? iF == 3 : !(iP != 3 && iP != 4)) || iP == 0;       // HNP_CONFIG==0, or new-UI codes
// getCityNOAConfig    :462-468   -> UNP_CONFIG == 0
// getNoaConfigState   :190-196   -> UNP==0 || E2E==0 || HNPconfig       => "DnpConfigState" / log label "DnpService"
// getHNoaSwitchState  :156-161   -> HNP_SWITCH==1 || ASSIST_DRIVE_MODE_STATUS in {3,4}
// getCityNOASwitch    :453-459   -> (uiType!=2 ? mode==3 : mode==4) || UNP_SWITCH==1
// getE2ESwitchState   :471-477   -> E2E_SWITCH==1
// f0() :249-258  onDnpSwitchState(any, hNoa = hSwitch&&hConfig, cNoa = cSwitch&&cConfig)   => "DnpSwitch"
```

`NoaStateManager` (`b\f.java`) folds in sleep mode and memory config:

```java
// b\f.java:172-192 onDnpSwitchState
boolean zG = g();                       // ADAS not in sleep mode
a.e = z2 && zG;  a.d = z3 && zG;        // hNoa / cNoa effective
a.p = z2 && zG && o();                  // memory NOA needs HNP on + MNOA self-learning signal == 1
a.a = z && zG;                          // DnpSwitch
```
and at init (`b\f.java:217-259`) logs `init noaSwitch cNoaSwitch hNoaSwitch noSleepStatus isDowntimeState memoryConfigState`.

So, **"DnpService"** in the logs is just the config flag `a.b` (vehicle equipped with any NOA), **"DnpSwitch"** is `a.a` (driver switched some NOA on and ADAS awake), **"CNOA"** is `a.d`. There is no binder/AIDL service called DnpService: grepping `c8`/`c16` for `DnpService` outside Logger strings returns nothing. All NOA traffic is SOME/IP (`ts.car.someip.sdk`) plus the `BYDAutoADASDevice` feature-id API.

### 1.2 What the flags gate

* SOME/IP client subscriptions are only made when `a.b && a.a` (`b\d.java:116-131`, `:178-200`), and incoming events are dropped while `a.o` (downtime) is true (`b\d.java:92-96`).
* Route hand-off (§2) is skipped unless `a.b && a.a` (`d\d.java:134-135`).
* ODD "distance to leave" (§3) only when `a.a` (`c\f.java:95`).
* The NOA card UI has an extra cloud switch (§5).

### 1.3 "云控" (cloud control)

Two booleans arrive from BYD's encrypted function-config service (`launchermap-jadx-classes5\sources\com\byd\automap\utils\MapFuncConfigManagerTwo.java`, decrypt at `:165-179`):

```java
// MapFuncConfigManagerTwo.java:325-335
new e(ID.NOA_ODD.name,  z -> MapSharePreference(hnoaOdd).j(isHnoaOddEnable, z))
new e(ID.NOA_CARD.name, z -> MapSharePreference(noaCard).j(isNoaCardEnable, z))
```
`NOA_CARD=false` -> `NoaResultListener` logs `云控关闭了NOA卡片相关显示` and never attaches the NOA card (`c16\k\h\f\f2\p\n6.java:19-23`).
`NOA_ODD=false` -> `RouteCityOddController.p(false, …)` (`c16\...\RouteResultMapPresenter.java:3504`) which stops the ODD percentage from being computed for the route bar (`d\d.java:60`), but does **not** stop the route hand-off.
Both default to `true` when the cloud value was never fetched.

---

## 2. Route hand-off to the driving computer

### 2.1 Trigger chain

`RouteResultMapPresenter.onSecondSuccess()` / `addMomentaRoadCal()` (`c16\...\RouteResultMapPresenter.java:4069-4070`, `:2936-2937`)
-> `routeCityOddController.m(mRouteCarResult)` (`c8\k\k\d0\d\d.java:134-155`):

```java
if (a.b && a.a) {
    this.d = (RouteCarResultData) iRouteResultData;
    ...
    if (!PlatformUtils.isSomeIpV2())
        workHandler.postDelayed(this::l /* "send SDRoute wait ODD" */, "msg_send_sd_route", 0L);
    if (AvpManager.hasAvpService() || a.d) return;      // wait for ADAS ODD reply (city NOA / AVP)
    b(new ArrayList<>());                               // else compute ODD locally now
}
```
`l()` (`:41-44`) -> `MomentaUtil.f(routeCarResultData)` (`d\f\k.java:121-181`) -> `SDRouteUtil.a(pathIds, json, n)` (`d\f\n.java`) -> `SendSomeIpDataManager.k(...)` (`c\j\e0.java:118-121`).
Also re-sent on every NOA switch-on (`RouteResultMapPresenter:3653-3655`, `DriveRouteManager.onDnpSwitchState` `c\g.java:152-160`), on path change (`c\g.java:138-147`) and on route update with a new pathId (`c\g.java:68-88`).

### 2.2 Carrier and payload

Carrier: **NavigationSDLink2, service 0x2B, event 0x8002** (`topic 1126084593287170 = 0x0004_002B_002B_8002`), sent by the map as *server* event:

```java
// c\j\e0.java:118-121
SomeIpData someIpData = new SomeIpData(1126084593287170L,
    naviSDRouteNotify.newBuilder().setNaviSDRouteStruct(
        naviSDRouteStruct.newBuilder().setNavigationSDLink2(str).setNavigationSDLink2Num(i).build()).build().toByteArray());
Logger.e("SendSomeIpDataManager", "sendPath2Momenta ret:{?} index：{?}  pathSize:{?} topic:{?}", ...);
```
`naviSDRouteStruct` = `{checks_crc32, counter, NavigationSDLink2Num, NavigationSDLink2:string}` (`j11\someip\navigation\sd\link2\service\SomeipNavigationSdLink2Service.java:4179-4183`). The string is **JSON**, chunked into 512 KiB pieces wrapped in `NaviDataDetail{time,pathCount,length,currentIndex,payload}` (`d\f\n.java:9-33`). The typed `NavigationHDLink2InfoStruct` (link arrays with formway/linktype/roadclass/pntCnt, `:63-69`, `:1265-1295`) exists in the proto but is not used by this code.

JSON envelope: `MessageSend{code,msg,mapProvider=1,RouteType,endPOIInfo,data}` (`c8\com\byd\noa\plan\data\sdroute\MessageSend.java`). Schema depends on `ADAS_FUNCTION_ALGORITHM_SUPPLIER` (`d\f\k.java:134-176`):

| supplier | data type | builder |
|---|---|---|
| 2 | `ArrayList<uke.SDRoute>` (all paths) | `l.i()` |
| 6 | `ArrayList<platformb.RouteInfo>` | `l.h()` all paths / `l.g()` focused path on SomeIpV2 |
| other (default) | `ArrayList<NaviToEHPData>` + `endPOIInfo` (AVP map ids for destination POI, `:99-110`) | `l.f()` / `l.g()` on SomeIpV2 |

Per-link content, all pulled from Amap `LinkInfo` (`d\f\l.java:111-141`):

```java
linkItem.linkID   = linkInfo.get64TopoID()  (or -1 if out of long range)
linkItem.roadclass/roadname/len/linktype/formway/hasTrafficLight/laneNum/hasMultiOut
linkItem.mainAction/assistantAction, adminCode = linkInfo.getAdcode(), ownership (1..4 -> 1)
linkItem.hasParallel, direction, speedLimit = 0, hasMixFork, pntBegIdx, pntCnt
```
plus per-path `pathID`, segments/guide groups, traffic lights, via roads, via points and the full point list (`RouteInfo`/`NaviToEHPData` fields in `com\byd\noa\plan\data\sdroute\...`). `RouteType` is a remap of Amap's route type (`d\f\k.java:49-97`; `14` when the send is a user-initiated path switch).

### 2.3 Acknowledgements from the ADAS

* **NavigationPathMatchStatus 0x2C / 0x8001** (`PathMatchProxy`, `c\j\j0\q.java`): `NavigationPathMatchStatusStruct{code, msg, RouteInfoArray[]}`; each `RouteInfo{pathID,totalNOADistance,routeBeginIdx,routeEndIdx,statusCode}` becomes `EHPtoNav` — this is the ADAS telling the map which point-index ranges of which pathId it can drive (city ODD).
  Note `RouteCityOddController.k(list)` — the callback that should consume it — is an empty body in this build (`d\d.java:130-132`), so the ADAS-reported ranges are only used through `b(...)`/`m.h()` when called with an empty list, i.e. the city ODD shown to the user is computed locally (§3).
* **0x2C / 0x8002** (`PathSelectedProxy`, `c\j\j0\t.java`): `NaviPathUserSelectStsConfirmStruct{pathId_resp,status_code,error_msg}`; `status_code==200` -> `DriveRouteManager.g(pathId)` ("MMTPathSelected"), else `w()` -> re-select.
* **0x2C / 0x8004** (`PathP2PMatchProxy`, `c\j\j0\s.java`): per-path AVP status + a `rePlanningReq` byte in `NavigationReservedArray1`.
* The map announces the user-chosen path with **NaviPathUserSelectSts 0x2D / 0x8001** `{pathID_req}` (`c\j\e0.java:123-126`, called from `MomentaUtil.i()` `d\f\k.java:216-228`).

### 2.4 Continuous data the ADAS also receives (SOME/IP server events from the map)

`SomeIPMatrixManager` (`j7\k\k\a0\c\c\a\f0.java`): `NavigationStatus_LinkInfoNotify{navigationStatus}` 0x07/0x8001 (`:71`), `TrafficInfo` 0x07/0x8003 (`:210`), `genernalNavigation{newNoODDRegionDist=-1,...}` 0x07/0x8006 (`:53`), and SdMapInform 0x8202 events: intersection/lanes, `regionalAndWeather{regionId,countryCode,provinceCode,cityCode,weatherType}` (`:107`), `sdVehicleLocation{lon,lat,matched lon/lat,GPS speed,curSDRouteID,curStepId,curLinkId,distanceToNextStep,linkOffset}` (`:235`), road facilities, tunnel, SAPA/toll, tidal lane, traffic lights. The `NavigationStatus_LinkInfoNotifyStruct` proto also has `DNP_SWITCH`, `MAPVERSION`, `LINKARRAY[LinK{linkId}]`, `HDSTARTDISTANCE`, `MATCHINGTABLESTATUS` fields (`j11\...\SomeipNavigationStatusLinkInfoService.java:2531-2544`) but only `navigationStatus` is set here; the link array may be filled elsewhere (not found in these dex files).

---

## 3. ODD gating

### 3.1 Where the ODD region list comes from

`RouteOddSegUtil.k(tag)` (`d\f\m.java:782-814`) calls `SendSomeIpDataManager.m()`:

```java
// c\j\e0.java:128-149  PilotStatusAlarmInfo 0x0D, method 2 (request/response)
SomeIpData req = new SomeIpData(563005788848130L /*0x0002_000D_000D_0002*/, now, ODDRegionCodeReq.newBuilder().build().toByteArray());
... ODDRegionCodeResp.parseFrom(resp).getODDRegionCodeRspInfoList().forEach(i -> list.add(i.getODDRegionCodeValue()));
```
The returned ints are stored in the static set `a` and persisted as `KEY_OPEN_CITY` JSON (`d\f\m.java:40-45`, `:643-659`, log `无法从缓存中获取城市列表` when the cache is empty). It is requested at route-result creation (`d\d.java:170`) and when service 0x0D becomes available (`b\d.java:80-83`).
**The codes are matched against Amap `LinkInfo.getURID()`** (`d\f\m.java:210-215`, `:155`, `c\f.java:57`), i.e. Amap's per-link city/region id — the ODD is a list of open cities in Amap's numbering, provided by the ADAS ECU, not by the map.

### 3.2 How a route's ODD segments are built (locally, from Amap data)

`RouteOddSegUtil.g(pathInfo)` (`d\f\m.java:233-365`) walks every segment/link and keeps runs of links where

```java
// d\f\m.java:796-802
l(prev, roadClass, formway) = roadClass==0 || roadClass==6 || m(formway) || ((prev==0||prev==6) && formway==6)
m(formway) = formway in {3,6,8,9,10}
```
(Amap roadClass 0 = motorway, 6 = urban expressway; formway 3/6/8/9/10 = ramps/JCT/separated carriageways). Each run becomes `BydOddInfos{cityURID, OddSegInfo{start/end seg+link idx, m_OddLen (cm)}, coords}`. `e()` (`:199-218`) then keeps only runs whose `cityURID` is in the ADAS set — unless the set is empty or >= 373 entries (treated as "everywhere"). `h()` (`:366-641`) turns it into `BydCNoaInfos{percent, bydOddInfos}`: `percent = ODD length / path length * 100`, clamped to 100 and logged as `cityNoa bydOddInfos.size() ==0 percent >= 100`. The log text is misleading: that branch is simply "no EHP ranges from ADAS, use local segments" (`:385-408`); with an empty ODD set and highway links the whole route becomes ODD = 100 %. The result is stored on the route (`setBydCNoaOdds`, `setMaxOddPathId`) and drives the route-bar ODD overlay (`j()` `:661`) and the route list percentage.

### 3.3 Distance to leave the ODD (fed back to ADAS)

`DnpOddDistanceController.e()` (`c\f.java:87-160`) runs every second while `DnpSwitch` and the car is stopped/slow (`g()` `:162-170`): it looks ahead along the current path for the next link whose URID changes, decides whether the next city is in the set, and sends `AdvAutoFunInfoNotify{noODDRegionDist}` on 0x07/0x8004 (`:173-177`): `0` = already outside, `Integer.MAX_VALUE` = no boundary within 2 km, else metres. If the set is empty it sends MAX (`:96-100`).

---

## 4. Memory NOA (记忆领航 / MNOA)

Design (from `PathRecordController`, `c8\k\k\d0\e\c1.java`):

* Gate: `c1.t()` = LiveData `e` = `b && c && d` (`s0()` `:670-679`), where `d` = ADAS "setting open" from `onDnpConfigState/onDnpSwitchState` (`e\a1.java`, `m0()` `:504-511`) and `f` = user "auto learn" preference.
* Learning start (`o0()` `:530-566`): on navigation start with a route, create `PathRecord{id = epochSeconds, startPOI, endPOI, pathName = destination}` and after 1 s call `e0.j(1, id)` -> "开始学习 learningState=1 pathId=…" which repeats `sendMemoryNoaStatus` every 100 ms for 3 minutes (`c\j\e0.java:33-58`). The map records the driven Amap path (`k.k.d0.e.f1.u`, native `PathInfo` bytes in `PathRecord.pathNativeData`).
* Stop/save (`p0()`/`j0()`): `MemoryNoaState` codes `CANCEL_LEARN 101, FINISH_LEARN 102, LEARN_FAILED 103, SAVE_START 201, SAVE_CANCEL 202, SAVE_FAILED 203, QUERY_STATE 204, USE_PATH 301, SDK_REROUTE 303, DELETE_PATH 401` (`com\byd\noa\drive\pilot\annotate\MemoryNoaState.java`) sent through `e0.i(state, pathId)` ("sendMemoryNoaPath2Momenta").
* ADAS reply model `MemoryNoaResponse{learningRouteIdResp, routeBuildingStatus (1 ok / 2 fail), routeSaveFailReason, learning start/end lon/lat/alt, recommendRouteIdResp, deleteRouteIdResp}`; fail reasons map to `PathSaveState.FAILED_MMT_1..11` (camera fault, too short, closed road, rural road, no nav, poor network, map vs self-built map deviation too large, >130 km/h 5 s, wipers max 5 s, unknown, system fault) (`com\byd\noa\record\constant\PathSaveState.java`).
* Using a route: `n0(id)` sends `USE_PATH` (`:518-525`); a route planned from ADAS recommendation is intercepted when `routeSource==2 && routeType==14` (`q()` `:635-641`); navigation exit sends `naviStatus` via `e0.g()` (`k0()` `:470-474`).
* Identity of a learned route: the **map-generated id** (`System.currentTimeMillis()/1000`) shared with the ADAS in the learning signal; the ADAS builds its own map ("自建图", "地图与自建图偏差过大") from sensors while the map only records the Amap path for display/replanning. A navigation route is mandatory to start learning (`o0()` needs `getCurrentFocusPath()`), and using a memorised route goes through a normal Amap route plan (`restore route` / `updatePath` `:216-238`).
* The proto side has `mNOARouteLearn{routeLearnArray, routeManageArray, recommendRouteIdArray}`, `naviMNOAInfoFFReq{routeLearningArray}`, `mNOAPilot{mNOAStatus, reasonMNOASpecState}` on service 0x0D (`j11\...\SomeipPilotStatusAlarmInfoService.java:5656-5667`, `:6673-6685`, `:9022-9034`).

**State of this build — effectively disabled/stubbed:**

* `c1.a` and `c1.b` are `false` and never assigned anywhere in `c8`/`c16` (`c1.java:42-45`, grep `c1.a =|c1.b =` empty), so `m0()` returns early (`:505`), `s0()` is never reached and `t()` is always false -> the debug-panel button shows **`当前不支持MNOA`** (`c16\k\h\f\f2\r\w.java:528-531`), and every public entry (`o0`, `j0`, `n0`, `q0`, `u0`) is a no-op.
* `onMemoryConfigState` -> `c1.o(z)` is an empty method (`c1.java:527-528`), so `ADAS_MNOA_SELF_LEARNING_SIGNAL` only affects the flag `a.p` used for SOME/IP subscription choice (`b\d.java:224-228`).
* All three MNOA payload builders return `new SomeIpData(0L, new byte[0])` (`c8\k\k\d0\c\h.java:8-18`), so `sendMemoryNoaPath2Momenta`/`sendMemoryNoaStatus` would emit topic 0 with an empty payload.
* No class outside the generated proto parses `mNOARouteLearnNotify`/`mNOAPilotNotify`; the only producers of `MemoryNoaResponse` are the debug floating panel (`e\e1.java:108-110`, `:147-149`, `:231`) and the retry-timeout helper (`e\g1\j.java:32`).

Conclusion: the memory-NOA protocol between map and ADAS is designed around a shared numeric path id plus start/stop/query/use/delete signals, but this firmware ships it compiled out. Whether the Tang L's memory NOA (if any) lives entirely in the ADAS domain cannot be decided from this app.

---

## 5. Region / market checks

Searched `c8\k\k\d0`, `c16\k\h\f\f2`, `c16\com\autosdk\drive\route` for `overseas|abroad|海外|国外|isChina|country|MCC|export`: **no NOA-specific country/MCC/GPS-bounds check exists**. What does gate by region:

1. The ADAS-supplied ODD city list (§3.1). Codes are Amap URIDs; a non-Amap route has no URIDs, so ODD segments cannot be computed (`m.g()` needs `LinkInfo.getURID/getRoadClass/getFormway`). With an empty list the map treats everything as in-ODD locally (`m.e()` `:203-205`) and sends `noODDRegionDist = MAX` (`c\f.java:96-100`) — the ADAS side presumably applies its own ODD.
2. Cloud control (§1.3): `NOA_CARD` / `NOA_ODD` come from BYD's authenticated config endpoint (`MapFuncConfigManagerTwo` `:88-147` token flow). Without connectivity the defaults are `true`.
3. `regionalAndWeather{countryCode, provinceCode, cityCode}` is sent to the ADAS every cycle (`f0.java:107`); source is Amap admin codes. The ADAS may use it — unknown.
4. Everything hinges on `BYDAutoFeatureIds.Adas.*_CONFIG` reads; on a car whose ADAS reports UNP/HNP not configured the map never subscribes.
5. Nothing checks map data version or "official export"; `MAPVERSION` exists in the 0x07 proto but is not set in the code found.

---

## 6. What the driving computer needs from the navigation app

Legend: **[A]** needs Amap map data / ids; **[S]** could be synthesised from another route source (e.g. Waze polyline); **[E]** comes from the ADAS itself.

### (a) Highway NOA (HNP)

| Input | Path | Class |
|---|---|---|
| Config/switch state (`ADAS_HNP_CONFIG`, `ADAS_HNP_SWITCH_STATE`, `ASSIST_DRIVE_MODE_STATUS`, sleep, downtime) | autoservice feature ids | **[E]** (map only reads) |
| Route JSON on 0x2B/0x8002: `pathID`, per-link `linkID = Amap 64-bit topo id`, `roadclass`, `formway`, `linktype`, `laneNum`, `adminCode`, `mainAction/assistantAction`, `pntBegIdx/pntCnt`, point list, segments, via points, traffic lights | `MomentaUtil.f` -> `SendSomeIpDataManager.k` | **[A]** for linkID/roadclass/formway/adminCode; geometry, length, actions **[S]** |
| `NaviPathUserSelectSts{pathID_req}` after the user picks a route | 0x2D/0x8001 | **[S]** (any id, must match JSON) |
| Per-second `sdVehicleLocation{curSDRouteID, curStepId, curLinkId, linkOffset, matched lat/lon}` | 0x8202/0x8008 | **[S]** if indices refer to the JSON just sent; matched position **[A]** today (Amap map-matching) |
| `NavigationStatus_LinkInfo{navigationStatus}`, `TrafficInfo`, `genernalNavigation`, intersections/lanes/facilities | 0x07, 0x8202 | mostly **[S]**; lane/facility content **[A]** |
| ODD list + `noODDRegionDist` | 0x0D method 2 -> `AdvAutoFunInfo` | list **[E]**; distance needs URIDs **[A]** (can be forced to MAX **[S]**, meaning "let ADAS decide") |
| Path-match ack `RouteInfoArray{pathID, routeBeginIdx/EndIdx, statusCode}` | 0x2C/0x8001 | **[E]** — the ADAS matches the *link ids* it received against its own map; unknown whether it tolerates `linkID = -1` (the code itself sends -1 when the topo id overflows, so the receiver must at least parse it) |

### (b) City NOA (UNP/CNOA/E2E)

Same channels as (a) plus: `hasCNoa` config (**[E]**), the ADAS-provided ODD city list (**[E]**) matched to Amap URIDs (**[A]**), traffic-light and lane info on 0x8202 (**[A]**, Amap lane model), and `endPOIInfo` AVP map ids for the destination POI (**[A]**, Amap POI id -> AVP map ids, only when AVP present). The `EHPtoNav` ranges coming back are the ADAS's own decision on the route it can drive (**[E]**); the map only displays a percentage.

### (c) Memory NOA

Designed inputs: learning start/stop/query/use/delete with a map-generated numeric id (**[S]**), the recorded Amap `PathInfo` for restore/replan (**[A]** for the map's own replay, not sent to ADAS), ADAS `mNOARouteLearn`/`recommendRouteId` replies (**[E]**). In this build the whole path is compiled out (§4), so nothing is required from the map today.

### Uncertainty and safety notes

* The ADAS-side consumer of the 0x2B JSON is not in this APK; how strictly it validates `linkID`/`adminCode`/`roadclass`, and whether HNP engages at all with `linkID = -1`, is unknown. The only hint is that the map itself emits `-1` for out-of-range ids.
* Faking `roadclass`/`formway` (motorway/ramp classification) or `laneNum`/`mainAction` for a route that is not from Amap would directly influence where the ADAS believes highway NOA is permitted — that is unsafe to synthesise; a foreign route should either leave these fields at "unknown" or not be sent as an SD route at all.
* `noODDRegionDist = MAX` means "no ODD boundary ahead"; sending it without a real ODD list moves the responsibility entirely to the ADAS's own ODD, which is the current behaviour when the city list is empty, but it is still an assumption.
* `sdVehicleLocation` matched coordinates come from Amap map-matching; substituting raw GPS changes the ADAS's lateral reference.
* The memory-NOA analysis is from dead code; behaviour on newer firmware may differ.
