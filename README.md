# openHAB BACnet/IP Binding (community / experimental)

A lightweight, dependency-free **BACnet/IP** binding for openHAB 4.3.x.
It discovers BACnet devices via Who-Is / I-Am, reads and writes present values,
receives live updates via COV subscriptions, and forwards intrinsic alarms as
trigger channels.

> ⚠️ **Status: experimental / work in progress.**
> This binding was written from scratch (clean-room, no `bacnet4j`, EPL-compatible).
> Discovery, object-list read (bulk + indexed), ReadProperty and WriteProperty are
> verified end-to-end against a BACnet/IP simulator; broad testing against real
> hardware is still ongoing. Use it for evaluation and testing. Feedback and pull
> requests are very welcome.

## Features

- BACnet/IP (Annex J) over UDP, no external BACnet library
- Device discovery (Who-Is / I-Am) into the openHAB inbox
- Read/write of present values: analog, binary and multi-state objects
- Live updates via COV subscriptions (instead of pure polling)
- Intrinsic alarms (event-state changes) exposed as trigger channels
- Schedule present value read **and write** (override); Calendar present value read

## Supported Things

| Thing | Type | Description |
|-------|------|-------------|
| `bacnet:bridge` | Bridge | The local BACnet/IP network; owns the UDP socket and runs discovery |
| `bacnet:device` | Thing | A discovered BACnet device; its objects become channels |

## Discovery

Create the bridge first, then start a scan. The bridge broadcasts a Who-Is and
adds every responding device to the inbox.

Because this binding is installed manually (not yet from the official add-on
store), the scan is most reliably triggered from the openHAB console:

```
discovery start bacnet
```

## Configuration

### Bridge (`bacnet:bridge`)

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `broadcastAddress` | yes | – | Subnet broadcast address, e.g. `192.168.1.255` |
| `localPort` | no | `47808` | UDP port to bind (BACnet default `0xBAC0`) |
| `discoveryTimeout` | no | `5` | Seconds to listen for I-Am replies per scan |

### Example (file-based, `conf/things/bacnet.things`)

```
Bridge bacnet:bridge:local "BACnet/IP Network" [ broadcastAddress="192.168.1.255", localPort=47808, discoveryTimeout=5 ]
```

## Installation

**Option A – manual JAR (quickest):**
Download `org.openhab.binding.bacnet-0.2.0.jar` from the
[Releases](../../releases) page and drop it into your openHAB `addons` folder.

**Option B – openHAB Community Marketplace:**
Install directly from *Settings → Add-on Store → Community Marketplace* once a
marketplace post exists (see `MARKETPLACE-POST.md`).

## Building from source

Requires JDK 17 and Maven. Build inside an `openhab-addons` checkout (branch
matching your openHAB version, e.g. tag `4.3.11`):

```
git clone --depth 1 --branch 4.3.11 https://github.com/openhab/openhab-addons.git
# copy this binding into openhab-addons/bundles/org.openhab.binding.bacnet
# add <module>org.openhab.binding.bacnet</module> to bundles/pom.xml
cd openhab-addons
mvn clean install -pl :org.openhab.binding.bacnet -am -DskipTests
```

The resulting bundle is at
`bundles/org.openhab.binding.bacnet/target/org.openhab.binding.bacnet-0.2.0.jar`.

## Changelog

### 0.2.0

- **Fixed: discovery and read/write were broken whenever the bridge was online.**
  A background COV/event dispatch thread shared the UDP socket with discovery and
  with synchronous requests, and silently consumed every I-Am reply and service
  ACK. The socket now has a **single reader** that routes each frame (COV/event →
  listeners, I-Am → discovery, ACK → the waiting request by invoke id). This was
  the root cause of empty scans against real hardware.
- **Object-list segmentation fallback.** If the bulk object-list read fails or is
  aborted (e.g. a large list that needs segmentation), the binding now reads the
  list element by element via the array index, which never needs segmentation.
- **Schedule present value is now writable** (numeric override).

### 0.1.0

- Initial experimental release.

## Known limitations

1. **Partial segmentation support.** Large responses are only worked around for
   the object-list (via indexed reads). Other very large properties that require
   true APDU segmentation are still not reassembled.
2. **Calendar present value is read-only** — this is per ASHRAE 135 (the value is
   derived from the date-list). Writing a calendar would require editing its
   `Date_List`, which is not yet implemented.
3. **Schedule write is numeric only** (REAL present value); non-numeric schedule
   datatypes are not encoded for writing.

## License

Eclipse Public License 2.0 (EPL-2.0). See [LICENSE](LICENSE) and
[NOTICE](NOTICE).
