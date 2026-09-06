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

- `main` = stable. Tags `vX.Y.Z`, published as normal GitHub releases. The app's "Stable" channel
  reads `releases/latest`, which GitHub serves from non-prerelease, non-draft releases only.
- `develop` = development. Tags `vX.Y.Z-dev.N`, published as **pre-releases**. The app's
  "Development" channel reads the newest non-draft release of any kind.
- Version order: numeric triple, then a final release beats a pre-release of the same triple,
  then pre-releases by their trailing number (`UpdateCheckerVersionTest`).

The channel is chosen in the app: Settings -> Updates -> Update channel.

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

Stable (from `main`):
```bash
git checkout main && git merge --ff-only develop && git push origin main
gh release create v3.15.0 --repo Udbv/BYDMate --title "BYDMate Waze 3.15.0" --notes-file docs/release-notes/v3.15.0.md \
  app/build/outputs/apk/waze/release/BYDMate-Waze-v3.15.0.apk
```

Development (from `develop`):
```bash
gh release create v3.15.1-dev.1 --repo Udbv/BYDMate --prerelease --title "BYDMate Waze 3.15.1-dev.1" \
  --notes "..." app/build/outputs/apk/waze/release/BYDMate-Waze-v3.15.1-dev.1.apk
```

Bump `versionCode` and `versionName` in `app/build.gradle.kts` before tagging; the tag must equal
`v` + versionName so the in-app checker sees the running version as current.

## First install in the car

Once by USB or Wi-Fi ADB (`adb connect <car-ip>:5555; adb install -g BYDMate-Waze-v3.15.0.apk`).
After that the app checks GitHub on start (Settings -> Updates) and installs newer releases from
the chosen channel itself.
