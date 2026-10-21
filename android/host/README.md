# `:host` — Share this phone

Android library that turns the phone into a Dual Earbuds **host** (protocol v3 + v3.1, see
`docs/protocol.md`): it captures other apps' sound with AudioPlaybackCapture (Android 10+) and serves it
to listeners (the app, the Windows app, or any browser) over HTTP + WebSocket on port 8790.

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

- `@Composable fun HostScreen(onBack: () -> Unit)` — the whole feature. Colours come from the caller's
  `MaterialTheme` (light and dark). Below Android 10 it explains that sharing needs Android 10.
- `HostController.state: StateFlow<HostUiState>` — status, link, listeners (read-only use is fine).
- `HostService` — foreground service (`mediaProjection|microphone`) owning capture, server, mDNS and mic.

## What is inside

| Folder | Files | JVM-tested |
|---|---|---|
| `server/` | `HostServer` (HTTP/WS, limits, lockout, PIN, ping 2 s, clock 1 s), `ListenerSession`, `WsCodec`, `HttpHead`, `Outbox`, `GuessGuard`, `RoomPin`, `RateLimiter`, `ClockTrack`, `HostMessages`, `MiniJson`, `LinkKey`, `AudioPacket` | yes |
| `audio/` | `Packetizer` (10 ms packets + capture time), `MicRing`, `TalkMixer` (−12 dB duck, soft limiter), `SpectralDenoiser` + `Fft`, `NoiseCancel` | yes |
| `net/` | `LinkBuilder`, `Discovery` (TXT rules) — tested; `HostNetwork`, `NsdAdvertiser` (Android) | partly |
| `capture/` | `PlaybackCapture`, `MicCapture`, `AppCatalog` | no (Android) |
| `remote/` | `MediaRemote` (media keys) | no (Android) |
| `ui/` | one file per section, `QrCode` (zxing core) | no |

The browser player is **not** forked: the `copyWebPlayer` task copies `../../shared/web-player.html`
into generated assets at build time.

## Behaviour notes

- **Permissions.** Android requires `RECORD_AUDIO` for AudioPlaybackCapture itself, so it is asked when
  sharing starts (with a one-line reason); the microphone is only opened while Talk-over is on.
  `POST_NOTIFICATIONS` is asked once, optional.
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
framing, lockout, PIN, clock/sync, remote rate limit, packetizer, mixer, denoiser, link/TXT rules).
