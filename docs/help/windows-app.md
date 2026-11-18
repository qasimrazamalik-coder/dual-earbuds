# The Windows app
<!-- keywords: windows, laptop, pc, home, outputs, tray, hotkey, source, one app, start sharing, laptop app, computer, ghar page, outputs, tray icon, shortcut, ek app ki awaz -->

## What are the pages in the Windows app?
The Windows app has seven pages in the left rail: Home, Listeners, Listen, Setup check, Settings, Diagnostics and About. Home shares the laptop's sound, Listeners shows the link and QR code, and Listen plays another device's sound on this laptop.
1. **Home**: Start sharing, the sound source, sync priority, the laptop's outputs and who is listening.
2. **Listeners**: QR code, Copy link, Party mode, Room PIN, port, talk-over and how to join.
3. **Listen**: join another laptop or a phone and play its sound here.
4. **Setup check**: network, firewall, Wi-Fi band, earbuds, calibration and power, each with a fix.
5. **Settings**, **Diagnostics** and **About**: appearance, smart features, firewall scope, updates, reports and version.

## How do I start and stop sharing on the laptop?
Press **Start sharing** on Home; the button changes to **Stop sharing**. The shortcut Ctrl + Alt + M starts or stops sharing from any app.
1. Open **Home**.
2. Press **Start sharing**.
3. Press **Stop sharing** when you are done.
The headline shows "Sharing to N ears" while sound plays. Turn the shortcut on or off in **Settings > General > Ctrl + Alt + M**.

## How do I choose which laptop outputs play the sound?
Each output (Bluetooth earbuds, wired headphones, speakers) has a switch on Home under **Outputs**; tick the ones that should play. The count shows, for example, "2 of 3 on".
1. Pair or plug in the earbuds; they appear under **Outputs** by themselves.
2. Turn on the switch of each output you want.
3. Press **Test** on a row to hear a short beep on that output.
The output Windows plays through (the **From** device) is the source and plays without the shared delay, so its switch is locked.

## What do Delay, Volume and EQ on an output row do?
While an output is on, its row shows Delay (0 to 500 ms), Volume (0 to 150 %) and an EQ choice. Delay adds extra delay on top of the shared deadline and is usually 0; calibrate instead.
1. **Delay**: makes this output later by hand, in 5 ms steps.
2. **Volume**: this output's own level; above 100 % can distort.
3. **EQ**: flat, bass, voice or movie, or **EQ auto** when Smart EQ is on.
4. **Hearing**: a hearing profile for the person wearing these, if you made one.

## How do I share only one app's sound from Windows?
On Home under **Source**, set **Share** to **One app** and pick the app from the list. Only that app (with its child windows) is shared; calls and notifications stay on the laptop.
1. Start playing in the app you want to share.
2. Home > Source > Share > **One app**.
3. Pick the app under **App**; press the refresh icon if it isn't listed.
**Windows:** One app needs Windows 11, or Windows 10 build 20348 or later. On older Windows the whole laptop is shared instead.

## What is the "From" device on the Home page?
**From** is the output whose sound Dual Earbuds captures, normally **Windows default**. What that output plays is captured, and it plays without the shared delay because Windows plays it directly.
1. Home > Source > **From**.
2. Leave it on **Windows default** unless you want to capture a different output.
With **Lip-sync** priority, this output is the reference ear the others line up with.

## Does the sound keep playing when I close the window?
Yes, by default. Closing the window keeps Dual Earbuds running in the tray, so the sound keeps playing; right-click the tray icon to exit.
1. Settings > General > **Closing the window**: choose **Keep running** or **Exit**.
2. **Start minimized** opens to the tray icon only.
3. **Run when Windows starts** starts it in the tray after you sign in.
4. **Share when the app opens** starts sharing by itself.

## How do I change the theme of the Windows app?
Open Settings > Appearance > **Theme** and choose System, Light or Dark. System follows Windows' app mode and switches with it.

## How do I reset all settings on Windows?
Settings > Help and data > **Reset all settings** > **Reset**. The link key changes too, so everyone has to join again; calibrations, hearing profiles and recent devices are kept.
**Windows:** settings and logs live in %LOCALAPPDATA%\DualEarbuds; open it with **Open folder** under **Settings and logs**.

## Why does a Windows output show an error or won't open?
Some laptops let Windows send stereo sound to only one Bluetooth pair at a time, so a second pair may fail to open. The Home status line then says "Windows won't open ... : let it listen through a phone instead".
1. Turn that output off on Home.
2. Pair those earbuds with a phone instead and join the laptop from the phone.
3. Or add a USB Bluetooth adapter for the second pair.
Diagnostics > **Run check** lists what Windows allows on each output.
