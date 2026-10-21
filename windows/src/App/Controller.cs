// Dual Earbuds Pro - app logic, independent of the window (also drives screenshot/demo mode).
using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Windows.Threading;
using Microsoft.Win32;

namespace DualEarbuds
{
    public class OutputVm
    {
        public string Id;
        public string Name;
        public Guid ContainerId;
        public bool Enabled;
        public int DelayMs;
        public int Volume = 100;
        public string State = "Off";
        public StateKind Kind = StateKind.Off;
        public string Error;
        public int Battery = -1;
        public float Level;
        public bool IsSource;
        public DateTime LastRetry = DateTime.MinValue;
    }

    public class Controller
    {
        public readonly Settings Settings;
        public readonly Engine Engine = new Engine();
        public PhoneLink Phone;
        public readonly List<OutputVm> Outputs = new List<OutputVm>();
        public List<DeviceInfo> AllDevices = new List<DeviceInfo>();
        public string DefaultId;
        public List<PhoneInfo> Phones = new List<PhoneInfo>();
        public string DemoNetwork = "Private";   // screenshots only

        // "Public", "Private", "DomainAuthenticated" or null when unknown
        public string NetworkCategory { get { return Demo ? DemoNetwork : NetworkInfo.Category; } }
        public string StatusText = "";
        public StateKind StatusKind = StateKind.Off;
        public float Level;
        public bool WantRunning;
        public bool Demo;

        public event Action DevicesChanged;   // device list changed: rebuild cards
        public event Action StateChanged;     // states/levels changed: refresh labels
        public event Action UpdateChanged;    // update check / download state changed
        public event Action<string> UpdateFound;   // an automatic check found this version (tray tip)

        // ---- updates (all network work runs on background threads)
        public UpdateCheckResult UpdateResult;
        public bool UpdateChecking, UpdateDownloading;
        public int UpdateProgress = -1;       // 0-100, -1 unknown
        public string UpdateError;            // download / install problem, shown under the release
        DateTime startedAt = DateTime.Now;
        string notifiedVersion;

        string devSig;
        DateTime lastAutoTry = DateTime.MinValue, errorUntil = DateTime.MinValue;
        int captureFailures;
        bool dirty;

        public Controller(Settings settings) { Settings = settings; }

        // ------------------------------------------------------------------ device worker
        // Opening and closing audio devices can take seconds (Bluetooth especially), so it never runs
        // on the UI thread. One worker runs those jobs in order; results come back via the dispatcher.
        readonly object qlk = new object();
        readonly Queue<Action> queue = new Queue<Action>();
        Thread worker;
        Dispatcher ui;
        int pendingWork;
        bool workerExit;

        bool Busy { get { return Thread.VolatileRead(ref pendingWork) > 0; } }

        void StartWorker()
        {
            if (worker != null || Demo) return;
            ui = Dispatcher.CurrentDispatcher;
            worker = new Thread(WorkerLoop) { IsBackground = true, Name = "EngineWorker" };
            worker.SetApartmentState(ApartmentState.MTA);
            worker.Start();
        }

        void WorkerLoop()
        {
            while (true)
            {
                Action a;
                lock (qlk)
                {
                    while (queue.Count == 0)
                    {
                        if (workerExit) return;
                        Monitor.Wait(qlk);
                    }
                    a = queue.Dequeue();
                }
                a();
            }
        }

        // Runs work on the device worker, then done (optional) and a state refresh on the UI thread.
        void Background(Action work, Action done)
        {
            if (ui == null)
            {
                // demo / screenshot mode and before Initialize: run inline
                work();
                if (done != null) done();
                return;
            }
            Interlocked.Increment(ref pendingWork);
            lock (qlk)
            {
                queue.Enqueue(() =>
                {
                    try { work(); }
                    catch (Exception ex) { Log.Error("Engine job: " + ex); }
                    Interlocked.Decrement(ref pendingWork);
                    try
                    {
                        ui.BeginInvoke(new Action(() =>
                        {
                            if (done != null) done();
                            UpdateStates();
                        }));
                    }
                    catch { }
                });
                Monitor.Pulse(qlk);
            }
        }

        public bool Running { get { return Demo ? WantRunning : Engine.Running; } }

        public string SourceId
        {
            get
            {
                if (Engine.Running) return Engine.SourceDeviceId;
                return Settings.SourceId == "" ? DefaultId : Settings.SourceId;
            }
        }

        public string NameOf(string id)
        {
            if (id == null) return "unknown device";
            var d = AllDevices.Find(x => x.Id == id);
            return d == null ? "unknown device" : d.Name;
        }

        // ------------------------------------------------------------------ lifecycle
        public void Initialize()
        {
            startedAt = DateTime.Now;
            StartWorker();
            RefreshDevices(true);
            if (Settings.PhoneLink) StartPhone();
            if (Settings.AutoStart) Start(true);
            else SetInfo("Press Start to begin.");
            Settings.FirstRun = false;
            dirty = true;
        }

        public void Shutdown()
        {
            lock (qlk) { workerExit = true; queue.Clear(); Monitor.Pulse(qlk); }
            if (worker != null) worker.Join(3000);
            Engine.Stop();
            StopPhone();
            Settings.Save();
        }

        // Called about every 2 seconds by the window.
        public void SlowTick()
        {
            if (Demo) return;
            RefreshDevices(false);
            HealthCheck();
            UpdateBatteries();
            if (Phone != null) Phones = Phone.Snapshot();
            if (Settings.PhoneLink) NetworkInfo.Refresh();   // background, at most every 30 s
            MaybeAutoCheckUpdates();
            UpdateStates();
            if (dirty) { Settings.Save(); dirty = false; }
        }

        // Called about every 50 ms by the window.
        public void FastTick()
        {
            if (Demo) return;
            float pk = Engine.TakePeak();
            Level = Math.Max(Level * 0.88f, (float)Math.Sqrt(pk));
            foreach (var o in Outputs)
            {
                var s = Engine.Get(o.Id);
                float p = s == null ? 0f : s.TakePeak();
                o.Level = Math.Max(o.Level * 0.85f, (float)Math.Sqrt(p));
            }
        }

        public void MarkDirty() { dirty = true; }

        // ------------------------------------------------------------------ devices
        public void RefreshDevices(bool force)
        {
            List<DeviceInfo> all;
            string def;
            try { all = Wasapi.ListOutputs(out def); }
            catch (Exception ex) { SetError("Could not read audio devices: " + ex.Message); return; }

            string sig = def + "|" + string.Join("|", all.Select(d => d.Id).ToArray());
            if (!force && sig == devSig) return;
            bool defChanged = devSig != null && def != DefaultId;
            devSig = sig;
            DefaultId = def;
            AllDevices = all;

            var visible = all.Where(d => !Wasapi.IsHandsFree(d.Name))
                             .OrderBy(d => d.Name.StartsWith("Headphones", StringComparison.OrdinalIgnoreCase) ? 0 : 1)
                             .ThenBy(d => d.Name).ToList();
            var old = Outputs.ToDictionary(o => o.Id);
            Outputs.Clear();
            foreach (var d in visible)
            {
                OutputVm vm;
                if (!old.TryGetValue(d.Id, out vm))
                {
                    bool isNew = !Settings.Outs.ContainsKey(d.Id);
                    var s = Settings.For(d.Id);
                    if (isNew)
                    {
                        if (d.Name.StartsWith("Headphones", StringComparison.OrdinalIgnoreCase)) s.Enabled = true;
                        dirty = true;
                    }
                    vm = new OutputVm { Id = d.Id, Enabled = s.Enabled, DelayMs = s.DelayMs, Volume = s.Volume };
                }
                vm.Name = d.Name;
                vm.ContainerId = d.ContainerId;
                Outputs.Add(vm);
            }
            UpdateBatteries();
            UpdateStates();
            Raise(DevicesChanged);
            if (Engine.Running && defChanged && Settings.SourceId == "")
            {
                SetInfo("Windows default output changed - switching source...");
                Start(false);
            }
        }

        void UpdateBatteries()
        {
            foreach (var o in Outputs) o.Battery = BatteryReader.ForContainer(o.ContainerId);
        }

        // ------------------------------------------------------------------ mirroring
        public void Toggle()
        {
            if (Running || WantRunning) Stop();
            else Start(true);
        }

        public void Start(bool manual)
        {
            if (manual) captureFailures = 0;
            WantRunning = true;
            lastAutoTry = DateTime.Now;
            if (Demo) { UpdateStates(); return; }
            string src = Settings.SourceId == "" ? null : Settings.SourceId;
            string srcName = NameOf(src ?? DefaultId);
            var targets = Outputs.Where(o => o.Enabled).ToList();
            foreach (var o in targets) o.LastRetry = DateTime.Now;
            string err = null;
            Background(() =>
            {
                err = Engine.Start(src);
                if (err != null) return;
                foreach (var o in targets)
                    if (o.Enabled && o.Id != Engine.SourceDeviceId) Engine.AddOutput(o.Id, o.DelayMs, o.Volume / 100f);
            }, () =>
            {
                if (err != null)
                {
                    if (WantRunning) SetError("Waiting for the sound source (" + srcName + "): " + err + ". Trying again every few seconds.");
                }
                else errorUntil = DateTime.MinValue;
            });
            UpdateStates();
        }

        public void Stop()
        {
            WantRunning = false;
            Background(Engine.Stop, null);
            SetInfo("Stopped.");
            UpdateStates();
        }

        void AddOut(OutputVm o)
        {
            o.LastRetry = DateTime.Now;
            Background(() => { if (Engine.Running && o.Enabled) Engine.AddOutput(o.Id, o.DelayMs, o.Volume / 100f); }, null);
        }

        void HealthCheck()
        {
            if (Busy) return;   // a device job is still running; check again next tick
            if (!Engine.Running)
            {
                if (WantRunning && (DateTime.Now - lastAutoTry).TotalSeconds >= 4) Start(false);
                return;
            }
            string ce = Engine.CaptureError;
            if (ce != null)
            {
                captureFailures++;
                if (captureFailures > 5)
                {
                    Background(Engine.Stop, null);
                    SetError("The sound source isn't available (" + ce + "). Trying again every few seconds - or pick another source.");
                    return;
                }
                Start(false);
                return;
            }
            foreach (var o in Outputs)
            {
                if (!o.Enabled || o.Id == Engine.SourceDeviceId) continue;
                var s = Engine.Get(o.Id);
                if ((s == null || !s.Running) && (DateTime.Now - o.LastRetry).TotalSeconds >= 3) AddOut(o);
            }
        }

        public void SetSource(string id)
        {
            Settings.SourceId = id ?? "";
            dirty = true;
            if (Engine.Running) Start(true);
            UpdateStates();
        }

        public void SetEnabled(OutputVm o, bool on)
        {
            o.Enabled = on;
            Settings.For(o.Id).Enabled = on;
            dirty = true;
            if (Engine.Running)
            {
                if (on && o.Id != Engine.SourceDeviceId) AddOut(o);
                else { string id = o.Id; Background(() => Engine.RemoveOutput(id), null); }
            }
            UpdateStates();
        }

        public void SetDelay(OutputVm o, int ms)
        {
            o.DelayMs = Math.Max(0, Math.Min(1000, ms));
            Settings.For(o.Id).DelayMs = o.DelayMs;
            dirty = true;
            var s = Engine.Get(o.Id);
            if (s != null) s.SetDelayMs(o.DelayMs);
        }

        public void SetVolume(OutputVm o, int volume)
        {
            o.Volume = Math.Max(0, Math.Min(150, volume));
            Settings.For(o.Id).Volume = o.Volume;
            dirty = true;
            var s = Engine.Get(o.Id);
            if (s != null) s.Gain = o.Volume / 100f;
        }

        public void Test(OutputVm o) { if (!Demo) Beeper.Play(o.Id); }

        // ------------------------------------------------------------------ phone link
        public void StartPhone()
        {
            if (Phone != null || Demo) return;
            var p = new PhoneLink(Settings.PhonePort, Settings.LinkKey);
            if (!p.Start())
            {
                SetError("Could not start the phone link on port " + Settings.PhonePort + ": " + p.Error);
                return;
            }
            p.Party = Settings.Party;
            Phone = p;
            Engine.Phone = p;
            if (Engine.Running) p.SetMirroring(true, Engine.Rate);
        }

        public void StopPhone()
        {
            Engine.Phone = null;
            if (Phone != null) { Phone.Stop(); Phone = null; }
            Phones = new List<PhoneInfo>();
        }

        public void SetPhoneLink(bool on)
        {
            Settings.PhoneLink = on;
            dirty = true;
            if (on) StartPhone(); else StopPhone();
            UpdateStates();
        }

        public void ResetLinkKey()
        {
            Settings.LinkKey = LinkKey.New();
            dirty = true;
            if (Phone != null) Phone.Key = Settings.LinkKey;
            Phones = new List<PhoneInfo>();
            UpdateStates();
        }

        public bool SetPort(int port)
        {
            if (port < 1024 || port > 65535) return false;
            if (port == Settings.PhonePort && Phone != null) return true;   // same port, already running
            Settings.PhonePort = port;
            dirty = true;
            if (Settings.PhoneLink) { StopPhone(); StartPhone(); }   // also retries after a failed start
            UpdateStates();
            return true;
        }

        public bool Party { get { return Settings.Party; } }
        public int ListenerCapacity { get { return Settings.Party ? PhoneLink.MaxClientsParty : PhoneLink.MaxClients; } }

        public void SetParty(bool on)
        {
            Settings.Party = on;
            dirty = true;
            if (Phone != null) Phone.Party = on;
            UpdateStates();
        }

        public void SetPhoneVolume(string id, int volume)
        {
            int v = PhoneLink.ClampVolume(volume);
            if (Phone != null) Phone.SetVolume(id, v);
            foreach (var p in Phones) if (p.Id == id) p.Volume = v;   // keep the card in step until the phone reports back
        }

        public void KickPhone(string id)
        {
            if (Phone != null) { Phone.Kick(id); Phones = Phone.Snapshot(); }
            else if (Demo) Phones.RemoveAll(p => p.Id == id);
            UpdateStates();
        }

        // ------------------------------------------------------------------ firewall (network scope)
        // Changes which Windows network types phones may connect on. Windows shows its permission prompt;
        // done gets the result on the UI thread. The setting is only saved when Windows agreed.
        public void ApplyNetworkScope(string scope, Action<FirewallResult> done)
        {
            if (Demo) { Settings.NetworkScope = scope; dirty = true; if (done != null) done(FirewallResult.Ok); return; }
            string exe = System.Reflection.Assembly.GetEntryAssembly().Location;
            int profiles = Firewall.ProfilesFor(scope);
            RunAsync(() => Firewall.Apply(exe, true, profiles), r =>
            {
                if (r == FirewallResult.Ok)
                {
                    Settings.NetworkScope = scope;
                    dirty = true;
                    MarkInstallerFirewallRule();
                    Log.Info("Firewall rule set to profiles " + profiles);
                }
                if (done != null) done(r);
            });
        }

        // So that uninstalling also removes a rule the app created (the installer only removes rules it knows about).
        static void MarkInstallerFirewallRule()
        {
            try
            {
                using (var k = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Uninstall\DualEarbudsPro", true))
                    if (k != null) k.SetValue("OptFirewall", 1, RegistryValueKind.DWord);
            }
            catch { }
        }

        // Runs work on its own background thread (never the device worker: it may wait on the network or on
        // a permission prompt), then done(result) on the UI thread.
        void RunAsync<T>(Func<T> work, Action<T> done)
        {
            var d = Dispatcher.CurrentDispatcher;
            var t = new Thread(() =>
            {
                T r = default(T);
                try { r = work(); }
                catch (Exception ex) { Log.Error("Background job: " + ex); }
                try { d.BeginInvoke(new Action(() => done(r))); } catch { }
            });
            t.IsBackground = true;
            t.Name = "AppJob";
            t.Start();
        }

        // ------------------------------------------------------------------ updates
        void MaybeAutoCheckUpdates()
        {
            if (Demo || UpdateChecking || UpdateDownloading) return;
            if ((DateTime.Now - startedAt).TotalSeconds < 20) return;   // let the app settle first
            if (!Updater.ShouldAutoCheck(Settings.CheckUpdates, Settings.LastUpdateCheckUtc, DateTime.UtcNow)) return;
            CheckForUpdates(false);
        }

        public void CheckForUpdates(bool manual)
        {
            if (UpdateChecking || UpdateDownloading) return;
            UpdateChecking = true;
            UpdateError = null;
            Settings.LastUpdateCheckUtc = DateTime.UtcNow;   // counts even when offline: at most one automatic try a day
            dirty = true;
            Raise(UpdateChanged);
            if (Demo)
            {
                UpdateChecking = false;
                UpdateResult = new UpdateCheckResult { Status = UpdateStatus.UpToDate, Message = "You have the latest version.", CheckedUtc = DateTime.UtcNow };
                Raise(UpdateChanged);
                return;
            }
            RunAsync(() => Updater.Check(), r =>
            {
                UpdateChecking = false;
                if (r == null) r = new UpdateCheckResult { Status = UpdateStatus.Error, Message = "Couldn't check for updates right now. Try again later.", CheckedUtc = DateTime.UtcNow };
                // a failed automatic check stays quiet: keep showing the last good result
                if (manual || r.Status == UpdateStatus.Available || r.Status == UpdateStatus.UpToDate || UpdateResult == null) UpdateResult = r;
                Log.Info("Update check: " + r.Status + (r.Release != null ? " (latest " + r.Release.Version + ")" : ""));
                if (!manual && r.Status == UpdateStatus.Available && r.Release.Version != notifiedVersion)
                {
                    notifiedVersion = r.Release.Version;
                    var h = UpdateFound;
                    if (h != null) h(r.Release.Version);
                }
                Raise(UpdateChanged);
            });
        }

        // Downloads the installer, starts it in update mode and asks the app to exit.
        public void InstallUpdate(Action exitApp)
        {
            var r = UpdateResult;
            if (UpdateDownloading || r == null || r.Status != UpdateStatus.Available || Demo) return;
            UpdateDownloading = true;
            UpdateProgress = 0;
            UpdateError = null;
            Raise(UpdateChanged);
            var d = Dispatcher.CurrentDispatcher;
            int shown = -2;
            Action<int> progress = pc =>
            {
                if (pc == shown) return;
                shown = pc;
                try { d.BeginInvoke(new Action(() => { UpdateProgress = pc; Raise(UpdateChanged); })); } catch { }
            };
            RunAsync(() =>
            {
                try
                {
                    string path = Updater.Download(r.Release, progress);
                    return Updater.StartInstaller(path) ? null : "The installer couldn't be started. Try again, or download it from the release page.";
                }
                catch (Exception ex)
                {
                    Log.Warn("Update install: " + ex);
                    if (ex is System.IO.IOException || ex is System.IO.InvalidDataException || ex is InvalidOperationException) return ex.Message;
                    return "The update couldn't be downloaded. Try again later.";
                }
            }, err =>
            {
                UpdateDownloading = false;
                if (err == null)
                {
                    Log.Info("Installer started for " + r.Release.Version + " - exiting");
                    exitApp();
                    return;
                }
                UpdateError = err;
                Raise(UpdateChanged);
            });
        }

        public List<string> Addresses
        {
            get { return Demo ? new List<string> { "192.168.1.16" } : PhoneLink.LanAddresses(); }
        }

        public string LinkUrl
        {
            get
            {
                var a = Addresses;
                if (a.Count == 0) return null;
                return "http://" + a[0] + ":" + Settings.PhonePort + "/?k=" + Settings.LinkKey;
            }
        }

        // ------------------------------------------------------------------ status
        public void UpdateStates()
        {
            string src = SourceId;
            int playing = 0, ticked = 0;
            var problems = new List<string>();
            foreach (var o in Outputs)
            {
                o.IsSource = o.Id == src;
                o.Error = null;
                if (o.IsSource) { o.State = "Sound source"; o.Kind = StateKind.Source; }
                else if (!o.Enabled) { o.State = "Off"; o.Kind = StateKind.Off; }
                else
                {
                    ticked++;
                    if (!Running) { o.State = "Ready"; o.Kind = StateKind.Ready; }
                    else if (Demo) { o.State = "Playing"; o.Kind = StateKind.Playing; playing++; }
                    else
                    {
                        var s = Engine.Get(o.Id);
                        if (s != null && s.Running) { o.State = "Playing"; o.Kind = StateKind.Playing; playing++; }
                        else if (s != null && s.Error != null) { o.State = "Can't open"; o.Kind = StateKind.Problem; o.Error = s.Error; problems.Add(o.Name); }
                        else { o.State = "Starting"; o.Kind = StateKind.Starting; }
                    }
                }
            }

            if (DateTime.Now >= errorUntil)
            {
                int phones = Phones.Count;
                if (!Running && !WantRunning) { StatusText = "Stopped. Press Start to mirror your sound."; StatusKind = StateKind.Off; }
                else if (!Running) { /* keep the waiting message from Start() */ }
                else if (ticked == 0 && phones == 0) { StatusText = "Running - tick another output or connect a phone."; StatusKind = StateKind.Ready; }
                else
                {
                    var parts = new List<string>();
                    if (playing > 0) parts.Add(playing + (playing == 1 ? " output" : " outputs"));
                    if (phones > 0) parts.Add(phones + (phones == 1 ? " phone" : " phones"));
                    StatusText = "Mirroring " + NameOf(src) + (parts.Count > 0 ? " to " + string.Join(" and ", parts.ToArray()) : "") + ".";
                    StatusKind = StateKind.Playing;
                    if (problems.Count > 0)
                    {
                        StatusText += " " + string.Join(", ", problems.ToArray()) + (problems.Count == 1 ? " can't" : " can't") + " be opened - use the phone link for it.";
                        StatusKind = StateKind.Problem;
                    }
                }
            }
            Raise(StateChanged);
        }

        void SetInfo(string msg)
        {
            errorUntil = DateTime.MinValue;
            StatusText = msg;
            StatusKind = StateKind.Off;
            Raise(StateChanged);
        }

        void SetError(string msg)
        {
            errorUntil = DateTime.Now.AddSeconds(8);
            StatusText = msg;
            StatusKind = StateKind.Problem;
            Raise(StateChanged);
        }

        static void Raise(Action a) { if (a != null) a(); }

        // ------------------------------------------------------------------ demo data (screenshots)
        public static Controller CreateDemo(bool running)
        {
            var s = new Settings();
            s.LinkKey = "Q7RK2MXD";
            var c = new Controller(s);
            c.Demo = true;
            c.DefaultId = "tws";
            c.AllDevices = new List<DeviceInfo>
            {
                new DeviceInfo { Id = "tws", Name = "Headphones (TWS)" },
                new DeviceInfo { Id = "evo", Name = "Headphones (Evo Z-Buds)" },
                new DeviceInfo { Id = "spk", Name = "Speaker (Realtek(R) Audio)" },
            };
            c.Outputs.Add(new OutputVm { Id = "tws", Name = "Headphones (TWS)", Enabled = true, Battery = 72, Level = 0.62f });
            c.Outputs.Add(new OutputVm { Id = "evo", Name = "Headphones (Evo Z-Buds)", Enabled = true, DelayMs = 120, Volume = 90, Battery = 54, Level = 0.58f });
            c.Outputs.Add(new OutputVm { Id = "spk", Name = "Speaker (Realtek(R) Audio)", Enabled = false });
            c.Phones = new List<PhoneInfo>
            {
                new PhoneInfo { Id = "1", Name = "Redmi Note 12", Client = "android", Earbuds = "Evo Z-Buds", Battery = 54, Volume = 100, BufferMs = 60, Playing = true, RttMs = 14, Remote = "192.168.1.23" },
            };
            c.WantRunning = running;
            c.Level = running ? 0.66f : 0f;
            c.UpdateStates();
            return c;
        }
    }

    public static class StartupReg
    {
        const string Key = @"Software\Microsoft\Windows\CurrentVersion\Run", Name = "DualEarbuds";

        public static bool Get()
        {
            try { using (var k = Registry.CurrentUser.OpenSubKey(Key)) return k != null && k.GetValue(Name) != null; }
            catch { return false; }
        }

        public static void Set(bool on, string exePath)
        {
            using (var k = Registry.CurrentUser.CreateSubKey(Key))
            {
                if (on) k.SetValue(Name, "\"" + exePath + "\" --tray");
                else k.DeleteValue(Name, false);
            }
        }
    }
}
