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

# Interrupting this script must not leave gradle behind. It did once: Ctrl-C killed the script,
# its gradle daemon kept running, and the next run's R8 step died on
# "classes.dex ... used by another process" - a Windows file lock that looks like a broken build
# and is not one. Kill the child we spawned and stop the daemons on any exit.
gradle_pid=""
cleanup() {
  local rc=$?
  [ -n "$gradle_pid" ] && kill "$gradle_pid" 2>/dev/null
  echo "  (interrupted - stopping gradle so the next run does not hit a locked classes.dex)"
  ./gradlew --stop >/dev/null 2>&1
  exit $rc
}
trap cleanup INT TERM

# Suites allowed to fail on this host. It is EMPTY, and it should stay that way: it began as ten
# suites and 146 failures, and every single one turned out to be a real problem hiding behind the
# label -- an unguarded start-up coroutine that could kill the app on the car, tests asserting
# against the wrong product flavour, a mock server URL that reverse-resolved to a Docker hosts
# entry. The last two, which genuinely cannot be staged on Windows, now skip themselves with
# JUnit assumptions instead, which is honest and needs no list.
#
# Before adding anything here: find the cause. "Fails on this machine" is almost never it. If a
# test really depends on the host, express that as an Assume in the test.
KNOWN_BROKEN=''

# Empty means "nothing is excused" - never let an empty pattern reach grep, it matches everything
# and would silently pass a completely red run.
is_known_broken() { [ -n "$KNOWN_BROKEN" ] && echo "$1" | grep -qE "$KNOWN_BROKEN"; }

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
# Keep the output: "FAIL: release build" on its own sends you looking for a compile error that
# is not there. The one that actually happened was a Windows file lock on classes.dex left by a
# previous interrupted run.
if ! ./gradlew :app:assembleWazeRelease -q > "$WORK/gradle-build.log" 2>&1; then
  echo "FAIL: release build"
  grep -E '^e: |^> |What went wrong|Caused by' "$WORK/gradle-build.log" | head -6 | sed 's/^/  /'
  echo "  full log: $WORK/gradle-build.log"
  fail=1
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
    elif is_known_broken "$name"; then echo "  ~    $name ($n, known-broken here)"
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
    is_known_broken "$name" || unexpected="$unexpected $name($n)"
  fi
done
echo "tests=$total failures=$bad"
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
    echo "$out" > "$WORK/scenarios.log"
    # Readiness lines too, not just the verdicts. A run where the app never bound printed 17
    # identical "got: " failures and read as broken lane code; the two lines that said the app
    # was not up had been filtered out.
    echo "$out" | grep -E '^(a11y connected|HUD active)' | sed 's/^/  /'
    echo "$out" | grep -E '^\s+(ok|FAIL)' | sed 's/^/  /'
    if echo "$out" | grep -q 'FAIL'; then
      echo "FAIL: emulator scenarios"
      echo "  full output: $WORK/scenarios.log"
      fail=1
    else
      echo "ok: all scenarios green"
    fi
  fi
fi

printf '\n=== verdict: %s\n' "$([ $fail = 0 ] && echo 'ready to push' || echo 'DO NOT PUSH')"
exit $fail
