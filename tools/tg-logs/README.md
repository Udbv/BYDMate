# Diagnostic files from the car, through the bot

The car sends a FID dump or a log to the owner's Telegram bot and pins it silently
(Settings → «Дамп FID», «Журнал останньої поїздки → Надіслати в Telegram», the recorded log).
`fetch.py` downloads every pinned file and unpins it, so nobody has to forward anything.

```sh
BYDMATE_TG_BOT_TOKEN=... BYDMATE_TG_CHAT_ID=... python3 tools/tg-logs/fetch.py /tmp/car-logs
```

- `BYDMATE_TG_BOT_TOKEN` — the bot's token. Keep it in the environment's secrets, never in a
  chat or in the repository.
- `BYDMATE_TG_CHAT_ID` — the linked chat. Without it the script takes the newest private chat
  from the bot's pending updates, which Telegram keeps for 24 hours.
- `--keep` downloads the latest pinned file and leaves it pinned.

Limits: the Bot API serves files up to 20 MB to a bot (the app uploads up to 50 MB). The script
only reads, downloads and unpins; it never deletes a message. Configuration backups are sent
unpinned and are never picked up.
