# Smart features (on-device AI/ML/DSP)

Everything runs on the device. No cloud, no account, no API cost, no data leaves the device.
Every feature is **optional**, has a clear default, and offers choices where it is not a simple on/off.
They live together under **Settings → Smart features**, each with a one-line explanation.

| # | Feature | Where | Default | Choices |
|---|---|---|---|---|
| 1 | **Mic noise cancelling** for talk-over | Windows host, Android host | On (when talk-over is used) | Off · Light · Strong |
| 2 | **Smart EQ** — picks the EQ preset from what is playing | Windows (laptop outputs), Android listener | Off | Off · By app (Netflix/YouTube → Movie, Spotify → Music…) · By sound (classifier) · Both. A manual EQ choice always wins until the user picks Auto again. |
| 3 | **Dialogue boost** — clearer voices over music/effects | Windows (laptop outputs), Android listener | Off | Off · Light · Strong · Night mode (dialogue boost + gentle loudness levelling) |
| 4 | **Smart buffer** — learns this network's jitter, keeps delay as low as is safe | every listener (Android app, Windows listener mode) | Smart | Smart · Steady (fixed adaptive rules from protocol.md) · Manual (user sets ms) |
| 6 | **Hearing profile** — short hearing test, EQ tuned to the person | Windows (per laptop output), Android listener (per earbuds) | Off | Off · per-person profiles (create, retest, delete); applied per output/earbuds |

## Implementation rules

- **Lightweight.** No ML runtimes (no TFLite/ONNX/ML Kit). Models are tiny and pure code:
  - #1: RNNoise-style GRU denoiser ported to C#/Kotlin with its BSD weights (~85 KB), 48 kHz, 10 ms frames.
    "Light" = mix 50 % dry, "Strong" = full. If the port is not finished and tested, fall back to a
    spectral-gating noise suppressor with the same options and say so in code comments.
  - #2 "By sound": small built-in classifier (speech / music / movie-mix / silence) on 1 s windows using
    hand-computed features (spectral flatness, centroid, 4 Hz modulation energy, zero-crossing rate,
    low-band energy ratio) and a tiny fixed logistic/decision model; switch preset only after the same class
    wins 5 s in a row (hysteresis), crossfade EQ over 300 ms. "By app": process name (Windows) or the
    host's `source/app` field / media session package (Android).
  - #3: mid/side split; boost the centre channel's 300 Hz–4 kHz band only while a voice-activity detector
    says speech; Strong = more boost + slight side cut; Night = plus a gentle compressor/limiter. No clipping.
  - #4: track inter-arrival jitter with an online quantile estimator (P² algorithm, p99) over the last 60 s;
    target buffer = p99 jitter + 10 ms, clamped to protocol min/max; react to an underrun immediately (+20 ms),
    shrink slowly (−5 ms per 5 s). "Steady" = the protocol's adaptive rules unchanged.
  - #6: tones at 250, 500, 1k, 2k, 4k, 6k, 8 kHz per ear, quieter until the person taps "can't hear"
    (simple up-down staircase); turn thresholds into a gentle EQ (max +9 dB per band, never boost above a
    safe output level). Clear disclaimer: "Not a medical hearing test."
- All DSP is real-time safe: no allocations per frame, CPU < 3 % on a mid-range phone.
- Every pure-logic piece (classifier features, quantile estimator, staircase→EQ, VAD, gain math) is unit-tested.
- Settings persist; each feature can be turned off instantly without restarting playback.
