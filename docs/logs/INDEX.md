# Car logs: what they contain (durable memory)

Purpose: a future assistant session has no access to the raw car logs (they live only in the Telegram
chat and in one old session's scratchpad). This file says what the logs contained as of 2026-11-03
(~22:34 car time) so questions can be answered from it, and tells how to get the raw files again.
Privacy: no coordinates, no top speeds, no bot token or chat id are written here. Limits such as
50/70/90/110 are road limits sent by the app, not vehicle speeds.

## 1. How to use

- Fetch: `BYDMATE_TG_BOT_TOKEN=... BYDMATE_TG_CHAT_ID=... python3 tools/tg-logs/fetch.py <DIR>`
  (details in `tools/tg-logs/README.md`). It downloads every pinned file and **unpins** it; it never
  deletes a message, so old files are still in the Telegram chat, only no longer pinned (and the Bot API
  serves files up to 20 MB). The next fetch therefore returns only files sent after the previous one:
  keep the fetched archive somewhere durable, or the older files must be recovered from the chat by hand.
- Index: `python3 -I -B tools/tg-logs/index.py <ARCHIVE_DIR> <OUT_DIR>` writes `INDEX.md` + `index.json`
  (file, kind, version, time span, per-category counts, parked-test table, sample lines). Read the index
  first, then grep only the right file for the category regex.
- `docs/logs/FILES.md` is the machine-made file table from the last index run (118 files indexed out of 164
  fetched; 46 zips skipped because a same-named .txt exists). This file (`INDEX.md`) is the human reading of it.
- Where the raw files were: `/tmp/claude-0/-home-user-BYDMate/13bace2c-94d2-5318-a86b-71357b91e275/scratchpad/log-archive`
  (session scratchpad, will vanish).
- File kinds: `bydmate_logs_*` = 20-minute parts of the automatic logcat trip log (car local time, UTC+2;
  consecutive parts overlap by up to ~10 s); `trip-*.log` = app TripDebugLog (time only, date from the name);
  `bydmate_fidrec_*` = sensor recorder dumps (large; only metadata and `#` notes indexed).
- Category regexes: see `tools/tg-logs/README.md` and the task list in `index.py`.
- A log part can start a minute before its trip log (same session). One trip log = one service run; its header says
  `reason: service start` (34 times; dev.15 trip-20261103-213653 is `app update`) or `app update` (5 times) and the version.
- Caveats: the four oldest logs (2026-10-24/25) lack the version line (version "?"); `trip-20261025-113120.log`
  and `trip-20261029-142648.log` contain only a header. Fid dumps have no versionName, only versionCode.

## 2. Timeline by date and app version

Versions seen: 4.0.1-dev.2 (510) .. dev.9 (517), dev.10 (518) on 2026-10-24..29; 4.1.0-dev.15 (523) .. dev.23 (531)
on 2026-10-30/31; 4.2.0-dev.1 (533) .. dev.15 (547) on 2026-10-31..11-03. Many dev builds were installed during the
2026-11-03 session (dev.2, .3, .6, .11, .15 within one afternoon). Lines in `categories` are counts of matching lines
(each parked/live/shadow line appears in both the trip log and the log part of the same time, so do not add them).
Kinds: trip = trip-*.log, log = bydmate_logs_*, fid = bydmate_fidrec_*.

| date | kind | file (without prefix) | version | local span | categories (count) |
|---|---|---|---|---|---|
| 2026-10-24 | log | `logs_20261024_163628` | ? | 16:34-16:36 | - |
| 2026-10-24 | trip | `trip-20261024-163440` | 4.0.1-dev.2 (510) | 16:34-16:36 | - |
| 2026-10-25 | trip | `trip-20261025-104822` | 4.0.1-dev.2 (510) | 10:48-10:49 | - |
| 2026-10-25 | trip | `trip-20261025-113120` | 4.0.1-dev.3 (511) | - | - |
| 2026-10-25 | log | `logs_20261025_115154` | ? | 11:50-11:52 | errors 1 |
| 2026-10-25 | fid | `fidrec_20261025_115150` | vc 511 | 11:51-14:18 | - |
| 2026-10-25 | log | `logs_20261025_140515` | ? | 14:04-14:05 | - |
| 2026-10-25 | trip | `trip-20261025-140410` | 4.0.1-dev.3 (511) | 14:04-14:05 | - |
| 2026-10-25 | trip | `trip-20261025-171450` | 4.0.1-dev.3 (511) | 17:14-17:15 | lanes_visual 2 |
| 2026-10-25 | trip | `trip-20261025-171535` | 4.0.1-dev.5 (513) | 17:15-17:36 | lanes_visual 295 |
| 2026-10-25 | log | `logs_20261025_173738` | ? | 17:36-17:38 | - |
| 2026-10-25 | trip | `trip-20261025-173626` | 4.0.1-dev.6 (514) | 17:36-17:38 | lanes_visual 2 |
| 2026-10-25 | trip | `trip-20261025-202228` | 4.0.1-dev.6 (514) | 20:22-20:24 | lanes_visual 1 |
| 2026-10-25 | trip | `trip-20261025-205233` | 4.0.1-dev.9 (517) | 20:52-20:53 | - |
| 2026-10-25 | log | `logs_20261025_205319` | 4.0.1-dev.9 (517) | 20:53-20:53 | - |
| 2026-10-27 | log | `logs_20261027_082501` | 4.0.1-dev.9 (517) | 08:24-08:34 | - |
| 2026-10-27 | trip | `trip-20261027-173929` | 4.0.1-dev.9 (517) | 17:39-17:47 | - |
| 2026-10-27 | log | `logs_20261027_174014` | 4.0.1-dev.9 (517) | 17:40-17:47 | errors 3, tg_fail 6 |
| 2026-10-28 | trip | `trip-20261028-131319` | 4.0.1-dev.9 (517) | 13:13-13:22 | lanes_visual 101 |
| 2026-10-28 | log | `logs_20261028_131407` | 4.0.1-dev.9 (517) | 13:14-13:22 | - |
| 2026-10-28 | log | `logs_20261028_182513` | 4.0.1-dev.9 (517) | 18:25-18:34 | - |
| 2026-10-29 | log | `logs_20261029_082708` (zip) | 4.0.1-dev.10 (518) | 08:26-08:40 | errors 6, lanes_visual 1, tg_fail 4 |
| 2026-10-29 | trip | `trip-20261029-082705` | 4.0.1-dev.10 (518) | 08:27-08:40 | lanes_visual 124 |
| 2026-10-29 | fid | `fidrec_20261029_082737` | vc 518 | 08:27-08:28 | - |
| 2026-10-29 | trip | `trip-20261029-140025` | 4.0.1-dev.10 (518) | 14:00-14:08 | - |
| 2026-10-29 | trip | `trip-20261029-142648` | 4.0.1-dev.10 (518) | - | - |
| 2026-10-30 | log | `logs_20261030_133938` | 4.0.1-dev.10 (518) | 13:39-13:41 | - |
| 2026-10-30 | log | `logs_20261030_141742` | 4.1.0-dev.15 (523) | 14:17-14:18 | - |
| 2026-10-30 | trip | `trip-20261030-141739` | 4.1.0-dev.15 (523) | 14:17-14:18 | - |
| 2026-10-30 | trip | `trip-20261030-142643` | 4.1.0-dev.15 (523) | 14:26-15:23 | lanes_visual 615 |
| 2026-10-30 | log | `logs_20261030_142652` | 4.1.0-dev.15 (523) | 14:26-15:23 | - |
| 2026-10-30 | trip | `trip-20261030-171230` | 4.1.0-dev.15 (523) | 17:12-19:49 | lanes_visual 2696 |
| 2026-10-30 | log | `logs_20261030_171237` | 4.1.0-dev.15 (523) | 17:12-19:12 | - |
| 2026-10-31 | trip | `trip-20261031-091953` | 4.1.0-dev.15 (523) | 09:19-09:22 | - |
| 2026-10-31 | log | `logs_20261031_092003` | 4.1.0-dev.15 (523) | 09:20-09:22 | - |
| 2026-10-31 | log | `logs_20261031_092301` | 4.1.0-dev.17 (525) | 09:22-10:23 | errors 1 |
| 2026-10-31 | trip | `trip-20261031-092256` | 4.1.0-dev.17 (525) | 09:23-10:22 | lanes_visual 974 |
| 2026-10-31 | trip | `trip-20261031-102914` | 4.1.0-dev.17 (525) | 10:29-10:32 | - |
| 2026-10-31 | log | `logs_20261031_102921` | 4.1.0-dev.17 (525) | 10:29-10:32 | - |
| 2026-10-31 | log | `logs_20261031_103228` | 4.1.0-dev.18 (526) | 10:29-12:32 | errors 4, lanes_visual 1 |
| 2026-10-31 | trip | `trip-20261031-103222` | 4.1.0-dev.18 (526) | 10:32-12:55 | lanes_visual 1925 |
| 2026-10-31 | log | `logs_20261031_123228` | 4.1.0-dev.18 (526) | 12:32-12:55 | - |
| 2026-10-31 | log | `logs_20261031_125559` | 4.1.0-dev.21 (529) | 12:55-13:33 | adas_shadow 179 |
| 2026-10-31 | trip | `trip-20261031-125552` | 4.1.0-dev.21 (529) | 12:55-13:33 | lanes_visual 559 |
| 2026-10-31 | trip | `trip-20261031-133346` | 4.1.0-dev.22 (530) | 13:33-13:45 | lanes_visual 348 |
| 2026-10-31 | trip | `trip-20261031-142916` | 4.1.0-dev.22 (530) | 14:29-14:29 | - |
| 2026-10-31 | log | `logs_20261031_142922` | 4.1.0-dev.22 (530) | 14:29-14:30 | adas_shadow 2 |
| 2026-10-31 | log | `logs_20261031_143016` | 4.1.0-dev.23 (531) | 14:30-15:24 | adas_shadow 272 |
| 2026-10-31 | trip | `trip-20261031-143012` | 4.1.0-dev.23 (531) | 14:30-15:23 | lanes_visual 1311 |
| 2026-10-31 | trip | `trip-20261031-152851` | 4.1.0-dev.23 (531) | 15:28-16:09 | lanes_visual 657 |
| 2026-10-31 | log | `logs_20261031_152855` | 4.1.0-dev.23 (531) | 15:28-16:10 | adas_shadow 164 |
| 2026-10-31 | log | `logs_20261031_161025` (zip) | 4.2.0-dev.1 (533) | 16:10-16:43 | adas_shadow 164 |
| 2026-10-31 | trip | `trip-20261031-161020` | 4.2.0-dev.1 (533) | 16:10-16:42 | lanes_visual 824 |
| 2026-10-31 | trip | `trip-20261031-170224` | 4.2.0-dev.1 (533) | 17:02-17:31 | adas_parked 12, lanes_visual 70 |
| 2026-10-31 | log | `logs_20261031_170230` (zip) | 4.2.0-dev.1 (533) | 17:02-17:32 | adas_parked 12, adas_shadow 47, errors 3, tg_fail 6 |
| 2026-11-01 | trip | `trip-20261101-105930` | 4.2.0-dev.1 (533) | 10:59-11:14 | - |
| 2026-11-01 | log | `logs_20261101_105938` (zip) | 4.2.0-dev.1 (533) | 10:59-11:14 | adas_shadow 25 |
| 2026-11-01 | log | `logs_20261101_111504` (zip) | 4.2.0-dev.2 (534) | 11:15-11:23 | adas_shadow 55 |
| 2026-11-01 | trip | `trip-20261101-111458` | 4.2.0-dev.2 (534) | 11:15-11:22 | adas_shadow 1, lanes_visual 259 |
| 2026-11-01 | trip | `trip-20261101-141535` | 4.2.0-dev.2 (534) | 14:15-14:43 | lanes_visual 517 |
| 2026-11-01 | log | `logs_20261101_141543` | 4.2.0-dev.2 (534) | 14:15-14:35 | adas_shadow 105, errors 3, tg_fail 6 |
| 2026-11-01 | log | `logs_20261101_143543` (zip) | 4.2.0-dev.2 (534) | 14:35-14:43 | adas_shadow 10, errors 1, tg_fail 2 |
| 2026-11-02 | trip | `trip-20261102-124540` | 4.2.0-dev.2 (534) | 12:45-12:52 | lanes_visual 74 |
| 2026-11-02 | log | `logs_20261102_124550` (zip) | 4.2.0-dev.2 (534) | 12:45-12:52 | adas_shadow 35 |
| 2026-11-03 | trip | `trip-20261103-152252` | 4.2.0-dev.2 (534) | 15:22-15:28 | adas_shadow 1, lanes_visual 165 |
| 2026-11-03 | log | `logs_20261103_152259` (zip) | 4.2.0-dev.2 (534) | 15:22-15:29 | adas_shadow 34, errors 1, tg_fail 2 |
| 2026-11-03 | log | `logs_20261103_152910` | 4.2.0-dev.3 (535) | 15:29-15:49 | adas_shadow 75, bump 1 |
| 2026-11-03 | trip | `trip-20261103-152906` | 4.2.0-dev.3 (535) | 15:29-17:15 | adas_shadow 1, lanes_visual 2550 |
| 2026-11-03 | fid | `fidrec_20261103_152910` | vc 535 | 15:29-15:49 | fid_notes 83 |
| 2026-11-03 | log | `logs_20261103_154911` | 4.2.0-dev.3 (535) | 15:49-16:09 | adas_shadow 108, bump 3, errors 1, tg_fail 2 |
| 2026-11-03 | fid | `fidrec_20261103_154919` | vc 535 | 15:49-16:09 | fid_notes 39 |
| 2026-11-03 | log | `logs_20261103_160912` | 4.2.0-dev.3 (535) | 16:09-16:29 | adas_shadow 123, bump 3, errors 2, tg_fail 4 |
| 2026-11-03 | fid | `fidrec_20261103_160923` | vc 535 | 16:09-16:29 | fid_notes 22 |
| 2026-11-03 | log | `logs_20261103_162913` | 4.2.0-dev.3 (535) | 16:29-16:49 | adas_shadow 114, bump 3, errors 1, tg_fail 2 |
| 2026-11-03 | fid | `fidrec_20261103_162928` | vc 535 | 16:29-16:49 | fid_notes 28 |
| 2026-11-03 | log | `logs_20261103_164914` | 4.2.0-dev.3 (535) | 16:49-17:09 | adas_shadow 118, bump 3 |
| 2026-11-03 | fid | `fidrec_20261103_164933` (zip) | vc 535 | 16:49-17:09 | fid_notes 29 |
| 2026-11-03 | log | `logs_20261103_170914` | 4.2.0-dev.3 (535) | 17:09-17:15 | adas_shadow 52, bump 3, tg_fail 3 |
| 2026-11-03 | fid | `fidrec_20261103_170943` (zip) | vc 535 | 17:09-17:43 | fid_notes 15 |
| 2026-11-03 | log | `logs_20261103_174304` | 4.2.0-dev.3 (535) | 17:43-17:51 | adas_shadow 62, bump 3, errors 1 |
| 2026-11-03 | trip | `trip-20261103-174301` | 4.2.0-dev.3 (535) | 17:43-17:51 | adas_shadow 1, lanes_visual 234 |
| 2026-11-03 | fid | `fidrec_20261103_174304` (zip) | vc 535 | 17:43-17:51 | fid_notes 5 |
| 2026-11-03 | log | `logs_20261103_175203` | 4.2.0-dev.6 (538) | 17:51-18:12 | adas_live 7, adas_shadow 90, bump 2, errors 1 |
| 2026-11-03 | trip | `trip-20261103-175157` | 4.2.0-dev.6 (538) | 17:52-19:45 | adas_live 36, adas_parked 7, adas_shadow 15, bump 1, lanes_visual 2617 |
| 2026-11-03 | fid | `fidrec_20261103_175203` | vc 538 | 17:52-18:12 | fid_notes 9 |
| 2026-11-03 | log | `logs_20261103_181204` | 4.2.0-dev.6 (538) | 18:12-18:32 | adas_live 5, adas_shadow 84, bump 3, errors 3, tg_fail 7 |
| 2026-11-03 | fid | `fidrec_20261103_181205` | vc 538 | 18:12-18:32 | fid_notes 7 |
| 2026-11-03 | log | `logs_20261103_183204` | 4.2.0-dev.6 (538) | 18:31-18:52 | adas_live 4, adas_shadow 57, bump 3 |
| 2026-11-03 | fid | `fidrec_20261103_183208` (zip) | vc 538 | 18:32-18:52 | fid_notes 5 |
| 2026-11-03 | log | `logs_20261103_185206` | 4.2.0-dev.6 (538) | 18:52-19:12 | adas_live 9, adas_parked 7, adas_shadow 27, bump 3, errors 1, tg_fail 3 |
| 2026-11-03 | fid | `fidrec_20261103_185211` (zip) | vc 538 | 18:52-19:12 | fid_notes 6 |
| 2026-11-03 | log | `logs_20261103_191207` | 4.2.0-dev.6 (538) | 19:11-19:32 | adas_live 4, adas_shadow 98, bump 4, tg_fail 1 |
| 2026-11-03 | fid | `fidrec_20261103_191212` (zip) | vc 538 | 19:12-19:32 | fid_notes 32 |
| 2026-11-03 | log | `logs_20261103_193207` | 4.2.0-dev.6 (538) | 19:32-19:45 | adas_live 7, adas_shadow 70, bump 3 |
| 2026-11-03 | fid | `fidrec_20261103_193215` (zip) | vc 538 | 19:32-19:45 | fid_notes 4 |
| 2026-11-03 | log | `logs_20261103_194548` | 4.2.0-dev.11 (543) | 19:45-20:05 | adas_live 16, adas_shadow 116, audio_test 93, bump 2, errors 1 |
| 2026-11-03 | trip | `trip-20261103-194542` | 4.2.0-dev.11 (543) | 19:45-21:28 | adas_live 118, adas_parked 22, adas_shadow 25, audio_test 93, bump 2, lanes_visual 2445 |
| 2026-11-03 | fid | `fidrec_20261103_194548` (zip) | vc 543 | 19:45-20:05 | fid_notes 17 |
| 2026-11-03 | log | `logs_20261103_200549` | 4.2.0-dev.11 (543) | 20:05-20:25 | adas_live 47, adas_shadow 115, bump 3, errors 1, tg_fail 2 |
| 2026-11-03 | fid | `fidrec_20261103_200550` (zip) | vc 543 | 20:05-20:25 | fid_notes 20 |
| 2026-11-03 | log | `logs_20261103_202549` | 4.2.0-dev.11 (543) | 20:25-20:45 | adas_live 20, adas_parked 22, adas_shadow 61, bump 3, errors 1, tg_fail 2 |
| 2026-11-03 | fid | `fidrec_20261103_202554` (zip) | vc 543 | 20:25-20:45 | fid_notes 7 |
| 2026-11-03 | log | `logs_20261103_204550` | 4.2.0-dev.11 (543) | 20:45-21:05 | adas_live 23, adas_shadow 100, bump 3, errors 1, tg_fail 2 |
| 2026-11-03 | fid | `fidrec_20261103_204554` (zip) | vc 543 | 20:45-21:05 | fid_notes 20 |
| 2026-11-03 | log | `logs_20261103_210550` | 4.2.0-dev.11 (543) | 21:05-21:25 | adas_live 14, adas_shadow 102, bump 3 |
| 2026-11-03 | fid | `fidrec_20261103_210555` | vc 543 | 21:05-21:25 | fid_notes 26 |
| 2026-11-03 | log | `logs_20261103_212553` | 4.2.0-dev.11 (543) | 21:25-21:28 | adas_shadow 2, bump 3 |
| 2026-11-03 | fid | `fidrec_20261103_212558` | vc 543 | 21:25-21:31 | fid_notes 2 |
| 2026-11-03 | trip | `trip-20261103-213134` | 4.2.0-dev.11 (543) | 21:31-21:36 | adas_live 5, adas_shadow 1, lanes_visual 29 |
| 2026-11-03 | log | `logs_20261103_213138` | 4.2.0-dev.11 (543) | 21:31-21:36 | adas_live 5, adas_shadow 9, bump 3, errors 1 |
| 2026-11-03 | fid | `fidrec_20261103_213138` | vc 543 | 21:31-21:36 | - |
| 2026-11-03 | trip | `trip-20261103-213653` | 4.2.0-dev.15 (547) | 21:36-22:12 | adas_live 7, adas_parked 27, lanes_visual 360 |
| 2026-11-03 | fid | `fidrec_20261103_213658` (zip) | vc 547 | 21:36-21:57 | - |
| 2026-11-03 | log | `logs_20261103_213658` (zip) | 4.2.0-dev.15 (547) | 21:41-21:56 | adas_live 2, adas_shadow 15, errors 1 |
| 2026-11-03 | log | `logs_20261103_215701` (zip) | 4.2.0-dev.15 (547) | 21:56-22:13 | adas_live 5, adas_shadow 17, bump 3 |
| 2026-11-03 | fid | `fidrec_20261103_215701` (zip) | vc 547 | 21:57-22:13 | fid_notes 13 |
| 2026-11-03 | log | `logs_20261103_221403` (zip) | 4.2.0-dev.15 (547) | 22:14-22:34 | adas_live 16, adas_shadow 63, bump 2, errors 2, tg_fail 2 |
| 2026-11-03 | fid | `fidrec_20261103_221403` (zip) | vc 547 | 22:14-22:34 | fid_notes 33 |

## 3. Findings by topic

Times are car local time, 2026-11-03 unless a date is given. File names are in `docs/logs/FILES.md` form.

### 3.1 ADAS parked tests (app builds 4.2.0-dev.1, dev.6, dev.11, dev.15)

Five distinct runs (the 10-31 pair is one session) in 4 sessions (each appears twice: trip log + log part). No `refused` or `frames refused` lines in any run
(refusals were not logged before 4.2.0-dev.18; the newest files, 22:34, are still dev.15 so run 5 is still the latest). Two test kinds exist: the single-limit test (limit + camera 300 m) and the limit change test
(`AdasLimitChangePlan`, variants plain/link/class/link+class, limits 70 and 110 alternating every 6 s).
`fires={rc=count}`: rc 0 = frame delivered, rc 11 = gateway refused (services not started or stopped by someone else).

| # | file (trip log) | time | kind | fires | car before -> after |
|---|---|---|---|---|---|
| 1 | trip-20261031-170224 (dev.1) | 10-31 17:02:59 | single-limit, `limit=70 camera=300m` | 0=438 | sign=- -> 70 within 1 s |
| 2 | same | 10-31 17:03:42 | single-limit, `limit=110 camera=300m` | 0=444 | 70 -> 110 within 2 s |
| 3 | trip-20261103-175157 (dev.6) | 19:04:23 | single-limit, `limit=110 camera=300m` | 0=444 | sign=50 -> 50, no change (acc=2) |
| 4 | trip-20261103-194542 (dev.11) | 20:27:22 | limit change test - INVALID | 0=2, 11=1694 | 50 -> 50, no change (acc=2) |
| 5 | trip-20261103-213653 (dev.15) | 21:37:40 | limit change test (`limit change pathID=...`) | 0=1681 (all delivered) | 5 -> 70 at 21:38:48 (acc 2 -> 1) |

Details:
- Run 1 end line says "шлюз SOME/IP недоступний" although fires were all rc 0; run 2 says "завершено". The first-frames rc
  is `[11, 11, 11]` in every run (services not yet started), then `startService(...) rc=0` for 2-4 services.
- The single-limit test (runs 1-3) worked on 10-31 (dev.1) with acc=1, and did not on 11-03 (dev.6, run 3: car stayed at 50, the camera sign) with acc=2.
  Hypothesis, not proven: the car only takes a sign from the app when acc is 1 (ICA/ACC state) - the one successful run on 11-03 (run 5) also shows acc=1 after.
- Run 4 (dev.11, 20:27 session) is invalid: fires 0=2, 11=1694, because the drive-time sender stopped the gateway services (fixed in dev.14).
  "No change" says nothing about the limit change variants.
- Run 5 (dev.15, 21:37) delivered all 1681 frames. The car showed sign=5 before, took nothing in the plain and link variants, switched to 70 at 21:38:48
  during the `class` variant (2 s into the second 70 step, RoadClass=2) and stayed at 70 to the end. 110 was always sent with RoadClass 0
  (omitted by proto3) and was never taken. Only one `car:` line exists (21:38:48).
- The files fetched at ~22:34 (dev.15, trip-20261103-213653 now complete to 22:12:48, logs 215701 and 221403) hold no new parked test: the only `parked test` lines are still run 5.
- Round 2 (dev.16: class and link+class variants, RoadClass 1 and 2, 12 s per value) is pending; no log yet.
- There is no route test: the start line `parked test start route pathID=` does not occur in any log. Three attempts by the owner from the map left no
  trace because refusals were not logged (fixed in 4.2.0-dev.18).

### 3.2 ADAS live sending (from dev.6, 2026-11-03 18:03)

- First "ADAS live switched on" 18:03:38 (dev.6, trip-20261103-175157); off/on again 19:04:14; later builds do not log the switch
  but keep sending (trip-20261103-194542: 118 live lines 19:46..21:23; trip-20261103-213134: 5 lines; log part 213658: first frames 21:42:10).
- Each start logs `first frames rc=[11, 11, 11], services started rc=[0, 0, 0, 0]`, then `send SpdLmtSpeedValue=N ... rc=[0, 0, 0]`.
  From dev.11 a change of the Waze limit is sent as `restart for the new limit A -> B (services restarted=true)` (first at 19:57:52,
  70+ times until 21:23). `clear: no Waze limit` appears when Waze has no limit (e.g. 18:40:16, 18:50:11, 19:04:08, 20:22:10).
- Limits sent: 5, 20, 50, 60, 70, 90, 110 (5 and 20 are odd: 21:23:00 -> 20, 21:23:06 -> 5, 21:32:04 -> 5, tests of the car's low sign range).
- Did the car's sign follow? Measured on 86 sends (first car sign line within 90 s after each send): mostly **no**.
  The car kept its own sign (50, 70, 90, 40, 30 from its camera/map) in the large majority of cases, e.g. sends of 110 at 19:58:59,
  20:03:30, 20:09:10, 20:11:53, 21:12:03 were all answered by sign=50. Clear cases where it did follow: 20:08:34 send 90 -> sign=90 within 1 s
  (then back to 70 and 50 within 25 s); 21:23:00 send 20 -> sign=20 in 3 s; 21:32:04 send 5 -> sign=5 in 2 s (trip-20261103-213134, log 213138);
  and the car stayed at 5 after the next send of 50 at 21:32:27 (episode "waze>lost waze=50 car=5" at 21:34:46).
  Before 18:39 the car showed 90 while Waze said 50 and the app sent 50 (18:07:15, 18:10:31, 18:16:29, 18:23:40): no follow.
- dev.10-15 restarted navigation and the gateway services on every new limit; on 11-03 20:46-21:03 eleven restarts were never followed by the car.
- On the evening of 11-03 the head unit's ADAS view froze (car parked, no surroundings) until the multimedia was restarted. 4.2.0-dev.17 removed the restart
  and never stops the services.
- New in the 21:56-22:34 files (dev.15, no `waze limit change` after 21:34 in the app, restart-per-limit still active):
  - 21:42:10 first frames rc=[11,11,11] and the first send (50) also rc=[11,11,11] (not delivered). Next sends rc=[0,0,0]: 22:05:44 send 50, 22:10:27 restart 50 -> 110 send 110.
    Car sign was "-" the whole time 21:57-22:12 (acc=1 ica=1, mostly standing/slow, `both=0 wazeOnly=111`): no sign to follow, no change after the 50 or 110 sends.
    The 70 left by the parked test (21:38:48) was gone by 21:57.
  - Car restarted the app process 22:14:01 (`service_start: new process`, new pid; the 215701 part ends 22:13:07). 22:14:29 first frames rc=[11,11,11] and send 110 rc=[11,11,11]
    (not delivered, services started only now). Car sign "-" until 22:16:33, then sign=110 (Waze also 110; no send after 22:14:29 until 22:17:57, so probably the car's own value).
  - 22:17:57 restart 110 -> 90, send 90 rc 0: car sign went 110 -> 70 at 22:18:07 (not 90) and **stayed at 70 until the end of the log (22:34)**, through seven more sends:
    22:18:26 send 50, 22:20:27 send 110, 22:24:50 send 90, 22:26:48 send 50, 22:29:36 send 90, 22:29:51 send 110 (all rc 0, each with a service restart). Car acc 2/3, ica 1/3/5 flicker as before.
    Shadow stats at 22:33: 558 samples, 498 with both signs, agree 41 (8%), differ 457, top pairs waze/car 110/70 x235, 50/70 x142, 90/70 x75, 90/110 x5. So the car's sign stayed 70 over the whole drive
    while Waze said 50/90/110: either the car's own map limit, or a 70 stuck from the app's frames (the parked test also ended at 70); the logs cannot tell which.
- Conclusion so far: the live feed is accepted (rc 0) but the car's displayed sign is usually decided by the car itself; following happened
  only in short windows and for unusual values. No evidence in the logs of the car losing its own sign because of the app.

### 3.3 ADAS shadow (Waze limit vs the car's sign)

- Shadow logging (`AdasShadow`) is present from trip 2026-10-31 12:55 (dev.21) on; each ~2 s it logs `car sign=.. sla=.. acc=.. ica=.. waze=..`,
  and a periodic `alive route=... samples=.. agree=..% differ=.. topDiffer(waze/car)=[..]`. the trip log also has `limit episode` and `drive summary` lines
  (37 episodes: waze>waze 14, wazeFirst 8, waze>lost 8, carFirst 4, car>lost 4, car>car 3, waze>both 3).
- Agreement is low: bydmate_logs_20261031_125559 ends with agree 4% (941 of 1111 samples differ, top pairs waze/car 90/50 x469, 90/5 x272, 50/5 x89);
  bydmate_logs_20261031_143016 ends with 29% (90/50 x525, 110/50 x154, 90/70 x118, 90/40 x106); bydmate_logs_20261103_194548 ends with 22%
  (90/50 x284, 110/50 x102). The car most often shows 50 where Waze says 90 or 110 (car sign appears sticky at 50, also 5/10/30/40 appear).
- bydmate_logs_20261103_221403 ends at 8% agreement (110/70 x235, 50/70 x142, 90/70 x75): the car showed a constant 70 from 22:18:07 to 22:34 (see 3.2).
- bydmate_logs_20261103_215701 (21:57-22:12): car sign "-" throughout, `both=0 wazeOnly=111` of 124 samples (route=true, waze 50 then 110).
- Some windows have no car sign at all: bydmate_logs_20261103_154911 ends with `both=0 wazeOnly=1101` (car sign "-" for 42 km, route=true, waze=90).
- `trip-20261103-152252` / `152906`: `drive summary samples=0` at 15:23 and 17:00 (nothing to compare).

### 3.4 Audio channel test (dev.11, trip-20261103-194542 / bydmate_logs_20261103_194548)

- Ran twice, 19:46:41-19:47:19 and 19:47:22-19:48:00 (`start variants=8 voice_ready=false`, so the "voice" was a tone, `voice=tone`).
  Spotify (com.spotify.music) held audio focus (GAIN) throughout.
- During assistant+duck in the first run (19:47:01) the focus stack also held com.android.server.telecom client "AudioFocus_For_Phone_Ring_And_Calls"
  (GAIN_TRANSIENT, flags LOCK) and Spotify showed `loss: LOSS_TRANSIENT`. In the second run (19:47:42) assistant+duck had only Spotify (GAIN, loss none).
- Variants (each with and without focus request, "+duck" = focus granted, "-nofocus" = not requested): media, navigation, assistant, notification.
  Results are identical in both runs: media, navigation and assistant variants `played=true` (durations 2.1/2.5, 2.7/3.0, 3.3/3.6 s);
  notification+duck and notification-nofocus `played=false` (3.9 s, 4.2 s).
- `played` is the player state, not what the driver heard. What the owner heard per variant is **not in the logs** (see open questions).
  The focus-stack dumps ("before/during") are in the file; the ducking of Spotify (`ducked players piids:` list) is empty in all variants: no variant ducked.

### 3.5 Navigation voice

- No line matching `NAVVOICE|NavVoice` exists in any indexed file (all 118). The feature either was not enabled/instrumented in these builds
  or never ran; the voice-related data is only the audio test above (voice_ready=false).
- `NLU|VoiceController` (counts only): no matches in the indexed logs either.

### 3.6 Bump detection / recording (4.2.0-dev.3+, from 2026-11-03 15:29)

- Component `BumpRec` starts a fid recording per 20-minute part: `started bydmate_fidrec_<time>.txt: registered=2883` (registered signals; every part)
  and, when the part is done, `part done` + `<file> to telegram: sent`. Present in all log parts from 15:29 to 22:34 (24 files); 22:14:05 the 215701 recording was sent (`sent`), the 22:14 part ends with `stopped: manual events=808558` (22:34:04).
- Fid dumps carry `#detail` notes (detail windows -8000..+3000 ms) written when a sensor crosses a threshold: top triggers Adas.ADAS_WY_PITCH_RATE (101),
  ADAS_TLA_STRAIGHT_LIGHT_STATUS (78), ADAS_X_AXIS_ACCELERATION (77), ADAS_Y_AXIS_ACCELERATION (53), ADAS_WX_ROLL_RATE (50), Speed.SPEED_BRAKE_S (7).
  In dev.15 parts: 13 notes (21:57 part, 22:07-22:12, incl. 3 `bump? noted` pp 14.1-14.4) and 33 (22:14 part, mostly ADAS_TLA_STRAIGHT_LIGHT_STATUS 0/3 and pitch rate; no `bump? offered`). Note counts per 20-minute dump fall from 83 (15:29 part) to 2-30 later (see `fid_notes` in the timeline).
- `BUMP bump? pp=.. v=.. answer=none`: the prompter asked three times (18:40:41 trip-20261103-175157, 19:54:36 and 21:23:03 trip-20261103-194542); each time
  `answer=none` (no reply from the driver). The 21:23 case also has fid notes `bump? noted` x2 and `bump? offered` x1 (bydmate_fidrec_20261103_210555).
  Peak-to-peak value pp ~22 in all three offers.
- Telegram: recordings were sent per part; two were lost: `bydmate_fidrec_20261103_185211 to telegram: failed for good` (19:13:12).

### 3.7 Lanes / visual reader

- Present in 27 files (almost every trip log), 2026-10-25 17:15 onward. `VISUAL navBarDirection ... icon=N exit=.. name=big_trans_direction_*` lines
  (the arrow read from the Navigator nav bar: icons 1, 5, 11, 2, 25/26/27 with exit numbers) dominate: e.g. 3100 lines for icon=1, 2990 for icon=5.
- `VISUAL laneGuidanceView ... unmatched grid=...` (791 lines): the lane arrow view was read but no template matched.
- `LANES pixels src=tree display=0 container=0,84,960,180 cells=2 ...`: first seen at trip-20261031-103222 (dev.18) 10:39; a lane grid was read (cells=2, arrows `#0/#1`).
  1407 non-empty LANES lines overall; an early `container=- count=0` shape (empty) at 2026-10-30 17:12.
- The largest sessions: trip-20261030-171230 (2696 lines, 17:12-19:49), trip-20261103-175157 (2617), trip-20261103-152906 (2550).

### 3.8 Telegram sending problems

- 16 files with failures. Kinds and examples:
  - `UnknownHostException` / `failed NO_NETWORK` (no network at send time): 10-27 17:40, 10-29 08:30, 10-31 17:03, 11-01 14:15, 11-03 18:14-18:17.
  - `SocketTimeoutException`: 10-27 17:43, 11-03 20:07 and 20:36.
  - `FileNotFoundException` (the file to send was gone/unreadable): 11-01 14:37, 11-03 17:46 and 18:54, 21:41 (`recording to telegram: unreadable`).
  - `HTTP:400` once: 11-03 17:11:14 (bydmate_logs_20261103_170914), `log to telegram: failed HTTP:400`.
  - `failed for good` (AutoTripLog gave up): bydmate_logs_20261103_175203 (18:16:04), _183204 (18:56:06), _185206 (19:14:07); and the fid `_185211` (19:13:12).
- 22:16:56 `recording to telegram: unreadable FileNotFoundException` and 22:17:56 `log to telegram: failed NO_NETWORK` + `AutoBackup telegram rc=io SocketTimeoutException` (bydmate_logs_20261103_221403); 16:56 refers to the recording that was being sent, check the archive for what got lost.
- Consequence: the parts named "failed for good" were probably never delivered (check the archive for them before relying on them).
  Configuration backups are sent unpinned and are never picked up by `fetch.py`.

### 3.9 Errors and crashes

- 21:56-22:34 files: no `E/`, no Exception/FATAL except the Telegram failures above; `FidPush: subscribed=143 ok=123 failed=20` at 22:14 again; no NAVVOICE/NavVoice/AudioChannelTest lines (0 hits in the new files).
- No `FATAL`, `AndroidRuntime` or crash was found in any file (0 hits). `errors` counts are almost all Telegram failures (above).
- Other errors: `E/ActivityManager: Failure starting process com.bydmate.app.waze ... SecurityException: Package com.bydmate.app.waze is currently frozen!`
  (bydmate_logs_20261031_103228, 10:32:18, right after the dev.17 -> dev.18 update); `FidRec dev=1031 ...BYDAutoPanoramaDevice: registered=0/62 err=RemoteException`
  (2026-10-25 11:51 and 10-29 08:27, three times); `E/AbsBYDAutoSettingListener: onDataEventChanged` (10-31 09:35, a system listener, not the app);
  `FidPush: subscribed=143 ok=123 failed=20` at each app start from 17:43 on 11-03 (20 FIDs always fail to subscribe).

## 4. Open questions the logs have not answered

1. Does the car's SLA/ADAS display follow the live Waze limit at all while driving? Cases of following exist (5, 20, 90 once); the dev.16 round 2 (RoadClass 1/2) is pending, but the car's own sign
   usually wins; no test compared the car with the app on a stable stretch with the app sending and the car's own sign deliberately absent.
2. Is `acc=1` (or the ICA/ACC state) the condition under which the car takes the parked-test sign? Only runs 1, 2 and 5 had acc=1 afterwards. (22:14-22:34 drive: acc 2/3 and car sign stuck at 70 despite sends.)
   Run 5 only logged one `car:` line; 110 was sent with RoadClass 0 and never taken - does the car need RoadClass for 110?
3. Why does the car stay at 50 so often against Waze 90/110 (and show 5/10)? Is that the car's own map limit, or a stale value left by the app's earlier frames?
4. Audio test: which of the 8 variants was actually audible, and was Spotify ducked or paused? `played=true` does not say it. The owner's heard-result was not recorded.
   The notification variant `played=false` - is it blocked by the car's audio policy?
5. Navigation voice: no instrumented log at all. When does the feature run and what should be logged?
6. Bump prompter: three offers got `answer=none`; are the thresholds (pp ~22) right, and did the driver see the prompt? No bump was ever confirmed.
7. Lanes: 791 `unmatched` lane-view reads - which lane graphics are missing templates? A screenshot or the grid text would be needed per case.
8. Telegram: why `FileNotFoundException` on files the app itself just wrote (rotation race?) and why `HTTP:400` once?
9. 45 zips (duplicates of same-named .txt) were not indexed; the 2026-10-24/25 logs lack a version line. The parts between 2026-10-25 20:53 and 10-27 08:24
   and 11-01 (11:23-14:15) have no logs; unknown whether the car was off or files were lost.
