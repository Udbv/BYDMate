# NOA on a Chinese-market Tang L outside China: consolidated assessment

Question (2026-09-07): the car is a Chinese-market Tang L EV 2025 (DiLink 150, God's Eye B /
DiPilot 300 with LiDAR, Momenta stack). The built-in navigation (`com.byd.launchermap`, a
customised Amap build) has no coverage of Ukraine, so Navigate-on-Autopilot never becomes
available. Can the map be overridden or extended so NOA can be used here?

Three detailed reports back this note; read them for evidence and file:line references:

- [noa-map-app-analysis.md](noa-map-app-analysis.md): the map app's own NOA logic (decompiled).
- [noa-someip-contract.md](noa-someip-contract.md): the bus contract map <-> driving computer,
  message by message, from the proto descriptors in `libsomeipimpl_proto.so`.
- [noa-outside-china-research.md](noa-outside-china-research.md): public sources, owner reports,
  OTA timeline, export markets.

## 1. How NOA is wired on this car (facts from the firmware)

**Availability is decided by the driving computer, not the map.** The map only reads ADAS
feature ids over the autoservice: `ADAS_HNP_CONFIG` / `ADAS_UNP_CONFIG` / `ADAS_E2E_CONFIG`
(0 = equipped), `*_SWITCH_STATE`, `ADAS_ASSIST_DRIVE_MODE_STATUS` (3 highway NOA, 4 city NOA),
`ADAS_NOA_UI_TYPE`, sleep mode, an MNOA self-learning signal. Two cloud flags (`NOA_CARD`,
`NOA_ODD`, default true) only hide UI. There is **no country, MCC, SIM or GPS check** anywhere in
the NOA code of the map app; the "not from official export" dialog is unrelated.

**The route hand-off is a JSON of Amap link identities.** When a route is planned the map sends,
on NavigationSDLink2 (service 0x2B, event 0x8002, TCP, chunked), a JSON with `pathID` and, per
link, `linkID` (Amap 64-bit topology id), `roadclass`, `formway`, `linktype`, `laneNum`,
`adminCode`, actions, point indices and the polyline; then `pathID_req` on 0x2D. Every second it
streams the map-matched position `sdVehicleLocation {curSDRouteID, curStepId, curLinkId,
linkOffset, matched lat/lon}` on SDMapInform (0x8202/0x8008) plus lanes, cameras, traffic lights,
facilities, all from Amap data. The driving computer answers on NavigationPathMatchStatus (0x2C):
per-`pathID` matched ranges with `routeBeginIdx/EndIdx/statusCode`, and a confirm with
`status_code 200` as the only explicit "route accepted".

**City-NOA area lists are keyed on Amap ids too.** The driving computer supplies the list of
open cities (`ODDRegionCodeReq/Resp` on 0x0D); the map matches it against Amap `URID` admin codes
of the route's links and reports the distance to the ODD boundary (`noODDRegionDist`) back.

**Memory NOA (记忆领航) is designed but compiled out** in this map build: the learning state
machine, ids and failure reasons exist, but the senders return empty payloads and nothing parses
the responses ("当前不支持MNOA"). BYD promised it for God's Eye A/B by end-2025; no source
confirms it shipped on the Tang L.

**Public evidence agrees.** Highway and city NOA on God's Eye B need an active route in the
built-in customised navigation; "无图" means no HD map, the SD route and lane attributes still
come from the map. No export BYD ships NOA (Indonesia "2026 or 2027", Brazil "2027", Australia's
Sealion 8 = Tang L without LiDAR/NOA). The one first-hand grey-import report (Tang L in
Kazakhstan): ICC works, NOA does not, no navigation map. Amap's in-car international SDK
(2025-11, HERE data) is an OEM export product without a lane/ADAS layer, not installable here.

## 2. Options, assessed against the firmware

| option | verdict | why |
|---|---|---|
| Add Ukraine to the built-in Amap | not possible | Amap has no data outside China; DiLink offline packs are China-only; region is checked against the SD map. |
| Transplant an export-market map app | not realistic | needs root, matching signatures and `libsomeipimpl` plugin; export maps have no NOA integration and no Amap link ids. |
| Third-party route provider on the bus (an app that plays the map's role, like the HUD work) | technically reachable, **not advisable** | The gateway accepts any client and the JSON schema is known, so a route with self-made `pathID` and indices can be sent. But `linkID`, `adminCode`, `roadclass`/`formway`, `laneNum` and the matched position are Amap-derived; the driving computer's validation of them is not in any file we have. Faking motorway/ramp classification and lane counts for a road the ADAS has no map for directly influences where it believes NOA is permitted. That is feeding fabricated map data to a Level-2 driving system. |
| Memory NOA as a map-free path | not available | compiled out in this build; when it ships it still records the Amap `PathInfo`. |
| Live with what works | realistic | ACC/ICC, lane centering (LCC/ICA), lane change on demand, AEB, parking work without navigation per owner reports and per the code (none of them read the route). |

## 3. What can still be done safely, next time the car is on Wi-Fi

1. **Read the ADAS configuration** through the helper daemon (read-only feature ids): whether
   the driving computer reports HNP/UNP/E2E as equipped and switched, the NOA UI type, the
   algorithm supplier, and the ODD city list it holds. This tells us whether NOA is merely
   route-starved or also configuration-blocked. Needs the numeric ids from a FID dump
   (Settings -> Service & data -> FID dump, or the daemon's `TX_DUMP_FIDS`).
2. **Pull `/system/framework`** (the BYD auto SDK jars) for the byte-array feature write (road
   name on the HUD) and for the exact `BYDAutoFeatureIds.Adas` values.
3. **Sniff, never send**: bind the gateway's client interface (openbyd's `SomeIpSniffer` shows
   how) and record 0x0D/0x2C traffic while the ADAS is awake, to learn the enum values the
   driving computer uses. Recording is passive.

## 4. Recommendation

NOA on this car outside China is blocked by the map, not by a region lock, and the map's
contract with the driving computer is built on Amap's link identities. A synthetic route provider
is buildable but would put fabricated road classification and lane data in front of the driving
computer, so I recommend not building it. The safe reads above are worth doing once to close the
question with data from the ADAS itself; if BYD ships memory NOA or an export NOA stack for
DiLink 150, that becomes the moment to revisit.
