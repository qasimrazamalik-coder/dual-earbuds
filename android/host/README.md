# `:host` — Share this phone

Android library that turns the phone into a Dual Earbuds **host** (protocol v3 + v3.1 + v3.2, see
`docs/protocol.md`): it captures other apps' sound with AudioPlaybackCapture (Android 10+) and serves it
to listeners (the app, the Windows app, or any browser) over HTTP + WebSocket on port 8790, with UDP audio
on the same port number for native listeners.

## Add it to the app (exact lines)

`android/settings.gradle.kts`

```kotlin
include(":host")
```

`android/build.gradle.kts` (inside `plugins { }`, next to the application plugin)

```kotlin
id("com.android.library") version "8.5.2" apply false
```

`android/app/build.gradle.kts` (inside `dependencies { }`)

```kotlin
implementation(project(":host"))
```

Navigation (e.g. in `ui/AppNav.kt`), inside the app's `MaterialTheme`:

```kotlin
composable("host") { com.dualearbuds.host.HostScreen(onBack = { navController.popBackStack() }) }
```

and an entry point such as a "Share this phone" button calling `navController.navigate("host")`.

Nothing else: the service, permissions and `<queries>` merge in from this module's manifest.

## Public API

- `@Composable fun HostScreen(onBack: () -> Unit, localOutputLatencyMs: ((String) -> Double?)? = null, onCalibrate: (() -> Unit)? = null)`
  — the whole feature. Colours come from the caller's `MaterialTheme` (light and dark); the screen only picks
  roles (see "Theme roles" below). Below Android 10 it explains that sharing needs Android 10.
  - `localOutputLatencyMs` (optional): calibrated latency in ms of this phone's own output, by device name as
    Android reports it (Bluetooth: the earbuds' name). Same as setting `HostConfig.localOutputLatencyMs`.
  - `onCalibrate` (optional): opens the app's calibration screen; the Setup check then shows "Calibrate"
    next to uncalibrated earbuds.
- `HostConfig.localOutputLatencyMs` — the same provider, settable from `Application.onCreate` so the service
  has it even when the screen was never opened. When neither is set, the module reads the listener app's own
  calibration (preference file `dual_earbuds`, key `output_latency`, `name<TAB>ms` lines), else assumes
  180 ms for Bluetooth and 20 ms for wired/speaker.
- `HostController.state: StateFlow<HostUiState>` — status, link, listeners, `playoutMs` (P), `localOutput`.
- `HostService` — foreground service (`mediaProjection`, plus `microphone` only while talk-over is on).

Suggested app wiring (optional, both lines):

```kotlin
composable("host") {
    HostScreen(
        onBack = { navController.popBackStack() },
        localOutputLatencyMs = { name -> SoundSettings.outputLatency.value[name] },
        onCalibrate = { navController.navigate(Routes.CALIBRATE) }
    )
}
```

### Theme roles

| Design token | MaterialTheme role |
|---|---|
| Bg / Surface / Surface2 | `background` / `surface` / `surfaceContainerHigh` |
| Line (borders, hairlines, scope) | `outlineVariant` |
| Text / Muted | `onSurface` / `onSurfaceVariant` |
| Accent (live dot, primary action, selected segment, meters), Good | `primary` (`onPrimary` on it) |
| Warn / Bad | `tertiary` / `error` |

## v3.2 on the wire (host side) — what listeners must match

- **Common deadline.** `P = clamp(max(needMs of synced listeners, host local need), 40, 400)`; announced as
  `{"type":"playout","ms":P}`, also `hello.playoutMs`. Increases at once, decreases 5 ms per second; with
  nobody synced P follows at once. A synced listener without `needMs` (v3.1) counts as 120 ms.
  Host local need = this phone's own output latency (the captured app plays here immediately).
- **Listener `status` fields read:** `needMs` (int ms, clamped 0–400) and `syncErrMs` (int ms, this ear's
  offset from the deadline, + = late; drawn on the host's sync scope). `synced` as before.
- **UDP.** Native listeners (`client=android|windows`) get `"udp":<port>,"token":"<32 lowercase hex>"`
  in `hello` (16 random bytes per WebSocket session). Keepalive: `DE3U` + byte `1` + the 16 token bytes
  (21 bytes) to that port, at start and every 1 s. Audio datagram: `DE3A`, then block `uint32 seq, int32 rate,
  PCM (10 ms s16le stereo)`, then usually the previous packet as a second block of the same size
  (seq − 1); 3 860 bytes at 48 kHz with both blocks. Little-endian. Dedupe by seq.
  No keepalive for 5 s → back to WebSocket audio. `{"type":"udp","ok":false}` → WebSocket audio;
  `{"type":"udp","ok":true}` → UDP again. No binary audio on the WebSocket while UDP works. Keepalives:
  4/s per IP (burst 8); unknown tokens are dropped silently. Browsers keep WebSocket audio.
- **Hygiene.** 300 ms per-listener queues (WS and UDP), oldest dropped and counted (`late` in the row);
  64 KB TCP send buffers; TCP_NODELAY; capture, WebSocket writers and the UDP sender at
  `THREAD_PRIORITY_URGENT_AUDIO`; AudioRecord buffer ≥ 100 ms read 10 ms at a time.

## What is inside

| Folder | Files | JVM-tested |
|---|---|---|
| `server/` | `HostServer` (HTTP/WS, limits, lockout, PIN, ping 2 s, clock 1 s, P), `Playout` + `LocalOutput` (v3.2 deadline), `UdpAudio` + `UdpPacket` (v3.2 UDP), `ListenerSession`, `WsCodec`, `HttpHead`, `Outbox`, `GuessGuard`, `RoomPin`, `RateLimiter`, `ClockTrack`, `HostMessages`, `MiniJson`, `LinkKey`, `AudioPacket` | yes |
| `setup/` | `SetupCheck` (battery, background, Wi-Fi band, hotspot, earbuds, weak listeners, brand notes) | yes |
| `model/` | `Readout` (header readout, headline, sync scope maths) | yes |
| `device/` | `DeviceProbe`, `LocalOutputProbe` (AudioManager), `SetupFixes` (settings intents) | no (Android) |
| `audio/` | `Packetizer` (10 ms packets + capture time), `MicRing`, `TalkMixer` (−12 dB duck, soft limiter), `SpectralDenoiser` + `Fft`, `NoiseCancel` | yes |
| `net/` | `LinkBuilder`, `Discovery` (TXT rules) — tested; `HostNetwork`, `NsdAdvertiser` (Android) | partly |
| `capture/` | `PlaybackCapture`, `MicCapture`, `AppCatalog` | no (Android) |
| `remote/` | `MediaRemote` (media keys) | no (Android) |
| `ui/` | `Instrument` (design-system primitives: section label, panel, rows, segmented, slider, sync scope), one file per section, `QrCode` (zxing core) | no |

The browser player is **not** forked: the `copyWebPlayer` task copies `../../shared/web-player.html`
into generated assets at build time.

## Behaviour notes

- **Permissions — only those that help, each with a one-line reason; every denial keeps working.**
  - `RECORD_AUDIO`: Android requires it for AudioPlaybackCapture itself, so it is asked when sharing starts;
    the microphone is only opened while Talk-over is on. Denied for good → a line with an App info button.
  - `POST_NOTIFICATIONS` (13+): asked once, optional; the Setup check offers it again ("Stop button in the
    notification").
  - `BLUETOOTH_CONNECT`: **not asked.** AudioManager names the output and its type without it; Bluetooth
    on/off comes from `BluetoothAdapter.isEnabled` (legacy `BLUETOOTH`, max SDK 30).
  - `NEARBY_WIFI_DEVICES` / location: **not asked.** Wi-Fi frequency (5 vs 2.4 GHz) is not location data and
    the hotspot shows in this phone's own addresses.
  - `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (normal permission): lets the Setup check's "Allow" open Android's
    own exemption prompt.
- **Background, battery saver, screen off.** Foreground service with an ongoing notification (listeners, P,
  Stop sharing), a partial wake lock, Wi-Fi locks `FULL_LOW_LATENCY` (API 29+) and `HIGH_PERF`.
  The Setup check shows battery saver, background restriction and the battery-optimisation exemption with
  fix buttons, plus brand notes (Samsung never sleeping apps, Xiaomi/Redmi/POCO no restrictions + Autostart,
  Realme/OPPO/OnePlus allow background activity, vivo, Huawei/Honor).
- **Noise cancelling** is the documented spectral-gating fallback (smart-features.md #1), not the
  RNNoise port. Light = 50 % dry.
- **`/info` vs `app`.** `/info` keeps `"app":"DualEarbuds"` (listeners check it); the shared app's name
  goes in `"sourceApp"` there, and in `"app"` in `hello` as the protocol says.
- **PIN.** Required on `/ws`; the page and `/info` load without it so the page can ask; a wrong `p`
  anywhere counts toward the lockout. Never in the link, QR or TXT record.
- **Show on this network** (puts `k` in the mDNS TXT): on by default on this phone's hotspot, off on
  joined Wi-Fi (Android can't tell a home network from a café), user choice lasts for the session.

## Tests

`gradle :host:testDebugUnitTest` — pure Kotlin tests under `src/test` (server over real sockets,
framing, lockout, PIN, clock/sync, remote rate limit, common deadline P, UDP over real DatagramSockets with
simulated loss, slow-listener backpressure, setup check, readout, packetizer, mixer, denoiser, link/TXT rules).
