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
The hotspot always uses the **same SSID and password on every activation**: on first
start the app generates a random pair once, persists it, and reuses it (shown in the UI
and in the SET AP fields). You can override with the **SET AP** fields (prefilled from
the system hotspot config when the device allows reading it); the app then starts the
hotspot with that exact SSID/passphrase via the (hidden) configured
`startLocalOnlyHotspot` overload, falling back to a fresh random one only if the device
blocks the configured call. The live SSID/password are read back from the hotspot
reservation and shown in the UI whenever it activates.
A legacy `setWifiApEnabled` reflection fallback is attempted as best effort on OEM builds
where it still works. The UI also offers an **OPEN WI-FI PANEL** button
(`Settings.Panel.ACTION_INTERNET_CONNECTIVITY`) to toggle the full hotspot manually.

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
