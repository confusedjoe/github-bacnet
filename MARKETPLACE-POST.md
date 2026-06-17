# Text für den openHAB Community Marketplace

So gehst du vor:
1. Lade die `.jar` als Datei an ein GitHub-**Release** an (siehe PUBLISH-Anleitung).
2. Gehe zu https://community.openhab.org → Kategorie **Add-ons → Marketplace → Bindings** → „+ New Topic".
3. Kopiere den Text unten hinein, ersetze den DOWNLOAD-LINK durch die URL deiner Release-`.jar`, und veröffentliche.

Wichtig: Der Marketplace erkennt das Add-on über den `.jar`-Link. Setze den Link
als eigene Zeile (nicht als verschachtelten Text), damit die Ein-Klick-Installation
funktioniert.

---

**Titel:** `[BACnet] BACnet/IP Binding (experimental)`

**Body:**

This is a lightweight, dependency-free **BACnet/IP** binding for openHAB 4.3.x.
It discovers BACnet devices (Who-Is / I-Am), reads and writes present values,
receives live updates via COV subscriptions, and forwards intrinsic alarms as
trigger channels. No external BACnet library is used (clean-room, EPL-compatible).

> ⚠️ **Experimental / work in progress.** Discovery and read/write are verified
> against a BACnet/IP simulator; please report your results with real hardware in
> this thread.
>
> **v0.2.0** fixes a critical bug where an always-on COV dispatch thread shared the
> UDP socket and silently ate every I-Am reply and service ACK — discovery and
> read/write now work while the bridge is online. Also adds an indexed object-list
> fallback (no segmentation needed) and Schedule present-value write.

## Supported Things
- `bacnet:bridge` – the local BACnet/IP network (owns the UDP socket, runs discovery)
- `bacnet:device` – a discovered BACnet device; its objects become channels

## Configuration (bridge)
- `broadcastAddress` (required) – subnet broadcast, e.g. `192.168.1.255`
- `localPort` (default `47808`)
- `discoveryTimeout` (seconds, default `5`)

Example (`things` file):
```
Bridge bacnet:bridge:local "BACnet/IP Network" [ broadcastAddress="192.168.1.255", localPort=47808, discoveryTimeout=5 ]
```

## Discovery
Create the bridge, then start a scan. Devices appear in the inbox. If the binding
does not appear in the UI binding picker (typical for community add-ons before
installation), start a scan from the console: `discovery start bacnet`.

## Known limitations
1. Partial segmentation: only the object-list is worked around (via indexed reads); other very large properties are not reassembled.
2. Calendar present-value is read-only (per ASHRAE 135); writing would require editing the Date_List.
3. Schedule write is numeric (REAL) only.

## Download
https://github.com/confusedjoe/github-bacnet/releases/download/v0.2.0/org.openhab.binding.bacnet-0.2.0.jar

Source code: https://github.com/confusedjoe/github-bacnet

Feedback, bug reports and pull requests welcome!
