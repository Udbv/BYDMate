# SOME/IP contract between the factory map and DiPilot/NOA (Tang L 2025, DiLink 150)

Read-only reconstruction from files under `D:\BYD\DiLink\` (vsomeip config, `libsomeipimpl*.so` strings,
decompiled `launchermap.apk` / `amapservice.apk`). No car or network access. Line numbers refer to those files.

Sources used
- `someip/someip_stack.json` — vsomeip config of the head unit (unicast 192.168.195.2, SD multicast 224.0.2.4:30490).
  `applications` block lines 20–250, `services` block from line 256, `clients` block from line 654.
- `lib/libsomeipimpl_proto.so` — protobuf descriptors of every `someip.*.proto`. Field tables below were parsed from the
  embedded `FileDescriptorProto` blobs (exact numbers/types), cross-checked against the generated Java classes in
  `apk/launchermap-jadx11/sources/someip/**` and `apk/amapservice-jadx/sources/someip/**`.
- `lib/impl-strings.txt` — CommonAPI names, e.g. `ServiceInterfaces.NavigationStatus_LinkInfoService:v1_0` (line 21735),
  `SDMapInformService` (21827), `NavigationSDLink2Service` (21986), `NavigationPathMatchStatusService` (21639),
  `NaviPathUserSelectStsService` (21703), `PilotStatus_AlarmInfoService` (22130).
- `apk/amapservice-jadx/sources/ts/car/someip/plugin/SomeIpTopic.java` — topic constants. Encoding verified:
  event topic = `0x4<<48 | service<<32 | instance<<16 | event`, method (request) = `0x2<<48|…`, response = `0x3<<48|…`,
  service-availability = `0x1<<48|…|0x0001` (available) / `0x0002` (not available), service key = `0xB<<48 | service<<32 | instance<<16`.
- Map app: `apk/launchermap-jadx7/sources/k/k/a0/c/c/a/f0.java` (SomeIPMatrixManager) and its caller
  `…/a0/c/c/a/e0.java`; NOA glue in `apk/launchermap-jadx-classes8/sources/k/k/d0/**` (NoaCanSomeIpLink `b/d.java`,
  NoaStateManager `b/f.java`, SendSomeIpDataManager `c/j/e0.java`, AutoPilotHub `c/j/j0/d.java`, proxies `c/j/j0/*.java`,
  RouteCityOddController `d/d.java`, MomentaUtil `d/f/k.java`, NaviToEHPUtil `d/f/l.java`, SDRouteUtil `d/f/n.java`);
  HUD sender `apk/launchermap-jadx17/sources/k/h/j/d.java`.

Transport facts common to all rows below: every payload is a protobuf-serialised `*Notify` wrapper message (one field
holding the struct); the Java side goes through the ThunderSoft gateway (`someip-gateway.apk`, exported
`com.ts.car.someip.SomeIpServerService` / `SomeIpClientService`, no permission attribute in
`apk/someip-gateway-jadx/resources/AndroidManifest.xml` lines 24–36) which forwards to `libsomeipimpl.so`/vsomeip.

## 1. Services in the navigation <-> ADAS exchange

"Offered" = listed in `services` (head unit is server, app name `*_server`); "consumed" = listed in `clients`
(head unit subscribes, app `*_client`). Ports are the head-unit-side ports from `someip_stack.json`.

| Service (hex) | Inst | Name (CommonAPI) | Dir. | Port / transport | Events / methods (event id -> proto message) |
|---|---|---|---|---|---|
| 0x0007 | 0x0007 | NavigationStatus_LinkInfoService | offered (app 0x7 `_server`) | UDP 51221, SOME/IP-TP on all events, eventgroup 0x1101 (json l.301) | 0x8001 `NavigationStatus_LinkInfoNotify`, 0x8002 `ADASISV2Notify`, 0x8003 `TrafficInfoNotify`, 0x8004 `AdvAutoFunInfoNotify`, 0x8005 `NavigationDataInfoNotifiy`, 0x8006 `genernalNavigationNotify` |
| 0x8202 | 0x8202 | SDMapInformService | offered (app 0x112 `_Server`) | UDP 52103, TP on all, eventgroups 0x1101 = 0x8001–0x800B, 0x1103 = 0x800C, 0x1104 = 0x800D–0x800E (l.546) | 0x8001 `sdTrafficLightNotify`, 0x8002 `sdTraffiIncidentNotify`, 0x8003 `serviceAreaAndTollStationNotify`, 0x8004 `tunnelNotify`, 0x8005 `roadFacilitiesNotify`, 0x8006 `intersectionNotify`, 0x8007 `tidalLaneNotify`, 0x8008 `sdVehicleLocationNotify`, 0x8009 `regionalAndWeatherNotify`, 0x800A `speedIntersectionInformationNotify`, 0x800B `naviActionAndCameraNotify`, 0x800C `nextIntersectionLanesInfoNotify`, 0x800D `mixForkInfoListNotify`, 0x800E `aheadIntersectionsLanesInfoNotify` |
| 0x002B | 0x002B | NavigationSDLink2Service | offered (app 0x2B `_server`) | TCP 51224 (reliable), TP, eventgroup 0x1101 (l.344) | 0x8001 `NavigationHDLink2Notify` (up to 3 candidate routes as link/point arrays), 0x8002 `naviSDRouteNotify` (JSON route, chunked) |
| 0x002D | 0x002D | NaviPathUserSelectStsService | offered (app 0x2D `_server`) | UDP 52105, TP, eventgroup 0x1101 (l.364) | 0x8001 `NaviPathUserSelectStsNotify` (pathID_Req) |
| 0x002C | 0x002C | NavigationPathMatchStatusService | consumed (app 0x2C `_client`) | UDP 51233 (l.735) | 0x8001 `NavigationPathMatchStatusNotify`, 0x8002 `NaviPathUserSelectStsConfirmNotify`, 0x8004 `navigationPathMatchP2PStatusNotify` (0x8003 presumably `parkingPaymentCodeNotify`, not subscribed by the map) |
| 0x000D | 0x000D | PilotStatus_AlarmInfoService | consumed (app 0xD `_client`) | UDP 51253, TP client->service for methods 0x1, 0x2 (l.695) | events 0x8001 `ChangeLaneDataNotify`, 0x8002 `PilotStatusNotify`, 0x8003 `PilotAlarmAndNoticeInfoNotify`, 0x8004 `BroadcastInfoNotify`, 0x8005 `NewBroadcastInfoNotify`, 0x8007 `drivingJourneyDataNotify`; methods 0x0001 `SwitchNotifyReq` (fire-and-forget), 0x0002 `ODDRegionCodeReq` -> `ODDRegionCodeResp`; also mNOA (memory NOA) messages `mNOAPilotNotify`, `mNOARouteLearnNotify`, `naviMNOAInfoFFReq` whose event ids are not in the decompiled code (builders are stubbed, see §4) |
| 0x000A | 0x000A | VehiclePositionInfoService | consumed (0xA) | UDP 51250 | 0x8001 `VehiclePositionInfoNotify` (fused pose + HD lane id) |
| 0x000B | 0x000B | RTK_IMUInfoService | consumed (0xB) | UDP 51251 | 0x8001 `RTKInfoNotify`, 0x8002 `IMUInfoNotify` |
| 0x000C | 0x000C | Obstacle_LaneLineInfoService | consumed (0xC) | UDP 51252 | 0x8001 `ObstacleInfoNotify`, 0x8002 `LaneLineDataNotify` (+ `NewLaneLineDataNotify`, id unknown) |
| 0x000E | 0x000E | PlanningLineInfoService | consumed (0xE) | UDP 51254 | 0x8001 `PlanningLineInfoNotify` (+ `NewPlanningLineInfoNotify`, `DrivingAreaIdentificationNotify`, ids unknown) |
| 0x0016 | 0x0016 | HeaderInfoService | consumed (0xF) | UDP 51262 | 0x8001 `HeaderInfoServiceNotify` (parking perception counters) |
| 0x0017 | 0x0017 | ParkingRealTimeDataService | consumed (0x17) | UDP 51263 | 0x8001 event + get/set/notify AVP target slot (parking) |
| 0x0018 | 0x0018 | HPAMapDataService | consumed (0x18) | UDP 51264 | 0x8001 `hpaMapDataNotify` (memory-parking map: track points, slots, ramps, pillars) |
| 0x0002 | 0x0C01 | INSService | consumed (0x2) | UDP 51228, multicast 224.0.2.4:51219 (l.662) | 0x8001 `INSNotify` |
| 0x0003 | 0x0C01 | PVTService | consumed (0x3) | UDP 51229, multicast 224.0.2.4:51219 (l.671) | 0x8001 `PVTNotify` (GNSS PVT + satellites) |
| 0x0030 | 0x0030 | PositioningDataService | consumed (0x10C) | UDP 51236 (l.795) | `GNSSStatusNotify{GNSSRtkStatus u32}`, `INSHeadingAngleNotify{INSHeadingAngle double}` |
| 0x0006 | 0x0006 | AnonymousIDService | offered (0x6) | UDP 51215, no events (l.287) | method 0x0001 `AnonymousIDNotifyReq{AnonymousIDReq u32}` -> `AnonymousIDNotifyResp{repeated u32 AnonymousIDRep}` |
| 0x0028 / 0x0029 | same | ConfigFiledownloadService (offered, TCP 51222) / ConfigFilecheckuploadService (consumed, TCP 51275) | — | — | config-file transfer with the ADAS domain; not navigation payload |
| 0x010A | 0x0001 | HudNaviInfoService | offered (0x101 `_server`) and also consumed (0x106) | UDP 52001 server / 52012 client (l.398, l.740) | 0x8001 `HudRoadInfo_EG`, 0x8002 `HudMappathInfo_EG`, 0x8003 `HudNavigationmap` — map -> HUD, not ADAS, listed because it is the Waze-HUD target |

Not listed but present: TestDemo (1), Music/MediaSync/Wifi/Telephony/BigData/Sentry/ADS_CameraStream/ADC_SystemParameter — no navigation role.

## 2. Message definitions (field number, name, type)

Types are from the descriptors (proto3, no `optional`). `u32/u64` = uint32/uint64. Reserve fields are kept where the map writes them.

### 0x0007 NavigationStatus_LinkInfoService (`someip.navigation.status_.link.info.service`)
- `NavigationStatus_LinkInfoNotify {1 NavigationStatus_LinkInfoNotifyStruct}`
- `NavigationStatus_LinkInfoNotifyStruct`: 1 Checksum u32, 2 Counter u32, 3 timestamp double, 4 NavigationStatus u32, 5 MatchingTableStatus u32, 6 RemainDistance u32, 7 ViaPointDistance u32, 8 HDStartDistance u32, 9 DNP_Switch u32, 10 ANP_road u32, 11 MapVersion u32, 12 LinKArray repeated `LinK{1 LinkID u64}`
- `ADASISV2Notify {1 ADASIS_V2 string}` (event 0x8002; no sender found in the decompiled map code)
- `TrafficInfoNotify {1 TrafficInfo}`; `TrafficInfo`: 1 TrafficJamDist u32, 2 DistToStartTrafficJam u32, 3 TrafficJamStatus u32, 4 PassTime u32, 5 CnstrctnRmnd u32, 6 CnstrctnCrdLatitude double, 7 CnstrctnCrdLongitude double, 8 SpdLmtSpeedValue u32, 9 SpdLmtEleEyeSpeedValue u32, 10 SpdLmtEleEyeDist u32, 11–14 IntervalCameraStart/EndPointLon/Lat double, 15 IntervalCameraSpeedValue u32, 16 OffCourse bool, 17 DistToDsttn u32, 18 TimeToDsttn u32, 19 RoadClass u32
- `AdvAutoFunInfoNotify {1 AdvAutoFunInfo{1 NoODDRegionDist u32}}`
- `NavigationDataInfoNotifiy {1 NavigationDataInfo}` (event 0x8005; no sender found): 1 Checksum, 2 Counter, 3 timestamp double, 4 NavigationalState, 5 TrafficLightExist, 6 TrafficLightDist, 7 CurrentRoadSpeed, 8 IntervalCameraLimitSpeed, 9 …Dist, 10 …RemainDist, 11 NaviCameraLimitSpeed, 12 …Dist, 13 CameraType, 14 CameraDist, 15 FacilityType, 16 FacilityDist, 17 RoadClass_RoadType, 18 RoadClassDist_RoadTypeDist, 19–26 RoadPoiType/Dist 1..4, 27 NaviCongestionInfo, 28 Distance, 29 RoadLaneTypeArray repeated `RoadLaneType{1 LaneType u32}`, 30 FormWay, 31 FormWayDist (all u32 unless noted)
- `genernalNavigationNotify {1 genernalNavigation}`; `genernalNavigation`: 1 intervalCameraSpeedDisttoStart u32, 2 intervalCameraSpeedDisttoEnd u32, 3 newNoODDRegionDist u32, 4 reserve1 u64, 5 reserve2 u32, 6–10 reserve3..7 double

### 0x8202 SDMapInformService (`someip.sd.map.inform.service`) — every `xNotify` wraps `{1 x}`
- `sdTrafficLight`: 1 trafficLightExist u32, 2 trafficLightLat double, 3 trafficLightLon double, 4 lightStateType u32, 5 startTime u64, 6 endTime u64, 7 lightDir u32, 8 lightWaitNum u32, 9 reserve1 u64, 10 trafficLightDist u32, 11 reserve3 float
- `sdTraffiIncidentNotify {1 repeated sdTraffiIncidentItem}`; item: 1 naviCongestionInfo u32, 2 occupiedLane u32, 3 cnstrctnCrdLatitude double, 4 cnstrctnCrdLongitude double, 5 naviCongestionDistLen u64, 6 occupiedLaneDtl u32, 7 reserve3 float
- `serviceAreaAndTollStation`: 1 hasSrvceStnRmnd u32, 2 sapaDist u32, 3 sapaName string, 4 sapaType u32, 5 nextSapDist u32, 6 hasTollStation u32, 7 tollGateLaneTypes bytes, 8 tollStationInfo u32, 9–11 reserve
- `tunnel`: 1 tunneStates u32, 2/3 tunnelStartPiointLat/Lon double, 4/5 tunnelEndPiointLat/Lon double, 6 toTunnelDist u32, 7–9 reserve
- `roadFacilities`: 1 naviFacilityType u32, 2 cruiseFacilityType u32, 3 distance u32, 4 boardSignLocationLon double, 5 boardSignLocationLat double, 6–8 reserve
- `intersection`: 1 distToNextGudc u32, 2 foreground bytes, 3 background bytes, 4 numOfLaneOfNextIntscn u32, 5–7 reserve
- `tidalLane`: 1 tidalLane u32, 2 rampRmnd u32, 3–5 reserve
- `sdVehicleLocation`: 1 locationLatitude double, 2 locationLongitude double, 3 locationLatitudeAssociateRoad double, 4 locationLongitudeAssociateRoad double, 5 vehicleSpeed u32, 6 gPSspeed u32, **7 curSDRouteID double**, 8 reserve4 u32, **9 curStepId u32, 10 curLinkId u32, 11 linkOffset u32, 12 distanceToNextStep u32, 13 currentRoadOwnership u32**, 14 reserve2 u32, 15 reserve3 float
- `regionalAndWeather`: 1 regionId u64, 2 countryCode u64, 3 provinceCode u64, 4 cityCode u64, 5 weatherType u32, 6–8 reserve
- `speedIntersectionInformation`: 1 trafficFlowSpeed u32, 2 historySpeed u32, 3 hasParallelRoad u32, 4 hasMixFork u32, 5 hasLongSolidLane u32, 6 assistantActionDistance u32, 7 naviVoiceCommand string, 8–10 reserve
- `naviActionAndCamera`: 1 iconType u32, 2 mainAction u32, 3 assistantAction u32, 4 distance u32, 5 cameraType u32, 6 cameraDistance u32, 7–9 reserve
- `nextIntersectionLanesInfo`: 1 backLane bytes, 2 frontLane bytes, 3 extendLane bytes, 4 recomendLane bytes, 5 backLaneType bytes, 6 frontLaneType bytes, 7 segmentIndex u32, 8 linkIndex u32, 9 timestamp double, 10 reserve1 bytes, 11/12 reserve2/3 repeated u32, 13 reserve4 repeated double, 14 reserve5 repeated float, 15 reserve6 repeated string
- `mixForkInfoListNotify {1 mixForkInfoLins string}` (JSON), `aheadIntersectionsLanesInfoNotify {1 aheadIntersectionsLanesInfo string}` (JSON)

### 0x002B NavigationSDLink2Service (`someip.navigation.sd.link2.service`)
- `naviSDRouteNotify {1 naviSDRouteStruct}`; `naviSDRouteStruct`: 1 checks_CRC32 u32, 2 Counter u32, 3 NavigationSDLink2Num u32, 4 NavigationSDLink2 string (JSON, see §3)
- `NavigationHDLink2Notify {1 NavigationHDLink2InfoStruct}`: 1 Checksum, 2 Counter, then three identical blocks k=1..3: NavigationPathValid_k u32, RoutePntCnt_k int32, RouteLinkCnt_k int32, RoutePathID_k u64, LinkItemArray_k repeated `LinkItem_k{1 Formway int32, 2 Linktype int32, 3 Roadclass int32, 4 BegIdx int32, 5 PntCnt int32, 6 Roadname string, 7 Len float}`, PointItemArray_k repeated `PointItem_k{1 X double, 2 Y double}`, reserve_k_1 u64, _2 u32, _3 float (field numbers 3–11, 12–20, 21–29). No sender found in the decompiled map code — appears to be the older binary route format superseded by the JSON route.

### 0x002D NaviPathUserSelectStsService
- `NaviPathUserSelectStsNotify {1 NaviPathUserSelectStsStruct}`: 1 checks_CRC32 u32, 2 Counter u32, 3 pathID_Req u64, 4 reserve1 u64, 5 reserve2 u32, 6 reserve3 float

### 0x002C NavigationPathMatchStatusService (`someip.navigation.path.match.status.service`)
- `NavigationPathMatchStatusNotify {1 NavigationPathMatchStatusStruct}`: 1 checks_CRC32 u32, 2 Counter u32, 3 code u64, 4 RouteInfoArray repeated `RouteInfo{1 pathID u64, 2 totalNOADistance u32, 3 routeBeginIdx u32, 4 routeEndIdx u32, 5 statusCode u32}`, 5 msg string, 6–8 reserve
- `NaviPathUserSelectStsConfirmNotify {1 NaviPathUserSelectStsConfirmStruct}`: 1 checks_CRC32, 2 Counter, 3 pathID_Resp u64, 4 status_code int32, 5 error_msg string, 6–8 reserve
- `navigationPathMatchP2PStatusNotify {1 navigationPathMatchP2PStatus}`: 1 navigationPathMathArray repeated `navigationPathMath{1 pathID u64, 2 p2PstatusCode u32, 3–7 reserve}`, 2 navigationReservedArray1 bytes (byte[0]==1 is read as "re-planning request"), 3–7 reserved arrays
- `parkingPaymentCodeNotify {1 parkingPaymentCode{1 paymentCodInformation string, …}}`

### 0x000D PilotStatus_AlarmInfoService (`someip.pilot.status_.alarm.info.service`)
- `PilotStatusNotifyStruct`: 1 Checksum, 2 Counter, 3 ACCStatus u32, 4 ICCStatus u32, 5 DNPStatus u32, 6 TakeoverStatus bool, 7 driving_time u32
- `PilotAlarmNoticeInfoNotifyStruct`: 3 PilotAlarmReason u32, 4 alarm_distance u32, 5 alarm_stage u32, 6 alarm_timestamp double, 7 PilotNotice u32, 8 notice_distance u32, 9 notice_timestamp double
- `ChangeLaneDataNotifyStruct`: 3 ChangeLaneState u32, 4 ChangeLaneDirection u32, 5 is_change_safety bool, 6 ChangeLane_timestamp u32, 7 change_ratio double, 8 change_termi u32, 9–14 landing box centre/size double
- `BroadcastInfoNotifyStruct`: 3 driver_attention, 4 large_vehicles, 5 dangerous_vehicle, 6 pedestrians (bool); `NewBroadcastInfoNotifyStruct`: 3 noaMode u32, 4 notice u32
- `drivingJourneyData`: 1 laneChangeFlag, 2 crossingFlag, 3 importExportFlag, 4 bypassFlag, 5 queueumpingFlag, 6 awaylargeVehicleFlag, 7 turnAroundFlag (u32)
- `SwitchNotifyReq {1 SwitchNotifyStruct{1 DNP_ICCSwithc_S u32}}`; `ODDRegionCodeReq {1 ODDRegionCodeReqInfo{1 ODDRegionCode u32}}` -> `ODDRegionCodeResp {1 repeated ODDRegionCodeInfo{1 ODDRegionCodeValue u32}}`
- Memory-NOA: `mNOAPilot{1 mNOAStatus u32, 2 reasonMNOASpecState u32}`, `mNOARouteLearn{1 repeated routeLearn, 2 repeated routeManage, 10 repeated recommendRouteId}`, `naviMNOAInfoFFReq{1 naviMNOAInfo{1 repeated routeLearning{1 learningRouteID string, 2 learningState, 3 learningExitReason, 4 routeSavingStatus}, 2 repeated manage{1 deleteRouteId string}, 3 repeated routeUse{1 selectRouteId string, 2–5 diffSegment lon/lat, 6 diffSegmentLength}, 11 repeated naviStatus{1 naviStatus u32}}}`

### ADAS-side sensor/state services (summary)
- 0x000A `VehiclePositionInfoNotifyStruct`: 1 Checksum, 2 Counter, 3 Longitude, 4 Latitude, 5 Altitude, 6 Heading, 7/8 HD_lane_left/right_angle, 9 VehicleSpeed, 10 Acceleration, 11–13 X/Y/Z_speed, 14 timestamp, 15 HD_link_id u32, 16 HD_lane_id u32, 17 HD_lane_type, 18 on_lane_offset, 19 HD_lane_seq, 20 HD_lane_num, 21/22 lateral offsets, 23 Roll, 24 Pitch, 25 HDStatus, 26 HDMap_version, 27 fusion_status, 28 pos_confidence, 29 position_type, 30 break_light, 31 indicator_light, 32 lights, 33 weather, 34 target_cruise_speed float, 35 target_lane_id_array, 36 target_lane_id_segment_array, 37 localization_output_offset (doubles unless noted; from `amapservice-jadx/sources/someip/vehicle/position/info/service/SomeipVehiclePositionInfoService.java`).
- 0x000B `RTKInfoNotifyStruct` (27 fields: rtk_status, utc/sys time, lon/lat/alt + accuracies, headings, speeds, DOPs, satellite counts, SNR), `IMUInfoNotifyStruct` (angular velocity xyz, acceleration xyz, imu_status, temperature, sys_time_us, is_calibrated).
- 0x0002 `INSNotifyStruct` (24 fields: utc, sat num, pitch/roll/heading + std, height, lat/lon, ground speed, stds, precious level, navstatus, imu calstatus, timestamp, timesync, product_SN string, sequence, crc32). 0x0003 `PVTNotifyStruct` (32 fields: lat/lon/alt, ENU velocity, track, speed, rtk status, sat num, HDOP, rtk age, GPS week/sec, utc, `repeated Sv{sv_type, sv_id, sv_elv, sv_az, sv_cno}`, stds, PDOP/VDOP, fix_type, alt_msl, gnss_status, timesync, timestamp_us, seq, crc32).
- 0x000C `ObstacleInfoNotifyStruct{…, repeated Object (obstacle_id, distance x/y/z in cm, heading, type, state, moving, direction, timestamps, speed, lights), targetFlag}`, `LaneLineDataNotifyStruct{Line, RoadMarking, TLA}`; 0x000E `PlanningLineInfoNotifyStruct{1 Checksum, 2 Counter, 3 planningLineStatus bool, 4 planning_timestamp double, 5 repeated PlanningLinePoints{id, x, y, z}}`; 0x0016 `HeaderInfoNotify` (22 u32 counters of parking-lot object classes); 0x0018 `hpaPMapData` (track points, build-map start/end, ramps, speed bumps, pillars, slots, target slot, `MapPathInfo{mapId, lon, lat}`).
- 0x010A HUD: `HudRoadInfoNotifyStruct` 33 fields (car_2_dest, time_of_car_2_dest, num_of_lanes, current_road_level, permissible_direction bytes, recommended_driving_directions_for_AJOTP bytes, distance_2_intersection, next_road_name, speed limits, navigating_status, camera, vehicle lon/lat/speed/altitude, danger_signs, POI/destination/ETA strings, recommendedDrivingDirectionsId, lanesPermissibleDirectionId, guideLine, guidePoint, vehicleHeading, navigatingRatio); `HudNavigationmap{1 navigation_map string (base64 PNG/JPEG)}`.

## 3. What the map app sends (map -> ADAS)

All sends in `f0.java` are suppressed while `this.b` is true (`t(boolean)` line 273–276, "setNoaNoSleepMode"), which
`NoaStateManager` sets from the ADAS sleep switch (`k/k/d0/b/f.java` lines 80–88, 178, 251): when the ADAS side
reports sleep-switch status 1, the map stops publishing everything below.

Service 0x0007 (topic base 0x0004_0007_0007_xxxx):
- 0x8001 `NavigationStatus_LinkInfoNotify` — `f0.e(int)` line 67–76 ("sendMapState"): only `NavigationStatus` is set; no Counter/Checksum/LinkArray. No caller of `f0.e` survived decompilation; the parallel Android broadcast in `NaviService.sendMapStateChangedBroadcast` (classes16 `com/autosdk/drive/navi/NaviService.java` line 1229) uses 8 = navigation started, 24 = cruise, 9 = stop, so the SOME/IP value set is probably the same "map state" enum (uncertain).
- 0x8003 `TrafficInfoNotify` — `f0.q` lines 206–215, called from `e0.H0` (jadx7 `e0.java` line 1096) on every position update: congestion length/distance/status/pass time, construction reminder + coordinates, road speed limit, camera speed/distance, interval camera start/end/speed, `OffCourse`, distance/time to destination, `RoadClass`.
- 0x8004 `AdvAutoFunInfoNotify.NoODDRegionDist` — `DnpOddDistanceController` (classes8 `k/k/d0/c/f.java` line 162–168): distance along the route until the next link whose `URID` (city code) is not in the ODD city set (`m.i()`, cached "KEY_OPEN_CITY", `k/k/d0/d/f/m.java` line 643), 0 = already outside, `Integer.MAX_VALUE` = >2 km / whole route inside; re-sent every 1 s while stationary (lines 23–35, 153–160).
- 0x8006 `genernalNavigationNotify` — `f0.c` lines 49–58: interval-camera distance to start / to end; `newNoODDRegionDist` is always `-1` (= 0xFFFFFFFF as u32).
- 0x8002 ADASISv2 and 0x8005 NavigationDataInfo: defined, never populated by this build.

Service 0x8202 (topic base 0x0004_8202_8202_xxxx), all from `f0.java` and driven by `e0.java` (jadx7):
- 0x8008 `sdVehicleLocation` — `f0.s` lines 228–271, called from `e0.H0` line 1095 with `(lon, lat, matchedLon, matchedLat, vehicleSpeed, gpsSpeed, course, pathId, curSegIdx, curLinkIdx, curSegDist, linkRemainDist, ownership)`. Mapping (from the log format string at line 260): `locationLongitude/Latitude` = raw position, `…AssociateRoad` = map-matched position, `vehicleSpeed` = CAN speed, `gPSspeed` = GNSS speed, `reserve4` = course/heading, **`curSDRouteID` = `PathInfo.getPathID()`**, **`curStepId` = `NaviInfo.curSegIdx`, `curLinkId` = `NaviInfo.curLinkIdx`**, `distanceToNextStep` = remaining distance in the current segment ("segOffset"), `linkOffset` = `linkRemainDist`, `currentRoadOwnership` = `LinkInfo.getOwnership()` (via `SomeIpAssist`). Not sent while segIdx/linkIdx are 0 (`e0.H0` lines 1087–1090 defer). Note the proto type of `curSDRouteID` is `double` although the app passes a `long` path id.
- 0x8001 `sdTrafficLight` — `f0.l` lines 148–159: exist flag, lon/lat, state type, start/end epoch seconds, direction, wait count (distance is logged but not put in the proto; `trafficLightDist` field 10 is unused). Sent only within 500 m of the light or when a countdown exists (`e0` line 1101).
- 0x8002 `sdTraffiIncident` — `f0.k` lines 131–146: per event lon/lat, `naviCongestionInfo` = event type, `occupiedLane` = lane id.
- 0x8003 `serviceAreaAndTollStation` — `f0.m` lines 161–170; 0x8004 `tunnel` — `f0.r` lines 217–226 (state, distance, start/end lon/lat); 0x8005 `roadFacilities` — `f0.j` lines 123–129 (distance, navi facility type, cruise type = 0, board-sign lon/lat); 0x8006 `intersection` — `f0.d` lines 60–65 (distance to next guidance point, lane count, background/foreground lane byte arrays); 0x8007 `tidalLane` — `f0.p` lines 195–204 (ramp distance, tidal-lane flag).
- 0x8009 `regionalAndWeather` — `f0.h` lines 103–112 (regionId, countryCode, provinceCode, cityCode, weather type).
- 0x800A `speedIntersectionInformation` — `f0.o` lines 184–193 (traffic-flow speed, historical speed, parallel-road / mix-fork / long-solid-lane flags, assistant-action distance, current voice prompt text).
- 0x800B `naviActionAndCamera` — `f0.f` lines 78–87 (icon type, main/assistant action, distance to manoeuvre, camera type/distance).
- 0x800C `nextIntersectionLanesInfo` — `f0.g` lines 89–101: back/front/extend/recommend lane byte arrays + lane-type arrays, **`segmentIndex`, `linkIndex`** of the intersection, timestamp.
- 0x800D `mixForkInfoList` and 0x800E `aheadIntersectionsLanesInfo` ("3KM_Lanes") — `f0.n` lines 172–182, `f0.i` lines 114–121: Gson JSON of `MixForkInfoParam` / `QueryAppointLanesInfoBean` lists (schema not in the decompiled subset).

Service 0x002B / 0x002D — the route itself (`k/k/d0/c/j/e0.java`):
- 0x002B/0x8002 `naviSDRouteNotify` — `e0.k(json, pathCount, index, pathId)` line 295–298 ("sendPath2Momenta"), invoked by `SDRouteUtil n.a` (`k/k/d0/d/f/n.java` lines 9–33). `NavigationSDLink2` = JSON `NaviDataDetail{time, pathCount, length (chunks), currentIndex, payload}`; payload is chunked at 524288 chars. Payload = JSON `MessageSend{msg:"response", code:200, data:[...], routeType, endPOIInfo, mapProvider}` built in `MomentaUtil k.g` (`k/k/d0/d/f/k.java` lines 186–232). The `data` element type depends on the ADAS type reported by BydAutoHub (`NoaStateManager.h()`, `b/f.java` line 90): type 2 -> `uke.SDRoute` (segments + `LinkInfoPlatform` with `URID`, `Adcode`, cameras, facilities, slopes, gantries), type 6 -> `platformb.RouteInfo`, otherwise `RouteInfo` or `NaviToEHPData` depending on `PlatformUtils.isSomeIpV2()` (implementation not in the decompiled subset).
  `platformb.RouteInfo` (`NaviToEHPUtil l.z` line 753, `l.e` 206, `l.p` 688, `l.c` 143): `pathID`, `segmentCount`, `SegmentsInfo[{segmentIndex, linkCount, length, mainAction, assistantAction, trafficLightNum, linkBegIdx}]`, `linkCount`, `linkInfos[{linkID = LinkInfo.get64TopoID() (−1 if out of int64 range), roadClass, roadName, length, linkType, formway, hasTrafficLight, laneNum, hasMultiOut, mainAction, assistantAction, adminCode = adcode, ownership (1..4 collapsed to 1), hasParallel, direction, speedLimit = 0, hasMixFork, roadDirection, isOverHead, isAtService, relatedSegmentIndex, pntBegIdx, linkIndex, pntCount}]`, `pointCount`, `points[{x = lon, y = lat}]` (degrees = Coord2DInt32/3600000), `viaRoadInfo`, `viaPointInfo`, `trafficLights`.
- 0x002D/0x8001 `NaviPathUserSelectStsNotify.pathID_Req` — `e0.l(pathId)` lines 300–303 ("sendPathSelected"): sent after the route JSON whenever the focused path changes (`DriveRouteManager g.G`, `k/k/d0/c/g.java` lines 72–92; `MomentaUtil k.i` `d/f/k.java` lines 239–250).
- 0x000D methods: `SwitchNotifyReq.DNP_ICCSwithc_S` = 1 or 2 (`e0.h`, line 265–268, user picked DNP vs ICC); `ODDRegionCodeReq` request/response (`e0.m` lines 305–326) returns the ADAS ODD region-code list, refreshed when the Pilot service becomes available (`b/d.java` lines 80–84, `m.k`).

## 4. What flows ADAS -> map and what the map does with it

Subscription is set up by `NoaCanSomeIpLink` (`k/k/d0/b/d.java`) only while the DNP switch is on; `AutoPilotHub`
(`k/k/d0/c/j/j0/d.java` lines 27–37, 97–116) maps topics to proxies and service keys to availability topics
(`0x0001_000D_000D_0001/0002`, `0x0001_002C_002C_0001/0002`; with the lane-SR UI also 0x000A, 0x000C, 0x000E).
Incoming payloads are dropped unless `noaSwitch && !downtime` (`b/d.java` lines 95–100).

| Topic | Proxy | Effect in the map |
|---|---|---|
| 0x002C/0x8001 `NavigationPathMatchStatusNotify` | `q` (PathMatchProxy) -> `RouteCityOddController.k` | Parses `RouteInfo[]` into `EHPtoNav{pathID, totalNOADistance, routeBeginIdx, routeEndIdx, statusCode}` (`j0/q.java`), but `d.k()` (`k/k/d0/d/d.java` line 116–118) is an empty body in this build; ODD coverage per path is computed locally from the city list (`d.b`, lines 61–100, called with an empty list from `d.m`). So the ADAS answer is logged only. |
| 0x002C/0x8002 `NaviPathUserSelectStsConfirmNotify` | `t` (PathSelectedProxy) | `status_code == 200` -> listener `g(pathID_Resp)` (path accepted), otherwise `w()` (rejected). |
| 0x002C/0x8004 `navigationPathMatchP2PStatusNotify` | `s` (PathP2PMatchProxy) | AVP/parking: `{pathID -> p2PstatusCode}` stored as AVP data; `navigationReservedArray1[0] == 1` triggers a re-planning request. |
| 0x000D/0x8002 `PilotStatusNotify` | `w` (PilotStatusProxy) | DNP/ICC/ACC status -> NOA state (`i0.b.a.g/h/i`), UI, tip-island; debounced (value must repeat once). |
| 0x000D/0x8003 `PilotAlarmAndNoticeInfoNotify` | `u` | Only while navigating: alarm reason/stage and notice/distance -> HMI prompts. |
| 0x000D/0x8001 `ChangeLaneDataNotify` | `f` | Lane-change state/direction/safety -> SR rendering. |
| 0x000D/0x8005 `NewBroadcastInfoNotify` | `o` | `noaMode` -> `i0.b.a.j`, UI mode. |
| 0x000D/0x8007 `drivingJourneyDataNotify` | `h` | Journey statistics flags (lane changes, crossings, bypasses…). |
| 0x000A/0x8001, 0x000C/0x8001, 0x000E/0x8001 | `a0`, `ObstacleInfoProxy`, `y` | Fused pose, obstacles and planning line for the on-map SR ("God's Eye") rendering (`k/k/d0/g/o.java`). |

Memory-NOA senders (`k/k/d0/c/h.java` lines 8–18) return empty `SomeIpData(0, [])` — the mNOA messages are compiled
in but disabled in this build.

## 5. Assessment for a third-party navigation source

What "a route exists" looks like on the wire, as far as this firmware shows (no ADAS-side code is available, so this is the
head-unit view only):

1. Service 0x0007 must be offered and 0x8001 `NavigationStatus` set to the "navigating" value (exact enum unknown; 8 is
   the map's internal "navi started" state). 0x8003 `TrafficInfo` (with `OffCourse=false`, `RoadClass`, distance/time to
   destination) and 0x8004/0x8006 ODD distances are sent continuously alongside.
2. Service 0x002B must be offered and 0x8002 `naviSDRouteNotify` must carry the JSON route (`NaviDataDetail` ->
   `MessageSend` -> route object) for the ADAS type of this car (type is read from BydAutoHub; the map picks `SDRoute`
   vs `RouteInfo` accordingly — for a Tang L with the Momenta-style "sendPath2Momenta" path the exact variant cannot be
   decided offline).
3. Service 0x002D/0x8001 `pathID_Req` naming the path, which the ADAS acknowledges on 0x002C/0x8002 with
   `status_code 200`. This ack is the only explicit "route accepted" signal the map listens to.
4. Service 0x8202/0x8008 `sdVehicleLocation` at position-update rate with `curSDRouteID` equal to the route's `pathID`
   and `curStepId`/`curLinkId` indexing into the sent `SegmentsInfo`/`linkInfos`, plus 0x800C lane info keyed by the
   same `segmentIndex`/`linkIndex`. Everything else on 0x8202 is enrichment.

Identity fields and whether they can be synthesised:
- `pathID` (0x002B JSON, 0x002D, 0x8202/0x8008, echoed back in 0x002C): an opaque 64-bit route handle generated by the
  AutoNavi engine; only self-consistency across messages is visible here. Synthesisable, unless the ADAS validates it
  against something else (not observable).
- `linkID` = `LinkInfo.get64TopoID()`, `adminCode`/`Adcode` = adcode, `URID` (uke format), `roadClass`, `formway`,
  `linkType`, `ownership`, `mainAction`/`assistantAction` enums: these are AutoNavi map-database identities and
  enumerations. `URID` is definitely consumed by the head unit itself for ODD-city checks; whether the driving computer
  matches `linkID`/adcode against its own map or only uses the polyline (`points`) for map-matching cannot be determined
  from these files. Treat `linkID` and `adminCode` as **not synthesisable** and the enums as **must follow AutoNavi
  value sets**.
- `curStepId`/`curLinkId`/`linkOffset`/`distanceToNextStep` are indices/offsets relative to the route the sender itself
  published, so they are synthesisable as long as they stay consistent with the JSON route.
- Coordinates are degrees (`Coord2DInt32/3600000`); the factory map runs on AutoNavi data, so GCJ-02 is the likely
  datum (not provable offline).
- `Checksum`/`Counter`/`checks_CRC32` fields exist in every struct but the map leaves them at 0 in all observed builders.

Uncertainties: the enum value sets (`NavigationStatus`, `RoadClass`, `mainAction`, `statusCode`), the ADAS-type -> JSON
variant mapping, `PlatformUtils.isSomeIpV2()`, and whether the driving computer requires 0x0007/0x8001 at all versus only
0x002B+0x002D+0x8202, are not recoverable without the ADAS-side binaries or a bus capture. Access control on the
gateway is not visible in its manifest; any app on the head unit appears able to offer these services, but the HAL
(`vendor.ts.someip`) may enforce its own checks.
