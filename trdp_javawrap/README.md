# trdp-java

Java wrapper for **TCNOpen TRDP Light** (v3.0.0.0) built on top of [JNA](https://github.com/java-native-access/jna).  
Provides idiomatic Java access to the full TRDP PD + MD + statistics API without writing any JNI code.

Two API levels are available:

| Level | Package | Analogy |
|-------|---------|---------|
| **Template API** (recommended) | `io.trdp.template` | Spring Kafka `KafkaTemplate` / `KafkaListenerContainer` |
| **Session API** (low-level) | `io.trdp` | Raw Kafka producer / consumer clients |

---

## Requirements

| Dependency | Version |
|------------|---------|
| Java       | 11+     |
| JNA        | 5.14.0  |
| Maven      | 3.6+    |
| libtrdp    | 3.0.0.0 (shared or static-turned-shared) |

---

## Building

```bash
mvn package
```

This produces two JARs under `target/`:

| Artifact | Contents |
|----------|----------|
| `trdp-java-0.1.0.jar` | wrapper classes only |
| `trdp-java-0.1.0-shaded.jar` | wrapper + JNA bundled (fat-jar, recommended) |

### Building the native library

The wrapper loads `libtrdp` at runtime via JNA.  
On Linux, build the shared library from the TRDP sources:

```bash
# 1. Build the static library
cd TRDP/3.0.0.0
make LINUX_X86_64_config
make -j

# 2. Turn the static library into a shared library
cd bld/output/<platform>
gcc -shared -o libtrdp.so -Wl,--whole-archive libtrdp.a -Wl,--no-whole-archive -lpthread
```

Place `libtrdp.so` somewhere on `LD_LIBRARY_PATH` (e.g. `/usr/local/lib`), or pass the directory with `-Djna.library.path=/path/to/dir` at runtime.

On Windows, build `trdp.dll` with MSVC and ensure it is on `PATH`.

---

## Quick start

```java
import io.trdp.TrdpSession;
import io.trdp.PdInfo;

// try-with-resources: session is automatically closed
try (TrdpSession s = new TrdpSession("10.0.1.1")) {

    // Publish comId=1000 to a multicast group every 100 ms
    var pub = s.publish(1000, "239.192.0.0", 100_000);

    // Subscribe to comId=2000 from any source, 500 ms timeout
    var sub = s.subscribe(2000, "0.0.0.0", 500_000);

    // Start background polling thread (10 ms period)
    s.startProcessingThread(10);

    // Update payload
    s.put(pub, "hello".getBytes());

    Thread.sleep(1000);

    // Poll once manually
    Object[] result = s.get(sub);
    if (result != null) {
        PdInfo info = (PdInfo) result[0];
        byte[] data = (byte[]) result[1];
        System.out.printf("received %d bytes from %s%n", data.length, info.srcIp);
    }
} // closes session automatically
```

---

## Package structure

```
io.trdp/
├── TrdpSession.java          High-level session API (main entry point)
├── TrdpLibrary.java          Raw JNA mapping of the C API (low-level)
├── TrdpErrCode.java          Enum of all TRDP_ERR_T error codes
├── TrdpException.java        RuntimeException wrapping a TRDP error code
├── PdInfo.java               Metadata from a received PD telegram
├── MdInfo.java               Metadata from a received MD telegram
├── Statistics.java           Session / per-sub / per-pub statistics
└── template/
    ├── TrdpTemplate.java     Fluent template — creates typed publishers & subscribers
    │   ├── PublisherBuilder<T>   Builder for TrdpPublisher<T>
    │   └── SubscriberBuilder<T>  Builder for TrdpSubscriber<T>
    ├── TrdpPublisher<T>.java     Typed PD publisher handle
    ├── TrdpSubscriber<T>.java    Typed PD subscriber handle (push + pull)
    ├── TrdpMdTemplate.java       MD operations with CompletableFuture support
    ├── MdListenerHandle.java     AutoCloseable MD listener handle
    ├── TrdpSerializer<T>.java    Serializer interface + bytes() / string() factories
    └── TrdpDeserializer<T>.java  Deserializer interface + bytes() / string() factories
```

---

## API reference

### `TrdpSession`

The main entry point. Implements `AutoCloseable`.

#### Constructors

```java
// Minimal: uses library name "trdp", autoInit=true
TrdpSession(String ownIp)

// Full control
TrdpSession(String ownIp, String leaderIp, String libName, boolean autoInit)
```

- **`ownIp`** — own IP address in dotted-decimal notation (`"10.0.1.1"`).
- **`leaderIp`** — redundancy leader IP; pass `"0.0.0.0"` when not using redundancy.
- **`libName`** — native library name as passed to `Native.load` (e.g. `"trdp"` resolves to `libtrdp.so` on Linux).
- **`autoInit`** — when `true`, `tlc_init` / `tlc_terminate` are called automatically on open/close. Set to `false` when multiple sessions share one process and you call `tlc_init` yourself.

The constructor calls `open()` immediately.

---

#### Lifecycle

| Method | Description |
|--------|-------------|
| `open()` | Open the session (called automatically by the constructor). |
| `close()` | Close the session, stop any background thread, release handles. |
| `reinit()` | Re-initialise the session (`tlc_reinitSession`). |
| `update()` | Update session parameters (`tlc_updateSession`). |

---

#### Event loop

TRDP requires a regular call to `tlc_getInterval` + `tlc_process` to drive timers and cyclic PD sends.

```java
// Single iteration — blocks for up to timeoutMs milliseconds
void processOnce(int timeoutMs)

// Start a ScheduledExecutorService daemon thread
void startProcessingThread(int periodMs)

// Stop it
void stopProcessingThread()
```

> **Note:** `processOnce` on Java does not perform a real `select()` on OS file descriptors (no portable API for raw fd-sets in Java without JNI/Panama). Instead it sleeps for the smaller of `timeoutMs` and the TRDP timer interval, then calls `tlc_process(handle, null, count)`. This is sufficient for driving cyclic PD transmission and timers, but socket-event-driven wakeups are not supported. Use the Python wrapper for full `select()` support.

---

#### Topology

```java
void setEtbTopoCount(int count)
int  getEtbTopoCount()

void setOpTrainTopoCount(int count)
int  getOpTrainTopoCount()
```

---

#### Process Data — publish

```java
// Minimal: no initial payload, default flags
Pointer publish(int comId, String destIp, int intervalUs)

// With initial payload
Pointer publish(int comId, String destIp, int intervalUs, byte[] data)

// Full overload
Pointer publish(int comId, String destIp, int intervalUs,
                byte[] data, String srcIp,
                int serviceId, int redId,
                int etbTopoCnt, int opTrnTopoCnt,
                byte pktFlags,
                BiConsumer<PdInfo, byte[]> callback)
```

- **`comId`** — communication identifier (application-defined, must match subscriber).
- **`destIp`** — destination IP, unicast or multicast (`"239.192.0.0"`).
- **`intervalUs`** — publishing period in microseconds (e.g. `100_000` = 100 ms).
- **`redId`** — redundancy group ID; `0` = no redundancy.
- **`pktFlags`** — bitmask of `FLAGS_*` constants; `FLAGS_DEFAULT` (0x00) for defaults.
- **`callback`** — `BiConsumer<PdInfo, byte[]>` invoked on each outgoing packet; `null` if unused. Executed on the processing thread.

Returns an opaque `Pointer` handle. Pass it to `put`, `putImmediate`, `republish`, or `unpublish`.

```java
// Update payload (sent on next cycle)
void put(Pointer pubHandle, byte[] data)

// Update payload and send immediately
void putImmediate(Pointer pubHandle, byte[] data)

// Update topology / source / destination of a live publisher
void republish(Pointer pubHandle, String srcIp, String destIp,
               int etbTopoCnt, int opTrnTopoCnt)

// Stop publishing and release the handle
void unpublish(Pointer pubHandle)
```

---

#### Process Data — subscribe

```java
// Minimal: any source, given timeout
Pointer subscribe(int comId, String srcIp1, int timeoutUs)

// Full overload
Pointer subscribe(int comId, String srcIp1, int timeoutUs,
                  String srcIp2, String destIp,
                  int serviceId,
                  int etbTopoCnt, int opTrnTopoCnt,
                  int pktFlags, int toBehavior,
                  BiConsumer<PdInfo, byte[]> callback)
```

- **`srcIp1`** — accepted source IP; `"0.0.0.0"` = any.
- **`srcIp2`** — second source filter (range or alternate); `"0.0.0.0"` = not used.
- **`destIp`** — multicast group to join; `"0.0.0.0"` for unicast.
- **`timeoutUs`** — receive timeout in microseconds; `0` = infinite.
- **`toBehavior`** — action on timeout: `TO_DEFAULT`, `TO_SET_TO_ZERO`, or `TO_KEEP_LAST_VALUE`.
- **`callback`** — `BiConsumer<PdInfo, byte[]>` invoked on each received packet; `null` if unused.

Returns an opaque `Pointer` handle.

```java
// Read the most recently received PD data.
// Returns Object[]{ PdInfo, byte[] } or null when no data yet (NODATA_ERR).
Object[] get(Pointer subHandle)              // default buffer 1500 bytes
Object[] get(Pointer subHandle, int maxSize)

// Update topology / addresses of a live subscriber
void resubscribe(Pointer subHandle, String srcIp1, String srcIp2,
                 String destIp, int etbTopoCnt, int opTrnTopoCnt)

// Unsubscribe and release the handle
void unsubscribe(Pointer subHandle)

// Send a pull-request (PD pull mode)
void pdRequest(Pointer subHandle, int comId, String destIp,
               byte[] data, String srcIp,
               int serviceId, int redId,
               int etbTopoCnt, int opTrnTopoCnt,
               byte pktFlags,
               int replyComId, String replyIp)
```

---

#### Process Data — redundancy

```java
// Set this session as leader (true) or follower (false) for redId
void    setRedundant(int redId, boolean leader)

// Returns true if this session is currently leader for redId
boolean getRedundant(int redId)
```

---

#### Message Data (MD)

> MD functions require the native library to be compiled with `MD_SUPPORT=1`.

```java
// One-way notification (no reply expected)
void mdNotify(int comId, String destIp, byte[] data,
              BiConsumer<MdInfo, byte[]> callback)          // convenience

void mdNotify(int comId, String destIp, byte[] data,
              String srcIp, int etbTopoCnt, int opTrnTopoCnt,
              byte pktFlags, int qos, int ttl,
              String srcUri, String destUri,
              BiConsumer<MdInfo, byte[]> callback)          // full

// Request / reply pattern — returns 16-byte session UUID
byte[] mdRequest(int comId, String destIp, byte[] data,
                 BiConsumer<MdInfo, byte[]> callback)       // convenience

byte[] mdRequest(int comId, String destIp, byte[] data,
                 String srcIp, int etbTopoCnt, int opTrnTopoCnt,
                 byte pktFlags, int numReplies, int replyTimeoutUs,
                 int qos, int ttl,
                 String srcUri, String destUri,
                 BiConsumer<MdInfo, byte[]> callback)       // full

// Send a reply (from the replying side)
void mdReply(byte[] sessionId, int comId, byte[] data)               // convenience
void mdReply(byte[] sessionId, int comId, byte[] data,
             int userStatus, int qos, int ttl, String srcUri)        // full

// Send a reply that requires a confirmation
void mdReplyQuery(byte[] sessionId, int comId, byte[] data,
                  int userStatus, int confirmTimeoutUs,
                  int qos, int ttl, String srcUri)

// Confirm receipt of a reply
void mdConfirm(byte[] sessionId, int userStatus, int qos, int ttl)

// Abort an in-progress MD session
void mdAbort(byte[] sessionId)

// Add / update / remove an MD listener
Pointer mdAddListener(int comId, BiConsumer<MdInfo, byte[]> callback)  // convenience
Pointer mdAddListener(int comId, BiConsumer<MdInfo, byte[]> callback,
                      String srcIp1, String srcIp2, String mcDestIp,
                      int etbTopoCnt, int opTrnTopoCnt,
                      byte pktFlags, boolean comIdListener,
                      String srcUri, String destUri)                    // full

void mdReaddListener(Pointer lisHandle, String srcIp, String srcIp2,
                     String mcDestIp, int etbTopoCnt, int opTrnTopoCnt)

void mdDelListener(Pointer lisHandle)
```

---

#### Statistics

```java
// Global session statistics
Statistics getStatistics()

// Per-subscription statistics (up to maxCount entries)
List<Statistics.SubsStats> getSubsStatistics(int maxCount)

// Per-publisher statistics (up to maxCount entries)
List<Statistics.PubStats>  getPubStatistics(int maxCount)

// Multicast group addresses joined by this session
List<String> getJoinAddresses(int maxCount)

// Reset all counters
void resetStatistics()
```

---

#### Utility

```java
// IP conversion helpers (static)
static String ipToString(int addr)    // uint32 → "a.b.c.d"
static int    ipToInt(String ip)      // "a.b.c.d" → uint32

// Own IP as reported by the stack
String getOwnIp()

// TRDP library version string (static, requires a TrdpLibrary instance)
static String versionString(TrdpLibrary lib)
```

---

### `TrdpErrCode`

Enum mapping all `TRDP_ERR_T` values from `trdp_types.h`.

```java
TrdpErrCode code = TrdpErrCode.fromCode(-5);  // → NODATA_ERR
int value = TrdpErrCode.SOCK_ERR.getValue();   // → -6
```

| Constant | Code | Meaning |
|----------|------|---------|
| `NO_ERR` | 0 | Success |
| `PARAM_ERR` | -1 | Invalid parameter |
| `INIT_ERR` | -2 | Library not initialised |
| `NOINIT_ERR` | -3 | Session not initialised |
| `TIMEOUT_ERR` | -4 | Timeout |
| `NODATA_ERR` | -5 | No data available yet |
| `SOCK_ERR` | -6 | Socket error |
| `IO_ERR` | -7 | I/O error |
| `MEM_ERR` | -8 | Memory allocation failure |
| `SEMA_ERR` | -9 | Semaphore error |
| `QUEUE_ERR` | -10 | Queue error |
| `QUEUE_FULL_ERR` | -11 | Queue full |
| `MUTEX_ERR` | -12 | Mutex error |
| `THREAD_ERR` | -13 | Thread error |
| `BLOCK_ERR` | -14 | Would block |
| `INTEGRATION_ERR` | -15 | Integration / config error |
| `NOCONN_ERR` | -16 | No connection |
| `NOSESSION_ERR` | -30 | No session |
| `SESSION_ABORT_ERR` | -31 | Session aborted |
| `NOSUB_ERR` | -32 | No subscription |
| `NOPUB_ERR` | -33 | No publisher |
| `NOLIST_ERR` | -34 | No listener |
| `CRC_ERR` | -35 | CRC mismatch |
| `WIRE_ERR` | -36 | Wire / serialisation error |
| `TOPO_ERR` | -37 | Topology counter mismatch |
| `COMID_ERR` | -38 | Unknown ComID |
| `STATE_ERR` | -39 | Invalid state |
| `APP_TIMEOUT_ERR` | -40 | Application timeout |
| `APP_REPLYTO_ERR` | -41 | Reply timeout (application) |
| `APP_CONFIRMTO_ERR` | -42 | Confirm timeout (application) |
| `REPLYTO_ERR` | -43 | Reply timeout |
| `CONFIRMTO_ERR` | -44 | Confirm timeout |
| `REQCONFIRMTO_ERR` | -45 | Request-confirm timeout |
| `PACKET_ERR` | -46 | Malformed packet |
| `UNRESOLVED_ERR` | -47 | Unresolved address |
| `XML_PARSER_ERR` | -48 | XML configuration parse error |
| `INUSE_ERR` | -49 | Resource in use |
| `MARSHALLING_ERR` | -50 | Marshalling error |
| `UNKNOWN_ERR` | -99 | Unknown error |

---

### `TrdpException`

`RuntimeException` thrown by `TrdpSession` whenever a TRDP API call returns a non-zero code.

```java
try {
    s.put(pub, data);
} catch (TrdpException e) {
    System.err.println(e.getErrCode());   // TrdpErrCode enum
    System.err.println(e.getCode());      // raw int
}
```

Fields: `getCode()` → `int`, `getErrCode()` → `TrdpErrCode` (may be `null` for unrecognised codes).

---

### `PdInfo`

Immutable metadata snapshot from a received PD telegram (`TRDP_PD_INFO_T` subset).  
Returned as the first element of the `Object[]` from `get()`, or passed to PD callbacks.

| Field | Type | Description |
|-------|------|-------------|
| `srcIp` | `String` | Source IP address (dotted-decimal) |
| `destIp` | `String` | Destination IP address |
| `comId` | `int` | Communication identifier |
| `seqCount` | `long` | Sequence counter (wraps at 2^32) |
| `msgType` | `int` | TRDP message type code |
| `etbTopoCnt` | `long` | ETB topology counter |
| `opTrnTopoCnt` | `long` | Operational-train topology counter |
| `replyComId` | `long` | Reply ComID (pull requests; 0 otherwise) |
| `replyIp` | `String` | Reply IP (pull requests; `"0.0.0.0"` otherwise) |
| `resultCode` | `int` | `TRDP_ERR_T` result code for this telegram |
| `serviceId` | `long` | Service ID (service-oriented use; 0 otherwise) |

---

### `MdInfo`

Immutable metadata snapshot from a received MD telegram (`TRDP_MD_INFO_T` subset).  
Passed to MD callbacks.

| Field | Type | Description |
|-------|------|-------------|
| `srcIp` | `String` | Source IP address |
| `destIp` | `String` | Destination IP address |
| `comId` | `int` | Communication identifier |
| `seqCount` | `long` | Sequence counter |
| `msgType` | `int` | TRDP message type code |
| `etbTopoCnt` | `long` | ETB topology counter |
| `opTrnTopoCnt` | `long` | Operational-train topology counter |
| `sessionId` | `byte[16]` | 16-byte session UUID (`TRDP_UUID_T`) |
| `resultCode` | `int` | Result code for this telegram |
| `userStatus` | `int` | Application status code |
| `replyStatus` | `int` | Reply status |
| `aboutToDie` | `boolean` | Session is closing |
| `numReplies` | `long` | Replies received so far |
| `numExpReplies` | `long` | Expected number of replies |
| `srcUserUri` | `String` | Source user URI |
| `destUserUri` | `String` | Destination user URI |
| `replyTimeoutUs` | `long` | Reply timeout in microseconds |

---

### `Statistics`

Returned by `TrdpSession.getStatistics()`. All fields are `public final`.

```java
Statistics s = session.getStatistics();
System.out.printf("up %ds  pd-sent %d  pd-rcv %d%n",
    s.upTimeSec, s.pdNumSent, s.pdNumReceived);
```

| Field | Type | Description |
|-------|------|-------------|
| `version` | `long` | Library version number |
| `upTimeSec` | `long` | Session uptime in seconds |
| `statisticTimeSec` | `long` | Time since last reset in seconds |
| `ownIp` | `String` | Session own IP |
| `leaderIp` | `String` | Redundancy leader IP |
| `numRedundancyGroups` | `long` | Number of active redundancy groups |
| `numJoinedMcGroups` | `long` | Number of joined multicast groups |
| `pdNumSubs` | `long` | Active PD subscriptions |
| `pdNumPub` | `long` | Active PD publishers |
| `pdNumSent` | `long` | Total PD packets sent |
| `pdNumReceived` | `long` | Total PD packets received |
| `pdNumTimeout` | `long` | PD receive timeouts |
| `pdNumCrcErrors` | `long` | PD CRC errors |
| `udpMdNumSent` | `long` | UDP MD packets sent |
| `udpMdNumReceived` | `long` | UDP MD packets received |
| `udpMdNumTimeout` | `long` | UDP MD timeouts |
| `tcpMdNumSent` | `long` | TCP MD packets sent |
| `tcpMdNumReceived` | `long` | TCP MD packets received |

#### `Statistics.SubsStats`

Per-subscription entry returned by `getSubsStatistics()`.

| Field | Type | Description |
|-------|------|-------------|
| `comId` | `int` | Communication identifier |
| `joinedAddr` | `String` | Multicast group joined (`"0.0.0.0"` for unicast) |
| `filterAddr` | `String` | Source filter address |
| `timeoutUs` | `long` | Configured timeout in microseconds |
| `status` | `int` | Current status code |
| `numReceived` | `long` | Packets received |
| `numMissed` | `long` | Packets missed (timeout events) |

#### `Statistics.PubStats`

Per-publisher entry returned by `getPubStatistics()`.

| Field | Type | Description |
|-------|------|-------------|
| `comId` | `int` | Communication identifier |
| `destAddr` | `String` | Destination IP |
| `cycleUs` | `long` | Publishing period in microseconds |
| `redId` | `long` | Redundancy group ID |
| `redState` | `long` | Redundancy state (`0` = follower, `1` = leader) |
| `numPut` | `long` | Total `put()` calls |
| `numSent` | `long` | Total packets actually sent |

---

## Constants

Defined as `public static final` fields on `TrdpSession`:

### Packet flags (`TRDP_FLAGS_T`)

| Constant | Value | Description |
|----------|-------|-------------|
| `FLAGS_DEFAULT` | `0x00` | Use session defaults |
| `FLAGS_NONE` | `0x01` | No special flags |
| `FLAGS_MARSHALL` | `0x02` | Enable TRDP marshalling |
| `FLAGS_CALLBACK` | `0x04` | Deliver data via callback |
| `FLAGS_TCP` | `0x08` | Use TCP for MD |
| `FLAGS_FORCE_CB` | `0x10` | Force callback on every received packet |

### Timeout behaviour (`TRDP_TO_BEHAVIOR_T`)

| Constant | Value | Description |
|----------|-------|-------------|
| `TO_DEFAULT` | `0` | Use session default behaviour |
| `TO_SET_TO_ZERO` | `1` | Zero the data buffer on timeout |
| `TO_KEEP_LAST_VALUE` | `2` | Keep the last valid value on timeout |

### Redundancy

| Constant | Value | Description |
|----------|-------|-------------|
| `RED_FOLLOWER` | `0` | Do not send redundant PD |
| `RED_LEADER` | `1` | Send redundant PD |

---

## Advanced: `TrdpLibrary`

`TrdpLibrary` is the raw JNA mapping of the C API.  It is `public` but its use is not normally required: `TrdpSession` covers all common operations.

Use `TrdpLibrary` directly only if you need a function that `TrdpSession` does not yet expose, or if you need to call multiple functions with minimal overhead in a tight loop.

```java
TrdpLibrary lib = Native.load("trdp", TrdpLibrary.class);
System.out.println(lib.tlc_getVersionString());
```

All C structures (`TrdpPdInfo`, `TrdpMdInfo`, `TrdpStatistics`, …) use `ALIGN_NONE` to match the `GNU_PACKED` attribute on the C side.

---

## Threading notes

- `TrdpSession` is **not thread-safe**. All method calls should come from the same thread, or you must add external synchronisation.
- `startProcessingThread` creates one internal daemon thread. Callbacks are invoked on that thread. Do not block or throw from a callback.
- If you use `processOnce` from your own thread instead, callbacks are invoked on that thread.

---

## Example: publish–subscribe loop

```java
try (TrdpSession s = new TrdpSession("10.0.1.1")) {
    var pub = s.publish(1000, "239.192.0.0", 50_000);   // 50 ms cycle
    var sub = s.subscribe(2000, "0.0.0.0", 200_000);    // 200 ms timeout

    s.startProcessingThread(5);   // 5 ms poll period

    for (int i = 0; i < 100; i++) {
        s.put(pub, ("msg-" + i).getBytes());
        Thread.sleep(100);

        Object[] r = s.get(sub);
        if (r != null) System.out.println(new String((byte[]) r[1]));
    }
}
```

## Example: MD request–reply

```java
try (TrdpSession requester = new TrdpSession("10.0.1.1");
     TrdpSession replier   = new TrdpSession("10.0.1.2")) {

    // Replier registers a listener
    replier.mdAddListener(3000, (info, data) -> {
        System.out.println("Request received: " + new String(data));
        replier.mdReply(info.sessionId, 3001, "pong".getBytes());  // WRONG – see note below
    });

    // Requester sends a request
    byte[] sid = requester.mdRequest(3000, "10.0.1.2",
            "ping".getBytes(), (info, data) -> {
                System.out.println("Reply: " + new String(data));
            });

    requester.processOnce(500);
    replier.processOnce(500);
    requester.processOnce(500);
}
```

> **Note:** in a real application the replier gets the `sessionId` from the `MdInfo` object delivered to the listener callback (`info` parameter). The `MdInfo.sessionId` field is the 16-byte UUID to pass back to `mdReply`.

---

---

## Template API (`io.trdp.template`)

The template layer sits on top of `TrdpSession` and provides:

- **Generics** — publishers and subscribers are typed (`TrdpPublisher<T>`, `TrdpSubscriber<T>`).
- **Fluent builders** — configure everything in one chain.
- **Pluggable serialization** — `TrdpSerializer<T>` / `TrdpDeserializer<T>` functional interfaces with built-in factories for `byte[]` and `String`.
- **Push and pull** — register an `onMessage` callback (push) or call `poll()` / `receive()` (pull).
- **`CompletableFuture`-based MD requests** — `TrdpMdTemplate.asyncRequest(...)` integrates cleanly with Java async pipelines.

### Package layout

```
io.trdp.template/
├── TrdpTemplate.java          Main template — creates publishers and subscribers
│   ├── PublisherBuilder<T>    Fluent builder for TrdpPublisher<T>
│   └── SubscriberBuilder<T>   Fluent builder for TrdpSubscriber<T>
├── TrdpPublisher<T>.java      Typed publisher handle (send / sendImmediate / close)
├── TrdpSubscriber<T>.java     Typed subscriber handle (poll / receive / close)
├── TrdpMdTemplate.java        MD operations: notify, request, asyncRequest, addListener
├── MdListenerHandle.java      AutoCloseable MD listener handle
├── TrdpSerializer<T>.java     Serializer interface + bytes() / string() factories
└── TrdpDeserializer<T>.java   Deserializer interface + bytes() / string() factories
```

---

### `TrdpTemplate`

#### Creating a self-managed instance

```java
// Template owns the session — constructor opens it, close() shuts it down
try (TrdpTemplate t = TrdpTemplate.builder()
        .ownIp("10.0.1.1")           // required
        .leaderIp("0.0.0.0")         // optional, default 0.0.0.0
        .libName("trdp")             // optional, default "trdp"
        .pollPeriodMs(10)            // optional, default 10 ms
        .build()) {
    // ...
}
```

#### Wrapping an existing session

```java
// Template borrows the session — close() does NOT close the session
TrdpTemplate t = new TrdpTemplate(existingSession);
TrdpTemplate t = new TrdpTemplate(existingSession, 5 /*pollPeriodMs*/);
```

#### Lifecycle

| Method | Description |
|--------|-------------|
| `start()` | Start the background daemon processing thread. |
| `stop()` | Stop it. |
| `close()` | Stop + close session (if owned). |
| `getSession()` | Access the raw `TrdpSession` for advanced use. |

---

### `TrdpPublisher<T>`

Returned by `TrdpTemplate.PublisherBuilder.register()`.

```java
TrdpPublisher<String> pub = template
    .publisher(TrdpSerializer.string())   // or .stringPublisher()
    .comId(1000)
    .dest("239.192.0.0")
    .intervalMs(100)           // or .intervalUs(100_000)
    .src("0.0.0.0")            // optional
    .redId(0)                  // optional — redundancy group
    .initialValue("init")      // optional — pre-load payload
    .register();

pub.send("hello");             // queued for next cycle
pub.sendImmediate("urgent");   // sent immediately
pub.close();                   // stop publishing
```

**`PublisherBuilder` options**

| Method | Default | Description |
|--------|---------|-------------|
| `comId(int)` | — | Communication identifier (required) |
| `dest(String)` | — | Destination IP (required) |
| `intervalUs(int)` | — | Publish period µs (required, or use `intervalMs`) |
| `intervalMs(int)` | — | Publish period ms |
| `src(String)` | `"0.0.0.0"` | Source IP |
| `redId(int)` | `0` | Redundancy group ID |
| `serviceId(int)` | `0` | Service ID |
| `etbTopoCnt(int)` | `0` | ETB topology counter |
| `opTrnTopoCnt(int)` | `0` | Op-train topology counter |
| `flags(byte)` | `FLAGS_DEFAULT` | Packet flags |
| `initialValue(T)` | empty | Initial payload |

---

### `TrdpSubscriber<T>`

Returned by `TrdpTemplate.SubscriberBuilder.register()`.

#### Push style (callback)

```java
TrdpSubscriber<String> sub = template
    .subscriber(TrdpDeserializer.string())   // or .stringSubscriber()
    .comId(2000)
    .src("10.0.1.2")           // optional, default any
    .timeoutMs(500)            // or .timeoutUs(500_000)
    .keepLastOnTimeout()       // optional: keep / zero / default
    .onMessage((info, msg) -> System.out.println(info.srcIp + ": " + msg))
    .register();
// callback fires on processing thread — don't block inside it
```

#### Pull style (polling)

```java
TrdpSubscriber<String> sub = template
    .stringSubscriber()
    .comId(2000).src("0.0.0.0").timeoutMs(500)
    .register();

// poll: returns null if no data yet
String latest = sub.poll();

// receive: returns Optional with metadata
sub.receive().ifPresent(e -> {
    PdInfo info = e.getKey();
    String msg  = e.getValue();
    System.out.printf("from %s: %s%n", info.srcIp, msg);
});

sub.close();
```

**`SubscriberBuilder` options**

| Method | Default | Description |
|--------|---------|-------------|
| `comId(int)` | — | Communication identifier (required) |
| `src(String)` | `"0.0.0.0"` | Source IP filter (any) |
| `srcRange(String, String)` | — | Source IP range |
| `dest(String)` | `"0.0.0.0"` | Multicast group (unicast if omitted) |
| `timeoutUs(int)` | `0` | Receive timeout µs (0 = infinite) |
| `timeoutMs(int)` | `0` | Receive timeout ms |
| `keepLastOnTimeout()` | — | Keep last value on timeout |
| `zeroOnTimeout()` | — | Zero buffer on timeout |
| `serviceId(int)` | `0` | Service ID |
| `etbTopoCnt(int)` | `0` | ETB topology counter |
| `opTrnTopoCnt(int)` | `0` | Op-train topology counter |
| `flags(int)` | `FLAGS_DEFAULT` | Packet flags |
| `onMessage(BiConsumer)` | none | Push-style callback |

---

### `TrdpSerializer<T>` / `TrdpDeserializer<T>`

Both are `@FunctionalInterface` — use a lambda or a method reference for custom types.

```java
// Built-in factories
TrdpSerializer<byte[]>  rawSer  = TrdpSerializer.bytes();
TrdpSerializer<String>  strSer  = TrdpSerializer.string();          // UTF-8
TrdpSerializer<String>  lat1Ser = TrdpSerializer.string(ISO_8859_1);

TrdpDeserializer<byte[]> rawDes = TrdpDeserializer.bytes();
TrdpDeserializer<String> strDes = TrdpDeserializer.string();

// Custom — Jackson JSON example
TrdpSerializer<MyDto>   jsonSer = value -> objectMapper.writeValueAsBytes(value);
TrdpDeserializer<MyDto> jsonDes = data  -> objectMapper.readValue(data, MyDto.class);
```

---

### `TrdpMdTemplate`

#### Setup

```java
TrdpMdTemplate md = new TrdpMdTemplate(template);   // shares session
// or
TrdpMdTemplate md = new TrdpMdTemplate(session);
```

#### Notify (fire and forget)

```java
md.notify(3000, "10.0.1.2", "ping".getBytes());

// typed
md.notify(3000, "10.0.1.2", myDto, jsonSerializer);
```

#### Async request — `CompletableFuture`

```java
// Raw bytes
md.asyncRequest(3000, "10.0.1.2", "ping".getBytes(), /*timeoutMs*/ 1_000)
  .thenAccept(reply -> System.out.println(new String(reply)))
  .exceptionally(ex -> { System.err.println(ex.getMessage()); return null; });

// Typed
CompletableFuture<String> f = md.asyncRequest(
    3000, "10.0.1.2",
    "ping",                          // REQ value
    TrdpSerializer.string(),         // REQ serializer
    TrdpDeserializer.string(),       // REP deserializer
    1_000);
f.thenAccept(System.out::println);
```

The future completes exceptionally with `TimeoutException` if the timeout fires first.

#### Synchronous request (callback)

```java
byte[] sessionId = md.request(3000, "10.0.1.2", "ping".getBytes(),
    (info, data) -> System.out.println("reply: " + new String(data)));
```

#### Reply (server side)

```java
// Inside a listener callback — info.sessionId is the UUID to reply to
md.reply(info.sessionId, 3001, "pong".getBytes());

// With explicit confirmation required
md.replyQuery(info.sessionId, 3001, "pong".getBytes(), /*confirmTimeoutUs*/ 500_000);

// Confirm a reply (requester side)
md.confirm(sessionId, /*userStatus*/ 0);

// Abort
md.abort(sessionId);
```

#### Listener

```java
// Typed listener — AutoCloseable
try (MdListenerHandle h = md.addListener(3000,
        TrdpDeserializer.string(),
        (info, msg) -> {
            System.out.println("request from " + info.srcIp + ": " + msg);
            md.reply(info.sessionId, 3001, "pong".getBytes());
        })) {
    Thread.sleep(30_000);
} // listener removed here

// Raw bytes listener
MdListenerHandle h = md.addListener(3000,
    (info, data) -> System.out.println(data.length + " bytes from " + info.srcIp));
h.close(); // explicit removal
```

---

### Complete template example

```java
import io.trdp.template.*;

public class TrdpDemo {
    public static void main(String[] args) throws Exception {

        // ── Publisher side ────────────────────────────────────────────────────
        try (TrdpTemplate producer = TrdpTemplate.builder()
                .ownIp("10.0.1.1").pollPeriodMs(5).build()) {

            TrdpPublisher<String> pub = producer.stringPublisher()
                .comId(1000).dest("239.192.0.0").intervalMs(100)
                .register();

            producer.start();

            for (int i = 0; i < 50; i++) {
                pub.send("message-" + i);
                Thread.sleep(100);
            }
        }

        // ── Subscriber side ───────────────────────────────────────────────────
        try (TrdpTemplate consumer = TrdpTemplate.builder()
                .ownIp("10.0.1.2").pollPeriodMs(5).build()) {

            consumer.stringSubscriber()
                .comId(1000).src("10.0.1.1").timeoutMs(500)
                .onMessage((info, msg) ->
                    System.out.printf("[%s] %s%n", info.srcIp, msg))
                .register();

            consumer.start();
            Thread.sleep(5_000);
        }

        // ── MD request / reply ────────────────────────────────────────────────
        try (TrdpTemplate t = TrdpTemplate.builder().ownIp("10.0.1.1").build()) {
            TrdpMdTemplate md = new TrdpMdTemplate(t);

            // Server: register a listener that replies
            md.addListener(3000, TrdpDeserializer.string(),
                (info, req) -> md.reply(info.sessionId, 3001, ("echo: " + req).getBytes()));

            // Client: async request
            md.asyncRequest(3000, "10.0.1.1", "hello".getBytes(), 1_000)
              .thenAccept(r -> System.out.println(new String(r)));

            t.start();
            Thread.sleep(2_000);
        }
    }
}
```
