# Built-in assistant (local RAG) and MCP server

Both run on the user's own devices. Nothing is sent to any cloud service by the app.

## 1. Help content — `docs/help/`

The single source of truth for "how do I…" answers, bundled into both apps at build time
(Windows: embedded resources via `windows/build.ps1`; Android: a Gradle copy task into generated assets,
like `shared/web-player.html` — never a forked copy).

Format, one Markdown file per topic:

```
# Topic title
<!-- keywords: comma, separated, extra, search, words (English + Roman Urdu) -->

## A question a user would ask?
Short direct answer first (1–3 sentences). Then steps if needed:
1. …
2. …
Platform notes: **Windows:** … **Android:** …
```

Rules: every `##` section is one retrievable chunk and must make sense on its own (repeat the subject, no
"as above"). Plain words, specific numbers, current app labels exactly as shown in the UI. No emoji.

## 2. Assistant ("Ask")

A page/screen named **Ask** in both apps. The user types a question; the app answers from the help content plus
**live facts** about the current session.

### Retrieval (both platforms, pure code, no ML runtime)
- Chunk = one `##` section (title + body). Tokenise: lowercase, split on non-letters/digits, drop a small stop-word
  list, light English stemming (strip `ing`, `ed`, `es`, `s`, `ly` when the stem stays ≥ 3 letters).
- Synonyms/Roman Urdu expansion from `docs/help/_synonyms.txt` (lines `word: alt1, alt2`), e.g.
  `awaz: sound, audio`, `der: delay, late`, `chal nahi raha: not working`.
- Score = BM25 (k1 1.2, b 0.75) over title (weight 2×) + body + keywords comment (1.5×). Return top 3 chunks
  with score ≥ a floor; below the floor say "I don't know that one yet" and show the 3 closest topics.
- **Live facts**: at question time the app builds 4–10 short fact lines from its own state (sync priority, P,
  each ear's latency and sync error, network type, Wi-Fi band, firewall/UDP state, battery saver, last error,
  listeners and their need). Facts are matched by the same scorer and shown in a "Right now" box when relevant
  (e.g. "Why is the sound late?" → `P is 238 ms because Redmi's earbuds need 230 ms`).

### Answer
- **Default (works everywhere, offline):** extractive — the best chunk's answer paragraph and steps, the
  "Right now" facts, then "Related" links to the next two chunks. Tapping a related link shows it.
- **Optional local model (Windows only, off by default):** if Ollama answers on `http://127.0.0.1:11434`, a
  setting "Use a local AI model" lists installed models (`/api/tags`). The prompt contains only the retrieved
  chunks + live facts and the instruction to answer only from them and say "not in the help" otherwise.
  Stream the answer (`/api/generate` with `stream:true`); show the sources under it. Timeout 30 s → fall back
  to the extractive answer. Never send anything to a non-loopback address.
- Questions and answers are not stored beyond the current session; nothing is logged with the link key or PIN.

## 3. MCP server (Windows app) — for Claude, ChatGPT, Codex, Cursor and other AI assistants

Lets an AI assistant on the same laptop check and control Dual Earbuds ("why are the phones behind?",
"lower the Redmi to 60 %", "start sharing and give me the link").

### Safety
- Off by default: **Settings → AI assistants → Allow AI assistants to control Dual Earbuds**.
- Loopback only. A random 32-byte token in `%LOCALAPPDATA%\DualEarbuds\mcp-token` (created when the setting is
  turned on, rotated with a button). Requests without it get 401.
- Tools never return the room PIN. `get_join_link` returns the link (it is meant to be shared) and says so.
- Destructive tools carry MCP annotations (`destructiveHint: true`): `stop_sharing`, `kick_listener`.
- Every tool call is shown for 3 s as a small toast/tray balloon ("Claude set Redmi volume to 60 %") and kept in a
  "Recent assistant actions" list (last 50) in Settings.

### Transports
1. **stdio** — `DualEarbuds.exe --mcp`. For Claude Desktop, Claude Code, Codex CLI, Cursor, Windsurf, VS Code.
   It reads the token file and forwards each call to the running app over loopback HTTP; if the app isn't
   running it starts it with `--tray` and waits up to 5 s. JSON-RPC 2.0, newline-delimited, MCP protocol
   revision 2025-06-18 (accept 2025-03-26 too): `initialize`, `notifications/initialized`, `ping`,
   `tools/list`, `tools/call`, `resources/list`, `resources/read`. Logs go to stderr only.
2. **Streamable HTTP** — `POST http://127.0.0.1:8790/mcp` with header `Authorization: Bearer <token>`, same
   methods, single JSON responses (no SSE needed). For clients that take a local URL. ChatGPT custom
   connectors need a public HTTPS URL: the docs explain that this needs a tunnel the user sets up themselves,
   and the app does not open one.

### Tools (JSON Schema inputs; outputs are short text plus `structuredContent`)
| Tool | Input | Does |
|---|---|---|
| `get_status` | – | sharing on/off, source, sync priority, P, outputs, listeners, network, warnings |
| `list_outputs` | – | each local output: id, name, on, volume, delay, latency, calibrated |
| `set_output` | `id`, optional `on`, `volume` (0–150), `delayMs` (0–500), `eq` | change one output |
| `list_listeners` | – | each listener: id, name, device, earbuds, battery, volume, needMs, syncErrMs, UDP/WS |
| `set_listener_volume` | `id`, `volume` (0–150) | |
| `kick_listener` | `id` | destructive |
| `start_sharing` / `stop_sharing` | – | stop is destructive |
| `set_sync_priority` | `priority`: `lipsync` \| `together` | |
| `set_party_mode` | `on`, optional `requirePin` | never returns the PIN |
| `get_join_link` | – | link + QR as text art is NOT returned; just the link and a note that it is shareable |
| `run_setup_check` | – | each check: status, why, fix |
| `get_diagnostics` | – | the Copy diagnostics text (already redacted) |
| `ask_help` | `question` | the assistant's extractive answer + sources (no local model) |

Resources: `dualearbuds://help/<file>` (each help file), `dualearbuds://status` (same as `get_status`).

### Setup helper
Settings → AI assistants shows copy buttons that put ready-to-paste config on the clipboard:
- Claude Desktop (`claude_desktop_config.json` → `mcpServers.dual-earbuds` with `command` = full exe path,
  `args: ["--mcp"]`), Claude Code (`claude mcp add dual-earbuds -- "<exe>" --mcp`), Codex CLI
  (`~/.codex/config.toml` `[mcp_servers.dual-earbuds]`), Cursor/VS Code (`mcp.json`), and a generic
  "HTTP: URL + token" block. The help file `ai-assistants.md` explains each.
