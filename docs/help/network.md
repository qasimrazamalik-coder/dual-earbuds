# Network, Wi-Fi and hotspot
<!-- keywords: network, wifi, wi-fi, hotspot, mobile hotspot, 5 ghz, 2.4 ghz, router, public wifi, cafe, hotel, university, campus, firewall, port, 8790, udp, websocket, private network, internet, net, wifi kamzor, hotspot lagana, router, signal -->

## Do all devices need to be on the same Wi-Fi?
Yes. The host and every listener must be on the same Wi-Fi network, or on one device's hotspot, because the sound goes directly between them. They do not need the internet.
1. Connect every device to the same router network (same name, ideally the 5 GHz one).
2. Or turn on a hotspot on the host and connect the others to it.

## How do I use a hotspot when Wi-Fi doesn't work?
A hotspot puts all devices on one small network with nothing in between, and it doesn't use mobile data for the sound. Turn on the hotspot on the host, or on a phone, and connect the other devices to it.
1. Windows host: Settings > Network > **Mobile hotspot**, or press **Mobile hotspot settings** on the Listeners page when it appears.
2. Android host: turn on the phone's hotspot; guests join it, and Setup check shows "Hotspot on".
3. Connect every listener to that hotspot and join as usual.
**Windows:** if the laptop joins a phone's hotspot instead, that works too.

## Why does it not work on cafe, hotel or university Wi-Fi?
Many public networks keep devices apart, so phones can't reach the laptop even on the same Wi-Fi. Use a hotspot instead; the link's key still keeps strangers out.
1. On the laptop, the Listeners page shows "This is a public network" with a **Mobile hotspot settings** button.
2. Turn on the laptop's Mobile hotspot and connect the phones to it, or join the laptop to a phone's hotspot.
**Windows:** also allow public networks in Settings > **Networks listeners may use** > **Private and public networks**.

## Is 5 GHz Wi-Fi better than 2.4 GHz?
Yes. 2.4 GHz is crowded and shares the air with every pair of Bluetooth earbuds, so it drops more packets and everyone has to wait longer. Join the router's 5 GHz network if it has one, or share from a 5 GHz hotspot.
**Windows:** Setup check > **Wi-Fi band** shows the band and signal. **Android:** Setup check > **Wi-Fi band**; some Android versions need location access to show the band, used for nothing else.

## How do I allow Dual Earbuds through Windows Firewall?
Choose which networks listeners may use in Settings; Dual Earbuds then sets the Windows Firewall rule for TCP and UDP, and Windows asks for permission.
1. Settings > **Networks listeners may use**.
2. Choose **Private networks only** or **Private and public networks**.
3. Accept Windows' permission prompt.
Setup check > **Firewall rule** shows the state and offers **Add rule**, **Update rule** or **Allow on public networks** when something is missing.

## What is the difference between UDP and WebSocket?
Phones and laptops get the sound over UDP: 10 ms packets with the previous packet repeated, so one lost packet costs nothing. WebSocket is the fallback, and browsers always use it; it can stall briefly when Wi-Fi drops a frame.
1. Each listener row shows **UDP** or **WebSocket**; on Android see the **Connection** row.
2. If a native listener shows WebSocket, update the firewall rule on the laptop (Setup check > Firewall rule > **Update rule**).
**Windows:** a rule made by an older version lets listeners connect but sends audio over the slower WebSocket path.

## Which port does Dual Earbuds use?
The host uses port 8790 for both TCP and UDP by default; nearby discovery also uses UDP 5353. You can change the port if another app takes it.
1. Windows: Listeners page > Link > **Port**, type a number from 1024 to 65535, press **Apply**.
2. Android host: Share this phone > Network > **Port**; it applies the next time you start sharing.
**Windows:** "Not running: port 8790 is taken by another app (an older Dual Earbuds still open?)" means close the other app or pick another port.

## Why does the laptop say "This is a public network"?
Windows treats the current Wi-Fi as public, so other devices don't find the laptop automatically and some public Wi-Fi keeps devices apart. If the network is yours, mark it Private in Windows; otherwise use a hotspot.
1. Setup check > **Network type** > **Network settings**.
2. Set the network profile to Private (only for your own network).
3. Or allow public networks in Settings > **Networks listeners may use**.

## How far can listeners be from the router or hotspot?
Keep everyone in the same room as the router or hotspot. Walls and distance weaken Wi-Fi, and one weak phone makes everyone wait for it in Everyone together mode.
**Windows:** Setup check > **Listeners' Wi-Fi** names listeners whose network can't keep up. **Android host:** Setup check shows "Weak link" with their names.

## Can strangers on the same Wi-Fi listen to my sound?
Not without your link. Every link carries an 8-character secret key, and a device that guesses wrong keys is locked out. In Party mode you can also require a room PIN.
**Windows:** **Show on public networks** lists the laptop for nearby devices on public Wi-Fi; leave it off in cafes and hotels. **Android host:** leave **Show on this network** off on public Wi-Fi.
