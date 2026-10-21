// Dual Earbuds Pro - reads Bluetooth earbud battery levels that Windows knows about.
// Windows stores the level on the Bluetooth device node (DEVPKEY {104EA319-...},2). The audio
// endpoint shares a container ID with that node, which is how a battery is matched to an output.
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Threading;

namespace DualEarbuds
{
    public static class BatteryReader
    {
        [StructLayout(LayoutKind.Sequential)]
        struct SP_DEVINFO_DATA { public uint cbSize; public Guid ClassGuid; public uint DevInst; public IntPtr Reserved; }

        [StructLayout(LayoutKind.Sequential)]
        struct DEVPROPKEY { public Guid fmtid; public uint pid; }

        [DllImport("setupapi.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        static extern IntPtr SetupDiGetClassDevsW(IntPtr classGuid, string enumerator, IntPtr hwnd, uint flags);

        [DllImport("setupapi.dll", SetLastError = true)]
        static extern bool SetupDiEnumDeviceInfo(IntPtr set, uint index, ref SP_DEVINFO_DATA data);

        [DllImport("setupapi.dll", SetLastError = true)]
        static extern bool SetupDiGetDevicePropertyW(IntPtr set, ref SP_DEVINFO_DATA data, ref DEVPROPKEY key, out uint type,
                                                     byte[] buffer, uint size, out uint required, uint flags);

        [DllImport("setupapi.dll")]
        static extern bool SetupDiDestroyDeviceInfoList(IntPtr set);

        const uint DIGCF_PRESENT = 0x2, DIGCF_ALLCLASSES = 0x4;
        const uint DEVPROP_TYPE_BYTE = 0x3, DEVPROP_TYPE_GUID = 0xD;
        static readonly Guid BatteryFmt = new Guid("104EA319-6EE2-4701-BD47-8DDBF425BBE5");
        static readonly Guid ContainerFmt = new Guid("8c7ed206-3f8a-4827-b3ab-ae9e1faefc6c");

        static readonly object lk = new object();
        static Dictionary<Guid, int> cache = new Dictionary<Guid, int>();
        static DateTime cacheTime = DateTime.MinValue;
        static int refreshing;

        // Battery percent for an audio endpoint's container, or -1 if Windows doesn't know it.
        // Never blocks: refreshes in the background at most every 30 seconds.
        public static int ForContainer(Guid container)
        {
            if (container == Guid.Empty) return -1;
            if ((DateTime.Now - cacheTime).TotalSeconds > 30 && Interlocked.Exchange(ref refreshing, 1) == 0)
            {
                var t = new Thread(() =>
                {
                    try
                    {
                        var fresh = ReadAll();
                        lock (lk) { cache = fresh; cacheTime = DateTime.Now; }
                    }
                    catch (Exception ex) { Log.Warn("Battery read: " + ex.Message); cacheTime = DateTime.Now; }
                    finally { Interlocked.Exchange(ref refreshing, 0); }
                });
                t.IsBackground = true;
                t.Start();
            }
            lock (lk)
            {
                int v;
                return cache.TryGetValue(container, out v) ? v : -1;
            }
        }

        // Container ID -> highest battery percent reported by any node in that container.
        public static Dictionary<Guid, int> ReadAll()
        {
            var result = new Dictionary<Guid, int>();
            IntPtr set = SetupDiGetClassDevsW(IntPtr.Zero, null, IntPtr.Zero, DIGCF_PRESENT | DIGCF_ALLCLASSES);
            if (set == IntPtr.Zero || set == new IntPtr(-1)) return result;
            try
            {
                var data = new SP_DEVINFO_DATA();
                data.cbSize = (uint)Marshal.SizeOf(typeof(SP_DEVINFO_DATA));
                var batKey = new DEVPROPKEY { fmtid = BatteryFmt, pid = 2 };
                var conKey = new DEVPROPKEY { fmtid = ContainerFmt, pid = 2 };
                var buf = new byte[16];
                for (uint i = 0; i < 4096 && SetupDiEnumDeviceInfo(set, i, ref data); i++)
                {
                    uint type, req;
                    if (!SetupDiGetDevicePropertyW(set, ref data, ref batKey, out type, buf, (uint)buf.Length, out req, 0)) continue;
                    if (type != DEVPROP_TYPE_BYTE || req < 1) continue;
                    int level = buf[0];
                    if (level > 100) continue;
                    if (!SetupDiGetDevicePropertyW(set, ref data, ref conKey, out type, buf, (uint)buf.Length, out req, 0)) continue;
                    if (type != DEVPROP_TYPE_GUID || req != 16) continue;
                    var g = new Guid(buf);
                    int old;
                    if (!result.TryGetValue(g, out old) || level > old) result[g] = level;
                }
            }
            finally { SetupDiDestroyDeviceInfoList(set); }
            return result;
        }
    }
}
