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

    public class OutSetting { public bool Enabled; public int DelayMs; public int Volume = 100; }

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
                            case "out":
                                if (p.Length >= 5)
                                {
                                    var o = new OutSetting();
                                    o.Enabled = p[2] == "1";
                                    int.TryParse(p[3], out o.DelayMs);
                                    int.TryParse(p[4], out o.Volume);
                                    o.DelayMs = Math.Max(0, Math.Min(1000, o.DelayMs));
                                    o.Volume = Math.Max(0, Math.Min(150, o.Volume));
                                    s.Outs[p[1]] = o;
                                }
                                break;
                        }
                    }
                }
            }
            catch (Exception ex) { Log.Warn("Settings load: " + ex.Message); }
            if (!DualEarbuds.LinkKey.IsValid(s.LinkKey)) s.LinkKey = DualEarbuds.LinkKey.New();
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
                foreach (var kv in Outs)
                    lines.Add(string.Format(CultureInfo.InvariantCulture, "out\t{0}\t{1}\t{2}\t{3}", kv.Key, B(kv.Value.Enabled), kv.Value.DelayMs, kv.Value.Volume));
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
}
