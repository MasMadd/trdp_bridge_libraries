"""CFFI build script for trdp_py.

Compiles a C extension module ``trdp_py._trdp_c`` that links against libtrdp.
The library must have been built with **MD_SUPPORT=1** to expose message-data
functions (tlm_*).

Environment variables (required at build time):
    TRDP_INCLUDE_DIRS   colon-separated include directories for TRDP headers
                        (at minimum the directories containing trdp_if_light.h,
                         vos_types.h and related headers)
    TRDP_LIB_DIR        directory containing libtrdp.so or libtrdp.a

Example::

    export TRDP_INCLUDE_DIRS=/path/to/TRDP/3.0.0.0/src/api:/path/to/TRDP/3.0.0.0/src/vos/api
    export TRDP_LIB_DIR=/path/to/TRDP/3.0.0.0/bld/output/linux-x86_64-rel
    pip install -e .
"""

import os
from cffi import FFI

ffi = FFI()

# ---------------------------------------------------------------------------
# C declarations visible to Python.
#
# Rules used here:
#   • Opaque handle types are forward-declared as pointers to incomplete structs.
#   • GNU_PACKED structs (e.g. TRDP_PD_INFO_T) are declared with only the
#     fields that appear *before* any pointer or misaligned field, followed
#     by "...;" so CFFI (API mode) uses the compiler to determine real offsets.
#   • All remaining packed / complex structs are fully opaque ("...;").
#   • Helper functions (_trdpy_*) implemented in set_source expose the packed
#     fields that cannot be safely declared in a portable cdef.
# ---------------------------------------------------------------------------

ffi.cdef(
    r"""
/* ── opaque session / handle types ── */
typedef struct TRDP_SESSION   *TRDP_APP_SESSION_T;
typedef struct PD_ELE         *TRDP_PUB_T;
typedef struct PD_ELE         *TRDP_SUB_T;
typedef struct MD_LIS_ELE     *TRDP_LIS_T;

/* ── primitive type aliases ── */
typedef int32_t   TRDP_ERR_T;
typedef uint32_t  TRDP_IP_ADDR_T;
typedef uint8_t   TRDP_FLAGS_T;
typedef uint16_t  TRDP_MSG_T;
typedef int32_t   TRDP_SOCK_T;
typedef uint8_t   BOOL8;
typedef uint8_t   TRDP_UUID_T[16];

/* ── time and fd-set (Linux x86-64 layout) ── */
typedef struct { long tv_sec; long tv_usec; } TRDP_TIME_T;
typedef struct { unsigned long fds_bits[16]; } TRDP_FDS_T;

/* ── QoS / TTL / retries (non-packed, safe to declare fully) ── */
typedef struct { uint8_t qos; uint8_t ttl; uint8_t retries; } TRDP_COM_PARAM_T;

/* ── enum aliases ── */
typedef uint32_t TRDP_TO_BEHAVIOR_T;
typedef int32_t  TRDP_REPLY_STATUS_T;

/* ── PD info struct ─────────────────────────────────────────────────────────
   GNU_PACKED: only declare fields before the first pointer (pUserRef at
   offset 36 on 64-bit) to avoid alignment mismatch in the cdef struct.
   Remaining fields are accessed via helper functions.                        */
typedef struct {
    uint32_t srcIpAddr;
    uint32_t destIpAddr;
    uint32_t seqCount;
    uint16_t protVersion;
    uint16_t msgType;
    uint32_t comId;
    uint32_t etbTopoCnt;
    uint32_t opTrnTopoCnt;
    uint32_t replyComId;
    uint32_t replyIpAddr;
    ...;
} TRDP_PD_INFO_T;

/* ── MD info struct ─────────────────────────────────────────────────────────
   GNU_PACKED: safe fields stop before aboutToDie (uint8_t at offset 28)
   which breaks alignment for the following uint32_t fields.                  */
typedef struct {
    uint32_t srcIpAddr;
    uint32_t destIpAddr;
    uint32_t seqCount;
    uint16_t protVersion;
    uint16_t msgType;
    uint32_t comId;
    uint32_t etbTopoCnt;
    uint32_t opTrnTopoCnt;
    ...;
} TRDP_MD_INFO_T;

/* ── opaque configuration structs ── */
typedef struct { ...; } TRDP_MEM_CONFIG_T;
typedef struct { ...; } TRDP_MARSHALL_CONFIG_T;
typedef struct { ...; } TRDP_PD_CONFIG_T;
typedef struct { ...; } TRDP_MD_CONFIG_T;
typedef struct { ...; } TRDP_PROCESS_CONFIG_T;
typedef struct { ...; } TRDP_IDX_TABLE_T;

/* ── opaque statistics structs ── */
typedef struct { ...; } TRDP_STATISTICS_T;
typedef struct { ...; } TRDP_SUBS_STATISTICS_T;
typedef struct { ...; } TRDP_PUB_STATISTICS_T;
typedef struct { ...; } TRDP_LIST_STATISTICS_T;
typedef struct { ...; } TRDP_RED_STATISTICS_T;

/* ── callback function pointer types ── */
typedef void (*TRDP_PRINT_DBG_T)(
    void *pRefCon, const char *pTime, const char *pFile,
    uint16_t LineNumber, const char *pMsgStr);

typedef void (*TRDP_PD_CALLBACK_T)(
    void *pRefCon, TRDP_APP_SESSION_T appHandle,
    const TRDP_PD_INFO_T *pMsg, uint8_t *pData, uint32_t dataSize);

typedef void (*TRDP_MD_CALLBACK_T)(
    void *pRefCon, TRDP_APP_SESSION_T appHandle,
    const TRDP_MD_INFO_T *pMsg, uint8_t *pData, uint32_t dataSize);


/* ═══════════════════════════════════════════════════════════════════════════
   Helper functions implemented in C (set_source block below) to safely read
   GNU_PACKED struct fields that cannot be accessed portably via cdef.
   ═══════════════════════════════════════════════════════════════════════════ */

/* PD info helpers */
TRDP_ERR_T    _trdpy_pd_result_code(const TRDP_PD_INFO_T *p);
uint32_t      _trdpy_pd_service_id(const TRDP_PD_INFO_T *p);

/* MD info helpers */
const uint8_t *_trdpy_md_session_id(const TRDP_MD_INFO_T *p);
TRDP_ERR_T    _trdpy_md_result_code(const TRDP_MD_INFO_T *p);
uint16_t      _trdpy_md_user_status(const TRDP_MD_INFO_T *p);
int32_t       _trdpy_md_reply_status(const TRDP_MD_INFO_T *p);
uint8_t       _trdpy_md_about_to_die(const TRDP_MD_INFO_T *p);
uint32_t      _trdpy_md_num_replies(const TRDP_MD_INFO_T *p);
uint32_t      _trdpy_md_num_exp_replies(const TRDP_MD_INFO_T *p);
const char   *_trdpy_md_src_user_uri(const TRDP_MD_INFO_T *p);
const char   *_trdpy_md_dest_user_uri(const TRDP_MD_INFO_T *p);
uint32_t      _trdpy_md_reply_timeout(const TRDP_MD_INFO_T *p);

/* TRDP_STATISTICS_T helpers */
uint32_t _trdpy_stats_version(const TRDP_STATISTICS_T *s);
uint32_t _trdpy_stats_uptime(const TRDP_STATISTICS_T *s);
uint32_t _trdpy_stats_stat_time(const TRDP_STATISTICS_T *s);
uint32_t _trdpy_stats_own_ip(const TRDP_STATISTICS_T *s);
uint32_t _trdpy_stats_leader_ip(const TRDP_STATISTICS_T *s);
uint32_t _trdpy_stats_num_red(const TRDP_STATISTICS_T *s);
uint32_t _trdpy_stats_num_join(const TRDP_STATISTICS_T *s);
uint32_t _trdpy_stats_pd_num_subs(const TRDP_STATISTICS_T *s);
uint32_t _trdpy_stats_pd_num_pub(const TRDP_STATISTICS_T *s);
uint32_t _trdpy_stats_pd_num_send(const TRDP_STATISTICS_T *s);
uint32_t _trdpy_stats_pd_num_rcv(const TRDP_STATISTICS_T *s);
uint32_t _trdpy_stats_pd_num_timeout(const TRDP_STATISTICS_T *s);
uint32_t _trdpy_stats_pd_num_crc_err(const TRDP_STATISTICS_T *s);
uint32_t _trdpy_stats_udpmd_num_send(const TRDP_STATISTICS_T *s);
uint32_t _trdpy_stats_udpmd_num_rcv(const TRDP_STATISTICS_T *s);
uint32_t _trdpy_stats_udpmd_num_timeout(const TRDP_STATISTICS_T *s);
uint32_t _trdpy_stats_tcpmd_num_send(const TRDP_STATISTICS_T *s);
uint32_t _trdpy_stats_tcpmd_num_rcv(const TRDP_STATISTICS_T *s);

/* TRDP_SUBS_STATISTICS_T helpers */
uint32_t _trdpy_subs_comid(const TRDP_SUBS_STATISTICS_T *s);
uint32_t _trdpy_subs_joined_addr(const TRDP_SUBS_STATISTICS_T *s);
uint32_t _trdpy_subs_filter_addr(const TRDP_SUBS_STATISTICS_T *s);
uint32_t _trdpy_subs_timeout(const TRDP_SUBS_STATISTICS_T *s);
uint32_t _trdpy_subs_status(const TRDP_SUBS_STATISTICS_T *s);
uint32_t _trdpy_subs_num_rcv(const TRDP_SUBS_STATISTICS_T *s);
uint32_t _trdpy_subs_num_missed(const TRDP_SUBS_STATISTICS_T *s);

/* TRDP_PUB_STATISTICS_T helpers */
uint32_t _trdpy_pub_comid(const TRDP_PUB_STATISTICS_T *s);
uint32_t _trdpy_pub_dest_addr(const TRDP_PUB_STATISTICS_T *s);
uint32_t _trdpy_pub_cycle(const TRDP_PUB_STATISTICS_T *s);
uint32_t _trdpy_pub_red_id(const TRDP_PUB_STATISTICS_T *s);
uint32_t _trdpy_pub_red_state(const TRDP_PUB_STATISTICS_T *s);
uint32_t _trdpy_pub_num_put(const TRDP_PUB_STATISTICS_T *s);
uint32_t _trdpy_pub_num_send(const TRDP_PUB_STATISTICS_T *s);


/* ═══════════════════════════════════════════════════════════════════════════
   TRDP Light API function prototypes
   ═══════════════════════════════════════════════════════════════════════════ */

/* ── Session lifecycle ── */
TRDP_ERR_T tlc_init(
    TRDP_PRINT_DBG_T        pPrintDebugString,
    void                   *pRefCon,
    const TRDP_MEM_CONFIG_T *pMemConfig);

TRDP_ERR_T tlc_terminate(void);

TRDP_ERR_T tlc_openSession(
    TRDP_APP_SESSION_T          *pAppHandle,
    TRDP_IP_ADDR_T               ownIpAddr,
    TRDP_IP_ADDR_T               leaderIpAddr,
    const TRDP_MARSHALL_CONFIG_T *pMarshall,
    const TRDP_PD_CONFIG_T       *pPdDefault,
    const TRDP_MD_CONFIG_T       *pMdDefault,
    const TRDP_PROCESS_CONFIG_T  *pProcessConfig);

TRDP_ERR_T tlc_closeSession(TRDP_APP_SESSION_T appHandle);
TRDP_ERR_T tlc_reinitSession(TRDP_APP_SESSION_T appHandle);
TRDP_ERR_T tlc_updateSession(TRDP_APP_SESSION_T appHandle);
TRDP_ERR_T tlc_presetIndexSession(
    TRDP_APP_SESSION_T appHandle, TRDP_IDX_TABLE_T *pIndexTableSizes);

/* ── Event loop ── */
TRDP_ERR_T tlc_getInterval(
    TRDP_APP_SESSION_T  appHandle,
    TRDP_TIME_T        *pInterval,
    TRDP_FDS_T         *pFileDesc,
    TRDP_SOCK_T        *pNoDesc);

TRDP_ERR_T tlc_process(
    TRDP_APP_SESSION_T  appHandle,
    TRDP_FDS_T         *pRfds,
    int32_t            *pCount);

/* ── Topology ── */
TRDP_ERR_T tlc_setETBTopoCount(TRDP_APP_SESSION_T appHandle, uint32_t etbTopoCnt);
uint32_t   tlc_getETBTopoCount(TRDP_APP_SESSION_T appHandle);
TRDP_ERR_T tlc_setOpTrainTopoCount(TRDP_APP_SESSION_T appHandle, uint32_t opTrnTopoCnt);
uint32_t   tlc_getOpTrainTopoCount(TRDP_APP_SESSION_T appHandle);

/* ── Misc ── */
TRDP_IP_ADDR_T tlc_getOwnIpAddress(TRDP_APP_SESSION_T appHandle);
const char    *tlc_getVersionString(void);

/* ── Statistics ── */
TRDP_ERR_T tlc_getStatistics(
    TRDP_APP_SESSION_T appHandle, TRDP_STATISTICS_T *pStatistics);
TRDP_ERR_T tlc_getSubsStatistics(
    TRDP_APP_SESSION_T appHandle, uint16_t *pNumSubs,
    TRDP_SUBS_STATISTICS_T *pStatistics);
TRDP_ERR_T tlc_getPubStatistics(
    TRDP_APP_SESSION_T appHandle, uint16_t *pNumPub,
    TRDP_PUB_STATISTICS_T *pStatistics);
TRDP_ERR_T tlc_getUdpListStatistics(
    TRDP_APP_SESSION_T appHandle, uint16_t *pNumList,
    TRDP_LIST_STATISTICS_T *pStatistics);
TRDP_ERR_T tlc_getTcpListStatistics(
    TRDP_APP_SESSION_T appHandle, uint16_t *pNumList,
    TRDP_LIST_STATISTICS_T *pStatistics);
TRDP_ERR_T tlc_getRedStatistics(
    TRDP_APP_SESSION_T appHandle, uint16_t *pNumRed,
    TRDP_RED_STATISTICS_T *pStatistics);
TRDP_ERR_T tlc_getJoinStatistics(
    TRDP_APP_SESSION_T appHandle, uint16_t *pNumJoin, uint32_t *pIpAddr);
TRDP_ERR_T tlc_resetStatistics(TRDP_APP_SESSION_T appHandle);


/* ── Process Data (PD) ── */
TRDP_ERR_T tlp_getInterval(
    TRDP_APP_SESSION_T  appHandle,
    TRDP_TIME_T        *pInterval,
    TRDP_FDS_T         *pFileDesc,
    TRDP_SOCK_T        *pNoDesc);

TRDP_ERR_T tlp_processSend(TRDP_APP_SESSION_T appHandle);
TRDP_ERR_T tlp_processReceive(
    TRDP_APP_SESSION_T  appHandle,
    TRDP_FDS_T         *pRfds,
    int32_t            *pCount);

TRDP_ERR_T tlp_publish(
    TRDP_APP_SESSION_T  appHandle,
    TRDP_PUB_T         *pPubHandle,
    const void         *pUserRef,
    TRDP_PD_CALLBACK_T  pfCbFunction,
    uint32_t            serviceId,
    uint32_t            comId,
    uint32_t            etbTopoCnt,
    uint32_t            opTrnTopoCnt,
    TRDP_IP_ADDR_T      srcIpAddr,
    TRDP_IP_ADDR_T      destIpAddr,
    uint32_t            interval,
    uint32_t            redId,
    TRDP_FLAGS_T        pktFlags,
    const uint8_t      *pData,
    uint32_t            dataSize);

TRDP_ERR_T tlp_republish(
    TRDP_APP_SESSION_T  appHandle,
    TRDP_PUB_T          pubHandle,
    uint32_t            etbTopoCnt,
    uint32_t            opTrnTopoCnt,
    TRDP_IP_ADDR_T      srcIpAddr,
    TRDP_IP_ADDR_T      destIpAddr);

TRDP_ERR_T tlp_unpublish(TRDP_APP_SESSION_T appHandle, TRDP_PUB_T pubHandle);

TRDP_ERR_T tlp_put(
    TRDP_APP_SESSION_T  appHandle,
    TRDP_PUB_T          pubHandle,
    const uint8_t      *pData,
    uint32_t            dataSize);

TRDP_ERR_T tlp_putImmediate(
    TRDP_APP_SESSION_T  appHandle,
    TRDP_PUB_T          pubHandle,
    const uint8_t      *pData,
    uint32_t            dataSize,
    TRDP_TIME_T        *pTxTime);

TRDP_ERR_T tlp_setRedundant(
    TRDP_APP_SESSION_T  appHandle,
    uint32_t            redId,
    BOOL8               leader);

TRDP_ERR_T tlp_getRedundant(
    TRDP_APP_SESSION_T  appHandle,
    uint32_t            redId,
    BOOL8              *pLeader);

TRDP_ERR_T tlp_request(
    TRDP_APP_SESSION_T  appHandle,
    TRDP_SUB_T          subHandle,
    uint32_t            serviceId,
    uint32_t            comId,
    uint32_t            etbTopoCnt,
    uint32_t            opTrnTopoCnt,
    TRDP_IP_ADDR_T      srcIpAddr,
    TRDP_IP_ADDR_T      destIpAddr,
    uint32_t            redId,
    TRDP_FLAGS_T        pktFlags,
    const uint8_t      *pData,
    uint32_t            dataSize,
    uint32_t            replyComId,
    TRDP_IP_ADDR_T      replyIpAddr);

TRDP_ERR_T tlp_subscribe(
    TRDP_APP_SESSION_T  appHandle,
    TRDP_SUB_T         *pSubHandle,
    const void         *pUserRef,
    TRDP_PD_CALLBACK_T  pfCbFunction,
    uint32_t            serviceId,
    uint32_t            comId,
    uint32_t            etbTopoCnt,
    uint32_t            opTrnTopoCnt,
    TRDP_IP_ADDR_T      srcIpAddr1,
    TRDP_IP_ADDR_T      srcIpAddr2,
    TRDP_IP_ADDR_T      destIpAddr,
    TRDP_FLAGS_T        pktFlags,
    uint32_t            timeout,
    TRDP_TO_BEHAVIOR_T  toBehavior);

TRDP_ERR_T tlp_resubscribe(
    TRDP_APP_SESSION_T  appHandle,
    TRDP_SUB_T          subHandle,
    uint32_t            etbTopoCnt,
    uint32_t            opTrnTopoCnt,
    TRDP_IP_ADDR_T      srcIpAddr1,
    TRDP_IP_ADDR_T      srcIpAddr2,
    TRDP_IP_ADDR_T      destIpAddr);

TRDP_ERR_T tlp_unsubscribe(TRDP_APP_SESSION_T appHandle, TRDP_SUB_T subHandle);

TRDP_ERR_T tlp_get(
    TRDP_APP_SESSION_T  appHandle,
    TRDP_SUB_T          subHandle,
    TRDP_PD_INFO_T     *pPdInfo,
    uint8_t            *pData,
    uint32_t           *pDataSize);


/* ── Message Data (MD) ── MD_SUPPORT=1 required in libtrdp ── */
TRDP_ERR_T tlm_getInterval(
    TRDP_APP_SESSION_T  appHandle,
    TRDP_TIME_T        *pInterval,
    TRDP_FDS_T         *pFileDesc,
    TRDP_SOCK_T        *pNoDesc);

TRDP_ERR_T tlm_process(
    TRDP_APP_SESSION_T  appHandle,
    TRDP_FDS_T         *pRfds,
    int32_t            *pCount);

TRDP_ERR_T tlm_notify(
    TRDP_APP_SESSION_T       appHandle,
    const void              *pUserRef,
    TRDP_MD_CALLBACK_T       pfCbFunction,
    uint32_t                 comId,
    uint32_t                 etbTopoCnt,
    uint32_t                 opTrnTopoCnt,
    TRDP_IP_ADDR_T           srcIpAddr,
    TRDP_IP_ADDR_T           destIpAddr,
    TRDP_FLAGS_T             pktFlags,
    const TRDP_COM_PARAM_T  *pSendParam,
    const uint8_t           *pData,
    uint32_t                 dataSize,
    const char              *srcURI,
    const char              *destURI);

TRDP_ERR_T tlm_request(
    TRDP_APP_SESSION_T       appHandle,
    const void              *pUserRef,
    TRDP_MD_CALLBACK_T       pfCbFunction,
    TRDP_UUID_T             *pSessionId,
    uint32_t                 comId,
    uint32_t                 etbTopoCnt,
    uint32_t                 opTrnTopoCnt,
    TRDP_IP_ADDR_T           srcIpAddr,
    TRDP_IP_ADDR_T           destIpAddr,
    TRDP_FLAGS_T             pktFlags,
    uint32_t                 numReplies,
    uint32_t                 replyTimeout,
    const TRDP_COM_PARAM_T  *pSendParam,
    const uint8_t           *pData,
    uint32_t                 dataSize,
    const char              *srcURI,
    const char              *destURI);

TRDP_ERR_T tlm_confirm(
    TRDP_APP_SESSION_T       appHandle,
    const TRDP_UUID_T       *pSessionId,
    uint16_t                 userStatus,
    const TRDP_COM_PARAM_T  *pSendParam);

TRDP_ERR_T tlm_abortSession(
    TRDP_APP_SESSION_T  appHandle,
    const TRDP_UUID_T  *pSessionId);

TRDP_ERR_T tlm_addListener(
    TRDP_APP_SESSION_T       appHandle,
    TRDP_LIS_T              *pListenHandle,
    const void              *pUserRef,
    TRDP_MD_CALLBACK_T       pfCbFunction,
    BOOL8                    comIdListener,
    uint32_t                 comId,
    uint32_t                 etbTopoCnt,
    uint32_t                 opTrnTopoCnt,
    TRDP_IP_ADDR_T           srcIpAddr1,
    TRDP_IP_ADDR_T           srcIpAddr2,
    TRDP_IP_ADDR_T           mcDestIpAddr,
    TRDP_FLAGS_T             pktFlags,
    const char              *srcURI,
    const char              *destURI);

TRDP_ERR_T tlm_readdListener(
    TRDP_APP_SESSION_T  appHandle,
    TRDP_LIS_T          listenHandle,
    uint32_t            etbTopoCnt,
    uint32_t            opTrnTopoCnt,
    TRDP_IP_ADDR_T      srcIpAddr,
    TRDP_IP_ADDR_T      srcIpAddr2,
    TRDP_IP_ADDR_T      mcDestIpAddr);

TRDP_ERR_T tlm_delListener(
    TRDP_APP_SESSION_T  appHandle,
    TRDP_LIS_T          listenHandle);

TRDP_ERR_T tlm_reply(
    TRDP_APP_SESSION_T       appHandle,
    const TRDP_UUID_T       *pSessionId,
    uint32_t                 comId,
    uint32_t                 userStatus,
    const TRDP_COM_PARAM_T  *pSendParam,
    const uint8_t           *pData,
    uint32_t                 dataSize,
    const char              *srcURI);

TRDP_ERR_T tlm_replyQuery(
    TRDP_APP_SESSION_T       appHandle,
    const TRDP_UUID_T       *pSessionId,
    uint32_t                 comId,
    uint32_t                 userStatus,
    uint32_t                 confirmTimeout,
    const TRDP_COM_PARAM_T  *pSendParam,
    const uint8_t           *pData,
    uint32_t                 dataSize,
    const char              *srcURI);
"""
)

# ---------------------------------------------------------------------------
# C source compiled into the extension module.
# Includes the real TRDP headers (MD_SUPPORT=1) and provides thin helper
# functions that let Python read GNU_PACKED struct fields safely.
# ---------------------------------------------------------------------------

_C_SOURCE = r"""
#define MD_SUPPORT 1
#include <stdint.h>
#include "trdp_if_light.h"

/* ── PD info field accessors ── */
TRDP_ERR_T _trdpy_pd_result_code(const TRDP_PD_INFO_T *p) { return p->resultCode; }
uint32_t   _trdpy_pd_service_id (const TRDP_PD_INFO_T *p) { return p->serviceId;  }

/* ── MD info field accessors ── */
const uint8_t *_trdpy_md_session_id   (const TRDP_MD_INFO_T *p) { return (const uint8_t *)&p->sessionId; }
TRDP_ERR_T    _trdpy_md_result_code   (const TRDP_MD_INFO_T *p) { return p->resultCode;    }
uint16_t      _trdpy_md_user_status   (const TRDP_MD_INFO_T *p) { return p->userStatus;    }
int32_t       _trdpy_md_reply_status  (const TRDP_MD_INFO_T *p) { return (int32_t)p->replyStatus; }
uint8_t       _trdpy_md_about_to_die  (const TRDP_MD_INFO_T *p) { return (uint8_t)p->aboutToDie; }
uint32_t      _trdpy_md_num_replies   (const TRDP_MD_INFO_T *p) { return p->numReplies;    }
uint32_t      _trdpy_md_num_exp_replies(const TRDP_MD_INFO_T *p){ return p->numExpReplies; }
const char   *_trdpy_md_src_user_uri  (const TRDP_MD_INFO_T *p) { return p->srcUserURI;    }
const char   *_trdpy_md_dest_user_uri (const TRDP_MD_INFO_T *p) { return p->destUserURI;   }
uint32_t      _trdpy_md_reply_timeout (const TRDP_MD_INFO_T *p) { return p->replyTimeout;  }

/* ── TRDP_STATISTICS_T field accessors ── */
uint32_t _trdpy_stats_version      (const TRDP_STATISTICS_T *s) { return s->version;          }
uint32_t _trdpy_stats_uptime       (const TRDP_STATISTICS_T *s) { return s->upTime;            }
uint32_t _trdpy_stats_stat_time    (const TRDP_STATISTICS_T *s) { return s->statisticTime;     }
uint32_t _trdpy_stats_own_ip       (const TRDP_STATISTICS_T *s) { return s->ownIpAddr;         }
uint32_t _trdpy_stats_leader_ip    (const TRDP_STATISTICS_T *s) { return s->leaderIpAddr;      }
uint32_t _trdpy_stats_num_red      (const TRDP_STATISTICS_T *s) { return s->numRed;            }
uint32_t _trdpy_stats_num_join     (const TRDP_STATISTICS_T *s) { return s->numJoin;           }
uint32_t _trdpy_stats_pd_num_subs  (const TRDP_STATISTICS_T *s) { return s->pd.numSubs;        }
uint32_t _trdpy_stats_pd_num_pub   (const TRDP_STATISTICS_T *s) { return s->pd.numPub;         }
uint32_t _trdpy_stats_pd_num_send  (const TRDP_STATISTICS_T *s) { return s->pd.numSend;        }
uint32_t _trdpy_stats_pd_num_rcv   (const TRDP_STATISTICS_T *s) { return s->pd.numRcv;         }
uint32_t _trdpy_stats_pd_num_timeout(const TRDP_STATISTICS_T *s){ return s->pd.numTimeout;     }
uint32_t _trdpy_stats_pd_num_crc_err(const TRDP_STATISTICS_T *s){ return s->pd.numCrcErr;      }
uint32_t _trdpy_stats_udpmd_num_send(const TRDP_STATISTICS_T *s){ return s->udpMd.numSend;     }
uint32_t _trdpy_stats_udpmd_num_rcv (const TRDP_STATISTICS_T *s){ return s->udpMd.numRcv;      }
uint32_t _trdpy_stats_udpmd_num_timeout(const TRDP_STATISTICS_T *s){return s->udpMd.numReplyTimeout;}
uint32_t _trdpy_stats_tcpmd_num_send(const TRDP_STATISTICS_T *s){ return s->tcpMd.numSend;     }
uint32_t _trdpy_stats_tcpmd_num_rcv (const TRDP_STATISTICS_T *s){ return s->tcpMd.numRcv;      }

/* ── TRDP_SUBS_STATISTICS_T field accessors ── */
uint32_t _trdpy_subs_comid       (const TRDP_SUBS_STATISTICS_T *s) { return s->comId;       }
uint32_t _trdpy_subs_joined_addr (const TRDP_SUBS_STATISTICS_T *s) { return s->joinedAddr;  }
uint32_t _trdpy_subs_filter_addr (const TRDP_SUBS_STATISTICS_T *s) { return s->filterAddr;  }
uint32_t _trdpy_subs_timeout     (const TRDP_SUBS_STATISTICS_T *s) { return s->timeout;     }
uint32_t _trdpy_subs_status      (const TRDP_SUBS_STATISTICS_T *s) { return s->status;      }
uint32_t _trdpy_subs_num_rcv     (const TRDP_SUBS_STATISTICS_T *s) { return s->numRecv;     }
uint32_t _trdpy_subs_num_missed  (const TRDP_SUBS_STATISTICS_T *s) { return s->numMissed;   }

/* ── TRDP_PUB_STATISTICS_T field accessors ── */
uint32_t _trdpy_pub_comid     (const TRDP_PUB_STATISTICS_T *s) { return s->comId;    }
uint32_t _trdpy_pub_dest_addr (const TRDP_PUB_STATISTICS_T *s) { return s->destAddr; }
uint32_t _trdpy_pub_cycle     (const TRDP_PUB_STATISTICS_T *s) { return s->cycle;    }
uint32_t _trdpy_pub_red_id    (const TRDP_PUB_STATISTICS_T *s) { return s->redId;    }
uint32_t _trdpy_pub_red_state (const TRDP_PUB_STATISTICS_T *s) { return s->redState; }
uint32_t _trdpy_pub_num_put   (const TRDP_PUB_STATISTICS_T *s) { return s->numPut;   }
uint32_t _trdpy_pub_num_send  (const TRDP_PUB_STATISTICS_T *s) { return s->numSend;  }
"""

include_dirs = [p for p in os.environ.get("TRDP_INCLUDE_DIRS", "").split(":") if p]
lib_dir = os.environ.get("TRDP_LIB_DIR", "")
lib_dirs = [lib_dir] if lib_dir else []

ffi.set_source(
    "trdp_py._trdp_c",
    _C_SOURCE,
    include_dirs=include_dirs,
    library_dirs=lib_dirs,
    libraries=["trdp"],
)
