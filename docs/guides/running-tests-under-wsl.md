# Running the test suite under WSL

The unit suite is fully green on Linux and partly host-bound on Windows, so WSL is the more
faithful place to run it — it is also the platform the app ships to.

Measured on this machine (Ubuntu-26.04, WSL2):

| | |
|---|---|
| Full run: dependencies, KSP, Kotlin compile, 3826 tests | 5m 39s |
| Warm re-run of the test task only | 1m 54s |
| Result | 361 suites, 3826 tests, **0 failures**, 2 skipped |

The 2 skips are pre-existing and identical on both hosts: `FidMapTest` has an explicit `@Ignore`,
and `WriteAllowlistTest` assumes a `.research/` file that is not in the repo.

## Setup, without sudo

The WSL user here has no passwordless sudo, so nothing below needs root and nothing lands outside
`$HOME`.

1. **JDK 17** — unpack the Temurin 17 Linux tarball from the Adoptium API into `~/tools/jdk17`,
   then `export JAVA_HOME=~/tools/jdk17`.
2. **Android SDK for Linux** — unpack `commandlinetools-linux-*` into
   `~/Android/Sdk/cmdline-tools/latest`, accept licences with `sdkmanager --licenses`, then
   install `platform-tools`, `platforms;android-34`, `build-tools;34.0.0` and `build-tools;35.0.0`
   (AGP 8.7.3 defaults to 35.0.0). The Windows SDK under `/mnt/c` will **not** do: `aapt2` is a
   native binary.
3. **Copy the tree into the WSL filesystem** — do not build in `/mnt/d`. It is slow, and worse, it
   shares `app/build` with the Windows run, so the two corrupt each other:

   ```bash
   rsync -a --exclude .git/ --exclude app/build/ --exclude build/ --exclude .gradle/ \
     /mnt/d/BYD/BYDMate/ ~/bydmate
   ```
4. **`local.properties`** — `sdk.dir=/home/<user>/Android/Sdk`.
5. `./gradlew :app:testWazeDebugUnitTest`

## How `scripts/preflight.sh` uses it

Once steps 1–2 are done, `scripts/preflight.sh` does steps 3–5 by itself on every run: it copies
the checkout into `~/bydmate-preflight-<checkout dir>` (one copy per worktree; override with
`WSL_DIR`), excluding `.git/`, `.claude/`, every `build/`, `.gradle/`, `local.properties` and
`keystore.properties`, writes a Linux `local.properties`, and runs `scripts/preflight.sh
--tests-only` there — the same watchdog and the same verdict, on Linux. Meanwhile Windows builds
the release, runs detekt and lint and packages the emulator APK in one gradle run, and the
emulator scenarios start as soon as that APK exists. With no usable WSL (or with
`PREFLIGHT_TESTS=windows`) the unit tests run on Windows after the build instead.

The test step runs with `-Pbydmate.noCoverage -Pbydmate.testForks=4` (`TEST_FORKS` overrides the
worker count). Measured 4.11.2026 on this machine (12 threads, 64 GB; WSL gets 30 GB), the same
one-line code change before every run, other agents' builds running beside it:

| Unit-test task in WSL after a code change | time | sum of suite times |
|---|---|---|
| 1 worker, no coverage agent | 7m 48s | 399 s |
| 4 workers, no coverage agent | 4m 03s | 722 s |
| 6 workers, no coverage agent | 4m 30s | 1141 s |
| 4 workers, coverage agent on | 5m 53s | 1055 s |

| Whole preflight | before | after |
|---|---|---|
| warm, light load | 12m 11s, unit tests on Windows | 4m 28s – 5m 05s (3 runs), detekt and lint included |
| first run in a fresh worktree, heavy load | 23m 29s | — |

Before, the unit tests on Windows alone took 10 min; they also fail there in six suites that
depend on POSIX file semantics (`getPosixFilePermissions`, replacing an open file, a read-only
directory) and pass on Linux, which is what CI and the head unit run.

What is left on the critical path (`--profile` of the Windows gradle run): `lintAnalyzeWazeDebug`
~235 s, R8 ~134 s beside it, detekt ~34 s; the WSL tests finish at about the same moment.

## The Windows-path trap

`keystore.properties` holds a Windows path (`D:/BYD/keys/...`). Gradle's `file()` reads `D:` as a
URL scheme and aborts the **entire configuration phase** on Linux — so even tasks that sign
nothing, such as the unit tests, cannot run. `app/build.gradle.kts` resolves the store with
`java.io.File` now and warns loudly instead of failing, so a Linux checkout builds; a release
there would be unsigned, and the warning says so.
