// Dual Earbuds Pro - engine tests (run with Mono: no audio hardware needed).
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using DualEarbuds;

static class EngineTests
{
    static int passed, failed;

    static void Check(bool cond, string name)
    {
        if (cond) { passed++; Console.WriteLine("  ok   " + name); }
        else { failed++; Console.WriteLine("  FAIL " + name); }
    }

    static int Main()
    {
        string tmp = Path.Combine(Path.GetTempPath(), "deb-tests-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(tmp);
        Environment.SetEnvironmentVariable("DUALEARBUDS_DATA", tmp);

        Run("Ring buffer", TestRing);
        Run("Output delay / gain / drift", TestOutput);
        Run("Sample conversion", TestConvert);
        Run("Link keys", TestKeys);
        Run("Settings", () => TestSettings(tmp));
        Run("Mini JSON", TestJson);
        Run("QR code", TestQr);
        Run("Phone link HTTP", TestHttp);
        Run("Phone link WebSocket", TestWebSocket);
        Run("Phone link limits", TestLimits);
        Run("Surround downmix", TestDownmix);
        Run("Slow-header protection", TestSlowHeaders);
        Run("Wrong-key lockout", TestLockout);
        Run("Network category parsing", TestNetCategory);
        Run("Web player resource", TestPlayerResource);
        Run("10 ms packets", TestPacketizer);
        Run("Party mode", TestParty);
        Run("Listener control + kick", TestControlKick);
        Run("Version compare", TestVersions);
        Run("Release JSON", TestReleaseJson);
        Run("Updater safety checks", TestUpdaterChecks);
        Run("Nested JSON", TestNestedJson);
        Run("Firewall script", TestFirewallScript);
        Run("Biquad + EQ chain", TestEq);
        Run("Dialogue boost", TestDialogue);
        Run("P-square quantile", TestP2);
        Run("Jitter buffer (Smart / Steady / Manual)", TestJitterBuffer);
        Run("Hearing staircase -> EQ", TestStaircase);
        Run("Rate limiter", TestRateLimiter);
        Run("FFT", TestFft);
        Run("Noise suppressor", TestNoise);
        Run("Sound classifier", TestClassifier);
        Run("Clock sync", TestClockSync);
        Run("Sync trim", TestSyncTrim);
        Run("Resampler", TestResampler);
        Run("Packet-loss concealment", TestConcealer);
        Run("Calibration cross-correlation", TestCalibration);
        Run("Host v3.1: PIN, sync, clock, remote, talk, source, EQ", TestHostV31);
        Run("mDNS / DNS-SD messages", TestDiscovery);
        Run("Join by link: parsing", TestLinkParse);
        Run("Laptop to laptop: loopback host + listener", TestLoopbackListen);

        Console.WriteLine();
        Console.WriteLine(string.Format("{0} passed, {1} failed", passed, failed));
        try { Directory.Delete(tmp, true); } catch { }
        return failed == 0 ? 0 : 1;
    }

    static void Run(string name, Action a)
    {
        Console.WriteLine(name);
        try { a(); }
        catch (Exception ex) { failed++; Console.WriteLine("  FAIL threw " + ex); }
    }

    // ------------------------------------------------------------------ pure logic
    static float[] Frames(int n, float start)
    {
        var f = new float[n * 2];
        for (int i = 0; i < n; i++) { f[i * 2] = start + i; f[i * 2 + 1] = -(start + i); }
        return f;
    }

    static void TestRing()
    {
        var r = new Ring(10);
        r.Write(Frames(6, 0), 6);
        var dst = new float[20];
        Check(r.Read(dst, 0, 4) == 4 && dst[0] == 0 && dst[6] == 3, "read in order");
        r.Write(Frames(6, 100), 6);                 // wraps around the end
        Check(r.Count == 8, "count after wrap");
        int n = r.Read(dst, 0, 10);
        Check(n == 8 && dst[0] == 4 && dst[2 * 2] == 100 && dst[7 * 2] == 105 && dst[7 * 2 + 1] == -105, "wrapped data intact");
        r.Write(Frames(8, 0), 8);
        r.Write(Frames(5, 50), 5);                  // overflow drops the oldest
        Check(r.Count == 10, "overflow keeps capacity");
        r.Read(dst, 0, 10);
        Check(dst[0] == 3 && dst[9 * 2] == 54, "overflow keeps newest");
        r.Write(Frames(25, 0), 25);                 // bigger than the ring
        r.Read(dst, 0, 10);
        Check(r.Count == 0 && dst[0] == 15 && dst[9 * 2] == 24, "oversized write keeps last frames");
        r.Write(Frames(4, 0), 4);
        r.Discard(10);
        Check(r.Count == 0, "discard more than count");
    }

    static void TestOutput()
    {
        int rate = 1000;                              // 1 frame = 1 ms, easy arithmetic
        var o = new OutputStream("test", rate, 100, 0.5f);
        o.MarkRunningForTest();
        var dst = new float[4000];
        Check(o.Fill(dst, 50, true) == 0, "nothing before first sound");
        o.Push(Frames(20, 1), 20);
        int got = o.Fill(dst, 200, false);
        // cushion 15 ms + delay 100 ms of silence, then 20 frames of sound at half volume
        Check(got == 135, "silence for delay+cushion then sound (" + got + ")");
        Check(dst[0] == 0 && dst[114 * 2] == 0 && dst[115 * 2] == 0.5f && dst[134 * 2] == 10f, "delay and gain applied");
        Check(o.Fill(dst, 10, true) == 0, "drained");
        o.Push(Frames(10, 1), 10);
        Check(o.Fill(dst, 500, false) == 125, "re-primes after draining");
        // drift: push far more than target + 80 ms, the oldest audio is dropped
        var big = Frames(500, 0);
        o.Push(big, 500);
        got = o.Fill(dst, 2000, false);
        Check(got <= 115 + 80 + 1, "drift limit trims backlog (" + got + ")");
        o.SetDelayMs(0);
        o.Push(Frames(10, 7), 10);
        o.Fill(dst, 2000, false);
        o.Fill(dst, 10, true);
        o.Push(Frames(10, 7), 10);
        Check(o.Fill(dst, 500, false) == 25, "delay change applies (" + "cushion 15 + 10" + ")");
        Check(o.SourceFrames > 0 && o.TakePeak() > 0, "counters and peak");
    }

    static void TestConvert()
    {
        var f16 = new AudioFormat { Channels = 2, Bits = 16, BlockAlign = 4 };
        var raw = new byte[] { 0x00, 0x40, 0x00, 0xC0 };   // +0.5, -0.5
        var dst = new float[2];
        SampleConvert.ToStereoFloat(raw, 1, f16, dst);
        Check(Math.Abs(dst[0] - 0.5f) < 1e-4 && Math.Abs(dst[1] + 0.5f) < 1e-4, "16-bit stereo");
        var mono = new AudioFormat { Channels = 1, Bits = 16, BlockAlign = 2 };
        SampleConvert.ToStereoFloat(new byte[] { 0x00, 0x40 }, 1, mono, dst);
        Check(dst[0] == dst[1] && dst[0] > 0.49f, "mono duplicated");
        var f24 = new AudioFormat { Channels = 2, Bits = 24, BlockAlign = 6 };
        SampleConvert.ToStereoFloat(new byte[] { 0, 0, 0x40, 0, 0, 0xC0 }, 1, f24, dst);
        Check(Math.Abs(dst[0] - 0.5f) < 1e-4 && Math.Abs(dst[1] + 0.5f) < 1e-4, "24-bit sign extension");
        var ff = new AudioFormat { Channels = 6, Bits = 32, BlockAlign = 24, IsFloat = true };
        var rf = new byte[24];
        Buffer.BlockCopy(BitConverter.GetBytes(0.25f), 0, rf, 0, 4);
        Buffer.BlockCopy(BitConverter.GetBytes(-0.75f), 0, rf, 4, 4);
        SampleConvert.ToStereoFloat(rf, 1, ff, dst);
        Check(dst[0] > 0 && dst[1] < 0 && Math.Abs(dst[1] / dst[0] + 3f) < 1e-3, "float 5.1 keeps front L/R in their own ears");
        var pcm = new byte[8];
        SampleConvert.ToPcm16(new float[] { 2f, -2f, 0f, 0.5f }, 2, pcm, 0);
        Check(BitConverter.ToInt16(pcm, 0) == 32767 && BitConverter.ToInt16(pcm, 2) == -32767 && BitConverter.ToInt16(pcm, 4) == 0, "pcm16 clipping");
    }

    static void TestKeys()
    {
        var seen = new HashSet<string>();
        bool allValid = true;
        for (int i = 0; i < 200; i++) { string k = LinkKey.New(); allValid &= LinkKey.IsValid(k); seen.Add(k); }
        Check(allValid, "generated keys valid");
        Check(seen.Count == 200, "generated keys unique");
        Check(LinkKey.Matches("ABCD2345", "abcd2345"), "case-insensitive match");
        Check(!LinkKey.Matches("ABCD2345", "ABCD234"), "shorter key rejected");
        Check(!LinkKey.Matches("ABCD2345", "ABCD23456"), "longer key rejected");
        Check(!LinkKey.Matches("ABCD2345", null), "missing key rejected");
        Check(!LinkKey.IsValid("ABCD234!") && !LinkKey.IsValid("ABCD2341"), "invalid characters rejected");
    }

    static void TestSettings(string dir)
    {
        string path = Path.Combine(dir, "s.txt");
        var s = Settings.Load(path);
        Check(s.FirstRun && LinkKey.IsValid(s.LinkKey), "defaults with a fresh key");
        s.Theme = "light"; s.CloseToTray = false; s.PhonePort = 9000; s.SourceId = "{dev-1}";
        var o = s.For("{0.0.0.00000000}.{abc}"); o.Enabled = true; o.DelayMs = 120; o.Volume = 80;
        s.Save(path);
        var t = Settings.Load(path);
        Check(!t.FirstRun && t.Theme == "light" && !t.CloseToTray && t.PhonePort == 9000 && t.SourceId == "{dev-1}" && t.LinkKey == s.LinkKey, "round trip");
        var to = t.For("{0.0.0.00000000}.{abc}");
        Check(to.Enabled && to.DelayMs == 120 && to.Volume == 80, "outputs round trip");
        File.WriteAllLines(path, new[] { "port\t80", "theme\tpurple", "key\tbad", "out\tx\t1\t99999\t-5", "garbage" });
        var u = Settings.Load(path);
        Check(u.PhonePort == 8790 && u.Theme == "system" && LinkKey.IsValid(u.LinkKey), "bad values ignored");
        Check(u.For("x").DelayMs == 1000 && u.For("x").Volume == 0, "out values clamped");
        Check(u.CheckUpdates && !u.Party && u.NetworkScope == Settings.NetScopeAll && u.LastUpdateCheckUtc == DateTime.MinValue, "v3 defaults: auto update on, party off, private+public");

        var v = Settings.Load(path);
        var when = new DateTime(2026, 10, 21, 8, 30, 0, DateTimeKind.Utc);
        v.Party = true; v.CheckUpdates = false; v.LastUpdateCheckUtc = when; v.NetworkScope = Settings.NetScopePrivate;
        v.Save(path);
        var w = Settings.Load(path);
        Check(w.Party && !w.CheckUpdates && w.LastUpdateCheckUtc == when && w.LastUpdateCheckUtc.Kind == DateTimeKind.Utc && w.NetworkScope == "private", "v3 settings round trip");
        File.WriteAllLines(path, new[] { "netscope\teverything", "updatechecked\tsoon" });
        var x = Settings.Load(path);
        Check(x.NetworkScope == Settings.NetScopeAll && x.LastUpdateCheckUtc == DateTime.MinValue, "bad v3 values ignored");
    }

    static void TestJson()
    {
        var d = MiniJson.ParseObject("{\"type\":\"status\",\"device\":\"Redmi \\\"Note\\\" 12\",\"battery\":80,\"volume\":1.5e2,\"playing\":true,\"x\":{\"a\":[1,2,{\"b\":\"}\"}]},\"n\":null,\"u\":\"\\u0633\"}");
        Check(MiniJson.GetString(d, "type", "") == "status", "string");
        Check(MiniJson.GetString(d, "device", "") == "Redmi \"Note\" 12", "escaped quotes");
        Check(MiniJson.GetInt(d, "battery", -1) == 80 && MiniJson.GetInt(d, "volume", -1) == 150, "numbers");
        Check(MiniJson.GetBool(d, "playing", false), "bool");
        Check(MiniJson.GetString(d, "u", "") == "\u0633", "unicode escape");
        Check(MiniJson.GetInt(d, "missing", -7) == -7, "default for missing");
        bool threw = false;
        try { MiniJson.ParseObject("{\"a\":"); } catch (FormatException) { threw = true; }
        Check(threw, "truncated input rejected");
        Check(MiniJson.Escape("a\"b\\c\n") == "a\\\"b\\\\c\\n", "escape");
    }

    static void TestQr()
    {
        var m = QrCode.Encode("http://192.168.1.16:8790/?k=ABCD2345");
        Check(m.GetLength(0) == 29 && m.GetLength(1) == 29, "version 3 size");
        Check(m[0, 0] && m[6, 6] && !m[7, 7] && m[0, 28 - 6] && m[28 - 6, 0], "finder patterns present");
    }

    // ------------------------------------------------------------------ phone link
    static int FreePort()
    {
        var l = new TcpListener(IPAddress.Loopback, 0);
        l.Start();
        int p = ((IPEndPoint)l.LocalEndpoint).Port;
        l.Stop();
        return p;
    }

    static string Http(int port, string requestLine)
    {
        using (var c = new TcpClient("127.0.0.1", port))
        {
            var ns = c.GetStream();
            byte[] req = Encoding.ASCII.GetBytes(requestLine + "\r\nHost: x\r\n\r\n");
            ns.Write(req, 0, req.Length);
            ns.ReadTimeout = 3000;
            var ms = new MemoryStream();
            var buf = new byte[8192];
            int n;
            try { while ((n = ns.Read(buf, 0, buf.Length)) > 0) ms.Write(buf, 0, n); } catch (IOException) { }
            return Encoding.UTF8.GetString(ms.ToArray());
        }
    }

    static void TestHttp()
    {
        int port = FreePort();
        var link = new PhoneLink(port, "ABCD2345", IPAddress.Loopback);
        Check(link.Start(), "server starts");
        try
        {
            Check(Http(port, "GET / HTTP/1.1").StartsWith("HTTP/1.1 403"), "page without key is 403");
            Check(Http(port, "GET /?k=WRONGKEY HTTP/1.1").StartsWith("HTTP/1.1 403"), "page with wrong key is 403");
            string page = Http(port, "GET /?k=abcd2345 HTTP/1.1");
            Check(page.StartsWith("HTTP/1.1 200") && page.Contains("Dual Earbuds") && page.Contains("mediaSession"), "page with key (any case)");
            string info = Http(port, "GET /info?k=ABCD2345 HTTP/1.1");
            Check(info.StartsWith("HTTP/1.1 200") && info.Contains("\"protocol\":3") && info.Contains("\"format\":\"s16le\""), "info json");
            Check(info.Contains("\"host\":\"windows\"") && info.Contains("\"party\":false") && info.Contains("\"maxClients\":8") && info.Contains("\"version\":\"3.0.0\""), "info v3 fields");
            var infoJson = MiniJson.ParseObject(info.Substring(info.IndexOf("\r\n\r\n") + 4));
            Check(MiniJson.GetInt(infoJson, "protocol", 0) == 3 && MiniJson.GetString(infoJson, "app", "") == "DualEarbuds" && MiniJson.GetInt(infoJson, "rate", -1) == 0 && MiniJson.GetInt(infoJson, "channels", 0) == 2, "info json parses, rate 0 while not sharing");
            Check(page.Contains(WebPlayer.Html.Substring(0, 200)) && page.Length > WebPlayer.Html.Length, "served page is the embedded player");
            Check(Http(port, "GET /info HTTP/1.1").StartsWith("HTTP/1.1 403"), "info needs key");
            Check(Http(port, "GET /favicon.ico HTTP/1.1").StartsWith("HTTP/1.1 204"), "favicon 204");
            Check(Http(port, "GET /nope?k=ABCD2345 HTTP/1.1").StartsWith("HTTP/1.1 404"), "unknown path 404");
            Check(Http(port, "POST /?k=ABCD2345 HTTP/1.1").StartsWith("HTTP/1.1 405"), "POST rejected");
            Check(Http(port, "GET /ws?k=ABCD2345 HTTP/1.1").StartsWith("HTTP/1.1 400"), "ws path without upgrade 400");
            Check(Http(port, "GET /?k=%41BCD2345 HTTP/1.1").StartsWith("HTTP/1.1 200"), "url-encoded key");
        }
        finally { link.Stop(); }
    }

    // A tiny WebSocket client that behaves like a browser (masks its frames).
    class WsClient : IDisposable
    {
        public TcpClient Tcp;
        public NetworkStream Ns;
        public string Handshake;

        public static WsClient Open(int port, string query)
        {
            var w = new WsClient();
            w.Tcp = new TcpClient("127.0.0.1", port);
            w.Ns = w.Tcp.GetStream();
            w.Ns.ReadTimeout = 3000;
            string key = Convert.ToBase64String(Guid.NewGuid().ToByteArray());
            byte[] req = Encoding.ASCII.GetBytes("GET /ws?" + query + " HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n");
            w.Ns.Write(req, 0, req.Length);
            var sb = new StringBuilder();
            var b = new byte[1];
            while (!sb.ToString().EndsWith("\r\n\r\n"))
            {
                if (w.Ns.Read(b, 0, 1) <= 0) break;
                sb.Append((char)b[0]);
            }
            w.Handshake = sb.ToString();
            string expect;
            using (var sha = SHA1.Create())
                expect = Convert.ToBase64String(sha.ComputeHash(Encoding.ASCII.GetBytes(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")));
            w.AcceptOk = w.Handshake.Contains("Sec-WebSocket-Accept: " + expect);
            return w;
        }

        public bool AcceptOk;

        public void SendText(string s) { Send(0x1, Encoding.UTF8.GetBytes(s), true); }

        public void Send(int opcode, byte[] payload, bool mask)
        {
            var ms = new MemoryStream();
            ms.WriteByte((byte)(0x80 | opcode));
            int len = payload.Length;
            byte m = mask ? (byte)0x80 : (byte)0;
            if (len < 126) ms.WriteByte((byte)(m | len));
            else if (len <= 65535) { ms.WriteByte((byte)(m | 126)); ms.WriteByte((byte)(len >> 8)); ms.WriteByte((byte)len); }
            else { ms.WriteByte((byte)(m | 127)); for (int i = 7; i >= 0; i--) ms.WriteByte((byte)((long)len >> (8 * i))); }
            var key = new byte[] { 0x12, 0x34, 0x56, 0x78 };
            if (mask) ms.Write(key, 0, 4);
            for (int i = 0; i < len; i++) ms.WriteByte(mask ? (byte)(payload[i] ^ key[i & 3]) : payload[i]);
            byte[] f = ms.ToArray();
            Ns.Write(f, 0, f.Length);
        }

        // Returns opcode and payload of the next frame, or -1 on close/timeout.
        public int Receive(out byte[] payload)
        {
            payload = null;
            try
            {
                var h = new byte[2];
                if (!ReadExact(h, 2)) return -1;
                int op = h[0] & 0x0F;
                long len = h[1] & 0x7F;
                if (len == 126) { var e = new byte[2]; ReadExact(e, 2); len = (e[0] << 8) | e[1]; }
                else if (len == 127) { var e = new byte[8]; ReadExact(e, 8); len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | e[i]; }
                payload = new byte[len];
                if (len > 0 && !ReadExact(payload, (int)len)) return -1;
                return op;
            }
            catch (IOException) { return -1; }
        }

        bool ReadExact(byte[] b, int n)
        {
            int got = 0;
            while (got < n) { int r = Ns.Read(b, got, n - got); if (r <= 0) return false; got += r; }
            return true;
        }

        // Next text frame (skips audio), or null.
        public string NextText(int maxFrames)
        {
            for (int i = 0; i < maxFrames; i++)
            {
                byte[] p;
                int op = Receive(out p);
                if (op < 0) return null;
                if (op == 1) return Encoding.UTF8.GetString(p);
            }
            return null;
        }

        public bool IsClosedByServer()
        {
            try
            {
                for (int i = 0; i < 50; i++)
                {
                    byte[] p;
                    int op = Receive(out p);
                    if (op < 0 || op == 8) return true;
                }
            }
            catch { return true; }
            return false;
        }

        public void Dispose() { try { Tcp.Close(); } catch { } }
    }

    static bool WaitFor(Func<bool> cond, int ms)
    {
        var until = DateTime.Now.AddMilliseconds(ms);
        while (DateTime.Now < until) { if (cond()) return true; Thread.Sleep(20); }
        return cond();
    }

    static void TestWebSocket()
    {
        int port = FreePort();
        var link = new PhoneLink(port, "ABCD2345", IPAddress.Loopback);
        link.PcName = "TEST-PC";
        link.Start();
        try
        {
            using (var bad = WsClient.Open(port, "k=NOPE2345"))
                Check(bad.Handshake.StartsWith("HTTP/1.1 403"), "ws wrong key 403");

            using (var w = WsClient.Open(port, "k=ABCD2345&client=android&name=Redmi%20Note%2012"))
            {
                Check(w.Handshake.StartsWith("HTTP/1.1 101") && w.AcceptOk, "ws handshake + accept key");
                string hello = w.NextText(5);
                Check(hello != null && hello.Contains("\"type\":\"hello\"") && hello.Contains("TEST-PC") && hello.Contains("\"protocol\":3"), "hello message");
                var hj = hello == null ? new Dictionary<string, object>() : MiniJson.ParseObject(hello);
                Check(MiniJson.GetString(hj, "host", "") == "windows" && MiniJson.GetString(hj, "version", "") == AppInfo.Version && hj.ContainsKey("party") && !MiniJson.GetBool(hj, "party", true) && MiniJson.GetInt(hj, "rate", -1) == 0, "hello v3 fields");
                Check(WaitFor(() => link.ClientCount == 1, 1000), "client counted");
                var snap = link.Snapshot();
                Check(snap.Count == 1 && snap[0].Name == "Redmi Note 12" && snap[0].Client == "android", "name and client type from query");

                w.SendText("{\"type\":\"status\",\"device\":\"Redmi Note 12\",\"earbuds\":\"Evo Z-Buds\",\"battery\":80,\"volume\":120,\"bufferMs\":200,\"underruns\":3,\"playing\":true}");
                Check(WaitFor(() => { var s = link.Snapshot(); return s.Count == 1 && s[0].Battery == 80; }, 1500), "status received");
                var p0 = link.Snapshot()[0];
                Check(p0.Earbuds == "Evo Z-Buds" && p0.Volume == 120 && p0.BufferMs == 200 && p0.Playing, "status fields");
                Check(p0.Underruns == 3, "underruns parsed from status");
                Check(!string.IsNullOrEmpty(p0.Id) && link.Snapshot()[0].Id == p0.Id, "listener has a stable id");

                w.SendText("{\"type\":\"status\",\"battery\":250}");
                Check(WaitFor(() => link.Snapshot()[0].Battery == -1, 1500), "out-of-range battery becomes -1");
                w.SendText("not json at all");
                w.SendText("{\"type\":\"pong\",\"t\":" + link.NowMs + "}");
                Check(WaitFor(() => link.Snapshot()[0].RttMs >= 0, 1500), "pong sets round-trip time");

                link.SetMirroring(true, 48000);
                string state = w.NextText(10);
                Check(state != null && state.Contains("\"mirroring\":true") && state.Contains("48000"), "state message");

                var data = new float[480 * 2];
                for (int i = 0; i < 480; i++) { data[2 * i] = i == 0 ? 0.5f : 0.25f; data[2 * i + 1] = i == 0 ? -0.5f : -0.25f; }
                link.Broadcast(data, 480, 48000);
                link.Broadcast(data, 480, 48000);
                byte[] a1 = null, a2 = null;
                for (int i = 0; i < 10 && a2 == null; i++)
                {
                    byte[] p;
                    int op = w.Receive(out p);
                    if (op == 2) { if (a1 == null) a1 = p; else a2 = p; }
                    if (op < 0) break;
                }
                Check(a1 != null && a1.Length == 8 + 480 * 4 && BitConverter.ToInt32(a1, 0) == 48000, "audio packet header + size (10 ms)");
                Check(a1 != null && BitConverter.ToInt16(a1, 8) == 16383 && BitConverter.ToInt16(a1, 10) == -16383, "audio samples s16le");
                Check(a1 != null && a2 != null && BitConverter.ToUInt32(a2, 4) == BitConverter.ToUInt32(a1, 4) + 1, "sequence increments");

                w.Send(0x9, Encoding.ASCII.GetBytes("hi"), true);   // ping -> pong
                bool gotPong = false;
                for (int i = 0; i < 20; i++) { byte[] p; int op = w.Receive(out p); if (op == 0xA) { gotPong = Encoding.ASCII.GetString(p) == "hi"; break; } if (op < 0) break; }
                Check(gotPong, "ws ping answered with pong");

                w.Send(0x8, new byte[0], true);
                Check(w.IsClosedByServer(), "close frame handled");
                Check(WaitFor(() => link.ClientCount == 0, 2000), "client removed after close");
            }

            using (var w = WsClient.Open(port, "k=ABCD2345"))
            {
                w.NextText(3);
                w.Send(0x1, Encoding.ASCII.GetBytes("{\"type\":\"status\"}"), false);   // unmasked = protocol violation
                Check(w.IsClosedByServer(), "unmasked client frame closes connection");
            }
            using (var w = WsClient.Open(port, "k=ABCD2345"))
            {
                w.NextText(3);
                w.SendText(new string('x', 5000));
                Check(w.IsClosedByServer(), "oversized message closes connection");
            }
            using (var w = WsClient.Open(port, "k=ABCD2345"))
            {
                w.NextText(3);
                Check(WaitFor(() => link.ClientCount == 1, 1000), "fresh client");
                link.Key = "ZZZZ2345";
                Check(w.IsClosedByServer(), "changing the key disconnects phones");
                using (var old = WsClient.Open(port, "k=ABCD2345"))
                    Check(old.Handshake.StartsWith("HTTP/1.1 403"), "old key rejected after reset");
            }
        }
        finally { link.Stop(); }
    }

    static void TestLimits()
    {
        int port = FreePort();
        var link = new PhoneLink(port, "ABCD2345", IPAddress.Loopback);
        link.Start();
        var clients = new List<WsClient>();
        try
        {
            for (int i = 0; i < PhoneLink.MaxClients; i++)
            {
                var c = WsClient.Open(port, "k=ABCD2345");
                clients.Add(c);
            }
            Check(WaitFor(() => link.ClientCount == PhoneLink.MaxClients, 2000), "8 phones connected");
            using (var extra = WsClient.Open(port, "k=ABCD2345"))
                Check(extra.Handshake.StartsWith("HTTP/1.1 503"), "9th phone gets 503");

            // a phone that never reads: the server must keep at most ~600 ms queued, not grow forever
            var data = new float[960];
            for (int i = 0; i < 2000; i++) link.Broadcast(data, 480, 48000);
            Thread.Sleep(300);
            long dropped = 0;
            foreach (var p in link.Snapshot()) dropped += p.PacketsDropped;
            Check(dropped > 0, "slow phones get old packets dropped (" + dropped + ")");

            using (var raw = new TcpClient("127.0.0.1", port))
            {
                var ns = raw.GetStream();
                var junk = Encoding.ASCII.GetBytes("GET / HTTP/1.1\r\nX: " + new string('a', 9000));
                try { ns.Write(junk, 0, junk.Length); } catch { }
                ns.ReadTimeout = 3000;
                int n = 0;
                try { n = ns.Read(new byte[16], 0, 16); } catch (IOException) { }
                Check(n == 0, "oversized headers dropped");
            }
        }
        finally
        {
            foreach (var c in clients) c.Dispose();
            link.Stop();
        }
    }

    static void TestDownmix()
    {
        float[] gl, gr;
        SampleConvert.DownmixGains(6, 0x3F, out gl, out gr);
        Check(gl[2] > 0.3f && gr[2] > 0.3f && Math.Abs(gl[2] - gr[2]) < 1e-6, "5.1 centre goes to both ears");
        Check(gl[3] == 0 && gr[3] == 0, "LFE dropped");
        Check(gl[0] > gl[4] && gr[0] == 0 && gl[1] == 0, "front left only on left, louder than back");
        SampleConvert.DownmixGains(8, 0, out gl, out gr);
        Check(gl[6] > 0 && gr[7] > 0 && gr[6] == 0, "7.1 without mask uses the standard layout");

        // a 5.1 float frame with only the centre channel playing must not become silent
        var f = new AudioFormat { Channels = 6, Rate = 48000, Bits = 32, BlockAlign = 24, IsFloat = true, ChannelMask = 0x3F };
        var raw = new byte[24 * 2];
        Buffer.BlockCopy(BitConverter.GetBytes(0.5f), 0, raw, 8, 4);            // frame 0: centre = 0.5
        Buffer.BlockCopy(BitConverter.GetBytes(1f), 0, raw, 24 + 0, 4);         // frame 1: all loud
        for (int c = 1; c < 6; c++) Buffer.BlockCopy(BitConverter.GetBytes(1f), 0, raw, 24 + c * 4, 4);
        var dst = new float[4];
        SampleConvert.ToStereoFloat(raw, 2, f, dst);
        Check(dst[0] > 0.1f && dst[1] > 0.1f, "dialogue (centre) is heard: " + dst[0].ToString("0.00"));
        Check(dst[2] <= 1f && dst[3] <= 1f && dst[2] > 0.9f, "loud frames clamp to 1");
    }

    static void TestSlowHeaders()
    {
        int port = FreePort();
        var link = new PhoneLink(port, "ABCD2345", IPAddress.Loopback);
        link.Start();
        var slow = new List<TcpClient>();
        try
        {
            // fill every handshake slot with a connection that never finishes its headers
            for (int i = 0; i < PhoneLink.MaxPendingHandshakes; i++)
            {
                var t = new TcpClient("127.0.0.1", port);
                var b = Encoding.ASCII.GetBytes("GET / HT");
                t.GetStream().Write(b, 0, b.Length);
                slow.Add(t);
            }
            Thread.Sleep(300);
            // while full, extra connections are dropped straight away instead of piling up threads
            using (var extra = new TcpClient("127.0.0.1", port))
            {
                var ns = extra.GetStream();
                ns.ReadTimeout = 2000;
                int n = -1;
                try { n = ns.Read(new byte[8], 0, 8); } catch (IOException) { n = 0; }
                Check(n == 0, "extra half-open connection dropped while slots are full");
            }
            // the slow ones are cut at the header deadline even if they keep trickling bytes
            var sw = Stopwatch.StartNew();
            var first = slow[0].GetStream();
            first.ReadTimeout = 500;
            bool closed = false;
            while (sw.ElapsedMilliseconds < PhoneLink.HeaderDeadlineMs + 3000 && !closed)
            {
                try { first.Write(new byte[] { (byte)'x' }, 0, 1); } catch { closed = true; break; }
                try { if (first.Read(new byte[8], 0, 8) == 0) closed = true; } catch (IOException) { }
            }
            Check(closed && sw.ElapsedMilliseconds < PhoneLink.HeaderDeadlineMs + 3000, "trickling client cut after the header deadline (" + sw.ElapsedMilliseconds + " ms)");
            foreach (var t in slow) t.Close();
            slow.Clear();
            Thread.Sleep(PhoneLink.HeaderDeadlineMs + 500);
            // slots are free again: a real phone still connects
            using (var ok = WsClient.Open(port, "k=ABCD2345"))
                Check(ok.Handshake.StartsWith("HTTP/1.1 101"), "real phone connects after the attack ends");
        }
        finally
        {
            foreach (var t in slow) t.Close();
            link.Stop();
        }
    }

    static void TestLockout()
    {
        int port = FreePort();
        var link = new PhoneLink(port, "ABCD2345", IPAddress.Loopback);
        link.Start();
        try
        {
            // a stale tab repeating one old key: refused, but never locked out
            for (int i = 0; i < PhoneLink.MaxBadKeys * 2; i++) Http(port, "GET /?k=OLDK2345 HTTP/1.1");
            Check(Http(port, "GET /?k=ABCD2345 HTTP/1.1").StartsWith("HTTP/1.1 200"), "repeating one old key does not lock the phone out");

            // a guesser trying many different keys gets ignored
            bool all403 = true;
            for (int i = 0; i < PhoneLink.MaxBadKeys - 2; i++)
                all403 &= Http(port, "GET /?k=GUES" + (char)('A' + i) + "234 HTTP/1.1").StartsWith("HTTP/1.1 403");
            Check(all403, "wrong keys refused with 403");
            Check(Http(port, "GET /?k=ABCD2345 HTTP/1.1").StartsWith("HTTP/1.1 200"), "right key still works before the limit");
            Http(port, "GET /?k=GUESZ234 HTTP/1.1");   // 10th different wrong key
            string after;
            try { after = Http(port, "GET /?k=ABCD2345 HTTP/1.1"); } catch (Exception) { after = ""; }
            Check(!after.StartsWith("HTTP/1.1 200"), "a device that guessed many keys is ignored, even with the right key");
        }
        finally { link.Stop(); }
    }

    static void TestNetCategory()
    {
        string o = "7|Private\r\n12|Public\r\n";
        Check(NetworkInfo.Parse(o, 12) == "Public", "public adapter found");
        Check(NetworkInfo.Parse(o, 7) == "Private", "private adapter found");
        Check(NetworkInfo.Parse(o, 3) == null, "unknown adapter -> unknown");
        Check(NetworkInfo.Parse("", 3) == null && NetworkInfo.Parse("junk", 3) == null, "bad output -> unknown");
    }

    // ------------------------------------------------------------------ v3
    static void TestPlayerResource()
    {
        string fromResource;
        using (var st = typeof(WebPlayer).Assembly.GetManifestResourceStream(WebPlayer.ResourceName))
        {
            Check(st != null, "player page embedded as manifest resource 'web-player.html'");
            using (var r = new StreamReader(st, Encoding.UTF8)) fromResource = r.ReadToEnd();
        }
        Check(WebPlayer.Html == fromResource, "WebPlayer.Html is the embedded page");
        Check(ReferenceEquals(WebPlayer.Html, WebPlayer.Html) && ReferenceEquals(WebPlayer.HtmlBytes, WebPlayer.HtmlBytes), "page is cached");
        string shared = Path.Combine("shared", "web-player.html");
        if (File.Exists(shared)) Check(File.ReadAllText(shared, Encoding.UTF8) == WebPlayer.Html, "embedded page matches shared/web-player.html");
        Check(WebPlayer.Html.Contains("mediaSession") && WebPlayer.ForbiddenHtml.Contains("missing its key"), "player + forbidden pages");
    }

    static void TestPacketizer()
    {
        int port = FreePort();
        var link = new PhoneLink(port, "ABCD2345", IPAddress.Loopback);
        link.Start();
        try
        {
            using (var w = WsClient.Open(port, "k=ABCD2345"))
            {
                w.NextText(3);
                WaitFor(() => link.ClientCount == 1, 1000);
                Check(PhoneLink.PacketFrames(48000) == 480 && PhoneLink.PacketFrames(44100) == 441, "10 ms = 480 frames at 48 kHz, 441 at 44.1 kHz");
                // capture delivers odd sizes: 300 + 300 + 1100 frames = 1700 -> three 10 ms packets, 260 frames pending
                var d = new float[1100 * 2];
                for (int i = 0; i < d.Length / 2; i++) { d[2 * i] = 0.5f; d[2 * i + 1] = -0.5f; }
                link.Broadcast(d, 300, 48000);
                link.Broadcast(d, 300, 48000);
                link.Broadcast(d, 1100, 48000);
                var sizes = new List<int>();
                var seqs = new List<uint>();
                for (int i = 0; i < 10 && sizes.Count < 3; i++)
                {
                    byte[] p;
                    int op = w.Receive(out p);
                    if (op < 0) break;
                    if (op == 2) { sizes.Add(p.Length - 8); seqs.Add(BitConverter.ToUInt32(p, 4)); }
                }
                Check(sizes.Count == 3 && sizes.TrueForAll(n => n == 480 * 4), "odd capture sizes re-cut into exact 10 ms packets");
                Check(seqs.Count == 3 && seqs[1] == seqs[0] + 1 && seqs[2] == seqs[1] + 1, "sequence +1 per packet");
                // a rate change throws away the partial packet instead of mixing rates
                link.Broadcast(d, 441, 44100);
                byte[] q;
                int op2 = w.Receive(out q);
                Check(op2 == 2 && q.Length == 8 + 441 * 4 && BitConverter.ToInt32(q, 0) == 44100, "rate change starts a clean 10 ms packet");
            }
        }
        finally { link.Stop(); }
    }

    static void TestParty()
    {
        int port = FreePort();
        var link = new PhoneLink(port, "ABCD2345", IPAddress.Loopback);
        link.Start();
        var clients = new List<WsClient>();
        try
        {
            Check(PhoneLink.MaxClientsParty == 16 && link.Capacity == PhoneLink.MaxClients, "8 listeners normally");
            var first = WsClient.Open(port, "k=ABCD2345");
            clients.Add(first);
            first.NextText(3);
            link.Party = true;
            string msg = first.NextText(10);
            Check(msg != null && msg.Contains("\"type\":\"party\"") && msg.Contains("\"on\":true"), "party message broadcast");
            Check(link.Capacity == 16, "party allows 16");
            string info = Http(port, "GET /info?k=ABCD2345 HTTP/1.1");
            Check(info.Contains("\"party\":true") && info.Contains("\"maxClients\":16"), "info shows party + 16");
            using (var h = WsClient.Open(port, "k=ABCD2345"))
            {
                string hello = h.NextText(3);
                Check(hello != null && hello.Contains("\"party\":true"), "hello shows party");
            }
            WaitFor(() => link.ClientCount == 1, 2000);
            for (int i = 1; i < PhoneLink.MaxClientsParty; i++) clients.Add(WsClient.Open(port, "k=ABCD2345"));
            Check(WaitFor(() => link.ClientCount == 16, 3000), "16 listeners connected in party mode (" + link.ClientCount + ")");
            using (var extra = WsClient.Open(port, "k=ABCD2345"))
                Check(extra.Handshake.StartsWith("HTTP/1.1 503"), "17th listener gets 503");
            link.Party = false;
            Check(link.ClientCount == 16, "switching party off keeps connected listeners");
            using (var extra = WsClient.Open(port, "k=ABCD2345"))
                Check(extra.Handshake.StartsWith("HTTP/1.1 503"), "no new listeners above 8 after party off");
            Check(PhoneLink.PartyMessage(false) == "{\"type\":\"party\",\"on\":false}", "party off message");
        }
        finally
        {
            foreach (var c in clients) c.Dispose();
            link.Stop();
        }
    }

    static void TestControlKick()
    {
        Check(PhoneLink.ControlMessage(80) == "{\"type\":\"control\",\"volume\":80}", "control message");
        Check(PhoneLink.ControlMessage(400).Contains(":150}") && PhoneLink.ControlMessage(-3).Contains(":0}"), "control volume clamped to 0-150");
        var km = MiniJson.ParseObject(PhoneLink.KickMessage(null));
        Check(MiniJson.GetString(km, "type", "") == "kick" && MiniJson.GetString(km, "reason", "") == "Removed by the host", "kick message");

        int port = FreePort();
        var link = new PhoneLink(port, "ABCD2345", IPAddress.Loopback);
        link.Start();
        try
        {
            using (var a = WsClient.Open(port, "k=ABCD2345&name=A"))
            using (var b = WsClient.Open(port, "k=ABCD2345&name=B"))
            {
                a.NextText(3); b.NextText(3);
                Check(WaitFor(() => link.ClientCount == 2, 1000), "two listeners");
                var snap = link.Snapshot();
                string idA = snap.Find(p => p.Name == "A").Id, idB = snap.Find(p => p.Name == "B").Id;
                Check(idA != idB, "ids are distinct");
                Check(link.SetVolume(idA, 135), "set volume on a listener");
                string ctl = a.NextText(10);
                Check(ctl == "{\"type\":\"control\",\"volume\":135}", "listener receives control");
                Check(link.Snapshot().Find(p => p.Id == idA).Volume == 135, "host shows the new volume right away");
                Check(!link.SetVolume("nope", 50), "unknown id ignored");

                Check(link.Kick(idB), "kick a listener");
                string kick = b.NextText(10);
                Check(kick != null && kick.Contains("\"type\":\"kick\""), "listener receives kick first");
                Check(b.IsClosedByServer(), "kicked listener is closed");
                Check(WaitFor(() => link.ClientCount == 1, 2000) && link.Snapshot()[0].Id == idA, "other listener stays");
                Check(!link.Kick(idB), "kicking twice is harmless");
            }
        }
        finally { link.Stop(); }
    }

    static void TestVersions()
    {
        Check(Updater.CompareVersions("v3.0.0", "3.0.0") == 0, "v prefix ignored");
        Check(Updater.CompareVersions("v3.0.10", "v3.0.9") > 0, "numeric, not text, compare");
        Check(Updater.CompareVersions("v10.0.0", "v9.9.9") > 0, "major wins");
        Check(Updater.CompareVersions("3.1", "3.1.0") == 0 && Updater.CompareVersions("3", "3.0.0") == 0, "missing parts are zero");
        Check(Updater.CompareVersions("v2.9.9", "3.0.0") < 0, "older");
        Check(Updater.IsNewer("v3.0.1", AppInfo.Version) && !Updater.IsNewer("v3.0.0", "3.0.0") && !Updater.IsNewer("v2.5.0", "3.0.0"), "IsNewer");
        Check(Updater.ParseVersion("v3.1.0-beta.2") != null && Updater.ParseVersion("v3.1.0-beta.2")[1] == 1, "suffix ignored");
        Check(Updater.ParseVersion("latest") == null && Updater.ParseVersion("") == null && Updater.ParseVersion("1.2.3.4") == null && Updater.ParseVersion("v1..2") == null && Updater.ParseVersion("-1.0.0") == null, "garbage is not a version");
        Check(!Updater.IsNewer("nightly", "3.0.0") && Updater.CompareVersions("junk", "1.0.0") < 0, "garbage never counts as newer");
    }

    const string SampleRelease = @"{
  ""url"": ""https://api.github.com/repos/qasimrazamalik-coder/dual-earbuds/releases/1"",
  ""html_url"": ""https://github.com/qasimrazamalik-coder/dual-earbuds/releases/tag/v3.2.0"",
  ""id"": 1, ""tag_name"": ""v3.2.0"", ""name"": ""Dual Earbuds 3.2"", ""draft"": false, ""prerelease"": false,
  ""author"": { ""login"": ""qasimrazamalik-coder"", ""id"": 7, ""site_admin"": false },
  ""assets"": [
    { ""name"": ""DualEarbuds.apk"", ""size"": 5242880, ""browser_download_url"": ""https://github.com/qasimrazamalik-coder/dual-earbuds/releases/download/v3.2.0/DualEarbuds.apk"",
      ""uploader"": { ""login"": ""x"" } },
    { ""name"": ""DualEarbuds-Setup.exe"", ""size"": 412345, ""content_type"": ""application/x-msdownload"",
      ""uploader"": { ""login"": ""x"", ""ids"": [1, 2, [3]] },
      ""browser_download_url"": ""https://github.com/qasimrazamalik-coder/dual-earbuds/releases/download/v3.2.0/DualEarbuds-Setup.exe"" }
  ],
  ""body"": ""## What's new\r\n- Party mode \u2014 up to 16 phones\r\n- \""Faster\"" link"",
  ""reactions"": { ""+1"": 3, ""heart"": 1 }
}";

    static void TestReleaseJson()
    {
        var r = Updater.ParseRelease(SampleRelease);
        Check(r.Tag == "v3.2.0" && r.Version == "3.2.0", "tag_name parsed");
        Check(r.SetupUrl == "https://github.com/qasimrazamalik-coder/dual-earbuds/releases/download/v3.2.0/DualEarbuds-Setup.exe" && r.SetupSize == 412345, "Windows installer asset picked (not the apk)");
        Check(r.Notes.StartsWith("## What's new\n- Party mode \u2014 up to 16 phones") && r.Notes.Contains("\"Faster\""), "body parsed with escapes");
        Check(r.PageUrl.EndsWith("/tag/v3.2.0"), "html_url");
        Check(Updater.NotesForDisplay(r.Notes) == "What's new\n\u2022 Party mode \u2014 up to 16 phones\n\u2022 \"Faster\" link", "notes shown as plain text");
        Check(Updater.NotesForDisplay(null) == "" && Updater.NotesForDisplay(new string('x', 9000)).Length <= Updater.MaxNotesChars + 3, "notes: empty and capped");
        var e = Updater.Evaluate(r, "3.0.0");
        Check(e.Status == UpdateStatus.Available && e.Message.Contains("3.2.0"), "newer release is offered");
        Check(Updater.Evaluate(r, "3.2.0").Status == UpdateStatus.UpToDate && Updater.Evaluate(r, "4.0.0").Status == UpdateStatus.UpToDate, "same or older is up to date");

        var noExe = Updater.ParseRelease("{\"tag_name\":\"v9.0.0\",\"assets\":[{\"name\":\"DualEarbuds.apk\",\"browser_download_url\":\"https://github.com/a.apk\"}]}");
        Check(noExe.SetupUrl == null && Updater.Evaluate(noExe, "3.0.0").Status == UpdateStatus.Error, "release without installer is not offered");
        var evil = Updater.ParseRelease("{\"tag_name\":\"v9.0.0\",\"assets\":[{\"name\":\"DualEarbuds-Setup.exe\",\"browser_download_url\":\"http://evil.example/DualEarbuds-Setup.exe\"}]}");
        Check(evil.SetupUrl == null, "installer on another host is ignored");
        var noBody = Updater.ParseRelease("{\"tag_name\":\"3.0.1\",\"body\":null}");
        Check(noBody.Notes == "" && noBody.Version == "3.0.1", "null body becomes empty notes");
        bool threw = false;
        try { Updater.ParseRelease("{\"message\":\"Not Found\",\"documentation_url\":\"https://docs.github.com\"}"); } catch (FormatException) { threw = true; }
        Check(threw, "404 body (no tag) rejected");
        threw = false;
        try { Updater.ParseRelease("<html>rate limited</html>"); } catch (FormatException) { threw = true; }
        Check(threw, "non-JSON rejected");
    }

    static void TestUpdaterChecks()
    {
        Check(Updater.LatestUrl == "https://api.github.com/repos/qasimrazamalik-coder/dual-earbuds/releases/latest", "latest release URL");
        Check(Updater.IsAllowedDownloadUrl("https://github.com/qasimrazamalik-coder/dual-earbuds/releases/download/v3.0.1/DualEarbuds-Setup.exe"), "github.com allowed");
        Check(Updater.IsAllowedDownloadUrl("https://objects.githubusercontent.com/github-production-release-asset/1?x=y"), "objects.githubusercontent.com allowed");
        Check(!Updater.IsAllowedDownloadUrl("http://github.com/x.exe"), "plain http refused");
        Check(!Updater.IsAllowedDownloadUrl("https://github.com.evil.example/x.exe") && !Updater.IsAllowedDownloadUrl("https://evilgithub.com/x.exe"), "look-alike hosts refused");
        Check(!Updater.IsAllowedDownloadUrl("https://user@github.com/x.exe") && !Updater.IsAllowedDownloadUrl("https://github.com:8443/x.exe"), "user info / odd ports refused");
        Check(!Updater.IsAllowedDownloadUrl(null) && !Updater.IsAllowedDownloadUrl("not a url") && !Updater.IsAllowedDownloadUrl("file:///C:/x.exe"), "junk refused");

        var mz = new byte[] { (byte)'M', (byte)'Z' };
        Check(Updater.LooksLikeInstaller(mz, 500 * 1024), "MZ + 500 KB looks like an installer");
        Check(!Updater.LooksLikeInstaller(mz, 50 * 1024), "too small refused");
        Check(!Updater.LooksLikeInstaller(new byte[] { (byte)'<', (byte)'h' }, 500 * 1024) && !Updater.LooksLikeInstaller(null, 500 * 1024), "not MZ refused");

        var now = new DateTime(2026, 10, 21, 12, 0, 0, DateTimeKind.Utc);
        Check(Updater.ShouldAutoCheck(true, DateTime.MinValue, now), "never checked -> check");
        Check(!Updater.ShouldAutoCheck(true, now.AddHours(-23), now), "within 24 h -> wait");
        Check(Updater.ShouldAutoCheck(true, now.AddHours(-24), now), "after 24 h -> check");
        Check(!Updater.ShouldAutoCheck(false, DateTime.MinValue, now), "switched off -> never");
        Check(Updater.ShouldAutoCheck(true, now.AddDays(3), now), "clock moved back -> check");
    }

    static void TestNestedJson()
    {
        var o = MiniJson.Parse("{\"a\":[1,{\"b\":[true,null,\"x\"]}],\"c\":{}}") as Dictionary<string, object>;
        Check(o != null && ((List<object>)o["a"]).Count == 2 && o["c"] is Dictionary<string, object>, "nested objects and arrays");
        var inner = (Dictionary<string, object>)((List<object>)o["a"])[1];
        Check(((List<object>)inner["b"])[2] as string == "x", "deep value");
        bool threw = false;
        try { MiniJson.Parse(new string('[', 100) + new string(']', 100)); } catch (FormatException) { threw = true; }
        Check(threw, "deep nesting rejected");
        threw = false;
        try { MiniJson.Parse("{\"a\":1} x"); } catch (FormatException) { threw = true; }
        Check(threw, "trailing garbage rejected");
        Check(MiniJson.ParseObject("{\"type\":\"pong\",\"t\":5,\"x\":[1,2]}").Count == 3, "flat parser unchanged");
    }

    static void TestFirewallScript()
    {
        string add = Firewall.BuildScript(@"C:\Users\O'Neil\AppData\Local\Programs\Dual Earbuds\DualEarbuds.exe", true, Firewall.ProfilePrivate);
        Check(add.Contains("HNetCfg.FwPolicy2") && add.Contains("$r.Profiles = 2;") && add.Contains("'Dual Earbuds (phone link)'"), "private-only rule script");
        Check(add.Contains("'C:\\Users\\O''Neil\\AppData"), "path quoted for PowerShell");
        Check(Firewall.BuildScript("x", true, Firewall.ProfilesFor(Settings.NetScopeAll)).Contains("$r.Profiles = 6;"), "private + public = 6");
        Check(Firewall.ProfilesFor(Settings.NetScopePrivate) == 2, "private scope = 2");
        Check(!Firewall.BuildScript("x", false, 6).Contains("FWRule"), "remove-only script adds nothing");
        Check(Encoding.Unicode.GetString(Convert.FromBase64String(PsScript.Encode(add))) == add, "encoded command round trip");
    }

    // ------------------------------------------------------------------ smart features / DSP
    static float[] Sine(int rate, double hz, int frames, double amp)
    {
        var b = new float[frames * 2];
        for (int i = 0; i < frames; i++) { float v = (float)(amp * Math.Sin(2 * Math.PI * hz * i / rate)); b[2 * i] = v; b[2 * i + 1] = v; }
        return b;
    }

    static double Rms(float[] b, int from, int to)
    {
        double s = 0;
        for (int i = from; i < to; i++) s += b[i] * b[i];
        return Math.Sqrt(s / Math.Max(1, to - from));
    }

    static void TestEq()
    {
        var bq = new Biquad();
        bq.Set(Biquad.Kind.Peak, 48000, 1000, 1.0, 6);
        Check(Math.Abs(bq.ResponseDb(48000, 1000) - 6) < 0.05, "peak +6 dB at its centre");
        Check(Math.Abs(bq.ResponseDb(48000, 50)) < 0.3, "peak leaves 50 Hz alone");
        bq.Set(Biquad.Kind.LowShelf, 48000, 100, 0.7, 6);
        Check(Math.Abs(bq.ResponseDb(48000, 20) - 6) < 0.6 && Math.Abs(bq.ResponseDb(48000, 5000)) < 0.2, "low shelf");

        var eq = new EqChain(48000);
        eq.SetPreset("flat", false);
        Check(eq.IsFlat, "flat preset is a bypass");
        var x = Sine(48000, 1000, 4800, 0.5);
        var y = (float[])x.Clone();
        eq.Process(y, 0, 4800);
        bool same = true;
        for (int i = 0; i < y.Length; i++) if (y[i] != x[i]) same = false;
        Check(same, "flat preset leaves samples bit-exact");

        eq.SetPreset("bass", false);
        Check(eq.ResponseDb(60, true) > eq.ResponseDb(3000, true) + 4, "bass preset lifts lows over mids");
        eq.SetPreset("voice", false);
        Check(eq.ResponseDb(3000, true) > eq.ResponseDb(80, true) + 4, "voice preset lifts presence");
        Check(eq.ResponseDb(3000, true) <= 1.5, "pre-gain keeps the peak near 0 dB");
        Check(!EqPresets.IsValid("loud") && EqPresets.IsValid("movie"), "preset names");

        // a loud full-scale signal through the strongest settings never clips
        eq.SetPreset("bass", false);
        var hp = new HearingProfile();
        for (int i = 0; i < 7; i++) { hp.LeftDb[i] = 9; hp.RightDb[i] = 9; }
        eq.SetHearing(hp);
        var loud = Sine(48000, 80, 9600, 1.0);
        eq.Process(loud, 0, 9600);
        float mx = 0;
        foreach (var v in loud) mx = Math.Max(mx, Math.Abs(v));
        Check(mx <= 1.0f, "never above full scale (" + mx.ToString("0.000") + ")");

        // glide: the switch is spread over ~300 ms of blocks
        var g = new EqChain(48000);
        g.SetPreset("flat", false);
        g.SetPreset("bass", true);
        var blk = new float[960];
        g.Process(blk, 0, 480);
        double after1 = g.BandDb(0);
        for (int i = 0; i < 40; i++) g.Process(blk, 0, 480);
        Check(after1 > 0 && after1 < 1.0 && Math.Abs(g.BandDb(0) - 6) < 0.01, "preset change glides over 300 ms");

        var hp2 = HearingProfile.Parse("Ammi|0,0,3,4.5,9,9,9|0,0,0,1.5,6,9,12");
        Check(hp2 != null && hp2.Name == "Ammi" && hp2.RightDb[6] == 9 && hp2.LeftDb[3] == 4.5, "profile parse (clamped to +9 dB)");
        Check(HearingProfile.Parse(hp2.Serialize()).Serialize() == hp2.Serialize(), "profile round trip");
        Check(HearingProfile.Parse("x|1,2|3") == null && HearingProfile.Parse(null) == null, "bad profile rejected");
    }

    static void TestDialogue()
    {
        int rate = 48000;
        // "speech": a 1 kHz tone gated on/off at 4 Hz in the centre; "music": a steady 80 Hz tone
        int n = rate * 2;
        var speech = new float[n * 2];
        for (int i = 0; i < n; i++)
        {
            double env = ((i / (rate / 8)) % 2 == 0) ? 1 : 0.05;
            float v = (float)(0.2 * env * Math.Sin(2 * Math.PI * 1000 * i / rate));
            speech[2 * i] = v; speech[2 * i + 1] = v;
        }
        var off = (float[])speech.Clone();
        var db = new DialogueBoost(rate, 480);
        db.Mode = DialogueMode.Strong;
        for (int o = 0; o < n; o += 480) db.Process(speech, o, Math.Min(480, n - o));
        double gainDb = 20 * Math.Log10(Rms(speech, n, 2 * n) / Rms(off, n, 2 * n));
        Check(gainDb > 2.5, "voice band boosted while speech (" + gainDb.ToString("0.0") + " dB)");

        var bass = Sine(rate, 80, n, 0.3);
        var bassRef = (float[])bass.Clone();
        var db2 = new DialogueBoost(rate, 480);
        db2.Mode = DialogueMode.Light;
        for (int o = 0; o < n; o += 480) db2.Process(bass, o, Math.Min(480, n - o));
        double bassDb = 20 * Math.Log10(Rms(bass, n, 2 * n) / Rms(bassRef, n, 2 * n));
        Check(Math.Abs(bassDb) < 1.0, "no boost without speech (" + bassDb.ToString("0.00") + " dB)");

        var hot = Sine(rate, 1000, n, 0.99);
        var db3 = new DialogueBoost(rate, 480);
        db3.Mode = DialogueMode.Night;
        for (int o = 0; o < n; o += 480) db3.Process(hot, o, Math.Min(480, n - o));
        float mx = 0;
        foreach (var v in hot) mx = Math.Max(mx, Math.Abs(v));
        Check(mx <= 1.0f, "night mode never clips");
        var offMode = new DialogueBoost(rate, 480);
        var z = Sine(rate, 1000, 480, 0.5);
        var zc = (float[])z.Clone();
        offMode.Process(z, 0, 480);
        Check(z[100] == zc[100] && z[901] == zc[901], "off = untouched");
    }

    static void TestP2()
    {
        var q = new P2Quantile(0.99);
        var rnd = new Random(3);
        var all = new List<double>();
        for (int i = 0; i < 20000; i++) { double v = rnd.NextDouble() * 100; q.Add(v); all.Add(v); }
        all.Sort();
        double exact = all[(int)(0.99 * (all.Count - 1))];
        Check(Math.Abs(q.Value - exact) < 1.5, "p99 of uniform 0..100 ~ " + exact.ToString("0.0") + " (got " + q.Value.ToString("0.0") + ")");
        var m = new P2Quantile(0.5);
        for (int i = 0; i < 5001; i++) m.Add(rnd.NextDouble() < 0.5 ? rnd.NextDouble() : 10 + rnd.NextDouble());
        Check(m.Value > 0.5 && m.Value < 10.5, "median of a two-cluster mix lands between");
        var few = new P2Quantile(0.99);
        few.Add(3); few.Add(9); few.Add(1);
        Check(few.Value == 9, "few samples: exact max");
    }

    static void TestJitterBuffer()
    {
        var jb = new JitterBuffer();
        jb.Mode = BufferMode.Steady;
        jb.Reset(0);
        Check(jb.TargetMs == 60, "starts at 60 ms");
        jb.OnUnderrun(1000);
        Check(jb.TargetMs == 80, "underrun +20 ms");
        jb.Tick(5000);
        Check(jb.TargetMs == 80, "holds after an underrun");
        jb.Tick(21000);
        Check(jb.TargetMs == 75, "-5 ms after 20 s calm");
        jb.Tick(23000);
        Check(jb.TargetMs == 75, "only every 5 s");
        for (long t = 26000; t < 200000; t += 5000) jb.Tick(t);
        Check(jb.TargetMs == 30, "never below 30 ms");
        for (int i = 0; i < 30; i++) jb.OnUnderrun(300000 + i);
        Check(jb.TargetMs == 300, "never above 300 ms");

        // Smart: steady 10 ms arrivals with +-3 ms jitter -> settles well below the start
        var s = new JitterBuffer();
        s.Reset(0);
        var rnd = new Random(5);
        long now = 0;
        for (int i = 0; i < 6000; i++) { now += 10; s.OnPacket(now + rnd.Next(-3, 4)); if (i % 50 == 0) s.Tick(now); }
        Check(s.TargetMs >= 30 && s.TargetMs <= 40, "smart target near p99 jitter + 10 (" + s.TargetMs + " ms)");
        // a burst of 60 ms jitter raises it right away
        for (int i = 0; i < 300; i++) { now += 10; s.OnPacket(now + (i % 3 == 0 ? 60 : 0)); }
        s.Tick(now);
        Check(s.TargetMs >= 60, "jittery network -> larger buffer at once (" + s.TargetMs + " ms)");
        int before = s.TargetMs;
        s.OnUnderrun(now);
        Check(s.TargetMs == Math.Min(300, before + 20), "smart: underrun +20 ms");
        var man = new JitterBuffer { Mode = BufferMode.Manual, ManualMs = 500 };
        Check(man.TargetMs == 300, "manual clamped to 300 ms");
    }

    static void TestStaircase()
    {
        // a listener whose true threshold is -62 dBFS
        var st = new HearingStaircase();
        int guard = 0;
        while (!st.Done && guard++ < 50) st.Answer(st.LevelDb >= -62);
        Check(st.Done, "finishes");
        Check(Math.Abs(st.ThresholdDb - (-62)) <= 6, "threshold near the truth (" + st.ThresholdDb.ToString("0.0") + ")");
        var p = HearingStaircase.ToProfile("Me", new double[] { -70, -70, -70, -66, -50, -40, -30 }, new double[] { -70, -70, -70, -70, -70, -70, -70 });
        Check(p.LeftDb[0] == 0 && p.LeftDb[3] == 2 && p.LeftDb[4] == 9 && p.LeftDb[6] == 9, "half-gain rule, capped at +9 dB");
        Check(p.RightDb[4] == 0, "a good ear gets no boost");
        var never = new HearingStaircase();
        guard = 0;
        while (!never.Done && guard++ < 50) never.Answer(false);
        Check(never.Done && never.ThresholdDb <= HearingStaircase.CeilingDb, "can't-hear-anything ends at the ceiling");
    }

    static void TestRateLimiter()
    {
        var r = new RateLimiter(5);
        int ok = 0;
        for (int i = 0; i < 20; i++) if (r.Allow(1000)) ok++;
        Check(ok == 5, "burst of 5");
        Check(!r.Allow(1100) && r.Allow(1250), "refills at 5 per second");
        int sustained = 0;
        for (long t = 2000; t < 12000; t += 20) if (r.Allow(t)) sustained++;
        Check(sustained >= 48 && sustained <= 56, "about 5/s sustained (" + sustained + " in 10 s)");
    }

    static void TestFft()
    {
        var f = new Fft(256);
        var re = new double[256]; var im = new double[256];
        for (int i = 0; i < 256; i++) re[i] = Math.Cos(2 * Math.PI * 8 * i / 256);
        f.Transform(re, im, false);
        Check(Math.Abs(re[8] - 128) < 1e-6 && Math.Abs(re[3]) < 1e-6, "cosine lands in its bin");
        f.Transform(re, im, true);
        Check(Math.Abs(re[5] - Math.Cos(2 * Math.PI * 8 * 5 / 256)) < 1e-9, "inverse round trip");
        bool threw = false;
        try { new Fft(100); } catch (ArgumentException) { threw = true; }
        Check(threw, "non power of two rejected");
    }

    static void TestNoise()
    {
        int rate = 48000, n = rate * 3;
        var rnd = new Random(11);
        var noise = new float[n];
        var tone = new float[n];
        for (int i = 0; i < n; i++)
        {
            noise[i] = (float)((rnd.NextDouble() * 2 - 1) * 0.05);
            double env = i > rate * 2 ? 1 : 0;   // the "voice" starts after 2 s of room noise
            tone[i] = (float)(0.3 * env * Math.Sin(2 * Math.PI * 700 * i / rate));
        }
        var x = new float[n];
        for (int i = 0; i < n; i++) x[i] = noise[i] + tone[i];
        var ns = new NoiseSuppressor { Mode = NoiseMode.Strong };
        for (int o = 0; o < n; o += 480) ns.Process(x, o, Math.Min(480, n - o));
        int lat = ns.LatencySamples;
        double before = Rms2(noise, rate, 2 * rate - lat - rate / 2), after = Rms2(x, rate + lat, 2 * rate - rate / 2);
        double cut = 20 * Math.Log10(before / Math.Max(1e-9, after));
        Check(cut > 8, "room noise reduced (" + cut.ToString("0.0") + " dB)");
        double toneIn = Rms2(tone, (int)(2.5 * rate) - lat, 3 * rate - lat), toneOut = Rms2(x, (int)(2.5 * rate), 3 * rate);
        double keep = 20 * Math.Log10(toneOut / toneIn);
        Check(keep > -3 && keep < 2, "voice kept (" + keep.ToString("0.0") + " dB)");
        var off = new NoiseSuppressor { Mode = NoiseMode.Off };
        var y = new float[] { 0.1f, 0.2f };
        off.Process(y, 0, 2);
        Check(y[1] == 0.2f, "off = untouched");
    }

    static double Rms2(float[] b, int from, int to)
    {
        double s = 0;
        for (int i = from; i < to; i++) s += b[i] * b[i];
        return Math.Sqrt(s / Math.Max(1, to - from));
    }

    static SoundClass RunClassifier(Func<int, float> gen, int seconds, out SoundClassifier c)
    {
        int rate = 48000;
        c = new SoundClassifier(rate);
        var blk = new float[960];
        int t = 0;
        for (int s = 0; s < seconds * 100; s++)
        {
            for (int i = 0; i < 480; i++) { float v = gen(t++); blk[2 * i] = v; blk[2 * i + 1] = v; }
            c.FeedStereo(blk, 480);
        }
        return c.Current;
    }

    static void TestClassifier()
    {
        int rate = 48000;
        var rnd = new Random(2);
        SoundClassifier c;
        // music: a steady chord of harmonics
        var music = RunClassifier(t => (float)(0.15 * (Math.Sin(2 * Math.PI * 220 * t / rate) + 0.5 * Math.Sin(2 * Math.PI * 440 * t / rate) + 0.3 * Math.Sin(2 * Math.PI * 660 * t / rate) + 0.3 * Math.Sin(2 * Math.PI * 110 * t / rate))), 7, out c);
        Check(music == SoundClass.Music, "steady chord -> music (" + c.LastWindow + ")");
        // speech-like: a voiced buzz (150 Hz harmonics) switched on and off at ~4 Hz syllable rate
        var speech = RunClassifier(t =>
        {
            double syl = Math.Sin(2 * Math.PI * 4 * t / rate);
            double env = syl > 0 ? syl : 0;
            double buzz = 0;
            for (int hh = 1; hh <= 12; hh++) buzz += Math.Sin(2 * Math.PI * 150 * hh * t / rate) / hh;
            return (float)(0.12 * env * buzz);
        }, 7, out c);
        Check(speech == SoundClass.Speech, "syllable-rate voiced buzz -> speech (" + c.LastWindow + ", mod " + c.LastFeatures.Modulation4Hz.ToString("0.00") + ")");
        // movie mix: broadband noise + rumble
        var movie = RunClassifier(t => (float)((rnd.NextDouble() * 2 - 1) * 0.15 + 0.2 * Math.Sin(2 * Math.PI * 50 * t / rate)), 7, out c);
        Check(movie == SoundClass.Movie, "noise + rumble -> movie mix (" + c.LastWindow + ")");
        var silence = RunClassifier(t => 0f, 7, out c);
        Check(silence == SoundClass.Silence, "silence");
        // hysteresis: 3 s of something else doesn't flip the class
        var h = new SoundClassifier(rate);
        var blk = new float[960];
        int tt = 0;
        for (int s = 0; s < 700; s++) { for (int i = 0; i < 480; i++) { float v = (float)(0.2 * Math.Sin(2 * Math.PI * 220 * tt / rate)); tt++; blk[2 * i] = v; blk[2 * i + 1] = v; } h.FeedStereo(blk, 480); }
        var was = h.Current;
        for (int s = 0; s < 300; s++) { Array.Clear(blk, 0, blk.Length); h.FeedStereo(blk, 480); }
        Check(was == SoundClass.Music && h.Current == SoundClass.Music, "3 s of silence doesn't switch (5 s hysteresis)");
        Check(SoundClassifier.PresetForApp("Spotify.exe") == "bass" && SoundClassifier.PresetForApp("Netflix") == "movie" && SoundClassifier.PresetForApp("Zoom.exe") == "voice" && SoundClassifier.PresetForApp("notepad") == null, "by-app mapping");
        Check(SoundClassifier.PresetFor(SoundClass.Speech) == "voice" && SoundClassifier.PresetFor(SoundClass.Silence) == null, "class -> preset");
    }

    static void TestClockSync()
    {
        var cs = new ClockSync();
        Check(!cs.Synced, "not synced at first");
        // true offset: host = listener + 5000; network one-way 7 ms, one sample with an asymmetric 60 ms delay
        cs.Add(1000, 1000 + 5000 + 7, 1014);
        cs.Add(2000, 2000 + 5000 + 60, 2067);
        Check(Math.Abs(cs.Offset - 5000) < 0.01, "offset from the low-RTT sample (" + cs.Offset + ")");
        Check(cs.BestRtt == 14, "best RTT");
        for (int i = 0; i < 8; i++) cs.Add(3000 + i * 100, 3000 + i * 100 + 5000 + 40, 3000 + i * 100 + 60);
        Check(Math.Abs(cs.Offset - 5010) < 0.01, "only the last 8 count");
        Check(!cs.Add(5000, 1, 4000), "negative RTT ignored");
        Check(Math.Abs(cs.HostNow(10000) - 15010) < 0.01, "host time now");
        var pc = new PacketClock();
        pc.OnClock(4294967290u, 100000);
        Check(pc.HostTime(4294967295u) == 100050 && pc.HostTime(4u) == 100100, "seq -> host time across the wrap");
        Check(pc.HostTime(4294967280u) == 99900, "earlier packets");
    }

    static void TestSyncTrim()
    {
        var t = new SyncTrim();
        double r;
        Check(t.Decide(3, 48000, out r) == 0 && r == 1.0, "inside +-5 ms: nothing");
        Check(t.Decide(20, 48000, out r) == 0 && r > 1.0 && r <= 1.005, "20 ms late: speed up a little");
        Check(t.Decide(-30, 48000, out r) == 0 && r < 1.0 && r >= 0.995, "30 ms early: slow down");
        Check(t.Decide(4, 48000, out r) == 0 && r != 1.0, "keeps trimming until within 2 ms");
        Check(t.Decide(1, 48000, out r) == 0 && r == 1.0, "stops trimming");
        Check(t.Decide(100, 48000, out r) == 4800, "100 ms late: skip 100 ms");
        Check(t.Decide(-50, 48000, out r) == -2400, "50 ms early: insert 50 ms");
        Check(Math.Abs(t.Decide(39, 48000, out r)) == 0 && r == 1.005, "rate trim capped at 0.5 %");
    }

    static void TestResampler()
    {
        var rs = new StereoResampler();
        var input = new float[960];
        for (int i = 0; i < 480; i++) { input[2 * i] = i; input[2 * i + 1] = -i; }
        var output = new float[2000];
        int total = 0;
        int n1 = rs.Process(input, 480, output, 1000, 1.0);
        Check(n1 == 479 && output[0] == 0 && output[2 * 478] == 478, "ratio 1 passes samples through");
        long sum = 0;
        for (int b = 0; b < 200; b++)
        {
            for (int i = 0; i < 480; i++) { input[2 * i] = (float)Math.Sin(i * 0.01); input[2 * i + 1] = input[2 * i]; }
            sum += rs.Process(input, 480, output, 1000, 1.005);
        }
        total = (int)sum;
        Check(Math.Abs(total - 96000 / 1.005) < 3, "1.005 -> 0.5 % fewer frames (" + total + ")");
        var rs2 = new StereoResampler();
        long s2 = 0;
        for (int b = 0; b < 100; b++) s2 += rs2.Process(input, 480, output, 1000, 0.995);
        Check(Math.Abs(s2 - 48000 / 0.995) < 3, "0.995 -> more frames");
    }

    static void TestConcealer()
    {
        int rate = 48000;
        var c = new Concealer(rate);
        int pf = c.PacketFrames;
        Check(pf == 480, "10 ms packets");
        int t = 0;
        var stream = new List<float>();
        var pkt = new float[pf * 2];
        Func<int, float> sig = i => (float)(0.5 * Math.Sin(2 * Math.PI * 440 * i / rate));
        for (int p = 0; p < 5; p++) { for (int i = 0; i < pf; i++) { pkt[2 * i] = pkt[2 * i + 1] = sig(t++); } c.OnPacket(pkt, pf); foreach (var v in pkt) stream.Add(v); }
        // lose 4 packets (40 ms)
        for (int p = 0; p < 4; p++) { c.Conceal(pkt, pf); foreach (var v in pkt) stream.Add(v); t += pf; }
        int concealStart = 5 * pf;
        float atFade = 0;
        for (int i = concealStart + rate * 30 / 1000; i < concealStart + 4 * pf; i++) atFade = Math.Max(atFade, Math.Abs(stream[2 * i]));
        Check(atFade < 1e-6, "silent after at most 30 ms");
        Check(Math.Abs(stream[2 * concealStart] - sig(concealStart)) < 0.05, "continues the last 10 ms smoothly");
        for (int p = 0; p < 3; p++) { for (int i = 0; i < pf; i++) { pkt[2 * i] = pkt[2 * i + 1] = sig(t++); } c.OnPacket(pkt, pf); foreach (var v in pkt) stream.Add(v); }
        double maxJump = 0;
        for (int i = 1; i < stream.Count / 2; i++) maxJump = Math.Max(maxJump, Math.Abs(stream[2 * i] - stream[2 * i - 2]));
        double natural = 0.5 * 2 * Math.PI * 440 / rate;   // biggest step of the clean sine
        Check(maxJump < natural * 3, "no clicks (max step " + maxJump.ToString("0.000") + ")");
        Check(c.ConcealedPackets == 4, "counts concealed packets");
        Check(SeqGap.Missing(true, 10, 11) == 0 && SeqGap.Missing(true, 10, 14) == 3 && SeqGap.Missing(true, 10, 9) == -1 && SeqGap.Missing(true, 4294967295u, 1) == 1, "sequence gaps (with wrap)");
        Check(SeqGap.Missing(false, 0, 77) == 0 && SeqGap.Missing(true, 5, 50000) == 0, "first packet / host restart");
    }

    static void TestCalibration()
    {
        int rate = 48000;
        int[] starts;
        var train = Calibration.BuildTrain(rate, 0.6f, out starts);
        Check(starts.Length == 8, "8 clicks");
        foreach (double delayMs in new[] { 137.0, 23.5, 410.0 })
        {
            int d = (int)Math.Round(delayMs * rate / 1000);
            var rec = new float[train.Length + d + rate];
            var rnd = new Random(9);
            for (int i = 0; i < rec.Length; i++) rec[i] = (float)((rnd.NextDouble() * 2 - 1) * 0.02);
            // quieter, slightly filtered copy (like a mic hearing an earbud), plus room noise
            float prev = 0;
            for (int i = 0; i < train.Length; i++) { float v = 0.25f * train[i] + 0.1f * prev; prev = train[i]; rec[i + d] += v; }
            var r = Calibration.Measure(rec, rec.Length, rate, starts);
            Check(r.Ok && Math.Abs(r.LatencyMs - delayMs) < 1.0, "finds " + delayMs + " ms (got " + r.LatencyMs.ToString("0.0") + ", " + r.Agreeing + "/8)");
        }
        var quiet = new float[train.Length + rate];
        var rq = new Random(4);
        for (int i = 0; i < quiet.Length; i++) quiet[i] = (float)((rq.NextDouble() * 2 - 1) * 0.05);
        var none = Calibration.Measure(quiet, quiet.Length, rate, starts);
        Check(!none.Ok && none.Problem != null, "no clicks -> a clear problem, no number");
    }

    static string NextOfType(WsClient w, string type, int maxFrames)
    {
        for (int i = 0; i < maxFrames; i++)
        {
            string t = w.NextText(200);
            if (t == null) return null;
            if (t.Contains("\"type\":\"" + type + "\"")) return t;
        }
        return null;
    }

    static void TestHostV31()
    {
        Check(RoomPin.IsValid("0420") && RoomPin.IsValid("123456") && !RoomPin.IsValid("123") && !RoomPin.IsValid("12a4") && !RoomPin.IsValid(null), "PIN format 4-6 digits");
        Check(RoomPin.IsValid(RoomPin.New()), "new PIN is valid");
        Check(MediaKeys.IsAction("playpause") && MediaKeys.IsAction("voldown") && !MediaKeys.IsAction("shutdown"), "remote actions");
        Check(PhoneLink.NormalizeClient("windows") == "windows" && PhoneLink.NormalizeClient("toaster") == "web", "client kinds");

        int port = FreePort();
        var link = new PhoneLink(port, "ABCD2345", IPAddress.Loopback);
        link.Start();
        try
        {
            link.Pin = "4711";
            var info = MiniJson.ParseObject(Http(port, "GET /info?k=ABCD2345 HTTP/1.1").Split(new[] { "\r\n\r\n" }, StringSplitOptions.None)[1]);
            Check(MiniJson.GetBool(info, "pin", false) && !MiniJson.GetBool(info, "remote", true) && MiniJson.GetString(info, "source", "") == "all", "/info: pin, remote, source");
            link.SetSource("app", "VLC");
            var info2 = MiniJson.ParseObject(Http(port, "GET /info?k=ABCD2345 HTTP/1.1").Split(new[] { "\r\n\r\n" }, StringSplitOptions.None)[1]);
            Check(MiniJson.GetString(info2, "app", "") == "DualEarbuds" && MiniJson.GetString(info2, "sourceApp", "") == "VLC", "/info: app stays DualEarbuds, shared app is sourceApp");
            link.SetSource("all", null);
            Check(!link.Url("1.2.3.4").Contains("4711"), "the link never contains the PIN");
            using (var noPin = WsClient.Open(port, "k=ABCD2345&name=A"))
                Check(noPin.Handshake.StartsWith("HTTP/1.1 403"), "missing PIN -> 403");
            using (var wrong = WsClient.Open(port, "k=ABCD2345&p=0000"))
                Check(wrong.Handshake.StartsWith("HTTP/1.1 403"), "wrong PIN -> 403");
            Check(Http(port, "GET /?k=ABCD2345 HTTP/1.1").StartsWith("HTTP/1.1 200"), "the page itself still opens (it asks for the PIN)");

            link.RemoteControl = true;
            link.SetSource("app", "Spotify");
            link.SuggestedEq = "movie";
            var pressed = new List<string>();
            link.RemoteAction = (id, a) => { lock (pressed) pressed.Add(a); };
            using (var w = WsClient.Open(port, "k=abcd2345&p=4711&client=windows&name=Laptop"))
            {
                Check(w.Handshake.StartsWith("HTTP/1.1 101"), "right PIN (key in lower case) -> joined");
                var hello = MiniJson.ParseObject(w.NextText(5));
                Check(MiniJson.GetInt(hello, "playoutMs", 0) == 120 && MiniJson.GetBool(hello, "remote", false) && MiniJson.GetString(hello, "app", "") == "Spotify", "hello: playoutMs, remote, source app");
                Check(w.NextText(5) == "{\"type\":\"control\",\"eq\":\"movie\"}", "suggested EQ sent on join");
                Check(WaitFor(() => link.Snapshot().Count == 1 && link.Snapshot()[0].Client == "windows", 1000), "listed as a Windows listener");

                w.SendText("{\"type\":\"sync\",\"c\":1234.5}");
                var sync = MiniJson.ParseObject(NextOfType(w, "sync", 20));
                Check(MiniJson.GetString(sync, "c", null) == null && Math.Abs((double)sync["c"] - 1234.5) < 1e-9 && (double)sync["h"] >= 0, "sync reply echoes c and adds h");
                Check(WaitFor(() => link.Snapshot()[0].Synced, 1000), "listener marked as synced");

                link.SetMirroring(true, 48000);
                var st = NextOfType(w, "state", 20);
                Check(st != null && st.Contains("\"source\":\"app\"") && st.Contains("\"app\":\"Spotify\""), "state carries the source");
                var buf = new float[480 * 2];
                for (int i = 0; i < 30; i++) link.Broadcast(buf, 480, 48000);
                var clock = NextOfType(w, "clock", 200);
                Check(clock != null, "clock message every second");
                if (clock != null)
                {
                    var cm = MiniJson.ParseObject(clock);
                    Check((double)cm["seq"] >= 0 && (double)cm["seq"] < 30 && (double)cm["h"] >= 0, "clock: seq of a sent packet + capture time");
                }

                for (int i = 0; i < 12; i++) w.SendText("{\"type\":\"remote\",\"action\":\"next\"}");
                w.SendText("{\"type\":\"remote\",\"action\":\"format-c\"}");
                Check(WaitFor(() => { lock (pressed) return pressed.Count >= 5; }, 1500), "remote commands arrive");
                Thread.Sleep(200);
                lock (pressed) Check(pressed.Count == 5 && pressed.TrueForAll(a => a == "next"), "rate-limited to 5 per second, unknown actions dropped (" + pressed.Count + ")");
                link.RemoteControl = false;
                w.SendText("{\"type\":\"remote\",\"action\":\"prev\"}");
                Thread.Sleep(300);
                lock (pressed) Check(!pressed.Contains("prev"), "ignored when the setting is off");

                link.Talking = true;
                Check(NextOfType(w, "talk", 200) == "{\"type\":\"talk\",\"on\":true}", "talk message");
                link.SuggestedEq = "bass";
                Check(NextOfType(w, "control", 200) == "{\"type\":\"control\",\"eq\":\"bass\"}", "EQ suggestion to everyone");
            }

            // wrong PINs count toward the lockout
            link.Pin = "123456";
            for (int i = 0; i < 10; i++) { try { using (var b = WsClient.Open(port, "k=ABCD2345&p=" + (100000 + i))) { } } catch (IOException) { } catch (SocketException) { } }
            bool blocked;
            try { using (var b = WsClient.Open(port, "k=ABCD2345&p=123456")) blocked = !b.Handshake.StartsWith("HTTP/1.1 101"); }
            catch { blocked = true; }
            Check(blocked, "10 wrong PINs -> locked out, even with the right one");
        }
        finally { link.Stop(); }
    }

    static void TestDiscovery()
    {
        var txt = DnsSd.HostTxt("Qasim's Laptop", "windows", false, "ABCD2345");
        Check(txt.Contains("v=3") && txt.Contains("pin=0") && txt.Contains("k=ABCD2345") && txt.Contains("host=windows"), "TXT per protocol");
        Check(!DnsSd.HostTxt("x", "windows", true, null).Exists(t => t.StartsWith("k=")), "no k when not allowed");
        Check(DnsSd.SafeLabel("a.b.c") == "abc" && Encoding.UTF8.GetByteCount(DnsSd.SafeLabel(new string('é', 50))) <= 63, "labels: no dots, max 63 bytes");
        Check(DnsSd.HostLabel("LAPTOP_Q 7") == "LAPTOP-Q-7", "host label");

        byte[] resp = DnsSd.BuildResponse("Qasim's Laptop", "LAPTOP-Q", 8790, txt, IPAddress.Parse("192.168.1.16"), 120);
        var m = DnsSd.Parse(resp, resp.Length);
        Check(m.IsResponse && m.Records.Count == 4, "response: PTR, SRV, TXT, A");
        var hosts = DiscoveryParse.Hosts(m, IPAddress.Parse("10.0.0.9"));
        Check(hosts.Count == 1, "one host found");
        if (hosts.Count == 1)
        {
            var h = hosts[0];
            Check(h.Name == "Qasim's Laptop" && h.Address == "192.168.1.16" && h.Port == 8790 && h.Key == "ABCD2345" && !h.Pin, "host details from SRV/TXT/A");
            Check(h.Link == "http://192.168.1.16:8790/?k=ABCD2345", "link built from the advert");
        }
        byte[] nk = DnsSd.BuildResponse("Pc", "PC", 9000, DnsSd.HostTxt("Pc", "android", true, null), null, 120);
        var noKey = DiscoveryParse.Hosts(DnsSd.Parse(nk, nk.Length), IPAddress.Parse("10.0.0.9"));
        Check(noKey.Count == 1 && noKey[0].Key == null && noKey[0].Link == null && noKey[0].Pin && noKey[0].HostKind == "android" && noKey[0].Address == "10.0.0.9", "without k: asks for the link; address from the sender");
        byte[] bb = DnsSd.BuildResponse("Pc", "PC", 9000, txt, null, 0);
        var bye = DnsSd.Parse(bb, bb.Length);
        Check(DiscoveryParse.Hosts(bye, IPAddress.Loopback).Count == 0 && DiscoveryParse.Goodbyes(bye).Contains("Pc"), "goodbye (TTL 0) removes the host");

        byte[] q = DnsSd.BuildQuery(DnsSd.ServiceType, true);
        var qm = DnsSd.Parse(q, q.Length);
        Check(!qm.IsResponse && qm.Questions.Count == 1 && qm.Questions[0] == "_dualearbuds._tcp.local" && DnsSd.AsksFor(qm, DnsSd.ServiceType), "query round trip");

        // name compression (as real responders send it): PTR rdata points back at the question name
        var c = new List<byte> { 0, 0, 0x84, 0, 0, 1, 0, 1, 0, 0, 0, 0 };
        foreach (var part in "_dualearbuds._tcp.local".Split('.')) { c.Add((byte)part.Length); c.AddRange(Encoding.ASCII.GetBytes(part)); }
        c.Add(0); c.Add(0); c.Add(12); c.Add(0); c.Add(1);
        c.Add(0xC0); c.Add(12);                                 // answer name -> offset 12
        c.AddRange(new byte[] { 0, 12, 0, 1, 0, 0, 0, 120, 0, 5, 2, (byte)'P', (byte)'C', 0xC0, 12 });
        var cm = DnsSd.Parse(c.ToArray(), c.Count);
        Check(cm.Records.Count == 1 && cm.Records[0].Target == "PC._dualearbuds._tcp.local", "compressed names");
        var loop = new byte[] { 0, 0, 0x84, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0xC0, 12, 0, 12, 0, 1, 0, 0, 0, 1, 0, 0 };
        bool threw = false;
        try { DnsSd.Parse(loop, loop.Length); } catch (FormatException) { threw = true; }
        Check(threw, "pointer loop rejected");
        threw = false;
        try { DnsSd.Parse(resp, 30); } catch (FormatException) { threw = true; }
        Check(threw, "truncated packet rejected");
        var rnd = new Random(1);
        int crashes = 0;
        for (int i = 0; i < 2000; i++)
        {
            var junk = (byte[])resp.Clone();
            for (int j = 0; j < 4; j++) junk[rnd.Next(junk.Length)] = (byte)rnd.Next(256);
            try { var jm = DnsSd.Parse(junk, junk.Length); DiscoveryParse.Hosts(jm, IPAddress.Loopback); }
            catch (FormatException) { }
            catch (Exception) { crashes++; }
        }
        Check(crashes == 0, "fuzzed packets never crash the parser");
    }

    static void TestLinkParse()
    {
        LinkTarget t; string why;
        Check(LinkAddress.TryParse("http://192.168.1.16:8790/?k=Q7RK2MXD", out t, out why) && t.Host == "192.168.1.16" && t.Port == 8790 && t.Key == "Q7RK2MXD", "full link");
        Check(LinkAddress.TryParse("  192.168.1.16:9000/?k=q7rk2mxd ", out t, out why) && t.Port == 9000 && t.Key == "Q7RK2MXD", "no scheme, lower-case key");
        Check(LinkAddress.TryParse("10.0.0.5/?k=Q7RK2MXD", out t, out why) && t.Port == 8790, "missing port = 8790");
        Check(LinkAddress.TryParse("HTTP://laptop.local:8790?k=Q7RK2MXD&p=1234", out t, out why) && t.Host == "laptop.local", "host name, extra params ignored");
        Check(LinkAddress.TryParse("http://[fe80::1]:8790/?k=Q7RK2MXD", out t, out why) && t.Host == "fe80::1" && t.WsUrl(null, "x").StartsWith("ws://[fe80::1]:8790/ws?"), "IPv6");
        Check(!LinkAddress.TryParse("http://192.168.1.16:8790/", out t, out why) && why.Contains("key"), "missing key explained");
        Check(!LinkAddress.TryParse("192.168.1.16:99999/?k=Q7RK2MXD", out t, out why) && why.Contains("port"), "bad port explained");
        Check(!LinkAddress.TryParse("https://evil.example/?k=Q7RK2MXD", out t, out why), "https refused");
        Check(!LinkAddress.TryParse("javascript:alert(1)", out t, out why) && !LinkAddress.TryParse("", out t, out why), "garbage refused");
        Check(!LinkAddress.TryParse("1.2.3.4/?k=SHORT", out t, out why), "invalid key refused");
        var g = new LinkTarget { Host = "1.2.3.4", Port = 8790, Key = "Q7RK2MXD" };
        Check(g.WsUrl("4711", "My PC").EndsWith("/ws?k=Q7RK2MXD&client=windows&name=My%20PC&p=4711"), "ws url carries client=windows, name and PIN");
    }

    static void TestLoopbackListen()
    {
        int port = FreePort();
        var host = new PhoneLink(port, "ABCD2345", IPAddress.Loopback);
        host.PcName = "Host PC";
        host.Start();
        LinkClient client = null;
        try
        {
            host.SetMirroring(true, 48000);
            LinkTarget t; string why;
            LinkAddress.TryParse("127.0.0.1:" + port + "/?k=abcd2345", out t, out why);
            client = new LinkClient(t, null, "Listener PC");
            var got = new List<float>();
            var seqs = new List<uint>();
            client.Tap = (d, n, s) => { lock (got) { seqs.Add(s); for (int i = 0; i < n * 2; i += 2) got.Add(d[i]); } };
            client.Start();
            Check(WaitFor(() => client.State == ListenState.Playing, 5000), "connects and plays (" + client.State + " " + client.Detail + ")");
            Check(client.HostName == "Host PC" && client.PlayoutMs == 120, "hello read");
            Check(WaitFor(() => host.Snapshot().Count == 1 && host.Snapshot()[0].Client == "windows" && host.Snapshot()[0].Name == "Listener PC", 2000), "host lists a Windows listener");

            // 30 packets of a ramp, then drop packet 31-32 on the "network" by skipping their sequence numbers
            var buf = new float[480 * 2];
            int v = 0;
            for (int p = 0; p < 30; p++)
            {
                for (int i = 0; i < 480; i++) { float x = ((v++ % 1000) - 500) / 1000f; buf[2 * i] = x; buf[2 * i + 1] = x; }
                host.Broadcast(buf, 480, 48000);
                Thread.Sleep(2);
            }
            Check(WaitFor(() => { lock (got) return seqs.Count >= 30; }, 3000), "30 packets arrive");
            lock (got)
            {
                bool inOrder = true;
                for (int i = 1; i < seqs.Count; i++) if (seqs[i] != seqs[i - 1] + 1) inOrder = false;
                Check(inOrder, "in order");
                Check(Math.Abs(got[100] - ((100 % 1000) - 500) / 1000f) < 0.001, "samples survive the trip (16-bit)");
            }
            Check(WaitFor(() => client.Synced, 4000), "clock sync from sync replies + clock messages");
            Check(client.RttMs >= 0 && client.RttMs < 200, "round trip measured (" + client.RttMs.ToString("0.0") + " ms)");
            Check(WaitFor(() => host.Snapshot()[0].Synced, 1000), "host sees a synced listener");

            host.SuggestedEq = "voice";
            Check(WaitFor(() => client.SuggestedEq == "voice", 2000), "EQ suggestion reaches the laptop listener");
            var id = host.Snapshot()[0].Id;
            host.SetVolume(id, 70);
            Check(WaitFor(() => client.HostVolume == 70, 2000), "host volume control applied");
            Check(WaitFor(() => host.Snapshot()[0].Volume == 70, 6000), "reported back in status");
            Check(!client.Remote("next"), "remote refused while the host doesn't allow it");

            host.Kick(id);
            Check(WaitFor(() => client.State == ListenState.Kicked, 4000), "kick -> stays disconnected (" + client.State + ")");
            Thread.Sleep(1500);
            Check(host.ClientCount == 0, "no automatic reconnect after a kick");
        }
        finally
        {
            if (client != null) client.Dispose();
            host.Stop();
        }

        // PIN required: the client reports it instead of retrying forever
        int port2 = FreePort();
        var h2 = new PhoneLink(port2, "ABCD2345", IPAddress.Loopback);
        h2.Pin = "2468";
        h2.Start();
        try
        {
            var t2 = new LinkTarget { Host = "127.0.0.1", Port = port2, Key = "ABCD2345" };
            using (var c2 = new LinkClient(t2, null, "L"))
            {
                c2.Start();
                Check(WaitFor(() => c2.State == ListenState.NeedPin, 6000), "PIN needed -> asks for it (" + c2.State + ")");
            }
            using (var c3 = new LinkClient(t2, "2468", "L"))
            {
                c3.Start();
                Check(WaitFor(() => c3.State == ListenState.HostPaused || c3.State == ListenState.Playing, 5000), "right PIN joins (" + c3.State + ")");
            }
            var bad = new LinkTarget { Host = "127.0.0.1", Port = port2, Key = "ZZZZ2345" };
            using (var c4 = new LinkClient(bad, "2468", "L"))
            {
                c4.Start();
                Check(WaitFor(() => c4.State == ListenState.WrongKey, 6000), "wrong key -> says so (" + c4.State + ")");
            }
        }
        finally { h2.Stop(); }
    }
}
