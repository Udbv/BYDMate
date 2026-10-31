# Releasing BYDMate Waze (fork Udbv/BYDMate)

## Flavors

| Flavor | applicationId | App name | Updates from | Helper daemon |
|---|---|---|---|---|
| `official` (default) | `com.bydmate.app` | BYDMate | AndyShaman/BYDMate releases | `bydmate_helper` |
| `waze` | `com.bydmate.app.waze` | BYDMate Waze | Udbv/BYDMate releases | `bydmate_helper_waze` (process `bydmate_waze`) |

`waze` installs beside the official app as a separate application (own uid, data, services,
accessibility and notification-listener grants, helper daemon, lock/log files, broadcast actions).
Everything that used to name the package literally now derives from `BuildConfig.APPLICATION_ID`.

## Branches and channels

**Policy: everything ships on the Development channel until a build has been tested in the real
car. `main` tracks upstream (AndyShaman/BYDMate) untouched until then; the first stable release is
cut only after that test.** The `waze` flavor therefore defaults to the Development channel.

- `develop` = development (the working branch; GitHub default stays `main`, like upstream). Tags `vX.Y.Z-dev.N`, published
  as **pre-releases**. The app's "Development" channel reads the newest non-draft release of any kind.
- `main` = stable, only after a real-car test. Tags `vX.Y.Z`, published as normal GitHub releases.
  The app's "Stable" channel reads `releases/latest`, which GitHub serves from non-prerelease,
  non-draft releases only.
- Version order: numeric triple, then a final release beats a pre-release of the same triple,
  then pre-releases by their trailing number (`UpdateCheckerVersionTest`).

The channel is chosen in the app: Settings -> Updates -> Update channel.

### Issues and pull requests

GitHub closes an issue only when a closing keyword reaches the default branch `main`, and the
release PR merges `develop` into `main` with a merge commit, so the commits of `develop` get there
with it. Therefore:

- Every PR into `develop` names its issues in the body: `Closes #N` for an issue the PR finishes,
  `Refs #N` for one it only advances. Work without an issue gets one first.
- The squash commit of a PR into `develop` repeats the `Closes #N` lines in its message. They
  reach `main` with the release merge, and GitHub closes the issues then: after the car test,
  not at the dev build.
- The release PR `develop` -> `main` lists `Closes #N` for every issue the release finishes.

## Signing

Release builds are signed with `D:\BYD\keys\bydmate-waze.jks` (alias `bydmate-waze`), referenced
from the gitignored `keystore.properties` in the repo root. **Back both files up**: an update
signed with a different key is refused by Android and the app must be uninstalled (data lost)
before the new one installs. Never commit either file.

## Build

```bash
# from D:\BYD\BYDMate in git bash (the repo ships a Unix gradlew)
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
./gradlew :app:assembleWazeRelease              # -> app/build/outputs/apk/waze/release/BYDMate-Waze-v3.15.0.apk (arm64, signed)
./gradlew :app:assembleWazeDebug -Pbydmate.emulatorAbi=true   # emulator testbed build (adds x86_64)
./gradlew :app:testWazeDebugUnitTest            # unit tests for the flavor
```

## Publish

Development (from `develop`, the normal case) — **CI does it**: `.github/workflows/release-dev.yml`
builds the signed APK and publishes the pre-release by itself when a push to `develop` changes
`versionName` (with `docs/release-notes/v<versionName>.md` present). So a dev release is: bump
the version, add the notes, merge into `develop`. It needs the four repository secrets listed at the
top of the workflow (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`), set once;
without them the run fails at the keystore step, and "Re-run jobs" after adding them publishes.

By hand from a PC (fallback):
```bash
git checkout develop && git push origin develop
gh release create v3.15.0-dev.2 --repo Udbv/BYDMate --target develop --prerelease \
  --title "BYDMate Waze 3.15.0-dev.2" --notes-file docs/release-notes/v3.15.0-dev.2.md \
  app/build/outputs/apk/waze/release/BYDMate-Waze-v3.15.0-dev.2.apk
```

Stable (from `main`, ONLY after a real-car test of that exact build) — **CI does it**:
`.github/workflows/release-stable.yml` runs on every push to `main`. It takes the `versionName` on
`main` (`X.Y.Z-dev.N`), checks that the pre-release `vX.Y.Z-dev.N` exists and is in `main`, and
rebuilds that tag's commit with only the name changed to `X.Y.Z`. The result goes out as `vX.Y.Z`,
a full release marked latest; the dev tag stays a pre-release. The code is exactly the tested code
and `versionCode` is the dev build's, so it installs over the dev build in the car; only the name
differs. Notes come from `docs/release-notes/vX.Y.Z.md`, or the dev build's notes when that file
is missing. Release PR: `develop` -> `main` with a **merge commit**.

**After a stable `X.Y.Z` the next dev build must be above it** (`X.Y.(Z+1)-dev.1` or
`X.(Y+1).0-dev.1`): `X.Y.Z-dev.N` sorts below `X.Y.Z`, and the Development channel would never offer it.

(Until 2026-10-31 the dev pre-release itself was promoted, so the stable build was called
`4.1.0-dev.23`; `v4.1.0` is the same build under its stable name.)

Bump `versionCode` and `versionName` in `app/build.gradle.kts` before tagging; the tag must equal
`v` + versionName so the in-app checker sees the running version as current.

## First install in the car

Once by USB or Wi-Fi ADB (`adb connect <car-ip>:5555; adb install -g BYDMate-Waze-v3.15.0.apk`).
After that the app checks GitHub on start (Settings -> Updates) and installs newer releases from
the chosen channel itself.
