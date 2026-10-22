// Dual Earbuds - the Windows Firewall rule for the phone link, shared by the app and the installer.
// One elevated PowerShell call (Windows shows its permission prompt) using the HNetCfg.FwPolicy2 COM API.
using System;
using System.ComponentModel;
using System.Diagnostics;
using System.Text;

namespace DualEarbuds
{
    public enum FirewallResult { Failed, Ok, Declined }   // Failed first: default(FirewallResult) must never mean success

    public static class PsScript
    {
        // Single-quoted PowerShell string literal.
        public static string Quote(string s) { return "'" + (s ?? "").Replace("'", "''") + "'"; }

        // For -EncodedCommand: base64 of the UTF-16LE script, so no quoting can break the command line.
        public static string Encode(string script) { return Convert.ToBase64String(Encoding.Unicode.GetBytes(script)); }
    }

    public static class Firewall
    {
        public const string RuleName = "Dual Earbuds (phone link)";
        public const int ProfilePrivate = 2;          // NET_FW_PROFILE2_PRIVATE
        public const int ProfilePublic = 4;           // NET_FW_PROFILE2_PUBLIC
        public const int ProfilesPrivateAndPublic = 6;

        public static int ProfilesFor(string networkScope)
        {
            return networkScope == Settings.NetScopePrivate ? ProfilePrivate : ProfilesPrivateAndPublic;
        }

        // Removes every rule with our name, then (add) creates one inbound TCP allow rule for exe on the given profiles.
        public static string BuildScript(string exe, bool add, int profiles)
        {
            return
                "$fw = New-Object -ComObject HNetCfg.FwPolicy2; " +
                "while (@($fw.Rules | Where-Object { $_.Name -eq " + PsScript.Quote(RuleName) + " }).Count -gt 0) { $fw.Rules.Remove(" + PsScript.Quote(RuleName) + ") }; " +
                (add
                    // TCP for the link page / WebSocket, UDP for v3.2 audio (listeners' keepalives come in on UDP)
                    ? "foreach ($p in 6, 17) { $r = New-Object -ComObject HNetCfg.FWRule; $r.Name = " + PsScript.Quote(RuleName) + "; $r.ApplicationName = " + PsScript.Quote(exe) + "; " +
                      "$r.Protocol = $p; $r.Direction = 1; $r.Action = 1; $r.Profiles = " + profiles.ToString(System.Globalization.CultureInfo.InvariantCulture) + "; $r.Enabled = $true; $fw.Rules.Add($r) }; "
                    : "") +
                "exit 0";
        }

        // Blocks until PowerShell finishes (up to 30 s) - never call it on the UI thread.
        public static FirewallResult Apply(string exe, bool add, int profiles)
        {
            try
            {
                var psi = new ProcessStartInfo("powershell.exe", "-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -EncodedCommand " + PsScript.Encode(BuildScript(exe, add, profiles)))
                    { Verb = "runas", UseShellExecute = true, WindowStyle = ProcessWindowStyle.Hidden };
                using (var p = Process.Start(psi))
                {
                    if (p == null) return FirewallResult.Failed;
                    if (!p.WaitForExit(30000)) return FirewallResult.Failed;
                    return p.ExitCode == 0 ? FirewallResult.Ok : FirewallResult.Failed;
                }
            }
            catch (Win32Exception ex)
            {
                if (ex.NativeErrorCode == 1223) return FirewallResult.Declined;   // ERROR_CANCELLED: the user said No
                Log.Warn("Firewall rule: " + ex.Message);
                return FirewallResult.Failed;
            }
            catch (Exception ex)
            {
                Log.Warn("Firewall rule: " + ex.Message);
                return FirewallResult.Failed;
            }
        }
    }

    // What the firewall rule allows right now (read without admin rights), for Setup check.
    public class FirewallStatus
    {
        public bool Known;           // the query worked
        public bool Tcp, Udp;        // an enabled inbound allow rule for this app per protocol
        public int Profiles;         // union of the profiles those rules cover
        public bool OtherApp;        // a rule with our name points at another exe (an old install)
        public bool Complete { get { return Tcp && Udp; } }
    }

    public static class FirewallCheck
    {
        public static string QueryScript
        {
            get
            {
                return "$fw = New-Object -ComObject HNetCfg.FwPolicy2; $fw.Rules | Where-Object { $_.Name -eq " + PsScript.Quote(Firewall.RuleName) + " } | " +
                       "ForEach-Object { [string]$_.Protocol + '|' + [string]$_.Profiles + '|' + [string]$_.Enabled + '|' + [string]$_.Direction + '|' + [string]$_.Action + '|' + $_.ApplicationName }";
            }
        }

        // Lines "protocol|profiles|enabled|direction|action|exe" -> status for exePath.
        public static FirewallStatus Parse(string output, string exePath)
        {
            var st = new FirewallStatus { Known = output != null };
            if (string.IsNullOrEmpty(output)) return st;
            foreach (var raw in output.Split('\n'))
            {
                var p = raw.Trim().Split('|');
                if (p.Length < 6) continue;
                int proto, prof, dir, act;
                if (!int.TryParse(p[0], out proto) || !int.TryParse(p[1], out prof) || !int.TryParse(p[3], out dir) || !int.TryParse(p[4], out act)) continue;
                bool enabled = p[2].Trim().Equals("True", StringComparison.OrdinalIgnoreCase);
                string app = string.Join("|", p, 5, p.Length - 5).Trim();
                if (exePath != null && app.Length > 0 && !app.Equals(exePath, StringComparison.OrdinalIgnoreCase)) { st.OtherApp = true; continue; }
                if (!enabled || dir != 1 || act != 1) continue;
                if (proto == 6) st.Tcp = true;
                else if (proto == 17) st.Udp = true;
                else if (proto == 256) { st.Tcp = true; st.Udp = true; }
                else continue;
                st.Profiles |= prof;
            }
            return st;
        }

        // Blocks for a few seconds: background threads only.
        public static FirewallStatus Query(string exePath)
        {
            try
            {
                var psi = new ProcessStartInfo("powershell.exe", "-NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand " + PsScript.Encode(QueryScript))
                {
                    UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true, WindowStyle = ProcessWindowStyle.Hidden
                };
                using (var p = Process.Start(psi))
                {
                    string output = p.StandardOutput.ReadToEnd();
                    if (!p.WaitForExit(15000)) { try { p.Kill(); } catch { } return new FirewallStatus(); }
                    return Parse(output ?? "", exePath);
                }
            }
            catch (Exception ex)
            {
                Log.Warn("Firewall check: " + ex.Message);
                return new FirewallStatus();
            }
        }
    }
}
