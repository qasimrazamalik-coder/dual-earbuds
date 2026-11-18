# Privacy and security
<!-- keywords: privacy, security, safe, secure, key, link key, pin, room pin, lockout, cloud, account, data, recording, microphone, camera, location, hack, stranger, mehfooz, privacy, password, pin code, ajnabi -->

## Does Dual Earbuds send my sound or data to the cloud?
No. Everything runs on your own network or hotspot: there is no account, no cloud server and no analytics. The only internet request is the optional update check to GitHub.

## What is the secret key in the link?
Every link ends with an 8-character key, for example ?k=Q7RK2MXD, and the host refuses any request without the right key. The QR code holds the same link, so only people you give it to can listen.
1. Share the link or QR code only with your listeners.
2. If someone else has it, make a new one.
**Windows:** Listeners > **New link**. **Android host:** Join > **New link**. Everyone on the old link is disconnected and has to join again.

## How do I set a room PIN for a party?
In Party mode you can require a 4 to 6 digit PIN on top of the link. The PIN is never in the link or QR code; tell guests out loud.
1. Windows: Listeners > turn on **Party mode** > **Require a PIN**; type a PIN and press **Set**, or press **New** for a random one.
2. Android host: Share this phone > Party > **Party mode** > **Room PIN**; type 4 to 6 digits.
3. The Windows Listeners page shows the PIN large under **Room PIN** so you can read it out.
**Android host:** listeners already in stay connected when you turn the PIN on.

## What happens if someone guesses the key or PIN?
A device that sends 10 different wrong keys or PINs within 60 seconds is ignored for 5 minutes. Repeating one old key, such as a stale browser tab after New link, never triggers the lockout.

## Does Dual Earbuds record with my microphone?
No. The microphone is used only for calibration (to hear 8 clicks for a few seconds) and, on the host, for talk-over while you hold or turn it on. Nothing is recorded, kept or sent anywhere else.
**Android host:** Android counts copying other apps' sound as recording, so it asks for the audio permission; the mic stays off unless you use Talk-over.

## Why does the Android app ask for camera, Bluetooth or location?
Each permission has one job. The camera only reads the QR code, Bluetooth shows which earbuds are connected and uses their measured delay, and location is needed on some Android versions only to read the Wi-Fi band.
**Android:** you can skip any of them; paste the link instead of scanning, and the app still plays.

## Does Copy diagnostics include my key or PIN?
No. The diagnostics text never includes your link key or PIN, so you can paste it in a bug report. On Windows that covers Settings > Help and data > **Diagnostics** and the Diagnostics page report; on Android, **Copy diagnostics**.

## Can people on the internet reach my host?
No. The host only answers on your local network or hotspot and Dual Earbuds opens nothing to the internet. On the laptop, the Windows Firewall rule decides whether private only, or private and public networks, may reach it.
