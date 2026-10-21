// Dual Earbuds - talk-over: the host's microphone is mixed into the shared stream while the music
// ducks by 12 dB (protocol v3.1 "Talk-over"), with optional mic noise cancelling (smart feature #1).
// The mic runs on its own capture thread; mixing happens on the capture thread of the music, without
// allocations.
using System;
using System.Diagnostics;
using System.Threading;

namespace DualEarbuds
{
    public class TalkOver
    {
        public const double DuckDb = -12;
        public const int CloseAfterMs = 3000;
        readonly object lk = new object();
        LoopbackCapture mic;
        Ring ring;
        readonly NoiseSuppressor ns = new NoiseSuppressor();
        float[] mono = new float[4096], stereo = new float[8192], micTmp = new float[8192];
        int rate;
        volatile bool talking;
        float duck = 1f;
        long stoppedAt = -1;
        readonly Stopwatch clock = Stopwatch.StartNew();
        public volatile string Error;

        public bool Talking { get { return talking; } }
        public bool MicOpen { get { var m = mic; return m != null && m.Running; } }

        public NoiseMode Noise
        {
            get { return ns.Mode; }
            set { ns.Mode = value; }
        }

        public static float DuckGain { get { return (float)Gain.DbToLin(DuckDb); } }

        // Blocks while Windows opens the microphone (up to a few seconds): call from a background thread.
        public bool Begin(int streamRate)
        {
            lock (lk)
            {
                if (mic != null && mic.Running && rate == streamRate) { talking = true; stoppedAt = -1; return true; }
                CloseLocked();
                rate = streamRate > 0 ? streamRate : 48000;
                ring = new Ring(rate);   // 1 s
                var m = new LoopbackCapture(CaptureKind.Microphone, null, 0, rate);
                m.OnData = OnMic;
                m.Start();
                if (!m.Running)
                {
                    Error = m.Error ?? "The microphone couldn't be opened.";
                    m.Stop();
                    return false;
                }
                Error = null;
                mic = m;
                talking = true;
                stoppedAt = -1;
                Log.Info("Talk-over on (noise cancelling " + ns.Mode + ")");
                return true;
            }
        }

        // Unit tests: talk without a microphone (only the ducking is heard).
        public void SimulateForTest(bool on, int streamRate) { rate = streamRate; talking = on; }

        public void End()
        {
            if (!talking) return;
            talking = false;
            stoppedAt = clock.ElapsedMilliseconds;
            Log.Info("Talk-over off");
        }

        // Called now and then (e.g. every 2 s): closes the mic a little after talking stopped.
        public void Tick()
        {
            lock (lk)
            {
                if (!talking && mic != null && stoppedAt >= 0 && clock.ElapsedMilliseconds - stoppedAt > CloseAfterMs) CloseLocked();
            }
        }

        void CloseLocked()
        {
            var m = mic;
            mic = null;
            if (m != null) m.Stop();
        }

        public void Close() { lock (lk) { talking = false; CloseLocked(); } }

        // Mic thread: stereo -> mono -> noise suppression -> ring (as stereo).
        void OnMic(float[] data, int frames)
        {
            var r = ring;
            if (r == null) return;
            int done = 0;
            while (done < frames)
            {
                int n = Math.Min(mono.Length, frames - done);
                for (int i = 0; i < n; i++) mono[i] = 0.5f * (data[2 * (done + i)] + data[2 * (done + i) + 1]);
                ns.Process(mono, 0, n);
                for (int i = 0; i < n; i++) { stereo[2 * i] = mono[i]; stereo[2 * i + 1] = mono[i]; }
                lock (r) r.Write(stereo, n);
                done += n;
            }
        }

        // Music capture thread, in place: ducks the music and adds the voice.
        public void MixInto(float[] data, int frames)
        {
            var r = ring;
            bool on = talking;
            if (!on && duck >= 0.9999f) return;
            float target = on ? DuckGain : 1f;
            float step = 1f / Math.Max(1, rate * 0.05f);   // ~50 ms glide
            int got = 0;
            if (r != null)
            {
                if (micTmp.Length < frames * 2) micTmp = new float[frames * 2];   // only if the capture packet grows
                lock (r)
                {
                    int excess = r.Count - rate / 10;          // mic clock drift: keep at most 100 ms queued
                    if (excess > 0) r.Discard(excess);
                    got = r.Read(micTmp, 0, frames);
                }
            }
            for (int i = 0; i < frames; i++)
            {
                if (duck < target) duck = Math.Min(target, duck + step);
                else if (duck > target) duck = Math.Max(target, duck - step);
                float vl = i < got && on ? micTmp[2 * i] : 0f, vr = i < got && on ? micTmp[2 * i + 1] : 0f;
                data[2 * i] = Gain.SoftClip(data[2 * i] * duck + vl);
                data[2 * i + 1] = Gain.SoftClip(data[2 * i + 1] * duck + vr);
            }
        }
    }

    // Auto-sync calibration on real hardware: plays the click train on one output and listens with the
    // default microphone. Blocks for ~7 s: run it on a background thread.
    public static class CalibrationRunner
    {
        public static Calibration.Result Run(string outputId, Func<bool> cancelled)
        {
            const int rate = 48000;
            var res = new Calibration.Result();
            int[] offsets;
            float[] train = Calibration.BuildTrain(rate, 0.5f, out offsets);
            var rec = new float[rate * 12];
            int recLen = 0;
            long micFirst = -1;   // 100 ns QPC of rec[0]
            object rlk = new object();
            var mic = new LoopbackCapture(CaptureKind.Microphone, null, 0, rate);
            mic.OnDataTimed = (d, n, qpc) =>
            {
                lock (rlk)
                {
                    if (micFirst < 0) micFirst = qpc;
                    int take = Math.Min(n, rec.Length - recLen);
                    for (int i = 0; i < take; i++) rec[recLen + i] = 0.5f * (d[2 * i] + d[2 * i + 1]);
                    recLen += take;
                }
            };
            OutputStream o = null;
            try
            {
                mic.Start();
                if (!mic.Running) { res.Problem = "The microphone couldn't be opened (" + (mic.Error ?? "unknown") + "). Check Windows' microphone privacy settings."; return res; }
                o = new OutputStream(outputId, rate, 0, 1f);
                o.Start();
                if (!o.Running) { res.Problem = "This output couldn't be opened: " + (o.Error ?? "unknown"); return res; }
                Thread.Sleep(400);   // let the mic settle
                // push the train in 50 ms chunks, noting when each click will start playing (QPC, 100 ns)
                var clickAt = new long[offsets.Length];
                int chunk = rate / 20, pos = 0, next = 0;
                var buf = new float[chunk * 2];
                while (pos < train.Length && !cancelled())
                {
                    while (o.QueuedFrames > chunk * 2) Thread.Sleep(5);
                    int n = Math.Min(chunk, train.Length - pos);
                    for (int i = 0; i < n; i++) { buf[2 * i] = train[pos + i]; buf[2 * i + 1] = train[pos + i]; }
                    long now = Qpc100ns();
                    int queued = o.QueuedFrames;
                    if (queued == 0) queued = rate * 15 / 1000;   // first push primes with the 15 ms cushion
                    o.Push(buf, n);
                    while (next < offsets.Length && offsets[next] < pos + n)
                    {
                        clickAt[next] = now + (long)((queued + offsets[next] - pos) * 1e7 / rate);
                        next++;
                    }
                    pos += n;
                }
                if (cancelled()) { res.Problem = "Cancelled."; return res; }
                Thread.Sleep(Calibration.MaxLatencyMs + 300);
                mic.Stop();
                float[] snap;
                int len;
                long first;
                lock (rlk) { snap = rec; len = recLen; first = micFirst; }
                if (first < 0 || len < rate) { res.Problem = "The microphone didn't deliver any sound."; return res; }
                var starts = new int[offsets.Length];
                for (int i = 0; i < offsets.Length; i++) starts[i] = (int)Math.Round((clickAt[i] - first) * rate / 1e7);
                return Calibration.Measure(snap, len, rate, starts);
            }
            catch (Exception ex)
            {
                res.Problem = "Calibration failed: " + ex.Message;
                return res;
            }
            finally
            {
                mic.Stop();
                if (o != null) o.Stop();
            }
        }

        // The same clock WASAPI uses for capture timestamps.
        static long Qpc100ns()
        {
            long t = Stopwatch.GetTimestamp();
            return (long)(t * (1e7 / Stopwatch.Frequency));
        }
    }
}
