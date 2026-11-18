// Dual Earbuds - optional local model for the assistant (docs/assistant-and-mcp.md "Optional local model"):
// Ollama on this laptop only. The prompt holds just the retrieved help chunks and live facts; every request goes
// to a literal loopback address, never through a proxy, never following redirects. On any failure or after 30 s
// the app keeps the extractive answer.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Net;
using System.Text;

namespace DualEarbuds
{
    public static class LocalModelPrompt
    {
        public const string NotInHelp = "not in the help";
        public const int MaxQuestionChars = 500, MaxSectionChars = 2500;

        // The grounded prompt: instruction, numbered help chunks, live facts, the question.
        public static string Build(string question, IList<HelpSection> sections, IList<LiveFact> facts)
        {
            var sb = new StringBuilder();
            sb.Append("You are the help assistant inside the Dual Earbuds app. Answer the question using ONLY the help sections and ");
            sb.Append("live facts below. If they do not contain the answer, reply exactly: ").Append(NotInHelp).Append(".\n");
            sb.Append("Answer in 1-3 short sentences, then numbered steps if the user has to do something. Use the app's labels ");
            sb.Append("exactly as written. Numbers over adjectives. No emoji. Reply in the language of the question.\n\n");
            sb.Append("Help sections:\n");
            if (sections != null)
                for (int i = 0; i < sections.Count; i++)
                {
                    var s = sections[i];
                    string body = s.Body ?? "";
                    if (body.Length > MaxSectionChars) body = body.Substring(0, MaxSectionChars);
                    sb.Append('[').Append((i + 1).ToString(CultureInfo.InvariantCulture)).Append("] ").Append(s.Title).Append(" (").Append(s.File).Append(")\n");
                    sb.Append(body).Append("\n\n");
                }
            sb.Append("Live facts (the app's state right now):\n");
            if (facts == null || facts.Count == 0) sb.Append("- none\n");
            else foreach (var f in facts) sb.Append("- ").Append(f.Text).Append('\n');
            sb.Append("\nQuestion: ").Append(Clean(question)).Append("\nAnswer:");
            return sb.ToString();
        }

        // One line, no control characters, at most 500 characters.
        public static string Clean(string q)
        {
            var sb = new StringBuilder();
            foreach (char c in q ?? "") sb.Append(char.IsControl(c) ? ' ' : c);
            string r = sb.ToString().Trim();
            return r.Length > MaxQuestionChars ? r.Substring(0, MaxQuestionChars) : r;
        }
    }

    public class OllamaClient
    {
        public const string DefaultUrl = "http://127.0.0.1:11434";
        public const int DefaultTimeoutMs = 30000, TagsTimeoutMs = 2000;
        public const int MaxResponseChars = 20000;

        public readonly Uri Base;
        public int TimeoutMs = DefaultTimeoutMs;

        public OllamaClient() : this(DefaultUrl) { }

        public OllamaClient(string baseUrl)
        {
            Uri u;
            if (!Uri.TryCreate(baseUrl ?? "", UriKind.Absolute, out u) || !IsLoopback(u)) throw new ArgumentException("The local model must be on this laptop (127.0.0.1 or ::1).");
            Base = u;
        }

        // Only plain http to a literal loopback IP: no names (a hosts file or DNS could point "localhost" elsewhere).
        public static bool IsLoopback(Uri u)
        {
            if (u == null || !u.IsAbsoluteUri || u.Scheme != Uri.UriSchemeHttp) return false;
            if (!string.IsNullOrEmpty(u.UserInfo)) return false;
            string host = u.Host;
            if (host.StartsWith("[") && host.EndsWith("]")) host = host.Substring(1, host.Length - 2);
            IPAddress ip;
            if (!IPAddress.TryParse(host, out ip)) return false;
            if (ip.IsIPv4MappedToIPv6) ip = ip.MapToIPv4();
            return IPAddress.IsLoopback(ip);
        }

        HttpWebRequest Request(string path, string method, int timeoutMs)
        {
            var u = new Uri(Base, path);
            if (!IsLoopback(u)) throw new InvalidOperationException("not a loopback address");
            var req = (HttpWebRequest)WebRequest.Create(u);
            req.Method = method;
            req.Proxy = null;                 // never through a proxy
            req.AllowAutoRedirect = false;    // never somewhere else
            req.Timeout = timeoutMs;
            req.ReadWriteTimeout = timeoutMs;
            req.KeepAlive = false;
            return req;
        }

        // Installed model names (GET /api/tags), or null when Ollama doesn't answer.
        public List<string> ListModels()
        {
            try
            {
                var req = Request("/api/tags", "GET", TagsTimeoutMs);
                using (var resp = (HttpWebResponse)req.GetResponse())
                using (var r = new StreamReader(resp.GetResponseStream(), Encoding.UTF8))
                    return ParseTags(r.ReadToEnd());
            }
            catch (Exception ex)
            {
                Log.Info("Local model: no answer from " + Base + " (" + ex.Message + ")");
                return null;
            }
        }

        public static List<string> ParseTags(string json)
        {
            var names = new List<string>();
            var d = MiniJson.Parse(json) as Dictionary<string, object>;
            if (d == null) return names;
            object m;
            var list = d.TryGetValue("models", out m) ? m as List<object> : null;
            if (list == null) return names;
            foreach (var o in list)
            {
                var md = o as Dictionary<string, object>;
                if (md == null) continue;
                string n = MiniJson.GetString(md, "name", null) ?? MiniJson.GetString(md, "model", null);
                if (!string.IsNullOrEmpty(n) && !names.Contains(n)) names.Add(n);
            }
            names.Sort(StringComparer.OrdinalIgnoreCase);
            return names;
        }

        public static string GenerateBody(string model, string prompt)
        {
            return "{\"model\":\"" + MiniJson.Escape(model) + "\",\"prompt\":\"" + MiniJson.Escape(prompt) +
                   "\",\"stream\":true,\"options\":{\"temperature\":0.2}}";
        }

        // One line of the /api/generate stream. Returns false for a line that isn't JSON.
        public static bool ParseStreamLine(string line, out string token, out bool done, out string error)
        {
            token = null; done = false; error = null;
            if (string.IsNullOrEmpty(line) || line.Trim().Length == 0) return false;
            Dictionary<string, object> d;
            try { d = MiniJson.Parse(line) as Dictionary<string, object>; }
            catch { return false; }
            if (d == null) return false;
            token = MiniJson.GetString(d, "response", null);
            done = MiniJson.GetBool(d, "done", false);
            error = MiniJson.GetString(d, "error", null);
            return true;
        }

        // Streams the answer (POST /api/generate, stream:true): onToken gets each piece on this thread. Returns the
        // whole text, or null with error set (no answer, an error from Ollama, cancelled, or past the timeout).
        public string Generate(string model, string prompt, Action<string> onToken, Func<bool> cancelled, out string error)
        {
            error = null;
            var sw = Stopwatch.StartNew();
            var all = new StringBuilder();
            try
            {
                var req = Request("/api/generate", "POST", TimeoutMs);
                req.ContentType = "application/json";
                byte[] body = Encoding.UTF8.GetBytes(GenerateBody(model, prompt));
                req.ContentLength = body.Length;
                using (var rs = req.GetRequestStream()) rs.Write(body, 0, body.Length);
                using (var resp = (HttpWebResponse)req.GetResponse())
                using (var r = new StreamReader(resp.GetResponseStream(), Encoding.UTF8))
                {
                    string line;
                    while ((line = r.ReadLine()) != null)
                    {
                        if (sw.ElapsedMilliseconds > TimeoutMs) { error = "The local model took longer than " + (TimeoutMs / 1000) + " s."; return null; }
                        if (cancelled != null && cancelled()) { error = "cancelled"; return null; }
                        string tok, err;
                        bool done;
                        if (!ParseStreamLine(line, out tok, out done, out err)) continue;
                        if (err != null) { error = "The local model said: " + err; return null; }
                        if (!string.IsNullOrEmpty(tok))
                        {
                            all.Append(tok);
                            if (all.Length > MaxResponseChars) { error = "The local model's answer was too long."; return null; }
                            if (onToken != null) onToken(tok);
                        }
                        if (done) return all.ToString();
                    }
                }
                error = "The local model stopped before finishing.";
                return null;
            }
            catch (WebException ex)
            {
                error = ex.Status == WebExceptionStatus.Timeout ? "The local model took longer than " + (TimeoutMs / 1000) + " s." : "The local model didn't answer (" + ex.Message + ").";
                return null;
            }
            catch (IOException ex)
            {
                error = sw.ElapsedMilliseconds >= TimeoutMs ? "The local model took longer than " + (TimeoutMs / 1000) + " s." : "The local model didn't answer (" + ex.Message + ").";
                return null;
            }
            catch (Exception ex)
            {
                error = "The local model didn't answer (" + ex.Message + ").";
                return null;
            }
        }

        // True when the model replied that the help doesn't cover the question.
        public static bool SaysNotInHelp(string answer)
        {
            if (answer == null) return true;
            string a = answer.Trim().TrimEnd('.', '!').Trim().ToLowerInvariant();
            return a.Length == 0 || a == LocalModelPrompt.NotInHelp || (a.Length < 40 && a.Contains(LocalModelPrompt.NotInHelp));
        }
    }
}
