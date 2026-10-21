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
            Check(info.StartsWith("HTTP/1.1 200") && info.Contains("\"protocol\":2") && info.Contains("\"format\":\"s16le\""), "info json");
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
                Check(hello != null && hello.Contains("\"type\":\"hello\"") && hello.Contains("TEST-PC") && hello.Contains("\"protocol\":2"), "hello message");
                Check(WaitFor(() => link.ClientCount == 1, 1000), "client counted");
                var snap = link.Snapshot();
                Check(snap.Count == 1 && snap[0].Name == "Redmi Note 12" && snap[0].Client == "android", "name and client type from query");

                w.SendText("{\"type\":\"status\",\"device\":\"Redmi Note 12\",\"earbuds\":\"Evo Z-Buds\",\"battery\":80,\"volume\":120,\"bufferMs\":200,\"playing\":true}");
                Check(WaitFor(() => { var s = link.Snapshot(); return s.Count == 1 && s[0].Battery == 80; }, 1500), "status received");
                var p0 = link.Snapshot()[0];
                Check(p0.Earbuds == "Evo Z-Buds" && p0.Volume == 120 && p0.BufferMs == 200 && p0.Playing, "status fields");

                w.SendText("{\"type\":\"status\",\"battery\":250}");
                Check(WaitFor(() => link.Snapshot()[0].Battery == -1, 1500), "out-of-range battery becomes -1");
                w.SendText("not json at all");
                w.SendText("{\"type\":\"pong\",\"t\":" + link.NowMs + "}");
                Check(WaitFor(() => link.Snapshot()[0].RttMs >= 0, 1500), "pong sets round-trip time");

                link.SetMirroring(true, 48000);
                string state = w.NextText(10);
                Check(state != null && state.Contains("\"mirroring\":true") && state.Contains("48000"), "state message");

                var data = new float[] { 0.5f, -0.5f, 0.25f, -0.25f };
                link.Broadcast(data, 2, 48000);
                link.Broadcast(data, 2, 48000);
                byte[] a1 = null, a2 = null;
                for (int i = 0; i < 10 && a2 == null; i++)
                {
                    byte[] p;
                    int op = w.Receive(out p);
                    if (op == 2) { if (a1 == null) a1 = p; else a2 = p; }
                    if (op < 0) break;
                }
                Check(a1 != null && a1.Length == 8 + 8 && BitConverter.ToInt32(a1, 0) == 48000, "audio packet header + size");
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
}
