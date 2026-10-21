// Dual Earbuds Pro - which kind of network the laptop is on (Public / Private / Domain), as Windows sees it.
// Used to explain phone-link trouble on cafe/hotel Wi-Fi. Asked in the background, never on the UI thread.
using System;
using System.Diagnostics;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Threading;

namespace DualEarbuds
{
    public static class NetworkInfo
    {
        static volatile string category;          // "Public", "Private", "DomainAuthenticated" or null (unknown)
        static int busy;
        static DateTime lastCheck = DateTime.MinValue;

        public static string Category { get { return category; } }
        public static bool IsPublic { get { return category == "Public"; } }

        // Starts a background check at most every 30 seconds.
        public static void Refresh()
        {
            if ((DateTime.UtcNow - lastCheck).TotalSeconds < 30) return;
            if (Interlocked.Exchange(ref busy, 1) == 1) return;
            lastCheck = DateTime.UtcNow;
            var t = new Thread(() =>
            {
                try { category = Query(); }
                catch (Exception ex) { Log.Warn("Network category: " + ex.Message); }
                finally { Interlocked.Exchange(ref busy, 0); }
            });
            t.IsBackground = true;
            t.Name = "NetworkCategory";
            t.Start();
        }

        // Category of the network adapter that carries the phone-link address.
        static string Query()
        {
            int index = PrimaryInterfaceIndex();
            if (index < 0) return null;
            var psi = new ProcessStartInfo("powershell.exe",
                "-NoProfile -NonInteractive -ExecutionPolicy Bypass -Command \"Get-NetConnectionProfile | ForEach-Object { [string]$_.InterfaceIndex + '|' + $_.NetworkCategory }\"")
            {
                UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true,
                WindowStyle = ProcessWindowStyle.Hidden
            };
            string output;
            using (var p = Process.Start(psi))
            {
                output = p.StandardOutput.ReadToEnd();
                if (!p.WaitForExit(15000)) { try { p.Kill(); } catch { } return null; }
            }
            return Parse(output, index);
        }

        // "12|Public" lines -> category of the given interface index.
        public static string Parse(string output, int index)
        {
            if (string.IsNullOrEmpty(output)) return null;
            foreach (var raw in output.Split('\n'))
            {
                string line = raw.Trim();
                int bar = line.IndexOf('|');
                if (bar <= 0) continue;
                int i;
                if (!int.TryParse(line.Substring(0, bar), out i) || i != index) continue;
                string c = line.Substring(bar + 1).Trim();
                if (c == "Public" || c == "Private" || c == "DomainAuthenticated") return c;
            }
            return null;
        }

        static int PrimaryInterfaceIndex()
        {
            string lan = PhoneLink.LanAddress();
            if (lan == null) return -1;
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up) continue;
                IPInterfaceProperties props;
                try { props = ni.GetIPProperties(); } catch { continue; }
                foreach (var ua in props.UnicastAddresses)
                {
                    if (ua.Address.AddressFamily != AddressFamily.InterNetwork || ua.Address.ToString() != lan) continue;
                    try { return props.GetIPv4Properties().Index; } catch { return -1; }
                }
            }
            return -1;
        }
    }
}
