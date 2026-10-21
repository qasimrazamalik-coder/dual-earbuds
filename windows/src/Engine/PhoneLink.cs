// Dual Earbuds Pro - phone link server (see PROTOCOL.md).
// One TCP port serves the player page, /info, and a WebSocket that streams the laptop's sound.
using System;
using System.IO;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Threading;

namespace DualEarbuds
{
    public class PhoneInfo
    {
        public string Id;
        public string Name = "Phone";
        public string Client = "web";
        public string Remote = "";
        public string Earbuds = "";
        public int Battery = -1;
        public int Volume = 100;
        public int BufferMs = 0;
        public bool Playing;
        public int RttMs = -1;
        public DateTime ConnectedAt;
        public long PacketsSent;
        public long PacketsDropped;
    }

    public class PhoneLink
    {
        public const int MaxClients = 8;
        public const int MaxHeaderBytes = 8192;
        public const int MaxClientMessage = 4096;
        public const int IdleTimeoutMs = 30000;
        public const int AudioQueueLimit = 60;   // ~600 ms of 10 ms packets

        public readonly int Port;
        readonly IPAddress bindAddress;
        volatile string key;
        public volatile string Error;
        public string PcName = Environment.MachineName;

        TcpListener listener;
        volatile bool stopFlag;
        Thread acceptThread, pingThread;
        // At most this many connections may be in the HTTP/handshake stage at once (slow-header protection).
        public const int MaxPendingHandshakes = 16;
        public const int HeaderDeadlineMs = 5000;
        readonly SemaphoreSlim pending = new SemaphoreSlim(MaxPendingHandshakes, MaxPendingHandshakes);

        // Guessing protection (the link also works on public Wi-Fi): a device that tries too many *different*
        // wrong keys is ignored for a while. Repeating one old key (a stale tab after "New link") never trips it.
        public const int MaxBadKeys = 10;
        public const int BadKeyWindowMs = 60000;
        public const int BlockMs = 5 * 60000;
        class BadKeyRecord { public long WindowStart, BlockedUntil; public HashSet<string> Keys = new HashSet<string>(); }
        readonly Dictionary<string, BadKeyRecord> badKeys = new Dictionary<string, BadKeyRecord>();

        bool IsBlocked(string ip)
        {
            lock (badKeys)
            {
                BadKeyRecord b;
                return badKeys.TryGetValue(ip, out b) && b.BlockedUntil > NowMs;
            }
        }

        void NoteBadKey(string ip, string triedKey)
        {
            long now = NowMs;
            lock (badKeys)
            {
                BadKeyRecord b;
                if (!badKeys.TryGetValue(ip, out b) || now - b.WindowStart > BadKeyWindowMs)
                {
                    long blocked = b != null ? b.BlockedUntil : 0;
                    b = new BadKeyRecord { WindowStart = now, BlockedUntil = blocked };
                    badKeys[ip] = b;
                }
                if (b.Keys.Count < MaxBadKeys) b.Keys.Add((triedKey ?? "").ToUpperInvariant());
                if (b.Keys.Count >= MaxBadKeys && b.BlockedUntil <= now)
                {
                    b.BlockedUntil = now + BlockMs;
                    Log.Warn("Too many wrong link keys from " + ip + " - ignoring it for 5 minutes");
                }
                if (badKeys.Count > 1000)   // keep memory bounded
                {
                    var stale = new List<string>();
                    foreach (var kv in badKeys) if (kv.Value.BlockedUntil <= now && now - kv.Value.WindowStart > BadKeyWindowMs) stale.Add(kv.Key);
                    foreach (var k in stale) badKeys.Remove(k);
                }
            }
        }

        static string IpOf(TcpClient c)
        {
            try { return ((IPEndPoint)c.Client.RemoteEndPoint).Address.ToString(); } catch { return "?"; }
        }
        readonly List<PhoneConn> conns = new List<PhoneConn>();
        readonly object lk = new object();
        readonly Stopwatch clock = Stopwatch.StartNew();
        volatile bool mirroring;
        volatile int rate;
        uint seq;
        long bytesSent;

        public PhoneLink(int port, string linkKey) : this(port, linkKey, IPAddress.Any) { }

        public PhoneLink(int port, string linkKey, IPAddress bind)
        {
            Port = port;
            key = linkKey;
            bindAddress = bind;
        }

        public string Key
        {
            get { return key; }
            set { key = value; DisconnectAll(); }
        }

        public long BytesSent { get { return Interlocked.Read(ref bytesSent); } }
        public long NowMs { get { return clock.ElapsedMilliseconds; } }

        public bool Start()
        {
            try
            {
                listener = new TcpListener(bindAddress, Port);
                listener.Start();
            }
            catch (Exception ex)
            {
                Error = ex.Message;
                Log.Warn("Phone link start: " + ex.Message);
                return false;
            }
            acceptThread = new Thread(AcceptLoop) { IsBackground = true, Name = "PhoneAccept" };
            acceptThread.Start();
            pingThread = new Thread(PingLoop) { IsBackground = true, Name = "PhonePing" };
            pingThread.Start();
            Log.Info("Phone link listening on port " + Port);
            return true;
        }

        public void Stop()
        {
            stopFlag = true;
            try { listener.Stop(); } catch { }
            DisconnectAll();
        }

        public void DisconnectAll()
        {
            PhoneConn[] arr;
            lock (lk) { arr = conns.ToArray(); conns.Clear(); }
            foreach (var c in arr) c.Close();
        }

        public int ClientCount
        {
            get { lock (lk) { conns.RemoveAll(c => c.Closed); return conns.Count; } }
        }

        public List<PhoneInfo> Snapshot()
        {
            var list = new List<PhoneInfo>();
            lock (lk)
            {
                conns.RemoveAll(c => c.Closed);
                foreach (var c in conns) list.Add(c.InfoCopy());
            }
            return list;
        }

        public void SetMirroring(bool on, int newRate)
        {
            mirroring = on;
            if (newRate > 0) rate = newRate;
            SendTextAll(string.Format(CultureInfo.InvariantCulture, "{{\"type\":\"state\",\"mirroring\":{0},\"rate\":{1}}}", on ? "true" : "false", on ? newRate : 0));
        }

        public string Url(string host)
        {
            return "http://" + host + ":" + Port.ToString(CultureInfo.InvariantCulture) + "/?k=" + key;
        }

        // ---------------------------------------------------------------- audio out
        public void Broadcast(float[] data, int frames, int sampleRate)
        {
            PhoneConn[] arr;
            lock (lk)
            {
                if (conns.Count == 0) return;
                arr = conns.ToArray();
            }
            rate = sampleRate;
            byte[] msg = BuildAudioFrame(data, frames, sampleRate, seq++);
            foreach (var c in arr) c.EnqueueAudio(msg);
            Interlocked.Add(ref bytesSent, (long)msg.Length * arr.Length);
        }

        // WebSocket binary frame: [rate int32][seq uint32][s16le stereo].
        public static byte[] BuildAudioFrame(float[] data, int frames, int sampleRate, uint sequence)
        {
            int payload = 8 + frames * 4;
            int hdr = HeaderSize(payload);
            byte[] f = new byte[hdr + payload];
            WriteHeader(f, 0x2, payload);
            int o = hdr;
            WriteInt32(f, o, sampleRate);
            WriteInt32(f, o + 4, unchecked((int)sequence));
            SampleConvert.ToPcm16(data, frames, f, o + 8);
            return f;
        }

        public static byte[] BuildTextFrame(string text) { return BuildFrame(0x1, Encoding.UTF8.GetBytes(text)); }

        public static byte[] BuildFrame(int opcode, byte[] payload)
        {
            int hdr = HeaderSize(payload.Length);
            byte[] f = new byte[hdr + payload.Length];
            WriteHeader(f, opcode, payload.Length);
            Buffer.BlockCopy(payload, 0, f, hdr, payload.Length);
            return f;
        }

        static int HeaderSize(int payload) { return payload < 126 ? 2 : (payload <= 65535 ? 4 : 10); }

        static void WriteHeader(byte[] f, int opcode, int payload)
        {
            f[0] = (byte)(0x80 | opcode);
            if (payload < 126) f[1] = (byte)payload;
            else if (payload <= 65535) { f[1] = 126; f[2] = (byte)(payload >> 8); f[3] = (byte)payload; }
            else { f[1] = 127; for (int i = 0; i < 8; i++) f[2 + i] = (byte)((long)payload >> (56 - 8 * i)); }
        }

        static void WriteInt32(byte[] b, int o, int v)
        {
            b[o] = (byte)v; b[o + 1] = (byte)(v >> 8); b[o + 2] = (byte)(v >> 16); b[o + 3] = (byte)(v >> 24);
        }

        void SendTextAll(string json)
        {
            PhoneConn[] arr;
            lock (lk) arr = conns.ToArray();
            if (arr.Length == 0) return;
            byte[] f = BuildTextFrame(json);
            foreach (var c in arr) c.EnqueueControl(f);
        }

        // ---------------------------------------------------------------- housekeeping
        void PingLoop()
        {
            while (!stopFlag)
            {
                Thread.Sleep(2000);
                long now = NowMs;
                SendTextAll("{\"type\":\"ping\",\"t\":" + now.ToString(CultureInfo.InvariantCulture) + "}");
                PhoneConn[] arr;
                lock (lk) arr = conns.ToArray();
                foreach (var c in arr)
                    if (now - c.LastSeenMs > IdleTimeoutMs) { Log.Info("Phone idle, closing: " + c.Remote); c.Close(); }
                lock (lk) conns.RemoveAll(c => c.Closed);
            }
        }

        void AcceptLoop()
        {
            while (!stopFlag)
            {
                try
                {
                    var c = listener.AcceptTcpClient();
                    if (IsBlocked(IpOf(c)) || !pending.Wait(0))
                    {
                        // too many half-open requests: drop this one immediately
                        try { c.Client.LingerState = new LingerOption(true, 0); c.Close(); } catch { }
                        continue;
                    }
                    new Thread(() => { try { Handle(c); } finally { pending.Release(); } }) { IsBackground = true, Name = "PhoneHttp" }.Start();
                }
                catch
                {
                    if (stopFlag) return;
                    Thread.Sleep(200);
                }
            }
        }

        // ---------------------------------------------------------------- HTTP
        void Handle(TcpClient c)
        {
            try
            {
                c.NoDelay = true;
                c.SendTimeout = 3000;
                var ns = c.GetStream();
                string req = ReadHeaders(ns);
                if (req == null) { c.Close(); return; }
                var lines = req.Split(new[] { "\r\n" }, StringSplitOptions.None);
                var parts = lines[0].Split(' ');
                if (parts.Length < 2 || parts[0] != "GET") { Respond(ns, "405 Method Not Allowed", "text/plain", Encoding.ASCII.GetBytes("Only GET")); c.Close(); return; }
                string target = parts[1];
                string path = target;
                var query = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
                int qi = target.IndexOf('?');
                if (qi >= 0)
                {
                    path = target.Substring(0, qi);
                    ParseQuery(target.Substring(qi + 1), query);
                }
                var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
                for (int i = 1; i < lines.Length; i++)
                {
                    int ci = lines[i].IndexOf(':');
                    if (ci > 0) headers[lines[i].Substring(0, ci).Trim()] = lines[i].Substring(ci + 1).Trim();
                }

                if (path == "/favicon.ico") { Respond(ns, "204 No Content", "text/plain", new byte[0]); c.Close(); return; }

                string given;
                query.TryGetValue("k", out given);
                bool authed = LinkKey.Matches(key, given);
                if (!authed) NoteBadKey(IpOf(c), given);

                if (path == "/ws")
                {
                    string wsKey;
                    if (!headers.TryGetValue("Sec-WebSocket-Key", out wsKey)) { Respond(ns, "400 Bad Request", "text/plain", Encoding.ASCII.GetBytes("WebSocket only")); c.Close(); return; }
                    if (!authed) { Respond(ns, "403 Forbidden", "text/plain", Encoding.ASCII.GetBytes("Wrong link key")); c.Close(); return; }
                    string name, client;
                    query.TryGetValue("name", out name);
                    query.TryGetValue("client", out client);
                    var conn = new PhoneConn(this, c, ns, Clip(name, 60, "Phone"), client == "android" ? "android" : "web");
                    // Key, stop flag and phone limit are re-checked under the same lock that New link / Stop use,
                    // so a phone can't slip in with an old key or after the link stopped.
                    string refuse = null;
                    lock (lk)
                    {
                        conns.RemoveAll(x => x.Closed);
                        if (stopFlag) refuse = "503 Service Unavailable";
                        else if (!LinkKey.Matches(key, given)) refuse = "403 Forbidden";
                        else if (conns.Count >= MaxClients) refuse = "503 Service Unavailable";
                        else conns.Add(conn);
                    }
                    if (refuse != null)
                    {
                        Respond(ns, refuse, "text/plain", Encoding.ASCII.GetBytes(refuse.StartsWith("403") ? "Wrong link key" : "Too many phones"));
                        c.Close();
                        return;
                    }
                    byte[] resp = Encoding.ASCII.GetBytes("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + AcceptKey(wsKey) + "\r\n\r\n");
                    try { ns.Write(resp, 0, resp.Length); }
                    catch { lock (lk) conns.Remove(conn); c.Close(); return; }
                    conn.EnqueueControl(BuildTextFrame(string.Format(CultureInfo.InvariantCulture,
                        "{{\"type\":\"hello\",\"protocol\":{0},\"pc\":\"{1}\",\"version\":\"{2}\",\"rate\":{3}}}",
                        AppInfo.Protocol, MiniJson.Escape(PcName), AppInfo.Version, mirroring ? rate : 0)));
                    conn.Begin();
                    Log.Info("Phone connected: " + conn.Remote + " (" + conn.Name + ", " + conn.Client + ")");
                    return;
                }

                if (path == "/" || path == "/index.html")
                {
                    if (!authed) { Respond(ns, "403 Forbidden", "text/html; charset=utf-8", Encoding.UTF8.GetBytes(WebPlayer.ForbiddenHtml)); c.Close(); return; }
                    Respond(ns, "200 OK", "text/html; charset=utf-8", Encoding.UTF8.GetBytes(WebPlayer.Html));
                    c.Close();
                    return;
                }

                if (path == "/info")
                {
                    if (!authed) { Respond(ns, "403 Forbidden", "application/json", Encoding.ASCII.GetBytes("{\"error\":\"key\"}")); c.Close(); return; }
                    string json = string.Format(CultureInfo.InvariantCulture,
                        "{{\"app\":\"DualEarbuds\",\"protocol\":{0},\"version\":\"{1}\",\"pc\":\"{2}\",\"rate\":{3},\"channels\":2,\"format\":\"s16le\"}}",
                        AppInfo.Protocol, AppInfo.Version, MiniJson.Escape(PcName), mirroring ? rate : 0);
                    Respond(ns, "200 OK", "application/json", Encoding.UTF8.GetBytes(json));
                    c.Close();
                    return;
                }

                Respond(ns, "404 Not Found", "text/plain", Encoding.ASCII.GetBytes("Not found"));
                c.Close();
            }
            catch { try { c.Close(); } catch { } }
        }

        static string Clip(string s, int max, string def)
        {
            if (string.IsNullOrEmpty(s)) return def;
            var sb = new StringBuilder();
            foreach (char ch in s) if (!char.IsControl(ch)) sb.Append(ch);
            string r = sb.ToString().Trim();
            if (r.Length == 0) return def;
            return r.Length > max ? r.Substring(0, max) : r;
        }

        public static void ParseQuery(string q, Dictionary<string, string> into)
        {
            foreach (var pair in q.Split('&'))
            {
                if (pair.Length == 0) continue;
                int e = pair.IndexOf('=');
                string k = e < 0 ? pair : pair.Substring(0, e);
                string v = e < 0 ? "" : pair.Substring(e + 1);
                try
                {
                    k = Uri.UnescapeDataString(k.Replace('+', ' '));
                    v = Uri.UnescapeDataString(v.Replace('+', ' '));
                }
                catch { continue; }
                into[k] = v;
            }
        }

        public static string AcceptKey(string wsKey)
        {
            using (var sha = SHA1.Create())
                return Convert.ToBase64String(sha.ComputeHash(Encoding.ASCII.GetBytes(wsKey.Trim() + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")));
        }

        static void Respond(NetworkStream ns, string status, string type, byte[] body)
        {
            byte[] head = Encoding.ASCII.GetBytes("HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.Length +
                                                  "\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n");
            ns.Write(head, 0, head.Length);
            if (body.Length > 0) ns.Write(body, 0, body.Length);
            ns.Flush();
        }

        // Reads the request head. The whole head must arrive within HeaderDeadlineMs (not per byte).
        // Reads one byte at a time on purpose: bytes after the head belong to the WebSocket stream.
        static string ReadHeaders(NetworkStream ns)
        {
            var sb = new StringBuilder();
            var b = new byte[1];
            var sw = Stopwatch.StartNew();
            try
            {
                while (sb.Length < MaxHeaderBytes)
                {
                    int left = HeaderDeadlineMs - (int)sw.ElapsedMilliseconds;
                    if (left <= 0) return null;
                    ns.ReadTimeout = left;
                    int n = ns.Read(b, 0, 1);
                    if (n <= 0) return null;
                    sb.Append((char)b[0]);
                    int L = sb.Length;
                    if (L >= 4 && sb[L - 1] == '\n' && sb[L - 2] == '\r' && sb[L - 3] == '\n' && sb[L - 4] == '\r')
                    {
                        ns.ReadTimeout = Timeout.Infinite;
                        return sb.ToString();
                    }
                }
            }
            catch (IOException) { }
            return null;
        }

        internal void OnClientText(PhoneConn c, string text)
        {
            Dictionary<string, object> d;
            try { d = MiniJson.ParseObject(text); }
            catch { return; }
            string type = MiniJson.GetString(d, "type", "");
            if (type == "pong")
            {
                object tv;
                if (d.TryGetValue("t", out tv) && tv is double && (double)tv >= 0)
                    c.SetRtt((int)Math.Min(int.MaxValue, Math.Max(0, NowMs - (double)tv)));
            }
            else if (type == "status")
            {
                string earb = MiniJson.GetString(d, "earbuds", null);
                c.UpdateStatus(
                    Clip(MiniJson.GetString(d, "device", null), 60, null),
                    earb == null ? null : Clip(earb, 60, ""),
                    MiniJson.GetInt(d, "battery", -1),
                    MiniJson.GetInt(d, "volume", -1),
                    MiniJson.GetInt(d, "bufferMs", -1),
                    MiniJson.GetBool(d, "playing", false));
            }
        }

        // ---------------------------------------------------------------- addresses
        // The laptop's addresses on local networks, Wi-Fi first.
        public static List<string> LanAddresses()
        {
            var wifi = new List<string>();
            var other = new List<string>();
            try
            {
                foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
                {
                    if (ni.OperationalStatus != OperationalStatus.Up || ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
                    var props = ni.GetIPProperties();
                    bool hasGateway = props.GatewayAddresses.Any(g => g.Address.AddressFamily == AddressFamily.InterNetwork && !g.Address.Equals(IPAddress.Any));
                    foreach (var ua in props.UnicastAddresses)
                    {
                        if (ua.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                        string a = ua.Address.ToString();
                        if (a.StartsWith("169.254.")) continue;
                        if (ni.NetworkInterfaceType == NetworkInterfaceType.Wireless80211 && hasGateway) wifi.Add(a);
                        else if (hasGateway) other.Add(a);
                    }
                }
            }
            catch { }
            wifi.AddRange(other);
            return wifi;
        }

        public static string LanAddress()
        {
            var l = LanAddresses();
            return l.Count > 0 ? l[0] : null;
        }
    }

    // One connected phone.
    class PhoneConn
    {
        readonly PhoneLink owner;
        readonly TcpClient tcp;
        readonly NetworkStream ns;
        readonly LinkedList<byte[]> audio = new LinkedList<byte[]>();
        readonly Queue<byte[]> control = new Queue<byte[]>();
        readonly object lk = new object();
        readonly AutoResetEvent signal = new AutoResetEvent(false);
        readonly PhoneInfo info = new PhoneInfo();
        int closed;
        long lastSeen;

        public PhoneConn(PhoneLink owner, TcpClient t, NetworkStream s, string name, string client)
        {
            this.owner = owner;
            tcp = t;
            ns = s;
            info.Id = Guid.NewGuid().ToString("N");
            info.Name = name;
            info.Client = client;
            try { info.Remote = ((IPEndPoint)t.Client.RemoteEndPoint).Address.ToString(); } catch { }
            info.ConnectedAt = DateTime.Now;
            lastSeen = owner.NowMs;
        }

        public bool Closed { get { return closed != 0; } }
        public string Remote { get { return info.Remote; } }
        public string Name { get { return info.Name; } }
        public string Client { get { return info.Client; } }
        public long LastSeenMs { get { return Interlocked.Read(ref lastSeen); } }

        public void Begin()
        {
            new Thread(SendLoop) { IsBackground = true, Name = "PhoneSend" }.Start();
            new Thread(ReadLoop) { IsBackground = true, Name = "PhoneRead" }.Start();
        }

        public PhoneInfo InfoCopy()
        {
            lock (lk)
            {
                return new PhoneInfo
                {
                    Id = info.Id, Name = info.Name, Client = info.Client, Remote = info.Remote, Earbuds = info.Earbuds,
                    Battery = info.Battery, Volume = info.Volume, BufferMs = info.BufferMs, Playing = info.Playing,
                    RttMs = info.RttMs, ConnectedAt = info.ConnectedAt, PacketsSent = info.PacketsSent, PacketsDropped = info.PacketsDropped
                };
            }
        }

        public void SetRtt(int ms) { lock (lk) info.RttMs = ms; }

        public void UpdateStatus(string device, string earbuds, int battery, int volume, int bufferMs, bool playing)
        {
            lock (lk)
            {
                if (!string.IsNullOrEmpty(device)) info.Name = device.Length > 60 ? device.Substring(0, 60) : device;
                if (earbuds != null) info.Earbuds = earbuds.Length > 60 ? earbuds.Substring(0, 60) : earbuds;
                info.Battery = battery >= 0 && battery <= 100 ? battery : -1;
                if (volume >= 0) info.Volume = Math.Min(150, volume);
                if (bufferMs >= 0) info.BufferMs = Math.Min(5000, bufferMs);
                info.Playing = playing;
            }
        }

        public void EnqueueAudio(byte[] frame)
        {
            if (Closed) return;
            lock (lk)
            {
                audio.AddLast(frame);
                info.PacketsSent++;
                while (audio.Count > PhoneLink.AudioQueueLimit) { audio.RemoveFirst(); info.PacketsDropped++; }
            }
            signal.Set();
        }

        public void EnqueueControl(byte[] frame)
        {
            if (Closed) return;
            lock (lk) control.Enqueue(frame);
            signal.Set();
        }

        void SendLoop()
        {
            try
            {
                while (!Closed)
                {
                    signal.WaitOne(500);
                    while (!Closed)
                    {
                        byte[] f = null;
                        lock (lk)
                        {
                            if (control.Count > 0) f = control.Dequeue();
                            else if (audio.Count > 0) { f = audio.First.Value; audio.RemoveFirst(); }
                        }
                        if (f == null) break;
                        ns.Write(f, 0, f.Length);
                    }
                }
            }
            catch { }
            Close();
        }

        void ReadLoop()
        {
            try
            {
                var hdr = new byte[14];
                while (!Closed)
                {
                    if (!ReadExact(hdr, 0, 2)) break;
                    bool fin = (hdr[0] & 0x80) != 0;
                    int opcode = hdr[0] & 0x0F;
                    bool masked = (hdr[1] & 0x80) != 0;
                    long len = hdr[1] & 0x7F;
                    if (len == 126)
                    {
                        if (!ReadExact(hdr, 2, 2)) break;
                        len = (hdr[2] << 8) | hdr[3];
                    }
                    else if (len == 127)
                    {
                        if (!ReadExact(hdr, 2, 8)) break;
                        len = 0;
                        for (int i = 0; i < 8; i++) len = (len << 8) | hdr[2 + i];
                    }
                    if (!masked || len > PhoneLink.MaxClientMessage || len < 0) break;   // clients must mask; cap size
                    var mask = new byte[4];
                    if (!ReadExact(mask, 0, 4)) break;
                    var payload = new byte[len];
                    if (len > 0 && !ReadExact(payload, 0, (int)len)) break;
                    for (int i = 0; i < payload.Length; i++) payload[i] ^= mask[i & 3];
                    Interlocked.Exchange(ref lastSeen, owner.NowMs);

                    if (opcode == 0x8)
                    {
                        EnqueueControl(PhoneLink.BuildFrame(0x8, new byte[0]));
                        Thread.Sleep(100);
                        break;
                    }
                    if (opcode == 0x9) { if (payload.Length <= 125) EnqueueControl(PhoneLink.BuildFrame(0xA, payload)); continue; }
                    if (opcode == 0xA) continue;
                    if (opcode == 0x1 && fin) owner.OnClientText(this, Encoding.UTF8.GetString(payload));
                    // binary and fragmented messages are not part of the protocol: ignore them
                }
            }
            catch { }
            Close();
        }

        bool ReadExact(byte[] buf, int offset, int count)
        {
            int got = 0;
            while (got < count)
            {
                int n = ns.Read(buf, offset + got, count - got);
                if (n <= 0) return false;
                got += n;
            }
            return true;
        }

        public void Close()
        {
            if (Interlocked.Exchange(ref closed, 1) != 0) return;
            try { tcp.Close(); } catch { }
            signal.Set();
            Log.Info("Phone disconnected: " + info.Remote);
        }
    }
}
