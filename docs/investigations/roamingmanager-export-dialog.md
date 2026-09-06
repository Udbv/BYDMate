# RoamingManager.apk: how it auto-closes "This car is not from official export" (2026-09-06)

Source: E:\apk\RoamingManager.apk, package `com.roamingmanager`, version 2026.06.01-11-v2, minSdk 26,
targetSdk 32, UI in Ukrainian and English. Decompiled with jadx/apktool into
C:\Users\Bohda\AppData\Local\Temp\bydw\roamingmanager-{jadx,apktool}. Nothing from it is copied here.

## Mechanism

No accessibility service, no notification listener, no root. It embeds the `dadb` ADB client and
talks to the head unit's own adbd at 127.0.0.1:5555 with an app-generated RSA key
(`utils/AdbExecutor.java:33-38`), i.e. the same shell-uid trick as BYDMate's helper daemon,
but one TCP session per command instead of a persistent daemon.

`service/RoamingService.java:878-925` (`dismissExportWarning`) runs one shell one-liner:

```
for i in 1 2 ... 20; do
  dumpsys window vehicledialog | grep -q vehicledialog && sleep 0.5 && input tap 960 790 && exit 0
  sleep 1
done; exit 0
```

- Detection: `dumpsys window vehicledialog` matches a WindowManager window whose TITLE contains
  `vehicledialog`. No package, activity, view id or text is matched in any language; the owning
  package is never named.
- Dismissal: a blind `input tap 960 790` (centre x of a 1920-wide screen, y at ~73% of 1080). No
  button lookup, no back key, no force-stop, no component disable, no setting change.
- Trigger: only on boot (`receiver/BootReceiver.java:33-37`, BOOT_COMPLETED / LOCKED_BOOT_COMPLETED
  / QUICKBOOT_POWERON) when pref `dismiss_export_warning` is on; polls 1 s for at most 20 s, then
  gives up; logs "closed automatically" even when no tap happened (`RoamingService.java:913-914`).
  No whitelist or safety logic.

## What else the app does

Data roaming on/off through `Settings.Global data_roaming`/`data_roaming2` (grants itself
WRITE_SECURE_SETTINGS via `pm grant` over ADB), `settings put secure location_mode 3`, APN switching
through `content://telephony/carriers/preferapn`, `svc data disable/enable`, a 180 min roaming
re-check job, Wi-Fi hotspot, a free-form ADB shell console (`ui/AdbFragment.java`), bugreport
collection. No network endpoints. Risky bits: writes global/secure settings, self-grants
permissions, unrestricted shell console.

## Port into BYDMate

Identifiers: window title substring `vehicledialog`; fallback tap (960, 790) on 1920x1080 landscape.

1. Accessibility first (preferred). `cluster/SteeringWheelKeyService` already runs with
   `typeAllMask`, `flagRetrieveInteractiveWindows` and `canRetrieveWindowContent`. On
   `TYPE_WINDOW_STATE_CHANGED`, iterate `windows`, match `window.title` containing `vehicledialog`
   (or the owning package once known on the car), find the confirm button
   (`findAccessibilityNodeInfosByText` / clickable node scan) and `performAction(ACTION_CLICK)`,
   fall back to `GLOBAL_ACTION_BACK`. Event-driven, language- and resolution-independent. Keep it
   out of `NavA11yFeed.shouldProcess` (that gate is for guidance packages).
2. Helper-daemon fallback. If the dialog is a system window invisible to accessibility, add a
   `TX_DISMISS_VEHICLE_DIALOG` transaction to `helper/HelperBinderProtocol.kt`, run
   `dumpsys window windows` + `input tap X Y` via the daemon's `shExec` (`HelperDaemon.kt:1370`),
   and expose it as an automation action in `data/automation/ActionDispatcher.kt` next to the other
   helper actions, with a bounded poll (20 x 1 s) bound to an ignition-on trigger.

## Tang L / DiLink 150 unknowns

- Window title `vehicledialog` and the owner package: confirm with
  `dumpsys window windows | grep -i vehicledialog` on the car and dump the a11y tree for the
  button text/ids (BYDMate's NavA11yFeed tree dump can be reused).
- (960, 790) assumes 1920x1080 landscape; the Tang L rotatable panel in portrait or a different
  DiLink 150 resolution would miss. Node clicks avoid this.
- RoamingManager fires only on cold boot; if the warning returns on every ignition cycle, trigger
  on the window event or the ignition signal instead.
- Which button the tap hits ("OK" vs "Don't show again") is unknown; verify before shipping.
