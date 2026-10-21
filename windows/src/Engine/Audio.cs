// Dual Earbuds Pro - audio engine.
// Captures what a Windows output device is playing (WASAPI loopback) and plays the same sound on
// other output devices, each with its own delay and volume, and hands every packet to the phone link.
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Threading;

namespace DualEarbuds
{
    // Stereo float frames, single-producer/single-consumer use under the owner's lock.
    public class Ring
    {
        readonly float[] buf;
        readonly int cap;
        int rd, cnt;

        public Ring(int frames) { cap = frames; buf = new float[frames * 2]; }
        public int Count { get { return cnt; } }
        public int Capacity { get { return cap; } }

        public void Write(float[] src, int frames)
        {
            if (frames <= 0) return;
            int srcOffset = 0;
            if (frames > cap) { srcOffset = frames - cap; frames = cap; }   // keep the newest audio
            int overflow = cnt + frames - cap;
            if (overflow > 0) Discard(overflow);
            int wr = (rd + cnt) % cap;
            int first = Math.Min(frames, cap - wr);
            Array.Copy(src, srcOffset * 2, buf, wr * 2, first * 2);
            if (frames > first) Array.Copy(src, (srcOffset + first) * 2, buf, 0, (frames - first) * 2);
            cnt += frames;
        }

        public int Read(float[] dst, int dstFrame, int frames)
        {
            int n = Math.Min(frames, cnt);
            if (n <= 0) return 0;
            int first = Math.Min(n, cap - rd);
            Array.Copy(buf, rd * 2, dst, dstFrame * 2, first * 2);
            if (n > first) Array.Copy(buf, 0, dst, (dstFrame + first) * 2, (n - first) * 2);
            rd = (rd + n) % cap;
            cnt -= n;
            return n;
        }

        public void Discard(int frames)
        {
            int n = Math.Min(frames, cnt);
            if (n <= 0) return;
            rd = (rd + n) % cap;
            cnt -= n;
        }
    }

    // One output device. Sound is pushed in from the capture thread and pulled by this thread.
    public class OutputStream
    {
        public readonly string DeviceId;
        public volatile float Gain;
        public volatile string Error;
        public volatile bool Running;
        public volatile int LastHResult;
        public volatile OutputEffects Effects;   // EQ / hearing profile / dialogue boost, or null
        // Listener sync mode: the queue length is steered from outside (Adjust), so the built-in drift
        // limit doesn't throw away audio the clock sync wants to keep.
        public volatile bool SyncMode;
        long sourceFrames;
        float peak;

        readonly int rate, cushion, driftLimit;
        readonly Ring ring;
        readonly object lk = new object();
        int target, pendingSilence, delayMs, bufferMs;
        int underruns;
        bool primed;
        Thread thread;
        volatile bool stopFlag;
        readonly ManualResetEvent started = new ManualResetEvent(false);

        public OutputStream(string id, int rate, int delayMs, float gain)
        {
            DeviceId = id;
            this.rate = rate;
            Gain = gain;
            ring = new Ring(rate * 3);
            cushion = rate * 15 / 1000;
            driftLimit = rate * 80 / 1000;
            this.delayMs = Math.Max(0, delayMs);
            target = cushion + this.delayMs * rate / 1000;
        }

        public int Rate { get { return rate; } }

        // Times the device ran dry while playing (network listening counts these; mirroring ignores them).
        public int Underruns { get { return Thread.VolatileRead(ref underruns); } }

        public long SourceFrames { get { return Interlocked.Read(ref sourceFrames); } }
        public float TakePeak() { float p = peak; peak = 0; return p; }

        public void Start()
        {
            thread = new Thread(Run);
            thread.IsBackground = true;
            thread.Name = "Output";
            thread.Start();
            started.WaitOne(4000);
        }

        public void Stop()
        {
            stopFlag = true;
            if (thread != null) thread.Join(1500);
        }

        public void SetDelayMs(int ms)
        {
            lock (lk)
            {
                delayMs = Math.Max(0, ms);
                Retarget();
            }
        }

        // Extra jitter buffer for network listening (on top of the 15 ms cushion and the manual delay).
        public void SetBufferMs(int ms)
        {
            lock (lk)
            {
                bufferMs = Math.Max(0, ms);
                Retarget();
            }
        }

        // Caller holds lk.
        void Retarget()
        {
            int nt = cushion + (delayMs + bufferMs) * rate / 1000;
            int diff = nt - target;
            target = nt;
            if (!primed) return;
            if (diff > 0) pendingSilence += diff;
            else
            {
                int d = -diff;
                int fromSil = Math.Min(d, pendingSilence);
                pendingSilence -= fromSil;
                ring.Discard(d - fromSil);
            }
        }

        public void Push(float[] data, int frames)
        {
            if (!Running) return;
            lock (lk)
            {
                if (!primed) { pendingSilence = target; primed = true; }
                ring.Write(data, frames);
            }
        }

        // Public for unit tests: fills dst with up to 'want' frames.
        public int Fill(float[] dst, int want, bool deviceEmpty)
        {
            lock (lk)
            {
                int excess = SyncMode ? 0 : pendingSilence + ring.Count - (target + driftLimit);
                if (excess > 0)
                {
                    int fromSil = Math.Min(excess, pendingSilence);
                    pendingSilence -= fromSil;
                    ring.Discard(excess - fromSil);
                }
                int w = 0;
                int sil = Math.Min(want, pendingSilence);
                if (sil > 0) { Array.Clear(dst, 0, sil * 2); pendingSilence -= sil; w = sil; }
                int r = ring.Read(dst, w, want - w);
                if (r > 0)
                {
                    float g = Gain;
                    float pk = peak;
                    for (int i = w * 2; i < (w + r) * 2; i++)
                    {
                        float v = dst[i] * g;
                        dst[i] = v;
                        float a = v < 0 ? -v : v;
                        if (a > pk) pk = a;
                    }
                    peak = pk;
                    w += r;
                    Interlocked.Add(ref sourceFrames, r);
                }
                var fx = Effects;
                if (fx != null && w > 0) fx.Process(dst, 0, w, rate);
                if (w == 0 && deviceEmpty)
                {
                    if (primed) Interlocked.Increment(ref underruns);
                    primed = false;
                }
                return w;
            }
        }

        // Frames waiting to be played (silence still to insert + sound in the ring), excluding the device's own buffer.
        public int QueuedFrames { get { lock (lk) return pendingSilence + ring.Count; } }

        // frames > 0 inserts that much silence at the end of the queue's head; < 0 drops that much (silence first).
        public void Adjust(int frames)
        {
            lock (lk)
            {
                if (frames > 0) { pendingSilence += frames; return; }
                int d = -frames;
                int fromSil = Math.Min(d, pendingSilence);
                pendingSilence -= fromSil;
                ring.Discard(d - fromSil);
            }
        }

        // For unit tests that drive Fill() without a real device.
        public void MarkRunningForTest() { Running = true; }

        void Run()
        {
            IMMDeviceEnumerator en = null;
            IAudioClient ac = null;
            IAudioRenderClient rc = null;
            IntPtr fmt = IntPtr.Zero;
            try
            {
                en = Wasapi.NewEnumerator();
                string rid;
                ac = Wasapi.OpenClient(en, DeviceId, out rid);
                fmt = Wasapi.MakeFloatStereo(rate);
                Wasapi.Check(ac.Initialize(0, Wasapi.FLAG_AUTOCONVERT | Wasapi.FLAG_SRC_QUALITY, 400000, 0, fmt, IntPtr.Zero), "Set up output");
                uint bufFrames;
                Wasapi.Check(ac.GetBufferSize(out bufFrames), "Read buffer size");
                Guid iid = Wasapi.IID_Render;
                object o;
                Wasapi.Check(ac.GetService(ref iid, out o), "Open renderer");
                rc = (IAudioRenderClient)o;
                Wasapi.Check(ac.Start(), "Start output");
                Error = null;
                Running = true;
                started.Set();
                float[] tmp = new float[bufFrames * 2];
                while (!stopFlag)
                {
                    Thread.Sleep(3);
                    uint pad;
                    Wasapi.Check(ac.GetCurrentPadding(out pad), "Check output");
                    int avail = (int)bufFrames - (int)pad;
                    if (avail <= 0) continue;
                    int n = Fill(tmp, avail, pad == 0);
                    if (n == 0) continue;
                    IntPtr p;
                    Wasapi.Check(rc.GetBuffer((uint)n, out p), "Write output");
                    Marshal.Copy(tmp, 0, p, n * 2);
                    Wasapi.Check(rc.ReleaseBuffer((uint)n, 0), "Write output");
                }
                ac.Stop();
            }
            catch (Exception ex)
            {
                LastHResult = ex.HResult;
                Error = Wasapi.Friendly(ex);
                Log.Warn("Output " + DeviceId + ": " + ex.Message);
            }
            finally
            {
                Running = false;
                started.Set();
                Wasapi.Release(rc);
                Wasapi.Release(ac);
                Wasapi.Release(en);
                if (fmt != IntPtr.Zero) Marshal.FreeCoTaskMem(fmt);
            }
        }
    }

    // The sound source: loopback capture of one output device.
    public class LoopbackCapture
    {
        readonly string requestedId;
        public volatile string DeviceId;
        public volatile int Rate;
        public volatile string Error;
        public volatile bool Running;
        public Action<float[], int> OnData;

        float peak;
        Thread thread;
        volatile bool stopFlag;
        readonly ManualResetEvent ready = new ManualResetEvent(false);

        public LoopbackCapture(string id) { requestedId = id; }

        public float TakePeak() { float p = peak; peak = 0; return p; }

        public void Start()
        {
            thread = new Thread(Run);
            thread.IsBackground = true;
            thread.Name = "Capture";
            thread.Start();
            ready.WaitOne(5000);
            if (!Running && Error == null) Error = "Timed out opening the sound source";
        }

        public void Stop()
        {
            stopFlag = true;
            if (thread != null) thread.Join(1500);
        }

        void Run()
        {
            IMMDeviceEnumerator en = null;
            IAudioClient ac = null;
            IAudioCaptureClient cc = null;
            try
            {
                en = Wasapi.NewEnumerator();
                string rid;
                ac = Wasapi.OpenClient(en, requestedId, out rid);
                DeviceId = rid;
                IntPtr mf;
                Wasapi.Check(ac.GetMixFormat(out mf), "Read sound format");
                AudioFormat f;
                try
                {
                    f = Wasapi.ReadFormat(mf);
                    Wasapi.Check(ac.Initialize(0, Wasapi.FLAG_LOOPBACK, 2000000, 0, mf, IntPtr.Zero), "Set up capture");
                }
                finally { Marshal.FreeCoTaskMem(mf); }
                bool ok = f.IsFloat ? f.Bits == 32 : (f.Bits == 16 || f.Bits == 24 || f.Bits == 32);
                if (!ok) throw new Exception("Unsupported sound format");
                Rate = f.Rate;
                Guid iid = Wasapi.IID_Capture;
                object o;
                Wasapi.Check(ac.GetService(ref iid, out o), "Open capture");
                cc = (IAudioCaptureClient)o;
                Wasapi.Check(ac.Start(), "Start capture");
                Running = true;
                ready.Set();

                float[] outBuf = new float[0];
                byte[] raw = new byte[0];
                while (!stopFlag)
                {
                    Thread.Sleep(5);
                    uint packet;
                    Wasapi.Check(cc.GetNextPacketSize(out packet), "Read sound");
                    while (packet > 0 && !stopFlag)
                    {
                        IntPtr data;
                        uint frames, flags;
                        ulong p1, p2;
                        Wasapi.Check(cc.GetBuffer(out data, out frames, out flags, out p1, out p2), "Read sound");
                        int fr = (int)frames;
                        if (outBuf.Length < fr * 2) outBuf = new float[fr * 2];
                        if ((flags & Wasapi.BUFFER_SILENT) != 0) Array.Clear(outBuf, 0, fr * 2);
                        else
                        {
                            int bytes = fr * f.BlockAlign;
                            if (raw.Length < bytes) raw = new byte[bytes];
                            Marshal.Copy(data, raw, 0, bytes);
                            SampleConvert.ToStereoFloat(raw, fr, f, outBuf);
                        }
                        cc.ReleaseBuffer(frames);

                        float pk = peak;
                        for (int i = 0; i < fr * 2; i++) { float a = Math.Abs(outBuf[i]); if (a > pk) pk = a; }
                        peak = pk;

                        var cb = OnData;
                        if (cb != null) cb(outBuf, fr);
                        Wasapi.Check(cc.GetNextPacketSize(out packet), "Read sound");
                    }
                }
                ac.Stop();
            }
            catch (Exception ex)
            {
                Error = Wasapi.Friendly(ex);
                Log.Warn("Capture: " + ex.Message);
            }
            finally
            {
                Running = false;
                ready.Set();
                Wasapi.Release(cc);
                Wasapi.Release(ac);
                Wasapi.Release(en);
            }
        }
    }

    public static class SampleConvert
    {
        // Converts interleaved PCM/float frames of any channel count to stereo float.
        // Surround (5.1, 7.1, ...) is downmixed so the centre channel (dialogue) and surrounds are kept.
        public static void ToStereoFloat(byte[] raw, int frames, AudioFormat f, float[] dst)
        {
            int bps = f.Bits / 8;
            if (f.Channels <= 2)
            {
                for (int i = 0; i < frames; i++)
                {
                    int b = i * f.BlockAlign;
                    float l = Sample(raw, b, f);
                    float r = f.Channels > 1 ? Sample(raw, b + bps, f) : l;
                    dst[i * 2] = l;
                    dst[i * 2 + 1] = r;
                }
                return;
            }
            float[] gl, gr;
            DownmixGains(f.Channels, f.ChannelMask, out gl, out gr);
            for (int i = 0; i < frames; i++)
            {
                int b = i * f.BlockAlign;
                float l = 0, r = 0;
                for (int c = 0; c < f.Channels; c++)
                {
                    float x = Sample(raw, b + c * bps, f);
                    l += x * gl[c];
                    r += x * gr[c];
                }
                dst[i * 2] = l > 1f ? 1f : (l < -1f ? -1f : l);
                dst[i * 2 + 1] = r > 1f ? 1f : (r < -1f ? -1f : r);
            }
        }

        // Left/right gain for each channel, from the speaker mask (or the usual layout for that channel count).
        public static void DownmixGains(int channels, uint mask, out float[] gl, out float[] gr)
        {
            if (mask == 0 || CountBits(mask) != channels)
            {
                switch (channels)
                {
                    case 3: mask = 0x7; break;      // L R C
                    case 4: mask = 0x33; break;     // quad
                    case 5: mask = 0x37; break;     // L R C BL BR
                    case 6: mask = 0x3F; break;     // 5.1
                    case 7: mask = 0x13F; break;    // 6.1
                    case 8: mask = 0x63F; break;    // 7.1
                    default: mask = 0; break;
                }
            }
            gl = new float[channels];
            gr = new float[channels];
            const float h = 0.7071f;
            int idx = 0;
            for (int bit = 0; bit < 32 && idx < channels; bit++)
            {
                uint sp = 1u << bit;
                if ((mask & sp) == 0) continue;
                float l, r;
                switch (sp)
                {
                    case 0x1: l = 1; r = 0; break;        // front left
                    case 0x2: l = 0; r = 1; break;        // front right
                    case 0x4: l = h; r = h; break;        // centre
                    case 0x8: l = 0; r = 0; break;        // LFE (earbuds can't use it)
                    case 0x10: l = h; r = 0; break;       // back left
                    case 0x20: l = 0; r = h; break;       // back right
                    case 0x40: l = 1; r = 0; break;       // front left of centre
                    case 0x80: l = 0; r = 1; break;       // front right of centre
                    case 0x100: l = 0.5f; r = 0.5f; break; // back centre
                    case 0x200: l = h; r = 0; break;      // side left
                    case 0x400: l = 0; r = h; break;      // side right
                    default: l = 0.5f; r = 0.5f; break;   // height and others
                }
                gl[idx] = l; gr[idx] = r; idx++;
            }
            for (; idx < channels; idx++) { gl[idx] = 0.5f; gr[idx] = 0.5f; }   // unknown layout: blend in
            // keep loudness close to stereo while avoiding most clipping
            float sl = 0, sr = 0;
            for (int c = 0; c < channels; c++) { sl += gl[c]; sr += gr[c]; }
            float scale = 1f / Math.Max(1f, 0.5f * Math.Max(sl, sr));
            for (int c = 0; c < channels; c++) { gl[c] *= scale; gr[c] *= scale; }
        }

        static int CountBits(uint v) { int n = 0; while (v != 0) { n += (int)(v & 1); v >>= 1; } return n; }

        public static float Sample(byte[] raw, int o, AudioFormat f)
        {
            if (f.IsFloat) return BitConverter.ToSingle(raw, o);
            switch (f.Bits)
            {
                case 16: return BitConverter.ToInt16(raw, o) / 32768f;
                case 24: return (raw[o] | (raw[o + 1] << 8) | ((sbyte)raw[o + 2] << 16)) / 8388608f;
                default: return BitConverter.ToInt32(raw, o) / 2147483648f;
            }
        }

        // Float stereo -> little-endian signed 16-bit, written into dst at offset.
        public static void ToPcm16(float[] src, int frames, byte[] dst, int offset)
        {
            ToPcm16(src, 0, frames, dst, offset);
        }

        public static void ToPcm16(float[] src, int startFrame, int frames, byte[] dst, int offset)
        {
            int o = offset;
            int end = (startFrame + frames) * 2;
            for (int i = startFrame * 2; i < end; i++)
            {
                float s = src[i];
                if (s > 1f) s = 1f; else if (s < -1f) s = -1f;
                short v = (short)(s * 32767f);
                dst[o++] = (byte)v;
                dst[o++] = (byte)(v >> 8);
            }
        }
    }

    // One source, many outputs, plus an optional phone link.
    public class Engine
    {
        readonly object lk = new object();
        readonly List<OutputStream> outs = new List<OutputStream>();
        LoopbackCapture cap;

        public volatile PhoneLink Phone;

        public bool Running { get { return cap != null; } }
        public string SourceDeviceId { get { var c = cap; return c == null ? null : c.DeviceId; } }
        public int Rate { get { var c = cap; return c == null ? 0 : c.Rate; } }
        public float TakePeak() { var c = cap; return c == null ? 0f : c.TakePeak(); }

        public string CaptureError
        {
            get
            {
                var c = cap;
                if (c == null || c.Running) return null;
                return c.Error ?? "The sound source stopped";
            }
        }

        // sourceId == null: follow the Windows default output. Returns an error message or null.
        public string Start(string sourceId)
        {
            Stop();
            var c = new LoopbackCapture(sourceId);
            c.OnData = Dispatch;
            c.Start();
            if (!c.Running)
            {
                string e = c.Error ?? "Could not open the sound source";
                c.Stop();
                return e;
            }
            cap = c;
            var ph = Phone;
            if (ph != null) ph.SetMirroring(true, c.Rate);
            Log.Info("Mirroring started, rate " + c.Rate);
            return null;
        }

        void Dispatch(float[] data, int frames)
        {
            OutputStream[] arr;
            lock (lk) arr = outs.ToArray();
            foreach (var o in arr) o.Push(data, frames);
            var ph = Phone;
            var c = cap;
            if (ph != null && c != null) ph.Broadcast(data, frames, c.Rate);
        }

        public OutputStream AddOutput(string id, int delayMs, float gain)
        {
            var c = cap;
            if (c == null) return null;
            RemoveOutput(id);
            var o = new OutputStream(id, c.Rate, delayMs, gain);
            o.Start();
            lock (lk) outs.Add(o);
            return o;
        }

        public void RemoveOutput(string id)
        {
            OutputStream o;
            lock (lk)
            {
                o = outs.Find(x => x.DeviceId == id);
                if (o != null) outs.Remove(o);
            }
            if (o != null) o.Stop();
        }

        public OutputStream Get(string id)
        {
            lock (lk) return outs.Find(x => x.DeviceId == id);
        }

        public void Stop()
        {
            var c = cap;
            cap = null;
            if (c != null) c.Stop();
            OutputStream[] arr;
            lock (lk) { arr = outs.ToArray(); outs.Clear(); }
            foreach (var o in arr) o.Stop();
            var ph = Phone;
            if (ph != null && c != null) ph.SetMirroring(false, 0);
            if (c != null) Log.Info("Mirroring stopped");
        }
    }

    // Short test tone on one device.
    public static class Beeper
    {
        public static float[] Tone(int rate, double seconds, double hz, float volume)
        {
            int n = (int)(rate * seconds);
            float[] b = new float[n * 2];
            for (int i = 0; i < n; i++)
            {
                float env = Math.Min(1f, Math.Min(i / (rate * 0.01f), (n - i) / (rate * 0.05f)));
                float s = (float)Math.Sin(2 * Math.PI * hz * i / rate) * volume * env;
                b[i * 2] = s;
                b[i * 2 + 1] = s;
            }
            return b;
        }

        public static void Play(string id)
        {
            var t = new Thread(() =>
            {
                var o = new OutputStream(id, 48000, 0, 1f);
                o.Start();
                if (!o.Running) return;
                float[] b = Tone(48000, 0.35, 660, 0.25f);
                o.Push(b, b.Length / 2);
                Thread.Sleep(800);
                o.Stop();
            });
            t.IsBackground = true;
            t.Start();
        }
    }
}
