# Can foreign (Ukrainian) Amap map data be loaded into the Tang L car's map engine?

Investigation for a BYD Tang L (DiLink 150, China-market, imported to Ukraine).
Read-only analysis of the factory map app `com.byd.launchermap` ("Launcher150Pro") plus
targeted web research. Firmware claims are backed by file:line / string evidence; web
claims are cited with URLs and separated into fact vs inference.

**Bottom line up front:** The owner's premise is based on a real thing, but conflates two
different products. The car does **not** run the same map product as the mobile app, and the
July-2025 mobile launch was **not** a worldwide-data rollout (it was a *language* launch for
the China map). The car engine (Amap "GBL/BL" + "Dice") is a China build that keys everything
on Chinese administrative codes (adcode) and obfuscates coordinates to GCJ-02. There is no
plausible path to hand-load Ukrainian map data: downloaded packs are MD5/signature/version
verified, and — more fundamentally — no compatible Ukrainian data product for this engine
exists that the owner could obtain. The realistic overseas path is Amap's **AutoSDK
International** (a *different* build, HERE-sourced), which is a factory/OEM provisioning matter,
not a user data-load.

---

## 1. What the car engine actually is, and what data it eats

**Engine.** The app is built on Amap's automotive engine "GBL / BL" (native libs
`libGbl.so` 62 MB, `libAutoDice.so` 62 MB, plus `libGEhp.so`, `libGNet.so`,
`libGComm3rd.so`, `libGPlatformInterface.so`). Build metadata
(`assets/GblBranchCommitInfo.txt`):

- `BLVersion: 9.810.33727.0.2268`, all components on branch `release/810`
  (`GBL21`, `GBLData`, `GBLActivationComponent`, `GBLAosComponent`, `GBLDataComponent`, …).
- `libGbl.so` internal string: `BLVersion::Init() AutoEngine_13.16.40.4`.

**Java SDK surface** (decompiled `classes13.dex` → `com.autonavi.gbl.*`): the standard Amap
AutoSDK. Map data is managed by `com.autonavi.gbl.data.MapDataService`
(`launchermap-jadx-classes13/sources/com/autonavi/gbl/data/MapDataService.java`).

**What data it eats — the file types** (`.../data/model/MapDataFileType.java`):
`FILE_MAP=0, POI=1, ROUTE=2, 3D=3, JV=4, JVLINK=5, VM=6, ROUTE_LANE=7, ROUTE_ADAS=8,
MAP_INDOOR=9, ROUTE_INDOOR=10, BUILDING_INDOOR=11`. Offline pack types
(`OfflineMapDataType.java`): `MAP=1, MAP_ROUTE=2, MAP_ROUTE_EHP=5, MAP_INDOOR=11`.
Data modes (`MapDataMode.java`): `BASE=0, EHP_ADAS=1, EHP_ADAS_LANE=2, EHP_ADAS_LANE_PARKING=3`
— i.e. plain map, plus optional lane-level / ADAS "electronic horizon" (EHP) tiers.

**Native data format is Amap-proprietary, NDS-derived.** `libAutoDice.so` exports patch/record
symbols `real3dTileDataPatch`, `bmdPatch` (BMD = base map data), and crucially
`adasPatch(__nds_adas_data__ …)`, plus type tags `__nds_adas_data__`, `__nds_route_data__`,
`nds_link_ids`. The ADAS/route tier uses NDS (Navigation Data Standard)-style structures under
the hood, but wrapped in Amap's own `Dice`/`BL` containers and encryption — not a portable,
publicly documented interchange format. HD vs SD tiers exist
(`dice::DiceEngineImpl::s_hdDataProvider` / `s_sdDataProvider`).

**Primary key is the Chinese administrative code (adcode).** The entire offline/POI/routing
model is adcode-based (`MapDataService.getAdcode(int)`, `getAdcodeByLonLat(double,double)`,
`getAdcodeList(...)`, `getArea(...)`, `getCityInfo(...)`, `searchAdcode(String)`;
model `Area.java` carries `adcode`, `upperAdcode`, `vecNearAdcodeList`; `CityItemInfo.java`,
`AdminCode.java`). Offline packs are downloaded/operated **by adcode**
(`com/byd/automap/data/presenter/MapManagePresenter.java` iterates `getAdcodeList(0, …)` and
`operate(mode, op, adcodeList)`). adcode is the Chinese GB/T 2260 administrative-division code
system — there is no adcode for Ukrainian oblasts.

**Coordinates are GCJ-02 ("Mars").** `libAutoDice.so` contains the obfuscation transform
`WGS84ToMGS` with parameters `m_EncryLatA/LatB/LonA/LonB/GD` (MGS = Mars Geodetic System =
GCJ-02, the PRC-mandated offset applied on Chinese territory). So the engine ingests/serves
coordinates in GCJ-02, matching China-only data.

**Region model is CN-centric.** `RegionCode.java` enumerates only
`CHN=156, HKG=344, MAC=446, TWN=158` (+ NULL/MAX); `AdminCode.java` defaults
`euRegionCode = 156` (China). There is no Ukraine / general-country region.

**Servers and account gating are all China (`-cn`).** App strings (classes5/15/19):
`https://vehicle-map-cn.byd.auto` (+ `/oauth/enc/token`, `/voice/map/active/enc/order`),
`vehicle-map-cn.denzacloud.com`, `vehicle-map-cn.yangwangcloud.com`,
`vehicle-map-cn.fangchengbaocloud.com`, `launchermap-cn.denzacloud.com`,
`map-cn.yangwangcloud.com`, `mps.amap.com`, `cache.amap.com`. Map service is gated behind a
BYD-CN account OAuth token and an activation/authentication flow
(`com.autonavi.gbl.activation.AuthenticationService`, `AuthenticationStatus` =
NotOpen/Expired/InUse, `libGbl.so`: `authentication.db`, `license.db`, `activation`).

**This is a China-only build.** The only "overseas" references are BYD *vehicle-config* flags,
not an alternate data path inside this app: `VehicleConfig.VEHICLE_CONFIG_ITEM_OVERSEA_MAP =
"0x11030707"` (`com/byd/car/property/config/VehicleConfig.java`), `PlatformInfo.OVERSEA_MASK`,
and CAN/HAL signals `INSTRUMENT_OVERSEA_MAP_SUPPLIER_FLAG`, `SET_LANGUAGE_TYPE_OVERSEA`
(strings in `classes.dex`). These tell the platform whether the *car* is an overseas variant
that uses a *different map supplier* — evidence that BYD swaps the map stack at the factory for
export cars, not that this China app can load foreign data.

## 2. Offline-pack mechanics and integrity checks

**Two ingest channels.** `DownLoadMode`: `NET=0` (download from Amap/BYD-CN servers) and
`USB=1` (import from a USB stick). The app implements both:
`com/byd/automap/data/presenter/DataMapUSBPresenter.java` scans a `usbPath`, calls
`MapDataController.checkDataInDisk(1, usbPath)` and `requestDataListCheck(1, usbPath, …)` to
enumerate importable city packs; `DataToolMapData.cityDataShift(src, dst)` moves city data.
So there *is* a user-facing "import map data from USB" channel — this is what the owner would
have to exploit.

**But every pack is verified.** Verification happens in the native engine, not in patchable
Java:

- Init config carries a signature/key contract: `InitConfig` fields `strSignatureFlag`,
  `strKeyVersion`, `strStoredPath`, `strDownloadPath`, `strConfigfilePath`
  (`.../data/model/InitConfig.java`).
- `libGbl.so` performs MD5 + signature checks on data files:
  `bl::DataTaskMgr::compareFileMd5`, `IsMd5CheckPass` / `IsMd5CheckPass_error`,
  `GetFileMd5`, `GetMd5SignValue`, `fileUrlMd5`, and SQLite `PRAGMA integrity_check`
  (`BlSQLiteDB::CheckIntegrity`). The USB path shares the same validator
  (`bl::DataUsbBase::CheckDataInDisk`).
- Error model explicitly rejects mismatches: `DataErrorType` =
  `NOT_MATCHED / OPEN_FAILED / PARSE_FAILED / VERSION_NOT_MATCHED`.
- Engine/data version compatibility is enforced: `MapDataService.getDataFileVersion(...)`,
  `syncDataVersion(int, ArrayList<MapDataVersion>)`, `getEngineVersion()`, and native
  `DataVersionManager`, `dice::get_offline_version`. Data built for a different BL data
  version is refused.

**Storage location.** Data lives under the app's private external files dir
(`getExternalFilesDir` → `/Android/data/com.byd.launchermap/…`), populated only through the
verified NET/USB pipelines above; it is not a plain drop-in folder the engine will scan
unconditionally.

**Consequence.** Hand-made or foreign data would need to (a) be packaged in Amap's proprietary
BL/Dice container for BL data-version 9.810, (b) carry a valid MD5 and a valid Amap signature
that the engine accepts, and (c) present adcode/region identity the engine recognizes. Items
(a)–(c) are gatekept by Amap's server-side data build + signing, which no third party has.

## 3. Is the mobile app's worldwide data the same product/format? — No.

**Verdict: different product, different data source, not loadable into this engine.**

Reasoning and evidence (web research, cited):

- **The July-2025 mobile launch was a language launch for the *China* map, not worldwide
  data.** On 2025-07-09 Amap added 14 languages on top of Chinese/English, explicitly aimed at
  *foreign tourists inside China*
  ([newsfilecorp](https://www.newsfilecorp.com/release/258537/Alibabas-Amap-Launches-Chinas-First-Multilingual-Map-with-14-New-Languages-for-Overseas-Users),
  [IT之家](https://www.ithome.com/0/872/858.htm)). Actual overseas consumer map coverage
  ("世界地图") had shipped earlier, in Sept 2023
  ([Tencent News](https://news.qq.com/rain/a/20230903A0535800)). *So the specific July-2025
  event the owner cites is not about Ukraine data at all.* (Fact.)
- **Overseas map data is licensed from HERE Technologies, not Amap's own survey.** HERE's press
  release (2020-08-01) states HERE provides map + traffic data for Amap **outside China**,
  covering "AMAP's application and SDK"
  ([here.com](https://www.here.com/about/press-releases/2020-08-01)); reaffirmed by a
  2025-11-03 HERE–Amap strategic alliance
  ([here.com](https://www.here.com/about/press-releases/here-technologies-and-amap-form-strategic-alliance-to-deliver-next-gen-ai)).
  (Fact.)
- **Abroad, coordinates are WGS-84, not GCJ-02** — Amap's own SDK docs say `getCoordType()`
  returns WGS-84 when locating abroad
  ([AMapLocation ref](https://amappc.cn-hangzhou.oss-pub.aliyun-inc.com/lbs/static/unzip/Android_Location_Doc/com/amap/api/location/AMapLocation.html)).
  This car engine hard-applies GCJ-02 (`WGS84ToMGS`), so even the coordinate convention
  differs. (Fact + firmware.)
- **The consumer app overseas is effectively online-only**; no credible evidence of
  downloadable foreign-country offline packs, and no naming/size/format published. Bellingcat's
  toolkit notes out-of-China data is not available in the English mobile app
  ([bellingcat](https://bellingcat.gitbook.io/toolkit/more/all-tools/gaode-maps)). (Inference,
  moderate confidence — no definitive official statement found.)
- **PRC surveying law (测绘法) is why the pieces are walled off.** Qualified operators cannot
  link to foreign map services and must serve from PRC-located servers; foreign map import is
  separately regulated
  ([中国互联网协会](https://www.isc.org.cn/article/18272.html)). This is consistent with
  overseas data being served online from Amap-controlled infra rather than as freely
  redistributable packs. (Fact + inference.)

Even setting format aside: the mobile app never *hands the user a file*. There is no artifact
to move from phone to car.

## 4. What would be required for foreign data to work in the car — impossible vs merely unknown

For Ukrainian navigation to work through **this** app/engine, all of the following would be
needed. Marked **[Impossible]** (no realistic path) or **[Unknown]** (not proven either way):

1. Ukrainian road/POI/routing data compiled into Amap's proprietary **BL/Dice container for
   data-version 9.810**. **[Impossible for a user]** — only Amap's data factory produces this;
   no public tooling or spec.
2. A valid **MD5 + Amap signature** on every pack so the engine's `IsMd5CheckPass` /
   `GetMd5SignValue` accept it. **[Impossible for a user]** — signing key is Amap's.
3. **adcode identity** for Ukrainian regions the engine recognizes, and a region entry beyond
   `CHN/HKG/MAC/TWN`. **[Impossible without engine/data changes]** — adcode is a China-only
   codespace baked into the data model.
4. **Coordinate convention** reconciled — data is WGS-84 abroad but the engine expects GCJ-02.
   **[Impossible without engine changes]** — the offset transform is compiled into
   `libAutoDice.so`.
5. Whether the engine even has a code path that *renders/routes* on non-China tiles at all
   (the `overseas.db` / `global.db` / `ackor::GlobalOfflineType` symbols in `libAutoDice.so`
   hint at a world/base tier). **[Unknown]** — these may be only a low-detail world basemap
   backdrop, not a navigable overseas dataset; could not confirm from strings alone.
6. Server/account acceptance: map service is gated by BYD-CN OAuth + activation. **[Unknown]**
   whether offline-only use survives without a valid CN account/activation over time.

**Net:** items 1–4 are hard blockers with no user-accessible workaround. The USB import channel
(§2) is real but useless without a signed, version-matched, adcode-keyed CN-format pack — which
for Ukraine does not exist.

**The only realistic overseas route** is Amap **AutoSDK International** (announced 2025-11-03):
170+ countries, 19 languages, positioning + search + routing + **offline** navigation + ISA,
HERE-sourced, targeting Chinese OEMs going global; 40+ OEMs / 100+ brands
([IT之家](https://www.ithome.com/0/894/513.htm),
[Sina](https://finance.sina.com.cn/tech/roll/2025-11-03/doc-infwckpr0973844.shtml)). That is a
*different firmware build* provisioned by the OEM at the factory (cf. the
`OVERSEA_MAP_SUPPLIER_FLAG` vehicle-config in §1), not something a user loads onto a
China-spec Tang L. Whether it shares this exact BL/Dice runtime, and whether BYD would ever
flash it to an imported CN car, is unknown.

## 5. Explicit uncertainty list

- **Ukraine coverage** in Amap's overseas data is not individually confirmed; "200+ countries"
  is a headline figure, and HERE's *full-detail* coverage is far narrower. Not verified.
- Whether the consumer app abroad is strictly online-only — strong indication, no explicit
  official statement.
- What `overseas.db` / `global.db` / `global_s.db` / `global_e.db` and
  `ackor::GlobalOfflineType` actually contain in **this** car build (navigable overseas data
  vs a decorative world basemap). Not determined.
- Whether AutoSDK International reuses this same `libGbl`/`libAutoDice` runtime or a distinct
  engine — no public architecture doc.
- Exact on-disk pack layout / file extensions of a downloaded CN offline city pack (engine uses
  `.db` SQLite + BL/Dice blobs; specific pack filenames not enumerated here).
- Whether the CN account/activation gate blocks purely-offline map use indefinitely.
- (Pending) any hobbyist reports of loading custom data into an Amap car head unit, and what
  built-in nav BYD ships in export cars — being researched; not yet incorporated.

---

### Evidence index (firmware)

- Engine/version: `D:\BYD\DiLink\apk` → `assets/GblBranchCommitInfo.txt`;
  `lib/arm64-v8a/libGbl.so`, `libAutoDice.so`.
- Data SDK: `launchermap-jadx-classes13/sources/com/autonavi/gbl/data/**`
  (`MapDataService.java`, `DataToolMapData.java`, `model/*.java`).
- App layer: `launchermap-jadx-classes5/sources/com/byd/automap/data/presenter/`
  (`MapManagePresenter.java`, `DataMapUSBPresenter.java`),
  `com/byd/car/property/config/VehicleConfig.java`, `com/byd/car/cabin/PlatformInfo.java`.
- URLs/gating: `launchermap-dex/classes5.dex`, `classes15.dex`, `classes19.dex`.
