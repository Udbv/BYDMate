# NOA on a Chinese-market Tang L outside China: can the built-in map be replaced or extended?

Question from the user (2026-09-07): the car is a Chinese-market Tang L EV 2025 (DiLink 150,
DiPilot / "God's Eye" with NOA). The built-in map (`com.byd.launchermap`, an Amap/Gaode based
"Launcher150Pro" build) has no coverage of Ukraine, so Navigate-on-Autopilot never has a route.
Can the map be overridden or extended so NOA can be used here?

Everything below comes from the firmware pulled from the car (`D:\BYD\DiLink`) and openbyd's
sources; nothing was tried on the road.

## How the map feeds the driving-assistance side

The map app does not just draw a route. Its `SomeIPMatrixManager` (dex `classes7`, class
`k.k.a0.c.c.a.f0`) streams route context on the SOME/IP bus to the ADAS / scene-rendering side
(`com.byd.sr`, GritPlayer, log tag `DiHmiLaneBeh OnSomeipNewLaneLineDataNotify`) and, through the
vehicle Ethernet, to the driving computer:

| service | messages the map sends (proto in `libsomeipimpl_proto.so`) |
|---|---|
| NavigationStatus_LinkInfo (7) | `NavigationStatus_LinkInfoNotify` (state 100/101), `TrafficInfoNotify` (congestion, construction, road speed, camera speed), `genernalNavigationNotify` (ODD region start/end) |
| SdMapInform (0x8202) | `sdVehicleLocationNotify` (lon/lat, speed, course, **pathId, curSegIdx, curLinkIdx, segOffset, linkOffset**, road class), `naviActionAndCameraNotify` (icon, distance, main/assistant action, camera), `nextIntersectionLanesInfoNotify`, `aheadIntersectionsLanesInfoNotify` (3 km of lanes as JSON), `mixForkInfoListNotify`, `roadFacilitiesNotify`, `sdTrafficLightNotify`, `serviceAreaAndTollStationNotify`, `tidalLaneNotify`, `tunnelNotify`, `intersectionNotify`, `regionalAndWeatherNotify`, `speedIntersectionInformationNotify` |
| NavigationSDLink2 (0x2B), NavigationPathMatchStatus (0x2C), NaviPathUserSelectSts (0x2D) | SD-map link data of the matched path, path-match status, the user's path choice |
| HPAMapData (0x18), PlanningLine (0xE), Obstacle_LaneLine (0xC), PilotStatus_AlarmInfo (0xD) | parking / planning / perception exchange with the driving computer |

The messages carry Amap link identities (pathId, segment and link indexes, road class, lane
topology). The driving computer matches its own perception and its map data against them. That is
the "NOA needs the navigation route" dependency: without a route in *this* map, there is no path,
no link ids and no lane preview, and the NOA entry condition is never met.

## Options, honestly assessed

1. **Map data for Ukraine inside the Amap engine.** Not available. Amap's offline packs cover
   mainland China (plus HK/Macau/Taiwan); the engine has no world coverage, and the app checks the
   region against the SD map. No legitimate way to add a country.

2. **Replace the map app with an export-market BYD map.** BYD's export DiLink builds ship a
   different navigation stack (HERE-based "BYD Map" in Europe on DiLink 4/5), but those firmwares
   are for different hardware/software baselines and their NOA integration is not the same
   `SomeIPMatrixManager`; export markets do not get NOA at all. Sideloading a system app from
   another firmware onto DiLink 150 would require root, signature matching and the matching
   `libsomeipimpl` plugin; not realistic, and it would still not provide link ids the driving
   computer's map data knows.

3. **A third-party app that plays the map's role on the bus** (what openbyd's
   `LauncherMapCnStrategy` does for the HUD/cluster, in miniature): build the route from
   OpenStreetMap or Waze, then stream `NavigationStatus`, `sdVehicleLocation`,
   `naviActionAndCamera`, lanes and the SD link messages. Technically the protos are known and the
   gateway accepts any client (the HUD work proves it). But the driving computer expects link
   identities that exist in its own map database and validates path matching
   (`NavigationPathMatchStatusService`); synthetic ids from OSM cannot match anything. At best
   the car would behave as "navigating without map data" (what it does already), at worst it would
   receive lane topology and speed limits that do not correspond to the road. Feeding a
   driver-assistance system fabricated map data is a safety problem, and I recommend not building
   it. Map-less city NOA on God's Eye B still uses the SD map for route intent; "NOA without a
   map" in BYD's marketing means no HD map, not no map.

4. **What is realistic on this car:** everything downstream of "the map has a route" that does
   *not* require the driving computer to accept the route: HUD/cluster guidance (this project),
   speed-limit and camera prompts from Waze on the glass, and possibly the AR overlay's lane
   rendering if it is driven by `naviActionAndCamera` alone (test plan in
   `tang-l-hud-someip.md`). NOA itself will stay unavailable outside Amap's coverage.

## If you still want to dig

- Decompile `com.byd.sr` (`/system/app/BydSR/BydSR.apk`) and the DiPilot-side consumer of
  `NavigationPathMatchStatusService` to see what the driving computer checks before it enables
  NOA (look for `pathId`, `matchStatus`, `oddRegion`).
- Sniff the bus with openbyd's `SomeIpSniffer` (client interface `com.ts.car.someip.SomeIpClientService`)
  while the factory map navigates in China-like conditions (impossible here) or replay a recorded
  Chinese session to learn the exact entry conditions.
- Track BYD's export firmware: if a DiLink 150 export build with NOA and HERE data ever ships,
  that is the only supported path.
