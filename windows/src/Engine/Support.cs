// Dual Earbuds Pro - logging, settings, tiny JSON helpers, link keys.
using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Security.Cryptography;
using System.Text;

namespace DualEarbuds
{
    public static class AppInfo
    {
        public const string Name = "Dual Earbuds";
        public const string Version = "3.0.0";
        public const int Protocol = 3;
        public const string Host = "windows";

        public static string DataDir
        {
            get
            {
                string over = Environment.GetEnvironmentVariable("DUALEARBUDS_DATA");
                if (!string.IsNullOrEmpty(over)) return over;
                return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "DualEarbuds");
            }
        }
    }

    public static class Log
    {
        static readonly object lk = new object();
        static string dir;
        static bool cleaned;

        public static string Dir
        {
            get { return dir ?? Path.Combine(AppInfo.DataDir, "logs"); }
            set { dir = value; }
        }

        public static string CurrentFile { get { return Path.Combine(Dir, "app-" + DateTime.Now.ToString("yyyyMMdd") + ".log"); } }

        public static void Info(string msg) { Write("INFO", msg); }
        public static void Warn(string msg) { Write("WARN", msg); }
        public static void Error(string msg) { Write("ERROR", msg); }

        static void Write(string level, string msg)
        {
            try
            {
                lock (lk)
                {
                    Directory.CreateDirectory(Dir);
                    if (!cleaned) { cleaned = true; CleanOld(); }
                    File.AppendAllText(CurrentFile, DateTime.Now.ToString("HH:mm:ss.fff") + " " + level + " " + msg + Environment.NewLine);
                }
            }
            catch { }
        }

        static void CleanOld()
        {
            try
            {
                foreach (var f in Directory.GetFiles(Dir, "app-*.log"))
                    if (File.GetLastWriteTime(f) < DateTime.Now.AddDays(-7)) File.Delete(f);
            }
            catch { }
        }

        public static string Tail(int maxLines)
        {
            try
            {
                if (!File.Exists(CurrentFile)) return "";
                string[] all;
                lock (lk) all = File.ReadAllLines(CurrentFile);
                int start = Math.Max(0, all.Length - maxLines);
                var sb = new StringBuilder();
                for (int i = start; i < all.Length; i++) sb.AppendLine(all[i]);
                return sb.ToString();
            }
            catch { return ""; }
        }
    }

    public static class LinkKey
    {
        const string Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

        public static string New()
        {
            var bytes = new byte[8];
            using (var rng = new RNGCryptoServiceProvider()) rng.GetBytes(bytes);
            var sb = new StringBuilder(8);
            for (int i = 0; i < 8; i++) sb.Append(Alphabet[bytes[i] % 32]);
            return sb.ToString();
        }

        public static bool IsValid(string k)
        {
            if (k == null || k.Length != 8) return false;
            foreach (char c in k.ToUpperInvariant()) if (Alphabet.IndexOf(c) < 0) return false;
            return true;
        }

        // Case-insensitive comparison that takes the same time whether or not the keys match.
        public static bool Matches(string expected, string given)
        {
            if (expected == null || given == null) return false;
            string a = expected.ToUpperInvariant(), b = given.ToUpperInvariant();
            int diff = a.Length ^ b.Length;
            for (int i = 0; i < a.Length; i++) diff |= a[i] ^ (i < b.Length ? b[i] : 0);
            return diff == 0;
        }
    }

    public class OutSetting
    {
        public bool Enabled;
        public int DelayMs;
        public int Volume = 100;
        public string Eq = "flat";       // manual EQ preset
        public bool EqAuto;               // Smart EQ picks (only when Smart EQ is on)
        public string Profile = "";       // hearing profile name, "" = none
    }

    public class RecentHost
    {
        public string HostPort;   // "ip:port"
        public string Key;
        public string Name = "";
        public string Link { get { return "http://" + HostPort + "/?k=" + Key; } }
    }

    public class Settings
    {
        public const string NetScopePrivate = "private";   // firewall profile 2
        public const string NetScopeAll = "all";            // firewall profiles 2 + 4 (installer default)

        public string SourceId = "";          // "" = follow the Windows default output
        public string Theme = "system";       // system | dark | light
        public bool AutoStart = true;         // start mirroring when the app opens
        public bool CloseToTray = true;       // the X button hides to the tray instead of exiting
        public bool StartMinimized = false;
        public bool PhoneLink = true;
        public int PhonePort = 8790;
        public string LinkKey = "";
        public bool Hotkey = true;            // Ctrl+Alt+M starts/stops mirroring
        public bool FirstRun = true;
        public bool TrayTipShown = false;
        public bool Party = false;            // Party mode: up to 16 listeners
        public bool CheckUpdates = true;      // look for a new release at most once a day
        public DateTime LastUpdateCheckUtc = DateTime.MinValue;
        public string NetworkScope = NetScopeAll;   // which Windows network types phones may connect on (firewall rule)
        public Dictionary<string, OutSetting> Outs = new Dictionary<string, OutSetting>();

        // protocol v3.1 host options
        public bool RemoteControl = false;    // "Let listeners control playback"
        public bool ShowOnNetwork = false;    // advertise the key even on public networks
        public bool PinOn = false;            // Party mode room PIN
        public string Pin = "";
        public string SuggestEq = "";         // "" = no suggestion
        public string SourceMode = "all";     // all | app
        public string SourceApp = "";         // process name for "One app only"
        public string TalkMode = "hold";      // hold | toggle

        // smart features (docs/smart-features.md)
        public string Noise = "strong";       // off | light | strong   (talk-over mic)
        public string SmartEq = "off";        // off | app | sound | both
        public string Dialogue = "off";       // off | light | strong | night
        public string Buffer = "smart";       // smart | steady | manual (listener mode)
        public int BufferMs = 80;
        public Dictionary<string, double> LatencyByName = new Dictionary<string, double>();   // calibrated output latency
        public List<HearingProfile> Profiles = new List<HearingProfile>();

        // listener mode
        public List<RecentHost> Recent = new List<RecentHost>();
        public List<string> ListenOutputs = new List<string>();
        public const int MaxRecent = 5;

        static readonly string[] NoiseValues = { "off", "light", "strong" }, SmartEqValues = { "off", "app", "sound", "both" },
                                 DialogueValues = { "off", "light", "strong", "night" }, BufferValues = { "smart", "steady", "manual" };

        static bool OneOf(string v, string[] set) { return Array.IndexOf(set, v) >= 0; }

        // Most recent first, no duplicates (same address), at most 5.
        public void AddRecent(string hostPort, string key, string name)
        {
            Recent.RemoveAll(r => string.Equals(r.HostPort, hostPort, StringComparison.OrdinalIgnoreCase));
            Recent.Insert(0, new RecentHost { HostPort = hostPort, Key = key.ToUpperInvariant(), Name = Clean(name) });
            while (Recent.Count > MaxRecent) Recent.RemoveAt(Recent.Count - 1);
        }

        static string Clean(string s) { return (s ?? "").Replace("\t", " ").Replace("\r", " ").Replace("\n", " ").Trim(); }

        public HearingProfile FindProfile(string name)
        {
            if (string.IsNullOrEmpty(name)) return null;
            return Profiles.Find(p => p.Name == name);
        }

        public double LatencyFor(string outputName)
        {
            double v;
            return outputName != null && LatencyByName.TryGetValue(outputName, out v) ? v : 0;
        }

        public static string DefaultPath { get { return Path.Combine(AppInfo.DataDir, "settings.txt"); } }

        public static Settings Load() { return Load(DefaultPath); }

        public static Settings Load(string path)
        {
            var s = new Settings();
            try
            {
                if (File.Exists(path))
                {
                    s.FirstRun = false;
                    foreach (var line in File.ReadAllLines(path))
                    {
                        var p = line.Split('\t');
                        if (p.Length < 2) continue;
                        string v = p[1];
                        switch (p[0])
                        {
                            case "source": s.SourceId = v; break;
                            case "theme": if (v == "dark" || v == "light" || v == "system") s.Theme = v; break;
                            case "autostart": s.AutoStart = v == "1"; break;
                            case "closetotray": s.CloseToTray = v == "1"; break;
                            case "startminimized": s.StartMinimized = v == "1"; break;
                            case "phone": s.PhoneLink = v == "1"; break;
                            case "port":
                                int port;
                                if (int.TryParse(v, out port) && port >= 1024 && port <= 65535) s.PhonePort = port;
                                break;
                            case "key": if (DualEarbuds.LinkKey.IsValid(v)) s.LinkKey = v.ToUpperInvariant(); break;
                            case "hotkey": s.Hotkey = v == "1"; break;
                            case "traytip": s.TrayTipShown = v == "1"; break;
                            case "party": s.Party = v == "1"; break;
                            case "updates": s.CheckUpdates = v == "1"; break;
                            case "updatechecked":
                                long ticks;
                                if (long.TryParse(v, NumberStyles.Integer, CultureInfo.InvariantCulture, out ticks) && ticks >= DateTime.MinValue.Ticks && ticks <= DateTime.MaxValue.Ticks)
                                    s.LastUpdateCheckUtc = new DateTime(ticks, DateTimeKind.Utc);
                                break;
                            case "netscope": if (v == NetScopePrivate || v == NetScopeAll) s.NetworkScope = v; break;
                            case "remote": s.RemoteControl = v == "1"; break;
                            case "shownet": s.ShowOnNetwork = v == "1"; break;
                            case "pinon": s.PinOn = v == "1"; break;
                            case "pin": if (RoomPin.IsValid(v)) s.Pin = v; break;
                            case "suggesteq": if (v == "" || EqPresets.IsValid(v)) s.SuggestEq = v; break;
                            case "sourcemode": if (v == "all" || v == "app") s.SourceMode = v; break;
                            case "sourceapp": s.SourceApp = v; break;
                            case "talkmode": if (v == "hold" || v == "toggle") s.TalkMode = v; break;
                            case "noise": if (OneOf(v, NoiseValues)) s.Noise = v; break;
                            case "smarteq": if (OneOf(v, SmartEqValues)) s.SmartEq = v; break;
                            case "dialogue": if (OneOf(v, DialogueValues)) s.Dialogue = v; break;
                            case "buffer": if (OneOf(v, BufferValues)) s.Buffer = v; break;
                            case "bufferms":
                                int bm;
                                if (int.TryParse(v, NumberStyles.Integer, CultureInfo.InvariantCulture, out bm)) s.BufferMs = JitterBuffer.Clamp(bm);
                                break;
                            case "latency":
                                double lat;
                                if (p.Length >= 3 && double.TryParse(p[2], NumberStyles.Float, CultureInfo.InvariantCulture, out lat) && lat >= 0 && lat <= 1000) s.LatencyByName[v] = lat;
                                break;
                            case "profile":
                                var hp = HearingProfile.Parse(v);
                                if (hp != null && s.FindProfile(hp.Name) == null && s.Profiles.Count < 20) s.Profiles.Add(hp);
                                break;
                            case "recent":
                                if (p.Length >= 3 && DualEarbuds.LinkKey.IsValid(p[2]) && s.Recent.Count < MaxRecent)
                                    s.Recent.Add(new RecentHost { HostPort = v, Key = p[2].ToUpperInvariant(), Name = p.Length >= 4 ? p[3] : "" });
                                break;
                            case "listenout": if (v.Length > 0 && !s.ListenOutputs.Contains(v)) s.ListenOutputs.Add(v); break;
                            case "outfx":
                                if (p.Length >= 5)
                                {
                                    var of = s.For(v);
                                    if (EqPresets.IsValid(p[2])) of.Eq = p[2];
                                    of.EqAuto = p[3] == "1";
                                    of.Profile = p[4];
                                }
                                break;
                            case "out":
                                if (p.Length >= 5)
                                {
                                    var o = s.For(p[1]);
                                    o.Enabled = p[2] == "1";
                                    int.TryParse(p[3], out o.DelayMs);
                                    int.TryParse(p[4], out o.Volume);
                                    o.DelayMs = Math.Max(0, Math.Min(1000, o.DelayMs));
                                    o.Volume = Math.Max(0, Math.Min(150, o.Volume));
                                }
                                break;
                        }
                    }
                }
            }
            catch (Exception ex) { Log.Warn("Settings load: " + ex.Message); }
            if (!DualEarbuds.LinkKey.IsValid(s.LinkKey)) s.LinkKey = DualEarbuds.LinkKey.New();
            if (!RoomPin.IsValid(s.Pin)) { s.Pin = RoomPin.New(); if (s.PinOn) s.PinOn = false; }
            return s;
        }

        public void Save() { Save(DefaultPath); }

        public void Save(string path)
        {
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(path));
                var lines = new List<string>();
                lines.Add("source\t" + SourceId);
                lines.Add("theme\t" + Theme);
                lines.Add("autostart\t" + B(AutoStart));
                lines.Add("closetotray\t" + B(CloseToTray));
                lines.Add("startminimized\t" + B(StartMinimized));
                lines.Add("phone\t" + B(PhoneLink));
                lines.Add("port\t" + PhonePort.ToString(CultureInfo.InvariantCulture));
                lines.Add("key\t" + LinkKey);
                lines.Add("hotkey\t" + B(Hotkey));
                lines.Add("traytip\t" + B(TrayTipShown));
                lines.Add("party\t" + B(Party));
                lines.Add("updates\t" + B(CheckUpdates));
                lines.Add("updatechecked\t" + LastUpdateCheckUtc.Ticks.ToString(CultureInfo.InvariantCulture));
                lines.Add("netscope\t" + NetworkScope);
                lines.Add("remote\t" + B(RemoteControl));
                lines.Add("shownet\t" + B(ShowOnNetwork));
                lines.Add("pinon\t" + B(PinOn));
                lines.Add("pin\t" + Pin);
                lines.Add("suggesteq\t" + SuggestEq);
                lines.Add("sourcemode\t" + SourceMode);
                lines.Add("sourceapp\t" + Clean(SourceApp));
                lines.Add("talkmode\t" + TalkMode);
                lines.Add("noise\t" + Noise);
                lines.Add("smarteq\t" + SmartEq);
                lines.Add("dialogue\t" + Dialogue);
                lines.Add("buffer\t" + Buffer);
                lines.Add("bufferms\t" + BufferMs.ToString(CultureInfo.InvariantCulture));
                foreach (var kv in LatencyByName)
                    lines.Add("latency\t" + Clean(kv.Key) + "\t" + kv.Value.ToString("0.#", CultureInfo.InvariantCulture));
                foreach (var hp in Profiles) lines.Add("profile\t" + hp.Serialize());
                foreach (var r in Recent) lines.Add("recent\t" + r.HostPort + "\t" + r.Key + "\t" + Clean(r.Name));
                foreach (var id in ListenOutputs) lines.Add("listenout\t" + id);
                foreach (var kv in Outs)
                {
                    lines.Add(string.Format(CultureInfo.InvariantCulture, "out\t{0}\t{1}\t{2}\t{3}", kv.Key, B(kv.Value.Enabled), kv.Value.DelayMs, kv.Value.Volume));
                    lines.Add("outfx\t" + kv.Key + "\t" + kv.Value.Eq + "\t" + B(kv.Value.EqAuto) + "\t" + Clean(kv.Value.Profile));
                }
                string tmp = path + ".tmp";
                File.WriteAllLines(tmp, lines.ToArray());
                if (File.Exists(path)) File.Replace(tmp, path, null);   // atomic: never leaves no settings file
                else File.Move(tmp, path);
            }
            catch (Exception ex) { Log.Warn("Settings save: " + ex.Message); }
        }

        static string B(bool b) { return b ? "1" : "0"; }

        public OutSetting For(string id)
        {
            OutSetting o;
            if (!Outs.TryGetValue(id, out o)) { o = new OutSetting(); Outs[id] = o; }
            return o;
        }
    }

    // Minimal JSON for the flat messages in PROTOCOL.md.
    public static class MiniJson
    {
        // Parses a flat JSON object. Values: string, number (double), bool, null. Nested objects/arrays are skipped.
        public static Dictionary<string, object> ParseObject(string s)
        {
            var d = new Dictionary<string, object>();
            int i = 0;
            Ws(s, ref i);
            if (i >= s.Length || s[i] != '{') throw new FormatException("expected {");
            i++;
            Ws(s, ref i);
            if (i < s.Length && s[i] == '}') return d;
            while (true)
            {
                Ws(s, ref i);
                string key = Str(s, ref i);
                Ws(s, ref i);
                if (i >= s.Length || s[i] != ':') throw new FormatException("expected :");
                i++;
                Ws(s, ref i);
                d[key] = Value(s, ref i);
                Ws(s, ref i);
                if (i >= s.Length) throw new FormatException("unterminated object");
                if (s[i] == ',') { i++; continue; }
                if (s[i] == '}') { i++; break; }
                throw new FormatException("expected , or }");
            }
            return d;
        }

        // Parses any JSON value (used for GitHub's release JSON, which nests objects and arrays).
        // Objects become Dictionary<string, object>, arrays List<object>, numbers double. Depth is capped.
        public const int MaxDepth = 32;

        public static object Parse(string s)
        {
            if (s == null) throw new FormatException("no input");
            int i = 0;
            Ws(s, ref i);
            object v = Any(s, ref i, 0);
            Ws(s, ref i);
            if (i != s.Length) throw new FormatException("trailing characters");
            return v;
        }

        static object Any(string s, ref int i, int depth)
        {
            if (depth > MaxDepth) throw new FormatException("too deeply nested");
            if (i >= s.Length) throw new FormatException("missing value");
            char c = s[i];
            if (c == '{')
            {
                var d = new Dictionary<string, object>();
                i++;
                Ws(s, ref i);
                if (i < s.Length && s[i] == '}') { i++; return d; }
                while (true)
                {
                    Ws(s, ref i);
                    string key = Str(s, ref i);
                    Ws(s, ref i);
                    if (i >= s.Length || s[i] != ':') throw new FormatException("expected :");
                    i++;
                    Ws(s, ref i);
                    d[key] = Any(s, ref i, depth + 1);
                    Ws(s, ref i);
                    if (i >= s.Length) throw new FormatException("unterminated object");
                    if (s[i] == ',') { i++; continue; }
                    if (s[i] == '}') { i++; return d; }
                    throw new FormatException("expected , or }");
                }
            }
            if (c == '[')
            {
                var l = new List<object>();
                i++;
                Ws(s, ref i);
                if (i < s.Length && s[i] == ']') { i++; return l; }
                while (true)
                {
                    Ws(s, ref i);
                    l.Add(Any(s, ref i, depth + 1));
                    Ws(s, ref i);
                    if (i >= s.Length) throw new FormatException("unterminated array");
                    if (s[i] == ',') { i++; continue; }
                    if (s[i] == ']') { i++; return l; }
                    throw new FormatException("expected , or ]");
                }
            }
            return Value(s, ref i);
        }

        static void Ws(string s, ref int i) { while (i < s.Length && char.IsWhiteSpace(s[i])) i++; }

        static object Value(string s, ref int i)
        {
            if (i >= s.Length) throw new FormatException("missing value");
            char c = s[i];
            if (c == '"') return Str(s, ref i);
            if (c == '{' || c == '[') { Skip(s, ref i); return null; }
            if (s.Length - i >= 4 && string.CompareOrdinal(s, i, "true", 0, 4) == 0) { i += 4; return true; }
            if (s.Length - i >= 5 && string.CompareOrdinal(s, i, "false", 0, 5) == 0) { i += 5; return false; }
            if (s.Length - i >= 4 && string.CompareOrdinal(s, i, "null", 0, 4) == 0) { i += 4; return null; }
            int start = i;
            while (i < s.Length && "+-0123456789.eE".IndexOf(s[i]) >= 0) i++;
            if (i == start) throw new FormatException("bad value");
            return double.Parse(s.Substring(start, i - start), NumberStyles.Float, CultureInfo.InvariantCulture);
        }

        static void Skip(string s, ref int i)
        {
            int depth = 0;
            bool inStr = false;
            for (; i < s.Length; i++)
            {
                char c = s[i];
                if (inStr)
                {
                    if (c == '\\') i++;
                    else if (c == '"') inStr = false;
                    continue;
                }
                if (c == '"') inStr = true;
                else if (c == '{' || c == '[') depth++;
                else if (c == '}' || c == ']') { depth--; if (depth == 0) { i++; return; } }
            }
            throw new FormatException("unterminated value");
        }

        static string Str(string s, ref int i)
        {
            if (i >= s.Length || s[i] != '"') throw new FormatException("expected string");
            i++;
            var sb = new StringBuilder();
            while (i < s.Length)
            {
                char c = s[i++];
                if (c == '"') return sb.ToString();
                if (c != '\\') { sb.Append(c); continue; }
                if (i >= s.Length) break;
                char e = s[i++];
                switch (e)
                {
                    case 'n': sb.Append('\n'); break;
                    case 't': sb.Append('\t'); break;
                    case 'r': sb.Append('\r'); break;
                    case 'b': sb.Append('\b'); break;
                    case 'f': sb.Append('\f'); break;
                    case 'u':
                        if (i + 4 > s.Length) throw new FormatException("bad escape");
                        sb.Append((char)int.Parse(s.Substring(i, 4), NumberStyles.HexNumber, CultureInfo.InvariantCulture));
                        i += 4;
                        break;
                    default: sb.Append(e); break;
                }
            }
            throw new FormatException("unterminated string");
        }

        public static string Escape(string s)
        {
            if (s == null) return "";
            var sb = new StringBuilder(s.Length + 8);
            foreach (char c in s)
            {
                switch (c)
                {
                    case '"': sb.Append("\\\""); break;
                    case '\\': sb.Append("\\\\"); break;
                    case '\n': sb.Append("\\n"); break;
                    case '\r': sb.Append("\\r"); break;
                    case '\t': sb.Append("\\t"); break;
                    default:
                        if (c < 0x20) sb.Append("\\u").Append(((int)c).ToString("x4"));
                        else sb.Append(c);
                        break;
                }
            }
            return sb.ToString();
        }

        public static string GetString(Dictionary<string, object> d, string key, string def)
        {
            object v;
            return d.TryGetValue(key, out v) && v is string ? (string)v : def;
        }

        public static int GetInt(Dictionary<string, object> d, string key, int def)
        {
            object v;
            if (d.TryGetValue(key, out v) && v is double)
            {
                double x = (double)v;
                if (x >= int.MinValue && x <= int.MaxValue) return (int)Math.Round(x);
            }
            return def;
        }

        public static bool GetBool(Dictionary<string, object> d, string key, bool def)
        {
            object v;
            return d.TryGetValue(key, out v) && v is bool ? (bool)v : def;
        }
    }

    // Optional room PIN for Party mode (protocol v3.1): 4-6 digits, never in the QR code or link.
    public static class RoomPin
    {
        public static bool IsValid(string p)
        {
            if (p == null || p.Length < 4 || p.Length > 6) return false;
            foreach (char c in p) if (c < '0' || c > '9') return false;
            return true;
        }

        public static string New()
        {
            var b = new byte[4];
            using (var rng = new RNGCryptoServiceProvider()) rng.GetBytes(b);
            uint v = BitConverter.ToUInt32(b, 0) % 10000;
            return v.ToString("0000", CultureInfo.InvariantCulture);
        }

        // Same time whether or not it matches.
        public static bool Matches(string expected, string given)
        {
            if (expected == null || given == null) return false;
            int diff = expected.Length ^ given.Length;
            for (int i = 0; i < expected.Length; i++) diff |= expected[i] ^ (i < given.Length ? given[i] : 0);
            return diff == 0;
        }
    }
}
