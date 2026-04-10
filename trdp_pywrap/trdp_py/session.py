"""High-level Python wrapper around the TRDP Light C API.

Typical usage::

    import trdp_py

    with trdp_py.Session("10.0.1.1") as s:
        pub = s.publish(com_id=1000, dest_ip="239.192.0.0", interval_us=100_000)
        sub = s.subscribe(com_id=2000, src_ip1="10.0.1.2", timeout_us=500_000)

        while True:
            s.process_once(timeout_ms=10)
            s.put(pub, b"hello")
            result = s.get(sub)
            if result:
                info, data = result
                print(info, data)

Notes
-----
- All IP addresses are strings in dotted-decimal notation (``"10.0.1.1"``).
- Callbacks are Python callables wrapped via ``ffi.callback()``.  Keep the
  returned handle alive for as long as the publisher / subscriber / listener
  is active (the Session stores them automatically).
- MD functions (``md_notify``, ``md_request``, …) require that ``libtrdp``
  was compiled with ``MD_SUPPORT=1``.
- ``process_once`` performs a real ``select()`` on Linux using the fd-set
  returned by ``tlc_getInterval()``.
"""

from __future__ import annotations

import enum
import ipaddress
import select as _select_module
import sys
from dataclasses import dataclass, field
from typing import Callable, Dict, List, Optional, Tuple

from ._trdp_c import ffi, lib  # built by _cffi_build.py


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------

def _ip_to_u32(ip: str) -> int:
    """Convert dotted-decimal IPv4 string to a host-byte-order uint32."""
    return int(ipaddress.IPv4Address(ip))


def _u32_to_ip(addr: int) -> str:
    """Convert host-byte-order uint32 to dotted-decimal string."""
    return str(ipaddress.IPv4Address(addr))


# fd_set bit-manipulation helpers (Linux x86-64: 16 × unsigned long)
_ULONG_BITS: int = 64 if sys.maxsize > 2**32 else 32


def _fd_isset(fd: int, fds) -> bool:
    word, bit = divmod(fd, _ULONG_BITS)
    return bool(fds.fds_bits[word] & (1 << bit))


def _fd_set_bit(fd: int, fds) -> None:
    word, bit = divmod(fd, _ULONG_BITS)
    fds.fds_bits[word] |= (1 << bit)


def _extract_fds(fds, max_fd: int) -> List[int]:
    return [fd for fd in range(max_fd + 1) if _fd_isset(fd, fds)]


# Error codes that are not fatal
_NODATA_ERR = -5   # TRDP_NODATA_ERR


# ---------------------------------------------------------------------------
# Public error / enum types
# ---------------------------------------------------------------------------

class TrdpErrCode(enum.IntEnum):
    """TRDP error codes (``TRDP_ERR_T``)."""
    NO_ERR              =   0
    PARAM_ERR           =  -1
    INIT_ERR            =  -2
    NOINIT_ERR          =  -3
    TIMEOUT_ERR         =  -4
    NODATA_ERR          =  -5
    SOCK_ERR            =  -6
    IO_ERR              =  -7
    MEM_ERR             =  -8
    SEMA_ERR            =  -9
    QUEUE_ERR           = -10
    QUEUE_FULL_ERR      = -11
    MUTEX_ERR           = -12
    THREAD_ERR          = -13
    BLOCK_ERR           = -14
    INTEGRATION_ERR     = -15
    NOCONN_ERR          = -16
    NOSESSION_ERR       = -30
    SESSION_ABORT_ERR   = -31
    NOSUB_ERR           = -32
    NOPUB_ERR           = -33
    NOLIST_ERR          = -34
    CRC_ERR             = -35
    WIRE_ERR            = -36
    TOPO_ERR            = -37
    COMID_ERR           = -38
    STATE_ERR           = -39
    APP_TIMEOUT_ERR     = -40
    APP_REPLYTO_ERR     = -41
    APP_CONFIRMTO_ERR   = -42
    REPLYTO_ERR         = -43
    CONFIRMTO_ERR       = -44
    REQCONFIRMTO_ERR    = -45
    PACKET_ERR          = -46
    UNRESOLVED_ERR      = -47
    XML_PARSER_ERR      = -48
    INUSE_ERR           = -49
    MARSHALLING_ERR     = -50
    UNKNOWN_ERR         = -99


class TrdpError(RuntimeError):
    """Raised when a TRDP API call returns a non-zero error code."""

    def __init__(self, code: int, msg: str = "TRDP call failed"):
        try:
            name = TrdpErrCode(code).name
        except ValueError:
            name = str(code)
        super().__init__(f"{msg}: {name} ({code})")
        self.code = code


# Packet flags (TRDP_FLAGS_T)
FLAGS_DEFAULT  = 0x00
FLAGS_NONE     = 0x01
FLAGS_MARSHALL = 0x02
FLAGS_CALLBACK = 0x04
FLAGS_TCP      = 0x08
FLAGS_FORCE_CB = 0x10

# Timeout behaviours (TRDP_TO_BEHAVIOR_T)
TO_DEFAULT         = 0
TO_SET_TO_ZERO     = 1
TO_KEEP_LAST_VALUE = 2

# Redundancy states
RED_FOLLOWER = 0
RED_LEADER   = 1


# ---------------------------------------------------------------------------
# Data classes returned to Python callers
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class PdInfo:
    """Metadata from a received PD telegram (subset of ``TRDP_PD_INFO_T``)."""
    src_ip:        str
    dest_ip:       str
    com_id:        int
    seq_count:     int
    msg_type:      int
    etb_topo_cnt:  int
    op_trn_topo_cnt: int
    reply_com_id:  int
    reply_ip:      str
    result_code:   int
    service_id:    int


@dataclass(frozen=True)
class MdInfo:
    """Metadata from a received MD telegram (subset of ``TRDP_MD_INFO_T``)."""
    src_ip:           str
    dest_ip:          str
    com_id:           int
    seq_count:        int
    msg_type:         int
    etb_topo_cnt:     int
    op_trn_topo_cnt:  int
    session_id:       bytes
    result_code:      int
    user_status:      int
    reply_status:     int
    about_to_die:     bool
    num_replies:      int
    num_exp_replies:  int
    src_user_uri:     str
    dest_user_uri:    str
    reply_timeout_us: int


@dataclass
class Statistics:
    """General session statistics (``TRDP_STATISTICS_T``)."""
    version:           int
    up_time_s:         int
    statistic_time_s:  int
    own_ip:            str
    leader_ip:         str
    num_red:           int
    num_join:          int
    pd_num_subs:       int
    pd_num_pub:        int
    pd_num_send:       int
    pd_num_rcv:        int
    pd_num_timeout:    int
    pd_num_crc_err:    int
    udp_md_num_send:   int
    udp_md_num_rcv:    int
    udp_md_num_timeout: int
    tcp_md_num_send:   int
    tcp_md_num_rcv:    int


@dataclass
class SubsStatistics:
    """Per-subscription statistics (``TRDP_SUBS_STATISTICS_T``)."""
    com_id:      int
    joined_addr: str
    filter_addr: str
    timeout_us:  int
    status:      int
    num_rcv:     int
    num_missed:  int


@dataclass
class PubStatistics:
    """Per-publisher statistics (``TRDP_PUB_STATISTICS_T``)."""
    com_id:    int
    dest_addr: str
    cycle_us:  int
    red_id:    int
    red_state: int
    num_put:   int
    num_send:  int


# ---------------------------------------------------------------------------
# Internal helpers for converting cdata → Python data classes
# ---------------------------------------------------------------------------

def _pd_info_from_c(pMsg) -> PdInfo:
    return PdInfo(
        src_ip=_u32_to_ip(pMsg.srcIpAddr),
        dest_ip=_u32_to_ip(pMsg.destIpAddr),
        com_id=int(pMsg.comId),
        seq_count=int(pMsg.seqCount),
        msg_type=int(pMsg.msgType),
        etb_topo_cnt=int(pMsg.etbTopoCnt),
        op_trn_topo_cnt=int(pMsg.opTrnTopoCnt),
        reply_com_id=int(pMsg.replyComId),
        reply_ip=_u32_to_ip(pMsg.replyIpAddr),
        result_code=int(lib._trdpy_pd_result_code(pMsg)),
        service_id=int(lib._trdpy_pd_service_id(pMsg)),
    )


def _md_info_from_c(pMsg) -> MdInfo:
    raw_sid = lib._trdpy_md_session_id(pMsg)
    session_id = bytes(ffi.buffer(raw_sid, 16))
    src_uri_ptr = lib._trdpy_md_src_user_uri(pMsg)
    dest_uri_ptr = lib._trdpy_md_dest_user_uri(pMsg)
    return MdInfo(
        src_ip=_u32_to_ip(pMsg.srcIpAddr),
        dest_ip=_u32_to_ip(pMsg.destIpAddr),
        com_id=int(pMsg.comId),
        seq_count=int(pMsg.seqCount),
        msg_type=int(pMsg.msgType),
        etb_topo_cnt=int(pMsg.etbTopoCnt),
        op_trn_topo_cnt=int(pMsg.opTrnTopoCnt),
        session_id=session_id,
        result_code=int(lib._trdpy_md_result_code(pMsg)),
        user_status=int(lib._trdpy_md_user_status(pMsg)),
        reply_status=int(lib._trdpy_md_reply_status(pMsg)),
        about_to_die=bool(lib._trdpy_md_about_to_die(pMsg)),
        num_replies=int(lib._trdpy_md_num_replies(pMsg)),
        num_exp_replies=int(lib._trdpy_md_num_exp_replies(pMsg)),
        src_user_uri=ffi.string(src_uri_ptr).decode("ascii", errors="replace")
        if src_uri_ptr != ffi.NULL else "",
        dest_user_uri=ffi.string(dest_uri_ptr).decode("ascii", errors="replace")
        if dest_uri_ptr != ffi.NULL else "",
        reply_timeout_us=int(lib._trdpy_md_reply_timeout(pMsg)),
    )


# ---------------------------------------------------------------------------
# Session
# ---------------------------------------------------------------------------

class Session:
    """Python interface to a single TRDP application session.

    Supports use as a context manager for automatic cleanup::

        with Session("10.0.1.1") as s:
            ...

    If ``auto_init=True`` (default), ``tlc_init`` is called on ``open()``.
    Set ``auto_init=False`` when you manage multiple sessions in one process
    and have already called ``tlc_init`` yourself.
    """

    def __init__(
        self,
        own_ip: str,
        leader_ip: str = "0.0.0.0",
        *,
        auto_init: bool = True,
    ) -> None:
        self._own_ip = _ip_to_u32(own_ip)
        self._leader_ip = _ip_to_u32(leader_ip)
        self._auto_init = auto_init
        self._handle = ffi.new("TRDP_APP_SESSION_T *")
        self._open = False
        # Keep callback cdata alive to prevent GC
        self._callbacks: List[object] = []

    def __enter__(self) -> "Session":
        self.open()
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.close()
        if self._auto_init:
            lib.tlc_terminate()

    # ── Lifecycle ────────────────────────────────────────────────────────────

    def open(self) -> None:
        """Initialise the TRDP stack and open an application session."""
        if self._open:
            return
        if self._auto_init:
            rc = lib.tlc_init(ffi.NULL, ffi.NULL, ffi.NULL)
            if rc != 0:
                raise TrdpError(int(rc), "tlc_init")
        rc = lib.tlc_openSession(
            self._handle,
            self._own_ip,
            self._leader_ip,
            ffi.NULL,   # marshalling config (none)
            ffi.NULL,   # PD defaults
            ffi.NULL,   # MD defaults
            ffi.NULL,   # process config
        )
        if rc != 0:
            raise TrdpError(int(rc), "tlc_openSession")
        self._open = True

    def close(self) -> None:
        """Close the application session (does not terminate the stack)."""
        if not self._open:
            return
        rc = lib.tlc_closeSession(self._handle[0])
        self._open = False
        self._callbacks.clear()
        if rc != 0:
            raise TrdpError(int(rc), "tlc_closeSession")

    def reinit(self) -> None:
        """Re-initialise the session (``tlc_reinitSession``)."""
        self._check_open()
        _check(lib.tlc_reinitSession(self._handle[0]), "tlc_reinitSession")

    def update(self) -> None:
        """Update session parameters (``tlc_updateSession``)."""
        self._check_open()
        _check(lib.tlc_updateSession(self._handle[0]), "tlc_updateSession")

    # ── Version ──────────────────────────────────────────────────────────────

    @staticmethod
    def version_string() -> str:
        """Return the TRDP library version string."""
        return ffi.string(lib.tlc_getVersionString()).decode("ascii")

    # ── Topology ─────────────────────────────────────────────────────────────

    @property
    def own_ip(self) -> str:
        """Own IP address reported by the session."""
        self._check_open()
        return _u32_to_ip(lib.tlc_getOwnIpAddress(self._handle[0]))

    def set_etb_topo_count(self, count: int) -> None:
        """Set ETB topology counter."""
        self._check_open()
        _check(lib.tlc_setETBTopoCount(self._handle[0], int(count)),
               "tlc_setETBTopoCount")

    def get_etb_topo_count(self) -> int:
        """Get ETB topology counter."""
        self._check_open()
        return int(lib.tlc_getETBTopoCount(self._handle[0]))

    def set_op_train_topo_count(self, count: int) -> None:
        """Set operational-train topology counter."""
        self._check_open()
        _check(lib.tlc_setOpTrainTopoCount(self._handle[0], int(count)),
               "tlc_setOpTrainTopoCount")

    def get_op_train_topo_count(self) -> int:
        """Get operational-train topology counter."""
        self._check_open()
        return int(lib.tlc_getOpTrainTopoCount(self._handle[0]))

    # ── Event loop ───────────────────────────────────────────────────────────

    def process_once(self, timeout_ms: int = 10) -> None:
        """Drive the TRDP stack for one iteration using ``select()``.

        Calls ``tlc_getInterval()`` to get the fd-set and timeout, performs
        a real ``select()`` call, then calls ``tlc_process()`` with any
        ready file descriptors.

        Parameters
        ----------
        timeout_ms:
            Maximum time (ms) to wait for socket activity.  The actual wait
            may be shorter if TRDP has an earlier timer deadline.
        """
        self._check_open()
        interval = ffi.new("TRDP_TIME_T *")
        rfds = ffi.new("TRDP_FDS_T *")
        no_desc = ffi.new("TRDP_SOCK_T *", -1)

        rc = lib.tlc_getInterval(self._handle[0], interval, rfds, no_desc)
        if rc not in (0, _NODATA_ERR):
            raise TrdpError(int(rc), "tlc_getInterval")

        max_fd = int(no_desc[0])
        # Honour both caller's timeout_ms and TRDP's own deadline
        trdp_timeout = interval.tv_sec + interval.tv_usec / 1_000_000
        timeout_s = min(timeout_ms / 1000.0, trdp_timeout) if trdp_timeout > 0 else timeout_ms / 1000.0

        if max_fd >= 0:
            fd_list = _extract_fds(rfds, max_fd)
            ready: List[int] = []
            if fd_list:
                try:
                    ready, _, _ = _select_module.select(fd_list, [], [], timeout_s)
                except (OSError, ValueError):
                    pass
            rset = ffi.new("TRDP_FDS_T *")
            for fd in ready:
                _fd_set_bit(fd, rset)
            cnt = ffi.new("int32_t *", len(ready))
            rc = lib.tlc_process(self._handle[0], rset if ready else ffi.NULL, cnt)
        else:
            cnt = ffi.new("int32_t *", 0)
            rc = lib.tlc_process(self._handle[0], ffi.NULL, cnt)

        if rc not in (0, _NODATA_ERR):
            raise TrdpError(int(rc), "tlc_process")

    # ── Process Data publish ─────────────────────────────────────────────────

    def publish(
        self,
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
        callback: Optional[Callable] = None,
    ):
        """Register a periodic PD publisher.

        Parameters
        ----------
        com_id:       Communication identifier.
        dest_ip:      Destination IP (unicast or multicast).
        interval_us:  Publishing period in microseconds.
        data:         Initial payload bytes.
        src_ip:       Source IP filter (default: any).
        red_id:       Redundancy group ID (0 = none).
        service_id:   Service ID for service-oriented use (0 = none).
        etb_topo_cnt: ETB topology counter (0 = ignore).
        op_trn_topo_cnt: Operational-train topology counter (0 = ignore).
        pkt_flags:    Packet flags (see ``FLAGS_*`` constants).
        callback:     Optional Python callable invoked on each sent packet.
                      Signature: ``cb(pd_info: PdInfo, data: bytes) -> None``.

        Returns
        -------
        A TRDP publisher handle to pass to :meth:`put` / :meth:`unpublish`.
        """
        self._check_open()
        pub = ffi.new("TRDP_PUB_T *")
        cb_ptr, cb_cdata = self._make_pd_callback(callback)
        rc = lib.tlp_publish(
            self._handle[0], pub, ffi.NULL, cb_ptr,
            int(service_id), int(com_id),
            int(etb_topo_cnt), int(op_trn_topo_cnt),
            _ip_to_u32(src_ip), _ip_to_u32(dest_ip),
            int(interval_us), int(red_id), int(pkt_flags),
            data, len(data),
        )
        _check(rc, "tlp_publish")
        return pub[0]

    def republish(
        self,
        pub_handle,
        src_ip: str,
        dest_ip: str,
        etb_topo_cnt: int = 0,
        op_trn_topo_cnt: int = 0,
    ) -> None:
        """Update topology / addresses of an existing publisher."""
        self._check_open()
        _check(
            lib.tlp_republish(
                self._handle[0], pub_handle,
                int(etb_topo_cnt), int(op_trn_topo_cnt),
                _ip_to_u32(src_ip), _ip_to_u32(dest_ip),
            ),
            "tlp_republish",
        )

    def unpublish(self, pub_handle) -> None:
        """Stop publishing and release a publisher handle."""
        self._check_open()
        _check(lib.tlp_unpublish(self._handle[0], pub_handle), "tlp_unpublish")

    def put(self, pub_handle, data: bytes) -> None:
        """Update the payload of a publisher (sent on next cycle)."""
        self._check_open()
        _check(
            lib.tlp_put(self._handle[0], pub_handle, data, len(data)),
            "tlp_put",
        )

    def put_immediate(self, pub_handle, data: bytes) -> None:
        """Update payload and trigger immediate transmission."""
        self._check_open()
        _check(
            lib.tlp_putImmediate(
                self._handle[0], pub_handle, data, len(data), ffi.NULL
            ),
            "tlp_putImmediate",
        )

    def set_redundant(self, red_id: int, leader: bool) -> None:
        """Set redundancy state for a group (leader vs. follower)."""
        self._check_open()
        _check(
            lib.tlp_setRedundant(self._handle[0], int(red_id), int(leader)),
            "tlp_setRedundant",
        )

    def get_redundant(self, red_id: int) -> bool:
        """Return True if this session is the redundancy leader for *red_id*."""
        self._check_open()
        leader = ffi.new("BOOL8 *", 0)
        _check(
            lib.tlp_getRedundant(self._handle[0], int(red_id), leader),
            "tlp_getRedundant",
        )
        return bool(leader[0])

    # ── Process Data subscribe ───────────────────────────────────────────────

    def subscribe(
        self,
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
        callback: Optional[Callable] = None,
    ):
        """Subscribe to a periodic PD stream.

        Parameters
        ----------
        com_id:         Communication identifier to listen for.
        src_ip1:        First allowed source IP (0.0.0.0 = any).
        timeout_us:     Receive timeout in microseconds (0 = infinite).
        src_ip2:        Second allowed source IP (range end / 0 = not used).
        dest_ip:        Multicast group address (0.0.0.0 = unicast).
        to_behavior:    Behaviour on timeout (see ``TO_*`` constants).
        callback:       Optional callable invoked on each received packet.
                        Signature: ``cb(pd_info: PdInfo, data: bytes) -> None``.

        Returns
        -------
        A TRDP subscriber handle to pass to :meth:`get` / :meth:`unsubscribe`.
        """
        self._check_open()
        sub = ffi.new("TRDP_SUB_T *")
        cb_ptr, cb_cdata = self._make_pd_callback(callback)
        rc = lib.tlp_subscribe(
            self._handle[0], sub, ffi.NULL, cb_ptr,
            int(service_id), int(com_id),
            int(etb_topo_cnt), int(op_trn_topo_cnt),
            _ip_to_u32(src_ip1), _ip_to_u32(src_ip2), _ip_to_u32(dest_ip),
            int(pkt_flags), int(timeout_us), int(to_behavior),
        )
        _check(rc, "tlp_subscribe")
        return sub[0]

    def resubscribe(
        self,
        sub_handle,
        src_ip1: str,
        src_ip2: str = "0.0.0.0",
        dest_ip: str = "0.0.0.0",
        etb_topo_cnt: int = 0,
        op_trn_topo_cnt: int = 0,
    ) -> None:
        """Update topology / addresses of an existing subscriber."""
        self._check_open()
        _check(
            lib.tlp_resubscribe(
                self._handle[0], sub_handle,
                int(etb_topo_cnt), int(op_trn_topo_cnt),
                _ip_to_u32(src_ip1), _ip_to_u32(src_ip2), _ip_to_u32(dest_ip),
            ),
            "tlp_resubscribe",
        )

    def unsubscribe(self, sub_handle) -> None:
        """Unsubscribe and release a subscriber handle."""
        self._check_open()
        _check(
            lib.tlp_unsubscribe(self._handle[0], sub_handle),
            "tlp_unsubscribe",
        )

    def get(
        self, sub_handle, max_size: int = 1500
    ) -> Optional[Tuple[PdInfo, bytes]]:
        """Read the most recently received PD data.

        Returns ``None`` when no data has been received yet
        (``TRDP_NODATA_ERR``).  Raises :class:`TrdpError` for other errors.
        """
        self._check_open()
        buf = ffi.new("uint8_t[]", max_size)
        size = ffi.new("uint32_t *", max_size)
        pd_info_c = ffi.new("TRDP_PD_INFO_T *")
        rc = lib.tlp_get(self._handle[0], sub_handle, pd_info_c, buf, size)
        if rc == 0:
            data = bytes(ffi.buffer(buf, int(size[0])))
            return _pd_info_from_c(pd_info_c), data
        if int(rc) == _NODATA_ERR:
            return None
        raise TrdpError(int(rc), "tlp_get")

    def pd_request(
        self,
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
    ) -> None:
        """Send a PD pull-request (``tlp_request``)."""
        self._check_open()
        _check(
            lib.tlp_request(
                self._handle[0], sub_handle,
                int(service_id), int(com_id),
                int(etb_topo_cnt), int(op_trn_topo_cnt),
                _ip_to_u32(src_ip), _ip_to_u32(dest_ip),
                int(red_id), int(pkt_flags),
                data, len(data),
                int(reply_com_id), _ip_to_u32(reply_ip),
            ),
            "tlp_request",
        )

    # ── Message Data (MD) ────────────────────────────────────────────────────

    def md_notify(
        self,
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
        callback: Optional[Callable] = None,
    ) -> None:
        """Send a one-way MD notification (``tlm_notify``).

        The *callback* receives ``(md_info: MdInfo, data: bytes)``.
        """
        self._check_open()
        cb_ptr, cb_cdata = self._make_md_callback(callback)
        send_param = _make_com_param(qos, ttl)
        _check(
            lib.tlm_notify(
                self._handle[0], ffi.NULL, cb_ptr,
                int(com_id), int(etb_topo_cnt), int(op_trn_topo_cnt),
                _ip_to_u32(src_ip), _ip_to_u32(dest_ip),
                int(pkt_flags), send_param,
                data, len(data),
                src_uri.encode() if src_uri else ffi.NULL,
                dest_uri.encode() if dest_uri else ffi.NULL,
            ),
            "tlm_notify",
        )

    def md_request(
        self,
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
        callback: Optional[Callable] = None,
    ) -> bytes:
        """Send an MD request and return the session UUID.

        The *callback* receives ``(md_info: MdInfo, data: bytes)`` for each
        reply (and on timeout / abort).

        Returns
        -------
        16-byte session UUID (``TRDP_UUID_T``).
        """
        self._check_open()
        cb_ptr, cb_cdata = self._make_md_callback(callback)
        session_id = ffi.new("TRDP_UUID_T *")
        send_param = _make_com_param(qos, ttl)
        _check(
            lib.tlm_request(
                self._handle[0], ffi.NULL, cb_ptr, session_id,
                int(com_id), int(etb_topo_cnt), int(op_trn_topo_cnt),
                _ip_to_u32(src_ip), _ip_to_u32(dest_ip),
                int(pkt_flags), int(num_replies), int(reply_timeout_us),
                send_param,
                data, len(data),
                src_uri.encode() if src_uri else ffi.NULL,
                dest_uri.encode() if dest_uri else ffi.NULL,
            ),
            "tlm_request",
        )
        return bytes(ffi.buffer(session_id, 16))

    def md_reply(
        self,
        session_id: bytes,
        com_id: int,
        data: bytes = b"",
        *,
        user_status: int = 0,
        src_uri: str = "",
        qos: int = 2,
        ttl: int = 64,
    ) -> None:
        """Send an MD reply (``tlm_reply``)."""
        self._check_open()
        sid = ffi.from_buffer("TRDP_UUID_T *", bytearray(session_id))
        send_param = _make_com_param(qos, ttl)
        _check(
            lib.tlm_reply(
                self._handle[0], sid, int(com_id), int(user_status),
                send_param, data, len(data),
                src_uri.encode() if src_uri else ffi.NULL,
            ),
            "tlm_reply",
        )

    def md_reply_query(
        self,
        session_id: bytes,
        com_id: int,
        data: bytes = b"",
        *,
        user_status: int = 0,
        confirm_timeout_us: int = 1_000_000,
        src_uri: str = "",
        qos: int = 2,
        ttl: int = 64,
    ) -> None:
        """Send an MD reply that requires a confirmation (``tlm_replyQuery``)."""
        self._check_open()
        sid = ffi.from_buffer("TRDP_UUID_T *", bytearray(session_id))
        send_param = _make_com_param(qos, ttl)
        _check(
            lib.tlm_replyQuery(
                self._handle[0], sid, int(com_id), int(user_status),
                int(confirm_timeout_us), send_param, data, len(data),
                src_uri.encode() if src_uri else ffi.NULL,
            ),
            "tlm_replyQuery",
        )

    def md_confirm(
        self,
        session_id: bytes,
        user_status: int = 0,
        qos: int = 2,
        ttl: int = 64,
    ) -> None:
        """Confirm receipt of a reply (``tlm_confirm``)."""
        self._check_open()
        sid = ffi.from_buffer("TRDP_UUID_T *", bytearray(session_id))
        send_param = _make_com_param(qos, ttl)
        _check(
            lib.tlm_confirm(self._handle[0], sid, int(user_status), send_param),
            "tlm_confirm",
        )

    def md_abort(self, session_id: bytes) -> None:
        """Abort an in-progress MD session (``tlm_abortSession``)."""
        self._check_open()
        sid = ffi.from_buffer("TRDP_UUID_T *", bytearray(session_id))
        _check(lib.tlm_abortSession(self._handle[0], sid), "tlm_abortSession")

    def md_add_listener(
        self,
        com_id: int,
        callback: Callable,
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
    ):
        """Add an MD listener (``tlm_addListener``).

        The *callback* receives ``(md_info: MdInfo, data: bytes)``.

        Returns
        -------
        Listener handle to pass to :meth:`md_del_listener`.
        """
        self._check_open()
        lis = ffi.new("TRDP_LIS_T *")
        cb_ptr, cb_cdata = self._make_md_callback(callback)
        _check(
            lib.tlm_addListener(
                self._handle[0], lis, ffi.NULL, cb_ptr,
                int(com_id_listener), int(com_id),
                int(etb_topo_cnt), int(op_trn_topo_cnt),
                _ip_to_u32(src_ip1), _ip_to_u32(src_ip2), _ip_to_u32(mc_dest_ip),
                int(pkt_flags),
                src_uri.encode() if src_uri else ffi.NULL,
                dest_uri.encode() if dest_uri else ffi.NULL,
            ),
            "tlm_addListener",
        )
        return lis[0]

    def md_readd_listener(
        self,
        lis_handle,
        src_ip: str = "0.0.0.0",
        src_ip2: str = "0.0.0.0",
        mc_dest_ip: str = "0.0.0.0",
        etb_topo_cnt: int = 0,
        op_trn_topo_cnt: int = 0,
    ) -> None:
        """Re-add a listener with updated topology / addresses."""
        self._check_open()
        _check(
            lib.tlm_readdListener(
                self._handle[0], lis_handle,
                int(etb_topo_cnt), int(op_trn_topo_cnt),
                _ip_to_u32(src_ip), _ip_to_u32(src_ip2), _ip_to_u32(mc_dest_ip),
            ),
            "tlm_readdListener",
        )

    def md_del_listener(self, lis_handle) -> None:
        """Remove an MD listener."""
        self._check_open()
        _check(lib.tlm_delListener(self._handle[0], lis_handle), "tlm_delListener")

    # ── Statistics ───────────────────────────────────────────────────────────

    def get_statistics(self) -> Statistics:
        """Return global session statistics."""
        self._check_open()
        c_stats = ffi.new("TRDP_STATISTICS_T *")
        _check(lib.tlc_getStatistics(self._handle[0], c_stats), "tlc_getStatistics")
        s = c_stats
        return Statistics(
            version=int(lib._trdpy_stats_version(s)),
            up_time_s=int(lib._trdpy_stats_uptime(s)),
            statistic_time_s=int(lib._trdpy_stats_stat_time(s)),
            own_ip=_u32_to_ip(lib._trdpy_stats_own_ip(s)),
            leader_ip=_u32_to_ip(lib._trdpy_stats_leader_ip(s)),
            num_red=int(lib._trdpy_stats_num_red(s)),
            num_join=int(lib._trdpy_stats_num_join(s)),
            pd_num_subs=int(lib._trdpy_stats_pd_num_subs(s)),
            pd_num_pub=int(lib._trdpy_stats_pd_num_pub(s)),
            pd_num_send=int(lib._trdpy_stats_pd_num_send(s)),
            pd_num_rcv=int(lib._trdpy_stats_pd_num_rcv(s)),
            pd_num_timeout=int(lib._trdpy_stats_pd_num_timeout(s)),
            pd_num_crc_err=int(lib._trdpy_stats_pd_num_crc_err(s)),
            udp_md_num_send=int(lib._trdpy_stats_udpmd_num_send(s)),
            udp_md_num_rcv=int(lib._trdpy_stats_udpmd_num_rcv(s)),
            udp_md_num_timeout=int(lib._trdpy_stats_udpmd_num_timeout(s)),
            tcp_md_num_send=int(lib._trdpy_stats_tcpmd_num_send(s)),
            tcp_md_num_rcv=int(lib._trdpy_stats_tcpmd_num_rcv(s)),
        )

    def get_subs_statistics(self, max_count: int = 64) -> List[SubsStatistics]:
        """Return per-subscription statistics."""
        self._check_open()
        n = ffi.new("uint16_t *", max_count)
        arr = ffi.new("TRDP_SUBS_STATISTICS_T[]", max_count)
        _check(lib.tlc_getSubsStatistics(self._handle[0], n, arr),
               "tlc_getSubsStatistics")
        result = []
        for i in range(int(n[0])):
            s = arr + i
            result.append(SubsStatistics(
                com_id=int(lib._trdpy_subs_comid(s)),
                joined_addr=_u32_to_ip(lib._trdpy_subs_joined_addr(s)),
                filter_addr=_u32_to_ip(lib._trdpy_subs_filter_addr(s)),
                timeout_us=int(lib._trdpy_subs_timeout(s)),
                status=int(lib._trdpy_subs_status(s)),
                num_rcv=int(lib._trdpy_subs_num_rcv(s)),
                num_missed=int(lib._trdpy_subs_num_missed(s)),
            ))
        return result

    def get_pub_statistics(self, max_count: int = 64) -> List[PubStatistics]:
        """Return per-publisher statistics."""
        self._check_open()
        n = ffi.new("uint16_t *", max_count)
        arr = ffi.new("TRDP_PUB_STATISTICS_T[]", max_count)
        _check(lib.tlc_getPubStatistics(self._handle[0], n, arr),
               "tlc_getPubStatistics")
        result = []
        for i in range(int(n[0])):
            s = arr + i
            result.append(PubStatistics(
                com_id=int(lib._trdpy_pub_comid(s)),
                dest_addr=_u32_to_ip(lib._trdpy_pub_dest_addr(s)),
                cycle_us=int(lib._trdpy_pub_cycle(s)),
                red_id=int(lib._trdpy_pub_red_id(s)),
                red_state=int(lib._trdpy_pub_red_state(s)),
                num_put=int(lib._trdpy_pub_num_put(s)),
                num_send=int(lib._trdpy_pub_num_send(s)),
            ))
        return result

    def reset_statistics(self) -> None:
        """Reset all statistic counters."""
        self._check_open()
        _check(lib.tlc_resetStatistics(self._handle[0]), "tlc_resetStatistics")

    def get_join_addresses(self, max_count: int = 64) -> List[str]:
        """Return the list of multicast groups joined by this session."""
        self._check_open()
        n = ffi.new("uint16_t *", max_count)
        arr = ffi.new("uint32_t[]", max_count)
        _check(lib.tlc_getJoinStatistics(self._handle[0], n, arr),
               "tlc_getJoinStatistics")
        return [_u32_to_ip(int(arr[i])) for i in range(int(n[0]))]

    # ── Internal helpers ─────────────────────────────────────────────────────

    def _check_open(self) -> None:
        if not self._open:
            raise RuntimeError("Session is not open; call open() first")

    def _make_pd_callback(self, callback: Optional[Callable]):
        """Wrap a Python callable as a TRDP_PD_CALLBACK_T."""
        if callback is None:
            return ffi.NULL, None

        @ffi.callback(
            "void(void *, TRDP_APP_SESSION_T, const TRDP_PD_INFO_T *, uint8_t *, uint32_t)"
        )
        def _cb(pRefCon, appHandle, pMsg, pData, dataSize):
            try:
                info = _pd_info_from_c(pMsg)
                data = bytes(ffi.buffer(pData, int(dataSize))) if dataSize else b""
                callback(info, data)
            except Exception:
                pass  # Cannot propagate exceptions across C boundary

        self._callbacks.append(_cb)
        return _cb, _cb

    def _make_md_callback(self, callback: Optional[Callable]):
        """Wrap a Python callable as a TRDP_MD_CALLBACK_T."""
        if callback is None:
            return ffi.NULL, None

        @ffi.callback(
            "void(void *, TRDP_APP_SESSION_T, const TRDP_MD_INFO_T *, uint8_t *, uint32_t)"
        )
        def _cb(pRefCon, appHandle, pMsg, pData, dataSize):
            try:
                info = _md_info_from_c(pMsg)
                data = bytes(ffi.buffer(pData, int(dataSize))) if dataSize else b""
                callback(info, data)
            except Exception:
                pass

        self._callbacks.append(_cb)
        return _cb, _cb


# ---------------------------------------------------------------------------
# Module-level helpers
# ---------------------------------------------------------------------------

def _check(rc: int, fn: str) -> None:
    """Raise TrdpError if *rc* is not TRDP_NO_ERR."""
    if int(rc) != 0:
        raise TrdpError(int(rc), fn)


def _make_com_param(qos: int = 2, ttl: int = 64, retries: int = 0):
    """Allocate and return a TRDP_COM_PARAM_T cdata struct."""
    p = ffi.new("TRDP_COM_PARAM_T *")
    p.qos = qos
    p.ttl = ttl
    p.retries = retries
    return p
