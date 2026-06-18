# openHAB BACnet/IP Binding (community / experimental)

A lightweight, dependency-free **BACnet/IP** binding for openHAB 4.3.x.
It discovers BACnet devices (even ones that ignore Who-Is), reads and writes
present values, receives live updates via COV subscriptions, and forwards
intrinsic alarms as trigger channels.

> ⚠️ **Status: experimental / work in progress.**
> This binding was written from scratch (clean-room, no `bacnet4j`, EPL-compatible).
> Discovery, object-list read (bulk + indexed) and Read/WriteProperty are verified
> against a simulator **and** against a real controller with 223 objects that does
> not answer Who-Is. Still evolving — feedback and pull requests very welcome.

## Features

- BACnet/IP (Annex J) over UDP, no external BACnet library
- **Universal discovery**: Who-Is/I-Am **plus** a unicast subnet sweep and passive
  detection, so devices that never answer Who-Is are still found
- Channels are labelled with each object's real **object-name and unit** (e.g.
  `AU_Temp_H00 [°C]`), read from the device — like YABE, not cryptic ids
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

Create the bridge first, then start a scan. The scan combines three mechanisms so
it finds far more than plain Who-Is:

1. **Who-Is / I-Am** broadcast — the standard mechanism.
2. **Passive** — any IP that has sent the bridge any BACnet frame (e.g. a
   controller that broadcasts its own Who-Is) is probed.
3. **Active subnet sweep** — every host in the bridge's `/24` subnet is probed
   with a unicast `ReadProperty(device:4194303, object-identifier)` (the
   wildcard-instance trick). Devices that ignore broadcast Who-Is still answer
   this, so they are discovered with their real instance and IP.

Trigger a scan from the UI (*Settings → Things → + → BACnet Binding → Scan*) or
the console:

```
discovery start bacnet
```

### Manual device (no discovery needed)

Because discovered devices are addressed by IP, you can also define a device
directly — useful for a fixed installation:

```
Bridge bacnet:bridge:local "BACnet/IP Network" [ broadcastAddress="192.168.1.255" ] {
    Thing device altbau "Altbau" [ deviceInstance=1, address="192.168.1.88" ]
}
```

## Configuration

### Bridge (`bacnet:bridge`)

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `broadcastAddress` | yes | – | Network to use — pick from the drop-down of local interfaces (YABE-style), or type a subnet broadcast such as `192.168.1.255` |
| `localPort` | no | `47808` | UDP port to bind (BACnet default `0xBAC0`) |
| `discoveryTimeout` | no | `5` | Seconds to listen for I-Am replies per scan |

### Example (file-based, `conf/things/bacnet.things`)

```
Bridge bacnet:bridge:local "BACnet/IP Network" [ broadcastAddress="192.168.1.255", localPort=47808, discoveryTimeout=5 ]
```

## Changing the IP address / network

Devices are addressed by IP. To change a device's address — or the bridge's
network — edit `conf/things/bacnet.things` and save; openHAB reloads it
automatically.

```
Bridge bacnet:bridge:local "BACnet/IP Network" [ broadcastAddress="192.168.1.255", localPort=47808 ] {
    Thing device altbau "Altbau" [ deviceInstance=1, address="192.168.1.88" ]
}
```

- `address` — the device's IP; change this to repoint the device.
- `broadcastAddress` — the network used for discovery.

> ⚠️ **Changing the IP via the openHAB UI is not active yet.** When the binding is
> installed as a manually-dropped jar it does not appear in the UI's add/edit-thing
> flow, and file-defined things are read-only in the UI anyway. For now, change the
> IP in the `.things` file. UI-based configuration (including the network drop-down)
> is planned for when the binding is installed from the Marketplace/add-on store.

## Installation

**Option A – manual JAR (quickest):**
Download `org.openhab.binding.bacnet-0.5.1.jar` from the
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
`bundles/org.openhab.binding.bacnet/target/org.openhab.binding.bacnet-0.5.1.jar`.

## Technical documentation

For the protocol subset, the single-reader socket design, discovery internals,
the service layer and the build/test setup, see **[ARCHITECTURE.md](ARCHITECTURE.md)**.

## Changelog

### 0.5.1

- **Fix: binding did not register at all.** A `limitToOptions` attribute added to
  the bridge config in 0.4.0 is not accepted by openHAB's thing-type XSD, which
  made the whole `thing-types.xml` fail to parse — so no thing types or binding
  info were registered and the binding never appeared in *Choose Binding*. The
  attribute is removed; the interface drop-down still comes from the config option
  provider.

### 0.5.0

- **Readable channel labels.** When building a device's channels, the binding now
  reads each object's `object-name` (property 77) and engineering `units` (117) and
  labels the channel accordingly, e.g. `AU_Temp_H00 [°C]` — the same names YABE
  shows. The channel id stays `<type>_<instance>` so links remain stable.

### 0.4.0

- **Network interface picker.** The bridge's *Network / Broadcast Address* field is
  now a drop-down of this machine's network interfaces (like YABE), so you just pick
  the network the BACnet devices are on instead of typing a broadcast address. Custom
  values are still allowed.

### 0.3.0

- **Universal discovery.** In addition to Who-Is/I-Am, a scan now (a) probes every
  IP it has passively heard BACnet traffic from and (b) actively sweeps the bridge's
  `/24` subnet with a unicast wildcard-instance `ReadProperty`. Devices that never
  answer Who-Is (verified against a real 223-object controller) are now discovered
  with their real instance and IP.

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
4. **UI thing management is not active.** As a manually-dropped jar the binding
   does not show in the UI's add/edit-thing flow, so creating a bridge, changing a
   device IP or using the network drop-down must be done in the `.things` file for
   now (see *Changing the IP address / network*).

## License

Eclipse Public License 2.0 (EPL-2.0). See [LICENSE](LICENSE) and
[NOTICE](NOTICE).
