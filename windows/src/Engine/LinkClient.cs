// Dual Earbuds - "Listen to another device" (laptop to laptop): this laptop joins another host's link as a
// listener (client=windows) and plays the sound on one or several of its own outputs.
// Listener side of docs/protocol.md: adaptive / smart buffer, clock sync, packet-loss concealment.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Net;
using System.Net.WebSockets;
using System.Text;
using System.Threading;

namespace DualEarbuds
{
    public class LinkTarget
    {
        public string Host;
        public int Port = 8790;
        public string Key;

        public string PageUrl { get { return "http://" + HostPart + ":" + Port.ToString(CultureInfo.InvariantCulture) + "/?k=" + Key; } }
        public string HostPort { get { return HostPart + ":" + Port.ToString(CultureInfo.InvariantCulture); } }
        string HostPart { get { return Host.Contains(":") && !Host.StartsWith("[") ? "[" + Host + "]" : Host; } }

        public string WsUrl(string pin, string name)
        {
            return "ws://" + HostPort + "/ws?k=" + Key + "&client=windows&name=" + Uri.EscapeDataString(name ?? "Laptop") +
                   (string.IsNullOrEmpty(pin) ? "" : "&p=" + pin);
        }
    }

    // Joining by pasted link: "http://IP:PORT/?k=KEY", also "IP:PORT/?k=KEY"; a missing port means 8790;
    // the key is case-insensitive.
    public static class LinkAddress
    {
        public const int DefaultPort = 8790;

        public static bool TryParse(string text, out LinkTarget target, out string problem)
        {
            target = null;
            problem = null;
            string s = (text ?? "").Trim();
            if (s.Length == 0) { problem = "Paste the link shown on the other device."; return false; }
            if (s.Length > 512) { problem = "That doesn't look like a Dual Earbuds link."; return false; }
            if (s.StartsWith("http://", StringComparison.OrdinalIgnoreCase)) s = s.Substring(7);
            else if (s.StartsWith("https://", StringComparison.OrdinalIgnoreCase)) { problem = "Dual Earbuds links start with http:// (they stay on your own network)."; return false; }
            else if (s.IndexOf("://", StringComparison.Ordinal) >= 0) { problem = "That doesn't look like a Dual Earbuds link."; return false; }

            string hostPort = s, query = "";
            int q = s.IndexOf('?');
            if (q >= 0) { hostPort = s.Substring(0, q); query = s.Substring(q + 1); }
            int slash = hostPort.IndexOf('/');
            if (slash >= 0) hostPort = hostPort.Substring(0, slash);
            int hash = query.IndexOf('#');
            if (hash >= 0) query = query.Substring(0, hash);

            string host = hostPort;
            int port = DefaultPort;
            if (hostPort.StartsWith("["))
            {
                int close = hostPort.IndexOf(']');
                if (close < 0) { problem = "The address in the link is incomplete."; return false; }
                host = hostPort.Substring(1, close - 1);
                string rest = hostPort.Substring(close + 1);
                if (rest.StartsWith(":") && !TryPort(rest.Substring(1), out port)) { problem = "The port number in the link isn't valid."; return false; }
            }
            else
            {
                int colon = hostPort.LastIndexOf(':');
                if (colon >= 0)
                {
                    host = hostPort.Substring(0, colon);
                    if (!TryPort(hostPort.Substring(colon + 1), out port)) { problem = "The port number in the link isn't valid."; return false; }
                }
            }
            if (host.Length == 0 || host.Length > 253) { problem = "The link is missing the other device's address."; return false; }
            foreach (char c in host)
                if (!(char.IsLetterOrDigit(c) || c == '.' || c == '-' || c == ':' || c == '%' || c == '_')) { problem = "The address in the link isn't valid."; return false; }

            var dict = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            PhoneLink.ParseQuery(query, dict);
            string key;
            if (!dict.TryGetValue("k", out key) || string.IsNullOrEmpty(key)) { problem = "The link is missing its key (the part after ?k=). Copy the whole link."; return false; }
            if (!LinkKey.IsValid(key)) { problem = "The key in the link isn't valid. Copy the whole link again."; return false; }
            target = new LinkTarget { Host = host, Port = port, Key = key.ToUpperInvariant() };
            return true;
        }

        static bool TryPort(string s, out int port)
        {
            return int.TryParse(s, NumberStyles.None, CultureInfo.InvariantCulture, out port) && port >= 1 && port <= 65535;
        }
    }

    public enum ListenState { Idle, Connecting, Playing, HostPaused, Reconnecting, NeedPin, WrongPin, WrongKey, Full, Kicked, Unreachable }

    // One local output playing what the host sends, kept on the host's schedule when clock sync works.
    public class ListenerOutput
    {
        public readonly string DeviceId, Name;
        public readonly OutputStream Stream;
        public volatile int DelayMs;            // manual Delay: always adds
        public volatile float Volume = 1f;      // this output's own volume
        public double OutputLatencyMs;          // calibrated (push -> heard), 0 when unknown
        readonly SyncTrim trim = new SyncTrim();
        readonly StereoResampler rs = new StereoResampler();
        float[] work = new float[4096];
        int lastBufferMs = -1, seenUnderruns;
        public double LastErrorMs;

        public ListenerOutput(string id, string name, OutputStream s) { DeviceId = id; Name = name; Stream = s; }

        public int NewUnderruns()
        {
            int u = Stream.Underruns, d = u - seenUnderruns;
            seenUnderruns = u;
            return d;
        }

        // Called on the receive thread for every 10 ms packet (real or concealed). playAtHost: host ms at which
        // the packet's first sample should be heard (NaN = not synced); hostNow: host ms now.
        public void Push(float[] pkt, int frames, double playAtHost, double hostNow, int jitterTargetMs, float masterGain)
        {
            var s = Stream;
            if (!s.Running) return;
            s.Gain = Volume * masterGain;
            int rate = s.Rate;
            if (!double.IsNaN(playAtHost))
            {
                if (!s.SyncMode) { s.SyncMode = true; s.SetBufferMs(0); lastBufferMs = 0; }
                double queuedMs = s.QueuedFrames * 1000.0 / rate;
                double heardAt = hostNow + queuedMs + OutputLatencyMs;
                double error = heardAt - (playAtHost + DelayMs);
                // never steer the queue below what the network needs; a late host plays late, not choppy
                double minQueue = jitterTargetMs;
                if (queuedMs - error < minQueue) error = queuedMs - minQueue;
                LastErrorMs = error;
                double ratio;
                int adj = trim.Decide(error, rate, out ratio);
                if (adj != 0) s.Adjust(-adj);
                if (work.Length < (frames + 32) * 2) work = new float[(frames + 32) * 2];   // only if packets grow
                int n = rs.Process(pkt, frames, work, work.Length / 2, ratio);
                s.Push(work, n);
                return;
            }
            if (s.SyncMode) { s.SyncMode = false; rs.Reset(); }
            int want = jitterTargetMs + DelayMs;
            if (want != lastBufferMs) { s.SetBufferMs(want); lastBufferMs = want; }
            s.Push(pkt, frames);
        }
    }

    public class LinkClient : IDisposable
    {
        public const int StatusEveryMs = 5000, SyncEveryMs = 2000, MaxPacketBytes = 65536;

        readonly LinkTarget target;
        readonly string pin, deviceName;
        readonly Stopwatch clock = Stopwatch.StartNew();
        readonly object sendLk = new object();
        readonly object outLk = new object();
        Thread thread, ticker;
        volatile bool stop;
        readonly CancellationTokenSource stopCts = new CancellationTokenSource();
        volatile ClientWebSocket ws;
        volatile ListenState state = ListenState.Idle;
        volatile string hostName = "", detail = "";
        volatile bool kicked, hostSharing, talking, remoteAllowed;
        volatile string source = "all", sourceApp, suggestedEq;
        volatile int playoutMs = PhoneLink.DefaultPlayoutMs, hostVolume = 100, rate;
        int underruns;
        long packets, concealed, lost;

        readonly ClockSync sync = new ClockSync();
        readonly PacketClock packetClock = new PacketClock();
        public readonly JitterBuffer Buffer = new JitterBuffer();
        Concealer plc;
        float[] pcm = new float[4096], gapBuf = new float[4096];
        bool haveSeq;
        uint lastSeq;
        volatile ListenerOutput[] outputs = new ListenerOutput[0];

        // Test / analysis tap: every packet that is played (after concealment), on the receive thread.
        public Action<float[], int, uint> Tap;
        public SoundClassifier Classifier;   // optional, fed on the receive thread (Smart EQ by sound)
        public event Action Changed;          // state or host info changed (any thread)
        public event Action<string> EqSuggested;
        public event Action<string> RemoteRefused;

        // Reported to the host in status messages.
        public volatile string Earbuds = "";
        public volatile int Battery = -1;

        public LinkClient(LinkTarget target, string pin, string deviceName)
        {
            this.target = target;
            this.pin = pin;
            this.deviceName = string.IsNullOrEmpty(deviceName) ? Environment.MachineName : deviceName;
            Buffer.Reset(0);
        }

        public LinkTarget Target { get { return target; } }
        public ListenState State { get { return state; } }
        public string HostName { get { return hostName; } }
        public string Detail { get { return detail; } }
        public bool HostTalking { get { return talking; } }
        public bool RemoteAllowed { get { return remoteAllowed; } }
        public string Source { get { return source; } }
        public string SourceApp { get { return sourceApp; } }
        public string SuggestedEq { get { return suggestedEq; } }
        public bool Synced { get { return sync.Synced && packetClock.Known; } }
        public double RttMs { get { return sync.BestRtt; } }
        public int Rate { get { return rate; } }
        public int PlayoutMs { get { return playoutMs; } }
        public int HostVolume { get { return hostVolume; } }
        public long Packets { get { return Interlocked.Read(ref packets); } }
        public long Concealed { get { return Interlocked.Read(ref concealed); } }
        public long Lost { get { return Interlocked.Read(ref lost); } }
        public int Underruns { get { return Thread.VolatileRead(ref underruns); } }
        public double NowMs { get { return clock.Elapsed.TotalMilliseconds; } }

        public ListenerOutput[] Outputs { get { return outputs; } }

        public void Start()
        {
            if (thread != null) return;
            stop = false;
            thread = new Thread(Run) { IsBackground = true, Name = "LinkClient" };
            thread.Start();
            ticker = new Thread(TickLoop) { IsBackground = true, Name = "LinkClientTick" };
            ticker.Start();
        }

        // Replaces the set of local outputs (they must already be started at the stream's rate).
        public void SetOutputs(List<ListenerOutput> list)
        {
            lock (outLk) outputs = list.ToArray();
        }

        void SetState(ListenState s, string d)
        {
            state = s;
            detail = d ?? "";
            Raise();
        }

        void Raise() { var h = Changed; if (h != null) { try { h(); } catch (Exception ex) { Log.Warn("Listen event: " + ex.Message); } } }

        // ------------------------------------------------------------------ connection
        void Run()
        {
            int backoff = 1000;
            while (!stop)
            {
                SetState(state == ListenState.Idle ? ListenState.Connecting : ListenState.Reconnecting, null);
                string refusal = null;
                bool connected = false;
                try
                {
                    var w = new ClientWebSocket();
                    w.Options.KeepAliveInterval = TimeSpan.Zero;   // the protocol has its own ping
                    ws = w;
                    using (var cts = CancellationTokenSource.CreateLinkedTokenSource(stopCts.Token))
                    {
                        cts.CancelAfter(5000);
                        w.ConnectAsync(new Uri(target.WsUrl(pin, deviceName)), cts.Token).Wait();
                    }
                    connected = true;
                    backoff = 1000;
                    haveSeq = false;
                    sync.Reset();
                    packetClock.Reset();
                    Buffer.Reset((long)NowMs);
                    Log.Info("Listening to " + target.HostPort);
                    SendText("{\"type\":\"sync\",\"c\":" + Num(NowMs) + "}");
                    Receive(w);
                }
                catch (Exception ex)
                {
                    refusal = Status(ex);
                    if (!stop) Log.Info("Listen: " + Flatten(ex));
                }
                finally
                {
                    var w = ws;
                    ws = null;
                    if (w != null) { try { w.Abort(); } catch { } try { w.Dispose(); } catch { } }
                }
                if (stop) break;
                if (kicked) { SetState(ListenState.Kicked, "The host removed this laptop."); break; }
                if (!connected && refusal != "503")
                {
                    // the upgrade was refused or failed: /info says whether the key is wrong or a PIN is needed
                    var probe = Probe();
                    if (probe == ProbeResult.Forbidden) { SetState(ListenState.WrongKey, null); break; }
                    if (probe == ProbeResult.PinRequired)
                    {
                        SetState(string.IsNullOrEmpty(pin) ? ListenState.NeedPin : ListenState.WrongPin, null);
                        break;
                    }
                    if (probe == ProbeResult.Unreachable && refusal == null) refusal = "unreachable";
                }
                if (refusal == "503") { SetState(ListenState.Full, "The host already has as many listeners as it allows."); Sleep(10000); continue; }
                SetState(ListenState.Reconnecting, refusal == "unreachable" ? "Can't reach " + target.HostPort + " - is the other device sharing, on the same Wi-Fi?" : "Connection lost.");
                Sleep(backoff);
                backoff = Math.Min(10000, backoff * 2);
            }
            if (state != ListenState.Kicked && state != ListenState.NeedPin && state != ListenState.WrongPin && state != ListenState.WrongKey)
                SetState(ListenState.Idle, null);
        }

        void Sleep(int ms)
        {
            for (int i = 0; i < ms / 100 && !stop; i++) Thread.Sleep(100);
        }

        static string Flatten(Exception ex)
        {
            var sb = new StringBuilder();
            for (var e = ex; e != null; e = e.InnerException) { if (sb.Length > 0) sb.Append(" / "); sb.Append(e.Message); }
            return sb.ToString();
        }

        // "403" / "503" when the server refused the upgrade with that status, else null.
        static string Status(Exception ex)
        {
            string all = Flatten(ex);
            if (all.Contains("403")) return "403";
            if (all.Contains("503")) return "503";
            return null;
        }

        enum ProbeResult { Unreachable, Forbidden, PinRequired, Open }

        ProbeResult Probe()
        {
            try
            {
                var req = (HttpWebRequest)WebRequest.Create("http://" + target.HostPort + "/info?k=" + target.Key);
                req.Timeout = 3000;
                req.ReadWriteTimeout = 3000;
                req.Proxy = null;
                using (var resp = (HttpWebResponse)req.GetResponse())
                using (var r = new StreamReader(resp.GetResponseStream(), Encoding.UTF8))
                {
                    var d = MiniJson.ParseObject(r.ReadToEnd());
                    return MiniJson.GetBool(d, "pin", false) ? ProbeResult.PinRequired : ProbeResult.Open;
                }
            }
            catch (WebException ex)
            {
                var hr = ex.Response as HttpWebResponse;
                bool forbidden = hr != null && hr.StatusCode == HttpStatusCode.Forbidden;
                if (hr != null) hr.Close();
                return forbidden ? ProbeResult.Forbidden : ProbeResult.Unreachable;
            }
            catch { return ProbeResult.Unreachable; }
        }

        void Receive(ClientWebSocket w)
        {
            var buf = new byte[MaxPacketBytes];
            while (!stop && w.State == WebSocketState.Open)
            {
                int got = 0;
                WebSocketReceiveResult r;
                do
                {
                    r = w.ReceiveAsync(new ArraySegment<byte>(buf, got, buf.Length - got), stopCts.Token).Result;
                    got += r.Count;
                    if (r.MessageType == WebSocketMessageType.Close) return;
                    if (got >= buf.Length && !r.EndOfMessage) throw new IOException("message too large");
                }
                while (!r.EndOfMessage);
                if (r.MessageType == WebSocketMessageType.Binary) OnAudio(buf, got);
                else OnText(Encoding.UTF8.GetString(buf, 0, got));
            }
        }

        public void SendText(string json)
        {
            var w = ws;
            if (w == null || w.State != WebSocketState.Open) return;
            byte[] b = Encoding.UTF8.GetBytes(json);
            try
            {
                lock (sendLk) w.SendAsync(new ArraySegment<byte>(b), WebSocketMessageType.Text, true, CancellationToken.None).Wait(3000);
            }
            catch (Exception ex) { Log.Info("Listen send: " + Flatten(ex)); }
        }

        static string Num(double v) { return Math.Round(v, 3).ToString("R", CultureInfo.InvariantCulture); }

        // ------------------------------------------------------------------ messages
        void OnText(string text)
        {
            Dictionary<string, object> d;
            try { d = MiniJson.ParseObject(text); }
            catch { return; }
            string type = MiniJson.GetString(d, "type", "");
            switch (type)
            {
                case "hello":
                    hostName = MiniJson.GetString(d, "pc", "") ?? "";
                    int po = MiniJson.GetInt(d, "playoutMs", PhoneLink.DefaultPlayoutMs);
                    playoutMs = Math.Max(30, Math.Min(1000, po));
                    remoteAllowed = MiniJson.GetBool(d, "remote", false);
                    ReadSource(d);
                    hostSharing = MiniJson.GetInt(d, "rate", 0) > 0;
                    SetState(hostSharing ? ListenState.Playing : ListenState.HostPaused, null);
                    break;
                case "ping":
                    object t;
                    if (d.TryGetValue("t", out t) && t is double) SendText("{\"type\":\"pong\",\"t\":" + Num((double)t) + "}");
                    break;
                case "sync":
                    object c, h;
                    if (d.TryGetValue("c", out c) && c is double && d.TryGetValue("h", out h) && h is double)
                        sync.Add((double)c, (double)h, NowMs);
                    break;
                case "clock":
                    object s, hh;
                    if (d.TryGetValue("seq", out s) && s is double && d.TryGetValue("h", out hh) && hh is double)
                    {
                        double sv = (double)s;
                        if (sv >= 0 && sv <= uint.MaxValue) packetClock.OnClock((uint)sv, (double)hh);
                    }
                    break;
                case "state":
                    hostSharing = MiniJson.GetBool(d, "mirroring", false);
                    ReadSource(d);
                    SetState(hostSharing ? ListenState.Playing : ListenState.HostPaused, null);
                    break;
                case "control":
                    int vol = MiniJson.GetInt(d, "volume", -1);
                    if (vol >= 0) { hostVolume = PhoneLink.ClampVolume(vol); SendStatus(); Raise(); }
                    string eq = MiniJson.GetString(d, "eq", null);
                    if (EqPresets.IsValid(eq))
                    {
                        suggestedEq = eq;
                        var e = EqSuggested;
                        if (e != null) e(eq);
                        Raise();
                    }
                    break;
                case "talk":
                    talking = MiniJson.GetBool(d, "on", false);
                    Raise();
                    break;
                case "kick":
                    kicked = true;
                    Log.Info("Removed by the host: " + MiniJson.GetString(d, "reason", ""));
                    break;
                case "party":
                    Raise();
                    break;
            }
        }

        void ReadSource(Dictionary<string, object> d)
        {
            string src = MiniJson.GetString(d, "source", null);
            if (src == null) return;
            source = src == "app" ? "app" : "all";
            sourceApp = source == "app" ? (MiniJson.GetString(d, "sourceApp", null) ?? MiniJson.GetString(d, "app", null)) : null;
        }

        // Sends a playback command to the host (it must have "Let listeners control playback" on).
        public bool Remote(string action)
        {
            if (!MediaKeys.IsAction(action)) return false;
            if (!remoteAllowed) { var h = RemoteRefused; if (h != null) h(action); return false; }
            SendText("{\"type\":\"remote\",\"action\":\"" + action + "\"}");
            return true;
        }

        void SendStatus()
        {
            var o = outputs;
            bool playing = hostSharing && state == ListenState.Playing && o.Length > 0;
            int bufferMs = Synced ? playoutMs : Buffer.TargetMs;
            SendText(string.Format(CultureInfo.InvariantCulture,
                "{{\"type\":\"status\",\"device\":\"{0}\",\"earbuds\":\"{1}\",\"battery\":{2},\"volume\":{3},\"bufferMs\":{4},\"underruns\":{5},\"playing\":{6}}}",
                MiniJson.Escape(deviceName), MiniJson.Escape(Earbuds ?? ""), Battery, hostVolume, bufferMs, Underruns, playing ? "true" : "false"));
        }

        void TickLoop()
        {
            long lastStatus = -StatusEveryMs, lastSync = 0;
            int quickSyncs = 0;
            while (!stop)
            {
                Thread.Sleep(100);
                var w = ws;
                if (w == null || w.State != WebSocketState.Open) { quickSyncs = 0; continue; }
                long now = (long)NowMs;
                // 5 quick syncs after connecting, then every 2 s
                int every = quickSyncs < 5 ? 200 : SyncEveryMs;
                if (now - lastSync >= every)
                {
                    lastSync = now;
                    quickSyncs++;
                    SendText("{\"type\":\"sync\",\"c\":" + Num(NowMs) + "}");
                }
                foreach (var o in outputs)
                {
                    int u = o.NewUnderruns();
                    if (u > 0 && hostSharing) { Interlocked.Add(ref underruns, u); Buffer.OnUnderrun(now); }
                }
                Buffer.Tick(now);
                if (now - lastStatus >= StatusEveryMs) { lastStatus = now; SendStatus(); }
            }
        }

        // ------------------------------------------------------------------ audio
        void OnAudio(byte[] b, int len)
        {
            if (len < 8 || ((len - 8) & 3) != 0) return;
            int r = b[0] | (b[1] << 8) | (b[2] << 16) | (b[3] << 24);
            uint seq = (uint)(b[4] | (b[5] << 8) | (b[6] << 16) | (b[7] << 24));
            if (r < 8000 || r > 384000) return;
            int frames = (len - 8) / 4;
            if (r != rate || plc == null || plc.PacketFrames != PhoneLink.PacketFrames(r))
            {
                rate = r;
                plc = new Concealer(r);   // once per rate change
                haveSeq = false;
                Raise();
            }
            if (frames * 2 > pcm.Length) { pcm = new float[frames * 2]; gapBuf = new float[frames * 2]; }
            for (int i = 0; i < frames * 2; i++)
            {
                int o = 8 + i * 2;
                pcm[i] = (short)(b[o] | (b[o + 1] << 8)) / 32768f;
            }
            Buffer.OnPacket((long)NowMs);
            int missing = SeqGap.Missing(haveSeq, lastSeq, seq);
            if (missing < 0) return;   // late or duplicate: already concealed
            if (missing > 0)
            {
                Interlocked.Add(ref lost, missing);
                int fill = Math.Min(missing, SeqGap.MaxConceal);
                int pf = plc.PacketFrames;
                for (int i = 0; i < fill; i++)
                {
                    plc.Conceal(gapBuf, pf);
                    Interlocked.Increment(ref concealed);
                    Emit(gapBuf, pf, unchecked(seq - (uint)(missing - i)));
                }
            }
            plc.OnPacket(pcm, frames);
            haveSeq = true;
            lastSeq = seq;
            Interlocked.Increment(ref packets);
            Emit(pcm, frames, seq);
        }

        void Emit(float[] data, int frames, uint seq)
        {
            var tap = Tap;
            if (tap != null) tap(data, frames, seq);
            var cl = Classifier;
            if (cl != null) cl.FeedStereo(data, frames);
            double hostNow = double.NaN, playAt = double.NaN;
            if (sync.Synced && packetClock.Known)
            {
                hostNow = sync.HostNow(NowMs);
                playAt = packetClock.HostTime(seq) + playoutMs;
            }
            float master = hostVolume / 100f;
            int jt = Buffer.TargetMs;
            foreach (var o in outputs) o.Push(data, frames, playAt, hostNow, jt, master);
        }

        // ------------------------------------------------------------------ shutdown
        public void Stop()
        {
            stop = true;
            try { stopCts.Cancel(); } catch (ObjectDisposedException) { }
            var w = ws;
            if (w != null)
            {
                try
                {
                    lock (sendLk) w.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, "bye", CancellationToken.None).Wait(500);
                }
                catch { }
                try { w.Abort(); } catch { }
            }
            if (thread != null) thread.Join(3000);
            if (ticker != null) ticker.Join(1000);
            thread = null;
            ticker = null;
        }

        public void Dispose()
        {
            Stop();
            stopCts.Dispose();
        }
    }
}
