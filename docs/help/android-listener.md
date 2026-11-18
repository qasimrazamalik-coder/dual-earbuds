# The Android app as a listener
<!-- keywords: android, phone, mobile, listener, join, nearby, scan qr, paste link, volume, equalizer, stop, mobile app, phone se sunna, join karna, qr scan, link paste, awaz kam zyada -->

## How do I join a laptop or phone from the Android app?
Open Dual Earbuds and pick the host under **Nearby**, tap **Scan QR**, or paste the link and tap **Join**. Playback starts as soon as the phone joins.
1. Connect the phone to the same Wi-Fi as the host, or to the host's hotspot.
2. On the host, start sharing (Windows app, or Share this phone on Android).
3. In the phone app, tap the host under **Nearby**, or tap **Scan QR** and point the camera at the code.
4. Or copy the link, tap **Paste link**, then **Join**.
**Android:** the camera is only used to read the QR code; you can paste the link instead.

## Why is the host missing from the Nearby list?
Nearby lists hosts that announce themselves on this network; public Wi-Fi often blocks that, and a laptop shows its key only on private networks. Scan the QR code or paste the link instead.
1. Check that the host is sharing and on the same Wi-Fi.
2. If the row says "needs the link or QR", tap it and choose **Scan QR** or **Paste link**.
3. On public Wi-Fi, use a hotspot (see the network help).
**Windows host:** turn on Settings > **Show on public networks** to list the laptop on public networks too.

## How do I stop listening on the phone?
Tap **Stop** on the now playing panel at the top of the main screen. You can also stop from the playing notification.
**Android:** you must stop playback before joining another host ("Stop playback to join another host").

## How do I change the volume on the Android listener?
Use the **Volume** slider under **Sound** (0 to 150 %), or the phone's own volume buttons. Above 100 % can distort loud sounds.
1. Main screen > **Sound** > **Volume**.
2. Tap **Phone media volume** to open the system volume panel.
In Party mode the host can also set your volume; the slider follows it.

## How do I change the equalizer on the phone?
Main screen > **Sound** > **Equalizer**: choose Auto, Flat, Bass, Voice or Movie. Auto follows the host's suggestion or Smart EQ; a manual choice stays until you pick Auto again.

## What do the numbers under the now playing panel mean?
While playing, the panel shows Sync, Connection, Host, Common deadline, This phone needs, Output delay and Buffer. Together they tell you whether this phone is in sync and why the sound is as late as it is.
1. **Sync**: this phone's offset from the common deadline on a plus or minus 50 ms scope; "in sync" is the goal.
2. **Connection**: UDP (best) or WebSocket.
3. **Common deadline**: when every ear plays a sound after the host captured it (P).
4. **This phone needs**: network jitter + earbuds delay + 10 ms, sent to the host.
5. **Output delay**: the earbuds' delay, calibrated or an estimate.
6. **Network**: lost, filled and gaps counts, shown only when packets were lost.

## How do I calibrate earbuds on the Android phone?
Hold one earbud against the phone's microphone and let the app play 8 clicks; it measures the earbuds' delay and saves it for those earbuds. Do it once per pair.
1. Main screen > **Earbuds on this phone** > **Calibrate**, or Settings > **Calibrate earbuds**.
2. Take one earbud out and hold it right against the microphone at the bottom edge.
3. Tap **Start** and keep it still for about 6 seconds.
4. Tap **Forget** to delete a measurement.
**Android:** the microphone is used only to hear the clicks; nothing is recorded, kept or sent.

## How do I control the host's music from my phone?
If the host turned on listener control, the now playing panel shows Previous, Play or pause, Next and host volume buttons. They press the matching media keys on the host.
**Windows host:** Listeners > **Listeners control playback**. **Android host:** Share this phone > Network > **Let listeners control playback**.

## Which earbuds does the phone play on?
The sound plays wherever the phone's audio goes: connected Bluetooth earbuds, wired headphones or the speaker. **Earbuds on this phone** shows the connected pair, its battery and its measured delay.
**Android 12 and newer:** tap **Allow** so the app can see which earbuds are connected and use their measured delay.

## What does the "Extra delay" setting on Android do?
Settings > **Extra delay** makes this phone later by hand, for example to match a speaker. It is never changed automatically; for normal sync, calibrate instead.
