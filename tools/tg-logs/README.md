# Diagnostic files from the car, through the bot

The car sends a FID dump or a log to the owner's Telegram bot and pins it silently
(Settings → «Дамп FID», «Журнал останньої поїздки → Надіслати в Telegram», the recorded log).
`fetch.py` downloads every pinned file and unpins it, so nobody has to forward anything.

```sh
BYDMATE_TG_BOT_TOKEN=... BYDMATE_TG_CHAT_ID=... python3 tools/tg-logs/fetch.py /tmp/car-logs
```

- `BYDMATE_TG_BOT_TOKEN` — the bot's token. Keep it in the environment's secrets, never in a
  chat or in the repository.
- `BYDMATE_TG_CHAT_ID` — the linked chat. Without it the script takes the newest chat (private or a
  group) from the bot's pending updates, which Telegram keeps for 24 hours.
- `--keep` downloads the latest pinned file and leaves it pinned.

Limits: the Bot API serves files up to 20 MB to a bot (the app uploads up to 50 MB). The script
only reads, downloads and unpins; it never deletes a message. Configuration backups are sent
unpinned and are never picked up.

## Index of the fetched files

`index.py` builds a searchable index of a directory of fetched files, so a reader looks at the
index first and then greps only the right file (stdlib only, nothing is extracted to disk):

```sh
python3 -I -B tools/tg-logs/index.py <ARCHIVE_DIR> <OUT_DIR>   # writes INDEX.md and index.json
python3 -I -B tools/tg-logs/index.py --selftest
```

- `INDEX.md`: a table of files by date (kind, app version, time span, categories with counts), a
  per-category list of which files contain it, and a "Parked test runs" table (test, start/end,
  `fires`, car before/after, refusals, changes of the car's sign/sla).
- `index.json`: the same as records, with up to 12 sample lines per category.
- Categories: ADAS parked/live/shadow, audio test, nav voice, bump, lanes, Telegram failures,
  errors, voice (count only). Fid recorder dumps get metadata and `#`/bump notes only.
- Samples are scrubbed: coordinates become `<geo>`, speeds above 130 become `<fast>`. A `.zip`
  is skipped when a `.txt` of the same name is at least as large. Run the index before the
  archive is used for answers, and keep `OUT_DIR` outside the repository unless it was reviewed.
