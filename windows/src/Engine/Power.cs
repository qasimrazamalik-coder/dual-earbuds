// Dual Earbuds - keeping sound smooth on a laptop: real-time thread priority (MMCSS "Pro Audio"), no sleep
// while sharing or listening, no EcoQoS throttling, and what battery saver / power mode are doing.
// Every Windows call is optional: on failure (or under Mono in the tests) it quietly does nothing.
using System;
using System.Runtime.InteropServices;

namespace DualEarbuds
{
    // MMCSS: the audio threads get the scheduler class Windows' own audio engine uses, so a busy CPU
    // (or a browser tab) can't make them miss a 10 ms deadline.
    public static class Realtime
    {
        [DllImport("avrt.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        static extern IntPtr AvSetMmThreadCharacteristicsW(string taskName, ref uint taskIndex);
        [DllImport("avrt.dll", SetLastError = true)]
        static extern bool AvRevertMmThreadCharacteristics(IntPtr handle);

        static volatile bool unavailable;

        // Call at the start of an audio thread; pass the result to Leave when the thread ends.
        public static IntPtr Enter(string task)
        {
            if (unavailable || Environment.OSVersion.Platform != PlatformID.Win32NT) return IntPtr.Zero;
            try
            {
                uint idx = 0;
                return AvSetMmThreadCharacteristicsW(task ?? "Pro Audio", ref idx);
            }
            catch (Exception) { unavailable = true; return IntPtr.Zero; }
        }

        public static void Leave(IntPtr h)
        {
            if (h == IntPtr.Zero) return;
            try { AvRevertMmThreadCharacteristics(h); } catch (Exception) { }
        }
    }

    public enum PowerMode { Unknown, BestEfficiency, Balanced, BetterPerformance, BestPerformance }

    public class PowerState
    {
        public bool OnBattery;
        public bool BatterySaver;
        public int BatteryPercent = -1;
        public PowerMode Mode = PowerMode.Unknown;
    }

    public static class Power
    {
        const uint ES_CONTINUOUS = 0x80000000, ES_SYSTEM_REQUIRED = 0x00000001;
        [DllImport("kernel32.dll")] static extern uint SetThreadExecutionState(uint flags);

        [StructLayout(LayoutKind.Sequential)]
        struct PROCESS_POWER_THROTTLING_STATE { public uint Version, ControlMask, StateMask; }
        const int ProcessPowerThrottling = 4;
        const uint THROTTLING_EXECUTION_SPEED = 0x1, THROTTLING_IGNORE_TIMER_RESOLUTION = 0x4;
        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool SetProcessInformation(IntPtr process, int infoClass, ref PROCESS_POWER_THROTTLING_STATE info, uint size);
        [DllImport("kernel32.dll")] static extern IntPtr GetCurrentProcess();

        [StructLayout(LayoutKind.Sequential)]
        struct SYSTEM_POWER_STATUS { public byte ACLineStatus, BatteryFlag, BatteryLifePercent, SystemStatusFlag; public uint BatteryLifeTime, BatteryFullLifeTime; }
        [DllImport("kernel32.dll")] static extern bool GetSystemPowerStatus(out SYSTEM_POWER_STATUS s);

        [DllImport("powrprof.dll")] static extern uint PowerGetEffectiveOverlayScheme(out Guid overlay);

        static bool Windows { get { return Environment.OSVersion.Platform == PlatformID.Win32NT; } }
        static bool awake;

        // While sharing or listening, Windows must not sleep (the screen may still turn off).
        // The flag belongs to the calling thread: call it from the UI thread, which lives as long as the app.
        public static void KeepAwake(bool on)
        {
            if (on == awake || !Windows) return;
            try
            {
                SetThreadExecutionState(on ? ES_CONTINUOUS | ES_SYSTEM_REQUIRED : ES_CONTINUOUS);
                awake = on;
                Log.Info(on ? "Keeping the laptop awake while sound plays" : "Sleep allowed again");
            }
            catch (Exception) { }
        }

        public static bool Awake { get { return awake; } }

        // EcoQoS / power throttling slows down background processes on battery and, on Windows 11, ignores
        // the 1 ms timer request of a minimized app. Opt out of both: sound must keep its 10 ms rhythm.
        public static bool OptOutOfThrottling()
        {
            if (!Windows) return false;
            try
            {
                var s = new PROCESS_POWER_THROTTLING_STATE
                {
                    Version = 1,
                    ControlMask = THROTTLING_EXECUTION_SPEED | THROTTLING_IGNORE_TIMER_RESOLUTION,
                    StateMask = 0   // control these, and turn them off
                };
                bool ok = SetProcessInformation(GetCurrentProcess(), ProcessPowerThrottling, ref s, (uint)Marshal.SizeOf(typeof(PROCESS_POWER_THROTTLING_STATE)));
                if (!ok)
                {
                    // older Windows 10 doesn't know the timer flag: try the speed flag alone
                    s.ControlMask = THROTTLING_EXECUTION_SPEED;
                    ok = SetProcessInformation(GetCurrentProcess(), ProcessPowerThrottling, ref s, (uint)Marshal.SizeOf(typeof(PROCESS_POWER_THROTTLING_STATE)));
                }
                Log.Info(ok ? "Power throttling (EcoQoS) off for this app" : "Power throttling opt-out not available on this Windows");
                return ok;
            }
            catch (Exception) { return false; }
        }

        public static PowerState Read()
        {
            var st = new PowerState();
            if (!Windows) return st;
            try
            {
                SYSTEM_POWER_STATUS s;
                if (GetSystemPowerStatus(out s))
                {
                    st.OnBattery = s.ACLineStatus == 0;
                    st.BatterySaver = s.SystemStatusFlag == 1;
                    st.BatteryPercent = s.BatteryLifePercent <= 100 ? s.BatteryLifePercent : -1;
                }
            }
            catch (Exception) { }
            try
            {
                Guid g;
                if (PowerGetEffectiveOverlayScheme(out g) == 0) st.Mode = ModeFor(g);
            }
            catch (Exception) { }
            return st;
        }

        // Windows 10/11 "Power mode" slider overlays.
        public static readonly Guid OverlayBestEfficiency = new Guid("961cc777-2547-4f9d-8174-7d86181b8a7a");
        public static readonly Guid OverlayBetterBattery = new Guid("3af9b8d9-7c97-431d-ad78-34a8bfea439f");
        public static readonly Guid OverlayBetterPerformance = new Guid("00000000-0000-0000-0000-000000000000");
        public static readonly Guid OverlayBestPerformance = new Guid("ded574b5-45a0-4f42-8737-46345c09c238");

        public static PowerMode ModeFor(Guid overlay)
        {
            if (overlay == OverlayBestEfficiency) return PowerMode.BestEfficiency;
            if (overlay == OverlayBetterBattery) return PowerMode.Balanced;
            if (overlay == OverlayBetterPerformance) return PowerMode.BetterPerformance;
            if (overlay == OverlayBestPerformance) return PowerMode.BestPerformance;
            return PowerMode.Unknown;
        }

        public static string Describe(PowerMode m)
        {
            switch (m)
            {
                case PowerMode.BestEfficiency: return "Best power efficiency";
                case PowerMode.Balanced: return "Balanced (battery)";
                case PowerMode.BetterPerformance: return "Balanced";
                case PowerMode.BestPerformance: return "Best performance";
                default: return "Unknown";
            }
        }
    }
}
