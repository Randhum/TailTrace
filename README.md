# TailTrace

Android app for passive surveillance detection, tuned for Switzerland. It is a
fork of [KaraZajac/OVERWATCH](https://github.com/KaraZajac/OVERWATCH) (through
v0.5.13), reworked as `ch.swhizkid.tailtrace` **0.7.0-tailtrace**.

Open it, press **START**, and the circle turns **green / yellow / orange /
red** from the highest live score. With the screen locked, the foreground
notification follows the tier and the phone vibrates on upward escalations.

TailTrace keeps every BLE advertisement and WiFi access point it hears, lets
you assign an entity later, and watches for item trackers (AirTag, Tile,
SmartTag, Find My, DULT) that travel with you. Location comes from the
platform `LocationManager`. There is no Google Play Services client and no
Magisk module.

> **Passive listening.** The app does not probe, jam, or interfere. The one
> active radio action is a ring you start yourself on a tracker you already
> decided to find.

---

## What this fork adds

- **Catalog** (list icon). Durable radio history in `tailtrace_catalog.db`.
  Each MAC or BSSID is a row: OUI, company IDs, service UUIDs, name, SSID,
  payload fingerprint, hit count, and the last GPS fix. Assign one row, or
  bind a trait rule so later matches inherit that entity. Stopping a scan
  does not wipe this database.
- **Watch list** (Bluetooth icon). Tracker wire formats, geotagged sightings,
  and escalation from OBSERVED to SUSPICIOUS to ALERTING only after
  co-movement and RSSI proximity both clear. Allowlist ("this is mine"),
  learned home/work baseline, hot/cold finder, user-started ring, and a
  rotating-clone presence check. Sightings stay in `tailtrace_trackers.db`
  for 14 days. Tracker detection itself stays on the device.
- **Switzerland.** BLE and WiFi signatures favor Swiss and EU camera and
  bodycam vendors. The map query is speed cameras, section control, red-light
  cameras, and public outdoor CCTV, not a US ALPR-only sweep. Cell heuristics
  use Swiss PLMN (MCC 228) and treat border networks as expected.
- **CELL.** Serving-cell IMSI-catcher heuristics from `TelephonyManager`.
  Passive. Extra phone-state permissions apply only if the app is installed
  as a privileged system app; a sideload simply does not receive them.
- **No Play Services.** Fixes come from GPS, network, the platform fused
  provider on Android 12+, and the passive provider.

The live threat circle is still an in-memory window (five minutes). It clears
when the scan stops.

---

## What it detects

| Source | What it looks at | Where it comes from |
|---|---|---|
| **BLE** | Swiss/EU camera and bodycam OUIs (Axis, Hikvision, Dahua, Mobotix, Hanwha, Axon) and advertised names | Local BLE scan |
| **WiFi** | The same vendor BSSIDs, plus Swiss SSIDs (Axis, Hikvision/Dahua, Kantonspolizei / Stadtpolizei / Securitas). `SBB-Free` is not a hit | `WifiManager` scan results, about every 35 s |
| **OSM** | Speed cameras, section control, red-light cameras, public outdoor CCTV | Overpass, Swiss endpoint first, public endpoint as fallback. Cached on device |
| **WAZE** | User-reported police alerts in range | Optional. Needs a proxy token in Settings. The token is stored encrypted and is not in the APK |
| **AIRCRAFT** | Police and surveillance aircraft, from a bundled law-enforcement registry plus loiter detection for unlisted airframes | Community ADS-B feeds, polled about every 60 s. No API key |
| **COMMERCIAL** | Nearby smart-home and voice gear (Nest, Ring, Echo, hidden cameras) and camera glasses (Meta, Snap, Vuzix) | Same BLE and WiFi scans. Score capped at orange |
| **CELL** | IMSI-catcher heuristics on the Swiss PLMN (2G inland, unexpected MCC/MNC) | On-device telephony info |
| **TRACKER** | AirTag, Tile, SmartTag, Find My, and DULT tags travelling with you | Local BLE parse and on-device co-movement |

Every observation is scored 0–100. The circle shows the maximum live score:

```
GREEN      < 40    nothing credible
YELLOW   40 – 69   single weak indicator
ORANGE   70 – 84   high confidence
RED        85 +    certain
```

Commercial matches cannot reach red on their own. A second source in the same
area can still raise the global tier. While idle the circle is gray and reads
`IDLE`.

While scanning, the circle is a map centered on you, with a threat-color ring
and a crosshair for your position. Dots are colored by source. The same map
can sit in a floating bubble (Settings, display over other apps).

---

## How alerts work

- **In the app.** Tap the circle for the source drill-down. A row turns
  orange when that scanner could not reach its data source, so an empty
  result is distinct from a failed fetch.
- **Notification.** Rebuilt when the tier changes. On red the priority is
  high enough for a heads-up.
- **Vibration.** Upward tier changes only. Short pulse for yellow, double for
  orange, triple for red. Toggle under Settings, Alerts.

---

## Layout

```
ui/MainScreen.kt                   map circle, threat ring, start/stop, drill-down
ui/OverlayBubble.kt                floating copy of the map circle
ui/SettingsScreen.kt               source toggles, distances, Waze token, vibrate, theme
ui/SignatureCatalogScreen.kt       radio history, entity and trait assignment
ui/TrackerWatchScreen.kt           watch list, finder, allowlist
ui/TrackerHistoryScreen.kt         tracker alert log and evidence export
ui/TrackerSafetyScreen.kt          what-to-do notes, Swiss emergency numbers
tracker/scan/TrackerParser.kt      AirTag, Find My, SmartTag, Tile, DULT formats
tracker/detect/                    co-movement, clone presence, home/work baseline
tracker/ring/TrackerRinger.kt      user-started play-sound
scan/                              BLE, WiFi, OSM, Waze, aircraft, cell
fusion/ConfidenceEngine.kt         scoring
fusion/DetectionStore.kt           in-memory dedup, five-minute retention
data/location/LocationProvider.kt  framework LocationManager
data/catalog/                      signature identity, trait rules, SQLite
```

The service uses `START_NOT_STICKY`. A system kill does not restart it into a
state where the notification is gone but the app still thinks it is scanning.

---

## Waze

Waze stays off until Settings has a proxy token. The app sends that token as
`X-App-Token`. It is stored with the Android Keystore (AES/GCM) and never
shipped in the APK. Clearing the field turns the source off.

---

## Build and install

The wrapper is Gradle 9.7.1. The app module is AGP 9.3.2 and Kotlin 2.4.10.
`compileSdk` is 37, `targetSdk` is 35, `minSdk` is 26. CI builds with Temurin
JDK 17 (`.github/workflows/release.yml`). The Kotlin toolchain in
`app/build.gradle.kts` is also 17. Foojay can download that toolchain if the
JDK running Gradle is newer, but JDK 17 as `JAVA_HOME` matches CI.

`targetSdk` stays at 35 so screen-off scanning is not tied to newer
foreground-service rules.

### 1. JDK 17

```sh
java -version   # 17
echo "$JAVA_HOME"
```

If `java` is missing, install a JDK 17 and point `JAVA_HOME` at it. On Gentoo
that is a 17 slot from the system JDK. Temurin 17 is what CI uses.

### 2. Android SDK

You need an SDK that can accept licenses. Android Studio is enough. A
command-line SDK works too. Platform 37 and the build-tools AGP asks for are
downloaded on the first Gradle run after licenses are accepted. CI does not
preinstall SDK packages for that reason.

```sh
export ANDROID_HOME="$HOME/Android/Sdk"   # or wherever the SDK actually is
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses
```
**Known gaps.**

`sdkmanager` is not always on `PATH`. If that binary is missing, install the
command-line tools package or use Android Studio's SDK manager and accept
licenses there.

### 3. `local.properties`

This file is gitignored. The example is a starting point. Set `sdk.dir` to
the same directory as `ANDROID_HOME`. Use a real path, not the placeholder.

```sh
cp local.properties.example local.properties
```

Linux example:

```
sdk.dir=/home/you/Android/Sdk
```

macOS Android Studio default:

```
sdk.dir=/Users/you/Library/Android/sdk
```

Nothing else in that file is read by the app. The Waze token is entered in
Settings on the phone, not at build time.

### 4. Build

From the repo root. The wrapper downloads Gradle on first use, so that step
needs network.

```sh
chmod +x ./gradlew

./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```

The debug APK is written to:

```
app/build/outputs/apk/debug/app-debug.apk
```

`assembleRelease` is configured with minify off and with no release keystore.
That APK is not the one CI publishes, and it is not signed for install the
way the debug APK is.

### 5. Install on a device

USB debugging on, and `adb devices` showing the phone as `device` (not
`unauthorized`).

```sh
./gradlew :app:installDebug
```

Or install the APK yourself:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Signing

`debug.keystore` is committed so local and CI debug builds sign the same way.
An update then installs over the previous debug build without an uninstall.

| Field | Value |
|---|---|
| File | `debug.keystore` (repo root) |
| Store password | `android` |
| Key alias | `androiddebugkey` |
| Key password | `android` |

A debug key is not a release secret. The password is the usual Android debug
password.

---

## Permissions

| Permission | Why |
|---|---|
| `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` (API 31+) | BLE scanning |
| `BLUETOOTH`, `BLUETOOTH_ADMIN` (API 30 and below) | BLE scanning |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | WiFi results on older Android, and map proximity |
| `NEARBY_WIFI_DEVICES` (API 33+) | WiFi scan results |
| `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE` | Start and read WiFi scans |
| `READ_PHONE_STATE` | Serving-cell heuristics. Telephony hardware is not required |
| `INTERNET`, `ACCESS_NETWORK_STATE` | OSM, Waze, and aircraft feeds |
| `FOREGROUND_SERVICE` plus connected-device and location types | Keep a scan running with the screen off |
| `POST_NOTIFICATIONS` (API 33+) | The foreground notification |
| `VIBRATE` | Haptic escalation |
| `SYSTEM_ALERT_WINDOW` | Optional floating circle |

`READ_PRECISE_PHONE_STATE`, `MODIFY_PHONE_STATE`, `WRITE_SECURE_SETTINGS`, and
`NETWORK_SETTINGS` are declared for a privileged install. A normal sideload
never receives them. How to place the app on `/system` is in
[Privileged install](#privileged-install). `NETWORK_SETTINGS` is signature-only,
so that procedure does not grant it.

The first START asks for the runtime permissions. If one is permanently
denied, START becomes **Open app settings**.

---

## Privileged install

A sideload is the normal install. This section is only for a phone whose
system partition you can write, or for a system image you are building. This
repo does not ship a systemless installer. The steps below have not been run
on a device from this tree.

What you get if the allowlist is installed with the APK:

- `READ_PRECISE_PHONE_STATE` — cell registration-reject causes for the
  IMSI-catcher heuristics.
- `WRITE_SECURE_SETTINGS` — the Settings switch **PRIV • Unthrottled WiFi
  scan** can clear the 4-scans / 2-minute Wi-Fi throttle. That switch
  defaults to on. It is a no-op when the permission is missing.
- `MODIFY_PHONE_STATE` is allowlisted because the manifest declares it.
  Nothing in the app calls it yet.

What you do not get:

- `NETWORK_SETTINGS` and `RADIO_SCAN_WITHOUT_LOCATION` are signature
  permissions. A priv-app allowlist cannot grant them. Wi-Fi scan results
  with Location services off still require a platform-signed build. BLE
  scan-with-location-off does not need this install; `neverForLocation` on
  `BLUETOOTH_SCAN` covers that.
- Doze exemption. Even a priv-app cannot whitelist itself. Long scans can
  still be deferred until you exempt TailTrace under battery settings.

### 1. Remove a sideloaded copy

The package name is `ch.swhizkid.tailtrace`. If that package is already
installed under `/data`, uninstall it before the reboot that picks up the
system copy. Two installs of the same package with different signatures will
not merge. The debug APK is signed with `debug.keystore`.

```sh
adb uninstall ch.swhizkid.tailtrace
```

### 2. Build the debug APK

```sh
./gradlew :app:assembleDebug
```

The file is `app/build/outputs/apk/debug/app-debug.apk`.

### 3. Copy the APK and the allowlist onto `/system`

`adb root` and `adb remount` work on userdebug builds (a Lineage userdebug
image, an emulator). A locked production system with verified boot will
reject the remount. In that case these copies have to go into the system
image before it is flashed.

```sh
adb root
adb remount

adb shell mkdir -p /system/priv-app/TailTrace /system/etc/permissions
adb push app/build/outputs/apk/debug/app-debug.apk /system/priv-app/TailTrace/TailTrace.apk
adb push privapp/privapp-permissions-ch.swhizkid.tailtrace.xml \
  /system/etc/permissions/privapp-permissions-ch.swhizkid.tailtrace.xml

adb shell chmod 755 /system/priv-app/TailTrace
adb shell chmod 644 /system/priv-app/TailTrace/TailTrace.apk \
  /system/etc/permissions/privapp-permissions-ch.swhizkid.tailtrace.xml
adb reboot
```

The allowlist and the APK have to be on the same system mount. Android 9 and
later can refuse to boot in enforce mode if a priv-app requests a privileged
permission that this XML does not name. Do not drop the XML.

### 4. Check that the grants stuck

After reboot:

```sh
adb shell dumpsys package ch.swhizkid.tailtrace | grep -E 'READ_PRECISE_PHONE_STATE|WRITE_SECURE_SETTINGS|NETWORK_SETTINGS'
```

`READ_PRECISE_PHONE_STATE` and `WRITE_SECURE_SETTINGS` should show granted.
`NETWORK_SETTINGS` should not. The in-app log line
`READ_PRECISE_PHONE_STATE not granted` means the allowlist or the priv-app
path was missed.

A later debug build signed with the same `debug.keystore` and a higher
`versionCode` can update the system copy with `adb install -r`. That update
path has not been tried on hardware in this tree.

---

## Settings

Gear icon, top right.

- **Detection sources.** BLE, WiFi, OSM, Waze, aircraft, commercial, cell,
  tracker. A change applies on the next start. While a scan is running,
  **Restart scan to apply** stops and starts in one tap.
- **Privileged extras.** Optional unthrottled WiFi scan when
  `WRITE_SECURE_SETTINGS` is actually granted.
- **RF-silent.** The app does not transmit: opportunistic BLE, no WiFi
  `startScan`, no classic inquiry. Detection is slower.
- **Distances.** OSM and Waze proximity, set in Settings.
- **Waze police feed.** Proxy token, encrypted on device. Empty means off.
- **Alerts.** Vibration on escalation (on by default).
- **Floating threat circle.** Needs the system overlay permission.
- **Appearance.** System, dark, or light. Default is dark.

---

## Known gaps

- The aircraft registry is a US law-enforcement table. A Swiss-tuned app can
  miss local police airframes and still flag US hexes that are irrelevant here.
- Waze does nothing without a proxy token for a host this project does not run.
  An empty or dead token is an off source, not a degraded one.
- OSM and Waze scores are step values (very near / near, plus small Waze
  trust nudges). Aircraft uses a distance curve. The two models are not the
  same, and neither is locked to a test.
- Privileged permissions are documented under [Privileged install](#privileged-install). That procedure has not been run on a device from this tree. `NETWORK_SETTINGS` stays ungranted even when the allowlist is installed, because it is signature-only.
- The tracker ring is the only transmit path. It has not been tried against
  an AirTag or another tag on hardware in this tree.
- The live circle, catalog, and watch list have not been checked together on
  a phone: start, stop, screen off, and a reboot.

---

## License

TailTrace is released under the MIT License. The full text is in `LICENSE`.

This tree includes the OVERWATCH history it was forked from. That upstream
project did not ship a `LICENSE` file in the commit this fork is based on
(v0.5.13). Third-party libraries (AndroidX, osmdroid, and the rest of the
Gradle graph) keep their own licenses.

## Disclaimer

This is a situational-awareness tool for surveillance infrastructure in
public space. Rules on radio monitoring and police-tracking apps differ by
place. You are responsible for what is legal where you are.
