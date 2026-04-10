# trdp-py

Python wrapper for **TCNOpen TRDP Light** (v3.0.0.0) built with [CFFI](https://cffi.readthedocs.io/).  
Provides idiomatic Python access to the full TRDP PD + MD + statistics API.

---

## Requirements

| Dependency | Version |
|------------|---------|
| Python     | 3.9+    |
| cffi       | 1.16+   |
| libtrdp    | 3.0.0.0 (shared library) |

---

## Building the native library

### Linux (static → shared)

```bash
# 1. Build the static library
cd TRDP/3.0.0.0
make LINUX_X86_64_config
make -j
# Output: bld/output/<platform>/libtrdp.a

# 2. Build a shared library (required by CFFI)
bash trdp_pywrap/build_scripts/build_shared_linux.sh /path/to/TRDP/3.0.0.0
# Output: bld/output/<platform>/libtrdp.so
```

Place `libtrdp.so` somewhere on `LD_LIBRARY_PATH` (e.g. `/usr/local/lib`).

### MD support

If you need Message Data functions (`md_notify`, `md_request`, …), build with:

```bash
make LINUX_X86_64_config MD_SUPPORT=1
make -j
```

---

## Installing the Python package

```bash
python -m pip install -U pip
python -m pip install -e .        # editable install from source
# or
python -m pip wheel .             # build a distributable wheel
```

---

## Quick start

```python
import trdp_py

with trdp_py.Session("10.0.1.1") as s:
    # Publish comId=1000 to multicast every 100 ms
    pub = s.publish(com_id=1000, dest_ip="239.192.0.0", interval_us=100_000)

    # Subscribe to comId=2000 from any source, 500 ms timeout
    sub = s.subscribe(com_id=2000, src_ip1="0.0.0.0", timeout_us=500_000)

    # Update payload (sent on next cycle)
    s.put(pub, b"hello")

    # Drive the stack for one iteration
    s.process_once(timeout_ms=10)

    # Read the latest received packet
    result = s.get(sub)
    if result is not None:
        info, payload = result
        print(f"received {len(payload)} bytes from {info.src_ip}")
```

---

## Package layout

```
trdp_py/
├── __init__.py       Public re-exports (Session, TrdpError, PdInfo, …)
├── session.py        High-level Python API
└── _cffi_build.py    CFFI declarations and C helper functions (build-time)
```

---

## API reference

### `Session`

The main entry point. Supports the context-manager protocol (`with` statement).

#### Constructor

```python
Session(
    own_ip: str,
    leader_ip: str = "0.0.0.0",
    *,
    auto_init: bool = True,
)
```

- **`own_ip`** — own IP address in dotted-decimal notation (`"10.0.1.1"`).
- **`leader_ip`** — redundancy leader IP; `"0.0.0.0"` when not using redundancy.
- **`auto_init`** — when `True` (default), `tlc_init` / `tlc_terminate` are called automatically on `open()` / `__exit__`. Set to `False` when managing multiple sessions in one process.

The constructor does **not** open the session. Use a `with` block or call `open()` explicitly.

---

#### Lifecycle

| Method | Description |
|--------|-------------|
| `open()` | Initialise the TRDP stack and open the session. |
| `close()` | Close the session and release handles (does not call `tlc_terminate`). |
| `__enter__` / `__exit__` | Opens on entry, closes + terminates (if `auto_init`) on exit. |
| `reinit()` | Re-initialise the session (`tlc_reinitSession`). |
| `update()` | Update session parameters (`tlc_updateSession`). |

---

#### Event loop

TRDP requires `tlc_getInterval` + `tlc_process` to be called regularly.

```python
# Single iteration — performs a real select() on Linux
session.process_once(timeout_ms: int = 10) -> None

# Start a background daemon thread
session.start_processing_thread(period_ms: int) -> None

# Stop it
session.stop_processing_thread() -> None
```

`process_once` calls `tlc_getInterval()` to obtain the fd-set and TRDP timer deadline, performs a genuine OS-level `select()` on the returned file descriptors, then calls `tlc_process()` with the ready descriptors. The effective timeout is `min(timeout_ms, trdp_deadline)`.

---

#### Topology

```python
session.set_etb_topo_count(count: int) -> None
session.get_etb_topo_count() -> int

session.set_op_train_topo_count(count: int) -> None
session.get_op_train_topo_count() -> int
```

#### Own IP (property)

```python
session.own_ip  # str — dotted-decimal IP as reported by the stack
```

#### Version (static method)

```python
Session.version_string() -> str
```

---

#### Process Data — publish

```python
pub_handle = session.publish(
    com_id: int,
    dest_ip: str,
    interval_us: int,
    data: bytes = b"",
    *,
    src_ip: str = "0.0.0.0",
    red_id: int = 0,
    service_id: int = 0,
    etb_topo_cnt: int = 0,
    op_trn_topo_cnt: int = 0,
    pkt_flags: int = FLAGS_DEFAULT,
    callback: Callable | None = None,
)
```

- **`com_id`** — communication identifier (must match subscriber).
- **`dest_ip`** — destination IP, unicast or multicast (`"239.192.0.0"`).
- **`interval_us`** — publishing period in microseconds (e.g. `100_000` = 100 ms).
- **`red_id`** — redundancy group ID; `0` = no redundancy.
- **`pkt_flags`** — bitmask of `FLAGS_*` constants.
- **`callback`** — optional callable `cb(pd_info: PdInfo, data: bytes) -> None` invoked on each outgoing packet.

Returns an opaque CFFI handle. Pass it to `put`, `put_immediate`, `republish`, or `unpublish`.

```python
# Update payload (sent on next cycle)
session.put(pub_handle, data: bytes) -> None

# Update payload and send immediately
session.put_immediate(pub_handle, data: bytes) -> None

# Update topology / addresses of a live publisher
session.republish(
    pub_handle,
    src_ip: str,
    dest_ip: str,
    etb_topo_cnt: int = 0,
    op_trn_topo_cnt: int = 0,
) -> None

# Stop publishing and release the handle
session.unpublish(pub_handle) -> None
```

---

#### Process Data — subscribe

```python
sub_handle = session.subscribe(
    com_id: int,
    src_ip1: str = "0.0.0.0",
    timeout_us: int = 0,
    *,
    src_ip2: str = "0.0.0.0",
    dest_ip: str = "0.0.0.0",
    service_id: int = 0,
    etb_topo_cnt: int = 0,
    op_trn_topo_cnt: int = 0,
    pkt_flags: int = FLAGS_DEFAULT,
    to_behavior: int = TO_DEFAULT,
    callback: Callable | None = None,
)
```

- **`src_ip1`** — accepted source IP; `"0.0.0.0"` = any.
- **`src_ip2`** — second source filter; `"0.0.0.0"` = not used.
- **`dest_ip`** — multicast group to join; `"0.0.0.0"` for unicast.
- **`timeout_us`** — receive timeout in microseconds; `0` = infinite.
- **`to_behavior`** — action on timeout: `TO_DEFAULT`, `TO_SET_TO_ZERO`, or `TO_KEEP_LAST_VALUE`.
- **`callback`** — optional callable `cb(pd_info: PdInfo, data: bytes) -> None`.

Returns an opaque CFFI handle.

```python
# Read the most recently received PD data.
# Returns (PdInfo, bytes) or None when no data is available yet.
session.get(sub_handle, max_size: int = 1500) -> tuple[PdInfo, bytes] | None

# Update topology / addresses of a live subscriber
session.resubscribe(
    sub_handle,
    src_ip1: str,
    src_ip2: str = "0.0.0.0",
    dest_ip: str = "0.0.0.0",
    etb_topo_cnt: int = 0,
    op_trn_topo_cnt: int = 0,
) -> None

# Unsubscribe and release the handle
session.unsubscribe(sub_handle) -> None

# Send a PD pull-request (pull mode)
session.pd_request(
    sub_handle,
    com_id: int,
    dest_ip: str,
    data: bytes = b"",
    *,
    src_ip: str = "0.0.0.0",
    reply_com_id: int = 0,
    reply_ip: str = "0.0.0.0",
    service_id: int = 0,
    red_id: int = 0,
    etb_topo_cnt: int = 0,
    op_trn_topo_cnt: int = 0,
    pkt_flags: int = FLAGS_DEFAULT,
) -> None
```

---

#### Process Data — redundancy

```python
session.set_redundant(red_id: int, leader: bool) -> None
session.get_redundant(red_id: int) -> bool
```

---

#### Message Data (MD)

> MD functions require `libtrdp` to be compiled with `MD_SUPPORT=1`.

```python
# One-way notification (no reply expected)
session.md_notify(
    com_id: int,
    dest_ip: str,
    data: bytes = b"",
    *,
    src_ip: str = "0.0.0.0",
    etb_topo_cnt: int = 0,
    op_trn_topo_cnt: int = 0,
    pkt_flags: int = FLAGS_DEFAULT,
    src_uri: str = "",
    dest_uri: str = "",
    qos: int = 2,
    ttl: int = 64,
    callback: Callable | None = None,
) -> None

# Request / reply — returns the 16-byte session UUID
session.md_request(
    com_id: int,
    dest_ip: str,
    data: bytes = b"",
    *,
    src_ip: str = "0.0.0.0",
    num_replies: int = 1,
    reply_timeout_us: int = 1_000_000,
    etb_topo_cnt: int = 0,
    op_trn_topo_cnt: int = 0,
    pkt_flags: int = FLAGS_DEFAULT,
    src_uri: str = "",
    dest_uri: str = "",
    qos: int = 2,
    ttl: int = 64,
    callback: Callable | None = None,
) -> bytes                    # 16-byte UUID

# Send a reply (from the replying side)
session.md_reply(
    session_id: bytes,        # 16-byte UUID from MdInfo.session_id
    com_id: int,
    data: bytes = b"",
    *,
    user_status: int = 0,
    src_uri: str = "",
    qos: int = 2,
    ttl: int = 64,
) -> None

# Send a reply that requires a confirmation
session.md_reply_query(
    session_id: bytes,
    com_id: int,
    data: bytes = b"",
    *,
    user_status: int = 0,
    confirm_timeout_us: int = 1_000_000,
    src_uri: str = "",
    qos: int = 2,
    ttl: int = 64,
) -> None

# Confirm receipt of a reply
session.md_confirm(
    session_id: bytes,
    user_status: int = 0,
    qos: int = 2,
    ttl: int = 64,
) -> None

# Abort an in-progress MD session
session.md_abort(session_id: bytes) -> None

# Add an MD listener
lis_handle = session.md_add_listener(
    com_id: int,
    callback: Callable,       # cb(md_info: MdInfo, data: bytes) -> None
    *,
    com_id_listener: bool = True,
    src_ip1: str = "0.0.0.0",
    src_ip2: str = "0.0.0.0",
    mc_dest_ip: str = "0.0.0.0",
    etb_topo_cnt: int = 0,
    op_trn_topo_cnt: int = 0,
    pkt_flags: int = FLAGS_DEFAULT,
    src_uri: str = "",
    dest_uri: str = "",
)

# Update listener topology / addresses
session.md_readd_listener(
    lis_handle,
    src_ip: str = "0.0.0.0",
    src_ip2: str = "0.0.0.0",
    mc_dest_ip: str = "0.0.0.0",
    etb_topo_cnt: int = 0,
    op_trn_topo_cnt: int = 0,
) -> None

# Remove a listener
session.md_del_listener(lis_handle) -> None
```

---

#### Statistics

```python
session.get_statistics() -> Statistics
session.get_subs_statistics(max_count: int = 64) -> list[SubsStatistics]
session.get_pub_statistics(max_count: int = 64) -> list[PubStatistics]
session.get_join_addresses(max_count: int = 64) -> list[str]
session.reset_statistics() -> None
```

---

### `TrdpError`

`RuntimeError` raised whenever a TRDP API call returns a non-zero code.

```python
try:
    s.put(pub, data)
except trdp_py.TrdpError as e:
    print(e.code)    # raw int
    print(e)         # "tlp_put: PARAM_ERR (-1)"
```

---

### `TrdpErrCode`

`IntEnum` of all `TRDP_ERR_T` values.

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

### `PdInfo`

Frozen dataclass with metadata from a received PD telegram (`TRDP_PD_INFO_T` subset).  
Returned as the first element of the tuple from `get()`, or passed to PD callbacks.

| Field | Type | Description |
|-------|------|-------------|
| `src_ip` | `str` | Source IP address (dotted-decimal) |
| `dest_ip` | `str` | Destination IP address |
| `com_id` | `int` | Communication identifier |
| `seq_count` | `int` | Sequence counter (wraps at 2^32) |
| `msg_type` | `int` | TRDP message type code |
| `etb_topo_cnt` | `int` | ETB topology counter |
| `op_trn_topo_cnt` | `int` | Operational-train topology counter |
| `reply_com_id` | `int` | Reply ComID (pull requests; 0 otherwise) |
| `reply_ip` | `str` | Reply IP (pull requests; `"0.0.0.0"` otherwise) |
| `result_code` | `int` | `TRDP_ERR_T` result code for this telegram |
| `service_id` | `int` | Service ID (service-oriented use; 0 otherwise) |

---

### `MdInfo`

Frozen dataclass with metadata from a received MD telegram (`TRDP_MD_INFO_T` subset).  
Passed to MD callbacks.

| Field | Type | Description |
|-------|------|-------------|
| `src_ip` | `str` | Source IP address |
| `dest_ip` | `str` | Destination IP address |
| `com_id` | `int` | Communication identifier |
| `seq_count` | `int` | Sequence counter |
| `msg_type` | `int` | TRDP message type code |
| `etb_topo_cnt` | `int` | ETB topology counter |
| `op_trn_topo_cnt` | `int` | Operational-train topology counter |
| `session_id` | `bytes` | 16-byte session UUID (`TRDP_UUID_T`) |
| `result_code` | `int` | Result code for this telegram |
| `user_status` | `int` | Application status code |
| `reply_status` | `int` | Reply status |
| `about_to_die` | `bool` | Session is closing |
| `num_replies` | `int` | Replies received so far |
| `num_exp_replies` | `int` | Expected number of replies |
| `src_user_uri` | `str` | Source user URI |
| `dest_user_uri` | `str` | Destination user URI |
| `reply_timeout_us` | `int` | Reply timeout in microseconds |

---

### `Statistics`

Returned by `session.get_statistics()`.

| Field | Type | Description |
|-------|------|-------------|
| `version` | `int` | Library version number |
| `up_time_s` | `int` | Session uptime in seconds |
| `statistic_time_s` | `int` | Time since last reset in seconds |
| `own_ip` | `str` | Session own IP |
| `leader_ip` | `str` | Redundancy leader IP |
| `num_red` | `int` | Number of active redundancy groups |
| `num_join` | `int` | Number of joined multicast groups |
| `pd_num_subs` | `int` | Active PD subscriptions |
| `pd_num_pub` | `int` | Active PD publishers |
| `pd_num_send` | `int` | Total PD packets sent |
| `pd_num_rcv` | `int` | Total PD packets received |
| `pd_num_timeout` | `int` | PD receive timeouts |
| `pd_num_crc_err` | `int` | PD CRC errors |
| `udp_md_num_send` | `int` | UDP MD packets sent |
| `udp_md_num_rcv` | `int` | UDP MD packets received |
| `udp_md_num_timeout` | `int` | UDP MD timeouts |
| `tcp_md_num_send` | `int` | TCP MD packets sent |
| `tcp_md_num_rcv` | `int` | TCP MD packets received |

### `SubsStatistics`

Per-subscription entry returned by `get_subs_statistics()`.

| Field | Type | Description |
|-------|------|-------------|
| `com_id` | `int` | Communication identifier |
| `joined_addr` | `str` | Multicast group joined (`"0.0.0.0"` for unicast) |
| `filter_addr` | `str` | Source filter address |
| `timeout_us` | `int` | Configured timeout in microseconds |
| `status` | `int` | Current status code |
| `num_rcv` | `int` | Packets received |
| `num_missed` | `int` | Packets missed (timeout events) |

### `PubStatistics`

Per-publisher entry returned by `get_pub_statistics()`.

| Field | Type | Description |
|-------|------|-------------|
| `com_id` | `int` | Communication identifier |
| `dest_addr` | `str` | Destination IP |
| `cycle_us` | `int` | Publishing period in microseconds |
| `red_id` | `int` | Redundancy group ID |
| `red_state` | `int` | Redundancy state (`0` = follower, `1` = leader) |
| `num_put` | `int` | Total `put()` calls |
| `num_send` | `int` | Total packets actually sent |

---

## Constants

Exported at the `trdp_py` package level.

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

## Internal modules

### `_cffi_build.py`

Build-time module consumed by setuptools. It declares the C types and function signatures that CFFI uses to generate the `_trdp_c` extension module. Also defines small C helper functions (`_trdpy_pd_*`, `_trdpy_md_*`, `_trdpy_stats_*`, …) that read fields from GNU-packed structs, bypassing Python-side alignment issues.

You do not interact with this module directly.

### `session.py`

Contains all public classes: `Session`, `TrdpError`, `TrdpErrCode`, `PdInfo`, `MdInfo`, `Statistics`, `SubsStatistics`, `PubStatistics`, and the module-level constants.

### `__init__.py`

Re-exports everything that `session.py` exposes, forming the public surface of the `trdp_py` package. The `__all__` list controls what `from trdp_py import *` imports.

---

## Threading notes

- `Session` is **not thread-safe**. Use a single thread, or protect accesses with a lock.
- `start_processing_thread` creates one internal daemon thread. Callbacks are invoked on that thread. Do not block or raise from a callback.
- `process_once` performs a real `select()` system call, so it can block for up to `timeout_ms` milliseconds waiting for socket activity.

---

## Example: publish–subscribe with background thread

```python
import time
import trdp_py

with trdp_py.Session("10.0.1.1") as s:
    pub = s.publish(com_id=1000, dest_ip="239.192.0.0", interval_us=50_000)
    sub = s.subscribe(com_id=2000, src_ip1="0.0.0.0", timeout_us=200_000)

    s.start_processing_thread(period_ms=5)

    for i in range(20):
        s.put(pub, f"msg-{i}".encode())
        time.sleep(0.1)

        result = s.get(sub)
        if result is not None:
            info, payload = result
            print(payload)
```

## Example: PD callback

```python
import trdp_py

def on_receive(info: trdp_py.PdInfo, data: bytes) -> None:
    print(f"comId={info.com_id} seq={info.seq_count} {data!r}")

with trdp_py.Session("10.0.1.1") as s:
    s.subscribe(
        com_id=2000,
        src_ip1="0.0.0.0",
        timeout_us=500_000,
        callback=on_receive,
    )
    while True:
        s.process_once(timeout_ms=10)
```

## Example: MD request–reply

```python
import trdp_py

def on_request(info: trdp_py.MdInfo, data: bytes) -> None:
    print(f"request: {data!r}")
    # Reply is sent from within the callback — session must be accessible here
    # (use a closure or global reference)
    replier.md_reply(info.session_id, com_id=3001, data=b"pong")

replier = trdp_py.Session("10.0.1.2")
replier.open()
replier.md_add_listener(com_id=3000, callback=on_request)

requester = trdp_py.Session("10.0.1.1")
requester.open()

sid = requester.md_request(
    com_id=3000,
    dest_ip="10.0.1.2",
    data=b"ping",
    callback=lambda info, data: print(f"reply: {data!r}"),
)

requester.process_once(500)
replier.process_once(500)
requester.process_once(500)

replier.close()
requester.close()
```
