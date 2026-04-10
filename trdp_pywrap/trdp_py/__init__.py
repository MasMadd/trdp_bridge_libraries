"""trdp_py – Python wrapper for the TCNOpen TRDP Light C library.

Public API
----------
Session
    Main class: open a TRDP session, publish / subscribe PD, send / receive MD.

TrdpError
    Exception raised on any non-zero TRDP return code.

TrdpErrCode
    IntEnum of all ``TRDP_ERR_T`` error codes.

PdInfo, MdInfo
    Metadata returned with received telegrams.

Statistics, SubsStatistics, PubStatistics
    Statistics data classes returned by Session.get_statistics() etc.

Constants
---------
FLAGS_DEFAULT, FLAGS_NONE, FLAGS_MARSHALL, FLAGS_CALLBACK, FLAGS_TCP, FLAGS_FORCE_CB
    Packet-flag constants (``TRDP_FLAGS_T``).

TO_DEFAULT, TO_SET_TO_ZERO, TO_KEEP_LAST_VALUE
    PD timeout-behaviour constants (``TRDP_TO_BEHAVIOR_T``).

RED_FOLLOWER, RED_LEADER
    Redundancy-state constants.
"""

from .session import (
    # Core
    Session,
    TrdpError,
    TrdpErrCode,
    # Data classes
    PdInfo,
    MdInfo,
    Statistics,
    SubsStatistics,
    PubStatistics,
    # Packet flags
    FLAGS_DEFAULT,
    FLAGS_NONE,
    FLAGS_MARSHALL,
    FLAGS_CALLBACK,
    FLAGS_TCP,
    FLAGS_FORCE_CB,
    # Timeout behaviours
    TO_DEFAULT,
    TO_SET_TO_ZERO,
    TO_KEEP_LAST_VALUE,
    # Redundancy states
    RED_FOLLOWER,
    RED_LEADER,
)

__all__ = [
    "Session",
    "TrdpError",
    "TrdpErrCode",
    "PdInfo",
    "MdInfo",
    "Statistics",
    "SubsStatistics",
    "PubStatistics",
    "FLAGS_DEFAULT",
    "FLAGS_NONE",
    "FLAGS_MARSHALL",
    "FLAGS_CALLBACK",
    "FLAGS_TCP",
    "FLAGS_FORCE_CB",
    "TO_DEFAULT",
    "TO_SET_TO_ZERO",
    "TO_KEEP_LAST_VALUE",
    "RED_FOLLOWER",
    "RED_LEADER",
]
