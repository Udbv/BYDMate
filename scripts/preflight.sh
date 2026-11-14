#!/usr/bin/env bash
# Everything that has to pass before a push. Run it, read the verdict, then push.
#
#   scripts/preflight.sh              # release build, detekt, lint, unit tests, emulator scenarios
#   scripts/preflight.sh --no-emu     # skip the emulator (it must be running otherwise)
#   PREFLIGHT_TESTS=windows scripts/preflight.sh   # unit tests on Windows instead of WSL
#
# Why this exists: three regressions reached the car because a change was pushed after a
# targeted test run and no emulator pass. The emulator catches what unit tests cannot - the
# accessibility read, the lane strip, and what actually leaves for the panel. detekt and lint run
# here because CI runs them: a PR once passed this script and then failed CI on three detekt rules.
#
# Why it has a watchdog: a single test blocking forever (MockWebServer.takeRequest with no
# timeout) silently ate several full-suite runs, and each one looked like "just slow". The
# unit-test step now reports progress every minute and, when nothing moves, names the test that
# is stuck instead of hanging until someone gives up.
#
# Why it runs things side by side: done one after another it took ~20 minutes. The parts do not
# depend on each other, so they now overlap:
#   WSL      copy of the tree -> unit tests, several workers, no coverage agent
#   Windows  debug APK -> (emulator scenarios) and, meanwhile, release build + detekt + lint in
#            one gradle run, whose tasks gradle executes in parallel (configuration cache)
# The unit tests run in WSL because Linux is what CI and the head unit run; under Windows they
# were also the slowest part. With no usable WSL they run on Windows after the build, never at the
# same time as another gradle run in this tree. Measurements: docs/guides/running-tests-under-wsl.md.
set -u

cd "$(dirname "$0")/.." || exit 2
ON_LINUX=0
[ "$(uname -s)" = "Linux" ] && ON_LINUX=1
if [ "$ON_LINUX" = 1 ]; then
  export JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk17}"
  JSTACK="${JSTACK:-$JAVA_HOME/bin/jstack}"
else
  export JAVA_HOME="${JAVA_HOME:-C:\\Program Files\\Eclipse Adoptium\\jdk-17.0.20.101-hotspot}"
  JSTACK="${JSTACK:-/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin/jstack.exe}"
fi
SIM="${SIM:-../dilink5-sim}"
SERIAL="${SERIAL:-emulator-5554}"
P="${P:-com.bydmate.app.waze}"
ADB="${ADB:-/c/Android/Sdk/platform-tools/adb.exe}"
WORK="${TMPDIR:-/tmp}/bydmate-preflight"
RESULTS="app/build/test-results/testWazeDebugUnitTest"
# Where the unit tests run: wsl (default; falls back to windows when WSL is not set up) or windows.
PREFLIGHT_TESTS="${PREFLIGHT_TESTS:-wsl}"
WSL_DISTRO="${WSL_DISTRO:-Ubuntu-26.04}"
# The WSL copy of this tree, relative to the WSL home. One per checkout, so two worktrees never
# share a build directory.
WSL_DIR="${WSL_DIR:-bydmate-preflight-$(basename "$PWD")}"
# Test workers. Every worker is a Robolectric JVM with a 2 GB heap; 4 was the fastest measured
# on the 12-thread dev machine while the release build and lint shared it.
TEST_FORKS="${TEST_FORKS:-4}"
# The coverage agent instruments every class the tests load; it cost about a third of the test
# time. Coverage is a local report (koverHtmlReportDebug), not something this gate checks.
TEST_FLAGS="-Pbydmate.noCoverage -Pbydmate.testForks=$TEST_FORKS"

# Minutes without any test progress before the run is declared stuck.
STALL_MINUTES="${STALL_MINUTES:-5}"

RUN_EMU=1
MODE=all
for arg in "$@"; do
  case "$arg" in
    --no-emu) RUN_EMU=0 ;;
    # Internal: only the unit-test step, here. The WSL side of a normal run is this.
    --tests-only) MODE=tests ;;
    *) echo "unknown option: $arg"; exit 2 ;;
  esac
done

mkdir -p "$WORK"
fail=0
t0=$(date +%s)
step() { printf '\n=== %s\n' "$1"; }
took() { echo "  ($(( $(date +%s) - $1 ))s)"; }

# Interrupting this script must not leave gradle behind. It did once: Ctrl-C killed the script,
# its gradle daemon kept running, and the next run's R8 step died on
# "classes.dex ... used by another process" - a Windows file lock that looks like a broken build
# and is not one. Kill the children we spawned and stop the daemons on any exit.
gradle_pid=""
bg_pids=""
cleanup() {
  local rc=$?
  for pid in $gradle_pid $bg_pids; do kill "$pid" 2>/dev/null; done
  echo "  (interrupted - stopping gradle so the next run does not hit a locked classes.dex)"
  ./gradlew --stop >/dev/null 2>&1
  if [ "$MODE" = all ] && [ "$PREFLIGHT_TESTS" = wsl ]; then
    MSYS_NO_PATHCONV=1 wsl.exe -d "$WSL_DISTRO" -- bash -lc "cd ~/$WSL_DIR 2>/dev/null && ./gradlew --stop" >/dev/null 2>&1
  fi
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
# shrug. Reads every java process because the test worker is not always the daemon. On Linux only
# the JVMs working in this tree: other checkouts may be testing in the same WSL.
stuck_where() {
  [ -x "$JSTACK" ] || { echo "  (jstack not found at $JSTACK)"; return; }
  local pids
  if [ "$ON_LINUX" = 1 ]; then
    pids=""
    for pid in $(pgrep -x java); do
      case "$(readlink "/proc/$pid/cwd" 2>/dev/null)" in "$PWD"*) pids="$pids $pid" ;; esac
    done
  else
    pids=$(tasklist //FI "IMAGENAME eq java.exe" //FO CSV //NH 2>/dev/null | tr -d '"' | cut -d, -f2)
  fi
  for pid in $pids; do
    "$JSTACK" "$pid" > "$WORK/stack-$pid.txt" 2>/dev/null || continue
    local frame
    frame=$(grep -oE 'com\.bydmate\.app\.[A-Za-z0-9_.]*Test[A-Za-z0-9_.]*\([^)]*\)' "$WORK/stack-$pid.txt" | head -1)
    [ -n "$frame" ] && echo "  pid $pid is in: $frame"
  done
  echo "  full stacks: $WORK/stack-<pid>.txt"
}

# The whole unit-test step: compile, then the suite under the watchdog, then the count.
run_unit_tests() {
  local ts
  ts=$(date +%s)
  step "unit tests: compile"
  # Compiling first and apart keeps the watchdog honest: a cold compile is minutes without a single
  # test result, which the stall limit used to count as "stuck".
  # shellcheck disable=SC2086
  if ! ./gradlew :app:compileWazeDebugUnitTestKotlin $TEST_FLAGS -q > "$WORK/gradle-test-compile.log" 2>&1; then
    echo "FAIL: unit test compile"
    grep -E '^e: |^> |What went wrong|Caused by' "$WORK/gradle-test-compile.log" | head -6 | sed 's/^/  /'
    echo "  full log: $WORK/gradle-test-compile.log"
    fail=1
    return
  fi
  took "$ts"

  ts=$(date +%s)
  step "unit tests ($TEST_FORKS workers, each suite reported as it finishes, stall limit ${STALL_MINUTES}m)"
  rm -rf "$RESULTS" 2>/dev/null
  # shellcheck disable=SC2086
  ./gradlew :app:testWazeDebugUnitTest $TEST_FLAGS -q > "$WORK/gradle-test.log" 2>&1 &
  gradle_pid=$!
  local stall=0 last_sig="" seen="$WORK/seen-suites.txt" f name n suites sig
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
  gradle_pid=""

  local total=0 bad=0 unexpected="" t
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
    echo "  report: $PWD/app/build/reports/tests/testWazeDebugUnitTest/index.html"
  elif [ "$total" -gt 0 ]; then
    echo "ok: no unexpected failures"
  else
    # No results at all is not a pass: gradle died before a single suite finished.
    echo "FAIL: no test results"
    grep -E '^e: |^> |What went wrong|Caused by' "$WORK/gradle-test.log" | head -6 | sed 's/^/  /'
    echo "  full log: $WORK/gradle-test.log"
    fail=1
  fi
  took "$ts"
}

if [ "$MODE" = tests ]; then
  run_unit_tests
  exit $fail
fi

# --- Unit tests in WSL, started first: they are the longest part. ---------------------------
tests_pid=""
if [ "$PREFLIGHT_TESTS" = wsl ]; then
  # Copy the tree into the WSL filesystem and test there; gradle on /mnt/d is slow and would share
  # app/build with the Windows build. build/ and .gradle/ are excluded from the copy, so the WSL
  # side keeps its own warm state between runs. local.properties and keystore.properties hold
  # Windows paths and stay behind (the release is built on Windows, not here).
  win_root=$(pwd -W 2>/dev/null)
  src="/mnt/$(printf '%s' "${win_root:0:1}" | tr 'A-Z' 'a-z')${win_root:2}"
  if [ -z "$win_root" ] || ! MSYS_NO_PATHCONV=1 wsl.exe -d "$WSL_DISTRO" -- bash -lc \
      'command -v rsync >/dev/null && test -d ~/Android/Sdk && test -x "${JAVA_HOME:-$HOME/tools/jdk17}/bin/java"' \
      >/dev/null 2>&1; then
    echo "note: WSL $WSL_DISTRO is not set up for the tests (docs/guides/running-tests-under-wsl.md) - running them on Windows"
    PREFLIGHT_TESTS=windows
  else
    MSYS_NO_PATHCONV=1 wsl.exe -d "$WSL_DISTRO" -- bash -lc "
      set -e
      mkdir -p ~/$WSL_DIR ~/$WSL_DIR.tmp
      rsync -a --delete --exclude .git/ --exclude .claude/ --exclude build/ --exclude .gradle/ \
        --exclude .kotlin/ --exclude local.properties --exclude keystore.properties \
        '$src/' ~/$WSL_DIR/
      cd ~/$WSL_DIR
      echo \"sdk.dir=\$HOME/Android/Sdk\" > local.properties
      TMPDIR=~/$WSL_DIR.tmp TEST_FORKS=$TEST_FORKS STALL_MINUTES=$STALL_MINUTES bash scripts/preflight.sh --tests-only
    " > "$WORK/tests-wsl.log" 2>&1 &
    tests_pid=$!
    bg_pids="$bg_pids $tests_pid"
    echo "unit tests started in WSL ($WSL_DISTRO:~/$WSL_DIR, $TEST_FORKS workers); log: $WORK/tests-wsl.log"
  fi
fi

# --- Windows: one gradle run for the release, detekt, lint and the emulator's debug APK. -------
# One run, not four: with the configuration cache gradle executes independent tasks of the same
# project in parallel, so R8, lint, detekt and the debug packaging overlap.
# The same checks CI runs on a pull request (ci.yml): detekt, and lint of the waze debug variant
# over the main sources. lintVitalWazeRelease is skipped as in release-dev.yml: it is a subset of
# that lint over the same sources (there is no release source set), analysed a second time.
# --continue so one failure still reports the others.
emu_ready=0
if [ "$RUN_EMU" = "1" ]; then
  if "$ADB" devices 2>/dev/null | grep -q "^$SERIAL[[:space:]]*device"; then
    emu_ready=1
  else
    echo "FAIL: $SERIAL is not running. Start it with:"
    echo "  /c/Android/Sdk/emulator/emulator.exe -avd AAOS_DiLink_A13 -gpu host"
    echo "  (-gpu host is required, the secondary display windows do not appear otherwise)"
    fail=1
  fi
fi
tasks=":app:assembleWazeRelease :app:detekt :app:lintWazeDebug"
flags="-Pbydmate.lintMainOnly -x lintVitalWazeRelease"
DEBUG_OUT="app/build/outputs/apk/waze/debug"
if [ "$emu_ready" = 1 ]; then
  tasks="$tasks :app:assembleWazeDebug"
  flags="$flags -Pbydmate.emulatorAbi=true"
  # The package task writes output-metadata.json after the APK; a stale one would start the
  # scenarios on the previous APK.
  rm -f "$DEBUG_OUT/output-metadata.json"
fi
step "release build + detekt + lint$([ "$emu_ready" = 1 ] && echo ' + debug APK')"
ts=$(date +%s)
# Keep the output: "FAIL: release build" on its own sends you looking for a compile error that
# is not there. The one that actually happened was a Windows file lock on classes.dex left by a
# previous interrupted run.
# shellcheck disable=SC2086
./gradlew $tasks $flags --continue -q > "$WORK/gradle-build.log" 2>&1 &
gradle_pid=$!

# The scenarios only need the debug APK and the emulator: they start as soon as the APK is
# packaged and run while R8 and lint are still busy.
emu_pid=""
if [ "$emu_ready" = 1 ]; then
  (
    while [ ! -e "$DEBUG_OUT/output-metadata.json" ] && kill -0 "$gradle_pid" 2>/dev/null; do sleep 3; done
    apk=$(ls -1 "$DEBUG_OUT"/*.apk 2>/dev/null | tail -1)
    if [ ! -e "$DEBUG_OUT/output-metadata.json" ] || [ -z "$apk" ]; then
      echo "  FAIL debug APK: gradle ended without packaging it - scenarios not run"
      exit 1
    fi
    echo "debug APK: $apk ($(( $(date +%s) - ts ))s into the build)"
    if ! "$ADB" -s "$SERIAL" install -r -g --user 10 "$(cygpath -w "$apk" 2>/dev/null || echo "$apk")" > "$WORK/install.log" 2>&1; then
      echo "  FAIL install: $(tail -1 "$WORK/install.log")"
      exit 1
    fi
    cd "$SIM" && SERIAL="$SERIAL" P="$P" DIALECT=arhud bash scripts/bydmate-scenarios.sh
  ) > "$WORK/scenarios.log" 2>&1 &
  emu_pid=$!
  bg_pids="$bg_pids $emu_pid"
fi

wait "$gradle_pid"
build_rc=$?
gradle_pid=""
failed_tasks=$(grep -oE "Execution failed for task '[^']+'" "$WORK/gradle-build.log" | grep -oE ":app:[A-Za-z0-9]+" | sort -u)
LINT_RE='^:app:lint[A-Za-z]*WazeDebug$'
detekt_bad=0; lint_bad=0; debug_bad=0; release_bad=0
echo "$failed_tasks" | grep -qE '^:app:detekt$' && detekt_bad=1
echo "$failed_tasks" | grep -qE "$LINT_RE" && lint_bad=1
echo "$failed_tasks" | grep -vE "$LINT_RE" | grep -qE 'WazeDebug' && debug_bad=1
# Anything else that failed belongs to the release build; a failure that names no task
# (configuration, a dead daemon) fails them all.
echo "$failed_tasks" | grep -vE "$LINT_RE"'|^:app:detekt$|WazeDebug' | grep -q . && release_bad=1
if [ "$build_rc" != 0 ] && [ -z "$failed_tasks" ]; then release_bad=1; detekt_bad=1; lint_bad=1; debug_bad=1; fi
# With --continue, a task whose dependency failed is skipped, not failed: it proved nothing.
unproven() { [ "$build_rc" != 0 ] && echo "--   $1: not confirmed, gradle failed elsewhere (see above)"; }
if [ "$release_bad" = 1 ]; then
  echo "FAIL: release build"
  grep -E '^e: |^> |What went wrong|Caused by' "$WORK/gradle-build.log" | head -6 | sed 's/^/  /'
  echo "  full log: $WORK/gradle-build.log"
  fail=1
else
  unproven "release build" || echo "ok: $(ls -1 app/build/outputs/apk/waze/release/*.apk 2>/dev/null | tail -1)"
fi
if [ "$debug_bad" = 1 ]; then
  echo "FAIL: debug build"
  grep -E '^e: |^> |What went wrong|Caused by' "$WORK/gradle-build.log" | head -6 | sed 's/^/  /'
  fail=1
fi
if [ "$detekt_bad" = 1 ]; then
  echo "FAIL: detekt (new findings; the baseline is config/detekt/baseline.xml)"
  grep -E '\.kt:[0-9]+:[0-9]+: ' "$WORK/gradle-build.log" | head -10 | sed 's/^/  /'
  echo "  report: $PWD/app/build/reports/detekt/detekt.html"
  fail=1
else
  unproven detekt || echo "ok: detekt"
fi
if [ "$lint_bad" = 1 ]; then
  echo "FAIL: lint"
  grep -E 'Error: |errors?, [0-9]+ warnings?' "$WORK/gradle-build.log" | head -10 | sed 's/^/  /'
  echo "  report: $PWD/app/build/reports/lint-results-wazeDebug.html"
  fail=1
else
  unproven lint || echo "ok: lint"
fi
[ "$build_rc" != 0 ] && fail=1
took "$ts"

# --- Collect the unit tests. ---------------------------------------------------------------
if [ "$PREFLIGHT_TESTS" = wsl ]; then
  wait "$tests_pid"
  tests_rc=$?
  # The WSL side printed its own sections (compile, suites, count); show them in place.
  cat "$WORK/tests-wsl.log"
  if [ "$tests_rc" != 0 ]; then
    fail=1
    grep -q '^FAIL' "$WORK/tests-wsl.log" || echo "FAIL: unit tests in WSL exited with $tests_rc"
    echo "  reports: \\\\wsl.localhost\\$WSL_DISTRO\\home\\<user>\\$WSL_DIR\\app\\build\\reports\\tests"
  fi
else
  run_unit_tests
fi

# --- Collect the emulator scenarios. -------------------------------------------------------
if [ -n "$emu_pid" ]; then
  step "emulator scenarios"
  wait "$emu_pid"
  out=$(cat "$WORK/scenarios.log")
  # Readiness lines too, not just the verdicts. A run where the app never bound printed 17
  # identical "got: " failures and read as broken lane code; the two lines that said the app
  # was not up had been filtered out.
  echo "$out" | grep -E '^(debug APK:|a11y connected|HUD active)' | sed 's/^/  /'
  echo "$out" | grep -E '^\s+(ok|FAIL)' | sed 's/^/  /'
  # No "ok" line at all is not green either: the testbed script did not get to run.
  if echo "$out" | grep -q 'FAIL' || ! echo "$out" | grep -qE '^\s+ok '; then
    echo "FAIL: emulator scenarios"
    echo "  full output: $WORK/scenarios.log"
    fail=1
  else
    echo "ok: all scenarios green"
  fi
fi

printf '\n=== verdict: %s (%ss)\n' "$([ $fail = 0 ] && echo 'ready to push' || echo 'DO NOT PUSH')" "$(( $(date +%s) - t0 ))"
exit $fail
