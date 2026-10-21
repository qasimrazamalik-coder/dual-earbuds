// Dual Earbuds - "One app only": which apps are playing sound right now (IAudioSessionManager2) and
// process loopback capture of one app and its child processes (ActivateAudioInterfaceAsync with
// AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK, Windows 10 build 20348 / Windows 11 and later).
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Threading;

namespace DualEarbuds
{
    [ComImport, Guid("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioSessionManager2
    {
        [PreserveSig] int GetAudioSessionControl(IntPtr groupingParam, uint flags, out IntPtr control);
        [PreserveSig] int GetSimpleAudioVolume(IntPtr groupingParam, uint flags, out IntPtr volume);
        [PreserveSig] int GetSessionEnumerator(out IAudioSessionEnumerator enumerator);
        [PreserveSig] int RegisterSessionNotification(IntPtr n);
        [PreserveSig] int UnregisterSessionNotification(IntPtr n);
        [PreserveSig] int RegisterDuckNotification(IntPtr sessionId, IntPtr n);
        [PreserveSig] int UnregisterDuckNotification(IntPtr n);
    }

    [ComImport, Guid("E2F5BB11-0570-40CA-ACDD-3AA01277DEE8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioSessionEnumerator
    {
        [PreserveSig] int GetCount(out int count);
        [PreserveSig] int GetSession(int index, out IAudioSessionControl2 session);
    }

    [ComImport, Guid("bfb7ff88-7239-4fc9-8fa2-07c950be9c6d"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioSessionControl2
    {
        // IAudioSessionControl
        [PreserveSig] int GetState(out int state);
        [PreserveSig] int GetDisplayName([MarshalAs(UnmanagedType.LPWStr)] out string name);
        [PreserveSig] int SetDisplayName([MarshalAs(UnmanagedType.LPWStr)] string name, IntPtr ctx);
        [PreserveSig] int GetIconPath([MarshalAs(UnmanagedType.LPWStr)] out string path);
        [PreserveSig] int SetIconPath([MarshalAs(UnmanagedType.LPWStr)] string path, IntPtr ctx);
        [PreserveSig] int GetGroupingParam(out Guid g);
        [PreserveSig] int SetGroupingParam(ref Guid g, IntPtr ctx);
        [PreserveSig] int RegisterAudioSessionNotification(IntPtr n);
        [PreserveSig] int UnregisterAudioSessionNotification(IntPtr n);
        // IAudioSessionControl2
        [PreserveSig] int GetSessionIdentifier([MarshalAs(UnmanagedType.LPWStr)] out string id);
        [PreserveSig] int GetSessionInstanceIdentifier([MarshalAs(UnmanagedType.LPWStr)] out string id);
        [PreserveSig] int GetProcessId(out uint pid);
        [PreserveSig] int IsSystemSoundsSession();
        [PreserveSig] int SetDuckingPreference(bool optOut);
    }

    [ComImport, Guid("41D949AB-9862-444A-80F6-C261334DA5EB"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IActivateAudioInterfaceCompletionHandler
    {
        void ActivateCompleted(IActivateAudioInterfaceAsyncOperation op);
    }

    [ComImport, Guid("72A22D78-CDE4-431D-B8CC-843A71199B6D"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IActivateAudioInterfaceAsyncOperation
    {
        void GetActivateResult(out int hr, [MarshalAs(UnmanagedType.IUnknown)] out object iface);
    }

    // Marker: the completion handler may be called on any thread.
    [ComImport, Guid("94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAgileObject { }

    public class AppSession
    {
        public int Pid;
        public string Process;       // "Spotify" (no .exe)
        public string Title;         // what to show
        public bool Active;          // playing right now
    }

    public static class AppAudio
    {
        public const int MinBuild = 20348;
        static readonly Guid IID_SessionManager2 = new Guid("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F");

        public static bool Supported { get { return OsInfo.IsWindows && OsInfo.Build >= MinBuild; } }

        public static string WhyNot
        {
            get
            {
                return Supported ? null : "Sharing one app's sound needs Windows 11 (or Windows 10 build " + MinBuild + " and later). This PC has " + OsInfo.Describe() + " - Dual Earbuds shares the whole laptop's sound instead.";
            }
        }

        // Apps with an audio session on the default output, playing ones first. MTA thread; takes ~10 ms.
        public static List<AppSession> Sessions()
        {
            var list = new List<AppSession>();
            IMMDeviceEnumerator en = null;
            IMMDevice dev = null;
            IAudioSessionManager2 mgr = null;
            IAudioSessionEnumerator se = null;
            try
            {
                en = Wasapi.NewEnumerator();
                Wasapi.Check(en.GetDefaultAudioEndpoint(EDataFlow.eRender, ERole.eMultimedia, out dev), "Find default output");
                Guid iid = IID_SessionManager2;
                object o;
                Wasapi.Check(dev.Activate(ref iid, Wasapi.CLSCTX_ALL, IntPtr.Zero, out o), "Open sessions");
                mgr = (IAudioSessionManager2)o;
                Wasapi.Check(mgr.GetSessionEnumerator(out se), "List sessions");
                int n;
                se.GetCount(out n);
                int self = Process.GetCurrentProcess().Id;
                for (int i = 0; i < n; i++)
                {
                    IAudioSessionControl2 c;
                    if (se.GetSession(i, out c) < 0 || c == null) continue;
                    try
                    {
                        if (c.IsSystemSoundsSession() == 0) continue;   // S_OK = system sounds
                        uint pid;
                        if (c.GetProcessId(out pid) < 0 || pid == 0 || pid == self) continue;
                        int state;
                        c.GetState(out state);
                        string name = ProcessName((int)pid);
                        if (name == null) continue;
                        string title;
                        c.GetDisplayName(out title);
                        if (string.IsNullOrEmpty(title) || title.StartsWith("@")) title = Pretty(name);
                        var existing = list.Find(a => string.Equals(a.Process, name, StringComparison.OrdinalIgnoreCase));
                        if (existing != null) { existing.Active |= state == 1; continue; }
                        list.Add(new AppSession { Pid = (int)pid, Process = name, Title = title, Active = state == 1 });
                    }
                    finally { Wasapi.Release(c); }
                }
            }
            catch (Exception ex) { Log.Warn("App list: " + ex.Message); }
            finally { Wasapi.Release(se); Wasapi.Release(mgr); Wasapi.Release(dev); Wasapi.Release(en); }
            list.Sort((a, b) => a.Active != b.Active ? (a.Active ? -1 : 1) : string.Compare(a.Title, b.Title, StringComparison.CurrentCultureIgnoreCase));
            return list;
        }

        static string ProcessName(int pid)
        {
            try { using (var p = Process.GetProcessById(pid)) return p.ProcessName; }
            catch { return null; }
        }

        public static string Pretty(string process)
        {
            if (string.IsNullOrEmpty(process)) return "App";
            string p = process;
            if (p.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)) p = p.Substring(0, p.Length - 4);
            return p.Length > 0 ? char.ToUpperInvariant(p[0]) + p.Substring(1) : "App";
        }

        // The process to capture for an app name: the top-most process of that name (browsers play sound from
        // a child process; capturing the parent's tree includes every tab). -1 when it isn't running.
        public static int FindTarget(string processName)
        {
            if (string.IsNullOrEmpty(processName)) return -1;
            string n = processName.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) ? processName.Substring(0, processName.Length - 4) : processName;
            Process[] ps;
            try { ps = Process.GetProcessesByName(n); }
            catch { return -1; }
            try
            {
                if (ps.Length == 0) return -1;
                var ids = new HashSet<int>();
                foreach (var p in ps) ids.Add(p.Id);
                var parents = ParentMap();
                foreach (var p in ps)
                {
                    int parent;
                    if (!parents.TryGetValue(p.Id, out parent) || !ids.Contains(parent)) return p.Id;
                }
                return ps[0].Id;
            }
            finally { foreach (var p in ps) p.Dispose(); }
        }

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        struct PROCESSENTRY32
        {
            public uint dwSize, cntUsage, th32ProcessID;
            public IntPtr th32DefaultHeapID;
            public uint th32ModuleID, cntThreads, th32ParentProcessID;
            public int pcPriClassBase;
            public uint dwFlags;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 260)] public string szExeFile;
        }

        [DllImport("kernel32.dll", SetLastError = true)] static extern IntPtr CreateToolhelp32Snapshot(uint flags, uint pid);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern bool Process32FirstW(IntPtr snap, ref PROCESSENTRY32 e);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern bool Process32NextW(IntPtr snap, ref PROCESSENTRY32 e);
        [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr h);

        static Dictionary<int, int> ParentMap()
        {
            var d = new Dictionary<int, int>();
            IntPtr snap = IntPtr.Zero;
            try
            {
                snap = CreateToolhelp32Snapshot(2, 0);   // TH32CS_SNAPPROCESS
                if (snap == IntPtr.Zero || snap == new IntPtr(-1)) return d;
                var e = new PROCESSENTRY32 { dwSize = (uint)Marshal.SizeOf(typeof(PROCESSENTRY32)) };
                if (Process32FirstW(snap, ref e))
                    do { d[(int)e.th32ProcessID] = (int)e.th32ParentProcessID; } while (Process32NextW(snap, ref e));
            }
            catch { }
            finally { if (snap != IntPtr.Zero && snap != new IntPtr(-1)) CloseHandle(snap); }
            return d;
        }

        // ------------------------------------------------------------------ process loopback activation
        [DllImport("Mmdevapi.dll", ExactSpelling = true, PreserveSig = true)]
        static extern int ActivateAudioInterfaceAsync([MarshalAs(UnmanagedType.LPWStr)] string path, [MarshalAs(UnmanagedType.LPStruct)] Guid riid,
                                                      IntPtr activationParams, IActivateAudioInterfaceCompletionHandler handler,
                                                      out IActivateAudioInterfaceAsyncOperation op);

        const string VirtualDevice = "VAD\\Process_Loopback";

        [ComVisible(true)]
        class Completion : IActivateAudioInterfaceCompletionHandler, IAgileObject
        {
            public readonly ManualResetEvent Done = new ManualResetEvent(false);
            public int Hr = unchecked((int)0x80004005);
            public object Client;

            public void ActivateCompleted(IActivateAudioInterfaceAsyncOperation op)
            {
                try
                {
                    int hr;
                    object o;
                    op.GetActivateResult(out hr, out o);
                    Hr = hr;
                    Client = o;
                }
                catch (Exception ex) { Hr = ex.HResult; }
                finally { Done.Set(); }
            }
        }

        // An audio client that captures pid and its child processes. Call from an MTA thread.
        internal static IAudioClient ActivateProcessLoopback(int pid)
        {
            if (!Supported) throw new NotSupportedException(WhyNot);
            int ptr = IntPtr.Size;
            IntPtr parms = Marshal.AllocCoTaskMem(12);            // AUDIOCLIENT_ACTIVATION_PARAMS
            IntPtr pv = Marshal.AllocCoTaskMem(8 + ptr * 2);      // PROPVARIANT holding a BLOB
            var done = new Completion();
            IActivateAudioInterfaceAsyncOperation op = null;
            try
            {
                Marshal.WriteInt32(parms, 0, 1);                  // AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK
                Marshal.WriteInt32(parms, 4, pid);                // TargetProcessId
                Marshal.WriteInt32(parms, 8, 0);                  // PROCESS_LOOPBACK_MODE_INCLUDE_TARGET_PROCESS_TREE
                for (int i = 0; i < 8 + ptr * 2; i += 4) Marshal.WriteInt32(pv, i, 0);
                Marshal.WriteInt16(pv, 0, 65);                    // VT_BLOB
                Marshal.WriteInt32(pv, 8, 12);                    // blob.cbSize
                Marshal.WriteIntPtr(pv, 8 + ptr, parms);          // blob.pBlobData (pointer-aligned)
                int hr = ActivateAudioInterfaceAsync(VirtualDevice, Wasapi.IID_AudioClient, pv, done, out op);
                Wasapi.Check(hr, "Open the app's sound");
                if (!done.Done.WaitOne(5000)) throw new TimeoutException("Windows didn't open the app's sound in time");
                Wasapi.Check(done.Hr, "Open the app's sound");
                return (IAudioClient)done.Client;
            }
            finally
            {
                Wasapi.Release(op);
                // if Windows never answered it may still read these: leave them (a few bytes) rather than crash
                if (done.Done.WaitOne(0))
                {
                    done.Done.Close();
                    Marshal.FreeCoTaskMem(pv);
                    Marshal.FreeCoTaskMem(parms);
                }
            }
        }
    }
}
