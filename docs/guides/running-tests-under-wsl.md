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

## The Windows-path trap

`keystore.properties` holds a Windows path (`D:/BYD/keys/...`). Gradle's `file()` reads `D:` as a
URL scheme and aborts the **entire configuration phase** on Linux — so even tasks that sign
nothing, such as the unit tests, cannot run. `app/build.gradle.kts` resolves the store with
`java.io.File` now and warns loudly instead of failing, so a Linux checkout builds; a release
there would be unsigned, and the warning says so.
