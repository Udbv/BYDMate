# Drives of 2026-09-13 (3.16.0-dev.10 / 3.16.1-dev.1)

Two drives plus a parked HUD Tester session. Evidence for the openbyd port after dev.9.

## Arrow classifier
- 167 classifications, one unmatched (the first frame, arrow still animating). Left turn: 102×
  `big_trans_direction_left` at hamming 0; roundabouts `_l`/`_r` at 0–1; plain roundabout at 5.
- No `default` target any more (dev.9 fix): the phantom roundabouts are gone.

## Speed limit
- `sendSpeedLimitInfo` (statistic 0x40906020 + 0x2CF20038, SDK `sendCameraGuidanceInfo(1,0,1)`)
  accepted with status 0 for 50/30/20 and the owner saw the limit on the panel. So the SDK camera
  call is not a no-op for type 1; other camera types drew nothing from the tester (statuses not
  captured — logcat rotated; dev.2 logs them to the trip log).

## Lanes
- `laneGuidanceView` never appeared in the accessibility tree on this route (Рачінського /
  Каховська / Куліша / Шептицького), although Waze drew a two-lane strip before the second
  roundabout. On 2026-09-11 (Вершигори / Шухевича / Бальзака) the same reader found it 7×.
  Open: whether that junction's strip is a different (Compose) view without the id. The dev.2
  discovery (800-node budget, text nodes under list containers) should name it.

## New nodes
- `navBarThen` / `navBarThenDirection` (TextView) / `navBarThenText` = "потім": the second
  maneuver widget. `routeDetailsRecycler` (maneuver list) and `eventsOnRouteContainer` (reports)
  exist; their rows have no ids and were not captured by the id-only discovery.
