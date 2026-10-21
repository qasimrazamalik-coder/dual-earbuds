// Dual Earbuds - diagnostics report (what the user can send when something doesn't work).
using System;
using System.Globalization;
using System.Text;
using System.Threading;

namespace DualEarbuds
{
    public static class Diagnostics
    {
        // Tries to open (not play) every output and reports what Windows says. Silent: nothing is played.
        public static string BuildReport(Engine engine, PhoneLink phone, Settings settings)
        {
            var sb = new StringBuilder();
            sb.AppendLine(AppInfo.Name + " " + AppInfo.Version + " diagnostics");
            sb.AppendLine("Time: " + DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss", CultureInfo.InvariantCulture));
            sb.AppendLine("Windows: " + OsInfo.Describe());
            sb.AppendLine();

            sb.AppendLine("== Outputs ==");
            try
            {
                string def;
                var list = Wasapi.ListOutputs(out def);
                foreach (var d in list)
                {
                    string probe = ProbeRender(d.Id);
                    int bat = BatteryReader.ForContainer(d.ContainerId);
                    sb.AppendLine(string.Format("{0}{1}  |  open: {2}  |  battery: {3}",
                        d.Name, d.Id == def ? " (default)" : "", probe, bat >= 0 ? bat + "%" : "not reported"));
                }
            }
            catch (Exception ex) { sb.AppendLine("Could not list outputs: " + ex.Message); }
            sb.AppendLine();

            sb.AppendLine("== Mirroring ==");
            sb.AppendLine("Running: " + engine.Running + (engine.Running ? ", rate " + engine.Rate : ""));
            if (engine.CaptureError != null) sb.AppendLine("Source error: " + engine.CaptureError);
            sb.AppendLine();

            sb.AppendLine("== Phone link ==");
            sb.AppendLine("Network: " + (NetworkInfo.Category ?? "unknown") + (NetworkInfo.IsPublic ? " (public - if phones can't connect, use a hotspot)" : ""));
            if (phone == null) sb.AppendLine("Off" + (settings.PhoneLink ? " (failed to start)" : ""));
            else
            {
                sb.AppendLine("Port " + phone.Port + (phone.Error != null ? " error: " + phone.Error : "") + " · protocol " + AppInfo.Protocol +
                              " · party " + (phone.Party ? "on" : "off") + " (max " + phone.Capacity + ") · firewall networks: " + settings.NetworkScope);
                sb.AppendLine("Addresses: " + string.Join(", ", PhoneLink.LanAddresses().ToArray()));
                foreach (var p in phone.Snapshot())
                    sb.AppendLine(string.Format("  #{0} {1} ({2}, {3}) earbuds='{4}' battery={5} volume={6}% rtt={7}ms buffer={8}ms underruns={9} sent={10} dropped={11}",
                        p.Id, p.Name, p.Client, p.Remote, p.Earbuds, p.Battery, p.Volume, p.RttMs, p.BufferMs, p.Underruns, p.PacketsSent, p.PacketsDropped));
            }
            sb.AppendLine();

            sb.AppendLine("== Recent log ==");
            sb.Append(Log.Tail(200));
            return DiagReport.Scrub(sb.ToString(), DiagReport.Secrets(settings));
        }

        static string ProbeRender(string id)
        {
            string result = "?";
            var t = new Thread(() =>
            {
                IMMDeviceEnumerator en = null;
                IAudioClient ac = null;
                IntPtr fmt = IntPtr.Zero;
                try
                {
                    en = Wasapi.NewEnumerator();
                    string rid;
                    ac = Wasapi.OpenClient(en, id, out rid);
                    fmt = Wasapi.MakeFloatStereo(48000);
                    int hr = ac.Initialize(0, Wasapi.FLAG_AUTOCONVERT | Wasapi.FLAG_SRC_QUALITY, 400000, 0, fmt, IntPtr.Zero);
                    result = hr >= 0 ? "OK" : string.Format("refused 0x{0:X8}", hr);
                }
                catch (Exception ex) { result = "error " + ex.Message; }
                finally
                {
                    Wasapi.Release(ac);
                    Wasapi.Release(en);
                    if (fmt != IntPtr.Zero) System.Runtime.InteropServices.Marshal.FreeCoTaskMem(fmt);
                }
            });
            t.IsBackground = true;
            t.Start();
            t.Join(4000);
            return result;
        }
    }

    // "Copy diagnostics" in Settings: a quick text report (no device probing) that never contains the
    // link key, a room PIN or keys of hosts this laptop listened to.
    public static class DiagReport
    {
        public static string[] Secrets(Settings s)
        {
            var l = new System.Collections.Generic.List<string>();
            if (s == null) return l.ToArray();
            if (!string.IsNullOrEmpty(s.LinkKey)) l.Add(s.LinkKey);
            if (!string.IsNullOrEmpty(s.Pin)) l.Add(s.Pin);
            foreach (var r in s.Recent) if (!string.IsNullOrEmpty(r.Key)) l.Add(r.Key);
            return l.ToArray();
        }

        public static string Scrub(string text, string[] secrets)
        {
            if (string.IsNullOrEmpty(text)) return text ?? "";
            string t = System.Text.RegularExpressions.Regex.Replace(text, @"([?&](k|p)=)[^&\s""']+", "$1***", System.Text.RegularExpressions.RegexOptions.IgnoreCase);
            if (secrets != null)
                foreach (var sec in secrets)
                {
                    if (string.IsNullOrEmpty(sec) || sec.Length < 4) continue;
                    t = System.Text.RegularExpressions.Regex.Replace(t, System.Text.RegularExpressions.Regex.Escape(sec), "***", System.Text.RegularExpressions.RegexOptions.IgnoreCase);
                }
            return t;
        }

        public static string Summary(Engine engine, PhoneLink phone, Settings settings, LinkClient listen, System.Collections.Generic.IEnumerable<string> outputs)
        {
            var sb = new StringBuilder();
            sb.AppendLine(AppInfo.Name + " " + AppInfo.Version + " (protocol " + AppInfo.Protocol + ")");
            sb.AppendLine("Time: " + DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss", CultureInfo.InvariantCulture));
            sb.AppendLine("Windows: " + OsInfo.Describe());
            sb.AppendLine("Network: " + (NetworkInfo.Category ?? "unknown") + " · firewall networks: " + settings.NetworkScope);
            sb.AppendLine("One-app sharing supported: " + (AppAudio.Supported ? "yes" : "no"));
            sb.AppendLine();
            sb.AppendLine("== Sharing ==");
            sb.AppendLine("Running: " + engine.Running + (engine.Running ? " · rate " + engine.Rate + " · source " + (engine.SourceKind == CaptureKind.App ? "one app (" + settings.SourceApp + ")" : "whole laptop") : ""));
            if (engine.CaptureError != null) sb.AppendLine("Source error: " + engine.CaptureError);
            sb.AppendLine("Smart: EQ " + settings.SmartEq + " · dialogue " + settings.Dialogue + " · mic noise " + settings.Noise + " · buffer " + settings.Buffer);
            sb.AppendLine();
            sb.AppendLine("== Outputs ==");
            foreach (var o in outputs) sb.AppendLine(o);
            sb.AppendLine();
            sb.AppendLine("== Listeners ==");
            if (phone == null) sb.AppendLine("Phone link off");
            else
            {
                sb.AppendLine("Port " + phone.Port + " · party " + (phone.Party ? "on" : "off") + " (max " + phone.Capacity + ") · PIN " + (phone.Pin != null ? "on" : "off") + " · remote " + (phone.RemoteControl ? "on" : "off"));
                foreach (var p in phone.Snapshot())
                    sb.AppendLine(string.Format(CultureInfo.InvariantCulture, "  #{0} {1} ({2}) earbuds='{3}' battery={4} volume={5}% rtt={6}ms buffer={7}ms underruns={8} synced={9} sent={10} dropped={11}",
                        p.Id, p.Name, p.Client, p.Earbuds, p.Battery, p.Volume, p.RttMs, p.BufferMs, p.Underruns, p.Synced, p.PacketsSent, p.PacketsDropped));
            }
            if (listen != null)
            {
                sb.AppendLine();
                sb.AppendLine("== Listening to another device ==");
                sb.AppendLine("State " + listen.State + " · host '" + listen.HostName + "' · synced " + listen.Synced + " · rtt " + listen.RttMs.ToString("0.0", CultureInfo.InvariantCulture) +
                              " ms · buffer " + listen.Buffer.TargetMs + " ms · packets " + listen.Packets + " · lost " + listen.Lost + " · concealed " + listen.Concealed + " · underruns " + listen.Underruns);
            }
            sb.AppendLine();
            sb.AppendLine("== Last 200 log lines ==");
            sb.Append(Log.Tail(200));
            return Scrub(sb.ToString(), Secrets(settings));
        }
    }
}
