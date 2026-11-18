# Troubleshooting
<!-- keywords: not working, no sound, silent, can't connect, cannot connect, stutter, buffering, cutting, dropouts, choppy, delay, error, diagnostics, test tone, signal path, wrong key, host full, removed, chal nahi raha, awaz nahi aa rahi, connect nahi ho raha, atak raha, toot raha, kharab, masla -->

## Phones connect but there is no sound
The link works, so the problem is usually that the host isn't playing anything yet, the wrong output is captured, or the listener's volume is low. Check the host first, then the listener.
1. On the host, play something and make sure sharing is on (Windows: **Stop sharing** is shown on Home; Android host: **Stop sharing** is shown).
2. Windows host: Home > Source: **Whole laptop**, or the right app under **One app**; **From** should be **Windows default**.
3. On the listener, check **Volume** and the phone's media volume.
4. On the listener, check which earbuds are connected under **Earbuds on this phone**.
5. If the phone shows "No sound shared", the host has stopped sharing for now; sound starts again when it shares.

## Phone to phone sharing is silent
Android often doesn't pass the playing sound to Dual Earbuds until the music starts after sharing began. Pause the music and play it again, or start it after tapping **Start sharing**.
1. Tap **Start sharing** in Share this phone and allow Android's prompt.
2. Then start the music, or pause and play it again.
3. If "No sound for 5 s" appears, the app playing blocks sharing (Netflix and other protected apps); try YouTube or a music app.
4. Use the **Signal path** section: if **Captured** shows "no data from capture" or "silence", the problem is on the host phone.

## How do I use the test tone and Signal path on the host phone?
While sharing, Share this phone shows **Signal path** with live counters: **Captured** (packets per second and the peak level) and **Sent to listeners** (UDP and WebSocket packets per second). **Send a test tone** replaces this phone's sound with a steady beep.
1. Turn on **Send a test tone**.
2. If listeners hear the beep, the network is fine and the problem is capture: restart the music or pick another app.
3. If listeners don't hear the beep, the problem is the network or the listener: check Wi-Fi, hotspot and volume.
4. Turn the test tone off again.

## The phone can't connect to the laptop
"Nothing answered" means the phone can't reach the laptop: they are on different networks, the laptop isn't sharing, the firewall blocks it, or the Wi-Fi keeps devices apart. Work through these in order.
1. Make sure the laptop shows its link on the **Listeners** page and **Let devices listen** is on.
2. Put both on the same Wi-Fi; check Setup check > **Same network as the host** on the phone.
3. On the laptop, Setup check > **Firewall rule**: press **Add rule** or **Allow on public networks** if shown.
4. On public Wi-Fi, use the laptop's Mobile hotspot or a phone's hotspot.

## Why does the phone say "Wrong link key"?
The host made a new link (or reset its settings), so the old key no longer works. Scan the QR code on the laptop or the sharing phone again, or copy the new link.

## The sound stutters or keeps cutting out
Stutters come from lost Wi-Fi packets, a weak signal, battery saver or a background limit on the phone. Fix the network first, then the phone's power settings.
1. Use 5 GHz Wi-Fi or a hotspot, and keep listeners in the same room as it.
2. Check that listeners use **UDP**, not WebSocket.
3. Turn off battery saver and set the app's battery to Unrestricted on each phone.
4. Keep **Smart buffer** on Smart, or raise a Manual buffer.
5. On Windows, use Balanced or Best performance power mode.

## The sound is late or out of sync
Late sound comes from Bluetooth earbuds and Wi-Fi; uncalibrated earbuds make pairs drift apart. Calibrate every pair and pick the right sync priority.
1. Calibrate every pair (Windows: **Calibrate** on the output row; Android: Settings > **Calibrate earbuds**).
2. Use **Lip-sync** for movies on the host's screen, **Everyone together** for music.
3. On Android, choose AAC or SBC instead of LDAC.
4. Set any manual Delay or Extra delay back to 0.

## Why does it say "The host is full"?
The host already has its maximum number of listeners: 8, or 16 with Party mode on. Ask the host to turn on Party mode or remove a listener; a Windows listener tries again every 10 seconds by itself.

## Why was my phone removed and won't reconnect?
The host pressed **Remove** for this device. A removed listener does not rejoin by itself; join again when the host lets you.

## How do I copy diagnostics to report a problem?
Copy diagnostics puts a short report on the clipboard: versions, network, outputs or buffer and sync numbers, listeners and the last 200 log lines. It never contains your link key or PIN.
1. Windows: Settings > Help and data > **Diagnostics** > **Copy**.
2. Android: Settings > **Copy diagnostics**.
3. Paste it into your message or bug report.
**Windows:** the **Diagnostics** page > **Run check** lists what Windows allows on each output (nothing is played); **Copy**, **Save** and **Open logs** are next to it.

## Why does Windows show error 0x8889000F for my second earbuds?
Most laptop Bluetooth chips send stereo music to only one device at a time, and Windows refuses the second pair with this error. Let the second pair listen through a phone or another laptop, or add a USB Bluetooth adapter for it.

## Why doesn't the Windows shortcut Ctrl + Alt + M work?
Another app already uses Ctrl + Alt + M, or the shortcut is turned off. Check Settings > General > **Ctrl + Alt + M**; if another app owns the keys, use **Start sharing** on Home instead.
