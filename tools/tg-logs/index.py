#!/usr/bin/env python3
"""Index the car's diagnostic files (fetched by fetch.py) so a reader can grep the right one fast.

Usage: python3 -I -B tools/tg-logs/index.py <ARCHIVE_DIR> <OUT_DIR>
       python3 -I -B tools/tg-logs/index.py --selftest
Writes OUT_DIR/index.json and OUT_DIR/INDEX.md. Stdlib only, deterministic, never extracts to disk.
Sample lines are scrubbed: geo values -> <geo>, speeds above 130 -> <fast>.
"""
import io, json, os, re, sys, tempfile, zipfile

N_SAMPLES, MAXLEN = 12, 220
CATS = [  # (name, regex on " " + text after the timestamp, keep samples)
    ("adas_parked", r"parked test (start|end|now:|car:|refused|route|frames refused|first frames)", True),
    ("adas_live", r"ADAS live (switched|first frames|send|clear|restart)", True),
    ("adas_shadow", r"AdasShadow.*car sign=|limit episode|drive summary|alive route=", True),
    ("audio_test", r"AudioChannelTest", True),
    ("nav_voice", r"NAVVOICE|NavVoice", True),
    ("bump", r"BUMP|BumpRec|BumpPrompter", True),
    ("lanes_visual", r"LANES|VISUAL|PANEL lanes", True),
    ("tg_fail", r"(TgReport|AutoBackup|AutoTripLog).*(failed|rc=io|HTTP:)", True),
    ("errors", r" E/|FATAL|AndroidRuntime|Exception", True),
    ("voice", r"NLU|VoiceController", False),  # counts only
]
CATRE = [(n, re.compile(r), s) for n, r, s in CATS]
NOTE = re.compile(r"#|bump", re.I)  # the only fidrec lines we look at
LOGLINE = re.compile(r"^(\d\d-\d\d) (\d\d:\d\d:\d\d(?:\.\d+)?) (.*)$")
TRIPLINE = re.compile(r"^(\d\d:\d\d:\d\d(?:\.\d+)?) (.*)$")
GEO = [re.compile(r"-?\d{1,3}\.\d{4,}\s*[,;/ ]\s*-?\d{1,3}\.\d{4,}"),
       re.compile(r"(?i)\b(lat|lon|lng|latitude|longitude)\w*\s*[=:]\s*\S+"),
       re.compile(r"(?i)\blocation\w*\s*[=:]\s*\S+")]
SPEED = re.compile(r"(?i)\b((?:speed|spd|vel)\w*\s*[=:]\s*)(\d+(?:\.\d+)?)")
TEST_START = re.compile(r"parked test start (.*?)(?: car before:|$)")
KV = re.compile(r"sign=(\S+) sla=(\S+)")


def scrub(s):
    for g in GEO:
        s = g.sub("<geo>", s)
    s = SPEED.sub(lambda m: m.group(1) + ("<fast>" if float(m.group(2)) > 130 else m.group(2)), s)
    return s if len(s) <= MAXLEN else s[:MAXLEN - 1] + "…"


def kind_of(name):
    if name.startswith("bydmate_logs_"): return "logs"
    if name.startswith("trip-"): return "trip"
    if name.startswith("bydmate_fidrec_"): return "fidrec"
    return None


def read_files(root):
    """Yield (name, text, size) for each usable file; zips read in memory, skipped if a bigger/equal .txt exists."""
    found = {}
    for d, _, fs in os.walk(root):
        for f in fs:
            found.setdefault(f, os.path.join(d, f))
    for f in sorted(found):
        p, stem, ext = found[f], os.path.splitext(f)[0], os.path.splitext(f)[1].lower()
        if not kind_of(f): continue
        if ext in (".txt", ".log"):
            data = open(p, "rb").read()
        elif ext == ".zip":
            try:
                with zipfile.ZipFile(p) as z:
                    mem = [i for i in z.infolist() if not i.is_dir()]
                    if not mem: continue
                    info = mem[0]
                    twin = found.get(stem + ".txt")
                    if twin and os.path.getsize(twin) >= info.file_size: continue
                    data = z.read(info)
            except zipfile.BadZipFile:
                continue
        else:
            continue
        yield f, data.decode("utf-8", "replace"), len(data)


def name_date(name):
    m = re.search(r"(\d{4})(\d\d)(\d\d)[_-](\d\d)(\d\d)(\d\d)", name)
    return ("%s-%s-%s" % m.group(1, 2, 3), "%s:%s:%s" % m.group(4, 5, 6)) if m else ("", "")


def parse_run_end(t, msg, run):
    run["end"] = t
    m = re.search(r"fires=\{([^}]*)\}", msg)
    run["fires"] = dict(p.split("=", 1) for p in m.group(1).split(", ") if "=" in p) if m else {}
    m = re.search(r"car after: (.*)$", msg)
    run["car_after"] = m.group(1) if m else None
    m = re.search(r"parked test end:?\s*(.*?)(?: fires=|$)", msg)
    run["end_note"] = scrub(m.group(1)) if m and m.group(1) else None


def index_file(name, text, size):
    kind = kind_of(name)
    lines = text.splitlines()
    ndate, ntime = name_date(name)
    rec = {"name": name, "kind": kind, "date": ndate, "name_time": ntime, "size": size,
           "lines": len(lines), "versionName": None, "versionCode": None,
           "first": None, "last": None, "categories": {}, "parked_runs": []}
    year = ndate[:4]
    head = "\n".join(lines[:200])
    m = re.match(r"BYDMate (\S+) \((\d+)\)", lines[0]) if lines else None
    m = m or re.search(r"app: (\S+) \((\d+)\)", head)
    if m: rec["versionName"], rec["versionCode"] = m.group(1), m.group(2)
    m = re.search(r"versionCode=(\d+)", head)
    if kind == "fidrec" and m: rec["versionCode"] = m.group(1)
    m = re.search(r"^devices=(.*)$", head, re.M)
    if kind == "fidrec" and m: rec["devices"] = m.group(1).strip()
    cur, last_car, dated = None, None, ndate
    for ln in lines:
        if kind == "logs":
            m = LOGLINE.match(ln)
            if not m: continue
            stamp, t, msg = "%s-%s %s" % (year, m.group(1), m.group(2)), m.group(2), m.group(3)
        else:
            m = TRIPLINE.match(ln)
            if not m: continue
            stamp, t, msg = "%s %s" % (dated, m.group(1)), m.group(1), m.group(2)
        rec["first"] = rec["first"] or stamp
        rec["last"] = stamp
        if kind == "fidrec" and not NOTE.search(msg): continue
        text_ = " " + msg
        cats = [("fid_notes", True)] if kind == "fidrec" else [(n, s) for n, rx, s in CATRE if rx.search(text_)]
        for n, keep in cats:
            c = rec["categories"].setdefault(n, {"count": 0, "first": t, "last": t, "samples": []})
            c["count"] += 1
            c["last"] = t
            if keep and len(c["samples"]) < N_SAMPLES: c["samples"].append(scrub(ln))
        if "parked test" not in msg: continue
        ms = TEST_START.search(msg)
        if ms:
            if cur: cur.setdefault("end", None); rec["parked_runs"].append(cur)
            b = re.search(r"car before: (.*)$", msg)
            test = ms.group(1).strip()
            cur = {"test": re.sub(r"\s+pathID=\d+", " pathID", test)[:80], "start": t, "end": None,
                   "fires": {}, "car_before": b.group(1) if b else None, "car_after": None,
                   "refusals": 0, "car_changes": []}
            last_car = b.group(1) if b else None
        elif cur is None:
            continue
        elif re.search(r"parked test (refused|frames refused)", msg):
            cur["refusals"] += 1
        elif re.search(r"parked test car:", msg):
            v = msg.split("car:", 1)[1].strip()
            if v != last_car: cur["car_changes"].append({"time": t, "car": v})
            last_car = v
        elif re.search(r"parked test end", msg):
            parse_run_end(t, msg, cur)
            rec["parked_runs"].append(cur)
            cur = None
    if cur: rec["parked_runs"].append(cur)
    return rec


def build(archive):
    recs = [index_file(*f) for f in read_files(archive)]
    recs.sort(key=lambda r: (r["date"], r["name_time"], r["name"]))
    return recs


def render_md(recs):
    o = ["# Log archive index", "",
         "Generated by tools/tg-logs/index.py. Times are the car's local time. Grep the named file for the category regex (see README).", "",
         "## Files (by date/time)", "", "| file | kind | version | time span | lines | categories (count) |", "|---|---|---|---|---|---|"]
    for r in recs:
        ver = "%s (%s)" % (r["versionName"] or "?", r["versionCode"] or "?")
        cats = ", ".join("%s %d" % (k, v["count"]) for k, v in sorted(r["categories"].items())) or "-"
        o.append("| %s | %s | %s | %s .. %s | %d | %s |" % (r["name"], r["kind"], ver, r["first"] or "-", r["last"] or "-", r["lines"], cats))
    o += ["", "## By category", ""]
    for n, _, _ in CATS + [("fid_notes", "", 0)]:
        hit = [(r, r["categories"][n]) for r in recs if n in r["categories"]]
        if not hit: continue
        o += ["### %s (%d files)" % (n, len(hit)), ""]
        o += ["- %s: %d, %s..%s" % (r["name"], c["count"], c["first"], c["last"]) for r, c in hit] + [""]
    o += ["## Parked test runs", "", "| file | test | start | end | fires | car before | car after | refusals | car changes |", "|---|---|---|---|---|---|---|---|---|"]
    for r in recs:
        for p in r["parked_runs"]:
            fires = " ".join("%s=%s" % kv for kv in sorted(p["fires"].items())) or "-"
            ch = " > ".join("%s %s" % (c["time"][:8], (KV.search(c["car"]) or [None])[0] or c["car"][:30]) for c in p["car_changes"]) or "-"
            cell = lambda s: (s or "-").replace("|", "/")
            o.append("| %s | %s | %s | %s | %s | %s | %s | %d | %s |" % (
                r["name"], cell(p["test"]), p["start"], p["end"] or "-", fires,
                cell(p["car_before"])[:60], cell(p["car_after"])[:60], p["refusals"], cell(ch)))
    return "\n".join(o) + "\n"


def run(archive, out):
    recs = build(archive)
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "index.json"), "w", encoding="utf-8") as f:
        json.dump(recs, f, ensure_ascii=False, indent=1, sort_keys=True)
    with open(os.path.join(out, "INDEX.md"), "w", encoding="utf-8") as f:
        f.write(render_md(recs))
    print("indexed %d files -> %s" % (len(recs), out))


def selftest():
    logs = "\n".join([
        "BYDMate 9.9.9 (999): automatic trip log, started 2026-11-03 18:00",
        "11-03 18:00:01.000 I/AdasShadow(1): parked test start limit=70 camera=300m car before: sign=- sla=- offset=9",
        "11-03 18:00:02.000 I/AdasShadow(1): parked test car: sign=70 sla=70 offset=9",
        "11-03 18:00:03.000 I/AdasShadow(1): parked test car: sign=70 sla=70 offset=9",
        "11-03 18:00:04.000 I/AdasShadow(1): parked test end: ok fires={0=438, 11=2} car after: sign=70 sla=70",
        "11-03 18:00:05.000 E/Foo(1): boom lat=50.123456 lon=30.654321 speed=150",
        "11-03 18:00:06.000 I/NLU(1): x", "11-03 18:00:07.000 I/NLU(1): y",
        "11-03 18:00:08.000 I/AdasShadow(1): parked test start route pathID=77 car before: sign=70 sla=70", ""])
    trip = "BYDMate trip debug log\nopened: 2026-11-03 17:00:00  reason: x\napp: 1.2.3 (12)  flavor: waze\n17:53:46.197 ADAS limit episode start\n17:53:47.000 BUMP detected\n"
    with tempfile.TemporaryDirectory() as d, tempfile.TemporaryDirectory() as o:
        open(os.path.join(d, "bydmate_logs_20261103_180000.txt"), "w").write(logs)
        with zipfile.ZipFile(os.path.join(d, "bydmate_logs_20261103_180000.zip"), "w") as z:
            z.writestr("bydmate_logs_20261103_180000.txt", logs)  # same content: must be skipped
        open(os.path.join(d, "trip-20261103-170000.log"), "w").write(trip)
        run(d, o)
        recs = json.load(open(os.path.join(o, "index.json")))
        assert [r["name"] for r in recs] == ["trip-20261103-170000.log", "bydmate_logs_20261103_180000.txt"], recs
        t, l = recs
        assert (t["versionName"], t["versionCode"]) == ("1.2.3", "12")
        assert t["categories"]["adas_shadow"]["count"] == 1 and t["categories"]["bump"]["count"] == 1
        assert (l["versionName"], l["versionCode"]) == ("9.9.9", "999")
        assert l["categories"]["adas_parked"]["count"] == 5 and l["categories"]["voice"]["count"] == 2
        assert l["categories"]["voice"]["samples"] == []
        assert l["categories"]["errors"]["samples"][0].endswith("boom <geo> <geo> speed=<fast>"), l["categories"]["errors"]
        r1, r2 = l["parked_runs"]
        assert r1["start"] == "18:00:01.000" and r1["end"] == "18:00:04.000" and r1["fires"] == {"0": "438", "11": "2"}
        assert r1["car_before"].startswith("sign=- sla=-") and len(r1["car_changes"]) == 1
        assert r1["car_after"].startswith("sign=70") and r2["end"] is None and r2["test"].startswith("route")
        assert "Parked test runs" in open(os.path.join(o, "INDEX.md")).read()
    print("selftest ok")


if __name__ == "__main__":
    a = sys.argv[1:]
    if a == ["--selftest"]: selftest()
    elif len(a) == 2: run(*a)
    else: sys.exit(__doc__)
