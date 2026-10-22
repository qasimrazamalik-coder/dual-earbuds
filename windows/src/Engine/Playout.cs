// Dual Earbuds - the common deadline (protocol v3.2): every ear, the host's own outputs included, hears the
// sample captured at host time t at t + P. Pure logic, unit-tested; the host and the listener share it.
using System;
using System.Collections.Generic;

namespace DualEarbuds
{
    // Chooses P from what every ear needs and moves it without audible jumps:
    // increases apply at once, decreases at most 5 ms per second.
    public class PlayoutPlanner
    {
        public const int MinMs = 40, MaxMs = 400, DecreasePerSecond = 5;

        int current = -1;
        long lastMs;
        double credit;   // ms the value may still come down (accumulated from elapsed time)

        public int Current { get { return current < 0 ? PhoneLink.DefaultPlayoutMs : current; } }
        public bool Started { get { return current >= 0; } }

        // P = clamp(max(listener needs, local need), 40, 400). Needs <= 0 are unknown and ignored.
        public static int Choose(IEnumerable<int> listenerNeeds, int localNeedMs)
        {
            int m = Math.Max(0, localNeedMs);
            if (listenerNeeds != null)
                foreach (int n in listenerNeeds) if (n > 0) m = Math.Max(m, Math.Min(MaxMs, n));
            return Clamp(m);
        }

        public static int Clamp(int ms) { return Math.Max(MinMs, Math.Min(MaxMs, ms)); }

        // Moves toward target; returns the value now in effect. Call about once a second (any rate works).
        public int Step(int target, long nowMs)
        {
            target = Clamp(target);
            if (current < 0) { current = target; lastMs = nowMs; credit = 0; return current; }
            long dt = Math.Max(0, nowMs - lastMs);
            lastMs = nowMs;
            if (target >= current) { current = target; credit = 0; return current; }
            credit = Math.Min(credit + dt * DecreasePerSecond / 1000.0, current - target);
            int down = (int)Math.Floor(credit);
            if (down > 0) { current -= down; credit -= down; }
            return current;
        }

        public void Reset() { current = -1; credit = 0; }
    }

    // How one local output lines up with P. outputLatency = time from a frame entering the device's buffer
    // (end of our own queue) to it being heard: calibrated, or estimated from what WASAPI reports.
    public static class OutputTiming
    {
        public const int CushionMs = 15;          // OutputStream's own safety cushion, always queued
        public const int MixerMs = 10;            // Windows audio engine period, not in the reported latency
        public const int BluetoothGuessMs = 150;  // A2DP codec + radio when nothing was measured
        public const int UnknownReportedMs = 20;

        // calibratedMs > 0 wins; otherwise reported stream latency + mixer (+ a Bluetooth guess).
        public static double LatencyMs(double calibratedMs, double reportedMs, bool bluetooth)
        {
            if (calibratedMs > 0) return calibratedMs;
            double r = reportedMs > 0 ? reportedMs : UnknownReportedMs;
            return r + MixerMs + (bluetooth ? BluetoothGuessMs : 0);
        }

        // The earliest this output can play a captured sample (no extra delay): its need for P.
        public static int NeedMs(double latencyMs) { return (int)Math.Ceiling(Math.Max(0, latencyMs) + CushionMs); }

        // Extra delay to queue so the output is heard at capture + P; the manual Delay always adds on top.
        public static int AutoDelayMs(int playoutMs, double latencyMs)
        {
            return Math.Max(0, (int)Math.Round(playoutMs - latencyMs - CushionMs));
        }

        // The host's local need: the slowest ticked output.
        public static int LocalNeedMs(IEnumerable<double> latencies)
        {
            int m = 0;
            foreach (var l in latencies) m = Math.Max(m, NeedMs(l));
            return m;
        }

        // Bluetooth or not, from what Windows tells us. Built-in devices share the computer's container id;
        // a battery level only exists for Bluetooth; A2DP endpoints are named "Headphones (<device>)".
        public static readonly Guid ComputerContainer = new Guid("00000000-0000-0000-ffff-ffffffffffff");
        static readonly string[] Wired = { "realtek", "high definition", "usb", "conexant", "idt", "intel", "hdmi", "nvidia", "amd", "display audio", "cirrus", "synaptics", "sound blaster", "dolby", "speakers", "spdif", "digital audio" };

        public static bool LooksBluetooth(string name, bool hasBattery, Guid container)
        {
            if (hasBattery) return true;
            if (container == ComputerContainer) return false;
            string n = (name ?? "").ToLowerInvariant();
            if (n.Contains("bluetooth") || n.Contains("hands-free") || n.Contains("airpods") || n.Contains("buds")) return true;
            foreach (var w in Wired) if (n.Contains(w)) return false;
            return n.StartsWith("headphones (") || n.StartsWith("headset (");
        }
    }

    // Inter-arrival jitter p99 over a sliding window (two staggered P-square estimators, each covering
    // half the window), as protocol v3.2 asks for needMs: "jitter p99 over the last 30 s".
    public class JitterWindow
    {
        readonly long halfMs;
        readonly int packetMs;
        P2Quantile cur = new P2Quantile(0.99), prev = new P2Quantile(0.99);
        long windowStart = long.MinValue, lastArrival = long.MinValue;
        double maxCur, maxPrev;   // the estimator is weak with few samples: never report below what was seen recently

        public JitterWindow(int windowMs, int packetMs) { halfMs = Math.Max(1000, windowMs / 2); this.packetMs = packetMs; }

        public void Reset() { cur.Reset(); prev.Reset(); windowStart = long.MinValue; lastArrival = long.MinValue; maxCur = maxPrev = 0; }

        // A packet arrived at local time now (ms); expected every packetMs.
        public void OnPacket(long now)
        {
            if (windowStart == long.MinValue) windowStart = now;
            if (lastArrival != long.MinValue)
            {
                double gap = now - lastArrival;
                if (gap >= 0 && gap < 2000) AddJitter(Math.Abs(gap - packetMs));
            }
            lastArrival = now;
            if (now - windowStart >= halfMs)
            {
                var t = prev; prev = cur; cur = t; cur.Reset();
                maxPrev = maxCur; maxCur = 0;
                windowStart = now;
            }
        }

        // For a burst of packets the arrival gap says little; what matters is how late each packet is
        // compared to a steady 10 ms schedule. Callers that know lateness can add it directly.
        public void AddJitter(double ms)
        {
            cur.Add(ms);
            if (ms > maxCur) maxCur = ms;
        }

        public int Samples { get { return cur.Count + prev.Count; } }

        public double P99
        {
            get
            {
                double a = cur.Count >= 20 ? cur.Value : (cur.Count > 0 ? maxCur : 0);
                double b = prev.Count >= 20 ? prev.Value : (prev.Count > 0 ? maxPrev : 0);
                return Math.Max(a, b);
            }
        }
    }

    // How late each packet is versus a steady schedule (the earliest arrival seen defines "on time"), which
    // captures bursts: 200 ms of silence then 20 packets at once means the first of them was 190 ms late.
    public class LatenessTracker
    {
        readonly int packetMs;
        bool has;
        double baseMs;   // arrival time minus seq * packetMs of the earliest packet (relative), slowly re-anchored
        uint seq0;
        long lastReanchor;

        public LatenessTracker(int packetMs) { this.packetMs = packetMs; }

        public void Reset() { has = false; }

        // Returns how many ms later than the best-case schedule this packet arrived (>= 0).
        public double OnPacket(uint seq, long now)
        {
            if (!has) { has = true; seq0 = seq; baseMs = now; lastReanchor = now; return 0; }
            int d = unchecked((int)(seq - seq0));
            if (d < -1000 || d > 1000000) { seq0 = seq; baseMs = now; lastReanchor = now; return 0; }
            double expected = baseMs + d * (double)packetMs;
            double late = now - expected;
            if (late < 0) { baseMs += late; late = 0; }               // earlier than ever: new best case
            else if (now - lastReanchor > 10000)
            {
                // clocks drift a little (host vs. listener crystal): creep the anchor forward 1 ms per 10 s
                baseMs += Math.Min(1, late);
                lastReanchor = now;
            }
            return late;
        }
    }
}
