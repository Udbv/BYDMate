# What the Tang L instrument panel and HUD can actually display

Source of truth: BYD's own feature-name catalogue, compiled into `com.byd.feature` inside
`AmapService.apk` on this car (11,483 named feature ids across 46 device groups). Every id below
is quoted with the name BYD gives it. Written through the autoservice as
`(device 1007, feature id, value)`; that is the only channel the Tang L glass was observed to
read (docs/investigations/tang-l-hud-someip.md).

Status column: **live** = BYDMate writes it today, **ready** = id confirmed, not wired yet,
**blocked** = needs a daemon capability we do not have.

## 1. Maneuver block — `0x43F01xxx`

| Feature | Id | Status | Notes |
|---|---|---|---|
| `INSTRUMENT_GUIDE_INFO_SIMPLE_SET` | `0x43F01010` | live | The maneuver glyph. **Its own numbering, not ours** — see §2. |
| `INSTRUMENT_GUIDE_INFO_AND_ROAD_AHEAD_DISTANCE_SET` | `0x43F01030` | live | Written with the same glyph as above, as the donor does. |
| `INSTRUMENT_FRONT_CROSSING_DISTANCE_SET` | `0x43F01018` | live | Metres to the maneuver. |
| `INSTRUMENT_SEND_NAVI_STATUS_SET` | `0x43E0003A` | live | 2 navigating, 4 stopped. Gates the whole strip. |

## 2. The glyph numbering, and why it bit us

The panel's glyph table and BYDMate's internal Gaode codes are different spaces that happen to
agree on plain turns. Writing a Gaode code straight through drew the wrong picture:

| Gaode code | we meant | panel drew |
|---|---|---|
| 4 | slight right | a second **slight left** glyph |
| 13 | enter roundabout | **detour right** |
| 24 | exit roundabout | roundabout straight, variant 2 |
| 45 | waypoint | **stop, left** |
| 46 | ferry | **parking / services** |
| 48 | arrive | **destination drawn as Chinese characters** |

Fixed in `HudInstrumentIcons`. The panel's full table, from the donor:

- 1 left, 2 right, 3 / 4 slight left (two variants), 5 / 6 slight right, 7 sharp left,
  8 sharp right, 9 U-turn left, 10 U-turn right, 11 straight, 12 straight dotted
- 13 detour right, 14 detour left
- 15–24 roundabout variants (quarter and three-quarter, left and right, straight)
- **25–34 roundabout exits, counter-clockwise** — right-hand traffic, so these are the ones
  Ukraine needs, and our synthetic "exit N" codes line up with them exactly
- 35–44 the same exits clockwise (left-hand traffic, never correct here)
- 45 stop left, 46 parking/services, 47 tollbooth, 48 destination in Chinese, 49 tunnel

Anything the panel cannot draw sensibly is now sent as 0, which blanks the icon rather than
showing a wrong or foreign-language picture.

## 3. Lanes — `0x19802xxx`

| Feature | Id | Status |
|---|---|---|
| `INSTRUMENT_TOTAL_LANES_SET` | `0x198020D8` | live |
| `INSTRUMENT_LANE_n_GUIDANCE_ARROW_SET` | `0x19802058` + 16·n | live |
| `INSTRUMENT_n_LANE_LINE_TYPE_SET` | `0x19802060` + 16·n | live |
| `INSTRUMENT_IS_LANE_n_RECOMMENDED_SET` | `0x19802064` + 16·n | live |

Eight slots. The arrow value carries both the lane's directions and whether the route uses it
(plain glyph = code + 1, highlighted = direction + 26, combined glyphs 51–82 for lanes allowing
several directions). The panel therefore shows a full lane strip, not just a count.

## 4. Street name — the current gap

| Feature | Id | Status |
|---|---|---|
| `INSTRUMENT_TARGET_NEXT_PATHNAME_INFO_SET` | `0x43FA1008` | blocked |
| `INSTRUMENT_TARGET_NEXT_PATHNAME_INFO_OVERASEA_SET` | `0x1F7A1008` | blocked |

Both take a **byte array**, and the helper daemon only offers integer writes, so BYDMate writes
neither. That is why the next-street name is missing from the panel: it was only ever carried by
the SOME/IP road-info frame (field 10), and that channel does nothing on this car.

Note the second id: BYD ships a separate **overseas** variant of the street-name feature, which is
very likely the one that accepts non-Chinese text. Worth trying first on a Ukrainian car.

Unblocking it means one new daemon transaction. The daemon runs under `app_process` with the
framework on its classpath, so it can call
`BYDAutoInstrumentDevice.getInstance(ctx).set(intArrayOf(fid), BYDAutoEventValue().apply { bufferDataValue = bytes })`
by reflection, exactly as the donor does, instead of guessing a raw binder transaction code.

## 5. Route totals — `0x43F02xxx`, `0x43F09xxx`

| Feature | Id | Status |
|---|---|---|
| `INSTRUMENT_NAVI_TRIP_INFO_MILEAGE_SET` | `0x43F02028` | live |
| `INSTRUMENT_NAVI_TRIP_INFO_HOUR_SET` | `0x43F02010` | live |
| `INSTRUMENT_NAVI_TRIP_INFO_MINUTE_SET` | `0x43F02018` | live |
| `INSTRUMENT_NAVI_TRIP_REMAINING_SECOND_SET` | `0x43F0201E` | ready |
| `INSTRUMENT_EXPECTED_ARRIVE_HOUR_SET` | `0x43F09018` | ready |
| `INSTRUMENT_EXPECTED_ARRIVE_DAY_SET` | `0x43F09010` | ready |
| `INSTRUMENT_REMAIN_DRIVING_TIME_DAY_SET` | `0x43F02024` | ready |

We currently write only the arrival *minute*; the hour is available and should be written with it.

## 6. Speed cameras and safety — `0x43F03xxx`, `0x43F04xxx`

| Feature | Id | Status |
|---|---|---|
| `INSTRUMENT_GUIDE_INFO_CAMERA_SET` | `0x43F03010` | ready |
| `INSTRUMENT_CAMERA_DISPLAY_STATE_SET` | `0x43F03018` | ready |
| `INSTRUMENT_NAVI_CAM_REMAINING_MILEAGE_SET` | `0x43F0301C` | ready |
| `INSTRUMENT_GUIDE_INFO_SAFETY_SET` | `0x43F04010` | ready |
| `INSTRUMENT_SAFETY_DISPLAY_STATE_SET` | `0x43F04018` | ready |
| `INSTRUMENT_NAVI_SAFETY_REMAINING_MILEAGE_SET` | `0x43F0401C` | ready |

This is the closest thing the panel has to a speed-limit display: a camera with its own limit and
a distance. There is **no** plain "show this speed limit" feature for navigation to write; the
driving computer's own detected limit (`ADAS_SLA_OUTPUT_SPEED_LIMIT`, `0x2D500020`) is read-only.

Relevant to Waze: it only shows the limit while you exceed it, unless the speedometer setting is
changed to show it always.

## 7. Secondary maneuver — `0x43F08xxx`

| Feature | Id | Status |
|---|---|---|
| `INSTRUMENT_NAVI_LEAD_MSG_ADVANCED_SET` | `0x43F08010` | ready |
| `INSTRUMENT_DISTANCE_OF_TARGET_AHEAD_ADVANCED_SET` | `0x43F08018` | ready |
| `INSTRUMENT_GUIDE_INFO_ADVANCED_ACTION_SET` | `0x43F08030` | ready |

The "and then" hint after the current maneuver. Waze shows this; we do not read it yet.

## 8. What the panel cannot do

- No arbitrary text beyond the street-name features.
- No speed limit as a plain number.
- No map image. The AR overlay is fed by a different service that this car ignores from us.

## 9. Open questions for the next drive

1. Does the street name reappear if the SOME/IP road-info frame is switched back on? That tells us
   whether the earlier bisection missed a channel or the name only ever came from the panel.
2. Which roundabout glyph the panel actually draws for `ROUNDABOUT_STRAIGHT` (23), and whether the
   numbered CCW exits look right for Ukrainian roundabouts.
3. Whether the overseas street-name feature accepts Cyrillic once the daemon can write bytes.
