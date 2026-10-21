// Dual Earbuds - finding hosts on the local network (protocol v3.1 "Discovery", mDNS / DNS-SD).
// Hosts advertise _dualearbuds._tcp.local; listeners browse for it. Advertising uses Windows' own
// DnsServiceRegister (Windows 10 1903+) and falls back to a tiny responder on UDP 5353.
// The DNS message code is pure and unit-tested; sockets and dnsapi.dll run only on Windows.
using System;
using System.Collections.Generic;
using System.Globalization;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

namespace DualEarbuds
{
    public class DnsRecord
    {
        public string Name;
        public ushort Type;          // 1 A, 12 PTR, 16 TXT, 33 SRV
        public uint Ttl;
        public string Target;        // PTR / SRV target
        public ushort Port;          // SRV
        public IPAddress Address;    // A
        public List<string> Txt;     // TXT strings ("key=value")
    }

    public class DnsMessage
    {
        public ushort Id;
        public bool IsResponse;
        public List<string> Questions = new List<string>();
        public List<ushort> QuestionTypes = new List<ushort>();
        public List<DnsRecord> Records = new List<DnsRecord>();   // answers + additionals
    }

    public static class DnsSd
    {
        public const ushort A = 1, PTR = 12, TXT = 16, SRV = 33, ANY = 255;
        public const string ServiceType = "_dualearbuds._tcp.local";
        public static readonly IPAddress Group = IPAddress.Parse("224.0.0.251");
        public const int Port = 5353;
        public const uint Ttl = 120;

        // A DNS label: UTF-8, at most 63 bytes, no dots (they would split the name).
        public static string SafeLabel(string s)
        {
            if (string.IsNullOrEmpty(s)) s = "Dual Earbuds";
            var sb = new StringBuilder();
            foreach (char c in s) if (!char.IsControl(c) && c != '.') sb.Append(c);
            string r = sb.ToString().Trim();
            if (r.Length == 0) r = "Dual Earbuds";
            while (Encoding.UTF8.GetByteCount(r) > 63) r = r.Substring(0, r.Length - 1);
            return r;
        }

        // Host name label for "<label>.local" (letters, digits, hyphens).
        public static string HostLabel(string machine)
        {
            var sb = new StringBuilder();
            foreach (char c in machine ?? "") sb.Append(char.IsLetterOrDigit(c) && c < 128 ? c : '-');
            string r = sb.ToString().Trim('-');
            if (r.Length == 0) r = "dual-earbuds";
            return r.Length > 63 ? r.Substring(0, 63) : r;
        }

        // TXT strings per protocol: v=3, name, host=windows|android, pin=0|1, and k only when allowed.
        public static List<string> HostTxt(string device, string hostKind, bool pin, string key)
        {
            var t = new List<string>();
            t.Add("v=3");
            t.Add("name=" + (device ?? ""));
            t.Add("host=" + hostKind);
            t.Add("pin=" + (pin ? "1" : "0"));
            if (!string.IsNullOrEmpty(key)) t.Add("k=" + key);
            return t;
        }

        // ------------------------------------------------------------------ encode
        static void Name(List<byte> o, string name)
        {
            foreach (var part in SplitName(name))
            {
                byte[] b = Encoding.UTF8.GetBytes(part);
                if (b.Length == 0) continue;
                if (b.Length > 63) throw new ArgumentException("label too long");
                o.Add((byte)b.Length);
                o.AddRange(b);
            }
            o.Add(0);
        }

        // The instance label may contain spaces and other characters, but never a dot (SafeLabel).
        static IEnumerable<string> SplitName(string name) { return name.Split('.'); }

        static void U16(List<byte> o, int v) { o.Add((byte)(v >> 8)); o.Add((byte)v); }
        static void U32(List<byte> o, uint v) { o.Add((byte)(v >> 24)); o.Add((byte)(v >> 16)); o.Add((byte)(v >> 8)); o.Add((byte)v); }

        static void Rr(List<byte> o, string name, ushort type, bool flush, uint ttl, List<byte> rdata)
        {
            Name(o, name);
            U16(o, type);
            U16(o, flush ? 0x8001 : 0x0001);   // class IN, cache-flush bit for unique records
            U32(o, ttl);
            U16(o, rdata.Count);
            o.AddRange(rdata);
        }

        public static byte[] BuildQuery(string serviceType, bool unicastResponse)
        {
            var o = new List<byte>();
            U16(o, 0); U16(o, 0); U16(o, 1); U16(o, 0); U16(o, 0); U16(o, 0);
            Name(o, serviceType);
            U16(o, PTR);
            U16(o, unicastResponse ? 0x8001 : 0x0001);
            return o.ToArray();
        }

        // A full announcement/answer: PTR + SRV + TXT + A. ttl 0 = goodbye.
        public static byte[] BuildResponse(string instance, string hostLabel, int port, List<string> txt, IPAddress ipv4, uint ttl)
        {
            string inst = SafeLabel(instance) + "." + ServiceType;
            string target = hostLabel + ".local";
            int answers = ipv4 != null ? 4 : 3;
            var o = new List<byte>();
            U16(o, 0); U16(o, 0x8400); U16(o, 0); U16(o, answers); U16(o, 0); U16(o, 0);
            var rd = new List<byte>();
            Name(rd, inst);
            Rr(o, ServiceType, PTR, false, ttl, rd);
            rd = new List<byte>();
            U16(rd, 0); U16(rd, 0); U16(rd, port); Name(rd, target);
            Rr(o, inst, SRV, true, ttl, rd);
            rd = new List<byte>();
            foreach (var t in txt)
            {
                byte[] b = Encoding.UTF8.GetBytes(t);
                if (b.Length > 255) continue;
                rd.Add((byte)b.Length);
                rd.AddRange(b);
            }
            if (rd.Count == 0) rd.Add(0);
            Rr(o, inst, TXT, true, ttl, rd);
            if (ipv4 != null)
            {
                rd = new List<byte>(ipv4.GetAddressBytes());
                Rr(o, target, A, true, ttl, rd);
            }
            return o.ToArray();
        }

        // ------------------------------------------------------------------ parse
        public static DnsMessage Parse(byte[] b, int len)
        {
            if (b == null || len < 12) throw new FormatException("short packet");
            var m = new DnsMessage();
            m.Id = (ushort)((b[0] << 8) | b[1]);
            m.IsResponse = (b[2] & 0x80) != 0;
            int qd = (b[4] << 8) | b[5], an = (b[6] << 8) | b[7], ns = (b[8] << 8) | b[9], ar = (b[10] << 8) | b[11];
            if (qd > 64 || an + ns + ar > 256) throw new FormatException("too many records");
            int p = 12;
            for (int i = 0; i < qd; i++)
            {
                string n = ReadName(b, len, ref p);
                if (p + 4 > len) throw new FormatException("truncated question");
                m.Questions.Add(n);
                m.QuestionTypes.Add((ushort)((b[p] << 8) | b[p + 1]));
                p += 4;
            }
            for (int i = 0; i < an + ns + ar; i++)
            {
                var r = new DnsRecord();
                r.Name = ReadName(b, len, ref p);
                if (p + 10 > len) throw new FormatException("truncated record");
                r.Type = (ushort)((b[p] << 8) | b[p + 1]);
                r.Ttl = (uint)((b[p + 4] << 24) | (b[p + 5] << 16) | (b[p + 6] << 8) | b[p + 7]);
                int rdlen = (b[p + 8] << 8) | b[p + 9];
                p += 10;
                if (p + rdlen > len) throw new FormatException("truncated rdata");
                int rp = p;
                switch (r.Type)
                {
                    case PTR: r.Target = ReadName(b, len, ref rp); break;
                    case SRV:
                        if (rdlen < 7) throw new FormatException("bad SRV");
                        r.Port = (ushort)((b[rp + 4] << 8) | b[rp + 5]);
                        rp += 6;
                        r.Target = ReadName(b, len, ref rp);
                        break;
                    case TXT:
                        r.Txt = new List<string>();
                        while (rp < p + rdlen)
                        {
                            int l = b[rp++];
                            if (rp + l > p + rdlen) throw new FormatException("bad TXT");
                            if (l > 0) r.Txt.Add(Encoding.UTF8.GetString(b, rp, l));
                            rp += l;
                        }
                        break;
                    case A:
                        if (rdlen == 4) r.Address = new IPAddress(new[] { b[p], b[p + 1], b[p + 2], b[p + 3] });
                        break;
                }
                p += rdlen;
                m.Records.Add(r);
            }
            return m;
        }

        static string ReadName(byte[] b, int len, ref int p)
        {
            var sb = new StringBuilder();
            int pos = p, jumps = 0;
            bool jumped = false;
            while (true)
            {
                if (pos >= len) throw new FormatException("name past end");
                int l = b[pos];
                if (l == 0) { pos++; break; }
                if ((l & 0xC0) == 0xC0)
                {
                    if (pos + 1 >= len) throw new FormatException("bad pointer");
                    int target = ((l & 0x3F) << 8) | b[pos + 1];
                    if (!jumped) p = pos + 2;
                    jumped = true;
                    if (++jumps > 16 || target >= len) throw new FormatException("pointer loop");
                    pos = target;
                    continue;
                }
                if ((l & 0xC0) != 0) throw new FormatException("bad label");
                if (pos + 1 + l > len) throw new FormatException("label past end");
                if (sb.Length > 0) sb.Append('.');
                sb.Append(Encoding.UTF8.GetString(b, pos + 1, l));
                pos += 1 + l;
                if (sb.Length > 1024) throw new FormatException("name too long");
            }
            if (!jumped) p = pos;
            return sb.ToString();
        }

        public static string TxtValue(List<string> txt, string key)
        {
            if (txt == null) return null;
            foreach (var t in txt)
            {
                int e = t.IndexOf('=');
                if (e > 0 && string.Equals(t.Substring(0, e), key, StringComparison.OrdinalIgnoreCase)) return t.Substring(e + 1);
            }
            return null;
        }

        public static bool AsksFor(DnsMessage m, string name)
        {
            for (int i = 0; i < m.Questions.Count; i++)
            {
                ushort t = m.QuestionTypes[i];
                if ((t == PTR || t == ANY || t == SRV || t == TXT) && m.Questions[i].EndsWith(name, StringComparison.OrdinalIgnoreCase)) return true;
            }
            return false;
        }
    }

    public class FoundHost
    {
        public string Instance;     // DNS-SD instance label
        public string Name;         // device name from TXT
        public string Address;
        public int Port;
        public string Key;          // null when the host didn't publish it (network not private)
        public bool Pin;
        public string HostKind = "windows";
        public DateTime SeenUtc;

        public string Link { get { return Key == null ? null : "http://" + Address + ":" + Port.ToString(CultureInfo.InvariantCulture) + "/?k=" + Key; } }
    }

    public static class DiscoveryParse
    {
        // Every complete Dual Earbuds host described in one response. from = the packet's sender (fallback address).
        public static List<FoundHost> Hosts(DnsMessage m, IPAddress from)
        {
            var list = new List<FoundHost>();
            if (!m.IsResponse) return list;
            var instances = new List<string>();
            foreach (var r in m.Records)
                if (r.Type == DnsSd.PTR && string.Equals(r.Name, DnsSd.ServiceType, StringComparison.OrdinalIgnoreCase) && r.Target != null && !instances.Contains(r.Target))
                    instances.Add(r.Target);
            // a response may also carry only SRV/TXT for an instance (no PTR)
            foreach (var r in m.Records)
                if ((r.Type == DnsSd.SRV || r.Type == DnsSd.TXT) && r.Name.EndsWith("." + DnsSd.ServiceType, StringComparison.OrdinalIgnoreCase) && !instances.Contains(r.Name))
                    instances.Add(r.Name);
            foreach (var inst in instances)
            {
                DnsRecord srv = null, txt = null;
                bool goodbye = false;
                foreach (var r in m.Records)
                {
                    if (!string.Equals(r.Name, inst, StringComparison.OrdinalIgnoreCase)) continue;
                    if (r.Type == DnsSd.SRV) { srv = r; goodbye |= r.Ttl == 0; }
                    else if (r.Type == DnsSd.TXT) txt = r;
                }
                if (srv == null || goodbye || srv.Port == 0) continue;
                IPAddress addr = null;
                foreach (var r in m.Records)
                    if (r.Type == DnsSd.A && r.Address != null && string.Equals(r.Name, srv.Target, StringComparison.OrdinalIgnoreCase)) { addr = r.Address; break; }
                if (addr == null) addr = from;
                if (addr == null) continue;
                var h = new FoundHost
                {
                    Instance = inst.Substring(0, inst.Length - DnsSd.ServiceType.Length - 1),
                    Address = addr.ToString(),
                    Port = srv.Port,
                    SeenUtc = DateTime.UtcNow,
                };
                var t = txt == null ? null : txt.Txt;
                h.Name = DnsSd.TxtValue(t, "name");
                if (string.IsNullOrEmpty(h.Name)) h.Name = h.Instance;
                string k = DnsSd.TxtValue(t, "k");
                h.Key = LinkKey.IsValid(k) ? k.ToUpperInvariant() : null;
                h.Pin = DnsSd.TxtValue(t, "pin") == "1";
                string hk = DnsSd.TxtValue(t, "host");
                if (hk == "android" || hk == "windows") h.HostKind = hk;
                list.Add(h);
            }
            return list;
        }

        // Goodbye packets (TTL 0) name instances that left.
        public static List<string> Goodbyes(DnsMessage m)
        {
            var l = new List<string>();
            foreach (var r in m.Records)
                if (r.Type == DnsSd.SRV && r.Ttl == 0 && r.Name.EndsWith("." + DnsSd.ServiceType, StringComparison.OrdinalIgnoreCase))
                    l.Add(r.Name.Substring(0, r.Name.Length - DnsSd.ServiceType.Length - 1));
            return l;
        }
    }

    // ---------------------------------------------------------------------- advertising (host)
    public class MdnsAdvertiser : IDisposable
    {
        readonly object lk = new object();
        string instance, hostLabel;
        int port;
        List<string> txt;
        IPAddress address;
        bool running;
        public string Method { get; private set; }   // "Windows" / "built-in" / null

        // Native registration
        [StructLayout(LayoutKind.Sequential)]
        struct RegisterRequest
        {
            public uint Version;
            public uint InterfaceIndex;
            public IntPtr pServiceInstance;
            public IntPtr pRegisterCompletionCallback;
            public IntPtr pQueryContext;
            public IntPtr hCredentials;
            public int unicastEnabled;
        }

        [UnmanagedFunctionPointer(CallingConvention.Winapi)]
        delegate void RegisterComplete(uint status, IntPtr context, IntPtr instance);

        [DllImport("dnsapi.dll", CharSet = CharSet.Unicode)]
        static extern IntPtr DnsServiceConstructInstance(string serviceName, string hostName, IntPtr ip4, IntPtr ip6, ushort port, ushort priority, ushort weight,
                                                         uint propertiesCount, string[] keys, string[] values);
        [DllImport("dnsapi.dll")] static extern void DnsServiceFreeInstance(IntPtr instance);
        [DllImport("dnsapi.dll")] static extern uint DnsServiceRegister(IntPtr request, IntPtr cancel);
        [DllImport("dnsapi.dll")] static extern uint DnsServiceDeRegister(IntPtr request, IntPtr cancel);
        const uint DNS_REQUEST_PENDING = 9506;

        IntPtr nativeRequest, nativeInstance, ip4Mem;
        readonly RegisterComplete callback;

        // Fallback responder
        UdpClient udp;
        Thread udpThread;
        volatile bool udpStop;

        public MdnsAdvertiser()
        {
            callback = OnRegistered;   // kept in a field: the native side calls it later
        }

        void OnRegistered(uint status, IntPtr context, IntPtr inst)
        {
            if (status != 0) Log.Warn("mDNS registration finished with status " + status);
            if (inst != IntPtr.Zero) { try { DnsServiceFreeInstance(inst); } catch { } }
        }

        public static bool NativeSupported
        {
            get { return OsInfo.IsWindows && OsInfo.Build >= 18362; }   // DnsServiceRegister: Windows 10 1903
        }

        // Starts or updates the advertisement. Never throws; cheap when nothing changed.
        public void Advertise(string instanceName, int tcpPort, List<string> txtStrings, IPAddress ipv4)
        {
            lock (lk)
            {
                string inst = DnsSd.SafeLabel(instanceName);
                bool same = running && inst == instance && tcpPort == port && ipv4 != null && ipv4.Equals(address) && txt != null && string.Join("\n", txt.ToArray()) == string.Join("\n", txtStrings.ToArray());
                if (same) return;
                StopLocked(false);
                instance = inst;
                hostLabel = DnsSd.HostLabel(Environment.MachineName);
                port = tcpPort;
                txt = new List<string>(txtStrings);
                address = ipv4;
                if (NativeSupported && StartNative()) { Method = "Windows"; running = true; return; }
                if (StartResponder()) { Method = "built-in"; running = true; return; }
                Method = null;
            }
        }

        bool StartNative()
        {
            try
            {
                var keys = new List<string>();
                var values = new List<string>();
                foreach (var t in txt)
                {
                    int e = t.IndexOf('=');
                    keys.Add(e < 0 ? t : t.Substring(0, e));
                    values.Add(e < 0 ? "" : t.Substring(e + 1));
                }
                if (address != null)
                {
                    ip4Mem = Marshal.AllocHGlobal(4);
                    Marshal.Copy(address.GetAddressBytes(), 0, ip4Mem, 4);   // IP4_ADDRESS is in network byte order
                }
                nativeInstance = DnsServiceConstructInstance(instance + "." + DnsSd.ServiceType, hostLabel + ".local", ip4Mem, IntPtr.Zero,
                                                             (ushort)port, 0, 0, (uint)keys.Count, keys.ToArray(), values.ToArray());
                if (nativeInstance == IntPtr.Zero) { FreeNative(); return false; }
                var req = new RegisterRequest
                {
                    Version = 1,
                    InterfaceIndex = 0,
                    pServiceInstance = nativeInstance,
                    pRegisterCompletionCallback = Marshal.GetFunctionPointerForDelegate(callback),
                };
                nativeRequest = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(RegisterRequest)));
                Marshal.StructureToPtr(req, nativeRequest, false);
                uint r = DnsServiceRegister(nativeRequest, IntPtr.Zero);
                if (r != DNS_REQUEST_PENDING && r != 0)
                {
                    Log.Warn("DnsServiceRegister returned " + r + " - using the built-in responder");
                    FreeNative();
                    return false;
                }
                Log.Info("Discovery: advertising " + DnsSd.ServiceType + " with Windows (port " + port + ")");
                return true;
            }
            catch (Exception ex)
            {
                Log.Warn("Discovery (Windows API): " + ex.Message);
                FreeNative();
                return false;
            }
        }

        void FreeNative()
        {
            // the instance and request memory are only released after deregistration (or a failed start)
            if (nativeInstance != IntPtr.Zero) { try { DnsServiceFreeInstance(nativeInstance); } catch { } nativeInstance = IntPtr.Zero; }
            if (nativeRequest != IntPtr.Zero) { Marshal.FreeHGlobal(nativeRequest); nativeRequest = IntPtr.Zero; }
            if (ip4Mem != IntPtr.Zero) { Marshal.FreeHGlobal(ip4Mem); ip4Mem = IntPtr.Zero; }
        }

        bool StartResponder()
        {
            try
            {
                var u = new UdpClient();
                u.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
                u.Client.Bind(new IPEndPoint(IPAddress.Any, DnsSd.Port));
                u.JoinMulticastGroup(DnsSd.Group);
                u.Client.ReceiveTimeout = 1000;
                udp = u;
                udpStop = false;
                udpThread = new Thread(ResponderLoop) { IsBackground = true, Name = "mDNS" };
                udpThread.Start();
                Announce(DnsSd.Ttl);
                Log.Info("Discovery: advertising " + DnsSd.ServiceType + " with the built-in responder (port " + port + ")");
                return true;
            }
            catch (Exception ex)
            {
                Log.Warn("Discovery (UDP 5353): " + ex.Message);
                try { if (udp != null) udp.Close(); } catch { }
                udp = null;
                return false;
            }
        }

        void Announce(uint ttl)
        {
            var u = udp;
            if (u == null) return;
            try
            {
                byte[] msg = DnsSd.BuildResponse(instance, hostLabel, port, txt, address, ttl);
                u.Send(msg, msg.Length, new IPEndPoint(DnsSd.Group, DnsSd.Port));
            }
            catch (Exception ex) { Log.Info("mDNS send: " + ex.Message); }
        }

        void ResponderLoop()
        {
            var ep = new IPEndPoint(IPAddress.Any, 0);
            long lastAnnounce = Environment.TickCount;
            int announcements = 1;
            while (!udpStop)
            {
                byte[] data = null;
                try { data = udp.Receive(ref ep); }
                catch (SocketException) { }
                catch (ObjectDisposedException) { return; }
                catch (Exception) { if (udpStop) return; }
                if (udpStop) return;
                // RFC 6762: a second announcement a second later
                if (announcements < 2 && Environment.TickCount - lastAnnounce > 1000) { Announce(DnsSd.Ttl); announcements++; }
                if (data == null) continue;
                try
                {
                    var m = DnsSd.Parse(data, data.Length);
                    if (m.IsResponse || !DnsSd.AsksFor(m, DnsSd.ServiceType)) continue;
                    lock (lk) { if (running) Announce(DnsSd.Ttl); }
                }
                catch (FormatException) { }
            }
        }

        void StopLocked(bool log)
        {
            if (!running) return;
            running = false;
            if (nativeRequest != IntPtr.Zero)
            {
                try { DnsServiceDeRegister(nativeRequest, IntPtr.Zero); } catch { }
                // deregistration completes asynchronously and may still read this memory: retire it and
                // free it a few seconds later (or when the app closes)
                retired.Add(new Retired { Request = nativeRequest, Instance = nativeInstance, Ip4 = ip4Mem, At = Environment.TickCount });
                nativeRequest = nativeInstance = ip4Mem = IntPtr.Zero;
            }
            FreeRetired(false);
            if (udp != null)
            {
                Announce(0);   // goodbye
                udpStop = true;
                try { udp.Close(); } catch { }
                if (udpThread != null) udpThread.Join(1500);
                udp = null;
                udpThread = null;
            }
            if (log) Log.Info("Discovery: stopped advertising");
        }

        class Retired { public IntPtr Request, Instance, Ip4; public int At; }
        readonly List<Retired> retired = new List<Retired>();

        void FreeRetired(bool all)
        {
            for (int i = retired.Count - 1; i >= 0; i--)
            {
                var r = retired[i];
                if (!all && Environment.TickCount - r.At < 5000) continue;
                try { if (r.Instance != IntPtr.Zero) DnsServiceFreeInstance(r.Instance); } catch { }
                if (r.Request != IntPtr.Zero) Marshal.FreeHGlobal(r.Request);
                if (r.Ip4 != IntPtr.Zero) Marshal.FreeHGlobal(r.Ip4);
                retired.RemoveAt(i);
            }
        }

        public void Stop() { lock (lk) StopLocked(true); }

        // On exit: stop, wait briefly for Windows to finish deregistering, then free everything.
        public void Dispose()
        {
            lock (lk)
            {
                StopLocked(true);
                if (retired.Count > 0) Thread.Sleep(300);
                FreeRetired(true);
            }
        }
    }

    // ---------------------------------------------------------------------- browsing (listener)
    public class MdnsBrowser : IDisposable
    {
        readonly object lk = new object();
        readonly Dictionary<string, FoundHost> hosts = new Dictionary<string, FoundHost>();
        UdpClient udp;
        Thread thread;
        volatile bool stop;
        public string Error { get; private set; }
        public string IgnorePort;   // "ip:port" of this laptop's own host, so it doesn't list itself

        public bool Start()
        {
            if (thread != null) return true;
            try
            {
                var u = new UdpClient();
                u.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
                try
                {
                    u.Client.Bind(new IPEndPoint(IPAddress.Any, DnsSd.Port));
                    u.JoinMulticastGroup(DnsSd.Group);
                }
                catch (SocketException)
                {
                    // 5353 taken exclusively: ask for unicast answers on our own port instead
                    u.Close();
                    u = new UdpClient(0);
                }
                u.Client.ReceiveTimeout = 1000;
                udp = u;
                stop = false;
                thread = new Thread(Loop) { IsBackground = true, Name = "mDNSBrowse" };
                thread.Start();
                return true;
            }
            catch (Exception ex)
            {
                Error = ex.Message;
                Log.Warn("Discovery browse: " + ex.Message);
                return false;
            }
        }

        void Query()
        {
            try
            {
                byte[] q = DnsSd.BuildQuery(DnsSd.ServiceType, true);
                udp.Send(q, q.Length, new IPEndPoint(DnsSd.Group, DnsSd.Port));
            }
            catch (Exception ex) { Log.Info("mDNS query: " + ex.Message); }
        }

        void Loop()
        {
            int lastQuery = Environment.TickCount - 100000, interval = 1000;
            var ep = new IPEndPoint(IPAddress.Any, 0);
            while (!stop)
            {
                if (Environment.TickCount - lastQuery >= interval)
                {
                    Query();
                    lastQuery = Environment.TickCount;
                    interval = Math.Min(30000, interval * 2);   // 1, 2, 4 ... 30 s
                }
                byte[] data = null;
                try { data = udp.Receive(ref ep); }
                catch (SocketException) { }
                catch (ObjectDisposedException) { return; }
                catch (Exception) { if (stop) return; }
                if (data == null) { Expire(); continue; }
                try
                {
                    var m = DnsSd.Parse(data, data.Length);
                    lock (lk)
                    {
                        foreach (var g in DiscoveryParse.Goodbyes(m)) hosts.Remove(g);
                        foreach (var h in DiscoveryParse.Hosts(m, ep.Address))
                        {
                            if (IgnorePort != null && h.Address + ":" + h.Port == IgnorePort) continue;
                            hosts[h.Instance] = h;
                        }
                    }
                }
                catch (FormatException) { }
            }
        }

        void Expire()
        {
            lock (lk)
            {
                var old = new List<string>();
                foreach (var kv in hosts) if ((DateTime.UtcNow - kv.Value.SeenUtc).TotalSeconds > 150) old.Add(kv.Key);
                foreach (var k in old) hosts.Remove(k);
            }
        }

        // Ask again now (the user opened the list or pressed Refresh).
        public void Refresh() { if (udp != null) Query(); }

        public List<FoundHost> Hosts
        {
            get { lock (lk) return new List<FoundHost>(hosts.Values); }
        }

        public void Stop()
        {
            stop = true;
            try { if (udp != null) udp.Close(); } catch { }
            if (thread != null) thread.Join(1500);
            thread = null;
            udp = null;
        }

        public void Dispose() { Stop(); }
    }

    public static class OsInfo
    {
        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        struct OSVERSIONINFOEX
        {
            public int dwOSVersionInfoSize, dwMajorVersion, dwMinorVersion, dwBuildNumber, dwPlatformId;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string szCSDVersion;
            public ushort wServicePackMajor, wServicePackMinor, wSuiteMask;
            public byte wProductType, wReserved;
        }

        [DllImport("ntdll.dll")] static extern int RtlGetVersion(ref OSVERSIONINFOEX v);

        static int build = -1, major;

        public static bool IsWindows { get { return Environment.OSVersion.Platform == PlatformID.Win32NT; } }

        // The real Windows build number (not the compatibility-shimmed one).
        public static int Build
        {
            get
            {
                if (build >= 0) return build;
                build = Environment.OSVersion.Version.Build;
                major = Environment.OSVersion.Version.Major;
                if (IsWindows)
                {
                    try
                    {
                        var v = new OSVERSIONINFOEX();
                        v.dwOSVersionInfoSize = Marshal.SizeOf(typeof(OSVERSIONINFOEX));
                        if (RtlGetVersion(ref v) == 0) { build = v.dwBuildNumber; major = v.dwMajorVersion; }
                    }
                    catch { }
                }
                return build;
            }
        }

        public static string Describe()
        {
            int b = Build;
            if (!IsWindows) return Environment.OSVersion.VersionString;
            string name = major >= 10 ? (b >= 22000 ? "Windows 11" : "Windows 10") : "Windows " + Environment.OSVersion.Version.Major + "." + Environment.OSVersion.Version.Minor;
            return name + " (build " + b.ToString(CultureInfo.InvariantCulture) + ", " + (Environment.Is64BitOperatingSystem ? "64-bit" : "32-bit") + ")";
        }
    }
}
