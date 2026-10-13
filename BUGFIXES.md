# Bug Fix Change Log

All issues were found through a full source review and fixed in-place.
No new features beyond making the documented behavior actually work.

## ⚙️ CI follow-up — Android SDK setup runtime

The first GitHub Actions run showed that `android-actions/setup-android@v3` downloaded a newer `sdkmanager` which requires JDK 17, while the workflow had selected JDK 11. Updated the CI runtime to JDK 17. This does **not** change the app's Java compatibility: `sourceCompatibility` and `targetCompatibility` remain Java 11.

## 🧱 Round 5 — build files, resources & UX-loop audit

| # | Bug | Fix |
|---|-----|-----|
| 15 | **Notification-permission nag loop** (regression from the round-4 fix) — after the user DENIED the optional notification permission, the already-granted branch re-prompted on **every cold start**, which is platform-banned nag UX on Android 13+. | Ask at most once per install: the attempt is recorded in `ui_prefs` before launching the prompt. After that the user controls it in system settings. |
| 16 | **Device-specific routing prefs got cloud-backed-up** — `audio_prefs.xml` stores a preference keyed to a device-specific audio-device ID; with `allowBackup=true` and template-only rules files, Auto Backup / D2D transfer would restore stale cross-device data to a new phone. | Added explicit `<exclude>` entries for both shared-pref files in `data_extraction_rules.xml` (API 31+, also covering D2D transfer) and `backup_rules.xml` (legacy ≤ API 30). |
| 17 | **Foreground zombie path** — if `AudioManager` were ever null, the service stayed foreground (notification visible) but inert, having skipped only the routing call. | `stopSelf()` on that path so state, prefs, and notification are all torn down consistently. |
| 18 | **Build noise** — AGP 7.4.2 is officially tested up to compileSdk 33; this project targets SDK 34, so every sync printed an unsupported-SDK warning (pre-existing in the original repo). | Added the official `android.suppressUnsupportedCompileSdk=34` property. |
| 19 | **Day/night theme mismatch** (leftover template) — the night theme still used `MaterialComponents.DayNight.DarkActionBar` (Material 2 + ActionBar) while the day theme had been migrated to Material3 NoActionBar, so the app grew a stray ActionBar at night and lost M3 styling. | `values-night/themes.xml` now mirrors the day definition; `DayNight` supplies dark colors automatically, and the splash theme safely falls back to `values/` (white background + black-tinted icon stays visible in both modes). |

Verified clean this round (previously uninspected files): `gradle.properties` (`useAndroidX` / `nonTransitiveRClass` present), splash theme chain (`Theme.App.Starting` → `postSplashScreenTheme` correct), `ic_mic_logo.xml` vector well-formed, night theme fallback intact.

## 🔁 Round 4 — permission-flow & API-guard audit

| # | Bug | Fix |
|---|-----|-----|
| 13 | **Dead permission path: notification permission never requested** — `POST_NOTIFICATIONS` was bundled into `requestPermissions()`, which only runs when a *core* permission is missing. If RECORD_AUDIO + BLUETOOTH_CONNECT were already granted (previous session / granted via system settings), the flow took the `checkPermissions() == true` branch and the notification permission was never asked for, leaving the FGS notification **silently hidden** on Android 13+ (incl. Android 15). | Added a dedicated single-permission launcher; the already-granted branch now separately requests `POST_NOTIFICATIONS` (optional, one-shot, never double-prompts with the batch flow). Denial no longer implies invisible state is an error — a toast explains the service still runs. |
| 14 | **Sloppy version guard** — the FGS-type guard used `VERSION_CODES.Q` (29), but `FOREGROUND_SERVICE_TYPE_MICROPHONE` (128) only exists since **API 30**; on API 29 the unknown type bit would be rejected. Harmless here (minSdk 30 makes the guard moot) but wrong as written. | Guard tightened to `VERSION_CODES.R` with an explanatory note. |

## 📱 Round 3 — Android 15 (API 35) compatibility audit

| # | Bug | Fix |
|---|-----|-----|
| 12 | **LE Audio earbuds invisible on Android 15** — the device filter only accepted `TYPE_BLUETOOTH_SCO` (classic). Modern earbuds connect via LE Audio and present as `TYPE_BLE_HEADSET` (constant 26, added in API 31, a valid *input* device type), so on Android 14/15 they never appeared in the list at all. | `TYPE_BLE_HEADSET` added to the input filter, the type-name mapping ("Bluetooth LE"), the routing decision in `applyRouting()`, and the type-based fallback in `routeToBluetooth()`. LE Audio devices route through the same `setCommunicationDevice()` path — no legacy SCO involvement needed. |

**Verified NOT a problem on Android 15** (official behavior-change docs):
- Android 15's new FGS rules only add timeouts for `dataSync`/`mediaProcessing` types and `BOOT_COMPLETED` launch bans — this app uses the `microphone` type and starts from a foreground Activity → unaffected, even if `targetSdk` is later raised to 35.
- Edge-to-edge enforcement applies to `targetSdk 35+` only; this project targets 34 → unaffected.
- No native code in the app or its dependencies → unaffected by 16 KB page-size devices.
- The app never requests audio focus → the new target-35 audio-focus restriction doesn't apply.

## 🔴 Crash-level fixes

| # | Bug | Fix |
|---|-----|-----|
| 1 | **App crashed on Android 14+** — `targetSdk 34` but the service declared no `foregroundServiceType`, so `startForeground()` threw `MissingForegroundServiceTypeException` every time "Set" was pressed. | Added `android:foregroundServiceType="microphone"` to the manifest service declaration, added the `FOREGROUND_SERVICE_MICROPHONE` permission, and started the foreground service via `ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_MICROPHONE)`. |
| 2 | **NullPointerException on service restart** — `onStartCommand` dereferenced `intent` with no null check; `START_STICKY` restarts deliver a **null** intent. | Null-safe restart: the last preference is persisted to `SharedPreferences` and restored when the intent is null. If nothing is stored, the service stops cleanly with `START_NOT_STICKY`. |
| 3 | **SecurityException risk (API 31+/34+)** — Bluetooth calls were made from the service without runtime permission checks; revoked permissions would crash it. A mic-type FGS also requires `RECORD_AUDIO` granted *before* `startForeground()`. | Runtime checks for `RECORD_AUDIO` and `BLUETOOTH_CONNECT` in the service before touching the corresponding APIs, graceful `stopSelf()` fallback, and `SecurityException` guards around routing and cleanup. |

## 🟡 Functional fixes

| # | Bug | Fix |
|---|-----|-----|
| 4 | **Bluetooth SCO often did nothing** — the code never changed the audio mode, so on many devices the SCO route never actually engaged. | `audioManager.setMode(MODE_IN_COMMUNICATION)` before routing to Bluetooth; mode restored to `MODE_NORMAL` on revert/destroy. |
| 5 | **Could not select a specific Bluetooth device** — only the device *type* was passed to the service, and `startBluetoothSco()` (deprecated on API 31+) picks whatever it wants. | The device **ID** is now passed; on API 31+ the service resolves it via `getAvailableCommunicationDevices()` and calls `setCommunicationDevice(device)` with type-based fallback; legacy SCO kept only for pre-31. |
| 6 | **README claimed the service "constantly tells" AudioManager — it set the route exactly once** and never corrected it. | The service now registers an `AudioDeviceCallback` and re-applies the routing whenever devices are added/removed, so the preference truly stays active. |
| 7 | **Dishonest "Default" button** — showed "reverted to default" even when nothing was running, and never restored the audio mode. | The button checks `AudioService.isRunning` and tells the user when there is nothing to revert; `onDestroy()` now clears the communication device, stops SCO, and restores `MODE_NORMAL`. |

## 🟢 Minor fixes

- **Routing leak on cleanup (found in second-pass review):** `routeToNonBluetooth()` sets a wired/USB communication device without needing the Bluetooth permission — correct — but `onDestroy()` originally gated `clearCommunicationDevice()` behind `BLUETOOTH_CONNECT`. Scenario: user grants mic but **denies Bluetooth**, picks a wired headset, taps "Default" → the routing was never cleared and got stuck. `clearCommunicationDevice()` (which only needs the install-time `MODIFY_AUDIO_SETTINGS`) is now called unconditionally on API 31+; only the Bluetooth-specific teardown stays permission-gated.
- `build.gradle`: removed the **duplicate** `material:1.12.0` declaration; removed unused `constraintlayout`; declared `androidx.core` explicitly (the service now uses `ServiceCompat`); aligned `compileOptions` to **Java 11** to match the README's JDK 11 prerequisite.
- Manifest: added `POST_NOTIFICATIONS` (so the foreground notification is actually visible on Android 13+) — requested at runtime too, treated as optional.
- `updateDeviceList()`: cleared stale spinner entries when a scan is blocked by missing permissions, and shows feedback when all devices were filtered out; added a bounds check on the spinner selection before indexing the device list.
- Foreground notification: added a tap-to-open `PendingIntent`, proper category/ongoing flags, and a dedicated mic icon.

## Files changed

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/example/audioinputmanager/AudioService.java` (rewritten)
- `app/src/main/java/com/example/audioinputmanager/MainActivity.java`
- `app/build.gradle`
- `README.md` (behavior description now matches implementation)
