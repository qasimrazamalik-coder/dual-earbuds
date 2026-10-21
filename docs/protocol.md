# Dual Earbuds — Link Protocol v3

The shared contract between every **host** (the Windows app, or an Android phone in *Share this phone* mode)
and every **listener** (the Android app, or the browser player page any host serves).
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

`GET /ws?k=KEY&client=<web|android>&name=<url-encoded device name>` with the usual upgrade headers.

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

The updater downloads only from `github.com`/`objects.githubusercontent.com` over HTTPS and only when the user agrees.
