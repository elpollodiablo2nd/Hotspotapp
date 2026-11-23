# Hotspot Link

Android app that turns the Wi-Fi hotspot **ON** when your phone connects to a chosen
Bluetooth device and **OFF** when that device disconnects. The UI is skinned with the
Mistral pixel-art style (`mistral-pixel-ui` skill: Press Start 2P / VT323, rainbow
gradient title, pixel M logo, chunky borders, CRT scanlines).

## How it works

- `MainActivity` renders a single-file pixel-art page (`app/src/main/assets/index.html`,
  styled with the skill's `pixel-base.css` + `mistral-m.svg`) inside a `WebView` and talks
  to Kotlin through the `PixelBridge` `@JavascriptInterface`.
- `HotspotService` is a **foreground service** (`connectedDevice` type) holding a partial
  wake lock, so the app stays awake and listening even with the screen off. It registers
  a runtime receiver for `BluetoothDevice.ACTION_ACL_CONNECTED` /
  `ACTION_ACL_DISCONNECTED` and reacts only when the saved device's MAC address matches.
- `BootReceiver` restarts the listener after reboot / app update if listening was enabled.
- `HotspotController` starts/stops the hotspot.

## Hotspot control on modern Android (important)

Google only lets **system apps** toggle the full internet-sharing hotspot
(`TETHER_PRIVILEGED`). For normal apps the reliable public path is
`WifiManager.startLocalOnlyHotspot(...)`, which this app uses as the primary mechanism.
The hotspot always uses the **same SSID and password on every activation**. Start order:
1. **SET AP** values if you set them (hidden `SoftApConfiguration.Builder`, every
   `setPassphrase` variant tried defensively);
2. otherwise the **system hotspot's own SoftApConfiguration object** passed straight to
   the hidden configured `startLocalOnlyHotspot` overload — i.e. the same name/password as
   your configured system hotspot;
3. otherwise a random pair generated once, persisted, and reused;
4. last resort: the public default `startLocalOnlyHotspot(callback, handler)` (fresh random
   credentials) if the device blocks all configured calls.
The live SSID/password are read back from the hotspot reservation and shown in the UI
whenever it activates.
A legacy `setWifiApEnabled` reflection fallback is attempted as best effort on OEM builds
where it still works. The UI also offers an **OPEN WI-FI PANEL** button
(`Settings.Panel.ACTION_INTERNET_CONNECTIVITY`) to toggle the full hotspot manually.

**Why connected devices report "no internet":** on Android 13+ every API that starts the
full internet-sharing hotspot (`ConnectivityManager.startTethering`,
`TetheringManager.startTetheredHotspot`, `setWifiApEnabled`) requires `TETHER_PRIVILEGED`,
a system-app-only permission (Android 16 blocks the reflection workaround entirely). The
app therefore first *tries* `startTethering(TETHERING_WIFI)` best-effort (works on some
OEM/system-signed builds — the UI then shows `tethered hotspot ON (internet shared)`), and
otherwise starts a **local-only** hotspot, which gives clients a Wi-Fi link to the phone but
no internet routing — the UI marks this as `[LOCAL-ONLY: NO INTERNET]`. For the car/Android
Auto use case the local-only link is usually enough (the phone keeps its own mobile data);
if the client device itself needs internet, enable the system hotspot manually via
**OPEN WI-FI PANEL**.

## Permissions

| Permission | Why |
|---|---|
| `BLUETOOTH_CONNECT` (31+) / `BLUETOOTH`, `BLUETOOTH_ADMIN` (≤30), `ACCESS_FINE_LOCATION` (≤30) | read paired devices, receive ACL broadcasts |
| `NEARBY_WIFI_DEVICES` (33+, `neverForLocation`) | start local-only hotspot on Android 13+ |
| `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE` | query/toggle Wi-Fi |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` | keep the listener alive (type `connectedDevice`) |
| `POST_NOTIFICATIONS` (33+) | show the persistent listener notification |
| `WAKE_LOCK` | stay awake while listening |
| `RECEIVE_BOOT_COMPLETED` | restart listener after reboot |
| `INTERNET` | load Google Fonts in the WebView UI |

For best reliability also disable battery optimization for the app
(Settings → Apps → Hotspot Link → Battery → Unrestricted).

## Usage

1. Pair the Bluetooth device (e.g. car head unit) in system settings.
2. Install the APK, grant the requested permissions.
3. Pick the device in the dropdown, press **SAVE**.
4. Press **START LISTENING**. Connect/disconnect the device — the hotspot follows.

## Build

- Android Studio (Hedgehog+): open the project, let it sync, Run. JDK 17 required.
- CLI: `gradle wrapper` once to generate `gradlew`, then `./gradlew assembleDebug`
  (Gradle 8.10.2, AGP 8.7.3, Kotlin 2.0.21, compileSdk 35, minSdk 26).

No third-party dependencies — platform Android APIs + Kotlin stdlib only.
