// Dual Earbuds Pro - installer entry point.
//   DualEarbuds-Setup.exe                      install wizard (or maintenance page if installed)
//   DualEarbuds-Setup.exe --maintain           Modify / Repair / Uninstall page
//   DualEarbuds-Setup.exe --uninstall          uninstall page
//   --silent                                   no UI: install (default), --repair or --uninstall
//   --dir "C:\path"                            install folder (silent install)
//   --no-desktop --no-startmenu --startup --no-firewall --remove-data
//   --test-root "C:\tmp\x"                     QA: keep files, shortcuts and registry inside a sandbox
//   --screenshots "C:\docs"                     render every installer screen to PNG (no window, installs nothing)
using System;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Windows;

namespace DualEarbuds.Setup
{
    public static class SetupProgram
    {
        [STAThread]
        public static int Main(string[] args)
        {
            string shots = Arg(args, "--screenshots");
            if (shots != null)
            {
                try
                {
                    var a = new Application { ShutdownMode = ShutdownMode.OnExplicitShutdown };
                    Theme.Apply(a.Resources, "dark");
                    return SetupWindow.Screenshots(shots);
                }
                catch (Exception ex)
                {
                    try { File.WriteAllText(Path.Combine(Path.GetTempPath(), "DualEarbuds-setup-shots-error.txt"), ex.ToString()); } catch { }
                    return 2;
                }
            }
            string testRoot = Arg(args, "--test-root");
            var core = new SetupCore(testRoot);
            bool silent = args.Contains("--silent");
            string self = Assembly.GetEntryAssembly().Location;
            try
            {
                byte[] payload = LoadPayload();
                if (silent)
                {
                    core.Progress = s => Console.WriteLine(s);
                    if (args.Contains("--uninstall")) core.Uninstall(args.Contains("--remove-data"));
                    else if (args.Contains("--repair")) core.Repair(payload, self);
                    else
                    {
                        var o = new InstallOptions
                        {
                            Folder = Arg(args, "--dir") ?? core.DefaultFolder,
                            DesktopShortcut = !args.Contains("--no-desktop"),
                            StartMenuShortcut = !args.Contains("--no-startmenu"),
                            RunAtStartup = args.Contains("--startup"),
                            FirewallRule = !args.Contains("--no-firewall"),
                            Launch = false,
                        };
                        core.Install(o, payload, self);
                    }
                    return 0;
                }

                var app = new Application { ShutdownMode = ShutdownMode.OnMainWindowClose };
                Theme.Apply(app.Resources, "dark");
                var mode = args.Contains("--uninstall") ? SetupMode.Uninstall : args.Contains("--maintain") ? SetupMode.Maintain : SetupMode.Install;
                var win = new SetupWindow(core, payload, self, mode);
                app.Run(win);
                return win.ExitCode;
            }
            catch (Exception ex)
            {
                if (silent) { Console.Error.WriteLine("ERROR " + ex.Message); return 2; }
                MessageBox.Show("Setup couldn't continue: " + ex.Message, "Dual Earbuds Setup", MessageBoxButton.OK, MessageBoxImage.Error);
                return 2;
            }
        }

        static string Arg(string[] args, string name)
        {
            int i = Array.IndexOf(args, name);
            return i >= 0 && i + 1 < args.Length ? args[i + 1] : null;
        }

        static byte[] LoadPayload()
        {
            using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("DualEarbuds.exe"))
            {
                if (s == null) throw new InvalidOperationException("This setup file is damaged (the app inside it is missing). Download it again.");
                var ms = new MemoryStream();
                s.CopyTo(ms);
                return ms.ToArray();
            }
        }
    }
}
