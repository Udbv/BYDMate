#!/usr/bin/env python3
"""Download the diagnostic files the car pinned in the BYDMate bot chat, oldest last.

The app sends a FID dump or a log with sendDocument and pins it silently (TelegramBackupSink.
sendDocument with pin = true). A bot cannot list the messages it sent, but getChat returns the chat's latest
pinned message with its file. So: take the pinned file, download it, unpin it, and the next pinned
file surfaces, until none is left. Nothing is deleted from the chat.

Environment:
  BYDMATE_TG_BOT_TOKEN  the bot token (required; never printed)
  BYDMATE_TG_CHAT_ID    the chat the app is linked to; when unset, the newest chat (private or a
                        group) in the bot's pending updates is used (Telegram keeps them for 24 h)

Usage: fetch.py [OUT_DIR] [--keep]   (--keep leaves the files pinned)
"""
import json
import os
import ssl
import sys
import time
import urllib.parse
import urllib.request

API = "https://api.telegram.org"


def ssl_context():
    cafile = os.environ.get("SSL_CERT_FILE") or os.environ.get("REQUESTS_CA_BUNDLE")
    return ssl.create_default_context(cafile=cafile) if cafile else ssl.create_default_context()


CTX = ssl_context()


def call(token, method, **params):
    data = urllib.parse.urlencode(params).encode() if params else None
    try:
        with urllib.request.urlopen(f"{API}/bot{token}/{method}", data=data, context=CTX, timeout=60) as r:
            body = json.load(r)
    except urllib.error.HTTPError as e:
        body = json.load(e)
    if not body.get("ok"):
        raise RuntimeError(f"{method}: {body.get('error_code')} {body.get('description')}")
    return body["result"]


def find_chat(token):
    updates = call(token, "getUpdates", offset=-100, limit=100)
    for update in reversed(updates):
        # A message, or the bot being added to / promoted in a group.
        event = update.get("message") or update.get("my_chat_member") or {}
        chat = event.get("chat") or {}
        if chat.get("type") in ("private", "group", "supergroup"):
            return chat["id"]
    raise RuntimeError("no chat: set BYDMATE_TG_CHAT_ID or send the bot any message")


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    keep = "--keep" in sys.argv
    out = args[0] if args else "."
    token = os.environ.get("BYDMATE_TG_BOT_TOKEN")
    if not token:
        sys.exit("BYDMATE_TG_BOT_TOKEN is not set")
    chat_id = os.environ.get("BYDMATE_TG_CHAT_ID") or find_chat(token)
    os.makedirs(out, exist_ok=True)

    seen = set()
    while True:
        pinned = call(token, "getChat", chat_id=chat_id).get("pinned_message")
        # Right after an unpin getChat may still report that message for a while (seen >5 s).
        for _ in range(20):
            if not pinned or pinned["message_id"] not in seen:
                break
            time.sleep(1)
            pinned = call(token, "getChat", chat_id=chat_id).get("pinned_message")
        document = (pinned or {}).get("document")
        if not document or pinned["message_id"] in seen:
            break
        seen.add(pinned["message_id"])
        path = call(token, "getFile", file_id=document["file_id"])["file_path"]
        name = os.path.basename(document.get("file_name") or path)
        target = os.path.join(out, name)
        with urllib.request.urlopen(f"{API}/file/bot{token}/{path}", context=CTX, timeout=120) as r, \
                open(target, "wb") as f:
            f.write(r.read())
        print(f"{target} ({document.get('file_size', 0) // 1024} KB, message {pinned['message_id']})")
        if keep:
            break
        call(token, "unpinChatMessage", chat_id=chat_id, message_id=pinned["message_id"])
    if not seen:
        print("nothing pinned")


if __name__ == "__main__":
    main()
