package io.trdp;

import com.sun.jna.*;
import com.sun.jna.Structure.FieldOrder;
import com.sun.jna.ptr.*;

/**
 * Raw JNA mapping of the TCNOpen TRDP Light C API ({@code trdp_if_light.h}).
 *
 * <p>Load via:
 * <pre>{@code
 *   TrdpLibrary lib = Native.load("trdp", TrdpLibrary.class);
 * }</pre>
 *
 * <p>All TRDP C types are mapped as follows:
 * <ul>
 *   <li>{@code TRDP_APP_SESSION_T / PUB / SUB / LIS} → {@link Pointer} (opaque handles)
 *   <li>{@code TRDP_IP_ADDR_T} ({@code uint32_t}) → {@code int}  (bits identical)
 *   <li>{@code TRDP_ERR_T} ({@code int32_t})  → {@code int}
 *   <li>{@code TRDP_FLAGS_T} ({@code uint8_t}) → {@code byte}
 *   <li>{@code BOOL8} ({@code uint8_t})        → {@code byte}
 *   <li>{@code TRDP_UUID_T} ({@code uint8_t[16]}) → {@code byte[16]}
 * </ul>
 *
 * <p>GNU_PACKED structs ({@code TRDP_PD_INFO_T}, {@code TRDP_MD_INFO_T},
 * statistics structs) are declared with {@link Structure#ALIGN_NONE} so that
 * JNA reproduces the C compiler's packed layout.
 *
 * <p><b>Note:</b> MD functions ({@code tlm_*}) require {@code libtrdp} compiled
 * with {@code MD_SUPPORT=1}.
 */
@SuppressWarnings({"unused", "SpellCheckingInspection"})
public interface TrdpLibrary extends Library {

    // =========================================================================
    // Callback interfaces
    // =========================================================================

    /** Debug/log output callback ({@code TRDP_PRINT_DBG_T}). */
    interface PrintDbgCallback extends Callback {
        void invoke(Pointer pRefCon, String pTime, String pFile,
                    short lineNumber, String pMsgStr);
    }

    /** PD receive / send callback ({@code TRDP_PD_CALLBACK_T}). */
    interface PdCallback extends Callback {
        void invoke(Pointer pRefCon, Pointer appHandle,
                    TrdpPdInfo pMsg, Pointer pData, int dataSize);
    }

    /** MD indication / reply callback ({@code TRDP_MD_CALLBACK_T}). */
    interface MdCallback extends Callback {
        void invoke(Pointer pRefCon, Pointer appHandle,
                    TrdpMdInfo pMsg, Pointer pData, int dataSize);
    }

    // =========================================================================
    // Struct: TRDP_TIME_T  (≡ struct timeval on Linux)
    // =========================================================================

    @FieldOrder({"tv_sec", "tv_usec"})
    class TrdpTime extends Structure {
        public long tv_sec;
        public long tv_usec;

        public TrdpTime() { super(); }
        public TrdpTime(Pointer p) { super(p); read(); }
    }

    // =========================================================================
    // Struct: TRDP_FDS_T  (≡ fd_set on Linux x86-64: 16 × unsigned long)
    // =========================================================================

    @FieldOrder({"fds_bits"})
    class TrdpFds extends Structure {
        public long[] fds_bits = new long[16];

        public TrdpFds() { super(); }
        public TrdpFds(Pointer p) { super(p); read(); }

        /** Set bit for file-descriptor {@code fd}. */
        public void set(int fd)   { fds_bits[fd / 64] |=  (1L << (fd % 64)); }
        /** Clear bit for {@code fd}. */
        public void clear(int fd) { fds_bits[fd / 64] &= ~(1L << (fd % 64)); }
        /** Return true if bit for {@code fd} is set. */
        public boolean isSet(int fd) {
            return (fds_bits[fd / 64] & (1L << (fd % 64))) != 0L;
        }
    }

    // =========================================================================
    // Struct: TRDP_COM_PARAM_T  (non-packed: 3 bytes, no alignment issues)
    // =========================================================================

    @FieldOrder({"qos", "ttl", "retries"})
    class TrdpComParam extends Structure {
        public byte qos;
        public byte ttl;
        public byte retries;

        public TrdpComParam() { super(ALIGN_NONE); }

        public TrdpComParam(int qos, int ttl, int retries) {
            super(ALIGN_NONE);
            this.qos     = (byte) qos;
            this.ttl     = (byte) ttl;
            this.retries = (byte) retries;
        }
    }

    // =========================================================================
    // Struct: TRDP_MEM_CONFIG_T  (passed as NULL in most cases)
    // =========================================================================

    @FieldOrder({"p", "size", "prealloc"})
    class TrdpMemConfig extends Structure {
        public Pointer p;          // UINT8 *
        public int     size;       // UINT32
        public int[]   prealloc = new int[15]; // UINT32[VOS_MEM_NBLOCKSIZES=15]

        public TrdpMemConfig() { super(); }
    }

    // =========================================================================
    // Struct: TRDP_PD_INFO_T  (GNU_PACKED → ALIGN_NONE)
    //
    // Layout (64-bit Linux, packed):
    //   0  srcIpAddr      uint32
    //   4  destIpAddr     uint32
    //   8  seqCount       uint32
    //  12  protVersion    uint16
    //  14  msgType        uint16
    //  16  comId          uint32
    //  20  etbTopoCnt     uint32
    //  24  opTrnTopoCnt   uint32
    //  28  replyComId     uint32
    //  32  replyIpAddr    uint32
    //  36  pUserRef       ptr (8 bytes, unaligned in packed struct!)
    //  44  resultCode     int32
    //  48  srcHostURI     char[81]
    // 129  destHostURI    char[81]
    // 210  toBehavior     uint32
    // 214  serviceId      uint32
    // =========================================================================

    @FieldOrder({
        "srcIpAddr", "destIpAddr", "seqCount", "protVersion", "msgType",
        "comId", "etbTopoCnt", "opTrnTopoCnt", "replyComId", "replyIpAddr",
        "pUserRef", "resultCode",
        "srcHostURI", "destHostURI",
        "toBehavior", "serviceId"
    })
    class TrdpPdInfo extends Structure {
        public int     srcIpAddr;
        public int     destIpAddr;
        public int     seqCount;
        public short   protVersion;
        public short   msgType;
        public int     comId;
        public int     etbTopoCnt;
        public int     opTrnTopoCnt;
        public int     replyComId;
        public int     replyIpAddr;
        public Pointer pUserRef;               // may be unaligned; do not dereference
        public int     resultCode;
        public byte[]  srcHostURI  = new byte[81];
        public byte[]  destHostURI = new byte[81];
        public int     toBehavior;
        public int     serviceId;

        public TrdpPdInfo()           { super(ALIGN_NONE); }
        public TrdpPdInfo(Pointer p)  { super(p); setAlignType(ALIGN_NONE); read(); }
    }

    // =========================================================================
    // Struct: TRDP_MD_INFO_T  (GNU_PACKED → ALIGN_NONE)
    //
    // Layout (64-bit Linux, packed):
    //   0  srcIpAddr          uint32
    //   4  destIpAddr         uint32
    //   8  seqCount           uint32
    //  12  protVersion        uint16
    //  14  msgType            uint16
    //  16  comId              uint32
    //  20  etbTopoCnt         uint32
    //  24  opTrnTopoCnt       uint32
    //  28  aboutToDie         uint8
    //  29  numRepliesQuery    uint32  (packed, unaligned!)
    //  33  numConfirmSent     uint32
    //  37  numConfirmTimeout  uint32
    //  41  userStatus         uint16
    //  43  replyStatus        int32   (packed, unaligned!)
    //  47  sessionId          uint8[16]
    //  63  replyTimeout       uint32
    //  67  srcUserURI         char[33]
    // 100  srcHostURI         char[81]
    // 181  destUserURI        char[33]
    // 214  destHostURI        char[81]
    // 295  numExpReplies      uint32
    // 299  numReplies         uint32
    // 303  pUserRef           ptr(8)  (unaligned!)
    // 311  resultCode         int32
    // =========================================================================

    @FieldOrder({
        "srcIpAddr", "destIpAddr", "seqCount", "protVersion", "msgType",
        "comId", "etbTopoCnt", "opTrnTopoCnt",
        "aboutToDie", "numRepliesQuery", "numConfirmSent", "numConfirmTimeout",
        "userStatus", "replyStatus",
        "sessionId", "replyTimeout",
        "srcUserURI", "srcHostURI", "destUserURI", "destHostURI",
        "numExpReplies", "numReplies",
        "pUserRef", "resultCode"
    })
    class TrdpMdInfo extends Structure {
        public int     srcIpAddr;
        public int     destIpAddr;
        public int     seqCount;
        public short   protVersion;
        public short   msgType;
        public int     comId;
        public int     etbTopoCnt;
        public int     opTrnTopoCnt;
        public byte    aboutToDie;
        public int     numRepliesQuery;
        public int     numConfirmSent;
        public int     numConfirmTimeout;
        public short   userStatus;
        public int     replyStatus;
        public byte[]  sessionId    = new byte[16];
        public int     replyTimeout;
        public byte[]  srcUserURI   = new byte[33];
        public byte[]  srcHostURI   = new byte[81];
        public byte[]  destUserURI  = new byte[33];
        public byte[]  destHostURI  = new byte[81];
        public int     numExpReplies;
        public int     numReplies;
        public Pointer pUserRef;
        public int     resultCode;

        public TrdpMdInfo()          { super(ALIGN_NONE); }
        public TrdpMdInfo(Pointer p) { super(p); setAlignType(ALIGN_NONE); read(); }
    }

    // =========================================================================
    // Statistics structs (all GNU_PACKED, all-uint32 fields → no alignment risk)
    // =========================================================================

    @FieldOrder({
        "total", "free", "minFree",
        "numAllocBlocks", "numAllocErr", "numFreeErr",
        "blockSize", "usedBlockSize"
    })
    class VosMemStatistics extends Structure {
        public int   total;
        public int   free;
        public int   minFree;
        public int   numAllocBlocks;
        public int   numAllocErr;
        public int   numFreeErr;
        public int[] blockSize     = new int[15];
        public int[] usedBlockSize = new int[15];

        public VosMemStatistics()          { super(ALIGN_NONE); }
        public VosMemStatistics(Pointer p) { super(p); setAlignType(ALIGN_NONE); read(); }
    }

    @FieldOrder({
        "defQos", "defTtl", "defTimeout",
        "numSubs", "numPub", "numRcv", "numCrcErr", "numProtErr",
        "numTopoErr", "numNoSubs", "numNoPub", "numTimeout", "numSend", "numMissed"
    })
    class TrdpPdStatistics extends Structure {
        public int defQos, defTtl, defTimeout;
        public int numSubs, numPub, numRcv, numCrcErr, numProtErr;
        public int numTopoErr, numNoSubs, numNoPub, numTimeout, numSend, numMissed;

        public TrdpPdStatistics()          { super(ALIGN_NONE); }
        public TrdpPdStatistics(Pointer p) { super(p); setAlignType(ALIGN_NONE); read(); }
    }

    @FieldOrder({
        "defQos", "defTtl", "defReplyTimeout", "defConfirmTimeout",
        "numList", "numRcv", "numCrcErr", "numProtErr",
        "numTopoErr", "numNoListener", "numReplyTimeout", "numConfirmTimeout", "numSend"
    })
    class TrdpMdStatistics extends Structure {
        public int defQos, defTtl, defReplyTimeout, defConfirmTimeout;
        public int numList, numRcv, numCrcErr, numProtErr;
        public int numTopoErr, numNoListener, numReplyTimeout, numConfirmTimeout, numSend;

        public TrdpMdStatistics()          { super(ALIGN_NONE); }
        public TrdpMdStatistics(Pointer p) { super(p); setAlignType(ALIGN_NONE); read(); }
    }

    // TRDP_STATISTICS_T – GNU_PACKED, contains UINT64 at offset 4 (misaligned!).
    // The UINT64 field is handled correctly by JNA ALIGN_NONE.
    @FieldOrder({
        "version", "timeStamp", "upTime", "statisticTime",
        "hostName", "leaderName",
        "ownIpAddr", "leaderIpAddr", "processPrio", "processCycle",
        "numJoin", "numRed",
        "mem", "pd", "udpMd", "tcpMd"
    })
    class TrdpStatistics extends Structure {
        public int               version;
        public long              timeStamp;        // UINT64 at offset 4 (packed)
        public int               upTime;
        public int               statisticTime;
        public byte[]            hostName   = new byte[16];  // TRDP_NET_LABEL_T
        public byte[]            leaderName = new byte[16];
        public int               ownIpAddr;
        public int               leaderIpAddr;
        public int               processPrio;
        public int               processCycle;
        public int               numJoin;
        public int               numRed;
        public VosMemStatistics  mem        = new VosMemStatistics();
        public TrdpPdStatistics  pd         = new TrdpPdStatistics();
        public TrdpMdStatistics  udpMd      = new TrdpMdStatistics();
        public TrdpMdStatistics  tcpMd      = new TrdpMdStatistics();

        public TrdpStatistics()          { super(ALIGN_NONE); }
        public TrdpStatistics(Pointer p) { super(p); setAlignType(ALIGN_NONE); read(); }
    }

    @FieldOrder({
        "comId", "joinedAddr", "filterAddr",
        "callBack", "userRef", "timeout", "status", "toBehav",
        "numRecv", "numMissed"
    })
    class TrdpSubsStatistics extends Structure {
        public int comId, joinedAddr, filterAddr;
        public int callBack, userRef, timeout, status, toBehav;
        public int numRecv, numMissed;

        public TrdpSubsStatistics()          { super(ALIGN_NONE); }
        public TrdpSubsStatistics(Pointer p) { super(p); setAlignType(ALIGN_NONE); read(); }
    }

    @FieldOrder({"comId", "destAddr", "cycle", "redId", "redState", "numPut", "numSend"})
    class TrdpPubStatistics extends Structure {
        public int comId, destAddr, cycle, redId, redState, numPut, numSend;

        public TrdpPubStatistics()          { super(ALIGN_NONE); }
        public TrdpPubStatistics(Pointer p) { super(p); setAlignType(ALIGN_NONE); read(); }
    }

    // =========================================================================
    // TRDP Light API function prototypes
    // =========================================================================

    // ── Session lifecycle ────────────────────────────────────────────────────

    int tlc_init(PrintDbgCallback pPrintDebugString,
                 Pointer pRefCon,
                 TrdpMemConfig pMemConfig);

    int tlc_terminate();

    int tlc_openSession(PointerByReference pAppHandle,
                        int ownIpAddr,
                        int leaderIpAddr,
                        Pointer pMarshall,
                        Pointer pPdDefault,
                        Pointer pMdDefault,
                        Pointer pProcessConfig);

    int tlc_closeSession(Pointer appHandle);
    int tlc_reinitSession(Pointer appHandle);
    int tlc_updateSession(Pointer appHandle);

    // ── Event loop ────────────────────────────────────────────────────────────

    int tlc_getInterval(Pointer appHandle,
                        TrdpTime pInterval,
                        TrdpFds  pFileDesc,
                        IntByReference pNoDesc);

    int tlc_process(Pointer appHandle,
                    TrdpFds pRfds,
                    IntByReference pCount);

    // ── Topology ─────────────────────────────────────────────────────────────

    int  tlc_setETBTopoCount(Pointer appHandle, int etbTopoCnt);
    int  tlc_getETBTopoCount(Pointer appHandle);
    int  tlc_setOpTrainTopoCount(Pointer appHandle, int opTrnTopoCnt);
    int  tlc_getOpTrainTopoCount(Pointer appHandle);

    // ── Misc ─────────────────────────────────────────────────────────────────

    int    tlc_getOwnIpAddress(Pointer appHandle);
    String tlc_getVersionString();

    // ── Statistics ────────────────────────────────────────────────────────────

    int tlc_getStatistics(Pointer appHandle, TrdpStatistics pStatistics);

    int tlc_getSubsStatistics(Pointer appHandle,
                               ShortByReference pNumSubs,
                               TrdpSubsStatistics[] pStatistics);

    int tlc_getPubStatistics(Pointer appHandle,
                              ShortByReference pNumPub,
                              TrdpPubStatistics[] pStatistics);

    int tlc_getJoinStatistics(Pointer appHandle,
                               ShortByReference pNumJoin,
                               int[] pIpAddr);

    int tlc_resetStatistics(Pointer appHandle);

    // ── Process Data: publish ─────────────────────────────────────────────────

    int tlp_publish(Pointer         appHandle,
                    PointerByReference pPubHandle,
                    Pointer         pUserRef,
                    PdCallback      pfCbFunction,
                    int             serviceId,
                    int             comId,
                    int             etbTopoCnt,
                    int             opTrnTopoCnt,
                    int             srcIpAddr,
                    int             destIpAddr,
                    int             interval,
                    int             redId,
                    byte            pktFlags,
                    byte[]          pData,
                    int             dataSize);

    int tlp_republish(Pointer appHandle,
                      Pointer pubHandle,
                      int etbTopoCnt, int opTrnTopoCnt,
                      int srcIpAddr,  int destIpAddr);

    int tlp_unpublish(Pointer appHandle, Pointer pubHandle);

    int tlp_put(Pointer appHandle, Pointer pubHandle,
                byte[] pData, int dataSize);

    int tlp_putImmediate(Pointer   appHandle,
                         Pointer   pubHandle,
                         byte[]    pData,
                         int       dataSize,
                         TrdpTime  pTxTime);

    int tlp_setRedundant(Pointer appHandle, int redId, byte leader);
    int tlp_getRedundant(Pointer appHandle, int redId, ByteByReference pLeader);

    // ── Process Data: subscribe ───────────────────────────────────────────────

    int tlp_subscribe(Pointer    appHandle,
                      PointerByReference pSubHandle,
                      Pointer    pUserRef,
                      PdCallback pfCbFunction,
                      int        serviceId,
                      int        comId,
                      int        etbTopoCnt,
                      int        opTrnTopoCnt,
                      int        srcIpAddr1,
                      int        srcIpAddr2,
                      int        destIpAddr,
                      byte       pktFlags,
                      int        timeout,
                      int        toBehavior);

    int tlp_resubscribe(Pointer appHandle,
                        Pointer subHandle,
                        int etbTopoCnt, int opTrnTopoCnt,
                        int srcIpAddr1, int srcIpAddr2, int destIpAddr);

    int tlp_unsubscribe(Pointer appHandle, Pointer subHandle);

    int tlp_get(Pointer    appHandle,
                Pointer    subHandle,
                TrdpPdInfo pPdInfo,
                byte[]     pData,
                IntByReference pDataSize);

    int tlp_request(Pointer appHandle,
                    Pointer subHandle,
                    int serviceId, int comId,
                    int etbTopoCnt, int opTrnTopoCnt,
                    int srcIpAddr,  int destIpAddr,
                    int redId,      byte pktFlags,
                    byte[] pData,   int dataSize,
                    int replyComId, int replyIpAddr);

    int tlp_getInterval(Pointer appHandle,
                        TrdpTime pInterval,
                        TrdpFds  pFileDesc,
                        IntByReference pNoDesc);

    int tlp_processSend(Pointer appHandle);

    int tlp_processReceive(Pointer appHandle,
                           TrdpFds pRfds,
                           IntByReference pCount);

    // ── Message Data (MD) — requires MD_SUPPORT=1 ────────────────────────────

    int tlm_notify(Pointer       appHandle,
                   Pointer       pUserRef,
                   MdCallback    pfCbFunction,
                   int           comId,
                   int           etbTopoCnt,
                   int           opTrnTopoCnt,
                   int           srcIpAddr,
                   int           destIpAddr,
                   byte          pktFlags,
                   TrdpComParam  pSendParam,
                   byte[]        pData,
                   int           dataSize,
                   String        srcURI,
                   String        destURI);

    int tlm_request(Pointer       appHandle,
                    Pointer       pUserRef,
                    MdCallback    pfCbFunction,
                    byte[]        pSessionId,    // TRDP_UUID_T * (output, 16 bytes)
                    int           comId,
                    int           etbTopoCnt,
                    int           opTrnTopoCnt,
                    int           srcIpAddr,
                    int           destIpAddr,
                    byte          pktFlags,
                    int           numReplies,
                    int           replyTimeout,
                    TrdpComParam  pSendParam,
                    byte[]        pData,
                    int           dataSize,
                    String        srcURI,
                    String        destURI);

    int tlm_confirm(Pointer      appHandle,
                    byte[]       pSessionId,    // const TRDP_UUID_T *
                    short        userStatus,
                    TrdpComParam pSendParam);

    int tlm_abortSession(Pointer appHandle, byte[] pSessionId);

    int tlm_addListener(Pointer       appHandle,
                        PointerByReference pListenHandle,
                        Pointer       pUserRef,
                        MdCallback    pfCbFunction,
                        byte          comIdListener,
                        int           comId,
                        int           etbTopoCnt,
                        int           opTrnTopoCnt,
                        int           srcIpAddr1,
                        int           srcIpAddr2,
                        int           mcDestIpAddr,
                        byte          pktFlags,
                        String        srcURI,
                        String        destURI);

    int tlm_readdListener(Pointer appHandle,
                          Pointer listenHandle,
                          int etbTopoCnt, int opTrnTopoCnt,
                          int srcIpAddr,  int srcIpAddr2, int mcDestIpAddr);

    int tlm_delListener(Pointer appHandle, Pointer listenHandle);

    int tlm_reply(Pointer       appHandle,
                  byte[]        pSessionId,
                  int           comId,
                  int           userStatus,
                  TrdpComParam  pSendParam,
                  byte[]        pData,
                  int           dataSize,
                  String        srcURI);

    int tlm_replyQuery(Pointer       appHandle,
                       byte[]        pSessionId,
                       int           comId,
                       int           userStatus,
                       int           confirmTimeout,
                       TrdpComParam  pSendParam,
                       byte[]        pData,
                       int           dataSize,
                       String        srcURI);

    int tlm_getInterval(Pointer appHandle,
                        TrdpTime pInterval,
                        TrdpFds  pFileDesc,
                        IntByReference pNoDesc);

    int tlm_process(Pointer appHandle,
                    TrdpFds pRfds,
                    IntByReference pCount);
}
