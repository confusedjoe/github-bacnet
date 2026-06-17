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

> ⚠️ **Experimental / work in progress.** Not yet tested against real BACnet
> hardware. Please report your results in this thread.

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
1. No segmentation – object-list discovery can fail on devices with very large object lists.
2. COV listener and read/write share one UDP socket – responses may race under load.
3. Schedule / Calendar are read-only.

## Download
<!-- Diese Zeile durch die echte Release-URL ersetzen: -->
https://github.com/DEIN-BENUTZERNAME/openhab-bacnet-binding/releases/download/v0.1.0/org.openhab.binding.bacnet-0.1.0.jar

Source code: https://github.com/DEIN-BENUTZERNAME/openhab-bacnet-binding

Feedback, bug reports and pull requests welcome!
