// Dual Earbuds - MCP server (docs/assistant-and-mcp.md section 3): lets an AI assistant on this laptop check
// and control the app. JSON-RPC 2.0, MCP revisions 2025-06-18 and 2025-03-26: initialize, ping, tools/list,
// tools/call, resources/list, resources/read. Two transports: POST /mcp on the link port (loopback + bearer token
// only) and "DualEarbuds.exe --mcp" (newline-delimited stdio, forwarded to that endpoint). Off by default.
// Tools never return the room PIN.
using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;

namespace DualEarbuds
{
    // An ordered JSON object for output.
    public class Jo : IEnumerable
    {
        public readonly List<KeyValuePair<string, object>> Items = new List<KeyValuePair<string, object>>();
        public void Add(string key, object value) { Items.Add(new KeyValuePair<string, object>(key, value)); }
        public IEnumerator GetEnumerator() { return Items.GetEnumerator(); }
    }

    public static class JsonOut
    {
        public static string Write(object v)
        {
            var sb = new StringBuilder();
            W(sb, v, 0);
            return sb.ToString();
        }

        static void W(StringBuilder sb, object v, int depth)
        {
            if (depth > 40) throw new InvalidOperationException("JSON too deep");
            if (v == null) { sb.Append("null"); return; }
            if (v is string) { sb.Append('"').Append(MiniJson.Escape((string)v)).Append('"'); return; }
            if (v is bool) { sb.Append((bool)v ? "true" : "false"); return; }
            if (v is int || v is long || v is short || v is uint) { sb.Append(Convert.ToInt64(v).ToString(CultureInfo.InvariantCulture)); return; }
            if (v is double || v is float)
            {
                double d = Convert.ToDouble(v);
                if (double.IsNaN(d) || double.IsInfinity(d)) { sb.Append("null"); return; }
                sb.Append(d.ToString("R", CultureInfo.InvariantCulture));
                return;
            }
            var jo = v as Jo;
            if (jo != null)
            {
                sb.Append('{');
                for (int i = 0; i < jo.Items.Count; i++)
                {
                    if (i > 0) sb.Append(',');
                    sb.Append('"').Append(MiniJson.Escape(jo.Items[i].Key)).Append("\":");
                    W(sb, jo.Items[i].Value, depth + 1);
                }
                sb.Append('}');
                return;
            }
            var dict = v as IDictionary<string, object>;
            if (dict != null)
            {
                sb.Append('{');
                bool first = true;
                foreach (var kv in dict)
                {
                    if (!first) sb.Append(',');
                    first = false;
                    sb.Append('"').Append(MiniJson.Escape(kv.Key)).Append("\":");
                    W(sb, kv.Value, depth + 1);
                }
                sb.Append('}');
                return;
            }
            var list = v as IEnumerable;
            if (list != null)
            {
                sb.Append('[');
                bool first = true;
                foreach (var x in list)
                {
                    if (!first) sb.Append(',');
                    first = false;
                    W(sb, x, depth + 1);
                }
                sb.Append(']');
                return;
            }
            sb.Append('"').Append(MiniJson.Escape(v.ToString())).Append('"');
        }
    }

    // What the MCP tools can do to the app. Implemented over the Controller on the UI thread (and by a fake in tests).
    // Setters return null when done, else a one-sentence problem.
    public interface IDeviceControl
    {
        SessionSnapshot Snapshot();
        string SetOutput(string id, bool? on, int? volume, int? delayMs, string eq);
        string SetListenerVolume(string id, int volume);
        string KickListener(string id);
        string StartSharing();
        string StopSharing();
        string SetSyncPriority(string priority);
        string SetPartyMode(bool on, bool? requirePin);
        string JoinLink();                 // null when there is no link (link off, no network)
        List<CheckItem> SetupChecks();
        string Diagnostics();              // already redacted
        string[] Secrets();                // values that must never appear in any output (the room PIN)
        void Report(string client, string tool, string summary);   // toast + "Recent assistant actions"
    }

    public class McpTool
    {
        public string Name, Title, Description;
        public Jo Schema;
        public bool ReadOnly, Destructive, Idempotent;
    }

    public class McpServer
    {
        public const string Latest = "2025-06-18", Older = "2025-03-26";
        public static readonly string[] Supported = { Latest, Older };
        public const string StatusUri = "dualearbuds://status", HelpUriPrefix = "dualearbuds://help/";
        public const int ParseError = -32700, InvalidRequest = -32600, MethodNotFound = -32601, InvalidParams = -32602, InternalError = -32603, ResourceNotFound = -32002;

        readonly IDeviceControl dev;
        readonly Assistant help;
        public string ClientName = "AI assistant";
        public string Negotiated;

        public McpServer(IDeviceControl device, Assistant helpContent)
        {
            dev = device;
            help = helpContent ?? new Assistant(null);
        }

        public static string Negotiate(string requested)
        {
            return Array.IndexOf(Supported, requested) >= 0 ? requested : Latest;
        }

        // One message (or a batch) in, the response out; null when nothing is to be sent (notifications).
        public string Handle(string json)
        {
            object msg;
            try { msg = MiniJson.Parse(json); }
            catch { return JsonOut.Write(Error(null, ParseError, "Parse error")); }
            var batch = msg as List<object>;
            if (batch != null)
            {
                if (batch.Count == 0) return JsonOut.Write(Error(null, InvalidRequest, "Empty batch"));
                var outs = new List<object>();
                foreach (var m in batch) { var r = One(m); if (r != null) outs.Add(r); }
                return outs.Count == 0 ? null : Scrub(JsonOut.Write(outs));
            }
            var res = One(msg);
            return res == null ? null : Scrub(JsonOut.Write(res));
        }

        // The PIN is never part of any answer: tools don't include it, and this catches it anywhere as a whole number.
        string Scrub(string text)
        {
            string[] secrets = null;
            try { secrets = dev.Secrets(); } catch { }
            if (secrets == null) return text;
            foreach (var s in secrets)
            {
                if (string.IsNullOrEmpty(s) || s.Length < 4) continue;
                text = Regex.Replace(text, "(?<![0-9A-Za-z])" + Regex.Escape(s) + "(?![0-9A-Za-z])", "[hidden]");
            }
            return text;
        }

        object One(object m)
        {
            var d = m as Dictionary<string, object>;
            if (d == null) return Error(null, InvalidRequest, "Invalid request");
            object id;
            bool hasId = d.TryGetValue("id", out id);
            if (hasId && id != null && !(id is string) && !(id is double)) return Error(null, InvalidRequest, "Invalid id");
            if (MiniJson.GetString(d, "jsonrpc", null) != "2.0") return hasId ? Error(id, InvalidRequest, "jsonrpc must be \"2.0\"") : null;
            string method = MiniJson.GetString(d, "method", null);
            if (method == null)
            {
                // a response to something we never send (we make no requests): ignore
                return hasId && !d.ContainsKey("result") && !d.ContainsKey("error") ? Error(id, InvalidRequest, "Missing method") : null;
            }
            object po;
            d.TryGetValue("params", out po);
            var p = po as Dictionary<string, object> ?? new Dictionary<string, object>();
            if (!hasId) return null;   // notifications (notifications/initialized, cancelled, ...) get no reply
            try
            {
                switch (method)
                {
                    case "initialize": return Result(id, Initialize(p));
                    case "ping": return Result(id, new Jo());
                    case "tools/list": return Result(id, new Jo { { "tools", ToolList() } });
                    case "tools/call":
                    {
                        string name = MiniJson.GetString(p, "name", null);
                        object ao;
                        p.TryGetValue("arguments", out ao);
                        var args = ao as Dictionary<string, object> ?? new Dictionary<string, object>();
                        if (name == null || FindTool(name) == null) return Error(id, InvalidParams, "Unknown tool: " + (name ?? "(none)"));
                        return Result(id, Call(name, args));
                    }
                    case "resources/list": return Result(id, new Jo { { "resources", ResourceList() } });
                    case "resources/read":
                    {
                        string uri = MiniJson.GetString(p, "uri", null);
                        var r = Read(uri);
                        if (r == null) return Error(id, ResourceNotFound, "Resource not found: " + (uri ?? "(none)"));
                        return Result(id, r);
                    }
                    default: return Error(id, MethodNotFound, "Method not found: " + method);
                }
            }
            catch (Exception ex)
            {
                Log.Warn("MCP " + method + ": " + ex.Message);
                return Error(id, InternalError, "Internal error");
            }
        }

        static Jo Result(object id, object result) { return new Jo { { "jsonrpc", "2.0" }, { "id", id }, { "result", result } }; }

        static Jo Error(object id, int code, string message)
        {
            return new Jo { { "jsonrpc", "2.0" }, { "id", id }, { "error", new Jo { { "code", code }, { "message", message } } } };
        }

        Jo Initialize(Dictionary<string, object> p)
        {
            Negotiated = Negotiate(MiniJson.GetString(p, "protocolVersion", null));
            object ci;
            var info = p.TryGetValue("clientInfo", out ci) ? ci as Dictionary<string, object> : null;
            if (info != null)
            {
                string n = MiniJson.GetString(info, "title", null) ?? MiniJson.GetString(info, "name", null);
                if (!string.IsNullOrEmpty(n)) ClientName = CleanName(n);
            }
            return new Jo
            {
                { "protocolVersion", Negotiated },
                { "capabilities", new Jo { { "tools", new Jo { { "listChanged", false } } }, { "resources", new Jo { { "subscribe", false }, { "listChanged", false } } } } },
                { "serverInfo", new Jo { { "name", "dual-earbuds" }, { "title", "Dual Earbuds" }, { "version", AppInfo.Version } } },
                { "instructions", "Dual Earbuds shares this laptop's sound to several earbuds and to phones on the same Wi-Fi, in sync. " +
                                  "Use get_status first. P is the shared deadline in ms. ask_help answers how-to questions from the built-in help. " +
                                  "The room PIN is never available through these tools." },
            };
        }

        // "claude-ai" -> "claude-ai"; at most 40 printable characters (it is shown in toasts).
        public static string CleanName(string n)
        {
            var sb = new StringBuilder();
            foreach (char c in n) if (!char.IsControl(c)) sb.Append(c);
            string r = sb.ToString().Trim();
            if (r.Length > 40) r = r.Substring(0, 40);
            return r.Length == 0 ? "AI assistant" : r;
        }

        // ------------------------------------------------------------------ tools
        static Jo Obj(Jo props, params string[] required)
        {
            var o = new Jo { { "type", "object" }, { "properties", props ?? new Jo() } };
            if (required.Length > 0) o.Add("required", required);
            o.Add("additionalProperties", false);
            return o;
        }

        static Jo Int(string desc, int min, int max) { return new Jo { { "type", "integer" }, { "minimum", min }, { "maximum", max }, { "description", desc } }; }
        static Jo Str(string desc) { return new Jo { { "type", "string" }, { "description", desc } }; }
        static Jo Bool(string desc) { return new Jo { { "type", "boolean" }, { "description", desc } }; }
        static Jo Enum(string desc, params string[] values) { return new Jo { { "type", "string" }, { "enum", values }, { "description", desc } }; }

        static List<McpTool> tools;

        public static List<McpTool> Tools
        {
            get
            {
                if (tools != null) return tools;
                var eq = new List<string>(EqPresets.Names);
                eq.Add("auto");
                var t = new List<McpTool>
                {
                    new McpTool { Name = "get_status", Title = "Get status", ReadOnly = true, Idempotent = true, Schema = Obj(null),
                        Description = "Sharing on/off, sound source, sync priority, P (shared deadline, ms), local outputs, listeners, network and warnings." },
                    new McpTool { Name = "list_outputs", Title = "List outputs", ReadOnly = true, Idempotent = true, Schema = Obj(null),
                        Description = "This laptop's audio outputs: id, name, on, volume, delay, latency, calibrated." },
                    new McpTool { Name = "set_output", Title = "Change an output", Idempotent = true,
                        Schema = Obj(new Jo { { "id", Str("Output id from list_outputs") }, { "on", Bool("Play to this output") },
                                              { "volume", Int("Volume in percent", 0, 150) }, { "delayMs", Int("Extra manual delay in ms", 0, 500) },
                                              { "eq", Enum("Sound preset", eq.ToArray()) } }, "id"),
                        Description = "Turn one local output on or off, or change its volume, manual delay or EQ preset." },
                    new McpTool { Name = "list_listeners", Title = "List listeners", ReadOnly = true, Idempotent = true, Schema = Obj(null),
                        Description = "Phones and laptops listening: id, name, device, earbuds, battery, volume, needMs, syncErrMs, UDP or WebSocket." },
                    new McpTool { Name = "set_listener_volume", Title = "Set a listener's volume", Idempotent = true,
                        Schema = Obj(new Jo { { "id", Str("Listener id from list_listeners") }, { "volume", Int("Volume in percent", 0, 150) } }, "id", "volume"),
                        Description = "Set how loud the laptop plays to one listener." },
                    new McpTool { Name = "kick_listener", Title = "Remove a listener", Destructive = true,
                        Schema = Obj(new Jo { { "id", Str("Listener id from list_listeners") } }, "id"),
                        Description = "Disconnect one listener. It can join again with the link unless the link key is changed." },
                    new McpTool { Name = "start_sharing", Title = "Start sharing", Idempotent = true, Schema = Obj(null),
                        Description = "Start sharing this laptop's sound to the ticked outputs and to listeners." },
                    new McpTool { Name = "stop_sharing", Title = "Stop sharing", Destructive = true, Idempotent = true, Schema = Obj(null),
                        Description = "Stop sharing: every output and listener goes silent." },
                    new McpTool { Name = "set_sync_priority", Title = "Set sync priority", Idempotent = true,
                        Schema = Obj(new Jo { { "priority", Enum("lipsync: the laptop's sound matches the picture; together: every ear waits for the slowest", "lipsync", "together") } }, "priority"),
                        Description = "Choose Lip-sync (video on this laptop) or Everyone together (music, all ears at the same moment)." },
                    new McpTool { Name = "set_party_mode", Title = "Set Party mode", Idempotent = true,
                        Schema = Obj(new Jo { { "on", Bool("Party mode: up to 16 listeners") }, { "requirePin", Bool("Require the room PIN to join (the PIN is only shown in the app)") } }, "on"),
                        Description = "Turn Party mode on or off and optionally require a room PIN. The PIN itself is never returned." },
                    new McpTool { Name = "get_join_link", Title = "Get the join link", ReadOnly = true, Idempotent = true, Schema = Obj(null),
                        Description = "The link phones open to listen. It is meant to be shared with people nearby." },
                    new McpTool { Name = "run_setup_check", Title = "Run Setup check", ReadOnly = true, Idempotent = true, Schema = Obj(null),
                        Description = "Each check (network, firewall, Wi-Fi band, power, calibration...) with its status, why and the fix." },
                    new McpTool { Name = "get_diagnostics", Title = "Get diagnostics", ReadOnly = true, Idempotent = true, Schema = Obj(null),
                        Description = "The Copy diagnostics text: versions, network, outputs, listeners, recent log. Keys and PIN are removed." },
                    new McpTool { Name = "ask_help", Title = "Ask the help", ReadOnly = true, Idempotent = true,
                        Schema = Obj(new Jo { { "question", Str("A how-to or troubleshooting question, English or Roman Urdu") } }, "question"),
                        Description = "Answer from Dual Earbuds' built-in help plus live facts about this session, with sources." },
                };
                tools = t;
                return t;
            }
        }

        static McpTool FindTool(string name)
        {
            foreach (var t in Tools) if (t.Name == name) return t;
            return null;
        }

        static List<object> ToolList()
        {
            var l = new List<object>();
            foreach (var t in Tools)
                l.Add(new Jo
                {
                    { "name", t.Name }, { "title", t.Title }, { "description", t.Description }, { "inputSchema", t.Schema },
                    { "annotations", new Jo { { "title", t.Title }, { "readOnlyHint", t.ReadOnly }, { "destructiveHint", t.Destructive },
                                              { "idempotentHint", t.Idempotent }, { "openWorldHint", false } } },
                });
            return l;
        }

        static Jo ToolResult(string text, object structured, bool isError)
        {
            var r = new Jo { { "content", new List<object> { new Jo { { "type", "text" }, { "text", text } } } } };
            if (structured != null) r.Add("structuredContent", structured);
            r.Add("isError", isError);
            return r;
        }

        static Jo Fail(string text) { return ToolResult(text, null, true); }

        // Integer argument: missing -> null; present but not a whole number in range -> problem.
        static int? IntArg(Dictionary<string, object> a, string key, int min, int max, out string problem)
        {
            problem = null;
            object v;
            if (!a.TryGetValue(key, out v) || v == null) return null;
            if (!(v is double) || Math.Floor((double)v) != (double)v || (double)v < min || (double)v > max)
            {
                problem = key + " must be a whole number from " + min + " to " + max + ".";
                return null;
            }
            return (int)(double)v;
        }

        static bool? BoolArg(Dictionary<string, object> a, string key, out string problem)
        {
            problem = null;
            object v;
            if (!a.TryGetValue(key, out v) || v == null) return null;
            if (!(v is bool)) { problem = key + " must be true or false."; return null; }
            return (bool)v;
        }

        static string StrArg(Dictionary<string, object> a, string key)
        {
            object v;
            return a.TryGetValue(key, out v) ? v as string : null;
        }

        static string Unknown(Dictionary<string, object> a, McpTool t)
        {
            object props = null;
            foreach (var kv in t.Schema.Items) if (kv.Key == "properties") props = kv.Value;
            var known = new HashSet<string>();
            var pj = props as Jo;
            if (pj != null) foreach (var kv in pj.Items) known.Add(kv.Key);
            foreach (var k in a.Keys) if (!known.Contains(k)) return "Unknown argument: " + k + ".";
            return null;
        }

        Jo Call(string name, Dictionary<string, object> a)
        {
            var tool = FindTool(name);
            string bad = Unknown(a, tool);
            if (bad != null) return Fail(bad);
            Jo r = Run(name, a);
            string summary = null;
            foreach (var kv in r.Items) if (kv.Key == "content") { var l = kv.Value as List<object>; if (l != null && l.Count > 0) foreach (var c in ((Jo)l[0]).Items) if (c.Key == "text") summary = c.Value as string; }
            try { dev.Report(ClientName, name, FirstLine(summary)); } catch { }
            return r;
        }

        static string FirstLine(string s)
        {
            if (s == null) return "";
            int i = s.IndexOf('\n');
            string f = i >= 0 ? s.Substring(0, i) : s;
            return f.Length > 120 ? f.Substring(0, 117) + "..." : f;
        }

        Jo Run(string name, Dictionary<string, object> a)
        {
            string prob, err;
            switch (name)
            {
                case "get_status":
                {
                    var s = dev.Snapshot();
                    return ToolResult(StatusText(s), StatusJson(s), false);
                }
                case "list_outputs":
                {
                    var s = dev.Snapshot();
                    var sb = new StringBuilder();
                    foreach (var o in s.Outputs) sb.AppendLine(OutputLine(o));
                    if (s.Outputs.Count == 0) sb.Append("No audio outputs found.");
                    return ToolResult(sb.ToString().TrimEnd(), new Jo { { "outputs", OutputsJson(s) } }, false);
                }
                case "set_output":
                {
                    string id = StrArg(a, "id");
                    if (string.IsNullOrEmpty(id)) return Fail("id is required (see list_outputs).");
                    bool? on = BoolArg(a, "on", out prob); if (prob != null) return Fail(prob);
                    int? vol = IntArg(a, "volume", 0, 150, out prob); if (prob != null) return Fail(prob);
                    int? delay = IntArg(a, "delayMs", 0, 500, out prob); if (prob != null) return Fail(prob);
                    string eq = StrArg(a, "eq");
                    if (a.ContainsKey("eq") && (eq == null || (eq != "auto" && !EqPresets.IsValid(eq)))) return Fail("eq must be one of flat, bass, voice, movie, auto.");
                    if (on == null && vol == null && delay == null && eq == null) return Fail("Nothing to change: give on, volume, delayMs or eq.");
                    var o = FindOutput(dev.Snapshot(), id);
                    if (o == null) return Fail("No output with id " + id + " (see list_outputs).");
                    err = dev.SetOutput(id, on, vol, delay, eq);
                    if (err != null) return Fail(err);
                    var parts = new List<string>();
                    if (on != null) parts.Add(on.Value ? "on" : "off");
                    if (vol != null) parts.Add("volume " + vol.Value + " %");
                    if (delay != null) parts.Add("delay " + delay.Value + " ms");
                    if (eq != null) parts.Add("EQ " + eq);
                    var after = FindOutput(dev.Snapshot(), id) ?? o;
                    return ToolResult("Set " + o.Name + ": " + string.Join(", ", parts.ToArray()) + ".", OutputJson(after), false);
                }
                case "list_listeners":
                {
                    var s = dev.Snapshot();
                    var sb = new StringBuilder();
                    foreach (var l in s.Listeners) sb.AppendLine(ListenerLine(l));
                    if (s.Listeners.Count == 0) sb.Append("No listeners right now.");
                    return ToolResult(sb.ToString().TrimEnd(), new Jo { { "listeners", ListenersJson(s) } }, false);
                }
                case "set_listener_volume":
                {
                    string id = StrArg(a, "id");
                    if (string.IsNullOrEmpty(id)) return Fail("id is required (see list_listeners).");
                    int? vol = IntArg(a, "volume", 0, 150, out prob); if (prob != null) return Fail(prob);
                    if (vol == null) return Fail("volume is required (0 to 150).");
                    var l = FindListener(dev.Snapshot(), id);
                    if (l == null) return Fail("No listener with id " + id + " (see list_listeners).");
                    err = dev.SetListenerVolume(id, vol.Value);
                    if (err != null) return Fail(err);
                    return ToolResult("Set " + l.Name + " volume to " + vol.Value + " %.", new Jo { { "id", id }, { "name", l.Name }, { "volume", vol.Value } }, false);
                }
                case "kick_listener":
                {
                    string id = StrArg(a, "id");
                    if (string.IsNullOrEmpty(id)) return Fail("id is required (see list_listeners).");
                    var l = FindListener(dev.Snapshot(), id);
                    if (l == null) return Fail("No listener with id " + id + " (see list_listeners).");
                    err = dev.KickListener(id);
                    if (err != null) return Fail(err);
                    return ToolResult("Removed " + l.Name + ".", new Jo { { "id", id }, { "name", l.Name }, { "removed", true } }, false);
                }
                case "start_sharing":
                case "stop_sharing":
                {
                    bool start = name == "start_sharing";
                    err = start ? dev.StartSharing() : dev.StopSharing();
                    if (err != null) return Fail(err);
                    var s = dev.Snapshot();
                    return ToolResult(start ? "Sharing started" + (s.Source.Length > 0 ? " from " + s.Source : "") + "." : "Sharing stopped.", StatusJson(s), false);
                }
                case "set_sync_priority":
                {
                    string p = StrArg(a, "priority");
                    if (p != SyncPriority.LipSync && p != SyncPriority.Together) return Fail("priority must be lipsync or together.");
                    err = dev.SetSyncPriority(p);
                    if (err != null) return Fail(err);
                    var s = dev.Snapshot();
                    return ToolResult("Sync priority: " + (p == SyncPriority.LipSync ? "Lip-sync" : "Everyone together") + ". P is " + s.PlayoutMs + " ms.",
                                      new Jo { { "priority", s.Priority }, { "playoutMs", s.PlayoutMs } }, false);
                }
                case "set_party_mode":
                {
                    bool? on = BoolArg(a, "on", out prob); if (prob != null) return Fail(prob);
                    if (on == null) return Fail("on is required.");
                    bool? pin = BoolArg(a, "requirePin", out prob); if (prob != null) return Fail(prob);
                    err = dev.SetPartyMode(on.Value, pin);
                    if (err != null) return Fail(err);
                    var s = dev.Snapshot();
                    string text = "Party mode " + (s.Party ? "on (up to " + s.Capacity + " listeners)" : "off") + "." +
                                  (s.PinOn ? " A room PIN is required; it is shown on the Listeners page of the app." : "");
                    return ToolResult(text, new Jo { { "party", s.Party }, { "pinRequired", s.PinOn }, { "capacity", s.Capacity } }, false);
                }
                case "get_join_link":
                {
                    string link = dev.JoinLink();
                    if (link == null) return Fail("There is no join link right now: the phone link is off or the laptop has no network.");
                    var s = dev.Snapshot();
                    return ToolResult(link + "\nThis link is meant to be shared: anyone on the same Wi-Fi who opens it can listen." +
                                      (s.PinOn ? " A room PIN is also required; it is shown in the app, not here." : ""),
                                      new Jo { { "link", link }, { "shareable", true }, { "pinRequired", s.PinOn } }, false);
                }
                case "run_setup_check":
                {
                    var items = dev.SetupChecks() ?? new List<CheckItem>();
                    var sb = new StringBuilder();
                    var list = new List<object>();
                    foreach (var c in items)
                    {
                        sb.Append(c.Status.ToString().ToLowerInvariant()).Append("  ").Append(c.Title);
                        if (!string.IsNullOrEmpty(c.Value)) sb.Append(" (").Append(c.Value).Append(')');
                        if (!string.IsNullOrEmpty(c.Detail)) sb.Append(": ").Append(c.Detail);
                        if (!string.IsNullOrEmpty(c.Action)) sb.Append(" Fix: ").Append(c.Action).Append('.');
                        sb.AppendLine();
                        list.Add(new Jo { { "id", c.Id }, { "title", c.Title }, { "status", c.Status.ToString().ToLowerInvariant() }, { "value", c.Value },
                                          { "why", c.Detail }, { "fix", c.Action } });
                    }
                    return ToolResult(sb.Length == 0 ? "No checks." : sb.ToString().TrimEnd(), new Jo { { "checks", list }, { "attention", SetupCheck.Attention(items) } }, false);
                }
                case "get_diagnostics":
                {
                    string t = dev.Diagnostics() ?? "";
                    return ToolResult(t, null, false);
                }
                case "ask_help":
                {
                    string q = StrArg(a, "question");
                    if (string.IsNullOrEmpty(q) || q.Trim().Length == 0) return Fail("question is required.");
                    var ans = help.Ask(LocalModelPrompt.Clean(q), dev.Snapshot());
                    var src = new List<object>();
                    foreach (var s in ans.Sources) src.Add(new Jo { { "file", s.File }, { "title", s.Title }, { "uri", HelpUriPrefix + s.File } });
                    var facts = new List<object>();
                    foreach (var f in ans.Facts) facts.Add(f.Text);
                    return ToolResult(ans.ToText(), new Jo { { "found", ans.Found }, { "sources", src }, { "rightNow", facts } }, false);
                }
            }
            return Fail("Unknown tool.");
        }

        // ------------------------------------------------------------------ formatting
        static OutputSnap FindOutput(SessionSnapshot s, string id) { foreach (var o in s.Outputs) if (o.Id == id) return o; return null; }
        static ListenerSnap FindListener(SessionSnapshot s, string id) { foreach (var l in s.Listeners) if (l.Id == id) return l; return null; }

        static object Num(double v) { return double.IsNaN(v) ? null : (object)Math.Round(v, 1); }

        static string OutputLine(OutputSnap o)
        {
            return o.Id + "  " + o.Name + (o.Source ? " (sound source)" : "") + ": " + (o.On ? "on" : "off") + ", volume " + o.Volume + " %, delay " + o.DelayMs +
                   " ms, latency " + Math.Round(o.LatencyMs) + " ms" + (o.Calibrated ? " (calibrated)" : " (estimated)") +
                   (double.IsNaN(o.SyncErrMs) ? "" : ", sync " + Math.Round(o.SyncErrMs) + " ms");
        }

        static string ListenerLine(ListenerSnap l)
        {
            return l.Id + "  " + l.Name + (l.Earbuds.Length > 0 ? " (" + l.Earbuds + ")" : "") + ": volume " + l.Volume + " %" +
                   (l.Battery >= 0 ? ", battery " + l.Battery + " %" : "") + (l.NeedMs > 0 ? ", needs " + l.NeedMs + " ms" : "") +
                   (double.IsNaN(l.SyncErrMs) ? "" : ", sync " + Math.Round(l.SyncErrMs) + " ms") + ", " + (l.Transport == "udp" ? "UDP" : "WebSocket");
        }

        static Jo OutputJson(OutputSnap o)
        {
            return new Jo { { "id", o.Id }, { "name", o.Name }, { "on", o.On }, { "source", o.Source }, { "volume", o.Volume }, { "delayMs", o.DelayMs },
                            { "autoDelayMs", o.ExtraDelayMs }, { "latencyMs", Num(o.LatencyMs) }, { "calibrated", o.Calibrated }, { "bluetooth", o.Bluetooth },
                            { "syncErrMs", Num(o.SyncErrMs) }, { "eq", o.Eq }, { "state", o.State }, { "battery", o.Battery >= 0 ? (object)o.Battery : null } };
        }

        static List<object> OutputsJson(SessionSnapshot s)
        {
            var l = new List<object>();
            foreach (var o in s.Outputs) l.Add(OutputJson(o));
            return l;
        }

        static List<object> ListenersJson(SessionSnapshot s)
        {
            var list = new List<object>();
            foreach (var l in s.Listeners)
                list.Add(new Jo { { "id", l.Id }, { "name", l.Name }, { "device", l.Client }, { "earbuds", l.Earbuds }, { "battery", l.Battery >= 0 ? (object)l.Battery : null },
                                  { "volume", l.Volume }, { "needMs", l.NeedMs > 0 ? (object)l.NeedMs : null }, { "syncErrMs", Num(l.SyncErrMs) },
                                  { "transport", l.Transport == "udp" ? "udp" : "ws" }, { "synced", l.Synced } });
            return list;
        }

        public static Jo StatusJson(SessionSnapshot s)
        {
            var net = new Jo { { "category", s.NetworkCategory } };
            if (s.Wifi != null && s.Wifi.Connected) { net.Add("wifiBand", s.Wifi.Band); net.Add("wifiSignal", s.Wifi.SignalPercent >= 0 ? (object)s.Wifi.SignalPercent : null); }
            if (s.Firewall != null && s.Firewall.Known) { net.Add("firewallTcp", s.Firewall.Tcp); net.Add("firewallUdp", s.Firewall.Udp); }
            return new Jo
            {
                { "sharing", s.Sharing }, { "waiting", s.Waiting }, { "listening", s.Listening }, { "source", s.Source }, { "syncPriority", s.Priority },
                { "playoutMs", s.PlayoutMs }, { "phoneLink", s.PhoneLinkOn }, { "party", s.Party }, { "pinRequired", s.PinOn }, { "capacity", s.Capacity },
                { "outputs", OutputsJson(s) }, { "listeners", ListenersJson(s) }, { "network", net },
                { "batterySaver", s.Power != null && s.Power.BatterySaver }, { "status", s.Status }, { "lastError", s.LastError }, { "warnings", s.Warnings },
            };
        }

        public static string StatusText(SessionSnapshot s)
        {
            var sb = new StringBuilder();
            sb.AppendLine(s.Listening ? "Listening to " + s.ListenHost + "." : s.Sharing ? "Sharing" + (s.Source.Length > 0 ? " from " + s.Source : "") + "." : s.Waiting ? "Waiting for the sound source." : "Not sharing.");
            if (!s.Listening)
                sb.AppendLine("Sync priority " + (SyncPriority.IsLipSync(s.Priority) ? "Lip-sync" : "Everyone together") + ", P " + s.PlayoutMs + " ms" +
                              (s.PlayoutMs > 0 ? " (" + LiveFacts.PlayoutReason(s) + ")" : "") + ".");
            int on = 0;
            foreach (var o in s.Outputs) if (o.On) on++;
            sb.AppendLine("Outputs: " + on + " of " + s.Outputs.Count + " on. Listeners: " + s.Listeners.Count + " of " + s.Capacity + (s.Party ? " (Party mode)" : "") + ".");
            foreach (var o in s.Outputs) if (o.On || o.Source) sb.AppendLine("  " + OutputLine(o));
            foreach (var l in s.Listeners) sb.AppendLine("  " + ListenerLine(l));
            if (!string.IsNullOrEmpty(s.NetworkCategory)) sb.AppendLine("Network: " + s.NetworkCategory + (s.Wifi != null && s.Wifi.Connected && s.Wifi.Band != null ? ", Wi-Fi " + s.Wifi.Band : "") + ".");
            foreach (var w in s.Warnings) sb.AppendLine("Warning: " + w);
            if (!string.IsNullOrEmpty(s.LastError)) sb.AppendLine("Last problem: " + s.LastError);
            return sb.ToString().TrimEnd();
        }

        // ------------------------------------------------------------------ resources
        List<object> ResourceList()
        {
            var l = new List<object>
            {
                new Jo { { "uri", StatusUri }, { "name", "status" }, { "title", "Dual Earbuds status" }, { "description", "Same as the get_status tool." }, { "mimeType", "application/json" } },
            };
            foreach (var f in help.Files)
                l.Add(new Jo { { "uri", HelpUriPrefix + f }, { "name", f }, { "title", help.TopicOf(f) ?? f }, { "description", "Dual Earbuds help: " + (help.TopicOf(f) ?? f) }, { "mimeType", "text/markdown" } });
            return l;
        }

        Jo Read(string uri)
        {
            if (uri == null) return null;
            string mime, text;
            if (uri == StatusUri) { mime = "application/json"; text = JsonOut.Write(StatusJson(dev.Snapshot())); }
            else if (uri.StartsWith(HelpUriPrefix, StringComparison.Ordinal))
            {
                text = help.FileText(uri.Substring(HelpUriPrefix.Length));
                if (text == null) return null;
                mime = "text/markdown";
            }
            else return null;
            return new Jo { { "contents", new List<object> { new Jo { { "uri", uri }, { "mimeType", mime }, { "text", text } } } } };
        }
    }

    // ------------------------------------------------------------------ the loopback HTTP endpoint
    public class McpReply
    {
        public int Status;
        public string Reason = "OK";
        public string ContentType = "application/json";
        public string Body = "";
        public bool Challenge;   // add WWW-Authenticate: Bearer
    }

    public class McpEndpoint
    {
        public const int MaxBodyBytes = 1024 * 1024;
        public volatile bool Enabled;
        public volatile string Token;
        public Func<string, string> Handle;   // a JSON-RPC message -> response (null: notification only)

        public static bool IsLoopback(IPAddress a)
        {
            if (a == null) return false;
            if (a.IsIPv4MappedToIPv6) a = a.MapToIPv4();
            return IPAddress.IsLoopback(a);
        }

        // Browser pages may only reach us from the app's own origin; other origins are refused (DNS rebinding).
        public static bool OriginAllowed(string origin)
        {
            if (string.IsNullOrEmpty(origin) || origin == "null") return true;
            Uri u;
            if (!Uri.TryCreate(origin, UriKind.Absolute, out u)) return false;
            IPAddress ip;
            string host = u.Host.Trim('[', ']');
            return IPAddress.TryParse(host, out ip) && IsLoopback(ip);
        }

        public static bool TokenMatches(string expected, string authorization)
        {
            if (string.IsNullOrEmpty(expected) || string.IsNullOrEmpty(authorization)) return false;
            const string prefix = "Bearer ";
            if (!authorization.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)) return false;
            string given = authorization.Substring(prefix.Length).Trim();
            int diff = expected.Length ^ given.Length;
            for (int i = 0; i < expected.Length; i++) diff |= expected[i] ^ (i < given.Length ? given[i] : 0);
            return diff == 0;
        }

        static McpReply Plain(int status, string reason, string text)
        {
            return new McpReply { Status = status, Reason = reason, ContentType = "text/plain; charset=utf-8", Body = text };
        }

        // Anything not from this laptop gets the same bare 403, whatever it asked: nothing about the feature leaks.
        public static McpReply Forbidden() { return Plain(403, "Forbidden", "Forbidden"); }

        public McpReply Process(IPAddress remote, string method, IDictionary<string, string> headers, string body)
        {
            if (!IsLoopback(remote)) return Forbidden();
            string origin = null, auth = null;
            if (headers != null) { headers.TryGetValue("Origin", out origin); headers.TryGetValue("Authorization", out auth); }
            if (!OriginAllowed(origin)) return Forbidden();
            if (!Enabled || Handle == null || string.IsNullOrEmpty(Token))
                return Plain(403, "Forbidden", "AI assistants are turned off in Dual Earbuds (Settings > AI assistants).");
            if (!TokenMatches(Token, auth)) { var r = Plain(401, "Unauthorized", "Unauthorized"); r.Challenge = true; return r; }
            if (method != "POST") return Plain(405, "Method Not Allowed", "POST JSON-RPC messages to /mcp.");
            string resp;
            try { resp = Handle(body ?? ""); }
            catch (Exception ex)
            {
                Log.Warn("MCP: " + ex.Message);
                return new McpReply { Status = 500, Reason = "Internal Server Error", Body = "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}" };
            }
            if (resp == null) return new McpReply { Status = 202, Reason = "Accepted", Body = "" };
            return new McpReply { Status = 200, Reason = "OK", Body = resp };
        }
    }

    // %LOCALAPPDATA%\DualEarbuds\mcp-token: 32 random bytes as hex, created when the setting is turned on.
    public static class McpToken
    {
        public static string PathOf(string dataDir) { return System.IO.Path.Combine(dataDir, "mcp-token"); }
        public static string DefaultPath { get { return PathOf(AppInfo.DataDir); } }

        public static string New()
        {
            var b = new byte[32];
            using (var rng = new RNGCryptoServiceProvider()) rng.GetBytes(b);
            var sb = new StringBuilder(64);
            foreach (byte x in b) sb.Append(x.ToString("x2", CultureInfo.InvariantCulture));
            return sb.ToString();
        }

        public static bool IsValid(string t)
        {
            if (t == null || t.Length != 64) return false;
            foreach (char c in t) if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
            return true;
        }

        public static string Read(string path)
        {
            try
            {
                if (!File.Exists(path)) return null;
                string t = File.ReadAllText(path).Trim();
                return IsValid(t) ? t : null;
            }
            catch { return null; }
        }

        public static string Ensure(string path)
        {
            string t = Read(path);
            return t ?? Rotate(path);
        }

        public static string Rotate(string path)
        {
            string t = New();
            Directory.CreateDirectory(System.IO.Path.GetDirectoryName(path));
            File.WriteAllText(path, t);
            return t;
        }
    }

    // Ready-to-paste client configs (Settings > AI assistants).
    public static class McpConfig
    {
        public const string ServerName = "dual-earbuds";

        public static string ClaudeDesktop(string exe)
        {
            return "{\n  \"mcpServers\": {\n    \"" + ServerName + "\": {\n      \"command\": \"" + MiniJson.Escape(exe) + "\",\n      \"args\": [\"--mcp\"]\n    }\n  }\n}";
        }

        public static string ClaudeCode(string exe) { return "claude mcp add " + ServerName + " -- \"" + exe + "\" --mcp"; }

        // TOML literal strings ('...') keep Windows backslashes as they are.
        public static string CodexCli(string exe) { return "[mcp_servers." + ServerName + "]\ncommand = '" + exe.Replace("'", "") + "'\nargs = [\"--mcp\"]"; }

        public static string Cursor(string exe) { return ClaudeDesktop(exe); }

        public static string VsCode(string exe)
        {
            return "{\n  \"servers\": {\n    \"" + ServerName + "\": {\n      \"type\": \"stdio\",\n      \"command\": \"" + MiniJson.Escape(exe) + "\",\n      \"args\": [\"--mcp\"]\n    }\n  }\n}";
        }

        public static string Http(int port, string token)
        {
            string url = "http://127.0.0.1:" + port.ToString(CultureInfo.InvariantCulture) + "/mcp";
            return "URL: " + url + "\nHeader: Authorization: Bearer " + token + "\n\n" +
                   "{\n  \"mcpServers\": {\n    \"" + ServerName + "\": {\n      \"type\": \"http\",\n      \"url\": \"" + url + "\",\n      \"headers\": { \"Authorization\": \"Bearer " + token + "\" }\n    }\n  }\n}";
        }
    }

    // "DualEarbuds.exe --mcp": newline-delimited JSON-RPC on stdin/stdout, each message forwarded to the running app.
    // Logs go to stderr only (stdout carries nothing but MCP messages).
    public static class McpStdio
    {
        public const int ErrorAppUnavailable = -32000;

        // forward: request body -> response body ("" or null for none); throws McpForwardException with a user message.
        public static void Run(TextReader input, TextWriter output, TextWriter log, Func<string, string> forward)
        {
            string line;
            while ((line = input.ReadLine()) != null)
            {
                string msg = line.Trim();
                if (msg.Length == 0) continue;
                string resp;
                try { resp = forward(msg); }
                catch (Exception ex)
                {
                    log.WriteLine("dual-earbuds mcp: " + ex.Message);
                    resp = ErrorFor(msg, ex.Message);
                }
                if (string.IsNullOrEmpty(resp)) continue;
                // one message per line: JSON never needs a raw newline
                output.Write(resp.Replace("\r", "").Replace("\n", ""));
                output.Write('\n');
                output.Flush();
            }
        }

        // An error response for each request in msg (none for notifications or unparsable input).
        public static string ErrorFor(string msg, string why)
        {
            object parsed;
            try { parsed = MiniJson.Parse(msg); }
            catch { return JsonOut.Write(new Jo { { "jsonrpc", "2.0" }, { "id", null }, { "error", new Jo { { "code", McpServer.ParseError }, { "message", "Parse error" } } } }); }
            var items = parsed as List<object> ?? new List<object> { parsed };
            var outs = new List<object>();
            foreach (var o in items)
            {
                var d = o as Dictionary<string, object>;
                object id;
                if (d == null || !d.TryGetValue("id", out id)) continue;
                outs.Add(new Jo { { "jsonrpc", "2.0" }, { "id", id }, { "error", new Jo { { "code", ErrorAppUnavailable }, { "message", why } } } });
            }
            if (outs.Count == 0) return null;
            return JsonOut.Write(parsed is List<object> ? (object)outs : outs[0]);
        }
    }

    public class McpForwardException : Exception { public McpForwardException(string m) : base(m) { } }

    // Posts one message to the app's loopback endpoint.
    public class McpHttpClient
    {
        public readonly int Port;
        public string Token;
        public int TimeoutMs = 15000;

        public McpHttpClient(int port, string token) { Port = port; Token = token; }

        public string Post(string body)
        {
            var req = (HttpWebRequest)WebRequest.Create("http://127.0.0.1:" + Port.ToString(CultureInfo.InvariantCulture) + "/mcp");
            req.Method = "POST";
            req.Proxy = null;
            req.AllowAutoRedirect = false;
            req.KeepAlive = false;
            req.Timeout = TimeoutMs;
            req.ReadWriteTimeout = TimeoutMs;
            req.ContentType = "application/json";
            req.Accept = "application/json, text/event-stream";
            req.Headers["Authorization"] = "Bearer " + (Token ?? "");
            byte[] b = Encoding.UTF8.GetBytes(body);
            req.ContentLength = b.Length;
            try
            {
                using (var s = req.GetRequestStream()) s.Write(b, 0, b.Length);
                using (var resp = (HttpWebResponse)req.GetResponse())
                using (var r = new StreamReader(resp.GetResponseStream(), Encoding.UTF8))
                    return r.ReadToEnd();
            }
            catch (WebException ex)
            {
                var hr = ex.Response as HttpWebResponse;
                if (hr == null) throw;   // not running: the caller may start the app
                int code = (int)hr.StatusCode;
                string text = "";
                try { using (var r = new StreamReader(hr.GetResponseStream(), Encoding.UTF8)) text = r.ReadToEnd(); } catch { }
                hr.Close();
                if (code == 401) throw new McpForwardException("Dual Earbuds refused the token. Turn on Settings > AI assistants (or press Rotate token) and restart the assistant.");
                if (code == 403) throw new McpForwardException(text.Length > 0 && text != "Forbidden" ? text : "Dual Earbuds refused the request.");
                throw new McpForwardException("Dual Earbuds answered HTTP " + code + ".");
            }
        }
    }
}
