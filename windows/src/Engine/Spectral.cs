// Dual Earbuds - frequency-domain helpers: an in-place FFT, the mic noise suppressor (smart feature #1)
// and the "what is playing" sound classifier (smart feature #2). Preallocated, allocation-free per frame.
using System;

namespace DualEarbuds
{
    // Radix-2 complex FFT, in place, size fixed at construction.
    public class Fft
    {
        public readonly int N;
        readonly double[] cos, sin;
        readonly int[] rev;

        public Fft(int n)
        {
            if (n < 2 || (n & (n - 1)) != 0) throw new ArgumentException("FFT size must be a power of two");
            N = n;
            cos = new double[n / 2];
            sin = new double[n / 2];
            for (int i = 0; i < n / 2; i++) { cos[i] = Math.Cos(2 * Math.PI * i / n); sin[i] = -Math.Sin(2 * Math.PI * i / n); }
            rev = new int[n];
            int bits = 0;
            while ((1 << bits) < n) bits++;
            for (int i = 0; i < n; i++)
            {
                int r = 0;
                for (int b = 0; b < bits; b++) if ((i & (1 << b)) != 0) r |= 1 << (bits - 1 - b);
                rev[i] = r;
            }
        }

        public void Transform(double[] re, double[] im, bool inverse)
        {
            int n = N;
            for (int i = 0; i < n; i++)
            {
                int j = rev[i];
                if (j > i) { double t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
            }
            for (int size = 2; size <= n; size <<= 1)
            {
                int half = size >> 1, step = n / size;
                for (int start = 0; start < n; start += size)
                {
                    for (int k = 0; k < half; k++)
                    {
                        double wr = cos[k * step], wi = inverse ? -sin[k * step] : sin[k * step];
                        int a = start + k, b = a + half;
                        double xr = re[b] * wr - im[b] * wi;
                        double xi = re[b] * wi + im[b] * wr;
                        re[b] = re[a] - xr; im[b] = im[a] - xi;
                        re[a] += xr; im[a] += xi;
                    }
                }
            }
            if (inverse) { double s = 1.0 / n; for (int i = 0; i < n; i++) { re[i] *= s; im[i] *= s; } }
        }
    }

    public enum NoiseMode { Off, Light, Strong }

    // Mic noise suppression for talk-over (smart feature #1).
    // The spec prefers an RNNoise port; that port is not done, so this is the documented fallback:
    // a spectral-gating suppressor (STFT 1024 / hop 512, sqrt-Hann windows, per-bin noise floor by
    // averaged noise floor, over-subtracted spectral gain with a -20 dB floor, time smoothing). Light = 50 % dry mix, Strong = full.
    // Mono in, mono out, any block size; adds 1023 samples (~21 ms at 48 kHz) of latency when on.
    public class NoiseSuppressor
    {
        public const int Size = 1024, Hop = 512;
        readonly Fft fft = new Fft(Size);
        readonly double[] win = new double[Size], re = new double[Size], im = new double[Size];
        readonly double[] noise = new double[Size / 2 + 1], gain = new double[Size / 2 + 1], psm = new double[Size / 2 + 1];
        readonly float[] inBuf = new float[Size], outAcc = new float[Size], dryDelay = new float[Size - 1];
        readonly float[] ready = new float[Hop];
        int inFill, readyPos = Hop, dryPos;
        long frames;
        public NoiseMode Mode = NoiseMode.Strong;

        public NoiseSuppressor()
        {
            for (int i = 0; i < Size; i++) win[i] = Math.Sqrt(0.5 - 0.5 * Math.Cos(2 * Math.PI * i / Size));
            for (int i = 0; i < noise.Length; i++) { noise[i] = 1e-9; gain[i] = 1; }
            inFill = Size - Hop;   // start with half a frame of silence so the first hop lines up
        }

        public int LatencySamples { get { return Size - 1; } }

        // In place: x[0..n) mono samples.
        public void Process(float[] x, int offset, int n)
        {
            if (Mode == NoiseMode.Off) return;
            double wet = Mode == NoiseMode.Light ? 0.5 : 1.0;
            for (int i = 0; i < n; i++)
            {
                float s = x[offset + i];
                inBuf[inFill++] = s;
                if (inFill == Size) RunFrame();
                // ready[] holds the newest Hop processed samples; read them out one by one
                float y = readyPos < Hop ? ready[readyPos++] : 0f;
                // the dry path is delayed by the same amount as the wet path
                float dry = dryDelay[dryPos];
                dryDelay[dryPos] = s;
                dryPos = (dryPos + 1) % (Size - 1);
                x[offset + i] = (float)(wet * y + (1 - wet) * dry);
            }
        }

        void RunFrame()
        {
            for (int i = 0; i < Size; i++) { re[i] = inBuf[i] * win[i]; im[i] = 0; }
            fft.Transform(re, im, false);
            frames++;
            int bins = Size / 2 + 1;
            for (int k = 0; k < bins; k++)
            {
                double p = re[k] * re[k] + im[k] * im[k];
                psm[k] = frames == 1 ? p : psm[k] * 0.7 + p * 0.3;
                // noise floor: running average of the frames that look like noise (below 3x the floor);
                // creeps up slowly (~1 dB/s) so a steady new noise is learned without eating the voice
                if (frames == 1) noise[k] = p;
                else if (p < 3 * noise[k]) noise[k] = noise[k] * 0.95 + p * 0.05;
                else noise[k] *= 1.0025;
                double g = 1 - 2.2 * noise[k] / (psm[k] + 1e-12);   // spectral subtraction (over-subtracted)
                g = Math.Max(0.1, g);                                 // -20 dB floor keeps it natural
                gain[k] = g > gain[k] ? gain[k] * 0.3 + g * 0.7 : gain[k] * 0.6 + g * 0.4;   // quick open, gentle close
                re[k] *= gain[k]; im[k] *= gain[k];
                if (k > 0 && k < Size / 2) { re[Size - k] = re[k]; im[Size - k] = -im[k]; }
            }
            fft.Transform(re, im, true);
            // overlap-add: first half completes now
            for (int i = 0; i < Size; i++) outAcc[i] += (float)(re[i] * win[i]);
            Array.Copy(outAcc, 0, ready, 0, Hop);
            Array.Copy(outAcc, Hop, outAcc, 0, Size - Hop);
            Array.Clear(outAcc, Size - Hop, Hop);
            readyPos = 0;
            Array.Copy(inBuf, Hop, inBuf, 0, Size - Hop);
            inFill = Size - Hop;
        }
    }

    public enum SoundClass { Silence, Speech, Music, Movie }

    // Features of one analysis window, all 0..1-ish and level independent.
    public struct SoundFeatures
    {
        public double Flatness;      // spectral flatness (geometric / arithmetic mean of power), noise -> 1, tones -> 0
        public double Centroid;      // spectral centroid / (rate/2)
        public double Modulation4Hz; // share of the level envelope's variation that sits at 2-8 Hz (syllable rate)
        public double Zcr;           // zero crossings per sample
        public double LowRatio;      // energy below 250 Hz / total
        public double Rms;
    }

    // "By sound" smart EQ classifier (smart feature #2): hand-made features on 1 s windows and a tiny fixed
    // decision model; the reported class changes only after the same class wins 5 windows in a row.
    public class SoundClassifier
    {
        public const int WindowMs = 1000, FrameMs = 10, HoldWindows = 5;
        readonly int rate, frameLen;
        readonly Fft fft = new Fft(1024);
        readonly double[] re = new double[1024], im = new double[1024], hann = new double[1024];
        readonly float[] fftIn = new float[1024];
        readonly double[] env = new double[WindowMs / FrameMs];
        int fftFill, envCount, frameFill, zc, samples;
        double frameEnergy, flatSum, centSum, lowSum, specCount, energySum;
        float prev;
        SoundClass candidate = SoundClass.Silence;
        int streak;

        public SoundClass Current { get; private set; }
        public SoundClass LastWindow { get; private set; }
        public SoundFeatures LastFeatures { get; private set; }
        public event Action<SoundClass> Changed;   // raised on the audio thread

        public SoundClassifier(int sampleRate)
        {
            rate = sampleRate;
            frameLen = Math.Max(1, sampleRate * FrameMs / 1000);
            for (int i = 0; i < 1024; i++) hann[i] = 0.5 - 0.5 * Math.Cos(2 * Math.PI * i / 1023);
            Current = SoundClass.Silence;
        }

        // Stereo interleaved block.
        public void FeedStereo(float[] buf, int frames)
        {
            for (int i = 0; i < frames; i++) FeedSample(0.5f * (buf[2 * i] + buf[2 * i + 1]));
        }

        public void FeedMono(float[] buf, int n)
        {
            for (int i = 0; i < n; i++) FeedSample(buf[i]);
        }

        void FeedSample(float x)
        {
            if ((x >= 0) != (prev >= 0)) zc++;
            prev = x;
            samples++;
            frameEnergy += x * x;
            if (++frameFill == frameLen)
            {
                if (envCount < env.Length) env[envCount++] = Math.Sqrt(frameEnergy / frameLen);
                energySum += frameEnergy;
                frameEnergy = 0;
                frameFill = 0;
                if (envCount == env.Length) EndWindow();
            }
            fftIn[fftFill++] = x;
            if (fftFill == 1024) { Spectrum(); fftFill = 0; }
        }

        void Spectrum()
        {
            for (int i = 0; i < 1024; i++) { re[i] = fftIn[i] * hann[i]; im[i] = 0; }
            fft.Transform(re, im, false);
            double sum = 0, cent = 0, low = 0, fSum = 0, fLog = 0;
            int bins = 512, lowBin = (int)(250.0 * 1024 / rate), topBin = Math.Min(bins - 1, (int)(8000.0 * 1024 / rate));
            for (int k = 1; k < bins; k++)
            {
                double p = re[k] * re[k] + im[k] * im[k] + 1e-12;
                sum += p;
                cent += p * k;
                if (k <= lowBin) low += p;
                else if (k <= topBin) { fSum += p; fLog += Math.Log(p); }
            }
            if (sum < 1e-8) return;   // silent: no spectral evidence
            // flatness over 250 Hz - 8 kHz only, so a bass line or rumble doesn't hide a noisy mix
            double n = Math.Max(1, topBin - lowBin);
            flatSum += fSum > 1e-10 ? Math.Exp(fLog / n) / (fSum / n) : 0;
            centSum += cent / sum / bins;
            lowSum += low / sum;
            specCount++;
        }

        void EndWindow()
        {
            var f = new SoundFeatures();
            int n = envCount;
            f.Rms = Math.Sqrt(energySum / Math.Max(1, samples));
            f.Zcr = (double)zc / Math.Max(1, samples);
            if (specCount > 0) { f.Flatness = flatSum / specCount; f.Centroid = centSum / specCount; f.LowRatio = lowSum / specCount; }
            f.Modulation4Hz = ModulationShare(env, n);
            LastFeatures = f;
            var c = Classify(f);
            LastWindow = c;
            if (c == candidate) streak++; else { candidate = c; streak = 1; }
            if (streak >= HoldWindows && c != Current)
            {
                Current = c;
                var h = Changed;
                if (h != null) h(c);
            }
            envCount = 0; zc = 0; samples = 0; energySum = 0;
            flatSum = centSum = lowSum = specCount = 0;
        }

        // Share of envelope variance (100 Hz frame rate) in the 2-8 Hz band, via a direct DFT of the
        // mean-removed envelope (n = 100, so this is cheap and needs no buffers).
        public static double ModulationShare(double[] e, int n)
        {
            if (n < 8) return 0;
            double mean = 0;
            for (int i = 0; i < n; i++) mean += e[i];
            mean /= n;
            double total = 0;
            for (int i = 0; i < n; i++) { double d = e[i] - mean; total += d * d; }
            if (total < 1e-14 || mean < 1e-6) return 0;
            double band = 0;
            double frameRate = 1000.0 / FrameMs;
            int k0 = (int)Math.Ceiling(2.0 * n / frameRate), k1 = (int)Math.Floor(8.0 * n / frameRate);
            for (int k = k0; k <= k1; k++)
            {
                double sr = 0, si = 0;
                for (int i = 0; i < n; i++)
                {
                    double d = e[i] - mean, a = 2 * Math.PI * k * i / n;
                    sr += d * Math.Cos(a); si -= d * Math.Sin(a);
                }
                band += 2 * (sr * sr + si * si) / n;   // one-sided power
            }
            return Math.Min(1.0, band / total);
        }

        // The fixed decision model. Silence first; then speech has strong syllable-rate modulation and a
        // mid spectrum; music is tonal (low flatness) and steady; movie mixes are noisy, wide and bassy.
        public static SoundClass Classify(SoundFeatures f)
        {
            if (f.Rms < 0.003) return SoundClass.Silence;
            double speech = 2.2 * f.Modulation4Hz - 1.0 * f.LowRatio + 0.6 * (f.Centroid < 0.25 ? 1 : 0) - 1.0;
            double music = 1.4 * (1 - f.Flatness) - 1.6 * f.Modulation4Hz - 0.6;
            double movie = 1.6 * f.Flatness + 0.8 * f.LowRatio - 0.8 * f.Modulation4Hz - 0.9;
            if (speech >= music && speech >= movie) return SoundClass.Speech;
            return music >= movie ? SoundClass.Music : SoundClass.Movie;
        }

        // Class -> EQ preset name.
        public static string PresetFor(SoundClass c)
        {
            switch (c) { case SoundClass.Speech: return "voice"; case SoundClass.Music: return "bass"; case SoundClass.Movie: return "movie"; default: return null; }
        }

        // "By app": process name -> preset (null = no opinion).
        public static string PresetForApp(string process)
        {
            if (string.IsNullOrEmpty(process)) return null;
            string p = process.ToLowerInvariant();
            if (p.EndsWith(".exe")) p = p.Substring(0, p.Length - 4);
            string[] movie = { "netflix", "vlc", "mpc-hc", "mpc-hc64", "mpc-be", "potplayer", "potplayermini64", "primevideo", "disneyplus", "wmplayer", "movies", "video.ui", "mpv", "kodi", "plex", "youtube" };
            string[] music = { "spotify", "itunes", "applemusic", "foobar2000", "musicbee", "aimp", "winamp", "tidal", "deezer", "amazon music", "groove", "music.ui" };
            string[] voice = { "zoom", "teams", "ms-teams", "skype", "discord", "slack", "webex", "audible", "podcasts", "whatsapp", "telegram" };
            foreach (var s in movie) if (p == s || p.StartsWith(s)) return "movie";
            foreach (var s in music) if (p == s || p.StartsWith(s)) return "bass";
            foreach (var s in voice) if (p == s || p.StartsWith(s)) return "voice";
            return null;
        }
    }
}
