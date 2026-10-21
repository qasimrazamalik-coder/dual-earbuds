# Dual Earbuds — Android companion

Plays your laptop's sound on a **second pair of Bluetooth earbuds connected to your phone**.
The Windows app (Dual Earbuds Pro) streams the sound over Wi-Fi using the phone link protocol v2
(`../PROTOCOL.md`). This app receives it and keeps playing with the screen off.

- Package / applicationId: `com.dualearbuds.companion`
- minSdk 26 · targetSdk/compileSdk 35 · Kotlin 2.0.20 · AGP 8.5.2 · Gradle 8.9 · JDK 17
- Jetpack Compose (Material 3), OkHttp web socket, AudioTrack, foreground `mediaPlayback` service

## Build on Windows

### 1. Install JDK 17
Install Temurin 17 (https://adoptium.net) and set `JAVA_HOME`, for example:

```bat
setx JAVA_HOME "C:\Program Files\Eclipse Adoptium\jdk-17.0.12.7-hotspot"
```

Open a new terminal and check: `java -version` shows 17.

### 2. Install the Android SDK command-line tools
1. Download "Command line tools only" for Windows from https://developer.android.com/studio#command-tools
2. Unzip to `C:\Android\cmdline-tools\latest\` (the `bin` folder must end up at `C:\Android\cmdline-tools\latest\bin`).
3. Set the environment:

```bat
setx ANDROID_HOME "C:\Android"
setx PATH "%PATH%;C:\Android\cmdline-tools\latest\bin;C:\Android\platform-tools"
```

4. In a new terminal install the packages and accept the licences:

```bat
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
sdkmanager --licenses
```

5. Tell Gradle where the SDK is: create `local.properties` in this folder with

```properties
sdk.dir=C\:\\Android
```

(or rely on `ANDROID_HOME`).

### 3. Generate the Gradle wrapper (one time)
The repository contains `gradle/wrapper/gradle-wrapper.properties` (pointing at `gradle-8.9-bin.zip`) but **not**
`gradle-wrapper.jar` / `gradlew.bat`. Generate them once with any installed Gradle (e.g. `choco install gradle`
or `scoop install gradle`, or unzip Gradle 8.9 and put its `bin` on PATH):

```bat
cd path\to\deb\android
gradle wrapper --gradle-version 8.9
```

Opening the folder in Android Studio also creates them.

### 4. Build

```bat
gradlew assembleDebug
```

The APK is at `app\build\outputs\apk\debug\app-debug.apk`.
`gradlew assembleRelease` also works (minify is off; it is signed with the debug key so it installs — replace
the signing config before publishing).

### 5. Install with adb
Enable Developer options → USB debugging on the phone, connect it, then:

```bat
adb devices
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Using it

1. On the laptop: open Dual Earbuds, turn on **Phone link**. It shows a QR code and a link like
   `http://192.168.1.20:8790/?k=ABCD2345`.
2. Pair the **second** earbuds with the phone (normal Bluetooth pairing).
3. In the app: **Scan QR** (or **Paste** the link) → it checks the link with `GET /info` → press **START**.
4. Allow notifications and Bluetooth access when asked (Bluetooth access is only used to show the earbuds' name
   and battery).

The phone and laptop must be on the same Wi-Fi (or the laptop connected to the phone's hotspot).

## First-run setup per brand ("Keep running in background")

The guide opens automatically on first launch (lock icon on Home to reopen it). It detects the brand and has
buttons that open the right settings page; if a page doesn't exist on your version, App info opens instead.

| Brand | What to switch on |
|---|---|
| **All phones** | Ignore battery optimization → *Allow* (green check when done). Keep the playing notification. |
| **Xiaomi / Redmi / POCO** (MIUI / HyperOS) | Security → Permissions → **Autostart** → on. App info → **Battery saver → No restrictions**. Lock the app in Recents (long-press → lock). |
| **Oppo / Realme / OnePlus** (ColorOS / realme UI / OxygenOS) | **Auto launch / Auto startup** → on. App info → Battery usage → **Allow background activity** (and foreground activity). Lock in Recents. |
| **Vivo / iQOO** | i Manager → **Background startup / Autostart** → on. Battery → **High background power consumption** → allow. Lock in Recents. |
| **Samsung** (One UI) | App info → Battery → **Unrestricted**. Device care → Battery → Background usage limits → **Never sleeping apps** → add Dual Earbuds; make sure it isn't in Sleeping / Deep sleeping apps. |

If sound still stops with the screen off, raise the buffer slider to 250–300 ms.

## How it works (code map)

| File | Role |
|---|---|
| `net/LinkParser.kt` | Parses `http://IP:PORT/?k=KEY` (key = 8 chars A–Z, 2–7; default port 8790). |
| `net/InfoClient.kt` | `GET /info?k=KEY` validation (403 = wrong key, 503 = 8 phones already). |
| `net/StreamConnection.kt` | OkHttp web socket `/ws?k=KEY&client=android&name=<Build.MODEL>`; decodes the 8-byte little-endian header (int32 rate, uint32 seq) + s16le stereo; answers `ping` with `pong`; 8 s silence watchdog. |
| `audio/AudioEngine.kt` | Jitter buffer (target 60–600 ms, default 180; trims oldest above target+250 ms; refills to target after an underrun) and an `AudioTrack` (USAGE_MEDIA / CONTENT_TYPE_MUSIC, PCM 16-bit stereo, streaming) on its own urgent-audio thread; software gain 0–150 % with clipping; recreated on sample-rate change. |
| `service/PlaybackService.kt` | Foreground `mediaPlayback` service: reconnect with backoff 1 s → 10 s, partial wake lock, Wi-Fi lock (LOW_LATENCY on API 29+, HIGH_PERF otherwise), MediaSession + media notification (Vol −, Stop, Vol +), status message every 5 s and on change, `START_STICKY`. |
| `bt/EarbudsMonitor.kt` | Connected Bluetooth audio device via A2DP/headset profile proxies; battery from `BATTERY_LEVEL_CHANGED` and `getBatteryLevel()` (reflection). |
| `power/BackgroundSettings.kt` | Brand detection, OEM settings intents with fallbacks, guide text. |
| `ui/…` | Compose screens: Home, Background guide, About. The QR scanner is zxing's `CaptureActivity`. |

Earbud / lock-screen **pause** mutes the sound and keeps the link open (play resumes instantly); **Stop** ends
playback and disables auto-restart.
