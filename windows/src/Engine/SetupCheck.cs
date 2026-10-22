// Dual Earbuds - "Setup check": the things that make sound and sync better, each with a status and a fix.
// Pure logic (snapshot in, rows out) plus the Wi-Fi reader; the page only draws the rows.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.Threading;

namespace DualEarbuds
{
    // What `netsh wlan show interfaces` says about the Wi-Fi in use.
    public class WifiInfo
    {
        public bool Connected;
        public string Ssid;
        public string RadioType;     // "802.11ac", ...
        public int Channel;          // 0 = unknown
        public string Band;          // "2.4 GHz" | "5 GHz" | "6 GHz" | null
        public int SignalPercent = -1;

        public bool Is24 { get { return Band == "2.4 GHz"; } }
        public bool IsFast { get { return Band == "5 GHz" || Band == "6 GHz"; } }

        // Parses the output defensively: Windows translates the labels, so values are recognised by their
        // shape where possible (802.11xx, NN%, "5 GHz"), and only channel/SSID/state need a known label.
        public static WifiInfo Parse(string output)
        {
            var w = new WifiInfo();
            if (string.IsNullOrEmpty(output)) return w;
            bool sawState = false, connected = false, anyField = false;
            string bandLine = null;
            foreach (var raw in output.Replace("\r", "").Split('\n'))
            {
                string line = raw.Trim();
                int sep = line.IndexOf(" : ", StringComparison.Ordinal);
                int skip = 3;
                if (sep < 0) { sep = line.IndexOf(": ", StringComparison.Ordinal); skip = 2; }
                if (sep <= 0) continue;
                string key = line.Substring(0, sep).Trim().ToLowerInvariant();
                string val = line.Substring(sep + skip).Trim();
                // a second interface starts with another "Name": keep the first connected one
                if ((key == "name" || key == "nom" || key == "nombre") && anyField)
                {
                    if (sawState && connected) break;
                    w = new WifiInfo(); sawState = false; connected = false; bandLine = null;
                }
                anyField = true;
                string lv = val.ToLowerInvariant();
                if (key == "state" || key == "\u00e9tat" || key == "etat" || key == "estado" || key == "zustand" || key == "stato")
                {
                    sawState = true;
                    connected = lv.StartsWith("connected") || lv.StartsWith("connect\u00e9") || lv.StartsWith("conectado") || lv.StartsWith("verbunden") || lv.StartsWith("connesso");
                }
                else if (key == "ssid") w.Ssid = val;
                else if (lv.StartsWith("802.11")) w.RadioType = val.Split(' ')[0];
                else if (key.Contains("channel") || key.Contains("kanal") || key.Contains("canal") || key.Contains("canale"))
                {
                    int ch;
                    if (int.TryParse(val.Split(' ')[0], NumberStyles.Integer, CultureInfo.InvariantCulture, out ch) && ch > 0 && ch < 256) w.Channel = ch;
                }
                else if ((key == "band" || key == "bande" || key == "banda") || (lv.EndsWith("ghz") && lv.Length <= 8)) bandLine = val;
                else if (val.EndsWith("%") && (key.Contains("signal") || key.Contains("se\u00f1al") || key.Contains("segnale") || val.Length <= 4))
                {
                    int sig;
                    if (int.TryParse(val.TrimEnd('%').Trim(), NumberStyles.Integer, CultureInfo.InvariantCulture, out sig) && sig >= 0 && sig <= 100) w.SignalPercent = sig;
                }
            }
            w.Connected = sawState ? connected : (w.Channel > 0 || w.RadioType != null);
            w.Band = BandFrom(bandLine, w.Channel, w.RadioType);
            return w;
        }

        public static string BandFrom(string bandLine, int channel, string radio)
        {
            if (bandLine != null)
            {
                string b = bandLine.Replace(',', '.').Replace(" ", "");
                if (b.StartsWith("2.4")) return "2.4 GHz";
                if (b.StartsWith("5")) return "5 GHz";
                if (b.StartsWith("6")) return "6 GHz";
            }
            string r = (radio ?? "").ToLowerInvariant();
            if (r == "802.11b" || r == "802.11g") return "2.4 GHz";
            if (r == "802.11a" || r == "802.11ac") return "5 GHz";
            if (channel >= 1 && channel <= 14) return "2.4 GHz";
            if (channel >= 32 && channel <= 177) return "5 GHz";
            return null;
        }
    }

    public static class WifiReader
    {
        static volatile WifiInfo last;
        static DateTime lastRun = DateTime.MinValue;
        static int busy;

        public static WifiInfo Last { get { return last; } }

        // Background refresh at most every 30 s (netsh takes ~100 ms).
        public static void Refresh()
        {
            if ((DateTime.UtcNow - lastRun).TotalSeconds < 30) return;
            if (Interlocked.Exchange(ref busy, 1) == 1) return;
            lastRun = DateTime.UtcNow;
            var t = new Thread(() =>
            {
                try { last = WifiInfo.Parse(Run()); }
                catch (Exception ex) { Log.Info("Wi-Fi info: " + ex.Message); }
                finally { Interlocked.Exchange(ref busy, 0); }
            }) { IsBackground = true, Name = "WifiInfo" };
            t.Start();
        }

        static string Run()
        {
            var psi = new ProcessStartInfo("netsh.exe", "wlan show interfaces")
            {
                UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true,
                WindowStyle = ProcessWindowStyle.Hidden
            };
            using (var p = Process.Start(psi))
            {
                string o = p.StandardOutput.ReadToEnd();
                if (!p.WaitForExit(8000)) { try { p.Kill(); } catch { } }
                return o;
            }
        }
    }

    public enum CheckStatus { Good, Warn, Bad, Unknown, Info }

    public class CheckItem
    {
        public string Id;
        public string Title;
        public CheckStatus Status;
        public string Value;      // short mono readout ("5 GHz · ch 44", "182 ms")
        public string Detail;     // one plain sentence
        public string Action;     // button label, or null
        public string ActionId;   // what the button does: "firewall", "network", "bluetooth", "calibrate:<id>", "power", "battery", "wifi"
    }

    public class CheckOutput { public string Id, Name; public bool Bluetooth, Enabled, Calibrated; public double LatencyMs; }
    public class CheckListener { public string Name, Client; public int NeedMs; public bool Synced; public string Transport; }

    public class CheckInput
    {
        public bool HostMode = true;          // sharing this laptop (false: listening to another device)
        public string NetworkCategory;        // Public | Private | DomainAuthenticated | null
        public string NetworkScope;           // Settings.NetScope*
        public FirewallStatus Firewall;       // null = not checked yet
        public List<CheckOutput> Outputs = new List<CheckOutput>();
        public PowerState Power;              // null = unknown
        public WifiInfo Wifi;                 // null = not read yet
        public bool Wired;                    // on Ethernet (no Wi-Fi row needed)
        public List<CheckListener> Listeners = new List<CheckListener>();
        public int PlayoutMs;
    }

    public static class SetupCheck
    {
        public const int PoorNeedMs = 180;    // a listener needing more than this has poor Wi-Fi

        public static List<CheckItem> Build(CheckInput x)
        {
            var list = new List<CheckItem>();

            // network type
            var net = new CheckItem { Id = "network", Title = "Network type" };
            if (x.NetworkCategory == "Private" || x.NetworkCategory == "DomainAuthenticated")
            { net.Status = CheckStatus.Good; net.Value = x.NetworkCategory == "Private" ? "Private" : "Domain"; net.Detail = "Devices on this network can find and reach each other."; }
            else if (x.NetworkCategory == "Public")
            {
                net.Status = CheckStatus.Warn; net.Value = "Public";
                net.Detail = "Windows treats this network as public: other devices don't find this laptop, and some Wi-Fi keeps devices apart. Mark it Private if it is yours, or use a hotspot.";
                net.Action = "Network settings"; net.ActionId = "network";
            }
            else { net.Status = CheckStatus.Unknown; net.Value = "-"; net.Detail = "Checking which kind of network this is."; }
            list.Add(net);

            // firewall
            var fw = new CheckItem { Id = "firewall", Title = "Firewall rule" };
            if (x.Firewall == null || !x.Firewall.Known) { fw.Status = CheckStatus.Unknown; fw.Value = "-"; fw.Detail = "Reading the Windows Firewall rule."; }
            else if (x.Firewall.Complete)
            {
                bool needPublic = x.NetworkCategory == "Public";
                bool coversPublic = (x.Firewall.Profiles & Firewall.ProfilePublic) != 0;
                fw.Status = needPublic && !coversPublic ? CheckStatus.Warn : CheckStatus.Good;
                fw.Value = "TCP + UDP · " + (coversPublic ? "private + public" : "private");
                fw.Detail = needPublic && !coversPublic ? "The rule covers private networks only, and this one is public. Allow public networks too, or mark this network Private." : "Listeners can connect, and get audio over UDP.";
                if (fw.Status != CheckStatus.Good) { fw.Action = "Allow on public networks"; fw.ActionId = "firewall"; }
            }
            else if (x.Firewall.Tcp)
            {
                fw.Status = CheckStatus.Warn; fw.Value = "TCP only";
                fw.Detail = "Made by an older version: listeners connect, but audio falls back to the slower WebSocket path. Update the rule (Windows asks for permission).";
                fw.Action = "Update rule"; fw.ActionId = "firewall";
            }
            else
            {
                fw.Status = x.HostMode ? CheckStatus.Bad : CheckStatus.Warn; fw.Value = "missing";
                fw.Detail = x.Firewall.OtherApp ? "The rule points at another copy of Dual Earbuds. Re-create it for this one (Windows asks for permission)."
                                               : "Windows may block listeners from reaching this laptop. Add the rule (Windows asks for permission).";
                fw.Action = "Add rule"; fw.ActionId = "firewall";
            }
            list.Add(fw);

            // Wi-Fi band
            var wifi = new CheckItem { Id = "wifi", Title = "Wi-Fi band" };
            if (x.Wired) { wifi.Status = CheckStatus.Good; wifi.Value = "Ethernet"; wifi.Detail = "This laptop is on a cable: the best case."; }
            else if (x.Wifi == null) { wifi.Status = CheckStatus.Unknown; wifi.Value = "-"; wifi.Detail = "Reading the Wi-Fi connection."; }
            else if (!x.Wifi.Connected) { wifi.Status = CheckStatus.Unknown; wifi.Value = "not on Wi-Fi"; wifi.Detail = "This laptop isn't connected to Wi-Fi."; }
            else
            {
                string ch = x.Wifi.Channel > 0 ? " · ch " + x.Wifi.Channel.ToString(CultureInfo.InvariantCulture) : "";
                string sig = x.Wifi.SignalPercent >= 0 ? " · " + x.Wifi.SignalPercent.ToString(CultureInfo.InvariantCulture) + "%" : "";
                wifi.Value = (x.Wifi.Band ?? "band unknown") + ch + sig;
                if (x.Wifi.IsFast)
                {
                    wifi.Status = x.Wifi.SignalPercent >= 0 && x.Wifi.SignalPercent < 50 ? CheckStatus.Warn : CheckStatus.Good;
                    wifi.Detail = wifi.Status == CheckStatus.Good ? "Fast band, little interference." : "Weak signal: move closer to the router, or use this laptop's hotspot.";
                }
                else if (x.Wifi.Is24)
                {
                    wifi.Status = CheckStatus.Warn;
                    wifi.Detail = "2.4 GHz is crowded and Bluetooth earbuds share it. Join the router's 5 GHz network if it has one, or share from a 5 GHz hotspot.";
                    wifi.Action = "Wi-Fi settings"; wifi.ActionId = "wifi";
                }
                else { wifi.Status = CheckStatus.Unknown; wifi.Detail = "Windows didn't say which band this is."; }
            }
            list.Add(wifi);

            // Bluetooth earbuds
            int bt = 0;
            foreach (var o in x.Outputs) if (o.Bluetooth) bt++;
            var bte = new CheckItem { Id = "bluetooth", Title = "Bluetooth earbuds" };
            bte.Value = bt.ToString(CultureInfo.InvariantCulture) + " connected";
            if (bt >= 1) { bte.Status = CheckStatus.Good; bte.Detail = bt == 1 ? "One pair on this laptop. A second pair can listen through a phone or another laptop." : "Each pair plays on its own output."; }
            else { bte.Status = CheckStatus.Warn; bte.Detail = "No Bluetooth earbuds on this laptop. Pair them in Windows settings; they appear by themselves."; bte.Action = "Bluetooth settings"; bte.ActionId = "bluetooth"; }
            list.Add(bte);

            // calibration per ticked output
            foreach (var o in x.Outputs)
            {
                if (!o.Enabled) continue;
                var c = new CheckItem { Id = "cal:" + o.Id, Title = "Calibrated: " + o.Name };
                if (o.Calibrated) { c.Status = CheckStatus.Good; c.Value = Math.Round(o.LatencyMs).ToString(CultureInfo.InvariantCulture) + " ms"; c.Detail = "Measured with the microphone: this pair plays on the shared deadline."; c.Action = "Measure again"; c.ActionId = "calibrate:" + o.Id; }
                else
                {
                    c.Status = o.Bluetooth ? CheckStatus.Warn : CheckStatus.Info;
                    c.Value = "~" + Math.Round(o.LatencyMs).ToString(CultureInfo.InvariantCulture) + " ms";
                    c.Detail = o.Bluetooth ? "Estimated. Bluetooth delay differs per model by up to 100 ms: calibrate so this pair lines up with the others."
                                           : "Estimated from Windows. Wired outputs are usually close; calibrate for exact sync.";
                    c.Action = "Calibrate"; c.ActionId = "calibrate:" + o.Id;
                }
                list.Add(c);
            }

            // power
            var bs = new CheckItem { Id = "battery", Title = "Battery saver" };
            var pw = new CheckItem { Id = "power", Title = "Power mode" };
            if (x.Power == null) { bs.Status = pw.Status = CheckStatus.Unknown; bs.Value = pw.Value = "-"; bs.Detail = pw.Detail = "Reading the power settings."; }
            else
            {
                bs.Value = x.Power.BatterySaver ? "on" : "off";
                if (x.Power.BatterySaver) { bs.Status = CheckStatus.Warn; bs.Detail = "Battery saver slows Wi-Fi and timers. Dual Earbuds opts out where it can; plug in, or turn it off while sharing."; bs.Action = "Battery settings"; bs.ActionId = "battery"; }
                else { bs.Status = CheckStatus.Good; bs.Detail = x.Power.OnBattery ? "Off. On battery, keep an eye on it." : "Off."; }
                pw.Value = Power.Describe(x.Power.Mode);
                if (x.Power.Mode == PowerMode.BestEfficiency) { pw.Status = CheckStatus.Warn; pw.Detail = "Best power efficiency can delay audio threads. Balanced or Best performance is smoother."; pw.Action = "Power settings"; pw.ActionId = "power"; }
                else if (x.Power.Mode == PowerMode.Unknown) { pw.Status = CheckStatus.Info; pw.Value = "-"; pw.Detail = "Windows didn't report a power mode."; }
                else { pw.Status = CheckStatus.Good; pw.Detail = "Good for real-time audio."; }
            }
            list.Add(bs);
            list.Add(pw);

            // listeners on poor Wi-Fi
            if (x.HostMode)
            {
                var poor = new List<string>();
                foreach (var l in x.Listeners) if (l.NeedMs > PoorNeedMs) poor.Add(l.Name + " " + l.NeedMs.ToString(CultureInfo.InvariantCulture) + " ms");
                var li = new CheckItem { Id = "listeners", Title = "Listeners' Wi-Fi" };
                if (x.Listeners.Count == 0) { li.Status = CheckStatus.Info; li.Value = "none"; li.Detail = "No one is listening yet."; }
                else if (poor.Count == 0) { li.Status = CheckStatus.Good; li.Value = x.Listeners.Count.ToString(CultureInfo.InvariantCulture) + " ok"; li.Detail = "Every listener's network keeps up with the shared deadline (" + x.PlayoutMs.ToString(CultureInfo.InvariantCulture) + " ms)."; }
                else
                {
                    li.Status = CheckStatus.Warn; li.Value = poor.Count.ToString(CultureInfo.InvariantCulture) + " slow";
                    li.Detail = string.Join(", ", poor.ToArray()) + ": everyone waits for the slowest device. Bring it closer to the router, or use a 5 GHz hotspot.";
                }
                list.Add(li);
            }
            return list;
        }

        // Worst status first counts the rows that want attention (for the nav badge / header).
        public static int Attention(List<CheckItem> items)
        {
            int n = 0;
            foreach (var i in items) if (i.Status == CheckStatus.Warn || i.Status == CheckStatus.Bad) n++;
            return n;
        }

        public static readonly string[] BestSoundGuide =
        {
            "Put every device on the same Wi-Fi, or on this laptop's hotspot (Settings > Network > Mobile hotspot).",
            "Use 5 GHz Wi-Fi. 2.4 GHz shares the air with every pair of Bluetooth earbuds.",
            "Calibrate each pair once: hold one earbud to the laptop microphone and press Calibrate.",
            "Keep phones and laptops in one room, near the router or hotspot.",
            "On Android phones, pick AAC or SBC for the earbuds (Developer options > Bluetooth audio codec). LDAC adds 100-200 ms of delay.",
        };
    }
}
