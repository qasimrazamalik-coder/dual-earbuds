# Dual Earbuds — Link Protocol v3 (with v3.1 and v3.2 additions)

The shared contract between every **host** (the Windows app, or an Android phone in *Share this phone* mode)
and every **listener** (the Android app, the Windows app in *Listen to another device* mode — laptop to laptop —,
or the browser player page any host serves, opened from the link on any device).
Everything runs on the local network or a hotspot. There is no cloud server.

v3 is backward compatible with v2: a v2 listener keeps working with a v3 host, and the reverse, because
unknown fields and message types are ignored.

## Transport

- A host runs one TCP server (default port **8790**, configurable 1024–65535) speaking plain HTTP/1.1
  and WebSocket (RFC 6455) on the same port.
- Every request must carry the link key `k` (8 characters, A–Z and 2–7, case-insensitive) as a query
  parameter. A missing or wrong key gets **HTTP 403**.
- The link is `http://<host-ip>:<port>/?k=<KEY>`. This exact URL is what the QR code holds.
- **Guessing protection:** a device that sends 10 *different* wrong keys within 60 s is ignored for 5 minutes.
  Repeating one old key (a stale tab after *New link*) never triggers it.

## HTTP endpoints

| Method/path | Response |
|---|---|
| `GET /?k=KEY` | The browser player page (`shared/web-player.html`, text/html). |
| `GET /info?k=KEY` | JSON, see below. |
| `GET /favicon.ico` | 204 No Content (no key needed). |
| anything else | 404. |

`/info`:

```json
{"app":"DualEarbuds","protocol":3,"version":"3.0.0","host":"windows","pc":"LAPTOP-NAME",
 "rate":48000,"channels":2,"format":"s16le","party":false,"maxClients":8}
```

- `host`: `"windows"` or `"android"`.
- `rate`: current sample rate, `0` while the host isn't sharing sound.
- `party`: Party mode on; `maxClients` is then 16 (8 otherwise).

## WebSocket

`GET /ws?k=KEY&client=<web|android|windows>&name=<url-encoded device name>` with the usual upgrade headers.

### Host → listener

**Binary message = one audio packet** (little-endian):

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | int32 sample rate (e.g. 48000) |
| 4 | 4 | uint32 sequence number (starts at 0, +1 per packet, wraps) |
| 8 | n×4 | PCM, signed 16-bit, stereo interleaved (L,R,L,R…) |

Packets carry **10 ms** of sound. A listener that falls behind gets old packets dropped on the host
(queue limit ≈ 600 ms); it detects gaps from the sequence number.

**Text messages (JSON):**

| Message | When |
|---|---|
| `{"type":"hello","protocol":3,"pc":"NAME","version":"3.0.0","host":"windows","rate":48000,"party":false}` | on connect |
| `{"type":"ping","t":<host ms since start>}` | every 2 s |
| `{"type":"state","mirroring":true,"rate":48000}` | sharing starts/stops |
| `{"type":"party","on":true}` | Party mode switched |
| `{"type":"control","volume":80}` | the host set this listener's volume (0–150 %); the listener applies it and reports it back in `status` |
| `{"type":"kick","reason":"Removed by the host"}` | sent just before the host closes this listener; the listener must not auto-reconnect after a kick |

### Listener → host (text JSON; client frames are masked as RFC 6455 requires)

- Reply to ping: `{"type":"pong","t":<same t>}` (the host shows round-trip time).
- Every 5 s and whenever something changes:

```json
{"type":"status","device":"Redmi Note 12 Pro","earbuds":"Evo Z-Buds","battery":80,
 "volume":100,"bufferMs":60,"underruns":0,"playing":true}
```

  - `earbuds`: connected Bluetooth audio device name, or `""`.
  - `battery`: earbuds battery 0–100, or `-1` when not reported.
  - `volume`: the listener's volume 0–150 (%).
  - `bufferMs`: current playback buffer; `underruns`: count since connect.
- Unknown message types and fields are ignored by both sides.
- Close: standard close frame (opcode 8). A dropped TCP connection also counts as a close.

## Low-latency playback (listeners)

- Adaptive buffer: start at **60 ms**, never below **30 ms**, at most **300 ms**.
- On an underrun (nothing to play): +20 ms, and stay there for 10 s.
- After 20 s with no underruns: −5 ms per 5 s down to the minimum.
- A manual *Delay* setting in the listener adds to this and is never changed automatically.

## Joining

Every listener joins the same way: scan the QR code **or paste the link** (`http://IP:PORT/?k=KEY`).
Listeners also accept `IP:PORT/?k=KEY`; a missing port means 8790; keys are case-insensitive.
Opening the link in any browser (phone, laptop, tablet, iPhone) plays the sound with no install.

## v3.1 additions

All optional; a listener or host that does not know them ignores them.

### Room PIN (Party mode)

- The host may set a 4–6 digit **PIN** (off by default). `/info` then carries `"pin":true`.
- Listeners add `&p=<PIN>` to `/ws` (and the browser page asks for it). Missing/wrong PIN → HTTP 403;
  wrong PINs count toward the guessing lockout exactly like wrong keys.
- The QR code and link never contain the PIN; it is told to guests separately.

### Clock sync (everyone plays the same packet at the same moment)

- Listener → host every 2 s (and 5× quickly after connect): `{"type":"sync","c":<listener ms>}`.
- Host → listener: `{"type":"sync","c":<same c>,"h":<host ms since start>}`.
  Listener offset = `h − (c + now)/2`; keep the sample with the smallest round trip of the last 8.
- Host → listener every 1 s: `{"type":"clock","seq":<uint32>,"h":<host ms when that packet was captured>}`
  and in `hello`: `"playoutMs":<target delay, default 120>`.
- A synced listener plays packet `seq` at host time `h(seq) + playoutMs − outputLatency + delay`, where
  `outputLatency` is the device's measured/calibrated output latency (played that much earlier so the sound
  *leaves the earbuds* on time) and `delay` the manual Delay (always makes this listener later, as in buffer mode).
  It speeds up/slows down by at most ±0.5 % (resampling) to stay within ±5 ms; beyond ±40 ms it skips/inserts.
- Listeners that can't sync fall back to the adaptive buffer above.

### Calibration

- Listener-local: the user holds one earbud to the phone/laptop mic, the app plays 8 clicks, finds them in the
  mic signal by cross-correlation, and stores `outputLatency` per output device (by name). Nothing on the wire.

### Packet-loss concealment

- Listener-local: a missing packet (sequence gap) is filled by repeating the last 10 ms with a fade to silence
  over ≤ 30 ms, then crossfaded back in. No clicks.

### Discovery (mDNS / DNS-SD)

- Hosts advertise `_dualearbuds._tcp.local` on their port with TXT `v=3`, `name=<device>`, `host=windows|android`,
  `pin=0|1`, and `k=<KEY>` **only while the network is Private** (or the user turned "Show on this network" on).
- Listeners list found hosts; one without `k` asks for the link/QR.

### Remote control

- Host setting "Let listeners control playback" (off by default). `/info` and `hello` carry `"remote":true` when on.
- Listener → host: `{"type":"remote","action":"playpause|next|prev|volup|voldown"}`.
  The host presses the matching media key; ignored when the setting is off.

### Talk-over

- Host → listener: `{"type":"talk","on":true|false}` while the host mixes its microphone into the stream
  (the music ducks by 12 dB). Informational only.

### Sound source

- `/info` and `hello` carry `"source":"all"` or `"source":"app"` when the host shares only one app's sound
  (Windows process loopback, Android `addMatchingUid`). The shared app's name is `"sourceApp":"<name>"` in
  `/info` (where `"app"` already means `"DualEarbuds"`) and `"app":"<name>"` in `hello`. Listeners read
  `sourceApp` first, then `hello.app`.

### Equalizer

- Listener-local presets `flat|bass|voice|movie`. The host may suggest one: `{"type":"control","eq":"movie"}`.

## v3.2 additions — one deadline for every ear, and a transport that doesn't stall

### Common deadline (fixes "phones lag behind the laptop")

- Every ear — the host's own outputs included — hears the sample captured at host time `t` at `t + P`.
  `P` (playout) is chosen by the host, not fixed at 120 ms.
- Listeners report what they need in `status`: `"needMs":<jitterP99 + outputLatency + 10>`
  (jitter p99 over the last 30 s of packet arrivals; outputLatency calibrated or from the platform).
- The host sets `P = clamp(max(needMs of listeners in sync mode, host local need), 40, 400)` where host local need =
  the slowest *ticked* local output's latency (+ the Windows mixer buffer). It announces
  `{"type":"playout","ms":P}` when P changes; increases apply at once, decreases at most 5 ms per second
  (no audible jumps); a listener can't pull P above 400.
- Windows host: each local output is delayed by `P − its outputLatency` (plus its manual Delay), so the laptop's
  earbuds play at the same moment as every phone.
- Android host: the captured app's own sound plays on the host phone immediately and cannot be delayed. The host
  therefore uses its own earbuds' calibrated latency as its local need, so listeners line up with the host's ear.
  If the host's earbuds aren't calibrated, assume 180 ms for Bluetooth, 20 ms for wired/speaker.
- `hello` keeps `playoutMs` (= current P). Listeners from v3.1 that ignore `playout` still work (they use hello's value).

### UDP audio (native listeners)

TCP stalls the whole stream for a retransmit whenever Wi-Fi drops one frame; that is the "buffering".
Native listeners (Android, Windows) therefore get audio over UDP; the WebSocket stays for control and as fallback.
Browsers keep WebSocket audio.

- `hello` carries `"udp":<port>` (the host binds a UDP socket on the same port number as TCP).
- Listener → host (UDP, every 1 s and once at start): `DE3U` + 1 byte type 1 + 16-byte session token
  (`hello.token`, random per WebSocket session). The host learns the listener's UDP address from it and starts
  sending; if no keepalive arrives for 5 s it stops sending UDP to that listener.
- Host → listener UDP datagram: `DE3A` + uint32 seq + int32 rate + 10 ms PCM (same as the WS packet body), followed by
  **the previous packet again** (seq−1, same layout) — simple redundancy, so a single lost datagram costs nothing.
  Datagram ≈ 3.9 KB (fragmented by IP; fine on LAN). Listeners dedupe by seq.
- If the listener receives no UDP within 2 s of `hello`, it sends `{"type":"udp","ok":false}` and the host sends
  audio over the WebSocket as before. The listener may switch to UDP later with `{"type":"udp","ok":true}`.
- While UDP works, the host sends no binary audio on that WebSocket.
- Security: the token proves the UDP sender is the WebSocket session that passed key/PIN checks; datagrams with an
  unknown token are dropped silently. Rate-limit keepalives per IP.

### Network and device hygiene (both platforms)

- Sockets: TCP_NODELAY on; small send buffers on the host (≤ 64 KB per listener) so a slow listener can't build
  seconds of backlog; real-time thread priority for capture, send and playback threads (Windows MMCSS "Pro Audio",
  Android `THREAD_PRIORITY_URGENT_AUDIO`).
- Android: `WifiManager.WifiLock` `WIFI_MODE_FULL_LOW_LATENCY` (API 29+, else `HIGH_PERF`) while hosting or listening,
  a partial wake lock, foreground service, AudioTrack `PERFORMANCE_MODE_LOW_LATENCY`.
- Windows: `SetThreadExecutionState(ES_SYSTEM_REQUIRED | ES_CONTINUOUS)` while sharing/listening; opt the process out of
  power throttling (EcoQoS) so battery saver doesn't stretch timers.

## Limits

- Max 8 listeners (16 in Party mode); the next one gets HTTP 503.
- Max 16 connections in the HTTP/handshake stage at once; the whole request head must arrive within 5 s.
- Header block max 8 KB; listener text message max 4 KB (larger closes the connection).
- Control frames (ping/pong) max 125 bytes.
- The host closes listeners that send nothing for 30 s.

## Hosts

| Host | Captures | Notes |
|---|---|---|
| Windows app | everything the laptop plays (WASAPI loopback) | also plays to the laptop's own Bluetooth/wired outputs |
| Android app, *Share this phone* | other apps' sound via `AudioPlaybackCapture` (Android 10+) | apps that opt out of capture (e.g. Netflix) stay silent; the app explains this |

## Updates

Both apps check the **latest GitHub release** of `qasimrazamalik-coder/dual-earbuds`
(`GET https://api.github.com/repos/qasimrazamalik-coder/dual-earbuds/releases/latest`) at most once a day
and on demand from Settings. Tags are `vMAJOR.MINOR.PATCH`. Release assets are named exactly:

- `DualEarbuds-Setup.exe` — Windows installer (runs as an update when the app is installed)
- `DualEarbuds.apk` — Android app, signed with the same key every release

The updater downloads only from `github.com`, `objects.githubusercontent.com` and `release-assets.githubusercontent.com` over HTTPS and only when the user agrees.
