#!/usr/bin/env bash
# Everything that has to pass before a push. Run it, read the verdict, then push.
#
#   scripts/preflight.sh              # build + unit tests + emulator scenarios
#   scripts/preflight.sh --no-emu     # skip the emulator (it must be running otherwise)
#
# Why this exists: three regressions reached the car because a change was pushed after a
# targeted test run and no emulator pass. The emulator catches what unit tests cannot - the
# accessibility read, the lane strip, and what actually leaves for the panel.
#
# Why it has a watchdog: a single test blocking forever (MockWebServer.takeRequest with no
# timeout) silently ate several full-suite runs, and each one looked like "just slow". The
# unit-test step now reports progress every minute and, when nothing moves, names the test that
# is stuck instead of hanging until someone gives up.
set -u

cd "$(dirname "$0")/.." || exit 2
export JAVA_HOME="${JAVA_HOME:-C:\\Program Files\\Eclipse Adoptium\\jdk-17.0.20.101-hotspot}"
SIM="${SIM:-../dilink5-sim}"
SERIAL="${SERIAL:-emulator-5554}"
P="${P:-com.bydmate.app.waze}"
ADB="${ADB:-/c/Android/Sdk/platform-tools/adb.exe}"
JSTACK="${JSTACK:-/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin/jstack.exe}"
WORK="${TMPDIR:-/tmp}/bydmate-preflight"
RESULTS="app/build/test-results/testWazeDebugUnitTest"

# Minutes without any test progress before the run is declared stuck.
STALL_MINUTES="${STALL_MINUTES:-5}"

RUN_EMU=1
[ "${1:-}" = "--no-emu" ] && RUN_EMU=0

mkdir -p "$WORK"
fail=0
step() { printf '\n=== %s\n' "$1"; }

# Suites that cannot pass on a Windows host. Keep this list honest and short: it started as ten
# suites and 146 failures, and all but these turned out to be real problems hiding behind it --
# an unguarded start-up coroutine, tests asserting against the wrong product flavour, and a mock
# server URL that reverse-resolved to a Docker hosts entry. Before adding anything here, find the
# cause; "fails on this machine" is usually not it.
#
# What is left: SettingsViewModelTest's two W6-F4 dumpFids cases. One needs a non-writable
# Download directory and File.setWritable(false) is a no-op on Windows; the other asserts a
# FileProvider root against a POSIX path and gets a C:\ path instead. Both should hold on Linux;
# being checked under WSL, and if that works the gate moves there and this list goes away.
KNOWN_BROKEN='SettingsViewModelTest'

# Names the test class each live JVM is executing, so a stall points at a culprit rather than a
# shrug. Reads every java process because the test worker is not always the daemon.
stuck_where() {
  [ -x "$JSTACK" ] || { echo "  (jstack not found at $JSTACK)"; return; }
  local pids
  pids=$(tasklist //FI "IMAGENAME eq java.exe" //FO CSV //NH 2>/dev/null | tr -d '"' | cut -d, -f2)
  for pid in $pids; do
    "$JSTACK" "$pid" > "$WORK/stack-$pid.txt" 2>/dev/null || continue
    local frame
    frame=$(grep -oE 'com\.bydmate\.app\.[A-Za-z0-9_.]*Test[A-Za-z0-9_.]*\([^)]*\)' "$WORK/stack-$pid.txt" | head -1)
    [ -n "$frame" ] && echo "  pid $pid is in: $frame"
  done
  echo "  full stacks: $WORK/stack-<pid>.txt"
}

step "release build"
if ./gradlew :app:assembleWazeRelease -q 2>&1 | grep -qE '^e: |FAILURE'; then
  echo "FAIL: release build"; fail=1
else
  echo "ok: $(ls -1 app/build/outputs/apk/waze/release/*.apk 2>/dev/null | tail -1)"
fi

step "unit tests (each suite reported as it finishes, stall limit ${STALL_MINUTES}m)"
rm -rf "$RESULTS" 2>/dev/null
./gradlew :app:testWazeDebugUnitTest -q > "$WORK/gradle-test.log" 2>&1 &
gradle_pid=$!
stall=0
last_sig=""
seen="$WORK/seen-suites.txt"
: > "$seen"
while kill -0 "$gradle_pid" 2>/dev/null; do
  sleep 15
  # Announce every suite that finished since the last look, with its failure count, so the run
  # is readable while it happens instead of only at the end.
  for f in "$RESULTS"/*.xml; do
    [ -e "$f" ] || continue
    name=$(basename "$f" .xml | sed 's/^TEST-//')
    grep -qxF "$name" "$seen" && continue
    echo "$name" >> "$seen"
    n=$(grep -c '<failure' "$f")
    if [ "$n" = "0" ]; then echo "  ok   $name"
    elif echo "$name" | grep -qE "$KNOWN_BROKEN"; then echo "  ~    $name ($n, known-broken here)"
    else echo "  FAIL $name ($n)"; fi
  done
  suites=$(ls -1 "$RESULTS"/*.xml 2>/dev/null | wc -l | tr -d ' ')
  sig="$suites:$(stat -c %Y "$RESULTS/binary/output.bin" 2>/dev/null || echo 0)"
  if [ "$sig" = "$last_sig" ]; then
    stall=$((stall + 1))
    # Four 15 s ticks make a minute; only speak up once a minute of silence.
    [ $((stall % 4)) = 0 ] && echo "  $(date +%H:%M:%S) no progress for $((stall / 4))m (suites=$suites)"
  else
    stall=0; last_sig="$sig"
  fi
  if [ "$stall" -ge $((STALL_MINUTES * 4)) ]; then
    echo "FAIL: no test progress for ${STALL_MINUTES} minutes - the run is stuck"
    stuck_where
    kill "$gradle_pid" 2>/dev/null
    ./gradlew --stop >/dev/null 2>&1
    fail=1
    break
  fi
done

total=0; bad=0; unexpected=""
for f in "$RESULTS"/*.xml; do
  [ -e "$f" ] || continue
  t=$(grep -oE 'tests="[0-9]+"' "$f" | head -1 | grep -oE '[0-9]+')
  n=$(grep -c '<failure' "$f")
  total=$((total + ${t:-0})); bad=$((bad + n))
  if [ "$n" != "0" ]; then
    name=$(basename "$f" .xml | sed 's/^TEST-//')
    echo "$name" | grep -qE "$KNOWN_BROKEN" || unexpected="$unexpected $name($n)"
  fi
done
echo "tests=$total failures=$bad (known-broken suites excluded from the verdict)"
if [ -n "$unexpected" ]; then
  echo "FAIL: unexpected failures:$unexpected"; fail=1
elif [ "$total" -gt 0 ]; then
  echo "ok: no unexpected failures"
fi

if [ "$RUN_EMU" = "1" ]; then
  step "emulator scenarios"
  if ! "$ADB" devices 2>/dev/null | grep -q "^$SERIAL[[:space:]]*device"; then
    echo "FAIL: $SERIAL is not running. Start it with:"
    echo "  /c/Android/Sdk/emulator/emulator.exe -avd AAOS_DiLink_A13 -gpu host"
    echo "  (-gpu host is required, the secondary display windows do not appear otherwise)"
    fail=1
  else
    ./gradlew :app:assembleWazeDebug -Pbydmate.emulatorAbi=true -q >/dev/null 2>&1
    apk=$(ls -1 app/build/outputs/apk/waze/debug/*.apk 2>/dev/null | tail -1)
    "$ADB" -s "$SERIAL" install -r -g --user 10 "$(cygpath -w "$apk" 2>/dev/null || echo "$apk")" >/dev/null 2>&1
    out=$(cd "$SIM" && SERIAL="$SERIAL" P="$P" DIALECT=arhud bash scripts/bydmate-scenarios.sh 2>&1)
    echo "$out" | grep -E '^\s+(ok|FAIL)' | sed 's/^/  /'
    if echo "$out" | grep -q 'FAIL'; then echo "FAIL: emulator scenarios"; fail=1; else echo "ok: all scenarios green"; fi
  fi
fi

printf '\n=== verdict: %s\n' "$([ $fail = 0 ] && echo 'ready to push' || echo 'DO NOT PUSH')"
exit $fail
