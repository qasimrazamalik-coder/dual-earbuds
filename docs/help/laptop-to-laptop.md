# Laptop to laptop, and phone to laptop
<!-- keywords: laptop to laptop, phone to laptop, listen page, listen to another device, second laptop, pc to pc, join by link, recent, dusra laptop, phone se laptop, laptop par sunna, listen -->

## How do I play one laptop's sound on another laptop?
Install Dual Earbuds on both laptops. Share on the first laptop, then on the second laptop open **Listen** and join the first one.
1. Laptop 1: Home > **Start sharing**.
2. Laptop 2: open the **Listen** page.
3. Under **On this network**, press **Join** next to laptop 1, or paste its link under **Join by link** and press **Join**.
4. Under **Play on this laptop**, tick the outputs (earbuds, speakers) that should play.
**Windows:** the link is on laptop 1's **Listeners** page (**Copy link**).

## How do I play my phone's sound on a laptop?
Share from the phone with Share this phone, then join it from the laptop's **Listen** page. The laptop plays the phone's sound on its own earbuds or speakers.
1. Phone: main screen > **Share this phone** > **Start sharing**.
2. Laptop: open **Listen**; the phone appears under **On this network** when its **Show on this network** setting is on.
3. Otherwise copy the link on the phone (**Copy link** or **Share**), paste it under **Join by link** and press **Join**.

## Why does the other laptop say "needs its link"?
A host that is on a public network, or has its "show" setting off, does not announce its key, so the Listen page can find it but can't join it alone. Paste its link (or scan its QR code) instead.
1. On the host, copy the link from its **Listeners** page (laptop) or **Join** section (phone).
2. On this laptop, paste it under **Listen > Join by link** and press **Join**.

## How do I enter a room PIN when joining from a laptop?
Join as usual; when the host needs a PIN, the Listen page says "This room needs a PIN" and shows a **Room PIN** box under **Join by link**. Type the PIN and press Enter or **Join**. The host tells you the PIN; it is never in the link.
**Windows:** "That PIN didn't work" means it was wrong; check it with the host and try again. Too many wrong tries lock this laptop out for 5 minutes.

## How do I stop listening on a laptop and share again?
Press **Stop listening** on the Listen page. While a laptop listens to another device it can't share its own sound, so **Start sharing** on Home is greyed out until you stop.

## Which outputs play the sound when a laptop listens?
The outputs you tick under **Play on this laptop** on the Listen page. Each output keeps its Delay, volume, EQ and calibration from Home, and calibrated outputs play on the host's deadline.

## How do I rejoin a device I listened to before?
The Listen page keeps the last 5 devices under **Recent**; press **Join** next to one. If the host made a new link since then, the old one no longer works and you need the new link.

## What do the numbers on the Listen page mean?
While listening, the Listen page shows the host kind, UDP or WebSocket, the sample rate, P (the shared deadline), what this laptop needs, round-trip time, and counts of lost, filled and recovered packets and dropouts. A few lost packets are normal; many dropouts mean a weak network.
**Windows:** "recovered" counts packets restored from the repeated copy that UDP sends with every packet.

## Can I use the host's EQ suggestion on the laptop?
Yes. When the host suggests an EQ, the Listen page shows "The host suggests the ... EQ" with **Use it** and **No thanks**. Nothing changes until you press **Use it**.
