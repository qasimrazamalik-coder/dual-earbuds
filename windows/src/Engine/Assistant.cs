// Dual Earbuds - the built-in assistant ("Ask", docs/assistant-and-mcp.md): retrieval over the bundled help
// content (docs/help/*.md, one chunk per "##" section) with BM25, synonym / Roman Urdu expansion, live facts
// built from the app's own state, and an extractive answer. Pure code, no ML runtime, nothing leaves the laptop.
using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Reflection;
using System.Text;

namespace DualEarbuds
{
    // One retrievable chunk: a "##" section of a help file.
    public class HelpSection
    {
        public string File = "";      // "getting-started.md"
        public string Topic = "";     // the file's "#" title
        public string Title = "";     // the "##" question
        public string Body = "";      // everything under it (keywords comments removed)
        public string Keywords = "";  // the file's keywords comment plus the section's own
        public int Index;             // position in the file

        public string Id { get { return File + "#" + Index.ToString(CultureInfo.InvariantCulture); } }
    }

    public static class HelpParser
    {
        // "# Topic" / "<!-- keywords: a, b -->" / "## Question?" + body. Keywords before the first "##" apply to every
        // section of the file; a keywords comment inside a section adds to that section only.
        public static List<HelpSection> Parse(string file, string text)
        {
            var list = new List<HelpSection>();
            if (string.IsNullOrEmpty(text)) return list;
            string topic = "", fileKeywords = "";
            HelpSection cur = null;
            var body = new StringBuilder();
            string[] lines = text.Replace("\r\n", "\n").Replace('\r', '\n').Split('\n');
            for (int i = 0; i < lines.Length; i++)
            {
                string line = lines[i];
                string t = line.Trim();
                string kw = KeywordsOf(t);
                if (kw != null)
                {
                    if (cur == null) fileKeywords = Join(fileKeywords, kw);
                    else cur.Keywords = Join(cur.Keywords, kw);
                    continue;
                }
                if (t.StartsWith("## ") || t == "##")
                {
                    Close(cur, body, list);
                    cur = new HelpSection { File = file ?? "", Topic = topic, Title = t.Length > 2 ? t.Substring(3).Trim() : "", Keywords = fileKeywords, Index = list.Count };
                    body.Length = 0;
                    continue;
                }
                if (t.StartsWith("# ") && cur == null)
                {
                    topic = t.Substring(2).Trim();
                    continue;
                }
                if (cur != null) body.Append(line.TrimEnd()).Append('\n');
            }
            Close(cur, body, list);
            return list;
        }

        static void Close(HelpSection cur, StringBuilder body, List<HelpSection> list)
        {
            if (cur == null) return;
            cur.Body = body.ToString().Trim();
            if (cur.Title.Length > 0 || cur.Body.Length > 0) { cur.Index = list.Count; list.Add(cur); }
        }

        static string Join(string a, string b) { return a.Length == 0 ? b : a + ", " + b; }

        // "<!-- keywords: a, b -->" -> "a, b"; anything else -> null.
        public static string KeywordsOf(string t)
        {
            if (!t.StartsWith("<!--") || !t.EndsWith("-->")) return null;
            string inner = t.Substring(4, t.Length - 7).Trim();
            if (!inner.StartsWith("keywords:", StringComparison.OrdinalIgnoreCase)) return null;
            return inner.Substring(9).Trim();
        }

        // The topic title of a help file ("# ..."), or the file name without extension.
        public static string TopicOf(string file, string text)
        {
            foreach (var raw in (text ?? "").Replace("\r", "").Split('\n'))
            {
                string t = raw.Trim();
                if (t.StartsWith("# ")) return t.Substring(2).Trim();
                if (t.StartsWith("## ")) break;
            }
            return Path.GetFileNameWithoutExtension(file ?? "");
        }
    }

    // Lowercase words, a small stop-word list (English + Roman Urdu filler), light English stemming.
    public static class TextTok
    {
        static readonly HashSet<string> Stop = new HashSet<string>
        {
            "a", "an", "the", "is", "are", "am", "was", "were", "be", "been", "being", "to", "of", "and", "or", "in", "on",
            "at", "for", "with", "my", "me", "i", "it", "its", "this", "that", "these", "those", "do", "does", "did", "how",
            "what", "why", "can", "could", "should", "would", "will", "you", "your", "from", "by", "as", "so", "if",
            "there", "here", "when", "which", "who", "has", "have", "had", "please", "just", "about", "into", "than",
            "then", "them", "they", "we", "our", "us", "he", "she", "his", "her", "any", "some", "also", "very", "get", "got", "want",
            // Roman Urdu filler
            "hai", "hain", "ka", "ki", "ke", "ko", "mein", "main", "mera", "meri", "mere", "kya", "ho", "raha", "rahi",
            "rahe", "aa", "pe", "par", "bhi", "tha", "thi", "kyun", "kyon", "kaise", "kese", "yeh", "ye", "woh", "wo", "to",
        };

        public static bool IsStop(string w) { return w.Length == 0 || (w.Length == 1 && !char.IsDigit(w[0])) || Stop.Contains(w); }

        // Raw lowercase words (letters and digits), stop words kept: used for phrase matching.
        public static List<string> Words(string s)
        {
            var list = new List<string>();
            if (string.IsNullOrEmpty(s)) return list;
            var sb = new StringBuilder();
            foreach (char c0 in s)
            {
                char c = char.ToLowerInvariant(c0);
                if (char.IsLetterOrDigit(c)) sb.Append(c);
                else if (sb.Length > 0) { list.Add(sb.ToString()); sb.Length = 0; }
            }
            if (sb.Length > 0) list.Add(sb.ToString());
            return list;
        }

        public static List<string> Tokenize(string s)
        {
            var list = new List<string>();
            foreach (var w in Words(s))
            {
                if (IsStop(w)) continue;
                list.Add(Stem(w));
            }
            return list;
        }

        // Strips ing / ed / ly / es / s when the stem stays >= 3 letters, then a final e, so that
        // phone/phones, share/sharing/shared, late/later?, battery/batteries meet.
        public static string Stem(string w)
        {
            if (w == null) return "";
            if (w.Length <= 3) return w;
            foreach (char c in w) if (char.IsDigit(c)) return w;   // "2.4", "5ghz", "8790" stay as they are
            string s = w;
            bool cut = false;
            if (s.EndsWith("ies") && s.Length - 3 >= 2) s = s.Substring(0, s.Length - 3) + "y";
            else if (s.EndsWith("ing") && s.Length - 3 >= 3) { s = s.Substring(0, s.Length - 3); cut = true; }
            else if (s.EndsWith("ed") && s.Length - 2 >= 3) { s = s.Substring(0, s.Length - 2); cut = true; }
            else if (s.EndsWith("ly") && s.Length - 2 >= 3) s = s.Substring(0, s.Length - 2);
            else if (s.EndsWith("es") && s.Length - 2 >= 3 && EsPlural(s)) s = s.Substring(0, s.Length - 2);
            else if (s.EndsWith("s") && !s.EndsWith("ss") && !s.EndsWith("us") && s.Length - 1 >= 3) s = s.Substring(0, s.Length - 1);
            if (cut && s.Length >= 4 && s[s.Length - 1] == s[s.Length - 2] && "lsz".IndexOf(s[s.Length - 1]) < 0 && !IsVowel(s[s.Length - 1]))
                s = s.Substring(0, s.Length - 1);   // stopped -> stop
            if (s.EndsWith("e") && s.Length - 1 >= 3) s = s.Substring(0, s.Length - 1);
            return s;
        }

        static bool IsVowel(char c) { return "aeiou".IndexOf(c) >= 0; }

        static bool EsPlural(string s)
        {
            char p = s[s.Length - 3];
            if (p == 's' || p == 'x' || p == 'z') return true;
            return s.Length >= 4 && (s.EndsWith("ches") || s.EndsWith("shes"));
        }
    }

    // "word: alt1, alt2" lines (docs/help/_synonyms.txt). A key may be a phrase ("chal nahi raha: not working").
    public class Synonyms
    {
        public const double ExpandWeight = 0.8, PhraseWeight = 1.0;   // a matched phrase is a precise signal
        readonly Dictionary<string, List<string>> single = new Dictionary<string, List<string>>();     // stemmed key -> alternatives
        readonly List<KeyValuePair<string, List<string>>> phrases = new List<KeyValuePair<string, List<string>>>();   // " w1 w2 " -> alternatives

        public int Count { get { return single.Count + phrases.Count; } }

        public static Synonyms Parse(string text)
        {
            var s = new Synonyms();
            if (string.IsNullOrEmpty(text)) return s;
            foreach (var raw in text.Replace("\r", "").Split('\n'))
            {
                string line = raw.Trim();
                if (line.Length == 0 || line.StartsWith("#")) continue;
                int c = line.IndexOf(':');
                if (c <= 0) continue;
                var keyWords = TextTok.Words(line.Substring(0, c));
                if (keyWords.Count == 0) continue;
                var alts = new List<string>();
                foreach (var a in line.Substring(c + 1).Split(','))
                {
                    string t = a.Trim().ToLowerInvariant();
                    if (t.Length > 0) alts.Add(t);
                }
                if (alts.Count == 0) continue;
                if (keyWords.Count == 1)
                {
                    string k = TextTok.Stem(keyWords[0]);
                    List<string> l;
                    if (!s.single.TryGetValue(k, out l)) { l = new List<string>(); s.single[k] = l; }
                    l.AddRange(alts);
                }
                else s.phrases.Add(new KeyValuePair<string, List<string>>(" " + string.Join(" ", keyWords.ToArray()) + " ", alts));
            }
            return s;
        }

        // Query terms with weights: the question's own tokens (1.0), a matched phrase's alternatives (1.0) and a single
        // word's alternatives (0.8).
        public Dictionary<string, double> Expand(string question)
        {
            var q = new Dictionary<string, double>();
            var words = TextTok.Words(question);
            foreach (var w in words)
            {
                if (TextTok.IsStop(w)) continue;
                Add(q, TextTok.Stem(w), 1.0);
            }
            string joined = " " + string.Join(" ", words.ToArray()) + " ";
            foreach (var p in phrases)
                if (joined.Contains(p.Key)) foreach (var alt in p.Value) foreach (var t in TextTok.Tokenize(alt)) Add(q, t, PhraseWeight);
            foreach (var w in words)
            {
                List<string> alts;
                if (!single.TryGetValue(TextTok.Stem(w), out alts)) continue;
                foreach (var alt in alts) foreach (var t in TextTok.Tokenize(alt)) Add(q, t, ExpandWeight);
            }
            return q;
        }

        // Multi-word alternatives of the synonyms the question matched ("phone se phone" -> "phone to phone"), as
        // " w1 w2 " for phrase matching against the help text.
        public List<string> Phrases(string question)
        {
            var res = new List<string>();
            var words = TextTok.Words(question);
            string joined = " " + string.Join(" ", words.ToArray()) + " ";
            var alts = new List<string>();
            foreach (var p in phrases) if (joined.Contains(p.Key)) alts.AddRange(p.Value);
            foreach (var w in words)
            {
                List<string> l;
                if (single.TryGetValue(TextTok.Stem(w), out l)) alts.AddRange(l);
            }
            foreach (var a in alts)
            {
                var aw = TextTok.Words(a);
                if (aw.Count < 2) continue;
                string ph = " " + string.Join(" ", aw.ToArray()) + " ";
                if (!res.Contains(ph)) res.Add(ph);
            }
            return res;
        }

        static void Add(Dictionary<string, double> q, string t, double w)
        {
            if (string.IsNullOrEmpty(t)) return;
            double old;
            if (!q.TryGetValue(t, out old) || old < w) q[t] = w;
        }
    }

    public class ScoredSection
    {
        public HelpSection Section;
        public double Score;
    }

    // BM25 (k1 1.2, b 0.75) over a weighted bag of words per section: title 2x, body 1x, keywords comment 1.5x.
    public class HelpIndex
    {
        public const double K1 = 1.2, B = 0.75, TitleWeight = 2.0, BodyWeight = 1.0, KeywordWeight = 1.5;
        // A section that contains a synonym's whole phrase ("phone to phone") gets this on top of BM25.
        public const double PhraseBonus = 2.5;
        // Users ask in the words of the section titles (which are questions): a section gets up to this much on top
        // for the share of the question's known terms its title holds. Breaks ties between sections of one file,
        // which share the file's keywords.
        public const double TitleBonus = 3.0;

        class Doc { public HelpSection S; public Dictionary<string, double> Tf = new Dictionary<string, double>(); public double Len; public string Words = " "; public HashSet<string> TitleTerms; }

        readonly List<Doc> docs = new List<Doc>();
        readonly Dictionary<string, int> df = new Dictionary<string, int>();
        double avgLen = 1;

        public HelpIndex(IEnumerable<HelpSection> sections)
        {
            foreach (var s in sections)
            {
                var d = new Doc { S = s };
                AddField(d, s.Title, TitleWeight);
                AddField(d, s.Body, BodyWeight);
                AddField(d, s.Keywords, KeywordWeight);
                d.TitleTerms = new HashSet<string>(TextTok.Tokenize(s.Title));
                d.Words = " " + string.Join(" ", TextTok.Words(s.Title + " " + s.Keywords + " " + s.Body).ToArray()) + " ";
                docs.Add(d);
                foreach (var t in d.Tf.Keys) { int n; df.TryGetValue(t, out n); df[t] = n + 1; }
            }
            double sum = 0;
            foreach (var d in docs) sum += d.Len;
            avgLen = docs.Count > 0 ? Math.Max(1, sum / docs.Count) : 1;
        }

        static void AddField(Doc d, string text, double w)
        {
            foreach (var t in TextTok.Tokenize(text))
            {
                double v;
                d.Tf.TryGetValue(t, out v);
                d.Tf[t] = v + w;
                d.Len += w;
            }
        }

        public int Count { get { return docs.Count; } }

        public double Idf(string term)
        {
            int n;
            if (!df.TryGetValue(term, out n)) n = 0;
            int N = Math.Max(1, docs.Count);
            return Math.Log(1 + (N - n + 0.5) / (n + 0.5));
        }

        public bool Knows(string term) { return df.ContainsKey(term); }

        public List<ScoredSection> Search(Dictionary<string, double> query) { return Search(query, null); }

        public List<ScoredSection> Search(Dictionary<string, double> query, List<string> phrases)
        {
            var res = new List<ScoredSection>();
            double known = 0;
            foreach (var kv in query) if (df.ContainsKey(kv.Key)) known += kv.Value;
            foreach (var d in docs)
            {
                double score = 0, inTitle = 0;
                foreach (var kv in query)
                {
                    double tf;
                    if (!d.Tf.TryGetValue(kv.Key, out tf)) continue;
                    double norm = tf * (K1 + 1) / (tf + K1 * (1 - B + B * d.Len / avgLen));
                    score += kv.Value * Idf(kv.Key) * norm;
                    if (d.TitleTerms.Contains(kv.Key)) inTitle += kv.Value;
                }
                if (score > 0 && known > 0) score += TitleBonus * inTitle / known;
                if (phrases != null && score > 0) foreach (var ph in phrases) if (d.Words.Contains(ph)) score += PhraseBonus;
                res.Add(new ScoredSection { Section = d.S, Score = score });
            }
            // stable: by score, then file order
            var order = new List<int>();
            for (int i = 0; i < res.Count; i++) order.Add(i);
            order.Sort((a, b) => { int c = res[b].Score.CompareTo(res[a].Score); return c != 0 ? c : a.CompareTo(b); });
            var sorted = new List<ScoredSection>();
            foreach (int i in order) sorted.Add(res[i]);
            return sorted;
        }
    }

    // ------------------------------------------------------------------ live facts
    public class OutputSnap
    {
        public string Id = "", Name = "", State = "", Eq = "";
        public bool On, Source, Calibrated, Bluetooth;
        public int Volume = 100, DelayMs, ExtraDelayMs, Battery = -1;
        public double LatencyMs;                 // calibrated, else the estimate
        public double SyncErrMs = double.NaN;    // offset from the deadline when known
    }

    public class ListenerSnap
    {
        public string Id = "", Name = "", Client = "", Earbuds = "", Transport = "ws";
        public int Battery = -1, Volume = 100, NeedMs, RttMs = -1;
        public double SyncErrMs = double.NaN;
        public bool Synced;
    }

    // The app's state at question time (also what the MCP get_status tool reports). Never holds the PIN.
    public class SessionSnapshot
    {
        public bool Sharing, Waiting, Listening, PhoneLinkOn = true, Party, PinOn;
        public string Source = "", Priority = SyncPriority.LipSync, NetworkCategory, Status = "", LastError, ListenHost = "";
        public int PlayoutMs, Capacity = PhoneLink.MaxClients, LocalNeedMs;
        public string LocalNeedName = "";         // what sets the local need: the source output (lip-sync) or the slowest output
        public List<OutputSnap> Outputs = new List<OutputSnap>();
        public List<ListenerSnap> Listeners = new List<ListenerSnap>();
        public WifiInfo Wifi;
        public FirewallStatus Firewall;
        public PowerState Power;
        public List<string> Warnings = new List<string>();
    }

    public class LiveFact
    {
        public string Text = "";
        public string Keywords = "";
        public double Score;
    }

    public static class LiveFacts
    {
        static readonly CultureInfo Inv = CultureInfo.InvariantCulture;
        public const int MaxFacts = 10, MaxEarFacts = 5;

        static string Ms(double v) { return Math.Round(v).ToString(Inv) + " ms"; }

        static string Signed(double v)
        {
            int r = (int)Math.Round(v);
            return (r > 0 ? "+" : r < 0 ? "-" : "\u00B1") + Math.Abs(r).ToString(Inv) + " ms";
        }

        // 4-10 short lines from the current state, each with words that make it come up for the right questions.
        public static List<LiveFact> Build(SessionSnapshot s)
        {
            var f = new List<LiveFact>();
            if (s == null) return f;
            bool lip = SyncPriority.IsLipSync(s.Priority);

            if (s.Listening) Add(f, "This laptop is listening to " + (s.ListenHost.Length > 0 ? s.ListenHost : "another device") + ".", "listen listening host other laptop sharing not working no sound");
            else if (s.Sharing) Add(f, "Sharing is on" + (s.Source.Length > 0 ? ", from " + s.Source : "") + ".", "sharing start stop source no sound not working play");
            else if (s.Waiting) Add(f, "Sharing is waiting for the sound source" + (s.Source.Length > 0 ? " (" + s.Source + ")" : "") + ".", "sharing waiting source no sound not working start");
            else Add(f, "Sharing is stopped. Press Start sharing on Home.", "sharing stopped start no sound not working play");

            Add(f, lip ? "Sync priority is lip-sync: the laptop's own earbuds play at the source's delay so video stays in step; listeners play at P."
                       : "Sync priority is everyone together: every ear, the laptop's own included, waits for the slowest one (P).",
                "sync priority lipsync lip together video late delay behind behind lag movie");

            if (!s.Listening && s.PlayoutMs > 0)
            {
                string why = PlayoutReason(s);
                Add(f, "P is " + Ms(s.PlayoutMs) + (why.Length > 0 ? " because " + why : "") + ".",
                    "late delay lag behind playout p deadline slow sound behind phones listener video");
            }

            // the ears: ticked outputs, then the listeners that need the most (at most MaxEarFacts lines)
            int ears = 0;
            foreach (var o in s.Outputs)
            {
                if (!o.On && !o.Source) continue;
                if (ears++ >= MaxEarFacts) break;
                var sb = new StringBuilder(o.Name);
                sb.Append(o.Source ? " (sound source): " : ": ");
                sb.Append(o.Calibrated ? "latency " + Ms(o.LatencyMs) + " (calibrated)" : "not calibrated, about " + Ms(o.LatencyMs) + " estimated");
                if (!double.IsNaN(o.SyncErrMs)) sb.Append(", sync " + Signed(o.SyncErrMs));
                if (o.State.Length > 0 && o.State != "Playing" && o.State != "Sound source") sb.Append(", " + o.State.ToLowerInvariant());
                sb.Append('.');
                Add(f, sb.ToString(), "earbuds output headphones latency calibrate calibration delay late sync behind " + o.Name);
            }
            var byNeed = new List<ListenerSnap>(s.Listeners);
            byNeed.Sort((a, b) => b.NeedMs.CompareTo(a.NeedMs));
            foreach (var l in byNeed)
            {
                if (ears++ >= MaxEarFacts) break;
                var sb = new StringBuilder(l.Name);
                if (l.NeedMs > 0) sb.Append(" needs " + Ms(l.NeedMs));
                else sb.Append(" has not reported its need yet");
                if (!double.IsNaN(l.SyncErrMs)) sb.Append(", sync " + Signed(l.SyncErrMs));
                sb.Append(l.Transport == "udp" ? ", audio over UDP" : ", audio over WebSocket");
                sb.Append('.');
                Add(f, sb.ToString(), "phone listener listeners late behind delay sync need udp websocket choppy " + l.Name + " " + l.Earbuds);
            }

            if (!string.IsNullOrEmpty(s.LastError))
                Add(f, "Last problem: " + s.LastError, "error problem not working fail failed stopped no sound cant open");

            var fw = s.Firewall;
            if (fw != null && fw.Known)
            {
                if (fw.Complete) Add(f, "Windows Firewall lets Dual Earbuds in over TCP and UDP.", "firewall udp tcp blocked connect join phone no sound");
                else if (fw.Tcp) Add(f, "Windows Firewall blocks UDP for Dual Earbuds: phones fall back to WebSocket audio, which needs more delay.", "firewall udp blocked websocket late delay phone choppy fix");
                else Add(f, "Windows Firewall has no rule letting phones reach Dual Earbuds: Setup check can fix it.", "firewall blocked connect join phone cant reach no sound fix");
            }

            var p = s.Power;
            if (p != null && p.BatterySaver)
                Add(f, "Battery saver is on: Windows may slow the audio timer, which can crackle or stutter.", "battery saver power choppy crackle stutter cut drop");

            var w = s.Wifi;
            if (w != null && w.Connected)
            {
                string band = string.IsNullOrEmpty(w.Band) ? "unknown band" : w.Band;
                string sig = w.SignalPercent >= 0 ? ", signal " + w.SignalPercent.ToString(Inv) + " %" : "";
                Add(f, w.Is24 ? "Wi-Fi is on 2.4 GHz" + sig + ": it is slower and busier than 5 GHz, so phones need more delay."
                              : "Wi-Fi is on " + band + sig + ".",
                    "wifi wi-fi band 2.4 5 ghz signal router slow jitter late choppy crackle network");
            }

            // only a public network is worth a line (phones may be kept out)
            if (s.NetworkCategory == "Public")
                Add(f, "Windows calls this network Public: phones can only join when Settings > Networks listeners may use allows public networks.",
                    "network public private wifi connect join phone firewall cant reach");

            if (f.Count > MaxFacts) f.RemoveRange(MaxFacts, f.Count - MaxFacts);
            return f;
        }

        // Who sets P: the biggest of the listeners' needs and the laptop's own need.
        public static string PlayoutReason(SessionSnapshot s)
        {
            int best = s.LocalNeedMs;
            string who = s.LocalNeedMs > 0 && s.LocalNeedName.Length > 0
                ? (SyncPriority.IsLipSync(s.Priority) ? "the sound source " + s.LocalNeedName + " takes " + Ms(s.LocalNeedMs) : s.LocalNeedName + " needs " + Ms(s.LocalNeedMs))
                : "";
            foreach (var l in s.Listeners)
                if (l.NeedMs > best) { best = l.NeedMs; who = l.Name + (l.Earbuds.Length > 0 ? " (" + l.Earbuds + ")" : "") + " needs " + Ms(l.NeedMs); }
            int floor = SyncPriority.FloorMs(s.Priority);
            if (best <= floor) return "that is the lowest it goes (" + Ms(floor) + ")";
            if (best > PlayoutPlanner.MaxMs) return who + ", and 400 ms is the most it waits";
            return who;
        }

        static void Add(List<LiveFact> f, string text, string kw) { f.Add(new LiveFact { Text = text, Keywords = kw }); }
    }

    // ------------------------------------------------------------------ answers
    public class AssistantAnswer
    {
        public string Question = "";
        public bool Found;
        public HelpSection Best;
        public string AnswerText = "";
        public List<string> Steps = new List<string>();
        public List<string> Notes = new List<string>();
        public List<LiveFact> Facts = new List<LiveFact>();
        public List<HelpSection> Related = new List<HelpSection>();
        public List<HelpSection> Sources = new List<HelpSection>();   // the chunks the answer (or a model) used
        public List<HelpSection> Closest = new List<HelpSection>();   // when nothing matched well enough

        public const string NoAnswer = "I don't know that one yet.";

        // Plain text (MCP ask_help, copy).
        public string ToText()
        {
            var sb = new StringBuilder();
            if (!Found)
            {
                sb.AppendLine(NoAnswer);
                if (Closest.Count > 0)
                {
                    sb.AppendLine("Closest topics:");
                    foreach (var c in Closest) sb.AppendLine("- " + c.Title + " (" + c.File + ")");
                }
            }
            else
            {
                sb.AppendLine(Best.Title);
                if (AnswerText.Length > 0) sb.AppendLine(AnswerText);
                for (int i = 0; i < Steps.Count; i++) sb.AppendLine((i + 1).ToString(CultureInfo.InvariantCulture) + ". " + Steps[i]);
                foreach (var n in Notes) sb.AppendLine(n);
            }
            if (Facts.Count > 0)
            {
                sb.AppendLine("Right now:");
                foreach (var f in Facts) sb.AppendLine("- " + f.Text);
            }
            if (Found && Related.Count > 0)
            {
                var r = new List<string>();
                foreach (var x in Related) r.Add(x.Title);
                sb.AppendLine("Related: " + string.Join("; ", r.ToArray()));
            }
            if (Found) sb.AppendLine("Source: " + Best.File + " - " + Best.Title);
            return sb.ToString().TrimEnd();
        }
    }

    public class Assistant
    {
        // Below this BM25 score the best chunk is not trusted as the answer. Stated for a 40-section help set; scaled
        // down with the idf a one-section term gets, so a small help set (fewer sections, lower idf) is not held to it.
        public const double ScoreFloor = 2.2;
        public const int FloorReferenceSections = 40;
        public const int MaxFacts = 4, TopHits = 3, RelatedCount = 2;
        public const string ResourcePrefix = "help/", SynonymsFile = "_synonyms.txt";

        public static readonly string[] SuggestedQuestions =
        {
            "Why is the sound late?",
            "How do I share with a phone?",
            "The phone has no sound",
            "How do I calibrate my earbuds?",
            "Awaz late aa rahi hai",
        };

        readonly List<HelpSection> sections = new List<HelpSection>();
        readonly Dictionary<string, string> files = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        readonly List<string> fileOrder = new List<string>();
        readonly HelpIndex index;
        readonly Synonyms synonyms;

        // files: name -> content; "_synonyms.txt" is the synonym list, every "*.md" a help topic.
        public Assistant(IEnumerable<KeyValuePair<string, string>> helpFiles)
        {
            string syn = "";
            var names = new List<KeyValuePair<string, string>>();
            if (helpFiles != null) foreach (var kv in helpFiles) names.Add(kv);
            names.Sort((a, b) => string.CompareOrdinal(a.Key, b.Key));
            foreach (var kv in names)
            {
                if (string.Equals(kv.Key, SynonymsFile, StringComparison.OrdinalIgnoreCase)) { syn = kv.Value ?? ""; continue; }
                if (!kv.Key.EndsWith(".md", StringComparison.OrdinalIgnoreCase)) continue;
                files[kv.Key] = kv.Value ?? "";
                fileOrder.Add(kv.Key);
                sections.AddRange(HelpParser.Parse(kv.Key, kv.Value));
            }
            synonyms = Synonyms.Parse(syn);
            index = new HelpIndex(sections);
        }

        // The help embedded in the exe by build.ps1 (resources named "help/<file>"). Empty when there is none.
        public static Assistant FromAssembly(Assembly asm)
        {
            var list = new List<KeyValuePair<string, string>>();
            try
            {
                foreach (var name in asm.GetManifestResourceNames())
                {
                    if (!name.StartsWith(ResourcePrefix, StringComparison.Ordinal)) continue;
                    using (var st = asm.GetManifestResourceStream(name))
                    {
                        if (st == null) continue;
                        using (var r = new StreamReader(st, Encoding.UTF8))
                            list.Add(new KeyValuePair<string, string>(name.Substring(ResourcePrefix.Length), r.ReadToEnd()));
                    }
                }
            }
            catch (Exception ex) { Log.Warn("Help content: " + ex.Message); }
            return new Assistant(list);
        }

        public List<HelpSection> Sections { get { return sections; } }
        public List<string> Files { get { return fileOrder; } }
        public int SynonymCount { get { return synonyms.Count; } }

        public string FileText(string name)
        {
            string t;
            return name != null && files.TryGetValue(name, out t) ? t : null;
        }

        public string TopicOf(string file)
        {
            string t = FileText(file);
            return t == null ? null : HelpParser.TopicOf(file, t);
        }

        public Dictionary<string, double> Query(string question) { return synonyms.Expand(question ?? ""); }

        static double IdfOne(int n) { return Math.Log(1 + (Math.Max(1, n) - 0.5) / 1.5); }

        public double Floor { get { return ScoreFloor * Math.Min(1.0, IdfOne(index.Count) / IdfOne(FloorReferenceSections)); } }

        public List<ScoredSection> Search(string question) { return index.Search(Query(question), synonyms.Phrases(question ?? "")); }

        // Facts that match the question by the same terms (idf-weighted); at most MaxFacts, best first.
        public List<LiveFact> MatchFacts(Dictionary<string, double> query, List<LiveFact> facts)
        {
            var hits = new List<LiveFact>();
            if (facts == null) return hits;
            foreach (var f in facts)
            {
                var terms = new HashSet<string>(TextTok.Tokenize(f.Text + " " + f.Keywords));
                double s = 0;
                foreach (var kv in query)
                    if (terms.Contains(kv.Key)) s += kv.Value * (index.Knows(kv.Key) ? index.Idf(kv.Key) : 1.0);
                if (s > 0) hits.Add(new LiveFact { Text = f.Text, Keywords = f.Keywords, Score = s });
            }
            var order = new List<int>();
            for (int i = 0; i < hits.Count; i++) order.Add(i);
            order.Sort((a, b) => { int c = hits[b].Score.CompareTo(hits[a].Score); return c != 0 ? c : a.CompareTo(b); });
            var res = new List<LiveFact>();
            foreach (int i in order) { if (res.Count >= MaxFacts) break; res.Add(hits[i]); }
            return res;
        }

        public AssistantAnswer Ask(string question, SessionSnapshot snapshot)
        {
            var q = Query(question);
            var ranked = index.Search(q, synonyms.Phrases(question ?? ""));
            var ans = new AssistantAnswer { Question = (question ?? "").Trim() };
            ans.Facts = MatchFacts(q, LiveFacts.Build(snapshot));
            double floor = Floor;
            if (ranked.Count == 0 || ranked[0].Score < floor || ranked[0].Score <= 0)
            {
                for (int i = 0; i < ranked.Count && ans.Closest.Count < TopHits; i++) ans.Closest.Add(ranked[i].Section);
                return ans;
            }
            Fill(ans, ranked[0].Section);
            for (int i = 0; i < ranked.Count && ans.Sources.Count < TopHits; i++)
                if (ranked[i].Score >= floor) ans.Sources.Add(ranked[i].Section);
            for (int i = 1; i < ranked.Count && ans.Related.Count < RelatedCount; i++)
                if (ranked[i].Score > 0) ans.Related.Add(ranked[i].Section);
            return ans;
        }

        // Shows one section (a tapped "Related" link) with the facts for its own words.
        public AssistantAnswer Show(HelpSection s, SessionSnapshot snapshot)
        {
            var ans = new AssistantAnswer { Question = s.Title };
            var q = Query(s.Title + " " + s.Keywords);
            ans.Facts = MatchFacts(Query(s.Title), LiveFacts.Build(snapshot));
            Fill(ans, s);
            ans.Sources.Add(s);
            var ranked = index.Search(q);
            foreach (var r in ranked)
            {
                if (ans.Related.Count >= RelatedCount) break;
                if (r.Section != s && r.Score > 0) ans.Related.Add(r.Section);
            }
            return ans;
        }

        static void Fill(AssistantAnswer ans, HelpSection s)
        {
            ans.Found = true;
            ans.Best = s;
            string answer;
            List<string> steps, notes;
            Split(s.Body, out answer, out steps, out notes);
            ans.AnswerText = answer;
            ans.Steps = steps;
            ans.Notes = notes;
        }

        // Body -> first paragraph (the direct answer), numbered or bulleted steps, and any further paragraphs.
        public static void Split(string body, out string answer, out List<string> steps, out List<string> notes)
        {
            answer = "";
            steps = new List<string>();
            notes = new List<string>();
            var para = new StringBuilder();
            foreach (var raw in (body ?? "").Replace("\r", "").Split('\n'))
            {
                string t = raw.Trim();
                string step = StepText(t);
                if (step != null || t.Length == 0)
                {
                    Flush(para, ref answer, notes);
                    if (step != null) steps.Add(Plain(step));
                    continue;
                }
                if (para.Length > 0) para.Append(' ');
                para.Append(t);
            }
            Flush(para, ref answer, notes);
        }

        static void Flush(StringBuilder para, ref string answer, List<string> notes)
        {
            if (para.Length == 0) return;
            string p = Plain(para.ToString());
            if (answer.Length == 0) answer = p; else notes.Add(p);
            para.Length = 0;
        }

        // "1. x", "2) x", "- x", "* x" -> "x"
        static string StepText(string t)
        {
            if (t.StartsWith("- ") || t.StartsWith("* ")) return t.Substring(2).Trim();
            int i = 0;
            while (i < t.Length && char.IsDigit(t[i])) i++;
            if (i > 0 && i < t.Length - 1 && (t[i] == '.' || t[i] == ')') && t[i + 1] == ' ') return t.Substring(i + 2).Trim();
            return null;
        }

        // Markdown emphasis and code ticks removed for display.
        public static string Plain(string s)
        {
            return (s ?? "").Replace("**", "").Replace("__", "").Replace("`", "");
        }
    }
}
