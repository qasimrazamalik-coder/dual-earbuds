// Dual Earbuds - app logic, independent of the window (also drives screenshot/demo mode).
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
        public OutputEffects Effects = new OutputEffects();
        public double LatencyMs;          // calibrated, 0 = not calibrated
        public double ReportedMs;         // what WASAPI reports for the open stream (0 = unknown)
        public bool IsBluetooth;
        public int ExtraDelayMs;          // common-deadline delay (P - latency) added to the manual delay
        // calibrated, else estimated from WASAPI (+ a Bluetooth guess)
        public double EffectiveLatencyMs { get { return OutputTiming.LatencyMs(LatencyMs, ReportedMs, IsBluetooth); } }
        public bool LatencyMeasured { get { return LatencyMs > 0; } }
        public bool Calibrating;
        public bool ListenTicked;         // plays the other device's sound in listener mode
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
        public event Action ListenChanged;         // listener mode state / found hosts changed

        // ---- one app only
        public List<AppSession> Apps = new List<AppSession>();
        public bool AppsLoading;
        public bool AppSupported { get { return Demo ? DemoAppSupported : AppAudio.Supported; } }
        public bool DemoAppSupported = true;

        // ---- talk-over
        public bool Talking;
        public string TalkError;

        // ---- discovery / hotspot help
        readonly MdnsAdvertiser advertiser = new MdnsAdvertiser();
        string advertSig;
        DateTime phoneStartedAt = DateTime.MinValue;
        bool everHadPhone;

        // ---- listener mode ("Listen to another device")
        public LinkClient Listen;
        MdnsBrowser browser;
        public List<FoundHost> FoundHosts = new List<FoundHost>();
        public string ListenProblem;          // shown under the link box
        public string ListenPin;
        public bool DemoListening;
        public string DemoListenHost = "";
        int listenRate;
        List<ListenerOutput> listenOutputs = new List<ListenerOutput>();
        bool wasMirroringBeforeListen;
        string pendingSuggestion;

        // ---- smart EQ
        string soundPreset, appPreset;
        DateTime lastAppPresetCheck = DateTime.MinValue;

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
            Engine.Talk.Noise = NoiseSetting;
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
            StopListeningNow();
            if (browser != null) { browser.Dispose(); browser = null; }
            Engine.Talk.Close();
            Engine.Stop();
            StopPhone();
            try { advertiser.Dispose(); } catch { }
            Settings.Save();
        }

        // Called about every 2 seconds by the window.
        public void SlowTick()
        {
            if (Demo) return;
            // while sound plays (sharing or listening) the laptop must not go to sleep
            Power.KeepAwake(Running || Listening);
            PowerNow = Power.Read();
            WifiReader.Refresh();
            if ((DateTime.Now - firewallCheckedAt).TotalMinutes >= 5) RefreshFirewallStatus();
            RefreshDevices(false);
            HealthCheck();
            UpdateBatteries();
            if (Phone != null) Phones = Phone.Snapshot();
            if (Phones.Count > 0) everHadPhone = true;
            if (Settings.PhoneLink) NetworkInfo.Refresh();   // background, at most every 30 s
            UpdateAdvert();
            Engine.Talk.Tick();
            UpdateAppPreset();
            if (browser != null) FoundHosts = browser.Hosts;
            if (Listen != null) { Listen.Buffer.Mode = BufferModeSetting; Listen.Buffer.ManualMs = Settings.BufferMs; }
            if (Listen != null || browser != null) Raise(ListenChanged);
            MaybeAutoCheckUpdates();
            UpdateStates();
            if (dirty) { Settings.Save(); dirty = false; }
        }

        // Called about every 50 ms by the window.
        public void FastTick()
        {
            if (Demo) return;
            // P moved (a listener joined, Wi-Fi got worse, an output was ticked): re-line-up the local outputs
            if (PlayoutMs != appliedPlayout || ++alignTicks % 20 == 0) ApplyDelays();
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
        int alignTicks;

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
                    vm = new OutputVm { Id = d.Id, Enabled = s.Enabled, DelayMs = s.DelayMs, Volume = s.Volume, ListenTicked = Settings.ListenOutputs.Contains(d.Id) };
                }
                vm.Name = d.Name;
                vm.LatencyMs = Settings.LatencyFor(d.Name);
                ApplyEffects(vm);
                vm.ContainerId = d.ContainerId;
                Outputs.Add(vm);
            }
            UpdateBatteries();
            foreach (var o in Outputs) o.IsBluetooth = OutputTiming.LooksBluetooth(o.Name, o.Battery >= 0, o.ContainerId);
            ComputeAlignment();
            fxSnapshot = Outputs.Select(o => o.Effects).ToArray();
            if (Listen != null) ReopenListenOutputs();
            UpdateStates();
            Raise(DevicesChanged);
            if (Engine.Running && defChanged && Settings.SourceId == "" && Settings.SourceMode != "app")
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
            if (Listen != null) { wasMirroringBeforeListen = false; StopListening(); }   // a laptop either shares or listens
            string src = Settings.SourceId == "" ? null : Settings.SourceId;
            bool oneApp = Settings.SourceMode == "app" && AppSupported && Settings.SourceApp != "";
            string appName = Settings.SourceApp;
            string srcName = oneApp ? AppAudio.Pretty(appName) : NameOf(src ?? DefaultId);
            var targets = Outputs.Where(o => o.Enabled).ToList();
            foreach (var o in targets) o.LastRetry = DateTime.Now;
            string err = null;
            Background(() =>
            {
                int pid = 0;
                if (oneApp)
                {
                    pid = AppAudio.FindTarget(appName);
                    if (pid < 0) { err = AppAudio.Pretty(appName) + " isn't running - start it, or share the whole laptop"; return; }
                }
                err = Engine.Start(src, pid);
                if (err != null) return;
                StartClassifier();
                foreach (var o in targets)
                    if (o.Enabled && o.Id != Engine.SourceDeviceId) AddStream(o);
            }, () =>
            {
                if (err != null)
                {
                    if (WantRunning) SetError("Waiting for the sound source (" + srcName + "): " + err + ". Trying again every few seconds.");
                }
                else
                {
                    errorUntil = DateTime.MinValue;
                    if (Phone != null) Phone.SetSource(oneApp ? "app" : "all", oneApp ? AppAudio.Pretty(appName) : null);
                }
            });
            UpdateStates();
        }

        // Device worker only.
        void AddStream(OutputVm o)
        {
            var st = Engine.AddOutput(o.Id, o.DelayMs + o.ExtraDelayMs, o.Volume / 100f);
            if (st != null) { st.Effects = o.Effects; if (st.ReportedLatencyMs > 0) o.ReportedMs = st.ReportedLatencyMs; }
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
            Background(() => { if (Engine.Running && o.Enabled) AddStream(o); }, null);
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
            ComputeAlignment();
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
            ApplyDelays();
            foreach (var lo in listenOutputs) if (lo.DeviceId == o.Id) lo.DelayMs = o.DelayMs;
        }

        public void SetVolume(OutputVm o, int volume)
        {
            o.Volume = Math.Max(0, Math.Min(150, volume));
            Settings.For(o.Id).Volume = o.Volume;
            dirty = true;
            var s = Engine.Get(o.Id);
            if (s != null) s.Gain = o.Volume / 100f;
            foreach (var lo in listenOutputs) if (lo.DeviceId == o.Id) lo.Volume = o.Volume / 100f;
        }

        // ------------------------------------------------------------------ common deadline (protocol v3.2)
        // Every ear hears the sample captured at t at t + P. P comes from the phone link (it knows the listeners'
        // needs) or, with the link off, from the local outputs alone. Each ticked local output is delayed by
        // P - its latency; the manual Delay adds on top.
        int appliedPlayout = -1;

        public int LocalNeedMs
        {
            get
            {
                var l = new List<double>();
                foreach (var o in Outputs) if (o.Enabled && !o.IsSource) l.Add(o.EffectiveLatencyMs);
                return OutputTiming.LocalNeedMs(l);
            }
        }

        public int PlayoutMs
        {
            get
            {
                if (Demo) return DemoPlayoutMs;
                var p = Phone;
                return p != null ? p.PlayoutMs : PlayoutPlanner.Choose(null, LocalNeedMs);
            }
        }
        public int DemoPlayoutMs = 214;

        void ComputeAlignment()
        {
            foreach (var o in Outputs)
            {
                var st = Demo ? null : Engine.Get(o.Id);
                if (st != null && st.ReportedLatencyMs > 0) o.ReportedMs = st.ReportedLatencyMs;
            }
            int need = LocalNeedMs;
            var ph = Phone;
            if (ph != null) ph.LocalNeedMs = need;
            int p = PlayoutMs;
            foreach (var o in Outputs)
                o.ExtraDelayMs = o.Enabled && !o.IsSource ? OutputTiming.AutoDelayMs(p, o.EffectiveLatencyMs) : 0;
            appliedPlayout = p;
        }

        void ApplyDelays()
        {
            ComputeAlignment();
            foreach (var o in Outputs)
            {
                var s = Engine.Get(o.Id);
                if (s != null) s.SetDelayMs(o.DelayMs + o.ExtraDelayMs);
            }
            foreach (var lo in listenOutputs)
            {
                var vm = Outputs.Find(x => x.Id == lo.DeviceId);
                if (vm != null) lo.OutputLatencyMs = vm.EffectiveLatencyMs;
            }
        }

        // Plays 8 clicks on the output and listens with the laptop mic. done gets the result on the UI thread.
        public void Calibrate(OutputVm o, Action<Calibration.Result> done)
        {
            if (o.Calibrating) return;
            if (Demo) { o.LatencyMs = 182; done(new Calibration.Result { Ok = true, LatencyMs = 182, Agreeing = 8 }); return; }
            o.Calibrating = true;
            string id = o.Id, name = o.Name;
            UpdateStates();
            RunAsync(() => CalibrationRunner.Run(id, () => false), r =>
            {
                o.Calibrating = false;
                if (r == null) r = new Calibration.Result { Problem = "Calibration failed." };
                if (r.Ok)
                {
                    o.LatencyMs = Math.Round(r.LatencyMs, 1);
                    Settings.LatencyByName[name] = o.LatencyMs;
                    dirty = true;
                    ApplyDelays();
                    Log.Info("Calibrated " + name + ": " + o.LatencyMs + " ms (" + r.Agreeing + "/8 clicks)");
                }
                else Log.Info("Calibration of " + name + ": " + r.Problem);
                done(r);
                UpdateStates();
            });
        }

        public void ForgetCalibration(OutputVm o)
        {
            o.LatencyMs = 0;
            Settings.LatencyByName.Remove(o.Name);
            dirty = true;
            ApplyDelays();
            UpdateStates();
        }

        // ------------------------------------------------------------------ EQ, hearing, dialogue (per laptop output)
        public bool SmartEqOn { get { return Settings.SmartEq != "off"; } }

        void ApplyEffects(OutputVm o)
        {
            var os = Settings.For(o.Id);
            var fx = o.Effects;
            fx.SetManual(os.Eq);
            fx.SetAuto(os.EqAuto && SmartEqOn);
            fx.SetHearing(Settings.FindProfile(os.Profile));
            fx.SetDialogue(DialogueSetting);
            fx.SetAutoPreset(CurrentAutoPreset());
        }

        public void ApplyAllEffects() { foreach (var o in Outputs) ApplyEffects(o); }

        public void SetOutputEq(OutputVm o, string preset)   // "auto" or a preset name
        {
            var os = Settings.For(o.Id);
            if (preset == "auto") os.EqAuto = true;
            else { os.EqAuto = false; if (EqPresets.IsValid(preset)) os.Eq = preset; }
            dirty = true;
            ApplyEffects(o);
            UpdateStates();
        }

        public void SetOutputProfile(OutputVm o, string profile)
        {
            Settings.For(o.Id).Profile = profile ?? "";
            dirty = true;
            ApplyEffects(o);
        }

        public DialogueMode DialogueSetting
        {
            get
            {
                switch (Settings.Dialogue) { case "light": return DialogueMode.Light; case "strong": return DialogueMode.Strong; case "night": return DialogueMode.Night; default: return DialogueMode.Off; }
            }
        }

        public NoiseMode NoiseSetting
        {
            get { return Settings.Noise == "off" ? NoiseMode.Off : Settings.Noise == "light" ? NoiseMode.Light : NoiseMode.Strong; }
        }

        public BufferMode BufferModeSetting
        {
            get { return Settings.Buffer == "steady" ? BufferMode.Steady : Settings.Buffer == "manual" ? BufferMode.Manual : BufferMode.Smart; }
        }

        // Settings -> Smart features changed: apply everything at once, without restarting playback.
        public void SmartSettingsChanged()
        {
            dirty = true;
            Engine.Talk.Noise = NoiseSetting;
            if (Engine.Running) StartClassifier(); else Engine.Classifier = null;
            if (Listen != null)
            {
                Listen.Buffer.Mode = BufferModeSetting;
                Listen.Buffer.ManualMs = Settings.BufferMs;
                Listen.Classifier = WantsSoundClassifier && Listen.Rate > 0 ? NewClassifier(Listen.Rate) : null;
            }
            ApplyAllEffects();
            UpdateStates();
        }

        bool WantsSoundClassifier { get { return Settings.SmartEq == "sound" || Settings.SmartEq == "both"; } }
        bool WantsAppPreset { get { return Settings.SmartEq == "app" || Settings.SmartEq == "both"; } }

        void StartClassifier()
        {
            int r = Engine.Rate;
            Engine.Classifier = WantsSoundClassifier && r > 0 ? NewClassifier(r) : null;
        }

        SoundClassifier NewClassifier(int rate)
        {
            var c = new SoundClassifier(rate);
            // raised on the audio thread: just remember it and update the (volatile) auto presets
            c.Changed += cls => { soundPreset = SoundClassifier.PresetFor(cls); PushAutoPreset(); };
            return c;
        }

        // "Both": the app's opinion wins when it has one, else what the sound sounds like.
        string CurrentAutoPreset()
        {
            if (WantsAppPreset && appPreset != null) return appPreset;
            if (WantsSoundClassifier) return soundPreset;
            return null;
        }

        // Called from the audio thread too: uses a snapshot array, never the live list.
        volatile OutputEffects[] fxSnapshot = new OutputEffects[0];

        void PushAutoPreset()
        {
            string p = CurrentAutoPreset();
            foreach (var fx in fxSnapshot) fx.SetAutoPreset(p);
        }

        public string AutoPresetNow { get { return CurrentAutoPreset(); } }

        void UpdateAppPreset()
        {
            if (!WantsAppPreset || Demo) return;
            if ((DateTime.Now - lastAppPresetCheck).TotalSeconds < 5) return;
            lastAppPresetCheck = DateTime.Now;
            if (Settings.SourceMode == "app" && Settings.SourceApp != "")
            {
                appPreset = SoundClassifier.PresetForApp(Settings.SourceApp);
                PushAutoPreset();
                return;
            }
            // whole laptop: go by the app that is playing right now
            RunAsync(() => AppAudio.Sessions(), list =>
            {
                string pick = null;
                if (list != null) foreach (var a in list) if (a.Active) { pick = SoundClassifier.PresetForApp(a.Process); if (pick != null) break; }
                appPreset = pick;
                PushAutoPreset();
            });
        }

        // ------------------------------------------------------------------ hearing profiles
        public void SaveProfile(HearingProfile p)
        {
            Settings.Profiles.RemoveAll(x => x.Name == p.Name);
            Settings.Profiles.Add(p);
            dirty = true;
            ApplyAllEffects();
        }

        public void DeleteProfile(string name)
        {
            Settings.Profiles.RemoveAll(x => x.Name == name);
            foreach (var kv in Settings.Outs) if (kv.Value.Profile == name) kv.Value.Profile = "";
            dirty = true;
            ApplyAllEffects();
        }

        // Plays one test tone (hearing test) on an output, left or right ear only. Off the UI thread.
        public void PlayTestTone(string outputId, double hz, double dbfs, bool left)
        {
            if (Demo) return;
            var t = new Thread(() =>
            {
                var o = new OutputStream(outputId, 48000, 0, 1f);
                try
                {
                    o.Start();
                    if (!o.Running) return;
                    float[] b = Beeper.Tone(48000, 0.8, hz, (float)Gain.DbToLin(dbfs));
                    for (int i = 0; i < b.Length; i += 2) { if (left) b[i + 1] = 0; else b[i] = 0; }
                    o.Push(b, b.Length / 2);
                    Thread.Sleep(1100);
                }
                finally { o.Stop(); }
            }) { IsBackground = true, Name = "HearingTone" };
            t.Start();
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
            ApplyPhoneOptions(p);
            p.RemoteAction = (id, action) => MediaKeys.Press(action);
            Phone = p;
            Engine.Phone = p;
            if (Engine.Running) p.SetMirroring(true, Engine.Rate);
            phoneStartedAt = DateTime.Now;
            advertSig = null;
        }

        void ApplyPhoneOptions(PhoneLink p)
        {
            if (p == null) return;
            p.Pin = Settings.Party && Settings.PinOn ? Settings.Pin : null;
            p.RemoteControl = Settings.RemoteControl;
            p.SuggestedEq = Settings.SuggestEq == "" ? null : Settings.SuggestEq;
            bool oneApp = Engine.Running && Engine.SourceKind == CaptureKind.App;
            p.SetSource(oneApp ? "app" : "all", oneApp ? AppAudio.Pretty(Settings.SourceApp) : null);
        }

        // ------------------------------------------------------------------ room PIN, remote control, EQ suggestion
        public bool PinActive { get { return Settings.Party && Settings.PinOn && Settings.PhoneLink; } }

        public void SetPinOn(bool on)
        {
            Settings.PinOn = on;
            if (!RoomPin.IsValid(Settings.Pin)) Settings.Pin = RoomPin.New();
            dirty = true;
            ApplyPhoneOptions(Phone);
            advertSig = null;
            UpdateStates();
        }

        public bool SetPin(string pin)
        {
            if (!RoomPin.IsValid(pin)) return false;
            Settings.Pin = pin;
            dirty = true;
            ApplyPhoneOptions(Phone);
            UpdateStates();
            return true;
        }

        public void NewPin() { SetPin(RoomPin.New()); }

        public void SetRemoteControl(bool on)
        {
            Settings.RemoteControl = on;
            dirty = true;
            if (Phone != null) Phone.RemoteControl = on;
            UpdateStates();
        }

        public void SetSuggestEq(string preset)
        {
            Settings.SuggestEq = EqPresets.IsValid(preset) ? preset : "";
            dirty = true;
            if (Phone != null) Phone.SuggestedEq = Settings.SuggestEq == "" ? null : Settings.SuggestEq;
        }

        public void SetShowOnNetwork(bool on)
        {
            Settings.ShowOnNetwork = on;
            dirty = true;
            advertSig = null;
            UpdateAdvert();
        }

        // ------------------------------------------------------------------ sound source (whole laptop / one app)
        public void SetSourceMode(string mode, string app)
        {
            Settings.SourceMode = mode == "app" ? "app" : "all";
            if (app != null) Settings.SourceApp = app;
            dirty = true;
            appPreset = null;
            lastAppPresetCheck = DateTime.MinValue;
            if (Engine.Running || WantRunning) Start(true);
            UpdateStates();
        }

        public void RefreshApps(Action done)
        {
            if (Demo)
            {
                Apps = new List<AppSession>
                {
                    new AppSession { Pid = 1, Process = "Spotify", Title = "Spotify", Active = true },
                    new AppSession { Pid = 2, Process = "chrome", Title = "Google Chrome", Active = true },
                    new AppSession { Pid = 3, Process = "Teams", Title = "Microsoft Teams", Active = false },
                };
                if (done != null) done();
                return;
            }
            if (AppsLoading) return;
            AppsLoading = true;
            RunAsync(() => AppAudio.Sessions(), list =>
            {
                AppsLoading = false;
                Apps = list ?? new List<AppSession>();
                if (done != null) done();
            });
        }

        public string SourceDescription
        {
            get
            {
                if (Settings.SourceMode == "app" && AppSupported && Settings.SourceApp != "") return AppAudio.Pretty(Settings.SourceApp);
                return NameOf(SourceId);
            }
        }

        // ------------------------------------------------------------------ talk-over
        public void SetTalking(bool on)
        {
            if (on == Talking) return;
            Talking = on;
            TalkError = null;
            if (Demo) { UpdateStates(); return; }
            if (!on)
            {
                Engine.Talk.End();
                if (Phone != null) Phone.Talking = false;
                UpdateStates();
                return;
            }
            if (!Engine.Running) { Talking = false; TalkError = "Start mirroring first - talk-over mixes your voice into the shared sound."; UpdateStates(); return; }
            Engine.Talk.Noise = NoiseSetting;
            int rate = Engine.Rate;
            RunAsync(() => Engine.Talk.Begin(rate), ok =>
            {
                if (!ok) { Talking = false; TalkError = Engine.Talk.Error; }
                else if (!Talking) Engine.Talk.End();   // released while the mic was opening
                else if (Phone != null) Phone.Talking = true;
                UpdateStates();
            });
            UpdateStates();
        }

        // ------------------------------------------------------------------ listener mode
        public bool Listening { get { return Demo ? DemoListening : Listen != null; } }

        public ListenState ListenStateNow { get { return Demo ? (DemoListening ? ListenState.Playing : ListenState.Idle) : (Listen == null ? ListenState.Idle : Listen.State); } }

        public string ListenHostName
        {
            get
            {
                if (Demo) return DemoListenHost;
                var l = Listen;
                if (l == null) return "";
                return l.HostName != "" ? l.HostName : l.Target.HostPort;
            }
        }

        // Paste-a-link join. Returns false (with ListenProblem set) when the link can't be used.
        public bool JoinLink(string text, string pin)
        {
            LinkTarget t;
            string why;
            if (!LinkAddress.TryParse(text, out t, out why)) { ListenProblem = why; Raise(ListenChanged); return false; }
            StartListening(t, pin, null);
            return true;
        }

        public void JoinFound(FoundHost h, string pin)
        {
            if (h.Key == null) { ListenProblem = h.Name + " doesn't show its key on this network. Paste its link (or scan its QR code) instead."; Raise(ListenChanged); return; }
            StartListening(new LinkTarget { Host = h.Address, Port = h.Port, Key = h.Key }, pin, h.Name);
        }

        public void JoinRecent(RecentHost r, string pin)
        {
            LinkTarget t;
            string why;
            if (!LinkAddress.TryParse(r.Link, out t, out why)) { ListenProblem = why; Raise(ListenChanged); return; }
            StartListening(t, pin, r.Name);
        }

        void StartListening(LinkTarget t, string pin, string knownName)
        {
            ListenProblem = null;
            ListenPin = pin;
            if (Demo) { DemoListening = true; DemoListenHost = knownName ?? t.HostPort; Raise(ListenChanged); UpdateStates(); return; }
            StopListeningNow();
            // a laptop either shares or listens: playing the other device's sound while capturing this one would echo
            wasMirroringBeforeListen = WantRunning;
            if (Engine.Running || WantRunning) { WantRunning = false; Background(Engine.Stop, null); }
            var c = new LinkClient(t, pin, Environment.MachineName);
            c.Buffer.Mode = BufferModeSetting;
            c.Buffer.ManualMs = Settings.BufferMs;
            c.Earbuds = string.Join(", ", Outputs.Where(o => o.ListenTicked).Select(o => o.Name).ToArray());
            var d = ui ?? Dispatcher.CurrentDispatcher;
            c.Changed += () => { try { d.BeginInvoke(new Action(() => OnListenChanged(c))); } catch { } };
            c.EqSuggested += eq => { try { d.BeginInvoke(new Action(() => { pendingSuggestion = eq; Raise(ListenChanged); })); } catch { } };
            Listen = c;
            listenRate = 0;
            pendingSuggestion = null;
            c.Start();
            Log.Info("Listen to another device: " + t.HostPort);
            Raise(ListenChanged);
            UpdateStates();
        }

        void OnListenChanged(LinkClient c)
        {
            if (c != Listen) return;
            if (c.Rate > 0 && c.Rate != listenRate) { listenRate = c.Rate; ReopenListenOutputs(); }
            if (c.State == ListenState.Playing || c.State == ListenState.HostPaused)
            {
                var t = c.Target;
                bool known = Settings.Recent.Count > 0 && Settings.Recent[0].HostPort == t.HostPort && Settings.Recent[0].Key == t.Key && Settings.Recent[0].Name == c.HostName;
                if (!known) { Settings.AddRecent(t.HostPort, t.Key, c.HostName); dirty = true; }
            }
            Raise(ListenChanged);
            UpdateStates();
        }

        public string ListenSuggestion { get { return pendingSuggestion; } }

        public void AcceptSuggestion()
        {
            string eq = pendingSuggestion;
            pendingSuggestion = null;
            if (eq != null) foreach (var o in Outputs) if (o.ListenTicked) SetOutputEq(o, eq);
            Raise(ListenChanged);
        }

        public void DismissSuggestion() { pendingSuggestion = null; Raise(ListenChanged); }

        public void StopListening()
        {
            if (Demo) { DemoListening = false; Raise(ListenChanged); UpdateStates(); return; }
            if (Listen == null) return;
            StopListeningNow();
            Raise(ListenChanged);
            UpdateStates();
            if (wasMirroringBeforeListen) { wasMirroringBeforeListen = false; Start(true); }
        }

        void StopListeningNow()
        {
            var c = Listen;
            Listen = null;
            var outs = listenOutputs;
            listenOutputs = new List<ListenerOutput>();
            if (c == null && outs.Count == 0) return;
            // joins network threads and closes devices: never on the UI thread
            var t = new Thread(() =>
            {
                try { if (c != null) c.Dispose(); } catch (Exception ex) { Log.Warn("Stop listening: " + ex.Message); }
                foreach (var o in outs) o.Stream.Stop();
            }) { IsBackground = true, Name = "ListenStop" };
            t.SetApartmentState(ApartmentState.MTA);
            t.Start();
            if (ui == null) t.Join(5000);   // shutdown / tests
        }

        public void SetListenTicked(OutputVm o, bool on)
        {
            o.ListenTicked = on;
            Settings.ListenOutputs.Remove(o.Id);
            if (on) Settings.ListenOutputs.Add(o.Id);
            dirty = true;
            if (Listen != null)
            {
                Listen.Earbuds = string.Join(", ", Outputs.Where(x => x.ListenTicked).Select(x => x.Name).ToArray());
                ReopenListenOutputs();
            }
            Raise(ListenChanged);
        }

        // Opens the ticked outputs at the stream's rate on the device worker, then hands them to the client.
        void ReopenListenOutputs()
        {
            var c = Listen;
            if (c == null || listenRate <= 0) return;
            int rate = listenRate;
            var want = Outputs.Where(o => o.ListenTicked).ToList();
            var old = listenOutputs;
            List<ListenerOutput> result = null;
            Background(() =>
            {
                var keep = new List<ListenerOutput>();
                foreach (var lo in old)
                {
                    bool still = want.Any(w => w.Id == lo.DeviceId) && lo.Stream.Rate == rate && lo.Stream.Running;
                    if (still) keep.Add(lo); else lo.Stream.Stop();
                }
                foreach (var w in want)
                {
                    if (keep.Any(k => k.DeviceId == w.Id)) continue;
                    var st = new OutputStream(w.Id, rate, 0, w.Volume / 100f);
                    st.Effects = w.Effects;
                    st.Start();
                    if (!st.Running) { Log.Warn("Listen output " + w.Name + ": " + st.Error); st.Stop(); continue; }
                    if (st.ReportedLatencyMs > 0) w.ReportedMs = st.ReportedLatencyMs;
                    keep.Add(new ListenerOutput(w.Id, w.Name, st) { DelayMs = w.DelayMs, Volume = w.Volume / 100f, OutputLatencyMs = w.EffectiveLatencyMs });
                }
                if (Listen == c) { c.SetOutputs(keep); result = keep; }
                else foreach (var k in keep) k.Stream.Stop();
            }, () =>
            {
                if (result != null && Listen == c) listenOutputs = result;
                else if (result != null) foreach (var k in result) k.Stream.Stop();
                Raise(ListenChanged);
            });
        }

        public string ListenOutputState(OutputVm o)
        {
            if (!o.ListenTicked) return null;
            if (Demo) return DemoListening ? "Playing" : null;
            var lo = listenOutputs.Find(x => x.DeviceId == o.Id);
            if (Listen == null) return null;
            if (lo == null) return listenRate > 0 ? "Can't open" : "Waiting";
            return "Playing";
        }

        public void StartBrowsing()
        {
            if (Demo)
            {
                FoundHosts = new List<FoundHost>
                {
                    new FoundHost { Name = "Sara's laptop", Address = "192.168.1.31", Port = 8790, Key = "M4TQ7ZPA", HostKind = "windows" },
                    new FoundHost { Name = "Pixel 8 (party)", Address = "192.168.1.44", Port = 8790, Key = null, Pin = true, HostKind = "android" },
                };
                return;
            }
            if (browser != null) { browser.Refresh(); return; }
            var b = new MdnsBrowser();
            if (Phone != null) { string ip = PhoneLink.LanAddress(); if (ip != null) b.IgnorePort = ip + ":" + Settings.PhonePort; }
            if (b.Start()) browser = b;
        }

        public void StopBrowsing()
        {
            var b = browser;
            browser = null;
            if (b != null) RunAsync(() => { b.Dispose(); return true; }, r => { });
        }

        public void ListenRemote(string action)
        {
            var c = Listen;
            if (c != null && !c.Remote(action)) { ListenProblem = "The other device doesn't allow its listeners to control playback."; Raise(ListenChanged); }
        }

        // ------------------------------------------------------------------ setup check
        public PowerState PowerNow;
        public FirewallStatus FirewallNow;
        public WifiInfo DemoWifi;
        DateTime firewallCheckedAt = DateTime.MinValue;
        bool firewallBusy;
        public event Action ChecksChanged;

        public void RefreshFirewallStatus()
        {
            firewallCheckedAt = DateTime.Now;
            if (Demo || firewallBusy) return;
            firewallBusy = true;
            string exe = System.Reflection.Assembly.GetEntryAssembly().Location;
            RunAsync(() => FirewallCheck.Query(exe), st => { firewallBusy = false; FirewallNow = st; Raise(ChecksChanged); });
        }

        public WifiInfo Wifi { get { return Demo ? DemoWifi : WifiReader.Last; } }

        public CheckInput CheckSnapshot()
        {
            var x = new CheckInput
            {
                HostMode = !Listening,
                NetworkCategory = NetworkCategory,
                NetworkScope = Settings.NetworkScope,
                Firewall = FirewallNow,
                Power = PowerNow,
                Wifi = Wifi,
                PlayoutMs = PlayoutMs,
            };
            foreach (var o in Outputs)
            {
                if (o.IsSource) continue;
                bool ticked = Listening ? o.ListenTicked : o.Enabled;
                x.Outputs.Add(new CheckOutput { Id = o.Id, Name = o.Name, Bluetooth = o.IsBluetooth, Enabled = ticked, Calibrated = o.LatencyMeasured, LatencyMs = o.EffectiveLatencyMs });
            }
            foreach (var p in Phones) x.Listeners.Add(new CheckListener { Name = p.Name, Client = p.Client, NeedMs = p.NeedMs, Synced = p.Synced, Transport = p.Transport });
            return x;
        }

        public List<CheckItem> Checks() { return SetupCheck.Build(CheckSnapshot()); }

        // The fix buttons of Setup check that open Windows settings pages (the firewall one runs ApplyNetworkScope).
        public static void OpenSettingsPage(string actionId)
        {
            string uri = null;
            switch (actionId)
            {
                case "network": uri = "ms-settings:network-status"; break;
                case "wifi": uri = "ms-settings:network-wifi"; break;
                case "bluetooth": uri = "ms-settings:bluetooth"; break;
                case "battery": uri = "ms-settings:batterysaver"; break;
                case "power": uri = "ms-settings:powersleep"; break;
            }
            if (uri == null) return;
            try { System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(uri) { UseShellExecute = true }); }
            catch (Exception ex) { Log.Warn("Open " + uri + ": " + ex.Message); }
        }

        // ------------------------------------------------------------------ diagnostics
        public string DiagnosticsText()
        {
            var lines = Outputs.Select(o => string.Format(System.Globalization.CultureInfo.InvariantCulture,
                "{0}{1} | {2} | delay {3}+{4} ms | volume {5}% | latency {6} | EQ {7}{8} | profile {9}",
                o.Name, o.IsSource ? " (source)" : "", o.State, o.DelayMs, o.ExtraDelayMs, o.Volume,
                o.LatencyMs > 0 ? o.LatencyMs + " ms" : "not calibrated", o.Effects.EffectivePreset, o.Effects.Auto ? " (auto)" : "",
                o.Effects.Hearing == null ? "none" : "set")).ToList();
            return DiagReport.Summary(Engine, Phone, Settings, Listen, lines);
        }

        // ------------------------------------------------------------------ discovery + hotspot help
        bool AdvertiseKey
        {
            get
            {
                string c = NetworkCategory;
                return Settings.ShowOnNetwork || c == "Private" || c == "DomainAuthenticated";
            }
        }

        void UpdateAdvert()
        {
            if (Demo) return;
            if (Phone == null || !Settings.PhoneLink)
            {
                if (advertSig != "off") { advertSig = "off"; RunAsync(() => { advertiser.Stop(); return true; }, r => { }); }
                return;
            }
            string ip = PhoneLink.LanAddress();
            bool pin = PinActive;
            string key = AdvertiseKey ? Settings.LinkKey : null;
            string sig = ip + "|" + Settings.PhonePort + "|" + pin + "|" + key + "|" + Phone.PcName;
            if (sig == advertSig) return;
            advertSig = sig;
            var txt = DnsSd.HostTxt(Phone.PcName, AppInfo.Host, pin, key);
            int port = Settings.PhonePort;
            string name = Phone.PcName;
            IPAddressHolder holder = new IPAddressHolder(ip);
            RunAsync(() => { advertiser.Advertise(name, port, txt, holder.Address); return true; }, r => { });
        }

        class IPAddressHolder
        {
            public readonly System.Net.IPAddress Address;
            public IPAddressHolder(string ip) { System.Net.IPAddress a; Address = ip != null && System.Net.IPAddress.TryParse(ip, out a) ? a : null; }
        }

        public string AdvertMethod { get { return advertiser.Method; } }

        // The hotspot card: on a public network, or when no phone joined within 60 s.
        public bool ShowHotspotHelp
        {
            get
            {
                if (!Settings.PhoneLink) return false;
                if (NetworkCategory == "Public") return true;
                if (Demo) return false;
                return Phone != null && !everHadPhone && Phones.Count == 0 && phoneStartedAt != DateTime.MinValue && (DateTime.Now - phoneStartedAt).TotalSeconds >= 60;
            }
        }

        public static void OpenHotspotSettings()
        {
            try { System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("ms-settings:network-mobilehotspot") { UseShellExecute = true }); }
            catch (Exception ex) { Log.Warn("Open hotspot settings: " + ex.Message); }
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
            ApplyPhoneOptions(Phone);   // the room PIN only applies in Party mode
            advertSig = null;
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
                RefreshFirewallStatus();
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

            if (Listening && DateTime.Now >= errorUntil)
            {
                var ls = ListenStateNow;
                StatusKind = ls == ListenState.Playing ? StateKind.Playing : ls == ListenState.Connecting || ls == ListenState.Reconnecting || ls == ListenState.HostPaused ? StateKind.Starting : StateKind.Problem;
                StatusText = ls == ListenState.Playing ? "Listening to " + ListenHostName + "." : ListenText(ls);
            }
            else if (DateTime.Now >= errorUntil)
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

        public string ListenText(ListenState ls)
        {
            switch (ls)
            {
                case ListenState.Connecting: return "Connecting to " + ListenHostName + "...";
                case ListenState.Reconnecting: return "Reconnecting to " + ListenHostName + "... " + (Listen != null ? Listen.Detail : "");
                case ListenState.Playing: return "Listening to " + ListenHostName + (Listen != null && Listen.Synced ? " · in step" : "") + ".";
                case ListenState.HostPaused: return ListenHostName + " isn't sharing sound right now. It starts by itself when they press Start.";
                case ListenState.NeedPin: return "This room needs a PIN. Ask the host - it's on their Phones page.";
                case ListenState.WrongPin: return "That PIN didn't work. Check it with the host and try again.";
                case ListenState.WrongKey: return "The link's key is out of date (the host made a new link). Ask for the new link.";
                case ListenState.Full: return "The host already has as many listeners as it allows. Trying again...";
                case ListenState.Kicked: return "The host removed this laptop.";
                case ListenState.Unreachable: return "Can't reach the other device.";
                default: return "Not listening.";
            }
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
            s.Pin = "4711";
            s.Recent.Add(new RecentHost { HostPort = "192.168.1.31:8790", Key = "M4TQ7ZPA", Name = "Sara's laptop" });
            s.Recent.Add(new RecentHost { HostPort = "192.168.1.50:8790", Key = "K2LPQ6RT", Name = "Office PC" });
            s.Profiles.Add(new HearingProfile { Name = "Ammi", LeftDb = new double[] { 0, 0, 0, 2, 5, 7.5, 9 }, RightDb = new double[] { 0, 0, 0, 1.5, 4, 6, 8 } });
            c.Outputs[0].LatencyMs = 182;
            c.Outputs[1].LatencyMs = 214;
            c.Outputs[1].ListenTicked = true;
            c.Outputs[0].ListenTicked = true;
            c.WantRunning = running;
            c.Level = running ? 0.66f : 0f;
            foreach (var o in c.Outputs) o.IsBluetooth = o.Id != "spk";
            c.PowerNow = new PowerState { OnBattery = true, BatterySaver = false, BatteryPercent = 64, Mode = PowerMode.BetterPerformance };
            c.FirewallNow = new FirewallStatus { Known = true, Tcp = true, Udp = true, Profiles = 6 };
            c.DemoWifi = new WifiInfo { Connected = true, Ssid = "HomeNet", RadioType = "802.11ax", Channel = 44, Band = "5 GHz", SignalPercent = 84 };
            c.Phones[0].NeedMs = 168; c.Phones[0].Synced = true; c.Phones[0].Transport = "udp"; c.Phones[0].OffsetMs = 2;
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
