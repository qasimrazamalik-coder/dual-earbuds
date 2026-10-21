// Dual Earbuds Pro - the Windows Firewall rule for the phone link, shared by the app and the installer.
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
                    ? "$r = New-Object -ComObject HNetCfg.FWRule; $r.Name = " + PsScript.Quote(RuleName) + "; $r.ApplicationName = " + PsScript.Quote(exe) + "; " +
                      "$r.Protocol = 6; $r.Direction = 1; $r.Action = 1; $r.Profiles = " + profiles.ToString(System.Globalization.CultureInfo.InvariantCulture) + "; $r.Enabled = $true; $fw.Rules.Add($r); "
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
}
