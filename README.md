# openHAB BACnet/IP Binding (community / experimental)

A lightweight, dependency-free **BACnet/IP** binding for openHAB 4.3.x.
It discovers BACnet devices via Who-Is / I-Am, reads and writes present values,
receives live updates via COV subscriptions, and forwards intrinsic alarms as
trigger channels.

> ⚠️ **Status: experimental / work in progress.**
> This binding was written from scratch (clean-room, no `bacnet4j`, EPL-compatible)
> and has **not yet been tested against real BACnet hardware**. Use it for
> evaluation and testing. Feedback and pull requests are very welcome.

## Features

- BACnet/IP (Annex J) over UDP, no external BACnet library
- Device discovery (Who-Is / I-Am) into the openHAB inbox
- Read/write of present values: analog, binary and multi-state objects
- Live updates via COV subscriptions (instead of pure polling)
- Intrinsic alarms (event-state changes) exposed as trigger channels
- Read access to Schedule and Calendar present values

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
Download `org.openhab.binding.bacnet-0.1.0.jar` from the
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
`bundles/org.openhab.binding.bacnet/target/org.openhab.binding.bacnet-0.1.0.jar`.

## Known limitations

These are documented and intentionally open in this first version:

1. **No segmentation.** Automatic object-list discovery can fail on devices with
   many data points (large object lists that exceed a single APDU).
2. **Shared UDP socket.** The COV listener and synchronous read/write requests
   share one UDP socket; under load, responses may race.
3. **Schedule / Calendar are read-only.** Only present values are read; weekly
   schedules and date lists cannot be written.

## License

Eclipse Public License 2.0 (EPL-2.0). See [LICENSE](LICENSE) and
[NOTICE](NOTICE).
