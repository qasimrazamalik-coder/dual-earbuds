# Dual Earbuds — Phone Link Protocol v2

Shared contract between the Windows app (Dual Earbuds Pro), the browser player page it serves,
and the Android companion app. Everything runs on the local network; there is no cloud server.

## Transport

- The Windows app runs one TCP server (default port **8790**, configurable 1024–65535) speaking
  plain HTTP/1.1 and WebSocket (RFC 6455) on the same port.
- Every request must carry the link key `k` (8 characters, A–Z and 2–7, case-insensitive) as a
  query parameter. Requests with a missing or wrong key get **HTTP 403**.
- The full link is `http://<laptop-ip>:<port>/?k=<KEY>`. This exact URL is what the QR code holds.
  The Android app parses host, port and key out of it.

## HTTP endpoints

| Method/path | Response |
|---|---|
| `GET /?k=KEY` | The browser player page (text/html). |
| `GET /info?k=KEY` | JSON: `{"app":"DualEarbuds","protocol":2,"version":"2.0.0","pc":"<PC name>","rate":48000,"channels":2,"format":"s16le"}` |
| `GET /favicon.ico` | 204 No Content (no key needed). |
| anything else | 404. |

`rate` is the current capture sample rate (0 if mirroring is stopped).

## WebSocket

`GET /ws?k=KEY&client=<web|android>&name=<url-encoded device name>` with the usual upgrade headers.

### Server → phone

**Binary message = one audio packet** (little-endian):

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | int32 sample rate (e.g. 48000) |
| 4 | 4 | uint32 sequence number (starts at 0, +1 per packet, wraps) |
| 8 | n×4 | PCM, signed 16-bit, stereo interleaved (L,R,L,R…) |

Packets are about 10 ms each. A phone that falls behind gets old packets dropped on the server
(queue limit ≈ 600 ms); it should detect gaps from the sequence number.

**Text messages (JSON):**

- On connect: `{"type":"hello","protocol":2,"pc":"LAPTOP-NAME","version":"2.0.0","rate":48000}`
- Every 2 s: `{"type":"ping","t":<server ms since start>}`
- When mirroring stops/starts: `{"type":"state","mirroring":true|false,"rate":48000}`

### Phone → server (text JSON, client frames are masked as RFC 6455 requires)

- Reply to ping: `{"type":"pong","t":<same t>}` — lets the server show round-trip time.
- Every 5 s and whenever something changes:
  `{"type":"status","device":"Redmi Note 12","earbuds":"Evo Z-Buds","battery":80,"volume":100,"bufferMs":180,"playing":true}`
  - `earbuds`: connected Bluetooth audio device name, or `""` if none/unknown.
  - `battery`: earbuds battery percent 0–100, or `-1` if the earbuds don't report it.
  - `volume`: the player's own volume 0–150 (%).
- Unknown message types must be ignored by both sides.
- Close: standard close frame (opcode 8). Server also treats a dropped TCP connection as a close.

## Limits

- Max 8 phones at once (9th gets HTTP 503).
- Header block max 8 KB, client text message max 4 KB (larger → connection closed).
- Server closes clients that send nothing for 30 s (web player sends status every 5 s, so it stays alive).
