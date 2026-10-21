// Dual Earbuds Pro - Windows Core Audio (WASAPI) interop and helpers.
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;

namespace DualEarbuds
{
    public enum EDataFlow { eRender = 0, eCapture = 1, eAll = 2 }
    public enum ERole { eConsole = 0, eMultimedia = 1, eCommunications = 2 }

    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    class MMDeviceEnumeratorComObject { }

    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDeviceEnumerator
    {
        [PreserveSig] int EnumAudioEndpoints(EDataFlow dataFlow, uint stateMask, out IMMDeviceCollection devices);
        [PreserveSig] int GetDefaultAudioEndpoint(EDataFlow dataFlow, ERole role, out IMMDevice endpoint);
        [PreserveSig] int GetDevice([MarshalAs(UnmanagedType.LPWStr)] string id, out IMMDevice device);
        [PreserveSig] int RegisterEndpointNotificationCallback(IntPtr client);
        [PreserveSig] int UnregisterEndpointNotificationCallback(IntPtr client);
    }

    [ComImport, Guid("0BD7A1BE-7A1A-44DB-8397-CC5392387B5E"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDeviceCollection
    {
        [PreserveSig] int GetCount(out uint count);
        [PreserveSig] int Item(uint index, out IMMDevice device);
    }

    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDevice
    {
        [PreserveSig] int Activate(ref Guid iid, uint clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object iface);
        [PreserveSig] int OpenPropertyStore(uint access, out IPropertyStore props);
        [PreserveSig] int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
        [PreserveSig] int GetState(out uint state);
    }

    [StructLayout(LayoutKind.Sequential)]
    struct PROPERTYKEY { public Guid fmtid; public uint pid; }

    [StructLayout(LayoutKind.Sequential)]
    struct PROPVARIANT { public ushort vt; public ushort r1, r2, r3; public IntPtr p; public IntPtr p2; }

    [ComImport, Guid("886d8eeb-8cf2-4446-8d02-cdba1dbdcf99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IPropertyStore
    {
        [PreserveSig] int GetCount(out uint count);
        [PreserveSig] int GetAt(uint index, out PROPERTYKEY key);
        [PreserveSig] int GetValue(ref PROPERTYKEY key, out PROPVARIANT value);
        [PreserveSig] int SetValue(ref PROPERTYKEY key, ref PROPVARIANT value);
        [PreserveSig] int Commit();
    }

    [ComImport, Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioClient
    {
        [PreserveSig] int Initialize(int shareMode, uint streamFlags, long bufferDuration, long periodicity, IntPtr format, IntPtr sessionGuid);
        [PreserveSig] int GetBufferSize(out uint frames);
        [PreserveSig] int GetStreamLatency(out long latency);
        [PreserveSig] int GetCurrentPadding(out uint padding);
        [PreserveSig] int IsFormatSupported(int shareMode, IntPtr format, out IntPtr closest);
        [PreserveSig] int GetMixFormat(out IntPtr format);
        [PreserveSig] int GetDevicePeriod(out long defaultPeriod, out long minPeriod);
        [PreserveSig] int Start();
        [PreserveSig] int Stop();
        [PreserveSig] int Reset();
        [PreserveSig] int SetEventHandle(IntPtr handle);
        [PreserveSig] int GetService(ref Guid iid, [MarshalAs(UnmanagedType.IUnknown)] out object service);
    }

    [ComImport, Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioCaptureClient
    {
        [PreserveSig] int GetBuffer(out IntPtr data, out uint frames, out uint flags, out ulong devicePosition, out ulong qpcPosition);
        [PreserveSig] int ReleaseBuffer(uint frames);
        [PreserveSig] int GetNextPacketSize(out uint frames);
    }

    [ComImport, Guid("F294ACFC-3146-4483-A7BF-ADDCA7C260E2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioRenderClient
    {
        [PreserveSig] int GetBuffer(uint frames, out IntPtr data);
        [PreserveSig] int ReleaseBuffer(uint frames, uint flags);
    }

    public class DeviceInfo
    {
        public string Id;
        public string Name;
        public Guid ContainerId;   // links the audio endpoint to its Bluetooth device (for battery)
    }

    public class AudioFormat { public int Channels, Rate, Bits, BlockAlign; public bool IsFloat; public uint ChannelMask; }

    public class AudioException : Exception
    {
        public AudioException(int hr, string what)
            : base(string.Format("{0} failed (0x{1:X8})", what, hr)) { HResult = hr; }
    }

    public static class Wasapi
    {
        public const uint STATE_ACTIVE = 1, CLSCTX_ALL = 23;
        public const uint FLAG_LOOPBACK = 0x00020000, FLAG_AUTOCONVERT = 0x80000000, FLAG_SRC_QUALITY = 0x08000000;
        public const uint BUFFER_SILENT = 0x2;
        public static readonly Guid IID_AudioClient = new Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2");
        public static readonly Guid IID_Capture = new Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317");
        public static readonly Guid IID_Render = new Guid("F294ACFC-3146-4483-A7BF-ADDCA7C260E2");
        public static readonly Guid FLOAT_SUBTYPE = new Guid("00000003-0000-0010-8000-00aa00389b71");
        static readonly Guid PKEY_FriendlyName = new Guid("a45c254e-df1c-4efd-8020-67d146a850e0");
        static readonly Guid PKEY_ContainerId = new Guid("8c7ed206-3f8a-4827-b3ab-ae9e1faefc6c");

        public const int E_DEVICE_INVALIDATED = unchecked((int)0x88890004);
        public const int E_ENDPOINT_CREATE_FAILED = unchecked((int)0x8889000F);

        [DllImport("ole32.dll")] static extern int PropVariantClear(ref PROPVARIANT pv);

        internal static void Check(int hr, string what) { if (hr < 0) throw new AudioException(hr, what); }

        internal static IMMDeviceEnumerator NewEnumerator() { return (IMMDeviceEnumerator)new MMDeviceEnumeratorComObject(); }

        internal static void Release(object o)
        {
            if (o != null && Marshal.IsComObject(o)) { try { Marshal.ReleaseComObject(o); } catch { } }
        }

        public static string Friendly(Exception ex)
        {
            int hr = ex.HResult;
            if (hr == E_DEVICE_INVALIDATED || hr == unchecked((int)0x80070490)) return "Disconnected";
            if (hr == unchecked((int)0x88890008)) return "This device doesn't accept the sound format";
            if (hr == unchecked((int)0x8889000A)) return "Busy - another app is using it exclusively";
            if (hr == E_ENDPOINT_CREATE_FAILED) return "Windows won't open a sound stream to it (this PC's Bluetooth may allow one stereo pair at a time - use the phone link for this pair)";
            return ex.Message;
        }

        public static List<DeviceInfo> ListOutputs(out string defaultId) { return ListEndpoints(EDataFlow.eRender, out defaultId); }

        public static List<DeviceInfo> ListEndpoints(EDataFlow flow, out string defaultId)
        {
            var list = new List<DeviceInfo>();
            defaultId = null;
            IMMDeviceEnumerator en = NewEnumerator();
            try
            {
                IMMDevice def;
                if (en.GetDefaultAudioEndpoint(flow, ERole.eMultimedia, out def) >= 0 && def != null)
                {
                    def.GetId(out defaultId);
                    Release(def);
                }
                IMMDeviceCollection col;
                Check(en.EnumAudioEndpoints(flow, STATE_ACTIVE, out col), "List devices");
                uint n;
                col.GetCount(out n);
                for (uint i = 0; i < n; i++)
                {
                    IMMDevice d;
                    if (col.Item(i, out d) < 0 || d == null) continue;
                    string id;
                    d.GetId(out id);
                    var info = new DeviceInfo { Id = id, Name = "Unknown device" };
                    ReadProperties(d, info);
                    list.Add(info);
                    Release(d);
                }
                Release(col);
            }
            finally { Release(en); }
            return list;
        }

        static void ReadProperties(IMMDevice d, DeviceInfo info)
        {
            IPropertyStore ps;
            if (d.OpenPropertyStore(0, out ps) < 0 || ps == null) return;
            try
            {
                var key = new PROPERTYKEY { fmtid = PKEY_FriendlyName, pid = 14 };
                PROPVARIANT pv;
                if (ps.GetValue(ref key, out pv) >= 0)
                {
                    if (pv.vt == 31 && pv.p != IntPtr.Zero) info.Name = Marshal.PtrToStringUni(pv.p);
                    PropVariantClear(ref pv);
                }
                key = new PROPERTYKEY { fmtid = PKEY_ContainerId, pid = 2 };
                if (ps.GetValue(ref key, out pv) >= 0)
                {
                    if (pv.vt == 72 && pv.p != IntPtr.Zero) info.ContainerId = (Guid)Marshal.PtrToStructure(pv.p, typeof(Guid));
                    PropVariantClear(ref pv);
                }
            }
            finally { Release(ps); }
        }

        // id == null means "the current Windows default output".
        internal static IAudioClient OpenClient(IMMDeviceEnumerator en, string id, out string resolvedId)
        {
            IMMDevice dev;
            if (id == null) Check(en.GetDefaultAudioEndpoint(EDataFlow.eRender, ERole.eMultimedia, out dev), "Find default output");
            else Check(en.GetDevice(id, out dev), "Open device");
            object o;
            try
            {
                dev.GetId(out resolvedId);
                Guid iid = IID_AudioClient;
                Check(dev.Activate(ref iid, CLSCTX_ALL, IntPtr.Zero, out o), "Activate device");
            }
            finally { Release(dev); }
            return (IAudioClient)o;
        }

        internal static AudioFormat ReadFormat(IntPtr p)
        {
            ushort tag = (ushort)Marshal.ReadInt16(p, 0);
            var f = new AudioFormat();
            f.Channels = (ushort)Marshal.ReadInt16(p, 2);
            f.Rate = Marshal.ReadInt32(p, 4);
            f.BlockAlign = (ushort)Marshal.ReadInt16(p, 12);
            f.Bits = (ushort)Marshal.ReadInt16(p, 14);
            if (tag == 3) f.IsFloat = true;
            else if (tag == 0xFFFE)
            {
                f.ChannelMask = (uint)Marshal.ReadInt32(p, 20);
                byte[] g = new byte[16];
                Marshal.Copy(IntPtr.Add(p, 24), g, 0, 16);
                f.IsFloat = new Guid(g) == FLOAT_SUBTYPE;
            }
            return f;
        }

        // WAVEFORMATEXTENSIBLE: 32-bit float, stereo. Caller frees with FreeCoTaskMem.
        internal static IntPtr MakeFloatStereo(int rate)
        {
            IntPtr p = Marshal.AllocCoTaskMem(40);
            Marshal.WriteInt16(p, 0, unchecked((short)0xFFFE));
            Marshal.WriteInt16(p, 2, 2);
            Marshal.WriteInt32(p, 4, rate);
            Marshal.WriteInt32(p, 8, rate * 8);
            Marshal.WriteInt16(p, 12, 8);
            Marshal.WriteInt16(p, 14, 32);
            Marshal.WriteInt16(p, 16, 22);
            Marshal.WriteInt16(p, 18, 32);
            Marshal.WriteInt32(p, 20, 3);
            Marshal.Copy(FLOAT_SUBTYPE.ToByteArray(), 0, IntPtr.Add(p, 24), 16);
            return p;
        }

        public static bool IsHandsFree(string name)
        {
            return name.IndexOf("Hands-Free", StringComparison.OrdinalIgnoreCase) >= 0
                || name.IndexOf("Hands Free", StringComparison.OrdinalIgnoreCase) >= 0;
        }
    }
}
