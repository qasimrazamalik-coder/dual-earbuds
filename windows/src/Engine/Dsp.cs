// Dual Earbuds - small real-time DSP blocks: biquads, the EQ chain (presets + hearing profile),
// dialogue boost, and the gain helpers they share. Everything here is allocation-free once built:
// coefficients are recomputed in place, buffers are preallocated, Process() never allocates.
using System;

namespace DualEarbuds
{
    public static class Gain
    {
        public static double DbToLin(double db) { return Math.Pow(10.0, db / 20.0); }
        public static double LinToDb(double lin) { return lin <= 1e-9 ? -180.0 : 20.0 * Math.Log10(lin); }

        // Soft limiter: transparent below 0.89 (-1 dBFS), smooth knee up to a hard ceiling of 1.0.
        public static float SoftClip(float x)
        {
            const float knee = 0.89f;
            float a = x < 0 ? -x : x;
            if (a <= knee) return x;
            float over = (a - knee) / (1f - knee);
            float y = knee + (1f - knee) * (over / (1f + over));   // approaches 1.0, never exceeds it
            return x < 0 ? -y : y;
        }
    }

    // One second-order section (RBJ cookbook), transposed direct form II, separate state per channel.
    public class Biquad
    {
        double b0 = 1, b1, b2, a1, a2;
        double z1L, z2L, z1R, z2R;

        public enum Kind { Peak, LowShelf, HighShelf, BandPass, HighPass }

        public void Set(Kind kind, double rate, double freq, double q, double gainDb)
        {
            freq = Math.Max(10, Math.Min(rate * 0.45, freq));
            double A = Math.Pow(10, gainDb / 40.0);
            double w = 2 * Math.PI * freq / rate;
            double cw = Math.Cos(w), sw = Math.Sin(w);
            double alpha = sw / (2 * q);
            double nb0, nb1, nb2, na0, na1, na2;
            switch (kind)
            {
                case Kind.LowShelf:
                {
                    double s = 2 * Math.Sqrt(A) * alpha;
                    nb0 = A * ((A + 1) - (A - 1) * cw + s);
                    nb1 = 2 * A * ((A - 1) - (A + 1) * cw);
                    nb2 = A * ((A + 1) - (A - 1) * cw - s);
                    na0 = (A + 1) + (A - 1) * cw + s;
                    na1 = -2 * ((A - 1) + (A + 1) * cw);
                    na2 = (A + 1) + (A - 1) * cw - s;
                    break;
                }
                case Kind.HighShelf:
                {
                    double s = 2 * Math.Sqrt(A) * alpha;
                    nb0 = A * ((A + 1) + (A - 1) * cw + s);
                    nb1 = -2 * A * ((A - 1) + (A + 1) * cw);
                    nb2 = A * ((A + 1) + (A - 1) * cw - s);
                    na0 = (A + 1) - (A - 1) * cw + s;
                    na1 = 2 * ((A - 1) - (A + 1) * cw);
                    na2 = (A + 1) - (A - 1) * cw - s;
                    break;
                }
                case Kind.BandPass:   // constant 0 dB peak gain
                    nb0 = alpha; nb1 = 0; nb2 = -alpha;
                    na0 = 1 + alpha; na1 = -2 * cw; na2 = 1 - alpha;
                    break;
                case Kind.HighPass:
                    nb0 = (1 + cw) / 2; nb1 = -(1 + cw); nb2 = (1 + cw) / 2;
                    na0 = 1 + alpha; na1 = -2 * cw; na2 = 1 - alpha;
                    break;
                default:              // peaking
                    nb0 = 1 + alpha * A; nb1 = -2 * cw; nb2 = 1 - alpha * A;
                    na0 = 1 + alpha / A; na1 = -2 * cw; na2 = 1 - alpha / A;
                    break;
            }
            b0 = nb0 / na0; b1 = nb1 / na0; b2 = nb2 / na0; a1 = na1 / na0; a2 = na2 / na0;
        }

        public void SetIdentity() { b0 = 1; b1 = b2 = a1 = a2 = 0; }
        public void Reset() { z1L = z2L = z1R = z2R = 0; }

        public bool IsIdentity { get { return b0 == 1 && b1 == 0 && b2 == 0 && a1 == 0 && a2 == 0; } }

        public float TickL(float x)
        {
            double y = b0 * x + z1L;
            z1L = b1 * x - a1 * y + z2L;
            z2L = b2 * x - a2 * y;
            return (float)y;
        }

        public float TickR(float x)
        {
            double y = b0 * x + z1R;
            z1R = b1 * x - a1 * y + z2R;
            z2R = b2 * x - a2 * y;
            return (float)y;
        }

        // Magnitude response in dB at freq (used by tests and the UI curve).
        public double ResponseDb(double rate, double freq)
        {
            double w = 2 * Math.PI * freq / rate;
            double cr = Math.Cos(w), ci = -Math.Sin(w), c2r = Math.Cos(2 * w), c2i = -Math.Sin(2 * w);
            double nr = b0 + b1 * cr + b2 * c2r, ni = b1 * ci + b2 * c2i;
            double dr = 1 + a1 * cr + a2 * c2r, di = a1 * ci + a2 * c2i;
            double mag = Math.Sqrt((nr * nr + ni * ni) / (dr * dr + di * di));
            return 20 * Math.Log10(Math.Max(1e-12, mag));
        }
    }

    // Listener-local EQ presets (docs/protocol.md "Equalizer"): flat | bass | voice | movie.
    public static class EqPresets
    {
        public static readonly string[] Names = { "flat", "bass", "voice", "movie" };
        public static readonly string[] Labels = { "Flat", "Bass", "Voice", "Movie" };

        // Five bands: low shelf 100 Hz, peaks 250 Hz / 1 kHz / 3 kHz, high shelf 8 kHz.
        public static readonly double[] Freqs = { 100, 250, 1000, 3000, 8000 };
        static readonly double[][] Gains =
        {
            new double[] { 0, 0, 0, 0, 0 },
            new double[] { 6, 2, 0, 0, 1 },
            new double[] { -3, -1, 2, 4, 1 },
            new double[] { 4, 0, 1, 2.5, 2 },
        };

        public static int IndexOf(string name)
        {
            for (int i = 0; i < Names.Length; i++) if (Names[i] == name) return i;
            return -1;
        }

        public static bool IsValid(string name) { return IndexOf(name) >= 0; }

        public static double[] GainsFor(string name)
        {
            int i = IndexOf(name);
            return Gains[i < 0 ? 0 : i];
        }
    }

    // Hearing profile: per-ear gains at the seven test frequencies (docs/smart-features.md #6).
    public class HearingProfile
    {
        public static readonly double[] Freqs = { 250, 500, 1000, 2000, 4000, 6000, 8000 };
        public const double MaxBoostDb = 9;
        public string Name = "Me";
        public double[] LeftDb = new double[7];    // EQ gain per frequency (0..MaxBoostDb)
        public double[] RightDb = new double[7];

        public bool IsFlat
        {
            get
            {
                for (int i = 0; i < 7; i++) if (LeftDb[i] > 0.05 || RightDb[i] > 0.05) return false;
                return true;
            }
        }

        // "name|l0,l1,..|r0,r1,.." with invariant numbers; null when it doesn't parse.
        public string Serialize()
        {
            var ci = System.Globalization.CultureInfo.InvariantCulture;
            var l = new string[7]; var r = new string[7];
            for (int i = 0; i < 7; i++) { l[i] = LeftDb[i].ToString("0.#", ci); r[i] = RightDb[i].ToString("0.#", ci); }
            return Name.Replace("|", " ").Replace("\t", " ") + "|" + string.Join(",", l) + "|" + string.Join(",", r);
        }

        public static HearingProfile Parse(string s)
        {
            if (s == null) return null;
            var p = s.Split('|');
            if (p.Length != 3 || p[0].Trim().Length == 0) return null;
            var h = new HearingProfile { Name = p[0].Trim() };
            if (!ParseList(p[1], h.LeftDb) || !ParseList(p[2], h.RightDb)) return null;
            return h;
        }

        static bool ParseList(string s, double[] into)
        {
            var parts = s.Split(',');
            if (parts.Length != 7) return false;
            for (int i = 0; i < 7; i++)
            {
                double v;
                if (!double.TryParse(parts[i], System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out v)) return false;
                into[i] = Math.Max(0, Math.Min(MaxBoostDb, v));
            }
            return true;
        }
    }

    // The per-output EQ: 5 preset bands (both ears) + 7 hearing bands (per ear) + safety pre-gain + soft clip.
    // Changing the preset glides the band gains over ~300 ms (recomputed once per block), so switches never click.
    public class EqChain
    {
        readonly double rate;
        readonly Biquad[] preset = new Biquad[5];
        readonly Biquad[] hearL = new Biquad[7], hearR = new Biquad[7];
        readonly double[] cur = new double[5], target = new double[5];
        readonly double[] hl = new double[7], hr = new double[7];
        double pre = 1.0, preTarget = 1.0;
        bool hearingOn;
        int glideBlocks;
        public const int GlideMs = 300;

        public EqChain(double sampleRate)
        {
            rate = sampleRate;
            for (int i = 0; i < 5; i++) { preset[i] = new Biquad(); preset[i].SetIdentity(); }
            for (int i = 0; i < 7; i++) { hearL[i] = new Biquad(); hearR[i] = new Biquad(); hearL[i].SetIdentity(); hearR[i].SetIdentity(); }
        }

        public string Preset { get; private set; }

        public void SetPreset(string name, bool glide)
        {
            if (!EqPresets.IsValid(name)) name = "flat";
            Preset = name;
            var g = EqPresets.GainsFor(name);
            for (int i = 0; i < 5; i++) target[i] = g[i];
            if (glide) glideBlocks = Math.Max(1, GlideMs / 10);
            else { for (int i = 0; i < 5; i++) cur[i] = target[i]; glideBlocks = 0; ApplyPreset(); }
            UpdatePreGain();
        }

        public void SetHearing(HearingProfile p)
        {
            hearingOn = p != null && !p.IsFlat;
            for (int i = 0; i < 7; i++)
            {
                hl[i] = hearingOn ? p.LeftDb[i] : 0;
                hr[i] = hearingOn ? p.RightDb[i] : 0;
                if (hearingOn)
                {
                    hearL[i].Set(Biquad.Kind.Peak, rate, HearingProfile.Freqs[i], 1.1, hl[i]);
                    hearR[i].Set(Biquad.Kind.Peak, rate, HearingProfile.Freqs[i], 1.1, hr[i]);
                }
                else { hearL[i].SetIdentity(); hearR[i].SetIdentity(); }
            }
            UpdatePreGain();
        }

        // Headroom: the biggest boost is mostly taken back as pre-gain (keeps loud music out of the limiter).
        void UpdatePreGain()
        {
            double max = 0;
            for (int i = 0; i < 5; i++) max = Math.Max(max, target[i]);
            double h = 0;
            for (int i = 0; i < 7; i++) h = Math.Max(h, Math.Max(hl[i], hr[i]));
            preTarget = Gain.DbToLin(-0.75 * (max + h));
        }

        public double PreGain { get { return preTarget; } }
        public bool IsFlat
        {
            get
            {
                if (hearingOn || glideBlocks > 0) return false;
                for (int i = 0; i < 5; i++) if (Math.Abs(cur[i]) > 0.01) return false;
                return true;
            }
        }

        void ApplyPreset()
        {
            for (int i = 0; i < 5; i++)
            {
                if (Math.Abs(cur[i]) < 0.01) { preset[i].SetIdentity(); continue; }
                var kind = i == 0 ? Biquad.Kind.LowShelf : i == 4 ? Biquad.Kind.HighShelf : Biquad.Kind.Peak;
                preset[i].Set(kind, rate, EqPresets.Freqs[i], i == 0 || i == 4 ? 0.7 : 1.0, cur[i]);
            }
        }

        public double BandDb(int band) { return cur[band]; }

        // In place, stereo interleaved.
        public void Process(float[] buf, int offsetFrames, int frames)
        {
            if (glideBlocks > 0)
            {
                for (int i = 0; i < 5; i++) cur[i] += (target[i] - cur[i]) / glideBlocks;
                glideBlocks--;
                ApplyPreset();
            }
            if (IsFlat && Math.Abs(pre - 1.0) < 1e-4 && Math.Abs(preTarget - 1.0) < 1e-4) return;
            double p0 = pre, p1 = preTarget, step = frames > 0 ? (p1 - p0) / frames : 0;
            int end = (offsetFrames + frames) * 2;
            double g = p0;
            for (int i = offsetFrames * 2; i < end; i += 2)
            {
                g += step;
                float l = (float)(buf[i] * g), r = (float)(buf[i + 1] * g);
                for (int b = 0; b < 5; b++) { var f = preset[b]; if (!f.IsIdentity) { l = f.TickL(l); r = f.TickR(r); } }
                if (hearingOn)
                    for (int b = 0; b < 7; b++) { l = hearL[b].TickL(l); r = hearR[b].TickR(r); }
                buf[i] = Gain.SoftClip(l);
                buf[i + 1] = Gain.SoftClip(r);
            }
            pre = p1;
        }

        // Combined response (both preset and hearing, left ear) in dB, for tests and the settings curve.
        public double ResponseDb(double freq, bool left)
        {
            double db = Gain.LinToDb(preTarget);
            for (int b = 0; b < 5; b++) db += preset[b].ResponseDb(rate, freq);
            if (hearingOn) for (int b = 0; b < 7; b++) db += (left ? hearL[b] : hearR[b]).ResponseDb(rate, freq);
            return db;
        }
    }

    public enum DialogueMode { Off, Light, Strong, Night }

    // Simple voice-activity detector on the centre (mid) channel, 10 ms blocks:
    // speech = most of the energy is in the 300 Hz - 4 kHz band, the block is well above the noise floor,
    // and the level moves like syllables. A hangover keeps it on between words.
    public class Vad
    {
        readonly Biquad band = new Biquad();
        readonly Biquad low = new Biquad();
        double floor = 1e-5, envSlow, envFast;
        int hang;
        public bool Speech { get; private set; }
        public double LastBandRatio { get; private set; }

        public Vad(double rate)
        {
            band.Set(Biquad.Kind.BandPass, rate, 1100, 0.45, 0);   // ~300 Hz .. 4 kHz
            low.Set(Biquad.Kind.HighPass, rate, 60, 0.7, 0);
        }

        // mono block -> decision
        public bool Feed(float[] mono, int n)
        {
            double eAll = 0, eBand = 0;
            for (int i = 0; i < n; i++)
            {
                float x = low.TickL(mono[i]);
                float y = band.TickL(x);
                eAll += x * x;
                eBand += y * y;
            }
            eAll /= Math.Max(1, n);
            eBand /= Math.Max(1, n);
            double ratio = eAll > 1e-12 ? eBand / eAll : 0;
            LastBandRatio = ratio;
            // noise floor: falls fast, rises slowly
            floor = eAll < floor ? eAll : floor * 1.002 + 1e-12;
            envFast = envFast * 0.6 + eAll * 0.4;
            envSlow = envSlow * 0.97 + eAll * 0.03;
            bool loud = eAll > floor * 6 && eAll > 1e-6;
            bool active = loud && ratio > 0.45;
            if (active) hang = 25;              // 250 ms
            else if (hang > 0) hang--;
            Speech = active || hang > 0;
            return Speech;
        }
    }

    // Dialogue boost (docs/smart-features.md #3): mid/side split, boost the centre's 300 Hz-4 kHz band only
    // while the VAD hears speech; Strong also cuts the sides a little; Night adds a gentle compressor.
    public class DialogueBoost
    {
        readonly double rate;
        readonly Biquad voice = new Biquad();
        readonly Vad vad;
        readonly float[] mono;
        DialogueMode mode = DialogueMode.Off;
        double boost, comp = 1.0;   // current linear voice-band gain extra, compressor gain
        double env;

        public DialogueBoost(double sampleRate, int maxBlockFrames)
        {
            rate = sampleRate;
            voice.Set(Biquad.Kind.BandPass, rate, 1100, 0.45, 0);
            vad = new Vad(rate);
            mono = new float[Math.Max(1, maxBlockFrames)];
        }

        public DialogueMode Mode { get { return mode; } set { mode = value; } }
        public bool SpeechNow { get { return vad.Speech; } }

        public static double TargetBoostDb(DialogueMode m)
        {
            switch (m) { case DialogueMode.Light: return 4; case DialogueMode.Strong: return 7; case DialogueMode.Night: return 6; default: return 0; }
        }

        public static double SideGain(DialogueMode m) { return m == DialogueMode.Strong || m == DialogueMode.Night ? 0.8 : 1.0; }

        public void Process(float[] buf, int offsetFrames, int frames)
        {
            if (mode == DialogueMode.Off && boost < 1e-3) return;
            int done = 0;
            while (done < frames)
            {
                int n = Math.Min(mono.Length, frames - done);
                int o = (offsetFrames + done) * 2;
                for (int i = 0; i < n; i++) mono[i] = 0.5f * (buf[o + 2 * i] + buf[o + 2 * i + 1]);
                bool speech = vad.Feed(mono, n);
                double want = mode != DialogueMode.Off && speech ? Gain.DbToLin(TargetBoostDb(mode)) - 1.0 : 0.0;
                double side = mode == DialogueMode.Off ? 1.0 : SideGain(mode);
                double b0 = boost, b1 = boost + (want - boost) * (want > boost ? 0.35 : 0.08);   // fast attack, slow release
                for (int i = 0; i < n; i++)
                {
                    double t = (double)i / n;
                    double b = b0 + (b1 - b0) * t;
                    float l = buf[o + 2 * i], r = buf[o + 2 * i + 1];
                    float m = 0.5f * (l + r), s = 0.5f * (l - r);
                    float v = voice.TickL(m);
                    m += (float)(v * b);
                    s *= (float)side;
                    l = m + s; r = m - s;
                    if (mode == DialogueMode.Night)
                    {
                        // gentle levelling: 3:1 above -24 dBFS with ~5 ms attack / 200 ms release, plus soft clip
                        double a = Math.Max(Math.Abs(l), Math.Abs(r));
                        env = a > env ? env + (a - env) * 0.004 : env + (a - env) * 0.0001;
                        double thr = 0.063;
                        double g = env > thr ? Math.Pow(env / thr, 1.0 / 3.0 - 1.0) : 1.0;
                        comp = comp + (g - comp) * 0.01;
                        l = (float)(l * comp * 1.6); r = (float)(r * comp * 1.6);   // make-up gain
                    }
                    buf[o + 2 * i] = Gain.SoftClip(l);
                    buf[o + 2 * i + 1] = Gain.SoftClip(r);
                }
                boost = b1;
                done += n;
            }
        }
    }

    // P-square online quantile estimator (Jain & Chlamtac 1985): constant memory, no sorting.
    public class P2Quantile
    {
        readonly double p;
        readonly double[] q = new double[5], dn = new double[5], np = new double[5];
        readonly int[] n = new int[5];
        int count;

        public P2Quantile(double quantile)
        {
            p = quantile;
            dn[0] = 0; dn[1] = p / 2; dn[2] = p; dn[3] = (1 + p) / 2; dn[4] = 1;
        }

        public int Count { get { return count; } }

        public void Reset() { count = 0; }

        public void Add(double x)
        {
            if (count < 5)
            {
                q[count++] = x;
                if (count == 5)
                {
                    Array.Sort(q);
                    for (int i = 0; i < 5; i++) n[i] = i;
                    np[0] = 0; np[1] = 2 * p; np[2] = 4 * p; np[3] = 2 + 2 * p; np[4] = 4;
                }
                return;
            }
            count++;
            int k;
            if (x < q[0]) { q[0] = x; k = 0; }
            else if (x >= q[4]) { q[4] = x; k = 3; }
            else { k = 0; while (k < 3 && x >= q[k + 1]) k++; }
            for (int i = k + 1; i < 5; i++) n[i]++;
            for (int i = 0; i < 5; i++) np[i] += dn[i];
            for (int i = 1; i <= 3; i++)
            {
                double d = np[i] - n[i];
                if ((d >= 1 && n[i + 1] - n[i] > 1) || (d <= -1 && n[i - 1] - n[i] < -1))
                {
                    int s = d >= 0 ? 1 : -1;
                    double qp = Parabolic(i, s);
                    if (q[i - 1] < qp && qp < q[i + 1]) q[i] = qp;
                    else q[i] = q[i] + s * (q[i + s] - q[i]) / (n[i + s] - n[i]);
                    n[i] += s;
                }
            }
        }

        double Parabolic(int i, int s)
        {
            return q[i] + (double)s / (n[i + 1] - n[i - 1]) *
                   ((n[i] - n[i - 1] + s) * (q[i + 1] - q[i]) / (n[i + 1] - n[i]) +
                    (n[i + 1] - n[i] - s) * (q[i] - q[i - 1]) / (n[i] - n[i - 1]));
        }

        public double Value
        {
            get
            {
                if (count == 0) return 0;
                if (count < 5)
                {
                    // few samples: exact quantile of what we have (copy into a fixed scratch, no allocation)
                    double a0 = q[0], a1 = q[1], a2 = q[2], a3 = q[3];
                    double[] t = scratch;
                    t[0] = a0; t[1] = a1; t[2] = a2; t[3] = a3;
                    Array.Sort(t, 0, count);
                    int idx = (int)Math.Min(count - 1, Math.Round(p * (count - 1)));
                    return t[idx];
                }
                return q[2];
            }
        }
        readonly double[] scratch = new double[5];
    }

    public enum BufferMode { Smart, Steady, Manual }

    // Listener playback buffer (docs/protocol.md "Low-latency playback" + smart-features.md #4).
    // Steady: the protocol's adaptive rules. Smart: target = p99 inter-arrival jitter (last ~60 s) + 10 ms,
    // clamped to 30..300 ms; an underrun still adds 20 ms at once; shrinking is slow (5 ms per 5 s).
    // Manual: the user's fixed value. Time is passed in (ms) so it is unit-testable.
    public class JitterBuffer
    {
        public const int StartMs = 60, MinMs = 30, MaxMs = 300, UnderrunStepMs = 20, HoldMs = 10000,
                         CalmMs = 20000, ShrinkStepMs = 5, ShrinkEveryMs = 5000, PacketMs = 10, WindowMs = 60000;

        public BufferMode Mode = BufferMode.Smart;
        public int ManualMs = 80;
        int targetMs = StartMs;
        long lastUnderrun = long.MinValue / 2, lastShrink, lastArrival = long.MinValue, windowStart;
        P2Quantile cur = new P2Quantile(0.99), prev = new P2Quantile(0.99);

        public int TargetMs { get { return Mode == BufferMode.Manual ? Clamp(ManualMs) : targetMs; } }

        public static int Clamp(int ms) { return Math.Max(MinMs, Math.Min(MaxMs, ms)); }

        public double JitterP99
        {
            get { var e = prev.Count >= 20 ? prev : cur; return e.Count == 0 ? 0 : Math.Max(cur.Count >= 20 ? cur.Value : 0, e.Value); }
        }

        public void Reset(long now)
        {
            targetMs = StartMs;
            lastUnderrun = long.MinValue / 2;
            lastShrink = now;
            lastArrival = long.MinValue;
            windowStart = now;
            cur.Reset(); prev.Reset();
        }

        // One packet arrived at local time now (ms). Packets are sent every 10 ms, so jitter = |gap - 10|.
        public void OnPacket(long now)
        {
            if (lastArrival != long.MinValue)
            {
                double gap = now - lastArrival;
                if (gap >= 0 && gap < 2000) cur.Add(Math.Abs(gap - PacketMs));
            }
            lastArrival = now;
            if (now - windowStart >= WindowMs)
            {
                // two staggered estimators approximate a sliding 60 s window
                var t = prev; prev = cur; cur = t; cur.Reset();
                windowStart = now;
            }
        }

        public void OnUnderrun(long now)
        {
            targetMs = Clamp(targetMs + UnderrunStepMs);
            lastUnderrun = now;
            lastShrink = now;
        }

        // Call a few times a second.
        public void Tick(long now)
        {
            if (Mode == BufferMode.Manual) return;
            if (Mode == BufferMode.Smart)
            {
                int want = Clamp((int)Math.Ceiling(JitterP99) + 10);
                if (want > targetMs && now - lastUnderrun > 0) { targetMs = want; lastShrink = now; return; }
                if (targetMs > want && now - lastUnderrun >= HoldMs && now - lastShrink >= ShrinkEveryMs)
                {
                    targetMs = Math.Max(want, targetMs - ShrinkStepMs);
                    lastShrink = now;
                }
                return;
            }
            // Steady: after 20 s with no underruns, -5 ms per 5 s down to the minimum
            if (now - lastUnderrun >= CalmMs && now - lastShrink >= ShrinkEveryMs && targetMs > MinMs)
            {
                targetMs = Math.Max(MinMs, targetMs - ShrinkStepMs);
                lastShrink = now;
            }
        }
    }

    // Hearing test (smart-features.md #6): one simple up-down staircase per frequency and ear.
    // Start clearly audible, 10 dB steps down while heard; after the first miss 5 dB steps;
    // done after 4 reversals (threshold = mean of the reversal levels) or 14 answers.
    public class HearingStaircase
    {
        public const double StartDb = -30, FloorDb = -95, CeilingDb = -10;
        public double LevelDb { get; private set; }   // dBFS of the tone to play now
        public bool Done { get; private set; }
        public double ThresholdDb { get; private set; }
        int reversals, answers;
        double revSum;
        int lastDir;   // -1 going down (heard), +1 going up (missed)
        bool fine;

        public HearingStaircase() { LevelDb = StartDb; }

        public void Answer(bool heard)
        {
            if (Done) return;
            answers++;
            int dir = heard ? -1 : +1;
            if (lastDir != 0 && dir != lastDir) { reversals++; revSum += LevelDb; fine = true; }
            lastDir = dir;
            double step = fine ? 5 : 10;
            LevelDb = Math.Max(FloorDb, Math.Min(CeilingDb, LevelDb + dir * step));
            if (reversals >= 4 || answers >= 14 || (LevelDb >= CeilingDb && !heard && answers > 3))
            {
                Done = true;
                ThresholdDb = reversals > 0 ? revSum / reversals : LevelDb;
            }
        }

        // Thresholds (dBFS per frequency) -> gentle EQ: half of the loss relative to the best frequency of
        // either ear ("half-gain rule"), never more than +9 dB, small differences (< 3 dB) ignored.
        public static double[] ToEq(double[] thresholds, double reference)
        {
            var g = new double[thresholds.Length];
            for (int i = 0; i < thresholds.Length; i++)
            {
                double loss = thresholds[i] - reference;
                g[i] = loss < 3 ? 0 : Math.Min(HearingProfile.MaxBoostDb, Math.Round(loss / 2 * 2) / 2);
            }
            return g;
        }

        public static HearingProfile ToProfile(string name, double[] left, double[] right)
        {
            double best = double.MaxValue;
            foreach (var v in left) best = Math.Min(best, v);
            foreach (var v in right) best = Math.Min(best, v);
            return new HearingProfile { Name = name, LeftDb = ToEq(left, best), RightDb = ToEq(right, best) };
        }
    }

    // Token bucket: at most `perSecond` events per second, bursts up to the same number.
    public class RateLimiter
    {
        readonly double perSecond;
        double tokens;
        long last;
        bool started;

        public RateLimiter(double perSecond) { this.perSecond = perSecond; tokens = perSecond; }

        public bool Allow(long nowMs)
        {
            if (!started) { started = true; last = nowMs; }
            double dt = Math.Max(0, nowMs - last) / 1000.0;
            last = nowMs;
            tokens = Math.Min(perSecond, tokens + dt * perSecond);
            if (tokens >= 1) { tokens -= 1; return true; }
            return false;
        }
    }
}

namespace DualEarbuds
{
    // Everything one laptop output plays through: EQ preset (manual or Smart EQ), hearing profile and
    // dialogue boost. Settings are written by the UI thread and picked up by the audio thread at the start
    // of the next block, so a change never tears a filter mid-block. Off settings cost nothing.
    public class OutputEffects
    {
        volatile string manualPreset = "flat";
        volatile bool auto;                 // the user picked "Auto" (Smart EQ decides)
        volatile string autoPreset;         // Smart EQ's current pick, null = no opinion
        volatile HearingProfile hearing;
        volatile int dialogue;              // DialogueMode
        int version, applied = -1;
        EqChain eq;
        DialogueBoost boost;
        int rate;
        string activePreset;

        public string ManualPreset { get { return manualPreset; } }
        public bool Auto { get { return auto; } }
        public string AutoPreset { get { return autoPreset; } }
        public HearingProfile Hearing { get { return hearing; } }
        public DialogueMode Dialogue { get { return (DialogueMode)dialogue; } }

        // What is actually playing now (for the UI).
        public string EffectivePreset { get { string a = autoPreset; return auto && a != null ? a : manualPreset; } }

        // A manual choice always wins until the user picks Auto again.
        public void SetManual(string preset) { manualPreset = EqPresets.IsValid(preset) ? preset : "flat"; auto = false; Bump(); }
        public void SetAuto(bool on) { auto = on; Bump(); }
        public void SetAutoPreset(string preset) { string p = EqPresets.IsValid(preset) ? preset : null; if (p == autoPreset) return; autoPreset = p; Bump(); }
        public void SetHearing(HearingProfile p) { hearing = p; Bump(); }
        public void SetDialogue(DialogueMode m) { dialogue = (int)m; Bump(); }

        void Bump() { System.Threading.Interlocked.Increment(ref version); }

        public bool IsBypass
        {
            get { return EffectivePreset == "flat" && hearing == null && dialogue == 0 && (eq == null || eq.IsFlat); }
        }

        // Audio thread. buf is stereo interleaved.
        public void Process(float[] buf, int offsetFrames, int frames, int sampleRate)
        {
            if (frames <= 0) return;
            if (eq == null || rate != sampleRate)
            {
                // built once per stream (first block); never per block
                rate = sampleRate;
                eq = new EqChain(sampleRate);
                boost = new DialogueBoost(sampleRate, 480);
                applied = -1;
                activePreset = null;
            }
            int v = System.Threading.Thread.VolatileRead(ref version);
            if (v != applied)
            {
                applied = v;
                string p = EffectivePreset;
                if (p != activePreset) { eq.SetPreset(p, activePreset != null); activePreset = p; }
                eq.SetHearing(hearing);
                boost.Mode = (DialogueMode)dialogue;
            }
            boost.Process(buf, offsetFrames, frames);
            eq.Process(buf, offsetFrames, frames);
        }
    }
}
