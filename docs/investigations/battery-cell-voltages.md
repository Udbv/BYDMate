# Per-cell battery voltages on a Tang L (DiLink 150) — reachability investigation

Date: 2026-09-07. Car offline; no live reads. Read-only analysis of local artefacts plus web
research. Target: individual voltage of every cell in the Blade LFP pack, plus per-cell/per-module
temperature, balancing state, and min/max cell identifiers.

**Headline: no evidence that per-cell data exists anywhere on the head unit.** BYD's own
feature-id catalogue — 11 483 constants shipped inside `amapservice.apk` on this car — contains
exactly two cell-voltage entries (highest and lowest) and no array, no index, no balancing field.
What *is* newly reachable is a set of adjacent BMS aggregates and GB/T-32960 metadata that BYDMate
does not read yet.

---

## 1. What BYDMate already reads, and how

BYDMate never touches the OBD port. It calls the Android system service `autoservice` over a
Binder transaction with a `(device, feature-id)` integer pair:

```
service call autoservice <tx> i32 <dev> i32 <fid>      # tx 5 = int, 7 = float, 9 = buffer
```

The write barrier in `app/src/main/kotlin/com/bydmate/app/data/autoservice/AdbOnDeviceClient.kt:247`
permits only `[579]`, and `HelperDaemon.kt:854` documents the codes as `5=getInt, 6=setInt,
7=getDouble raw int, 9=getBuffer`. Reads run either through on-device ADB or through the helper
daemon (`TX_READ` / `TX_READ_BATCH`, `helper/HelperBinderProtocol.kt`). Sentinel returns
(`0xFFFF`, `0xFFFFF`, `-10013`, `-10011`, `-1.0f`) are collapsed to null by `SentinelDecoder.kt`.

Battery-related entries wired today, from `data/nativestack/FidMap.kt`. I resolved every one
against BYD's own name catalogue (see §2) — the hex column is the same fid in BYD's notation:

| BYDMate field | dev | fid (dec) | fid (hex) | BYD's name for it |
|---|---|---|---|---|
| `soc` | 1014 | 1246777400 | 0x4A505038 | STATISTIC_ELEC_PERCENTAGE |
| `soh` (FidRegistry) | 1014 | 1145045032 | 0x44400028 | STATISTIC_BATTERY_HEALTHY_INDEX |
| `maxCellVoltage` | 1014 | 1147142192 | 0x44600030 | **STATISTIC_HIGHEST_BATTERY_VOLTAGE** |
| `minCellVoltage` | 1014 | 1147142160 | 0x44600010 | **STATISTIC_LOWEST_BATTERY_VOLTAGE** |
| `maxBatTemp` | 1014 | 1148190752 | 0x44700020 | STATISTIC_HIGHEST_BATTERY_TEMP |
| `minBatTemp` | 1014 | 1148190736 | 0x44700010 | STATISTIC_LOWEST_BATTERY_TEMP |
| `hvVoltage` | 1009 | 1145045000 | 0x44400008 | CHARGING_CHARGE_BATTERY_VOLT |
| `hvCurrent` | 1009 | 1145045016 | 0x44400018 | CHARGING_CHARGE_CURRENT |
| `bmsMaxChargeKw` | 1014 | 877658136 | 0x34500018 | STATISTIC_MAX_CHARGE_POWER_ALLOW |
| `bmsMaxDischargeKw` | 1014 | 1145045048 | 0x44400038 | STATISTIC_BATTERY_AVAILABLE_POWER |
| `insulationKohm` | 1039 | 1134559256 | 0x43A00018 | GB_BMC_INSULATION_VALUE |
| `motorTempFront/Rear` | 1039 | 1154482192 / 1155530768 | 0x44D00010 / 0x44E00010 | GB_FRONT_MOTOR_TEMP / GB_TREAR_MOTOR_TEMP |
| `inverterTempFront/Rear` | 1039 | 1154482184 / 1155530760 | 0x44D00008 / 0x44E00008 | GB_FRONT_MOTOR_IPM_TEMP / GB_REAR_MOTOR_IPM_TEMP |
| `voltage12v` | 1001 | 1128267816 | 0x43400028 | OTA_BATTERY_POWER_VOLTAGE |
| `chargingCapacityKwh` | 1009 | 666894360 | 0x27C00018 | CHARGING_CAPACITY |
| `batteryType` | 1009 | -1728053169 | 0x9900004F | CHARGING_BATTERY_TYPE |

The Tech panel (`ui/tech/TechPanelScreen.kt:227`, `LimitsAndCellsCard`) renders exactly
min / max / delta plus a range strip. That is BYDMate's entire cell story today: **a spread, not
cells**. Nothing in the codebase reads a per-cell array, a per-cell temperature, a balancing bit,
or a cell index. `DiParsData.avgBatTemp` exists as a field but has no `FidMap` entry.

---

## 2. Can per-cell data be reached from the head unit's autoservice?

### 2.1 The decisive local evidence

`D:\BYD\DiLink\apk\amapservice-jadx\sources\com\byd\feature\` is BYD's own feature-id name
catalogue, compiled into an app that ships on this car: 46 device groups (`ac`, `body`, `charging`,
`gb`, `statistics`, `motor`, `dtc`, …) holding **11 483** `NAME = "0xXXXXXXXX"` constants. This is
the authoritative dictionary for the fid space; every BYDMate fid above resolves inside it,
which validates the mapping.

Searching all 11 483 for cell-level names (`CELL`, `SINGLE`, `MONOMER`, `BALANC`, `单体`, `MODULE_VOLT`)
gives, in full:

- `STATISTIC_HIGHEST_BATTERY_VOLTAGE` = 0x44600030 — already read
- `STATISTIC_LOWEST_BATTERY_VOLTAGE` = 0x44600010 — already read
- `GB_VEHICLE_CELL_COUNT` = 0x43600808 — cell count
- `GB_SINGLE_CELL_OVER_VOLTAGE_ALARM` / `_UNDER_VOLTAGE_ALARM` / `_CLASS_A_TEMPERATURE_DIFFERENCE_ALARM`
- `GB_TRACTION_BATTERY_CELL_INCONSISTENCY_ALARM`, `GB_BATTERY_POOR_CONSISTENCY_ALARM`
- `CHARGING_BATTERY_VOLTAGE_CELL_NUM` = 0x22D05810 (a count/index, not a measurement)
- `CHARGING_SINGLE_BATTERY_MAX_VOLTAGE` = 0x4A710034 — this is the GB/T 27930 DC-handshake
  *permitted* max cell voltage, a limit the BMS declares, not a measured value
- `BODYWORK_BATTERY_SINGLE_NUM` = 0x43A00008
- `BODYWORK_UAV_BATTERY_CELL_TEMPERATURE` (drone battery — the Yangwang roof drone, irrelevant)

Group 0x446 — the cell-voltage group — has exactly two members, at offsets +0x10 and +0x30.
There is no third entry, no `..._CELL_NO`, no array fid. Compare with the temperature-probe group
0x43B, which *does* carry indices (`GB_MAX_TEMP_PROBE_NUM`, `GB_MIN_TEMP_PROBE_NUM` alongside
`GB_PROBE_MAX_TEMP`/`GB_PROBE_MIN_TEMP`) — so BYD models "which sensor" where it wants to, and
chose not to for cell voltage.

**Conclusion: on the autoservice surface as catalogued, per-cell voltages, per-cell temperatures,
balancing state, and the identity of the min/max cell do not exist.** The BMS aggregates them
before publishing to the cabin bus. This is a strong negative, not an inconclusive search.

### 2.2 What *is* new and worth wiring

These resolve in the catalogue but are unread by BYDMate. Device ids follow the group→device
mapping already proven above (statistics→1014, charging→1009, gb→1039, bodywork→1001):

| Name | hex | dec | dev (likely) |
|---|---|---|---|
| STATISTIC_AVERAGE_BATTERY_TEMP | 0x44700038 | 1148190776 | 1014 |
| STATISTIC_REMAINING_BATTERY_POWER | 0x44700028 | 1148190760 | 1014 |
| STATISTIC_INSTANTANEOUS_CURRENT | 0x44A00020 | 1151336480 | 1014 |
| GB_PROBE_MAX_TEMP | 0x43B00010 | 1135607824 | 1039 |
| GB_MAX_TEMP_PROBE_NUM | 0x43B00008 | 1135607816 | 1039 |
| GB_PROBE_MIN_TEMP | 0x43B00020 | 1135607840 | 1039 |
| GB_MIN_TEMP_PROBE_NUM | 0x43B00018 | 1135607832 | 1039 |
| GB_VEHICLE_CELL_COUNT | 0x43600808 | 1130366984 | 1039 |
| GB_VEHICLE_TEMPERATURE_PROBE_COUNT | 0x43600818 | 1130367000 | 1039 |
| GB_BATTERY_PROBE_NUM | 0x43A00010 | 1134559248 | 1039 |
| GB_BATTRY_PACKS_NUMBER | 0x43900808 | 1133512712 | 1039 |
| GB_TOTAL_VOLTAGE | 0x33100808 | 856688648 | 1039 |
| GB_TOTAL_CURRENT | 0x33900838 | 865077304 | 1039 |
| GB_THERMAL_RUNAWAY | 0x43800018 | 1132462104 | 1039 |
| GB_TEMP_DIFF_ALARM | 0x43800008 | 1132462088 | 1039 |
| GB_BATTERY_POOR_CONSISTENCY_ALARM | 0x4380000A | 1132462090 | 1039 |
| GB_TRACTION_BATTERY_CELL_INCONSISTENCY_ALARM | 0x23000836 | 587204662 | 1039 |
| GB_SINGLE_CELL_OVER_VOLTAGE_ALARM | 0x23000808 | 587204616 | 1039 |
| GB_SINGLE_CELL_UNDER_VOLTAGE_ALARM | 0x2300080A | 587204618 | 1039 |
| CHARGING_ACTUAL_BATTERY_NUMBER | 0x43800038 | 1132462136 | 1009 |
| CHARGING_BATTERY_VOLTAGE_CELL_NUM | 0x22D05810 | 584079376 | 1009 |
| BODYWORK_BATTERY_SINGLE_NUM | 0x43A00008 | 1134559240 | 1001 |

That gives a *cell-count-aware* spread plus a per-probe temperature story — a materially better
battery page than today's — without any per-cell array. Two entries are speculative and should be
labelled as such if pursued: `GB_DYNAMIC_DATA_CALLBACK` = 0x9900001F (dec -1728053217). The 0x99
prefix marks MCU→SOC message channels (307 of them in the catalogue) and GB/T 32960's dynamic-data
block *does* by standard carry a full cell-voltage list. **But no decompiled app on this car uses
that fid, its payload shape is unknown, and BYDMate's daemon reads only two ints from the reply
parcel — a buffer read (tx 9) is not implemented.** Treat as a long shot, not a plan.

### 2.3 Exact procedure to produce the owner's own catalogue

`dumpFidsCore` (`helper/HelperDaemon.kt:2110`) reflects every static `int`/`long` out of
`android.hardware.bydauto.BYDAutoFeatureIds` and `BYDAutoConstants` **plus their declared inner
classes**, and emits sorted `InnerClass.FIELD=value` lines. Inner classes seen in decompiled BYD
apps: `Adas`, `Audio`, `Bodywork`, `Charging`, `Gearbox`, `Instrument`, `Reminder`, `Rse`, `Safety`,
`Sensor`, `Setting`.

1. In BYDMate → Settings, scroll to the diagnostics card and press **"Сохранить каталог fid'ов"**
   (`settings_fid_dump_button`, `SettingsScreen.kt:2211`). The helper daemon is restarted first,
   so a stale daemon is not an issue.
2. The file lands in `/storage/emulated/0/Download/fid-dump-YYYYMMDD-HHMMSS.txt` with a BYDMate
   version + `Build.DISPLAY` header, and a share sheet opens. Only the most recent two dumps are
   kept. It contains SDK constant names/values only — no VIN, no location.
3. Grep it. Case-insensitively, for: `CELL`, `SINGLE`, `MONOMER`, `BALANC`, `EQUAL`, `VOLT`,
   `BATT`, `BMS`, `MODULE`, `PROBE`, `TEMP`, `CONSIST`, `SOC`, `SOH`, `单体`, `均衡`, `电压`, `电芯`
   (the framework class is likely ASCII-only, but check anyway).
4. Cross-check any hit against §2.1: convert the decimal value to hex and look the hex up in
   `D:\BYD\DiLink\apk\amapservice-jadx\sources\com\byd\feature\` to get BYD's own name for it.
5. To probe a candidate live, from an adb shell on the car:
   `service call autoservice 5 i32 <dev> i32 <fid>` (int), or `7` for float. A reply of `0000ffff`
   means the fid↔CAN link is not established on this car; `ffffd8e3` means wrong transact code.

Caveat: the dump reflects the *framework's* id list, which on DiLink 5.1 may be a subset or superset
of the amapservice catalogue. A name in the dump does not prove the signal is routed on a Tang L —
only a non-sentinel read does.

---

## 3. The OBD-II / UDS route

Web research found **no public prior art at all** for per-cell voltage from a BYD car's OBD port.
Specifically:

- **No BYD BMS UDS DID list** anywhere. The one GitHub project search engines attribute BYD
  "2101–210A" cell-voltage PIDs to (`waleedmandour/EVD`) returns 404 — the repo does not exist.
  Several blog posts that appear to describe BYD cell PIDs are recycling documented **VW e-Golf**
  DIDs (github.com/cheshirekow/egolf-battery-report).
- **Car Scanner ELM OBD2** does ship a BYD/Denza profile. Its official changelog
  (https://www.carscanner.info/profile-database-changelog/) lists: *Atto 2 / Atto 3 / Yuan Plus EV /
  Dolphin / Seal / Seal U / Sealion / M6 / eMax 7 / 海豚 / Denza D9 / Denza N7 / Sealion 7 EV*.
  **Tang, Tang L, Han and Song do not appear.** The changelog does not itemise field granularity;
  two YouTube demos on a Seal show "hidden battery parameters" but neither could be confirmed to
  show individual cells rather than pack min/max.
- **Torque Pro**: no BYD custom-PID list found. **EVNotify / ABRP**: no BYD support; OBD support for
  the Seal U is an open, undelivered request (https://abrp.featurebase.app/p/67cc7661faea692533d1fd57).
- **DBC files**: `github.com/BYDcar/opendbc-byd` contains `byd_tang_phev_2015.dbc` — body signals only
  (doors, lights, indicators), for the 2015 petrol-hybrid Tang. No BMS content, wrong car.
- **Beware a category of false hits**: `dfch/BydCanProtocol`, `kaeferfreund/BYDB-Box_reversed` and the
  `pelle8/gen24` register docs all concern BYD's **stationary Battery-Box** home storage product.
- **Gating**: `github.com/wheregoes/byd-dolphin-hacking` reports that BYD's own `BydDevelopmentTools.apk`
  carries a full ISO 14229 UDS stack with session control and **security access (seed/key)** — so BYD's
  proprietary DIDs are likely gated behind an algorithm that is not public. No one has posted a passive
  CAN sniff at a 2025 BYD's OBD connector showing un-gated BMS cell frames. Claims that BYD "locked
  down OBD for UN R155" appear only in near-identical SEO/AI-generated articles and should not be
  treated as evidence; a China-market Tang L is not subject to UN R155 anyway.
- **Aftermarket tools**: Launch X431 EV kit, Foxwell NT710 Max and BYD's factory VDS2100 are *claimed*
  to show "individual cell voltages"; the claims recur in identical wording across five unrelated
  domains — the signature of syndicated AI content — and no screenshot, log or user report against a
  Tang L exists.

**Hardware needed if pursued anyway**: not an ELM327. A raw-CAN interface with UDS and arbitrary-DID
request capability (SavvyCAN + CANable/Kvaser, or a Panda), plus either the seed/key algorithm or a
physical tap of the internal battery CAN segment behind the gateway. Tapping the pack bus on a CTB
Blade car means opening HV-adjacent structure — warranty-voiding and safety-relevant, with no
published starting point for this model.

---

## 4. SOME/IP

Checked directly, and the answer is a clean no.

- `D:\BYD\DiLink\lib\proto-strings.txt` (32 678 symbols from the SOME/IP protobuf library): searching
  for `cell`, `batt`, `bms`, `volt`, `soc`, `module`, `temperature` yields **only** the
  `someip::in::i_::cellular::network` namespace (modem/APN/IMEI) — a false positive on "cellular".
  The only two proprietary proto namespaces present are `cellular` and `agns` (assisted GNSS).
- `D:\BYD\DiLink\someip\someip_stack.json` lists all 58 vsomeip applications the head unit runs:
  ADAS/NOA (INS, PVT, RTK/IMU, obstacle/lane, planning, parking, HPA), navigation and HUD,
  media/telephony/WiFi, Huawei ADS render/OTA, sentry mode, ECU version, cellular/AGNSS. **No BMS,
  battery, energy or powertrain service is offered or consumed.**

So the head unit's SOME/IP link (the same one the Waze-HUD work uses) carries no battery data at all.
Pack-level values reach the head unit over the CAN/`autoservice` path instead.

The `com.byd.car` DiCar SDK inside `amapservice` (`carinfo/ICarInfoService`,
`feature/energy/ICarEnergyManagementService`, `feature/driving/ICarDrivingStateService`) likewise
exposes only `getBatteryLevel`, SOC hold/display settings and battery heat-saving. `openbyd`'s
`CarControlImpl.java` uses `BYDAutoStatisticDevice` purely for navigation/HUD registers.

---

## 5. Ranked recommendation

1. **Run the fid dump on the car and grep it (§2.3).** Effort: minutes. Likelihood of finding a
   per-cell array: low, given §2.1 — but it is cheap, it is the only way to see this car's *actual*
   framework list, and it validates or refutes the whole §2.2 table in one pass. Do this first
   because it gates everything else.
2. **Wire the §2.2 aggregates into the Tech panel.** Effort: hours. Likelihood of working: high —
   catalogued names in groups whose neighbours already read correctly on this car. Gets average pack
   temp, remaining kWh, instantaneous current, hot/cold probe temperature *and index*, cell count, and
   the BMS consistency/thermal-runaway alarm flags. That last group is the practically useful part:
   `GB_BATTERY_POOR_CONSISTENCY_ALARM` and `GB_TRACTION_BATTERY_CELL_INCONSISTENCY_ALARM` are the
   BMS's own verdict on cell balance — which is what per-cell voltages would mostly be used to infer.
3. **Pull and decompile the factory diagnostic apps already installed on this Tang L.** Confirmed
   present in the car dump: `com.byd.byddevelopmenttools` (the app the Dolphin research says carries a
   full UDS stack), `com.byd.diagnosticinfo`, `com.byd.spotinspection` (点检), `com.byd.CanDataCollect`,
   `com.byd.byddatachecktool`, `com.byd.cdr`. Effort: a day. Likelihood: moderate. If any factory
   tooling on this car reads cell voltages it does so from the head unit's own position on the bus,
   and the request would be visible in its code — handing over the DID and possibly the seed/key
   routine. The single highest-value unexplored local lead.
4. **Chase the sources that blocked automated fetch**, by hand in a browser: the CSDN series
   "比亚迪开放平台接口" by `shangxianyue5670` (items #2, #4, #14) and `ccagy`'s "比亚迪开放接口清单253个"
   (blog.csdn.net/ccagy/article/details/105005081) — both HTTP 521; the 迪友社区 thread on a community
   "battery self-test" DiLink app claimed to show 单体电池电压 (bydmax.com, connection reset; reported
   broken on DiLink 4.0 0720+, status on DiLink 150 unknown); and 4pda.ru's BYD subforum, which
   produced zero indexed hits. Effort: an hour. Likelihood: low-to-moderate, mostly for older Tang/Qin.
5. **OBD/UDS.** Effort: weeks, plus hardware, plus HV risk if the pack bus must be tapped.
   Likelihood: low without the seed/key. Do not start here.

Not recommended: implementing a tx-9 buffer read for `GB_DYNAMIC_DATA_CALLBACK` on spec. Do it only
if step 2 or 3 gives independent reason to think that channel carries the GB/T 32960 dynamic block.

---

## 6. Uncertainty list

- The amapservice catalogue is the fid dictionary shipped on *this* car, but not proof of what the
  framework's `BYDAutoFeatureIds` contains on DiLink 5.1. A per-cell fid could exist in the framework
  and be absent from the app's copy. §2.3 settles this; until run, §2.1 is strong evidence, not proof.
- Device ids in §2.2 are inferred from the group→device pattern that holds for every already-working
  fid (statistics→1014, charging→1009, gb→1039, bodywork→1001). Untested for these specific fids.
  A wrong dev id returns a sentinel, not a wrong number, so the failure mode is safe.
- Scaling and offsets for every §2.2 candidate are unknown. Battery temps elsewhere carry a −40 CAN
  offset; cell voltages are mV; current sign convention is unverified.
- `GB_DYNAMIC_DATA_CALLBACK`'s payload is entirely unknown. The GB/T 32960 connection is inference
  from the name and the standard's contents, with zero local corroboration.
- Whether a Tang L routes any given GB_* fid is unverified — several may return `FEATURE_LINK_ERROR`.
  All BYDMate GB_* fids to date were validated on a Leopard 3, not a Tang L.
- Whether the min/max voltage BYDMate reads is per-cell or per-module is not established. On a CTB
  Blade pack the distinction matters; `BODYWORK_BATTERY_SINGLE_NUM` / `GB_VEHICLE_CELL_COUNT` would
  settle it if they read.
- OBD gating on a China-market 2025 BYD is unverified in both directions; the seed/key claim comes
  from one repo's reading of `BydDevelopmentTools.apk` on a *Dolphin*.
- Car Scanner's omission of Tang/Tang L is from its public changelog; the vendor may support models
  it does not list, and the changelog does not describe field granularity.
- No evidence either way on whether BYD's cloud/telematics backend holds cell-level data.
