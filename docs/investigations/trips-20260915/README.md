# Drives of 2026-09-14/15 (3.16.1-dev.2)

Five drives; logs kept locally (route text), this file is the committed summary.

## Arrow classifier — stable
1741 classifications: exit_right 503× (h=1), left 393× (h=0), right 367× (h=1), forward,
exit_left, u_turn, roundabout, end; 27 unmatched. Roundabout exits with numbers (e.g. CCW 6th
exit) reached the panel. Speed limit changes (10/30/50) accepted each time.

## Lanes — container found, pixels wrong
- Tree: `laneGuidanceView` with 3/5/7/8 leaf children (cells with bounds, no labels), ~35 times.
- Pixel path ran 9 times, found fewer cells than the tree (3 vs 5, 1 vs 5); every lane crop
  classified as code 0 (the lane arrows are not the big_trans_* shapes). Owner saw lanes on the
  panel that did not match Waze. Container rectangle is not logged yet, so the owner's
  hypothesis (a small hint widget cropped instead of the main lane panel) cannot be checked.
  dev.3: cells from the tree children, rectangles and unmatched cell grids in the log.

## Reports list — text
`navListReportsList` rows: `navListReportItemType` ('Глухий затор'), `navListReportItemDistance`
('1.6 км попереду'); `navListNoReportsText` when empty. Route card: 'Небезпека', 'Аварія'
badges under `eventsOnRouteContainer`. dev.3 reads camera reports from here.

## Then widget
`navBarThenDirection` has no text (icon), `navBarThenText` carries the street after the next
maneuver ('вул. Стеценка', 'просп. Степана Бандери').

## Not seen
No PANEL lines from the tester's raw camera/safety/then rows — not pressed, or pressed before
dev.2. Still open which channel draws cameras.
