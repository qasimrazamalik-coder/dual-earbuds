// Dual Earbuds Pro - update check and download (GitHub releases, see "Updates" in docs/protocol.md).
// Everything here blocks on the network: call it from a background thread, never the UI thread.
using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Net;
using System.Text;

namespace DualEarbuds
{
    public class ReleaseInfo
    {
        public string Tag;          // e.g. "v3.1.0"
        public string Version;      // normalized "3.1.0"
        public string Notes = "";   // release body (What's new)
        public string PageUrl;      // html_url
        public string SetupUrl;     // browser_download_url of DualEarbuds-Setup.exe, or null
        public long SetupSize;      // bytes, 0 when unknown
    }

    public enum UpdateStatus { UpToDate, Available, NoRelease, Offline, Error }

    public class UpdateCheckResult
    {
        public UpdateStatus Status;
        public ReleaseInfo Release;
        public string Message;
        public DateTime CheckedUtc;
    }

    public static class Updater
    {
        public const string Repo = "qasimrazamalik-coder/dual-earbuds";
        public const string LatestUrl = "https://api.github.com/repos/" + Repo + "/releases/latest";
        public const string AssetName = "DualEarbuds-Setup.exe";
        public const long MinInstallerBytes = 100 * 1024;
        public const long MaxInstallerBytes = 200L * 1024 * 1024;
        const int MaxJsonBytes = 2 * 1024 * 1024;
        const int TimeoutMs = 20000;
        public static readonly string[] AllowedDownloadHosts = { "github.com", "objects.githubusercontent.com" };
        public static readonly TimeSpan AutoCheckInterval = TimeSpan.FromHours(24);

        public static string UserAgent { get { return "DualEarbuds/" + AppInfo.Version + " (Windows)"; } }

        // ------------------------------------------------------------------ versions
        // "v3.0.10", "3.1", "v3.0.0-beta" -> {3,0,10}, {3,1,0}, {3,0,0}. Null when it isn't a version.
        public static int[] ParseVersion(string s)
        {
            if (s == null) return null;
            s = s.Trim();
            if (s.StartsWith("v") || s.StartsWith("V")) s = s.Substring(1);
            int cut = s.IndexOfAny(new[] { '-', '+', ' ' });
            if (cut >= 0) s = s.Substring(0, cut);
            var parts = s.Split('.');
            if (parts.Length < 1 || parts.Length > 3) return null;
            var v = new int[3];
            for (int i = 0; i < parts.Length; i++)
            {
                int n;
                if (parts[i].Length == 0 || parts[i].Length > 6) return null;
                foreach (char c in parts[i]) if (c < '0' || c > '9') return null;
                if (!int.TryParse(parts[i], NumberStyles.None, CultureInfo.InvariantCulture, out n)) return null;
                v[i] = n;
            }
            return v;
        }

        // <0 when a is older than b, 0 when equal, >0 when newer. An unreadable version counts as oldest.
        public static int CompareVersions(string a, string b)
        {
            int[] x = ParseVersion(a), y = ParseVersion(b);
            if (x == null || y == null) return (x == null ? 0 : 1) - (y == null ? 0 : 1);
            for (int i = 0; i < 3; i++) if (x[i] != y[i]) return x[i] < y[i] ? -1 : 1;
            return 0;
        }

        public static bool IsNewer(string candidate, string current)
        {
            return ParseVersion(candidate) != null && CompareVersions(candidate, current) > 0;
        }

        public static string Normalize(int[] v)
        {
            return v[0].ToString(CultureInfo.InvariantCulture) + "." + v[1].ToString(CultureInfo.InvariantCulture) + "." + v[2].ToString(CultureInfo.InvariantCulture);
        }

        public static bool ShouldAutoCheck(bool enabled, DateTime lastUtc, DateTime nowUtc)
        {
            if (!enabled) return false;
            if (lastUtc > nowUtc) return true;   // clock went backwards: don't wait for it to catch up
            return nowUtc - lastUtc >= AutoCheckInterval;
        }

        // ------------------------------------------------------------------ release JSON
        public static ReleaseInfo ParseRelease(string json)
        {
            var root = MiniJson.Parse(json) as Dictionary<string, object>;
            if (root == null) throw new FormatException("release is not an object");
            string tag = MiniJson.GetString(root, "tag_name", null);
            int[] v = ParseVersion(tag);
            if (v == null) throw new FormatException("release tag is not a version: " + (tag ?? "(none)"));
            var r = new ReleaseInfo
            {
                Tag = tag,
                Version = Normalize(v),
                Notes = (MiniJson.GetString(root, "body", null) ?? "").Replace("\r\n", "\n").Trim(),
                PageUrl = MiniJson.GetString(root, "html_url", null),
            };
            object assets;
            if (root.TryGetValue("assets", out assets) && assets is List<object>)
            {
                foreach (var a in (List<object>)assets)
                {
                    var d = a as Dictionary<string, object>;
                    if (d == null || MiniJson.GetString(d, "name", null) != AssetName) continue;
                    string url = MiniJson.GetString(d, "browser_download_url", null);
                    if (!IsAllowedDownloadUrl(url)) continue;
                    r.SetupUrl = url;
                    object size;
                    if (d.TryGetValue("size", out size) && size is double && (double)size > 0 && (double)size < MaxInstallerBytes) r.SetupSize = (long)(double)size;
                    break;
                }
            }
            return r;
        }

        public static UpdateCheckResult Evaluate(ReleaseInfo r, string currentVersion)
        {
            var res = new UpdateCheckResult { Release = r, CheckedUtc = DateTime.UtcNow };
            if (!IsNewer(r.Version, currentVersion))
            {
                res.Status = UpdateStatus.UpToDate;
                res.Message = "You have the latest version.";
            }
            else if (r.SetupUrl == null)
            {
                res.Status = UpdateStatus.Error;
                res.Message = "Version " + r.Version + " is out, but its Windows installer isn't attached yet. Try again later.";
            }
            else
            {
                res.Status = UpdateStatus.Available;
                res.Message = "Version " + r.Version + " is available.";
            }
            return res;
        }

        // Release notes are Markdown; show them as plain text: headings without '#', bullets as '•', no '**'.
        public const int MaxNotesChars = 4000;
        public static string NotesForDisplay(string md)
        {
            if (string.IsNullOrEmpty(md)) return "";
            var sb = new StringBuilder();
            foreach (var raw in md.Replace("\r\n", "\n").Split('\n'))
            {
                string line = raw.TrimEnd().Replace("**", "").Replace("__", "").Replace("`", "");
                string t = line.TrimStart();
                if (t.StartsWith("#")) line = t.TrimStart('#').Trim();
                else if (t.StartsWith("- ") || t.StartsWith("* ") || t.StartsWith("+ ")) line = "\u2022 " + t.Substring(2).Trim();
                if (line.Length == 0 && (sb.Length == 0 || sb.ToString().EndsWith("\n\n"))) continue;
                sb.Append(line).Append('\n');
                if (sb.Length > MaxNotesChars) { sb.Length = MaxNotesChars; sb.Append("..."); break; }
            }
            return sb.ToString().Trim();
        }

        // Only HTTPS to GitHub's own download hosts.
        public static bool IsAllowedDownloadUrl(string url)
        {
            Uri u;
            if (string.IsNullOrEmpty(url) || !Uri.TryCreate(url, UriKind.Absolute, out u)) return false;
            if (u.Scheme != Uri.UriSchemeHttps || !u.IsDefaultPort || !string.IsNullOrEmpty(u.UserInfo)) return false;
            string host = u.Host.ToLowerInvariant();
            foreach (var h in AllowedDownloadHosts) if (host == h) return true;
            return false;
        }

        // A Windows program starts with "MZ"; anything tiny is an error page, not our installer.
        public static bool LooksLikeInstaller(byte[] head, long size)
        {
            return head != null && head.Length >= 2 && head[0] == (byte)'M' && head[1] == (byte)'Z' && size > MinInstallerBytes && size <= MaxInstallerBytes;
        }

        // ------------------------------------------------------------------ network
        static bool tlsReady;

        static void EnsureTls()
        {
            if (tlsReady) return;
            try { ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12; } catch { }
            tlsReady = true;
        }

        static HttpWebRequest NewRequest(Uri u)
        {
            var req = (HttpWebRequest)WebRequest.Create(u);
            req.Method = "GET";
            req.UserAgent = UserAgent;
            req.Timeout = TimeoutMs;
            req.ReadWriteTimeout = TimeoutMs;
            req.AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate;
            return req;
        }

        // Never throws: offline, missing repo, 404 and rate limits all come back as a calm message.
        public static UpdateCheckResult Check()
        {
            try
            {
                EnsureTls();
                var req = NewRequest(new Uri(LatestUrl));
                req.Accept = "application/vnd.github+json";
                string json;
                using (var resp = (HttpWebResponse)req.GetResponse())
                using (var s = resp.GetResponseStream())
                    json = ReadText(s, MaxJsonBytes);
                return Evaluate(ParseRelease(json), AppInfo.Version);
            }
            catch (WebException ex)
            {
                var hr = ex.Response as HttpWebResponse;
                int code = hr == null ? 0 : (int)hr.StatusCode;
                if (hr != null) try { hr.Close(); } catch { }
                Log.Info("Update check: " + ex.Status + " " + code);
                if (code == 404) return Result(UpdateStatus.NoRelease, "No updates have been published yet.");
                if (code == 403 || code == 429) return Result(UpdateStatus.Error, "GitHub is busy right now. Try again in a while.");
                if (code == 0) return Result(UpdateStatus.Offline, "Couldn't reach GitHub. Check your internet connection and try again.");
                return Result(UpdateStatus.Error, "GitHub didn't answer properly (" + code + "). Try again later.");
            }
            catch (FormatException ex)
            {
                Log.Warn("Update check: " + ex.Message);
                return Result(UpdateStatus.Error, "The release information couldn't be read. Try again later.");
            }
            catch (Exception ex)
            {
                Log.Warn("Update check: " + ex.Message);
                return Result(UpdateStatus.Error, "Couldn't check for updates right now. Try again later.");
            }
        }

        static UpdateCheckResult Result(UpdateStatus st, string msg)
        {
            return new UpdateCheckResult { Status = st, Message = msg, CheckedUtc = DateTime.UtcNow };
        }

        static string ReadText(Stream s, int max)
        {
            var ms = new MemoryStream();
            var buf = new byte[16384];
            int n;
            while ((n = s.Read(buf, 0, buf.Length)) > 0)
            {
                if (ms.Length + n > max) throw new FormatException("response too large");
                ms.Write(buf, 0, n);
            }
            return Encoding.UTF8.GetString(ms.ToArray());
        }

        public static string DownloadFolder { get { return Path.Combine(Path.GetTempPath(), "DualEarbuds-Update"); } }

        // Downloads the installer to %TEMP%. percent gets 0-100 (or -1 when the size is unknown).
        // Redirects are followed by hand so every hop is checked against the allowed hosts.
        // Throws with a message that can be shown to the user.
        public static string Download(ReleaseInfo r, Action<int> percent)
        {
            if (r == null || !IsAllowedDownloadUrl(r.SetupUrl)) throw new InvalidOperationException("This update can't be downloaded safely.");
            EnsureTls();
            Directory.CreateDirectory(DownloadFolder);
            string path = Path.Combine(DownloadFolder, "DualEarbuds-Setup-" + Normalize(ParseVersion(r.Version)) + ".exe");
            string part = path + ".part";
            try
            {
                if (File.Exists(part)) File.Delete(part);
                var u = new Uri(r.SetupUrl);
                for (int hop = 0; ; hop++)
                {
                    if (hop > 5) throw new IOException("Too many redirects.");
                    var req = NewRequest(u);
                    req.AllowAutoRedirect = false;
                    req.Accept = "application/octet-stream";
                    using (var resp = (HttpWebResponse)req.GetResponse())
                    {
                        int code = (int)resp.StatusCode;
                        if (code >= 300 && code < 400)
                        {
                            string loc = resp.Headers["Location"];
                            Uri next;
                            if (string.IsNullOrEmpty(loc) || !Uri.TryCreate(u, loc, out next) || !IsAllowedDownloadUrl(next.AbsoluteUri))
                                throw new IOException("The download was redirected to a place Dual Earbuds doesn't trust.");
                            u = next;
                            continue;
                        }
                        if (code != 200) throw new IOException("The download failed (" + code + ").");
                        long total = r.SetupSize > 0 ? r.SetupSize : resp.ContentLength;
                        if (total > MaxInstallerBytes) throw new IOException("The download is too large.");
                        using (var s = resp.GetResponseStream())
                        using (var fs = File.Create(part))
                        {
                            var buf = new byte[65536];
                            long got = 0;
                            int n, last = -2;
                            while ((n = s.Read(buf, 0, buf.Length)) > 0)
                            {
                                got += n;
                                if (got > MaxInstallerBytes) throw new IOException("The download is too large.");
                                fs.Write(buf, 0, n);
                                int pc = total > 0 ? (int)Math.Min(100, got * 100 / total) : -1;
                                if (pc != last && percent != null) { last = pc; percent(pc); }
                            }
                        }
                        break;
                    }
                }

                long size = new FileInfo(part).Length;
                var head = new byte[2];
                using (var fs = File.OpenRead(part)) fs.Read(head, 0, 2);
                if (!LooksLikeInstaller(head, size) || (r.SetupSize > 0 && size != r.SetupSize))
                    throw new InvalidDataException("The downloaded file isn't a valid installer. Try again later.");
                if (File.Exists(path)) File.Delete(path);
                File.Move(part, path);
                return path;
            }
            catch (WebException ex)
            {
                TryDelete(part);
                Log.Warn("Update download: " + ex.Message);
                throw new IOException("The download didn't finish. Check your internet connection and try again.", ex);
            }
            catch
            {
                TryDelete(part);
                throw;
            }
        }

        static void TryDelete(string f) { try { if (File.Exists(f)) File.Delete(f); } catch { } }

        // Runs the downloaded installer in update mode. The caller exits the app right after.
        public static bool StartInstaller(string path)
        {
            try
            {
                var psi = new System.Diagnostics.ProcessStartInfo(path, "--update") { UseShellExecute = true, WorkingDirectory = Path.GetDirectoryName(path) };
                System.Diagnostics.Process.Start(psi);
                return true;
            }
            catch (Exception ex)
            {
                Log.Warn("Starting the installer: " + ex.Message);
                return false;
            }
        }
    }
}
