# BACnet/IP Binding — Technical Documentation

This document describes the internal design of the openHAB BACnet/IP binding:
the BACnet/IP protocol subset it implements, the socket architecture, discovery,
the service layer, the thing model, and how to build and test it.

It is a **clean-room** implementation: no `bacnet4j` or other GPL code is used,
so the bundle stays EPL-2.0 compatible. Only the parts of ANSI/ASHRAE 135 that
the binding actually needs are implemented.

---

## 1. Module overview

All code lives under `org.openhab.binding.bacnet.internal`.

```
internal/
├── BACnetBindingConstants.java     thing-type UIDs and config keys
├── BACnetHandlerFactory.java       creates bridge/device handlers
├── BACnetConfigOptionProvider.java network-interface drop-down for the bridge
├── discovery/
│   └── BACnetDiscoveryService.java universal discovery (who-is + passive + sweep)
├── handler/
│   ├── BACnetBridgeHandler.java    owns the UDP socket; one per BACnet network
│   └── BACnetDeviceHandler.java    one BACnet device; builds channels, polls, COV
└── protocol/
    ├── BACnetIpClient.java         the single-reader UDP transport
    ├── BACnetServices.java         confirmed services (Read/Write/SubscribeCOV/obj-list)
    ├── BACnetCodec.java            ASN.1 tag encode/decode (Clause 20.2)
    ├── BACnetEnums.java            object types, properties, units, event states
    ├── PropertyValue.java          a decoded, typed property value
    ├── BACnetCovNotification.java  parses pushed COV notifications
    ├── BACnetEventNotification.java parses intrinsic alarm notifications
    └── BACnetScheduleCalendar.java helpers for Schedule/Calendar present-value
```

Layering: `handler` (openHAB things) → `protocol` (BACnet/IP). The protocol
layer has no openHAB-core dependency except the JDT null annotations and slf4j,
which makes it unit-testable in isolation (see §9).

---

## 2. BACnet/IP framing (Annex J)

Every datagram is `BVLC | NPDU | APDU`.

### BVLC (BACnet Virtual Link Control) — 4 bytes
| Offset | Field | Value |
|--------|-------|-------|
| 0 | Type | `0x81` (BACnet/IP) |
| 1 | Function | `0x0A` Original-Unicast-NPDU, `0x0B` Original-Broadcast-NPDU |
| 2–3 | Length | total frame length, big-endian |

### NPDU (Network Protocol Data Unit)
| Field | Notes |
|-------|-------|
| Version | `0x01` |
| Control | bit 3 (`0x08`) = source present, bit 5 (`0x20`) = destination present, bit 2 (`0x04`) = expecting reply |

The binding sends:
- **Who-Is**: broadcast BVLC `0x0B`, NPDU control `0x00`.
- **Confirmed requests**: unicast BVLC `0x0A`, NPDU control `0x04` (expecting reply).

On receive, `BACnetIpClient.unwrap()` strips BVLC + NPDU — including any
destination (DNET/DLEN/DADR) and source (SNET/SLEN/SADR) specifiers and the hop
count — and returns the bare APDU. This is what lets the binding correctly read
I-Am replies that arrived through a BACnet router.

### APDU (Application PDU)
First byte high nibble = PDU type:
| Nibble | PDU type |
|--------|----------|
| `0x00` | Confirmed-Request |
| `0x10` | Unconfirmed-Request (Who-Is, I-Am, COV/Event notifications) |
| `0x20` | SimpleACK |
| `0x30` | ComplexACK |
| `0x50` | Error |
| `0x60` | Reject |
| `0x70` | Abort |

---

## 3. ASN.1 codec (`BACnetCodec`)

Implements the tag encoding of Clause 20.2:

- **Application tags** carry the application tag number in the high nibble
  (Real=4, Unsigned=2, Enumerated=9, CharacterString=7, ObjectId=12, …).
- **Context tags** set bit 3 and carry a context number.
- **Length/Value/Type field**: lengths ≤ 4 are inline; `5` signals extended
  length (1, 3 or 5 following bytes); `6`/`7` on a context tag mean opening /
  closing tag of a constructed value.

`Reader` is a small cursor (`data`, `pos`, `end`); `readTag()` returns a `Tag`
(number, context flag, opening/closing flags, length). Helpers decode unsigned,
real, object-id and character strings; encoders build the request APDUs.

---

## 4. Transport: the single-reader socket (`BACnetIpClient`)

This is the heart of the binding and the subject of the most important bug fix.

### The problem (pre-0.2.0)
The bridge started a background "dispatch" thread to receive pushed COV/event
notifications, **and** discovery and every confirmed request also called
`socket.receive()` on the *same* `DatagramSocket`. With two or more threads
blocked in `receive()`, an inbound packet is delivered to **exactly one** of
them, non-deterministically. The dispatch thread frequently won, saw a packet
that was not a COV/event notification (an I-Am or a service ACK), and **silently
discarded it**. Net effect: discovery returned nothing and read/write timed out
whenever the bridge was online.

### The design (0.2.0+)
There is now **exactly one** thread that ever calls `receive()` — `dispatchLoop()`.
It classifies each frame and routes it:

```
receive() → unwrap() → APDU
   ├── COV notification        → covListeners          (BACnetCovNotification.parse)
   ├── Event/alarm             → eventListeners        (BACnetEventNotification.parse)
   ├── Unconfirmed I-Am        → iamQueue              (drained by receiveIAm)
   └── ACK/Error/Reject/Abort  → pending[invokeId]     (handed to the waiting caller)
```

Key members:
- `pending : ConcurrentHashMap<Integer, BlockingQueue<byte[]>>` — one slot per
  in-flight confirmed request, keyed by **invoke id**.
- `iamQueue : LinkedBlockingQueue<IamFrame>` — I-Am replies for the current scan
  (cleared at the start of each `sendWhoIs()`).
- `seenSources : Set<String>` — every source IP ever received, for passive
  discovery (§6).

A confirmed request is issued with `sendConfirmedAndAwait(target, apdu, invokeId,
timeoutMs)`: it registers an `ArrayBlockingQueue(1)` under the invoke id, sends
the unicast frame, then blocks on `queue.poll(timeout)`. The reader thread drops
the matching reply into that queue. Invoke ids are 8-bit and allocated
round-robin (`nextInvokeId()`), which is enough because the number of concurrent
requests is bounded (poll loop + discovery thread-pool).

Because there is a single reader, there is no longer any contention on
`SocketTimeoutException`, no lost packets, and COV + request/response coexist
safely. This also fixed read/write, not just discovery.

---

## 5. Confirmed services (`BACnetServices`)

Thin builders/parsers on top of `BACnetIpClient.sendConfirmedAndAwait`:

| Method | Service | Notes |
|--------|---------|-------|
| `readProperty` | ReadProperty (12) | decodes Real/Unsigned/Enum/Bool/String |
| `writeReal` | WriteProperty (15) | analog present-value, optional priority |
| `writeEnumerated` | WriteProperty (15) | binary/multi-state present-value |
| `subscribeCov` | SubscribeCOV (5) | unconfirmed notifications, lifetime 0 = indefinite |
| `readObjectList` | ReadProperty (76) | bulk **with indexed fallback** (§5.1) |
| `readDeviceObjectId` | ReadProperty (75) | wildcard-instance probe (§6) |
| `readObjectName` | ReadProperty (77) | used for channel labels (§7) |

### 5.1 Object-list segmentation fallback
A device's `object-list` (property 76) can be far larger than one APDU. The
binding does **not** implement BACnet segmentation; instead `readObjectList`:

1. **Fast path** — one ReadProperty of the whole list (`readObjectListBulk`).
2. If that returns empty or an Abort, **indexed fallback** (`readObjectListIndexed`):
   - ReadProperty `object-list[0]` → the element **count** (unsigned).
   - ReadProperty `object-list[i]` for `i = 1..count` → one object-id each.

Each indexed reply is tiny, so segmentation is never needed. Verified against a
real controller with **223 objects** (which Aborts the bulk read).

---

## 6. Discovery (`BACnetDiscoveryService`)

A scan combines three mechanisms — this is what makes it find more than YABE's
plain Who-Is:

1. **Who-Is / I-Am** — `sendWhoIs()` (global broadcast), then `receiveIAm()`
   drains the `iamQueue` for `discoveryTimeout` seconds.
2. **Passive** — `client.getSeenSources()` returns every IP that has sent the
   bridge *any* BACnet frame (e.g. a controller broadcasting its own Who-Is).
3. **Active subnet sweep** — for a `/24` broadcast address, every host
   `x.y.z.1 … x.y.z.254` is probed.

For every passive/sweep candidate IP a worker (fixed pool of 32) sends
`readDeviceObjectId(ip)` — a unicast `ReadProperty(device:4194303,
object-identifier)`. **4194303 (0x3FFFFF) is the wildcard device instance**:
devices answer it over unicast even when they ignore broadcast Who-Is. The reply
yields the device's *real* instance; `readObjectName` then gives a label. Results
are de-duplicated by instance and reported with their IP, so the resulting Thing
works purely over unicast.

> This is why a controller that never answers Who-Is is still discovered and
> usable.

**Background mode (0.6.0+):** the service extends `AbstractThingHandlerDiscoveryService`
with background discovery enabled. `startBackgroundDiscovery()` schedules
`startScan()` shortly after the bridge comes online and then every
`discoveryInterval` minutes, and registers a *new-source listener* on the client.
`BACnetIpClient.dispatchLoop` fires that listener the first time it sees any frame
from a given IP (`seenSources.add(...)` returns `true`), so a newly-appearing
device is probed immediately rather than waiting for the next interval. Bridge
config: `backgroundDiscovery` (on/off) and `discoveryInterval` (minutes).

---

## 7. Thing model & handlers

### Bridge (`bacnet:bridge`, `BACnetBridgeHandler`)
Owns one `BACnetIpClient` (one UDP socket per BACnet network). Config:

| Parameter | Default | Meaning |
|-----------|---------|---------|
| `broadcastAddress` | – | subnet broadcast / network to use (drop-down, §8) |
| `localPort` | 47808 | UDP bind port (`0xBAC0`) |
| `discoveryTimeout` | 5 | seconds to listen for I-Am per scan |

`initialize()` opens the socket (which starts the single reader) and goes ONLINE.
`dispose()` closes it. The broadcast address is also exposed for the discovery
sweep.

### Device (`bacnet:device`, `BACnetDeviceHandler`)
Config: `deviceInstance` (required) and `address` (IP). Because both can be set
in a `.things` file, a device can be defined **manually without discovery** — the
standard way for a fixed installation.

On init it:
1. Reads the object-list (§5.1).
2. For each object builds a **value channel** and a companion **alarm trigger
   channel**, labelling the value channel with the object's `object-name` and
   engineering unit, e.g. `AU_Temp_H00 [°C]`. The channel **id** stays
   `<type>_<instance>` (e.g. `ai_1101`) so links are stable regardless of label.
3. Subscribes to COV for each object (live updates).
4. Polls all channels every 30 s as a fallback.

Channel type mapping (`channelTypeFor`):
| BACnet object | Channel type | Item |
|---------------|--------------|------|
| Analog In | `analogReadonly` | Number (read-only) |
| Analog Out/Value | `analogValue` | Number (writable) |
| Binary In | `contactReadonly` | Switch (read-only) |
| Binary Out/Value | `switchValue` | Switch (writable) |
| Multi-State * | `multiStateValue` | Number |
| Schedule | `analogValue` | Number (writable present-value) |
| Calendar | `contactReadonly` | Switch (read-only per spec) |

Writes are routed in `handleCommand`: `DecimalType`→`writeReal` for analog/schedule,
`OnOffType`→`writeEnumerated` for binary, `DecimalType`→`writeEnumerated` for
multi-state.

### Live updates & alarms
- **COV** notifications (`BACnetCovNotification`) update the matching channel's
  state immediately (`onCov`).
- **Event** notifications (`BACnetEventNotification`) fire the object's alarm
  trigger channel with the new event state (`onEvent`).

---

## 8. Network-interface picker (`BACnetConfigOptionProvider`)

A `ConfigOptionProvider` that fills the bridge's `broadcastAddress` parameter with
a drop-down of this machine's network interfaces (YABE-style). It enumerates
`NetworkInterface.getNetworkInterfaces()`, skips loopback/down/link-local
(`169.254.*`) interfaces, and for each IPv4 `InterfaceAddress` offers the derived
subnet broadcast as the value with a label like
`192.168.1.255 (WLAN — 192.168.1.23/24)`.

> Note: option providers only surface in the **UI** add-thing flow. A
> file-defined (`.things`) bridge simply sets the value directly.

---

## 9. Build & test

- **Toolchain**: JDK 17 (openHAB 4.3.x), Maven. Build inside an `openhab-addons`
  checkout matching your openHAB version (e.g. tag `4.3.11`):
  ```
  mvn clean install -pl :org.openhab.binding.bacnet -am -DskipTests
  ```
  The artifact is `target/org.openhab.binding.bacnet-<version>.jar`.
  > openHAB's JDT compiler enforces `@NonNullByDefault` strictly — e.g.
  > `DatagramPacket.getAddress()` is `@Nullable`; the build will reject passing it
  > where non-null is required.

- **Protocol-layer testing without openHAB**: the `protocol` package depends only
  on the JDT null annotations + slf4j, so it can be compiled standalone with tiny
  stubs and exercised against a software BACnet device. The repository's test
  rig drives discovery, object-list read (bulk and indexed), ReadProperty and
  WriteProperty end-to-end with the single reader running.

- **Single-host gotcha**: openHAB binds wildcard `0.0.0.0:47808`. A simulator on
  the same host must bind a *specific* IP and be addressed by that IP, otherwise
  the wildcard socket steals the unicast replies. Test tools bind a different
  local port (e.g. 47809) so they never clash with a running openHAB.

---

## 10. Known limitations

1. **Partial segmentation** — only the object-list is worked around (indexed
   reads). Other very large properties needing true APDU segmentation are not
   reassembled.
2. **Calendar present-value is read-only** — per ASHRAE 135 it is derived from
   the `Date_List`; writing a calendar would require editing that list.
3. **Schedule write is numeric only** (REAL present-value).
4. **Engineering-unit symbols** are mapped for the common cases (°C, K, °F, %);
   other units add no suffix to the channel label.

---

## 11. Version history

| Version | Highlights |
|---------|-----------|
| 0.6.1 | Fix: background discovery is now actually started (from `initialize()`) |
| 0.6.0 | Automatic background discovery (initial + periodic scan, immediate probe of new IPs) |
| 0.5.1 | Fix: removed an unsupported `limitToOptions` attribute that broke thing-type registration |
| 0.5.0 | Readable channel labels from `object-name` + units |
| 0.4.0 | Network-interface picker in bridge config |
| 0.3.0 | Universal discovery (Who-Is + passive + unicast wildcard sweep) |
| 0.2.0 | Single-reader socket (fixes discovery + read/write); object-list indexed fallback; Schedule write |
| 0.1.0 | Initial experimental release |
