# Getting started
<!-- keywords: start, setup, first time, install, begin, how to use, two earbuds, dual audio, shuru, kaise chalaye, pehli dafa, install karna, do earbuds, ek awaz -->

## What does Dual Earbuds do?
Dual Earbuds plays one sound on two or more pairs of earbuds at the same time. A Windows laptop or an Android phone shares what it plays, and other phones, laptops and browsers on the same Wi-Fi or hotspot play it on their own earbuds, in sync.
1. The host (laptop or phone) captures its sound and sends it over Wi-Fi in 10 ms packets.
2. Each listener plays the sound on its own earbuds.
3. Everyone plays each packet at the same moment, so a movie or song stays together.
It is free, has no account and no cloud: the sound stays on your own network.

## What do I need to share my laptop's sound to more earbuds?
You need the Windows app on the laptop and, for every extra pair of earbuds, either another output on the laptop or a phone or laptop that listens. Most laptops send music to only one pair of Bluetooth earbuds at a time, so the second pair usually listens through a phone.
1. Laptop: Windows 10 or 11 with the Dual Earbuds Windows app.
2. Each extra pair: an Android phone with the Dual Earbuds app (Android 8 or newer), another Windows laptop with Dual Earbuds, or any browser, including iPhone.
3. All devices on the same Wi-Fi, or on one device's hotspot.

## How do I install Dual Earbuds on Windows?
Download DualEarbuds-Setup.exe from the project's GitHub releases page and run it. It installs for your user only, so it does not need admin rights.
1. Download DualEarbuds-Setup.exe.
2. Run it and follow the installer.
3. Open Dual Earbuds from the Start menu.
**Windows:** the app is under 1 MB and uses .NET Framework 4.8, which is built into Windows.

## How do I install Dual Earbuds on an Android phone?
Download DualEarbuds.apk on the phone from the project's GitHub releases page and install it. Android asks once to allow installing apps from your browser or file manager.
1. Open the download link on the phone.
2. Tap the downloaded DualEarbuds.apk.
3. If Android asks, allow "Install unknown apps" for that app, then tap Install.
**Android:** listening works on Android 8 and newer; Share this phone needs Android 10 or newer.

## How do I play my laptop's sound on two pairs of earbuds?
Connect the first pair to the laptop, let the second pair listen through a phone, then press Start sharing on the laptop.
1. Pair the first earbuds in Windows Bluetooth settings and play something on the laptop.
2. In Dual Earbuds on the laptop, press **Start sharing** on Home (or press Ctrl + Alt + M).
3. On the phone, pair the second earbuds with the phone and open Dual Earbuds.
4. On the phone, tap the laptop under **Nearby**, or tap **Scan QR** and scan the code on the laptop's **Listeners** page.
5. Calibrate each pair once for exact sync (hold one earbud to the microphone and tap **Calibrate**).

## How does a phone join the laptop?
A phone joins with the link: pick the laptop under Nearby, scan the QR code, or paste the link. Joining starts playback on the phone.
1. Put the phone on the same Wi-Fi as the laptop, or on the laptop's hotspot.
2. On the laptop, open **Listeners** to see the QR code and the link.
3. In the phone app, tap the laptop under **Nearby**, or tap **Scan QR**, or copy the link and tap **Paste link** then **Join**.
**Android:** tap **Stop** on the phone to stop listening.

## How many earbuds can listen at once?
Up to 8 listeners can join a host, or 16 with Party mode on, plus the laptop's own outputs. Each listener is one phone, laptop or browser with its own earbuds.
**Windows:** turn on **Party mode** on the **Listeners** page. **Android:** turn on **Party mode** in Share this phone.

## Does Dual Earbuds need the internet or mobile data?
No. The sound goes directly between your devices over Wi-Fi or a hotspot, so it uses no internet and no mobile data. Only the optional update check talks to GitHub.

## Which app do I use as host and which as listener?
The device that plays the movie or music is the host; every other device is a listener. A Windows laptop is a host on its Home page and a listener on its Listen page; an Android phone is a listener on its main screen and a host in Share this phone.
**Windows:** host = **Home** and **Listeners** pages; listener = **Listen** page. **Android:** listener = main screen; host = **Share this phone**.
