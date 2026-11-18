# Sync and delay
<!-- keywords: sync, delay, latency, lag, late, behind, lip-sync, lip sync, everyone together, calibrate, calibration, P, playout, deadline, bluetooth delay, game mode, low latency, ldac, aac, sbc, codec, netflix, echo, der, late, peeche, awaz late, hont, sync nahi, aage peeche -->

## Why is the sound behind the picture?
Every pair of earbuds and every Wi-Fi hop adds delay, so sound on a phone listener arrives later than on the laptop showing the video. With **Lip-sync** priority the laptop's own sound matches the picture and phones may be slightly later.
1. Set **Sync priority** to **Lip-sync** on the host (default).
2. Calibrate every pair of earbuds, including the laptop's source pair.
3. Use 5 GHz Wi-Fi or a hotspot, and keep listeners in the same room.
4. On Android phones, pick AAC or SBC instead of LDAC.
5. If the earbuds have a game or low-latency mode, turn it on and calibrate again.

## What is the difference between Lip-sync and Everyone together?
**Lip-sync** keeps the host's own sound on time with the picture, and other ears line up with it; phones on slow Wi-Fi may play a little later. **Everyone together** gives every ear the same deadline, so the laptop's sound waits for the slowest phone.
1. Windows: Home > Source > **Sync priority**: **Lip-sync** or **Everyone together**.
2. Android host: Share this phone > Sync priority: **Lip-sync** or **Together**.
Choose Lip-sync for movies watched on the host's screen, and Everyone together for music or when nobody watches the host's screen.

## What is P and why does it change?
P is the shared deadline: every ear plays a sound P milliseconds after the host captured it. The host picks P from what the slowest listener needs, between 30 and 400 ms, and shows it as "P 92 ms" in the header and next to the listener count.
1. P rises at once when a listener needs more time.
2. P falls slowly, at most 5 ms per second, so you hear no jumps.
3. A listener with weak Wi-Fi or slow earbuds raises P for everyone in Everyone together mode.

## How do I calibrate earbuds for exact sync?
Calibration measures each pair's own delay with the microphone: hold one earbud against the mic, and the app plays 8 clicks and finds them. Do it once per pair; the value is saved by the earbuds' name.
1. Windows: Home > the output's row > **Calibrate**, hold one earbud to the laptop microphone in a quiet room, press OK and keep still for about 7 seconds.
2. Android: Settings > **Calibrate earbuds** (or **Calibrate** under Earbuds on this phone), hold one earbud to the bottom microphone, tap **Start**.
3. Recalibrate after an earbuds firmware update.
**Windows:** right-click **Calibrate** and choose **Forget the measurement** to delete it. With Lip-sync, calibrating the source output lines every other ear up with the picture.

## Why do Bluetooth earbuds add so much delay?
Bluetooth earbuds buffer sound before playing it, typically more than 100 ms, and the amount differs per model by up to 100 ms. Without calibration Dual Earbuds assumes 180 ms for Bluetooth and 20 ms for wired, which is why calibration matters.
When a measured delay is above 150 ms the app says: "These earbuds add N ms. If they have a game or low-latency mode (often in the earbuds' own app or a triple tap), turn it on and calibrate again."

## Should I use AAC or SBC instead of LDAC on my phone?
Yes, for watching video together. LDAC sounds detailed but adds 100 to 200 ms of delay; AAC or SBC keep the delay lower.
1. Turn on Developer options on the phone (tap Build number 7 times in About phone).
2. Developer options > **Bluetooth audio codec** > AAC or SBC.
3. Calibrate the earbuds again.

## Why do phones lag behind the laptop?
A phone's sound has to cross Wi-Fi and then go to its Bluetooth earbuds, so it needs more time than the laptop's own output. With Lip-sync the laptop stays on time with the picture and phones play later by the difference; with Everyone together the laptop waits for the phones.
1. Look at the listener's row on the host: "needs N ms" shows what it asks for.
2. Calibrate the phone's earbuds and switch the phone to AAC or SBC.
3. Move the phone closer to the router or hotspot, or use 5 GHz.

## How do I fix lip-sync for Netflix on the laptop?
Play Netflix in the laptop's browser, keep **Sync priority** on **Lip-sync**, and calibrate the laptop's source earbuds. Netflix compensates for the source output's delay, and the other laptop outputs line up with it.
1. Home > Source > **Sync priority** > **Lip-sync**.
2. Calibrate the source output (the one Windows plays through) and every other output.
3. Listeners on phones may still be slightly later; see the AAC/SBC and 5 GHz tips.

## What does the sync scope on each row show?
The sync scope is a small plus or minus 50 ms scale on every ear's row, with a mark for how far that ear is from the shared deadline. A mark at 0 means in sync; a plus value means late.
**Android:** the phone's own scope is the **Sync** row; "not synced" means it plays on the adaptive buffer until it learns the host's clock.

## When should I use the manual Delay setting?
Rarely. Delay makes one output or phone later by hand, for example to match a room speaker, and is never changed automatically. For normal sync, calibrate instead and leave Delay at 0.
**Windows:** the **Delay** slider on an output row (0 to 500 ms). **Android:** Settings > **Extra delay**.

## Why is there an echo between two pairs of earbuds?
An echo means two ears are a few tens of milliseconds apart, usually because one pair isn't calibrated or its codec changed. Calibrate both pairs and check the sync scope on each row.
1. Calibrate every pair, including the host's.
2. On Android, check the codec is still AAC or SBC.
3. Set any manual Delay back to 0.
