<div align="center">

<img src="assets/logo/mark-128.png" width="96" alt="Dual Earbuds logo">

# Dual Earbuds

**Play one sound on two or more pairs of earbuds at once — from your Windows laptop or your Android phone.**

Watch a movie together, share music on a trip, run a silent party. Free, open source, no account, no cloud.

[Download for Windows](https://github.com/qasimrazamalik-coder/dual-earbuds/releases/latest/download/DualEarbuds-Setup.exe) ·
[Download for Android](https://github.com/qasimrazamalik-coder/dual-earbuds/releases/latest/download/DualEarbuds.apk) ·
[How it works](#how-it-works) · [FAQ](#faq)

</div>

---

## Why Dual Earbuds?

Windows and most phones send stereo sound to **only one pair of Bluetooth earbuds at a time**.
Dual Earbuds gets around that the simple way: it plays the sound on every output your laptop can
reach, and streams it over **Wi-Fi or a hotspot** to phones, each with its own earbuds.

- 🎧 **Two, three, ten pairs.** Laptop earbuds + any number of phones (up to 16 in Party mode).
- 🎬 **Works with Netflix, YouTube, Spotify, games** — anything your laptop plays.
- 📱 **Phone-to-phone.** Share your Android phone's music or YouTube with friends' phones (Android 10+).
- 🔒 **Private by design.** Local network only; every link has a secret key; devices that guess keys get locked out.
- ⚡ **Low latency.** 10 ms audio packets and an adaptive buffer from 30 ms, plus per-device delay to line everyone up.
- 🌙 **Keeps playing with the screen off** on Xiaomi, Redmi, POCO, Realme, OPPO, Samsung and others.
- 🔋 **Shows earbuds battery** where the device reports it.
- 🔄 **Updates itself** from GitHub releases, only when you say yes.
- 🪶 **Lightweight.** Windows app under 1 MB, nothing extra to install (uses .NET Framework 4.8 built into Windows).

## Screenshots

| Home | Phones & Party mode |
|---|---|
| ![Dual Earbuds Windows app home screen mirroring sound to two earbuds](docs/images/home-dark.png) | ![Phones page with QR code to connect phones over Wi-Fi](docs/images/phones-dark.png) |
| **Settings** | **Light theme** |
| ![Settings with updates and network options](docs/images/settings-dark.png) | ![Light theme](docs/images/home-light.png) |

<details>
<summary>Installer screens</summary>

| | | |
|---|---|---|
| ![](docs/images/setup-1-welcome.png) | ![](docs/images/setup-3-options.png) | ![](docs/images/setup-6-repair-modify-uninstall.png) |

</details>

## How it works

```
             ┌───────────────── Wi-Fi / hotspot ─────────────────┐
 Laptop ──► Dual Earbuds (Windows) ──► phone 1 ──► earbuds pair 2
   │                                 └─► phone 2 ──► earbuds pair 3
   └──► earbuds pair 1 (Bluetooth)        …up to 16 phones
```

1. The Windows app captures what the laptop is playing (WASAPI loopback) and plays it on every output you tick.
2. Phones scan a QR code and receive the same sound over the local network ([protocol](docs/protocol.md)).
3. A per-device **Delay** lines up earbuds that lag; the phone's adaptive buffer keeps it smooth.

## Get started

### Windows
1. Download **[DualEarbuds-Setup.exe](https://github.com/qasimrazamalik-coder/dual-earbuds/releases/latest/download/DualEarbuds-Setup.exe)** and run it (no admin rights needed).
2. Connect your earbuds in Windows Bluetooth settings, play something, tick the outputs, press **Start** (or **Ctrl + Alt + M**).

### Android
1. Download **[DualEarbuds.apk](https://github.com/qasimrazamalik-coder/dual-earbuds/releases/latest/download/DualEarbuds.apk)** on the phone and install it (allow "install unknown apps" once).
2. Pair your earbuds with the phone, scan the QR code on the laptop's **Phones** page, tap **Start**.

### Party mode
Turn on **Party mode** on the host (laptop or phone): up to 16 listeners, each with their own volume. Guests can join from the app or straight from the browser.

### Outside home (café, hotel, university Wi-Fi)
It works on public Wi-Fi too. If a phone can't reach the laptop, that network blocks devices from seeing each other — use a **hotspot** (phone hotspot, or Windows **Mobile hotspot**). The sound goes directly between the devices and **doesn't use mobile data**.

## FAQ

**Can Windows play to two Bluetooth earbuds at once by itself?**
Most laptop Bluetooth chips send stereo music to only one device at a time (Windows error `0x8889000F` for the second). Dual Earbuds uses a phone, or a second USB Bluetooth adapter, for every extra pair.

**Does it work with Netflix?**
Yes, when Netflix plays on the laptop. Netflix on an Android phone blocks other apps from capturing its sound, so phone-to-phone sharing can't include it — on Samsung phones use built-in **Dual Audio** (Quick panel → Media output → tick both earbuds) instead.

**Is there an iPhone app?**
Not yet. iPhones can listen through the browser player today (it pauses when the screen turns off).

**Does it need the internet?**
No. Only the optional update check uses the internet.

## Build from source

| Part | Stack | Build |
|---|---|---|
| Windows app + installer | C# 5, WPF, WASAPI, .NET Framework 4.8 (built into Windows) | `powershell -File windows\build.ps1` |
| Android app | Kotlin, Jetpack Compose, AudioTrack, AudioPlaybackCapture | `gradle -p android assembleRelease` |
| Shared | [Link protocol v3](docs/protocol.md), [browser player](shared/web-player.html) | — |

CI builds, tests and publishes both apps on every tagged release.

```
dual-earbuds/
├── windows/   Windows app (src/Engine, src/App, src/Setup), tests, build script
├── android/   Android app (app/) and phone-to-phone host module (host/)
├── shared/    browser player page served by every host
├── docs/      protocol, screenshots
└── assets/    logo
```

## License

[MIT](LICENSE) © 2026 [Qasim Raza Malik](https://github.com/qasimrazamalik-coder) · Contact: iamqasimrazamalik@gmail.com

<sub>Keywords: play audio on two bluetooth headphones windows, connect two earbuds to one laptop, share audio to multiple phones, dual audio android, silent disco app, listen together over wifi.</sub>
