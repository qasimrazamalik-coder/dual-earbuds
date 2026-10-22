// Interop harness: the Windows engine (C# 5) as host (PhoneLink) or listener (LinkClient), unchanged, with a
// 1 kHz tone source / a simulated output device. Prints "CHECK PASS|FAIL [who] name :: detail" lines; exit 1 on
// any failure. Built with the engine sources by tests/interop/run.sh.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Net;
using System.Reflection;
using System.Threading;
using DualEarbuds;

static class InteropDriver
{
    static string who = "csharp";
    static int failed;

    static void Check(bool ok, string name, string detail)
    {
        if (!ok) failed++;
        Console.WriteLine((ok ? "CHECK PASS " : "CHECK FAIL ") + "[" + who + "] " + name + (string.IsNullOrEmpty(detail) ? "" : " :: " + detail));
    }

    static void Info(string s) { Console.WriteLine("INFO [" + who + "] " + s); }

    static Dictionary<string, string> ParseArgs(string[] a)
    {
        var d = new Dictionary<string, string>();
        for (int i = 0; i < a.Length; i++)
        {
            if (!a[i].StartsWith("--")) continue;
            if (i + 1 < a.Length && !a[i + 1].StartsWith("--")) { d[a[i].Substring(2)] = a[i + 1]; i++; }
            else d[a[i].Substring(2)] = "true";
        }
        return d;
    }

    static string Arg(Dictionary<string, string> d, string k, string def) { string v; return d.TryGetValue(k, out v) ? v : def; }

    static double EpochMs() { return (DateTime.UtcNow - new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc)).TotalMilliseconds; }

    static string F(double v) { return v.ToString("0.0", CultureInfo.InvariantCulture); }

    static int Main(string[] args)
    {
        var a = ParseArgs(args);
        string mode = Arg(a, "mode", "host");
        try
        {
            if (mode == "host") Host(a); else Listen(a);
        }
        catch (Exception ex) { Check(false, "driver ran", ex.ToString()); }
        Console.Out.Flush();
        Environment.Exit(failed == 0 ? 0 : 1);
        return 0;
    }

    // ------------------------------------------------------------------ host: PhoneLink + tone
    static void Host(Dictionary<string, string> a)
    {
        who = "csharp-host";
        int port = int.Parse(Arg(a, "port", "8790"), CultureInfo.InvariantCulture);
        double seconds = double.Parse(Arg(a, "seconds", "8"), CultureInfo.InvariantCulture);
        var link = new PhoneLink(port, Arg(a, "key", "ABCD2345"), IPAddress.Loopback);
        link.PcName = "CSharp host";
        string pin = Arg(a, "pin", null);
        if (pin != null) link.Pin = pin;
        link.LocalNeedMs = 0;   // no local outputs: P must follow the listeners
        if (!link.Start()) { Check(false, "host starts", link.Error); return; }
        string ef = Arg(a, "epoch-file", null);
        if (ef != null) File.WriteAllText(ef, (EpochMs() - link.NowMs).ToString("0.000", CultureInfo.InvariantCulture));
        link.SetMirroring(true, 48000);
        string seqStart = Arg(a, "seq-start", null);
        if (seqStart != null)
        {
            // start near the uint32 wrap so it happens mid-run (sequence numbers are unsigned on the wire)
            typeof(PhoneLink).GetField("seq", BindingFlags.NonPublic | BindingFlags.Instance).SetValue(link, uint.Parse(seqStart, CultureInfo.InvariantCulture));
            Info("sequence starts at " + seqStart);
        }
        Info("listening on " + port + " udp=" + link.UdpAvailable + " pin=" + (pin != null));

        bool stop = false;
        var tone = new Thread(() =>
        {
            var buf = new float[480 * 2];
            long n = 0, k = 0;
            var sw = Stopwatch.StartNew();
            while (!stop)
            {
                for (int i = 0; i < 480; i++) { float x = (float)(0.3 * Math.Sin(2 * Math.PI * 1000.0 * (k + i) / 48000.0)); buf[2 * i] = x; buf[2 * i + 1] = x; }
                k += 480;
                link.Broadcast(buf, 480, 48000);
                n++;
                long wait = n * 10 - sw.ElapsedMilliseconds;
                if (wait > 0) Thread.Sleep((int)wait);
            }
        }) { IsBackground = true, Name = "tone" };
        tone.Start();

        bool sawUdp = false, sawWsAfter = false;
        int needSeen = 0, maxNeed = 0, errSeen = int.MinValue, lastNeed = -1, pWith = -1;
        long udpAt = 0, lastNeedChange = 0;
        string followFail = null, aboveFail = null;
        var sw2 = Stopwatch.StartNew();
        while (sw2.Elapsed.TotalSeconds < seconds)
        {
            Thread.Sleep(100);
            long now = sw2.ElapsedMilliseconds;
            int p = link.PlayoutMs;
            foreach (var l in link.Snapshot())
            {
                if (l.Client == "web") continue;
                pWith = p;
                if (l.Transport == "udp" && !sawUdp) { sawUdp = true; udpAt = now; Info("listener " + l.Name + " on UDP"); }
                if (sawUdp && l.Transport == "ws" && !sawWsAfter) { sawWsAfter = true; Info("listener back on WebSocket after " + (now - udpAt) + " ms of UDP"); }
                if (l.NeedMs > 0)
                {
                    if (needSeen == 0) Info("first needMs " + l.NeedMs + " (P was " + p + ")");
                    needSeen = l.NeedMs;
                    if (l.NeedMs != lastNeed) { lastNeed = l.NeedMs; lastNeedChange = now; }
                    if (l.Synced) maxNeed = Math.Max(maxNeed, l.NeedMs);
                    int want = Math.Max(40, Math.Min(400, l.NeedMs));
                    if (l.Synced && now - lastNeedChange > 1500 && p < want && followFail == null) followFail = "P=" + p + " < need=" + l.NeedMs;
                }
                if (l.OffsetMs != int.MinValue) errSeen = l.OffsetMs;
            }
            if (maxNeed > 0 && p > Math.Max(40, maxNeed) && aboveFail == null) aboveFail = "P=" + p + " above every need (max " + maxNeed + ")";
        }
        Info("end: P=" + pWith + " need=" + needSeen + " maxNeed=" + maxNeed + " syncErrMs=" + (errSeen == int.MinValue ? "-" : errSeen.ToString()) +
             " udpSent=" + link.UdpDatagramsSent + " keepalivesDropped=" + link.KeepalivesDropped);
        Check(sawUdp, "native listener got UDP audio (host side)", null);
        if (a.ContainsKey("expect-fallback")) Check(sawWsAfter, "listener moved back to WebSocket audio (host side)", null);
        Check(needSeen > 0, "listener reported needMs", "last " + needSeen);
        Check(errSeen != int.MinValue, "listener reported syncErrMs", errSeen == int.MinValue ? "never" : "last " + errSeen);
        Check(needSeen > 0 && followFail == null && aboveFail == null && pWith != 120, "P follows needMs (local need 0)",
              ("P=" + pWith + " need=" + needSeen + " " + (followFail ?? "") + " " + (aboveFail ?? "")).Trim());
        stop = true;
        link.Stop();
    }

    // ------------------------------------------------------------------ listener: LinkClient + simulated device
    static void Listen(Dictionary<string, string> a)
    {
        who = "csharp-listener";
        string host = Arg(a, "host", "127.0.0.1");
        int port = int.Parse(Arg(a, "port", "8790"), CultureInfo.InvariantCulture);
        string key = Arg(a, "key", "ABCD2345");
        string pin = Arg(a, "pin", null);
        double seconds = double.Parse(Arg(a, "seconds", "6"), CultureInfo.InvariantCulture);
        double loss = double.Parse(Arg(a, "loss", "0"), CultureInfo.InvariantCulture);
        int blockAt = int.Parse(Arg(a, "block-at-ms", "-1"), CultureInfo.InvariantCulture);
        string ef = Arg(a, "epoch-file", null);
        double epoch = double.NaN;
        if (ef != null && File.Exists(ef)) epoch = double.Parse(File.ReadAllText(ef).Trim(), CultureInfo.InvariantCulture);
        var target = new LinkTarget { Host = host, Port = port, Key = key };

        if (pin != null)
        {
            using (var wrong = new LinkClient(target, pin == "1111" ? "2222" : "1111", "Wrong PIN"))
            {
                wrong.Start();
                Check(WaitFor(() => wrong.State == ListenState.WrongPin, 6000), "wrong PIN is rejected", wrong.State.ToString());
            }
            using (var none = new LinkClient(target, null, "No PIN"))
            {
                none.Start();
                Check(WaitFor(() => none.State == ListenState.NeedPin, 6000), "missing PIN is detected", none.State.ToString());
            }
        }

        var c = new LinkClient(target, pin, "CSharp listener");
        // simulated Wi-Fi loss / blocked UDP in front of the parser; remembers which packets were lost
        var rnd = new Random(7);
        bool blocked = false;
        long dropped = 0;
        var droppedSeqs = new HashSet<uint>();
        c.UdpDropForTest = (buf, n) =>
        {
            if (!blocked && !(loss > 0 && rnd.NextDouble() < loss)) return false;
            UdpBlock cur, prev; bool hasPrev;
            if (UdpAudio.TryParse(buf, n, out cur, out hasPrev, out prev)) lock (droppedSeqs) droppedSeqs.Add(cur.Seq);
            Interlocked.Increment(ref dropped);
            return true;
        };
        long badTone = 0;
        bool wrapped = false;
        uint lastTap = 0;
        c.Tap = (d, frames, s) =>
        {
            if (lastTap > 0xFFFFFF00u && s < 0x100u) wrapped = true;
            lastTap = s;
            int z = 0;
            for (int i = 2; i < frames * 2; i += 2) if ((d[i - 2] < 0) != (d[i] < 0)) z++;
            if (z != 0 && (z < 19 || z > 21)) Interlocked.Increment(ref badTone);   // concealed fade-outs may be silent
        };

        // the simulated output: a 40 ms device buffer drained every 10 ms, 150 ms more until the ear ("calibrated")
        var st = new OutputStream("sim", 48000, 0, 1f);
        st.MarkRunningForTest();
        var lo = new ListenerOutput("sim", "Simulated earbuds", st) { OutputLatencyMs = 150 };
        c.SetOutputs(new List<ListenerOutput> { lo });
        bool stopDev = false;
        var dev = new Thread(() =>
        {
            const int per = 480, devCap = 1920;
            var tmp = new float[devCap * 2];
            int pad = 0;
            var sw = Stopwatch.StartNew();
            long n = 0;
            while (!stopDev)
            {
                pad = Math.Max(0, pad - per);
                st.SetPaddingForTest(pad);
                int got = st.Fill(tmp, devCap - pad, pad == 0);
                pad += got;
                st.SetPaddingForTest(pad);
                n++;
                long wait = n * 10 - sw.ElapsedMilliseconds;
                if (wait > 0) Thread.Sleep((int)wait);
            }
        }) { IsBackground = true, Name = "sim-device" };
        dev.Start();

        c.Start();
        var syncField = typeof(LinkClient).GetField("sync", BindingFlags.NonPublic | BindingFlags.Instance);
        var t0 = Stopwatch.StartNew();
        bool switched = false;
        long udpAt = -1, blockedAt = -1, fallbackMs = -1, pktAtFallback = 0, wsAtUdp = 0, lostAtUdp = 0;
        double maxErr = 0, clockErr = double.NaN;
        int errSamples = 0;
        var errs = new List<double>();
        long lastLost = 0;
        while (t0.Elapsed.TotalSeconds < seconds)
        {
            Thread.Sleep(50);
            long now = t0.ElapsedMilliseconds;
            if (c.Transport == "udp" && udpAt < 0) { udpAt = now; wsAtUdp = c.WsPackets; Info("UDP active after " + now + " ms"); }
            // the switch is over 300 ms later (WebSocket packets still in flight have arrived)
            if (udpAt >= 0 && !switched && now >= udpAt + 300) { switched = true; wsAtUdp = c.WsPackets; lostAtUdp = c.Lost; }
            if (blockAt >= 0 && udpAt >= 0 && blockedAt < 0 && now - udpAt >= blockAt) { blocked = true; blockedAt = now; Info("blocking UDP (all datagrams dropped) at " + now + " ms"); }
            if (blockedAt >= 0 && fallbackMs < 0 && c.Transport == "ws" && c.WsPackets > wsAtUdp + 5)
            {
                fallbackMs = now - blockedAt; pktAtFallback = c.Packets;
                Info("WebSocket audio again " + fallbackMs + " ms after the block");
            }
            double off = c.OffsetMs;
            // (the outage and the second after the fallback are a re-buffering, not clock sync)
            bool settling = blockedAt >= 0 && (fallbackMs < 0 || now < blockedAt + fallbackMs + 1500);
            if (c.Synced && !double.IsNaN(off) && now > seconds * 1000 - 2000 && !settling) { errSamples++; maxErr = Math.Max(maxErr, Math.Abs(off)); errs.Add(Math.Abs(off)); }
            if (c.Lost != lastLost) { Info("lost " + (c.Lost - lastLost) + " at " + now + " ms (ws " + c.WsPackets + ", udp " + c.UdpPackets + ", transport " + c.Transport + ")"); lastLost = c.Lost; }
            if (!double.IsNaN(epoch) && c.Synced)
            {
                var cs = (ClockSync)syncField.GetValue(c);
                clockErr = (EpochMs() - epoch) - cs.HostNow(c.NowMs);
            }
        }
        long udpEnd = blockedAt >= 0 ? blockedAt : t0.ElapsedMilliseconds;
        Info("state=" + c.State + " packets=" + c.Packets + " ws=" + c.WsPackets + " udp=" + c.UdpPackets + " recovered=" + c.UdpRecovered +
             " dropped=" + dropped + " lost=" + c.Lost + " concealed=" + c.Concealed + " P=" + c.PlayoutMs + " need=" + c.NeedMs + " transport=" + c.Transport);
        double outage = fallbackMs > 0 ? fallbackMs / 1000.0 : 0;   // packets sent while UDP was blocked are gone
        Check(c.Packets > (seconds - outage) * 100 * 0.7, "audio flows", c.Packets + " packets in " + seconds + " s" + (outage > 0 ? " (" + F(outage) + " s blocked)" : ""));
        Check(Interlocked.Read(ref badTone) == 0, "PCM is the 1 kHz tone (byte order, layout)", badTone + " odd packets");
        Check(udpAt >= 0, "UDP negotiated (hello udp+token, keepalive)", null);
        if (udpAt >= 0 && blockAt < 0)
            Check(c.WsPackets - wsAtUdp <= 2, "no WebSocket binary while UDP works", (c.WsPackets - wsAtUdp) + " WS packets in " + (udpEnd - udpAt - 300) + " ms of UDP");
        if (loss == 0 && blockAt < 0)
        {
            Check(c.Lost - lostAtUdp == 0, "sequence continuous over UDP", "lost " + (c.Lost - lostAtUdp));
            // UDP can overtake WebSocket packets still in flight when audio moves over: those arrive late (dropped)
            Check(lostAtUdp <= 10, "at most 100 ms concealed at the WebSocket -> UDP switch", lostAtUdp + " packets");
        }
        if (a.ContainsKey("expect-wrap")) Check(wrapped, "uint32 sequence wrap crossed without a gap", "lost " + (c.Lost - lostAtUdp) + " after the switch");
        if (loss > 0)
        {
            int unrecoverable = 0;
            lock (droppedSeqs) foreach (var s in droppedSeqs) if (droppedSeqs.Contains(unchecked(s + 1))) unrecoverable++;
            long residual = c.Lost - lostAtUdp;
            Info("loss: " + dropped + " datagrams dropped, residual gaps " + residual + ", double losses " + unrecoverable);
            Check(dropped > 20, "loss was injected", dropped + " datagrams");
            Check(residual <= unrecoverable + 1, "redundancy hides single losses", "residual " + residual + ", unrecoverable double losses " + unrecoverable + ", raw drops " + dropped);
        }
        errs.Sort();
        double median = errs.Count > 0 ? errs[errs.Count / 2] : double.NaN;
        // (a P increase below the 40 ms snap is trimmed away at 0.5 %, i.e. 5 ms per second: allow for that)
        Check(errSamples > 0 && median < 25, "plays towards the common deadline (median |syncErr| < 25 ms, last 2 s)", "median " + F(median) + " ms, max " + F(maxErr) + " ms over " + errSamples + " samples");
        if (!double.IsNaN(epoch)) Check(!double.IsNaN(clockErr) && Math.Abs(clockErr) < 3, "host clock estimate matches the host", "off by " + F(clockErr) + " ms");
        int need = c.NeedMs;
        Check(need > 0 && !double.IsNaN(c.OffsetMs), "status carries needMs + syncErrMs", "need " + need + " offset " + F(c.OffsetMs));
        Check(c.PlayoutMs >= Math.Min(400, need) - 15 && c.PlayoutMs > 120, "host P follows our needMs", "P=" + c.PlayoutMs + " need=" + need);
        if (blockAt >= 0)
        {
            Check(fallbackMs >= 0 && fallbackMs <= 2600, "WebSocket fallback within ~2 s of losing UDP", fallbackMs + " ms");
            Check(c.Packets > pktAtFallback + 50, "audio continues after the fallback", (c.Packets - pktAtFallback) + " packets after");
        }
        stopDev = true;
        c.Dispose();
    }

    static bool WaitFor(Func<bool> cond, int ms)
    {
        var sw = Stopwatch.StartNew();
        while (sw.ElapsedMilliseconds < ms) { if (cond()) return true; Thread.Sleep(20); }
        return cond();
    }
}
