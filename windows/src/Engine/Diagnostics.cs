// Dual Earbuds Pro - diagnostics report (what the user can send when something doesn't work).
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
            sb.AppendLine("Windows: " + Environment.OSVersion.VersionString + (Environment.Is64BitOperatingSystem ? " (64-bit)" : ""));
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
            sb.Append(Log.Tail(80));
            return sb.ToString();
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
}
