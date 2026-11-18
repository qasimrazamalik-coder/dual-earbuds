# AI assistants and the Ask page
<!-- keywords: ai, assistant, mcp, claude, claude desktop, claude code, chatgpt, codex, cursor, vs code, windsurf, ollama, local model, ask, chatbot, token, tunnel, automation, ai se control, poochna, sawal, madad -->

## What is the Ask page?
**Ask** is a page in both apps where you type a question in plain words, in English or Roman Urdu, and get the matching answer from this help, plus a "Right now" box with live facts about your session. It works offline and nothing is sent to any cloud service.
1. Open **Ask** and type a question, for example "Why is the sound late?".
2. Read the answer and its steps; the "Right now" box shows facts such as the current P and which ear needs the most time.
3. Tap a **Related** link to read the next closest topic.
If nothing matches well, it says "I don't know that one yet" and shows the 3 closest topics. Questions and answers are not stored after the session.

## Can the Ask page use a local AI model?
Yes, on Windows, if you run Ollama on the same laptop. Turn on "Use a local AI model" and pick an installed model; the model gets only the matching help sections and live facts and is told to answer only from them. It is off by default.
1. Install Ollama and a model on the laptop; it must answer on http://127.0.0.1:11434.
2. In Dual Earbuds, turn on **Use a local AI model** and choose the model.
3. Ask as usual; the sources are shown under the answer.
**Windows:** if the model doesn't answer within 30 seconds, the normal answer is shown instead. Nothing is sent to any address outside the laptop.

## What can an AI assistant do with Dual Earbuds?
With the MCP server turned on, an AI assistant on the same laptop (Claude, ChatGPT, Codex, Cursor and others) can check and control Dual Earbuds, for example "why are the phones behind?", "lower the Redmi to 60 %" or "start sharing and give me the link".
1. Read: status, outputs, listeners, the Setup check, diagnostics (already redacted) and these help files.
2. Change: an output's switch, volume, delay or EQ; a listener's volume; sync priority; Party mode; start or stop sharing.
3. Remove a listener or stop sharing; assistants mark these as destructive so they ask you first.
4. Get the join link, which is meant to be shared.

## Is it safe to let an AI assistant control Dual Earbuds?
It is off by default and works only on this laptop. Turning it on creates a random secret token; requests without it are refused, and tools never return the room PIN.
1. Turn it on only in Settings > **AI assistants** > **Allow AI assistants to control Dual Earbuds**.
2. The server listens on loopback (127.0.0.1) only, never on Wi-Fi.
3. Every action shows a short notice for 3 seconds, such as "Claude set Redmi volume to 60 %", and is kept in **Recent assistant actions** (last 50) in Settings.
4. Rotate the token with its button if you think it leaked; the token is stored in %LOCALAPPDATA%\DualEarbuds\mcp-token.

## How do I connect Claude Desktop to Dual Earbuds?
Turn on the AI assistants setting, copy the Claude Desktop config from Settings > AI assistants, and paste it into Claude Desktop's config file, then restart Claude Desktop.
1. Settings > **AI assistants** > turn on **Allow AI assistants to control Dual Earbuds**.
2. Press the copy button for **Claude Desktop**.
3. In Claude Desktop, open claude_desktop_config.json and paste the entry under "mcpServers" as "dual-earbuds": it runs DualEarbuds.exe with the argument --mcp.
4. Restart Claude Desktop and ask it, for example, "What is Dual Earbuds doing right now?".
**Windows:** if Dual Earbuds isn't running, the --mcp command starts it in the tray and waits up to 5 seconds.

## How do I connect Claude Code to Dual Earbuds?
Turn on the setting, then add the server with one command that the copy button in Settings > AI assistants puts on the clipboard.
1. Settings > **AI assistants** > turn on the setting and press the copy button for **Claude Code**.
2. Run the copied command in a terminal; it looks like: claude mcp add dual-earbuds -- "C:\Users\you\AppData\Local\Programs\Dual Earbuds\DualEarbuds.exe" --mcp
3. Start Claude Code and ask about Dual Earbuds.

## How do I connect Codex CLI to Dual Earbuds?
Copy the Codex block from Settings > AI assistants and paste it into Codex's config file at ~/.codex/config.toml under [mcp_servers.dual-earbuds].
1. Settings > **AI assistants** > copy the **Codex CLI** block.
2. Open ~/.codex/config.toml (create it if missing) and paste the block; it sets the command to DualEarbuds.exe with the argument --mcp.
3. Start Codex again.

## How do I connect Cursor or VS Code to Dual Earbuds?
Copy the Cursor/VS Code block from Settings > AI assistants into the editor's mcp.json, which runs DualEarbuds.exe with --mcp over stdio. Windsurf and other clients that take a command work the same way.
1. Settings > **AI assistants** > copy the **Cursor/VS Code** block.
2. Paste it into the editor's mcp.json and save.
3. Reload the editor and enable the dual-earbuds server if it asks.
Clients that take a local URL can use the **HTTP: URL + token** block instead: POST to http://127.0.0.1:8790/mcp with the header "Authorization: Bearer" followed by the token.

## Can I use Dual Earbuds from ChatGPT?
Only with extra work you set up yourself. ChatGPT's custom connectors need a public HTTPS address, and Dual Earbuds only listens on this laptop; it does not open a tunnel for you.
1. If you understand the risk, run your own HTTPS tunnel to http://127.0.0.1:8790/mcp and keep the token secret.
2. Anyone with the tunnel address and token could control Dual Earbuds, so close the tunnel when you are done.
Assistants that run on the laptop (Claude Desktop, Claude Code, Codex CLI, Cursor) need no tunnel.

## Does the AI assistant feature work on Android?
No. The MCP server is part of the Windows app only. The Ask page is in both apps.
