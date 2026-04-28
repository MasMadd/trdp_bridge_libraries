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
| libtrdp    | 3.0.0.0 (shared library — see below) |

---

## Building the native TRDP library

JNA loads a **shared library** at runtime (`libtrdp.so` on Linux, `libtrdp.dylib` on
macOS, `trdp.dll` on Windows). The TRDP source ships under `TRDP/3.0.0.0/` and its
Makefile produces a static archive by default; an extra link step is needed to wrap it
into a shared library on POSIX systems. The full procedure is described below for each
supported OS.

> The source tree already contains pre-selected configuration files under
> `TRDP/3.0.0.0/config/`. All commands below are run from inside `TRDP/3.0.0.0/`.

---

### Linux (x86-64)

**Prerequisites:** `gcc`, `make`, `libuuid-dev` (Ubuntu/Debian) or `libuuid-devel` (RHEL/Fedora).

```bash
# 1. Select the target configuration (copies it to config/config.mk)
make LINUX_X86_64_config

# 2. Build the static library + object files
make libtrdp

# 3. Wrap the objects into a shared library (JNA requires .so)
gcc -shared -fPIC -o bld/output/linux-x86_64-rel/libtrdp.so \
    bld/output/linux-x86_64-rel/*.o \
    -lrt -luuid -lpthread

# 4. (Optional) install system-wide
sudo cp bld/output/linux-x86_64-rel/libtrdp.so /usr/local/lib/
sudo ldconfig
```

To enable **MD support** (required for `TrdpMdTemplate`), verify that the config file
contains `MD_SUPPORT = 1` — the `LINUX_X86_64_config` preset already has it enabled.

To build a **debug** variant add `DEBUG=TRUE` to the make invocation:

```bash
make DEBUG=TRUE libtrdp
```

The debug output lands in `bld/output/linux-x86_64-dbg/`.

**Alternative configs for Linux:**

| Config file | Use case |
|---|---|
| `LINUX_X86_64_config` | Standard 64-bit Linux |
| `LINUX_X86_config` | 32-bit Linux |
| `LINUX_X86_64_HP_config` | High-performance indexed mode (base 10) |
| `CENTOS_X86_64_config` | CentOS / RHEL 64-bit |
| `RASPIAN_config` | Raspberry Pi (ARM) |

---

### macOS

**Prerequisites:** Xcode Command Line Tools (`xcode-select --install`).
No `libuuid` is needed — the OS provides it via `<uuid/uuid.h>`.

```bash
# 1. Select the macOS config
make OSX_X86_64_config

# 2. Build
make libtrdp

# 3. Link a shared library (JNA requires .dylib)
gcc -dynamiclib -fPIC -o bld/output/osx_x86_64-rel/libtrdp.dylib \
    bld/output/osx_x86_64-rel/*.o \
    -lpthread

# 4. (Optional) install
sudo cp bld/output/osx_x86_64-rel/libtrdp.dylib /usr/local/lib/
```

**Apple Silicon (M1/M2/M3):** the shipped configs target `x86_64` and pass `-m64`.
On Apple Silicon you can either run under Rosetta 2 (transparent if your JDK is also
`x86_64`) or create a new config file based on `OSX_X86_64_config` removing `-m64`
and changing `ARCH = osx_arm64`:

```bash
cp config/OSX_X86_64_config config/OSX_ARM64_config
# edit OSX_ARM64_config: remove -m64, set ARCH = osx_arm64
make OSX_ARM64_config
make libtrdp
gcc -dynamiclib -fPIC -o bld/output/osx_arm64-rel/libtrdp.dylib \
    bld/output/osx_arm64-rel/*.o -lpthread
```

Alternatively, you can open `TRDP/3.0.0.0/Xcode/trdp.xcodeproj` in Xcode and build
the library target from the IDE.

---

### Windows

The repository includes a ready-to-use Visual Studio 2019 solution at
`TRDP/3.0.0.0/VSExpress2019/Win_TRDP_VS2019.sln`.

**Prerequisites:** Visual Studio 2019 (or later) with the **Desktop development with
C++** workload installed.

#### Build via Visual Studio IDE

1. Open `VSExpress2019/Win_TRDP_VS2019.sln`.
2. Select the **TRDP_DLL** project in Solution Explorer.
3. Choose the desired configuration (`Debug` or `Release`) and platform (`x64`).
4. Build → Build Solution (`Ctrl+Shift+B`).

The output DLL is placed in  
`VSExpress2019/TRDP_DLL/x64/Release/TRDP_DLL.dll` (Release)  
or  `VSExpress2019/TRDP_DLL/x64/Debug/TRDP_DLL.dll` (Debug).

The project already defines the required preprocessor macros:
- `WIN32` / `WIN64`
- `MD_SUPPORT=1`
- `L_ENDIAN`
- `DLL_EXPORT` (triggers `__declspec(dllexport)` on all public symbols)

#### Build from the command line (MSBuild)

```bat
:: Open a "Developer Command Prompt for VS 2019" or run vcvars64.bat first

cd TRDP\3.0.0.0\VSExpress2019

msbuild Win_TRDP_VS2019.sln ^
    /t:TRDP_DLL ^
    /p:Configuration=Release ^
    /p:Platform=x64
```

#### Making JNA find the DLL

JNA looks for `trdp.dll` on the Java library path. The DLL produced by the VS project
is named `TRDP_DLL.dll` — either rename it or tell JNA the exact name when constructing
the session:

```java
// Pass the exact DLL base name (without extension) to TrdpSession / TrdpTemplate
TrdpTemplate t = TrdpTemplate.builder()
    .ownIp("10.0.1.1")
    .libName("TRDP_DLL")   // ← matches TRDP_DLL.dll
    .build();
```

Alternatively, copy `TRDP_DLL.dll` to a directory on `PATH`, or pass
`-Djna.library.path=C:\path\to\dll` to the JVM.

---

### Pointing the Java library at the built file

Once you have the shared library, tell JNA where to find it using one of:

| Method | How |
|---|---|
| Install system-wide | Copy to `/usr/local/lib/` (Linux/macOS) and run `ldconfig` |
| JVM property | `-Djna.library.path=/path/to/dir/containing/libtrdp.so` |
| `TrdpTemplate.builder().libName(name)` | Pass the base name if it differs from `"trdp"` |
| `TrdpSession` constructor | 4th argument is the library base name |

---

## Building the Java wrapper

### Standard build (no native library bundled)

```bash
mvn package
```

Produces `target/trdp-java-0.1.0.jar`. The native library must be installed
separately on any machine that runs the application (see
[Pointing the Java library at the built file](#pointing-the-java-library-at-the-built-file)).

---

### Self-contained build (native library bundled inside the JAR)

Running with the `native` Maven profile compiles `libtrdp` for the current
platform, then packs the resulting binary directly inside the JAR.
The consumer of the artifact needs **zero configuration** — no environment
variable, no `jna.library.path`, no separate installation step.

```bash
# Release build — Linux, macOS, or Windows
mvn package -Pnative

# Debug build
mvn package -Pnative -Dnative.debug=true
```

What the profile does internally:

1. Runs `scripts/build-native-unix.sh` (Linux/macOS) or
   `scripts/build-native-win.bat` (Windows) via the Ant `<exec>` task.
2. The script selects the right TRDP config for the detected OS + arch,
   runs `make libtrdp`, then links a shared library.
3. The resulting file is copied to:
   ```
   src/main/resources/com/sun/jna/<jna-platform>/libtrdp.{so,dylib,dll}
   ```
4. `mvn package` includes those resources in the JAR normally.

#### How JNA finds the right binary at runtime

JNA evaluates `com.sun.jna.Platform.RESOURCE_PREFIX` once at load time.
This property is derived from the running JVM's `os.name` and `os.arch`
system properties and produces strings such as:

| OS / arch | `RESOURCE_PREFIX` | Library file in JAR |
|---|---|---|
| Linux x86-64 | `linux-x86-64` | `com/sun/jna/linux-x86-64/libtrdp.so` |
| Linux ARM 64-bit | `linux-aarch64` | `com/sun/jna/linux-aarch64/libtrdp.so` |
| macOS x86-64 | `darwin-x86-64` | `com/sun/jna/darwin-x86-64/libtrdp.dylib` |
| macOS Apple Silicon | `darwin-aarch64` | `com/sun/jna/darwin-aarch64/libtrdp.dylib` |
| Windows x86-64 | `win32-x86-64` | `com/sun/jna/win32-x86-64/trdp.dll` |

JNA then:

1. Locates `com/sun/jna/<RESOURCE_PREFIX>/libtrdp.<ext>` in the classpath.
2. Extracts it to a temporary directory (e.g. `/tmp/jna-<pid>/`).
3. Loads it with the OS native loader.

This is completely transparent to the Java code — `Native.load("trdp", TrdpLibrary.class)`
works the same way regardless of whether the library was bundled or installed system-wide.

If the bundled binary is not found (e.g. a platform not covered by the JAR),
JNA falls back to the system library search path as usual.

#### Building a multi-platform JAR

A single `mvn package -Pnative` run bundles only the library for the current
OS. To produce a JAR that works on all platforms without any native install,
build on each target OS and merge the resources before the final `mvn package`:

```
Linux   →  target/classes/com/sun/jna/linux-x86-64/libtrdp.so
macOS   →  target/classes/com/sun/jna/darwin-x86-64/libtrdp.dylib
           target/classes/com/sun/jna/darwin-aarch64/libtrdp.dylib
Windows →  target/classes/com/sun/jna/win32-x86-64/trdp.dll
```

The typical CI approach (e.g. GitHub Actions) is:

1. Run `mvn package -Pnative -DskipTests` on a Linux runner → collect `libtrdp.so`.
2. Run the same on a macOS runner → collect `libtrdp.dylib` (x86-64 + arm64).
3. Run the same on a Windows runner → collect `trdp.dll`.
4. On a final assembly job, copy all binaries into the resource tree and run
   `mvn package` once more to produce the fat JAR.

#### Project layout for native resources

```
trdp_javawrap/
├── scripts/
│   ├── build-native-unix.sh    ← Linux + macOS build script
│   └── build-native-win.bat    ← Windows (MSBuild) build script
└── src/main/resources/
    └── com/sun/jna/
        ├── linux-x86-64/       libtrdp.so     (generated, git-ignored)
        ├── linux-aarch64/      libtrdp.so     (generated, git-ignored)
        ├── darwin-x86-64/      libtrdp.dylib  (generated, git-ignored)
        ├── darwin-aarch64/     libtrdp.dylib  (generated, git-ignored)
        └── win32-x86-64/       trdp.dll       (generated, git-ignored)
```

The directories are tracked in git (via `.gitkeep`); the compiled binaries are
`.gitignore`d and must be regenerated locally with `mvn package -Pnative`.

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

---

---

## Testing

### Running the tests

```bash
# Unit tests only (no native library needed)
mvn test

# Unit + integration tests (requires libtrdp on the system)
mvn verify -Pintegration-test -Dtrdp.native.lib=/path/to/dir/containing/libtrdp.so
```

### Test structure

```
src/test/java/io/trdp/
├── template/          ← unit tests — run always, no native library required
│   ├── TrdpSerializerTest.java
│   ├── TrdpDeserializerTest.java
│   ├── TrdpPublisherTest.java
│   ├── TrdpSubscriberTest.java
│   ├── TrdpTemplateBuilderTest.java
│   └── TrdpMdTemplateTest.java
└── integration/       ← integration tests — skipped if libtrdp is not found
    └── TrdpPdLoopbackIT.java
```

`TrdpSession` is mocked in all unit tests (Mockito creates a proxy bypassing the
native constructor), so no shared library is needed to run them.

---

### Unit tests

#### `TrdpSerializerTest` / `TrdpDeserializerTest`

Pure-Java tests with no mocking.

| What is tested | Expected behaviour |
|---|---|
| `TrdpSerializer.bytes()` on a non-null array | Returns the same array instance (identity, no copy) |
| `TrdpSerializer.bytes()` on `null` | Returns an empty `byte[]` |
| `TrdpSerializer.string()` on an ASCII string | Produces the correct UTF-8 byte sequence |
| `TrdpSerializer.string()` on a multi-byte character (e.g. `café`) | Encodes correctly as UTF-8 |
| `TrdpSerializer.string()` on `null` / empty | Returns an empty `byte[]` |
| `TrdpSerializer.string(Charset)` | Uses the specified charset |
| Custom lambda serializer | The lambda is invoked with the value and the result is correct |
| Round-trip `serialize` → `deserialize` | Recovers the original value unchanged |
| `TrdpDeserializer.bytes()` on `null` | Passes `null` through without throwing |
| `TrdpDeserializer.string()` on `null` / empty bytes | Returns an empty `String` |

#### `TrdpPublisherTest`

Mocks `TrdpSession`; verifies that `TrdpPublisher` correctly delegates to it.

| What is tested | Expected behaviour |
|---|---|
| `send(value)` | Serializes the value and calls `session.put(handle, bytes)` |
| `send(value)` with a custom serializer | Uses the provided serializer, not the default |
| `sendImmediate(value)` | Calls `session.putImmediate(handle, bytes)` |
| `send()` / `sendImmediate()` after `close()` | Throws `IllegalStateException` |
| `close()` | Calls `session.unpublish(handle)` exactly once |
| `close()` called twice | `session.unpublish` is invoked only once (idempotent) |
| Use in `try-with-resources` | `session.unpublish` is called on exit |

#### `TrdpSubscriberTest`

Mocks `TrdpSession` and `PdInfo`; verifies `TrdpSubscriber` delegation and deserialization.

| What is tested | Expected behaviour |
|---|---|
| `poll()` when session returns `null` | Returns `null` (no data yet) |
| `poll()` when session returns a packet | Deserializes `byte[]` and returns the typed value |
| `poll()` with a custom deserializer | Uses the provided deserializer |
| `receive()` when no data | Returns `Optional.empty()` |
| `receive()` when data present | Returns `Optional` with the `PdInfo` metadata and deserialized value |
| `poll()` / `receive()` after `close()` | Throws `IllegalStateException` |
| `close()` | Calls `session.unsubscribe(handle)` exactly once |
| `close()` called twice | `session.unsubscribe` is invoked only once (idempotent) |

#### `TrdpTemplateBuilderTest`

Verifies the builder validation rules and the wiring between builder options and the underlying `TrdpSession` calls.

| What is tested | Expected behaviour |
|---|---|
| `TrdpTemplate.builder().build()` without `ownIp` | Throws `NullPointerException` |
| `close()` on a template wrapping an external session | Does **not** call `session.close()` |
| `start()` | Calls `session.startProcessingThread(pollPeriodMs)` |
| `stop()` | Calls `session.stopProcessingThread()` |
| `PublisherBuilder.register()` without `dest()` | Throws `NullPointerException` |
| `PublisherBuilder.register()` without interval | Throws `IllegalStateException` |
| `intervalMs(100)` | Passed to `session.publish` as `100_000` µs |
| `initialValue(T)` | The serialized bytes are passed to `session.publish` |
| `SubscriberBuilder.register()` | Passes the correct arguments to `session.subscribe` |
| `timeoutMs(200)` | Passed to `session.subscribe` as `200_000` µs |
| `keepLastOnTimeout()` | Passes `TO_KEEP_LAST_VALUE` to `session.subscribe` |
| `onMessage(callback)` | Wraps the raw `byte[]` callback with deserialization; the user callback receives the typed value |

#### `TrdpMdTemplateTest`

Verifies MD delegation, the `CompletableFuture` contract, and listener lifecycle.

| What is tested | Expected behaviour |
|---|---|
| `notify(comId, destIp, data)` | Calls `session.mdNotify` with the raw bytes and a `null` callback |
| `notify(…, value, serializer)` | Serializes the value before delegating |
| `asyncRequest` — reply arrives in time | `CompletableFuture` completes with the reply payload |
| `asyncRequest` — callback invoked twice | Future captures only the **first** reply; subsequent calls are no-ops |
| `asyncRequest` — no reply within timeout | Future completes exceptionally with `TimeoutException` (message contains the timeout value) |
| Typed `asyncRequest<REQ, REP>` | Request is serialized; reply is deserialized before completing the future |
| `reply(sessionId, comId, data)` | Calls `session.mdReply` |
| `reply(…, value, serializer)` | Serializes before delegating |
| Typed `addListener` | Incoming bytes are deserialized before the user callback is invoked |
| `addListener` return value | Returns an `MdListenerHandle` that calls `session.mdDelListener` on `close()` |
| `MdListenerHandle.close()` called twice | `session.mdDelListener` invoked only once (idempotent) |
| `MdListenerHandle` in `try-with-resources` | Listener is removed on block exit |

---

### Integration test

#### `TrdpPdLoopbackIT`

Requires `libtrdp` installed. Uses `127.0.0.1` (loopback), so no physical network
or multicast routing is needed. All tests are skipped (not failed) when the library
is absent.

| Test | What it verifies |
|---|---|
| `sessionLifecycle` | `TrdpTemplate` opens and closes a session without throwing — the native library initialises and tears down cleanly |
| `publishThenReceive` | A string value published with `initialValue()` is read back by `poll()` / `receive()` after one cycle; `PdInfo.srcIp` and `comId` are correct |
| `putUpdatesPayload` | After calling `send("updated")`, the subscriber's next `poll()` returns the new value (not the stale one) |
| `onMessageCallback` | The push-style `onMessage` callback is invoked at least 3 times with the expected deserialized string within 2 seconds |
