// Dual Earbuds - timing for listeners: clock sync (protocol v3.1), rate trim, a tiny resampler,
// packet-loss concealment, and the cross-correlation behind auto-sync calibration. Pure logic,
// unit-tested, allocation-free in the per-packet paths.
using System;

namespace DualEarbuds
{
    // Host clock estimate from sync replies: offset = h - (c + now) / 2; the sample with the smallest
    // round trip among the last 8 wins (docs/protocol.md "Clock sync").
    public class ClockSync
    {
        public const int Keep = 8;
        readonly double[] rtts = new double[Keep], offs = new double[Keep];
        int count, pos;

        public int Samples { get { return count; } }
        public bool Synced { get { return count > 0; } }

        // c = listener ms sent, h = host ms in the reply, now = listener ms when the reply arrived.
        public bool Add(double c, double h, double now)
        {
            double rtt = now - c;
            if (rtt < 0 || rtt > 5000 || double.IsNaN(h)) return false;
            rtts[pos] = rtt;
            offs[pos] = h - (c + now) / 2;
            pos = (pos + 1) % Keep;
            if (count < Keep) count++;
            return true;
        }

        int Best()
        {
            int b = 0;
            for (int i = 1; i < count; i++) if (rtts[i] < rtts[b]) b = i;
            return b;
        }

        public double Offset { get { return count == 0 ? 0 : offs[Best()]; } }
        public double BestRtt { get { return count == 0 ? -1 : rtts[Best()]; } }
        public double HostNow(double localNow) { return localNow + Offset; }
        public void Reset() { count = 0; pos = 0; }
    }

    // seq -> host capture time, from the host's {"type":"clock","seq":..,"h":..} (one per second).
    public class PacketClock
    {
        bool has;
        uint seq0;
        double h0;
        public bool Known { get { return has; } }

        public void OnClock(uint seq, double h) { seq0 = seq; h0 = h; has = true; }

        public double HostTime(uint seq)
        {
            int diff = unchecked((int)(seq - seq0));   // handles the uint wrap
            return h0 + diff * (double)PhoneLink.PacketMs;
        }

        public void Reset() { has = false; }
    }

    // Keeps one output on schedule (protocol v3.1): within +-5 ms do nothing, up to +-40 ms trim the
    // playback rate by at most +-0.5 %, beyond that skip or insert at once.
    public class SyncTrim
    {
        public const double DeadbandMs = 5, ResumeMs = 2, SnapMs = 40, MaxTrim = 0.005, CorrectOverMs = 2000;
        bool trimming;

        // errorMs > 0: this output plays later than it should (too much queued).
        // Returns the frames to skip (>0) or insert (<0) right now; rate gets the resampler ratio
        // (>1 consumes input faster, so the output catches up).
        public int Decide(double errorMs, int sampleRate, out double rate)
        {
            rate = 1.0;
            double a = Math.Abs(errorMs);
            if (a > SnapMs)
            {
                trimming = false;
                return (int)Math.Round(errorMs * sampleRate / 1000.0);
            }
            if (!trimming && a > DeadbandMs) trimming = true;
            else if (trimming && a < ResumeMs) trimming = false;
            if (trimming) rate = 1.0 + Math.Max(-MaxTrim, Math.Min(MaxTrim, errorMs / CorrectOverMs));
            return 0;
        }

        public bool Trimming { get { return trimming; } }
    }

    // Linear-interpolation resampler for small rate trims (stereo interleaved, streaming, no allocation).
    public class StereoResampler
    {
        double pos;
        float lastL, lastR;

        public void Reset() { pos = 0; lastL = lastR = 0; }

        // ratio = input frames consumed per output frame. Returns output frames written.
        public int Process(float[] input, int inFrames, float[] output, int maxOut, double ratio)
        {
            if (inFrames <= 0) return 0;
            if (ratio <= 0) ratio = 1;
            int o = 0;
            while (o < maxOut)
            {
                int i0 = (int)Math.Floor(pos);
                if (i0 + 1 >= inFrames) break;
                double frac = pos - i0;
                float l0, r0;
                if (i0 < 0) { l0 = lastL; r0 = lastR; } else { l0 = input[2 * i0]; r0 = input[2 * i0 + 1]; }
                float l1 = input[2 * (i0 + 1)], r1 = input[2 * (i0 + 1) + 1];
                output[2 * o] = (float)(l0 + (l1 - l0) * frac);
                output[2 * o + 1] = (float)(r0 + (r1 - r0) * frac);
                o++;
                pos += ratio;
            }
            pos -= inFrames;
            if (pos < -1) pos = -1;
            lastL = input[2 * (inFrames - 1)];
            lastR = input[2 * (inFrames - 1) + 1];
            return o;
        }
    }

    // Packet-loss concealment (protocol v3.1): a missing packet is filled from the last 10 ms, fading to
    // silence over at most 30 ms; the next real packet is crossfaded back in. No clicks: instead of
    // looping the whole 10 ms (which jumps in phase at the loop point), the repeat loops the last pitch
    // period found in those 10 ms by autocorrelation, so the repeated sound continues smoothly.
    public class Concealer
    {
        public const int FadeMs = 30, CrossfadeMs = 5;
        readonly int packetFrames, fadeFrames, xfadeFrames, minPeriod, matchLen;
        readonly float[] last;
        bool hasLast, afterGap;
        int repeatPos, period;
        double gain = 1.0;
        long concealedPackets;

        public Concealer(int sampleRate)
        {
            packetFrames = PhoneLink.PacketFrames(sampleRate);
            fadeFrames = Math.Max(1, sampleRate * FadeMs / 1000);
            xfadeFrames = Math.Max(1, sampleRate * CrossfadeMs / 1000);
            minPeriod = Math.Max(2, sampleRate / 400);          // 2.5 ms (400 Hz)
            matchLen = Math.Max(2, sampleRate / 800);            // compare 1.25 ms
            last = new float[packetFrames * 2];
            period = packetFrames;
        }

        public int PacketFrames { get { return packetFrames; } }
        public long ConcealedPackets { get { return concealedPackets; } }

        float Tail(int i, int ch)   // sample i of the looped tail
        {
            int r = packetFrames - period + (i % period);
            return last[2 * r + ch];
        }

        // A real packet (stereo, in place). Crossfades from the concealment tail when it follows a gap.
        public void OnPacket(float[] pkt, int frames)
        {
            if (afterGap && hasLast)
            {
                int n = Math.Min(xfadeFrames, frames);
                double g = gain, step = 1.0 / fadeFrames;
                for (int i = 0; i < n; i++)
                {
                    double t = (i + 1.0) / (n + 1.0);
                    pkt[2 * i] = (float)(pkt[2 * i] * t + Tail(repeatPos, 0) * g * (1 - t));
                    pkt[2 * i + 1] = (float)(pkt[2 * i + 1] * t + Tail(repeatPos, 1) * g * (1 - t));
                    repeatPos++;
                    g = Math.Max(0, g - step);
                }
            }
            afterGap = false;
            gain = 1.0;
            repeatPos = 0;
            int copy = Math.Min(frames, packetFrames);
            Array.Copy(pkt, (frames - copy) * 2, last, 0, copy * 2);
            if (copy < packetFrames) Array.Clear(last, 0, (packetFrames - copy) * 2);
            hasLast = true;
        }

        // Best loop length: the lag (2.5..10 ms) whose start matches what follows the packet's last sample.
        void FindPeriod()
        {
            int pf = packetFrames;
            double best = double.MaxValue;
            int bestP = pf;
            for (int p = minPeriod; p <= pf - matchLen; p++)
            {
                // the sound just before the end vs. the sound just before (end - p): a periodic signal matches
                double err = 0, en = 0;
                for (int j = 1; j <= matchLen; j++)
                {
                    int a = pf - j, b = pf - p - j;
                    float ma = last[2 * a] + last[2 * a + 1], mb = last[2 * b] + last[2 * b + 1];
                    double d = ma - mb;
                    err += d * d;
                    en += ma * ma;
                }
                double score = err / (en + 1e-9) + p * 1e-6;   // tiny bias toward short periods
                if (score < best) { best = score; bestP = p; }
            }
            period = bestP;
        }

        // Writes one packet's worth of concealment into dst (stereo).
        public void Conceal(float[] dst, int frames)
        {
            concealedPackets++;
            if (!hasLast) { afterGap = true; Array.Clear(dst, 0, frames * 2); return; }
            if (!afterGap) { FindPeriod(); repeatPos = 0; }
            afterGap = true;
            double step = 1.0 / fadeFrames;
            for (int i = 0; i < frames; i++)
            {
                dst[2 * i] = (float)(Tail(repeatPos, 0) * gain);
                dst[2 * i + 1] = (float)(Tail(repeatPos, 1) * gain);
                repeatPos++;
                gain = Math.Max(0, gain - step);
            }
        }

        public void Reset() { hasLast = false; afterGap = false; gain = 1; repeatPos = 0; period = packetFrames; }
    }

    // Sequence numbers -> what to do with an arriving packet.
    public static class SeqGap
    {
        public const int MaxConceal = 6;   // longer gaps are left to the jitter buffer (silence + catch-up)

        // Returns how many packets are missing before `seq` (0 = in order), or -1 for a late/duplicate packet.
        public static int Missing(bool haveLast, uint lastSeq, uint seq)
        {
            if (!haveLast) return 0;
            int d = unchecked((int)(seq - lastSeq));
            if (d <= 0) return -1;
            if (d > 1000) return 0;   // a restart on the host: treat as a fresh stream
            return d - 1;
        }
    }

    // Auto-sync calibration (protocol v3.1 "Calibration"): 8 clicks played on one output, found in the
    // microphone signal by normalized cross-correlation; the median lag is the output's latency.
    public static class Calibration
    {
        // the search window for one click (MaxLatencyMs) must end before the next click starts
        public const int Clicks = 8, SpacingMs = 700, LeadInMs = 250, MaxLatencyMs = 550, ClickMs = 4;

        // A short windowed 1.5-5 kHz chirp: easy to find in a laptop mic, survives Bluetooth codecs.
        public static float[] ClickTemplate(int rate)
        {
            int n = Math.Max(16, rate * ClickMs / 1000);
            var t = new float[n];
            double f0 = 1500, f1 = 5000, dur = (double)n / rate;
            for (int i = 0; i < n; i++)
            {
                double x = (double)i / rate;
                double phase = 2 * Math.PI * (f0 * x + (f1 - f0) * x * x / (2 * dur));
                double w = 0.5 - 0.5 * Math.Cos(2 * Math.PI * i / (n - 1));
                t[i] = (float)(Math.Sin(phase) * w);
            }
            return t;
        }

        // The click train (mono) and where each click starts.
        public static float[] BuildTrain(int rate, float volume, out int[] starts)
        {
            var click = ClickTemplate(rate);
            int spacing = rate * SpacingMs / 1000, lead = rate * LeadInMs / 1000;
            int len = lead + spacing * Clicks + rate * MaxLatencyMs / 1000;
            var s = new float[len];
            starts = new int[Clicks];
            for (int c = 0; c < Clicks; c++)
            {
                starts[c] = lead + c * spacing;
                for (int i = 0; i < click.Length; i++) s[starts[c] + i] = click[i] * volume;
            }
            return s;
        }

        public class Result
        {
            public bool Ok;
            public double LatencyMs;
            public int Agreeing;      // clicks within 2 ms of the median
            public double Quality;    // mean normalized correlation of the agreeing clicks
            public string Problem;
        }

        // rec: mono mic samples; clickStarts: where each click was handed to the output, in rec's sample index.
        public static Result Measure(float[] rec, int recLen, int rate, int[] clickStarts)
        {
            var scratch = new double[0];
            var res = new Result();
            var tmpl = ClickTemplate(rate);
            int n = tmpl.Length, maxLag = rate * MaxLatencyMs / 1000;
            double te = 0;
            for (int j = 0; j < n; j++) te += tmpl[j] * tmpl[j];
            var lags = new double[clickStarts.Length];
            var corr = new double[clickStarts.Length];
            int found = 0;
            for (int c = 0; c < clickStarts.Length; c++)
            {
                int s0 = clickStarts[c];
                if (s0 < 0 || s0 + n >= recLen) continue;
                int top = Math.Min(maxLag, recLen - n - s0 - 1);
                double best = 0;
                int bestLag = -1;
                // pass 1 finds the best match; pass 2 takes the earliest lag nearly as good (the direct sound,
                // not a room echo)
                var ncs = scratch.Length >= top + 1 ? scratch : (scratch = new double[top + 1]);
                // running window energy for normalization
                double we = 0;
                for (int j = 0; j < n; j++) we += rec[s0 + j] * rec[s0 + j];
                for (int L = 0; L <= top; L++)
                {
                    int b = s0 + L;
                    if (L > 0) { float o = rec[b - 1], ni = rec[b + n - 1]; we += ni * ni - o * o; if (we < 0) we = 0; }
                    double acc = 0;
                    for (int j = 0; j < n; j++) acc += tmpl[j] * rec[b + j];
                    double nc = we > 1e-12 ? acc / Math.Sqrt(te * we) : 0;
                    ncs[L] = nc;
                    if (nc > best) { best = nc; bestLag = L; }
                }
                for (int L = 0; L < bestLag; L++) if (ncs[L] >= best * 0.9) { bestLag = L; break; }
                if (bestLag < 0 || best < 0.3) continue;
                lags[found] = bestLag * 1000.0 / rate;
                corr[found] = best;
                found++;
            }
            if (found < 3) { res.Problem = "The clicks weren't heard. Hold the earbud right against the laptop's microphone and try again in a quiet room."; return res; }
            var sorted = new double[found];
            Array.Copy(lags, sorted, found);
            Array.Sort(sorted);
            double median = found % 2 == 1 ? sorted[found / 2] : (sorted[found / 2 - 1] + sorted[found / 2]) / 2;
            int agree = 0;
            double q = 0, sum = 0;
            for (int i = 0; i < found; i++)
                if (Math.Abs(lags[i] - median) <= 2.0) { agree++; q += corr[i]; sum += lags[i]; }
            res.Agreeing = agree;
            res.Quality = agree > 0 ? q / agree : 0;
            if (agree < 5) { res.Problem = "The result wasn't steady. Keep the earbud still against the microphone and try again."; return res; }
            res.LatencyMs = sum / agree;
            res.Ok = true;
            return res;
        }
    }
}
