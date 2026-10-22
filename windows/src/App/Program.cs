// Dual Earbuds - entry point.
//   DualEarbuds.exe                 normal start
//   DualEarbuds.exe --tray          start hidden in the tray (used by "Run when Windows starts")
//   DualEarbuds.exe --screenshots D render demo screenshots + demo.gif into folder D (no window, no sound)
//   DualEarbuds.exe --make-icon F   write the multi-size app icon to F
//   DualEarbuds.exe --uitest F      build every page offscreen with demo data and report problems to F
//   DualEarbuds.exe --battery F     write the battery levels Windows reports to F
using System;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Windows;
using System.Windows.Threading;

namespace DualEarbuds
{
    public static class Program
    {
        [DllImport("winmm.dll")] static extern uint timeBeginPeriod(uint ms);
        [DllImport("winmm.dll")] static extern uint timeEndPeriod(uint ms);

        const string MutexName = "DualEarbudsPro_SingleInstance";
        const string ShowEventName = "DualEarbudsPro_Show";
        public const string QuitEventName = "DualEarbudsPro_Quit";   // the installer asks a running copy to exit

        [STAThread]
        public static int Main(string[] args)
        {
            try
            {
                if (args.Length >= 2 && args[0] == "--screenshots") return Tool(() => Screenshots.Run(args[1]));
                if (args.Length >= 2 && args[0] == "--make-icon") return Tool(() => { IconFactory.WriteIco(args[1]); return 0; });
                if (args.Length >= 2 && args[0] == "--uitest") return Tool(() => Screenshots.UiTest(args[1]));
                if (args.Length >= 2 && args[0] == "--battery") return BatteryDump(args[1]);
                return RunApp(args.Contains("--tray"));
            }
            catch (Exception ex)
            {
                Log.Error("Fatal: " + ex);
                if (args.Length >= 1 && args[0].StartsWith("--") && args[0] != "--tray")
                {
                    // build / QA tools never show a dialog
                    try { File.WriteAllText(Path.Combine(Path.GetTempPath(), "DualEarbuds-tool-error.txt"), ex.ToString()); } catch { }
                    return 1;
                }
                MessageBox.Show("Dual Earbuds hit an unexpected problem and has to close.\n\n" + ex.Message + "\n\nDetails were saved in the log folder.", "Dual Earbuds", MessageBoxButton.OK, MessageBoxImage.Error);
                return 1;
            }
        }

        static int Tool(Func<int> f)
        {
            var app = new Application { ShutdownMode = ShutdownMode.OnExplicitShutdown };
            Theme.Apply(app.Resources, "dark");
            return f();
        }

        static int RunApp(bool tray)
        {
            bool createdNew;
            using (var mutex = new Mutex(true, MutexName, out createdNew))
            {
                if (!createdNew)
                {
                    try { using (var ev = EventWaitHandle.OpenExisting(ShowEventName)) ev.Set(); } catch { }
                    return 0;
                }
                using (var showEvent = new EventWaitHandle(false, EventResetMode.AutoReset, ShowEventName))
                using (var quitEvent = new EventWaitHandle(false, EventResetMode.AutoReset, QuitEventName))
                {
                    timeBeginPeriod(1);
                    Log.Info("Starting " + AppInfo.Name + " " + AppInfo.Version);
                    Power.OptOutOfThrottling();   // keep the 10 ms rhythm on battery saver and while minimized
                    var app = new Application { ShutdownMode = ShutdownMode.OnExplicitShutdown };
                    app.DispatcherUnhandledException += (s, e) =>
                    {
                        Log.Error("UI error: " + e.Exception);
                        e.Handled = true;
                    };
                    AppDomain.CurrentDomain.UnhandledException += (s, e) => Log.Error("Unhandled: " + e.ExceptionObject);

                    var settings = Settings.Load();
                    Theme.Apply(app.Resources, settings.Theme);
                    var ctl = new Controller(settings);
                    var win = new MainWindow(ctl);
                    ctl.Initialize();

                    // a second launch asks this one to show its window; the installer asks it to exit
                    var listener = new Thread(() =>
                    {
                        var handles = new WaitHandle[] { showEvent, quitEvent };
                        while (true)
                        {
                            int which = WaitHandle.WaitAny(handles);
                            try
                            {
                                if (which == 0) app.Dispatcher.BeginInvoke(new Action(win.ShowFromTray));
                                else { app.Dispatcher.BeginInvoke(new Action(win.ExitApp)); return; }
                            }
                            catch { return; }
                        }
                    });
                    listener.IsBackground = true;
                    listener.Start();

                    if (!(tray || settings.StartMinimized)) win.Show();
                    app.Run();
                    Power.KeepAwake(false);
                    timeEndPeriod(1);
                    Log.Info("Exited");
                }
            }
            return 0;
        }

        static int BatteryDump(string path)
        {
            var sb = new StringBuilder();
            try
            {
                var levels = BatteryReader.ReadAll();
                sb.AppendLine("battery levels by container: " + levels.Count);
                foreach (var kv in levels) sb.AppendLine("  " + kv.Key + " = " + kv.Value + "%");
                string def;
                foreach (var d in Wasapi.ListOutputs(out def))
                {
                    int v;
                    sb.AppendLine("output " + d.Name + " container=" + d.ContainerId + " battery=" + (levels.TryGetValue(d.ContainerId, out v) ? v + "%" : "n/a"));
                }
            }
            catch (Exception ex) { sb.AppendLine("ERROR " + ex); }
            File.WriteAllText(path, sb.ToString());
            return 0;
        }
    }
}
