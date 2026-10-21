// Dual Earbuds - installer logic (install / repair / modify / uninstall), no UI.
// Per-user install: no administrator rights needed, except the optional firewall rule.
using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Threading;
using Microsoft.Win32;

namespace DualEarbuds.Setup
{
    public class InstallOptions
    {
        public string Folder;
        public bool DesktopShortcut = true;
        public bool StartMenuShortcut = true;
        public bool RunAtStartup = false;
        public bool FirewallRule = true;
        public bool Launch = true;
    }

    public class InstalledInfo
    {
        public string Folder;
        public string Version;
        public InstallOptions Options;
    }

    public class SetupCore
    {
        public const string AppExe = "DualEarbuds.exe";
        public const string SetupExe = "DualEarbuds-Setup.exe";
        public const string RuleName = Firewall.RuleName;
        const string UninstallKeyName = "DualEarbudsPro";

        // Test mode keeps everything inside one folder and a private registry key.
        public readonly string TestRoot;
        public Action<string> Progress = s => { };

        public SetupCore(string testRoot) { TestRoot = testRoot; }

        bool Test { get { return !string.IsNullOrEmpty(TestRoot); } }

        string UninstallKey
        {
            get { return Test ? @"Software\DualEarbudsSetupTest\Uninstall\" + UninstallKeyName : @"Software\Microsoft\Windows\CurrentVersion\Uninstall\" + UninstallKeyName; }
        }

        string RunKey { get { return Test ? @"Software\DualEarbudsSetupTest\Run" : @"Software\Microsoft\Windows\CurrentVersion\Run"; } }

        public string DefaultFolder
        {
            get
            {
                if (Test) return Path.Combine(TestRoot, "Programs", "Dual Earbuds");
                return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "Dual Earbuds");
            }
        }

        string DesktopDir { get { return Test ? Path.Combine(TestRoot, "Desktop") : Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory); } }
        string StartMenuDir { get { return Test ? Path.Combine(TestRoot, "StartMenu") : Environment.GetFolderPath(Environment.SpecialFolder.Programs); } }
        string DataDir
        {
            get { return Test ? Path.Combine(TestRoot, "AppData") : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "DualEarbuds"); }
        }

        // ------------------------------------------------------------------ state
        public InstalledInfo Installed()
        {
            using (var k = Registry.CurrentUser.OpenSubKey(UninstallKey))
            {
                if (k == null) return null;
                var folder = k.GetValue("InstallLocation") as string;
                if (string.IsNullOrEmpty(folder)) return null;
                var o = new InstallOptions
                {
                    Folder = folder,
                    DesktopShortcut = Flag(k, "OptDesktop", true),
                    StartMenuShortcut = Flag(k, "OptStartMenu", true),
                    RunAtStartup = Flag(k, "OptStartup", false),
                    FirewallRule = Flag(k, "OptFirewall", false),
                    Launch = false,
                };
                return new InstalledInfo { Folder = folder, Version = k.GetValue("DisplayVersion") as string, Options = o };
            }
        }

        static bool Flag(RegistryKey k, string name, bool def)
        {
            object v = k.GetValue(name);
            return v is int ? (int)v != 0 : def;
        }

        // ------------------------------------------------------------------ install / repair
        public void Install(InstallOptions o, byte[] appBytes, string setupSourcePath)
        {
            var prev = Installed();
            string folder = SafeInstallFolder(Path.GetFullPath(o.Folder), prev);
            o.Folder = folder;
            bool hadRule = prev != null && prev.Options.FirewallRule;
            Progress("Preparing...");
            CloseRunningApp(folder);
            if (prev != null && !SameDir(prev.Folder, folder))
            {
                // moving the install: remove our files from the old folder (only ours)
                Progress("Removing the copy in " + prev.Folder + "...");
                CloseRunningApp(prev.Folder);
                RemoveInstalledFiles(prev.Folder);
                hadRule = false;   // the old rule points at the old exe
            }
            Directory.CreateDirectory(folder);

            Progress("Copying Dual Earbuds...");
            string exe = Path.Combine(folder, AppExe);
            WriteAtomic(exe, appBytes);
            string setupCopy = Path.Combine(folder, SetupExe);
            if (!string.IsNullOrEmpty(setupSourcePath) && File.Exists(setupSourcePath) &&
                !string.Equals(Path.GetFullPath(setupSourcePath), Path.GetFullPath(setupCopy), StringComparison.OrdinalIgnoreCase))
                File.Copy(setupSourcePath, setupCopy, true);

            Progress("Creating shortcuts...");
            string desktopLnk = Path.Combine(DesktopDir, "Dual Earbuds.lnk");
            string startLnk = Path.Combine(StartMenuDir, "Dual Earbuds.lnk");
            if (o.DesktopShortcut) CreateShortcut(desktopLnk, exe, folder); else DeleteFile(desktopLnk);
            if (o.StartMenuShortcut) CreateShortcut(startLnk, exe, folder); else DeleteFile(startLnk);

            Progress("Registering with Windows...");
            using (var k = Registry.CurrentUser.CreateSubKey(RunKey))
            {
                if (o.RunAtStartup) k.SetValue("DualEarbuds", "\"" + exe + "\" --tray");
                else k.DeleteValue("DualEarbuds", false);
            }
            using (var k = Registry.CurrentUser.CreateSubKey(UninstallKey))
            {
                k.SetValue("DisplayName", "Dual Earbuds");
                k.SetValue("DisplayVersion", AppInfo.Version);
                k.SetValue("Publisher", "Qasim Raza Malik");
                k.SetValue("DisplayIcon", exe + ",0");
                k.SetValue("InstallLocation", folder);
                k.SetValue("InstallDate", DateTime.Now.ToString("yyyyMMdd"));
                k.SetValue("UninstallString", "\"" + setupCopy + "\" --uninstall");
                k.SetValue("QuietUninstallString", "\"" + setupCopy + "\" --uninstall --silent");
                k.SetValue("ModifyPath", "\"" + setupCopy + "\" --maintain");
                k.SetValue("NoModify", 0, RegistryValueKind.DWord);
                k.SetValue("NoRepair", 0, RegistryValueKind.DWord);
                k.SetValue("EstimatedSize", (int)Math.Max(1, (appBytes.Length + 2 * 1024 * 1024) / 1024), RegistryValueKind.DWord);
                k.SetValue("OptDesktop", o.DesktopShortcut ? 1 : 0, RegistryValueKind.DWord);
                k.SetValue("OptStartMenu", o.StartMenuShortcut ? 1 : 0, RegistryValueKind.DWord);
                k.SetValue("OptStartup", o.RunAtStartup ? 1 : 0, RegistryValueKind.DWord);
                k.SetValue("OptFirewall", (o.FirewallRule || hadRule) ? 1 : 0, RegistryValueKind.DWord);
            }

            if (o.FirewallRule && !Test)
            {
                Progress("Allowing phones through the firewall (Windows will ask)...");
                // private and public networks by default, so it also works on cafe/hotel Wi-Fi; the link key and the
                // app's wrong-key lockout protect it. The app's Settings can narrow it to home networks.
                bool ok = Firewall.Apply(exe, true, Firewall.ProfilesPrivateAndPublic) == FirewallResult.Ok;
                if (!ok)
                {
                    Progress("Firewall rule skipped - Windows will ask the first time a phone connects.");
                    using (var k = Registry.CurrentUser.CreateSubKey(UninstallKey))
                        k.SetValue("OptFirewall", hadRule ? 1 : 0, RegistryValueKind.DWord);
                }
            }
            Progress("Done.");
        }

        public void Repair(byte[] appBytes, string setupSourcePath)
        {
            var info = Installed();
            if (info == null) throw new InvalidOperationException("Dual Earbuds is not installed.");
            var o = info.Options;
            o.Folder = info.Folder;
            o.FirewallRule = false;            // keep the existing rule; repair never prompts
            Install(o, appBytes, setupSourcePath);
        }

        // In-place upgrade started by the app's updater: same folder, shortcuts, startup and firewall
        // choices as the installed copy, no prompts. Returns the app to start again afterwards.
        public string Update(byte[] appBytes, string setupSourcePath)
        {
            var info = Installed();
            if (info == null) throw new InvalidOperationException("Dual Earbuds is not installed.");
            Repair(appBytes, setupSourcePath);
            return Path.Combine(info.Folder, AppExe);
        }

        // ------------------------------------------------------------------ uninstall
        public void Uninstall(bool removeUserData)
        {
            var info = Installed();
            if (info == null) throw new InvalidOperationException("Dual Earbuds is not installed.");
            string folder = info.Folder;
            Progress("Closing Dual Earbuds...");
            CloseRunningApp(folder);

            Progress("Removing shortcuts...");
            DeleteFile(Path.Combine(DesktopDir, "Dual Earbuds.lnk"));
            DeleteFile(Path.Combine(StartMenuDir, "Dual Earbuds.lnk"));

            Progress("Removing startup entry...");
            using (var k = Registry.CurrentUser.OpenSubKey(RunKey, true))
                if (k != null) k.DeleteValue("DualEarbuds", false);

            if (info.Options.FirewallRule && !Test)
            {
                Progress("Removing the firewall rule (Windows will ask)...");
                Firewall.Apply(Path.Combine(folder, AppExe), false, 0);
            }

            Progress("Removing files...");
            string setupCopy = Path.Combine(folder, SetupExe);
            bool runningFromFolder = RemoveInstalledFiles(folder);

            if (removeUserData)
            {
                Progress("Removing settings and logs...");
                TryDeleteDir(DataDir, false);
            }

            Registry.CurrentUser.DeleteSubKeyTree(UninstallKey, false);
            if (runningFromFolder && !Test) ScheduleDelete(setupCopy, folder);
            Progress("Done.");
        }

        // ------------------------------------------------------------------ helpers
        void CloseRunningApp(string folder)
        {
            string exe = Path.Combine(folder, AppExe);
            if (!Test)
            {
                try { using (var ev = EventWaitHandle.OpenExisting("DualEarbudsPro_Quit")) ev.Set(); Thread.Sleep(1500); } catch { }
            }
            foreach (var p in Process.GetProcessesByName(Path.GetFileNameWithoutExtension(AppExe)))
            {
                try
                {
                    string path = p.MainModule.FileName;
                    if (!string.Equals(Path.GetFullPath(path), Path.GetFullPath(exe), StringComparison.OrdinalIgnoreCase)) continue;
                    Progress("Closing the running app...");
                    if (!p.WaitForExit(2500)) { p.Kill(); p.WaitForExit(3000); }
                }
                catch { }
                finally { p.Dispose(); }
            }
        }

        static void WriteAtomic(string path, byte[] bytes)
        {
            string tmp = path + ".new";
            File.WriteAllBytes(tmp, bytes);
            for (int i = 0; ; i++)
            {
                try
                {
                    if (File.Exists(path)) File.Delete(path);
                    File.Move(tmp, path);
                    return;
                }
                catch (IOException)
                {
                    if (i >= 10) throw;
                    Thread.Sleep(300);
                }
                catch (UnauthorizedAccessException)
                {
                    if (i >= 10) throw;
                    Thread.Sleep(300);
                }
            }
        }

        // The install folder must be empty, already ours, or new. Otherwise use a "Dual Earbuds" subfolder,
        // so uninstall can never touch files that were there before.
        // A non-empty folder that isn't ours gets a "Dual Earbuds" subfolder, so uninstalling never touches other
        // files. The folder recorded in our uninstall key is ours by definition (repair after the exe was deleted
        // must not nest), and so is any folder holding our exe or our setup copy.
        static string SafeInstallFolder(string folder, InstalledInfo prev)
        {
            try
            {
                if (prev != null && !string.IsNullOrEmpty(prev.Folder) && SameDir(prev.Folder, folder)) return folder;
                if (Directory.Exists(folder) && Directory.EnumerateFileSystemEntries(folder).Any()
                    && !File.Exists(Path.Combine(folder, AppExe)) && !File.Exists(Path.Combine(folder, SetupExe)))
                    return Path.Combine(folder, "Dual Earbuds");
            }
            catch { }
            return folder;
        }

        static bool SameDir(string a, string b)
        {
            try { return string.Equals(Path.GetFullPath(a).TrimEnd('\\'), Path.GetFullPath(b).TrimEnd('\\'), StringComparison.OrdinalIgnoreCase); }
            catch { return false; }
        }

        // Deletes only the files setup writes, then the folder if it is empty. Never recursive.
        // Returns true when this setup is running from that folder (its own copy is removed after exit).
        static bool RemoveInstalledFiles(string folder)
        {
            string self = Assembly.GetEntryAssembly() == null ? null : Assembly.GetEntryAssembly().Location;
            string setupCopy = Path.Combine(folder, SetupExe);
            bool runningFromFolder = self != null && SameDir(self, setupCopy);
            DeleteFile(Path.Combine(folder, AppExe));
            DeleteFile(Path.Combine(folder, AppExe + ".new"));
            if (!runningFromFolder) DeleteFile(setupCopy);
            try
            {
                if (Directory.Exists(folder) && !Directory.EnumerateFileSystemEntries(folder).Any()) Directory.Delete(folder);
            }
            catch { }
            return runningFromFolder;
        }

        static void DeleteFile(string path)
        {
            try { if (File.Exists(path)) File.Delete(path); } catch { }
        }

        static void TryDeleteDir(string dir, bool keepIfBusy)
        {
            try
            {
                if (!Directory.Exists(dir)) return;
                if (keepIfBusy) { if (!Directory.EnumerateFileSystemEntries(dir).Any()) Directory.Delete(dir); }
                else Directory.Delete(dir, true);
            }
            catch { }
        }

        // The setup copy inside the install folder can't delete itself; a hidden cmd does it after we exit.
        static void ScheduleDelete(string file, string folder)
        {
            try
            {
                string script = "Start-Sleep -Seconds 3; " +
                    "Remove-Item -LiteralPath " + PsScript.Quote(file) + " -Force -ErrorAction SilentlyContinue; " +
                    "try { [IO.Directory]::Delete(" + PsScript.Quote(folder) + ") } catch { }";   // only if empty
                var psi = new ProcessStartInfo("powershell.exe", "-NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand " + PsScript.Encode(script))
                    { CreateNoWindow = true, UseShellExecute = false, WindowStyle = ProcessWindowStyle.Hidden };
                Process.Start(psi);
            }
            catch { }
        }

        public static void CreateShortcut(string lnk, string target, string workDir)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(lnk));
            Type t = Type.GetTypeFromProgID("WScript.Shell");
            object shell = Activator.CreateInstance(t);
            try
            {
                object sc = t.InvokeMember("CreateShortcut", BindingFlags.InvokeMethod, null, shell, new object[] { lnk });
                Type st = sc.GetType();
                st.InvokeMember("TargetPath", BindingFlags.SetProperty, null, sc, new object[] { target });
                st.InvokeMember("WorkingDirectory", BindingFlags.SetProperty, null, sc, new object[] { workDir });
                st.InvokeMember("IconLocation", BindingFlags.SetProperty, null, sc, new object[] { target + ",0" });
                st.InvokeMember("Description", BindingFlags.SetProperty, null, sc, new object[] { "Play your laptop's sound on two pairs of earbuds" });
                st.InvokeMember("Save", BindingFlags.InvokeMethod, null, sc, null);
                System.Runtime.InteropServices.Marshal.FinalReleaseComObject(sc);
            }
            finally { System.Runtime.InteropServices.Marshal.FinalReleaseComObject(shell); }
        }
    }
}
