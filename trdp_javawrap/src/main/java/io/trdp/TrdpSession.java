package io.trdp;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.*;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

/**
 * High-level wrapper around a single TRDP application session.
 *
 * <p>Implements {@link AutoCloseable} for use in try-with-resources:
 * <pre>{@code
 *   try (TrdpSession s = new TrdpSession("10.0.1.1")) {
 *       Pointer pub = s.publish(1000, "239.192.0.0", 100_000);
 *       Pointer sub = s.subscribe(2000, "10.0.1.2", 500_000);
 *       s.startProcessingThread(10);     // background 10 ms poll
 *       Thread.sleep(5000);
 *       s.put(pub, "hello".getBytes());
 *   }
 * }</pre>
 *
 * <p>All IP addresses are dotted-decimal strings ({@code "10.0.1.1"}).
 * All handles returned by publish/subscribe/addListener are raw {@link Pointer}
 * values and must be passed back to the corresponding remove/stop methods.
 *
 * <p>Callbacks are {@code BiConsumer<XInfo, byte[]>} lambdas executed on the
 * thread that calls {@link #processOnce} (or the background processing thread).
 * Do not block or throw from a callback.
 *
 * <p><b>Threading:</b> The session and all handle-level operations are NOT
 * thread-safe by themselves.  Either call {@link #processOnce} from a single
 * thread, or use {@link #startProcessingThread}.
 */
@SuppressWarnings("unused")
public class TrdpSession implements AutoCloseable {

    // ── Packet flags (TRDP_FLAGS_T) ──────────────────────────────────────────
    public static final byte FLAGS_DEFAULT  = 0x00;
    public static final byte FLAGS_NONE     = 0x01;
    public static final byte FLAGS_MARSHALL = 0x02;
    public static final byte FLAGS_CALLBACK = 0x04;
    public static final byte FLAGS_TCP      = 0x08;
    public static final byte FLAGS_FORCE_CB = 0x10;

    // ── Timeout behaviours (TRDP_TO_BEHAVIOR_T) ───────────────────────────────
    public static final int TO_DEFAULT          = 0;
    public static final int TO_SET_TO_ZERO      = 1;
    public static final int TO_KEEP_LAST_VALUE  = 2;

    // ── Redundancy states ─────────────────────────────────────────────────────
    public static final byte RED_FOLLOWER = 0;
    public static final byte RED_LEADER   = 1;

    // ── Error codes that are not fatal ────────────────────────────────────────
    private static final int TRDP_NO_ERR    =  0;
    private static final int TRDP_NODATA_ERR = -5;

    private static final Logger LOG = Logger.getLogger(TrdpSession.class.getName());

    // =========================================================================
    // State
    // =========================================================================

    private final TrdpLibrary lib;
    private final int ownIp;
    private final int leaderIp;
    private final boolean autoInit;

    private Pointer handle;   // TRDP_APP_SESSION_T
    private boolean open;

    /** Hold strong references to JNA Callback objects to prevent GC. */
    private final List<Object> callbackRefs = new ArrayList<>();

    /** Background processing thread (optional). */
    private volatile ScheduledExecutorService scheduler;

    // =========================================================================
    // Constructors
    // =========================================================================

    /**
     * Create a session with default library name {@code "trdp"}.
     *
     * @param ownIp    own IP address in dotted-decimal notation
     */
    public TrdpSession(String ownIp) {
        this(ownIp, "0.0.0.0", "trdp", true);
    }

    /**
     * Full constructor.
     *
     * @param ownIp     own IP address
     * @param leaderIp  redundancy leader IP (pass {@code "0.0.0.0"} if unused)
     * @param libName   native library name as passed to {@link Native#load}
     * @param autoInit  if {@code true}, calls {@code tlc_init} / {@code tlc_terminate}
     *                  automatically on open/close
     */
    public TrdpSession(String ownIp, String leaderIp, String libName, boolean autoInit) {
        this.ownIp    = ipToInt(ownIp);
        this.leaderIp = ipToInt(leaderIp);
        this.autoInit = autoInit;
        this.lib      = Native.load(libName, TrdpLibrary.class);
        open();
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /** Open the session (called automatically by the constructor). */
    public final void open() {
        if (open) return;
        if (autoInit) {
            check(lib.tlc_init(null, null, null), "tlc_init");
        }
        PointerByReference ref = new PointerByReference();
        check(lib.tlc_openSession(ref, ownIp, leaderIp,
                null, null, null, null), "tlc_openSession");
        handle = ref.getValue();
        open = true;
    }

    /** Close the session and stop any background processing thread. */
    @Override
    public void close() {
        stopProcessingThread();
        if (!open) return;
        int rc = lib.tlc_closeSession(handle);
        open = false;
        callbackRefs.clear();
        if (autoInit) lib.tlc_terminate();
        if (rc != TRDP_NO_ERR) throw new TrdpException(rc, "tlc_closeSession");
    }

    public void reinit()  { checkOpen(); check(lib.tlc_reinitSession(handle), "tlc_reinitSession"); }
    public void update()  { checkOpen(); check(lib.tlc_updateSession(handle),  "tlc_updateSession"); }

    // =========================================================================
    // Version / address
    // =========================================================================

    /** @return TRDP library version string. */
    public static String versionString(TrdpLibrary lib) {
        return lib.tlc_getVersionString();
    }

    /** @return this session's own IP address as reported by the stack. */
    public String getOwnIp() {
        checkOpen();
        return intToIp(lib.tlc_getOwnIpAddress(handle));
    }

    // =========================================================================
    // Topology
    // =========================================================================

    public void  setEtbTopoCount(int count) { checkOpen(); check(lib.tlc_setETBTopoCount(handle, count), "setETBTopoCount"); }
    public int   getEtbTopoCount()          { checkOpen(); return lib.tlc_getETBTopoCount(handle); }
    public void  setOpTrainTopoCount(int c) { checkOpen(); check(lib.tlc_setOpTrainTopoCount(handle, c), "setOpTrainTopoCount"); }
    public int   getOpTrainTopoCount()      { checkOpen(); return lib.tlc_getOpTrainTopoCount(handle); }

    // =========================================================================
    // Event loop
    // =========================================================================

    /**
     * Drive the TRDP stack for one iteration.
     *
     * <p>Calls {@code tlc_getInterval()} then {@code tlc_process()}.
     * On Java there is no portable way to perform {@code select()} on raw
     * OS file-descriptors, so the fd-set is passed as {@code null} to the
     * process call.  This is sufficient for driving timers and PD cyclic
     * transmission; for full socket-event-driven processing you would need
     * a JNI bridge or Panama FFI.
     *
     * @param timeoutMs maximum wait in milliseconds
     */
    public void processOnce(int timeoutMs) {
        checkOpen();
        TrdpLibrary.TrdpTime interval = new TrdpLibrary.TrdpTime();
        TrdpLibrary.TrdpFds  fds      = new TrdpLibrary.TrdpFds();
        IntByReference       noDesc   = new IntByReference(-1);

        int rc = lib.tlc_getInterval(handle, interval, fds, noDesc);
        if (rc != TRDP_NO_ERR && rc != TRDP_NODATA_ERR) {
            throw new TrdpException(rc, "tlc_getInterval");
        }

        // Compute effective sleep time: min(caller timeout, TRDP deadline)
        long trdpUs = interval.tv_sec * 1_000_000L + interval.tv_usec;
        long sleepUs = Math.min((long) timeoutMs * 1000L, trdpUs > 0 ? trdpUs : Long.MAX_VALUE);
        if (sleepUs > 0) {
            try { Thread.sleep(sleepUs / 1000, (int)((sleepUs % 1000) * 1000)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        IntByReference count = new IntByReference(0);
        rc = lib.tlc_process(handle, null, count);
        if (rc != TRDP_NO_ERR && rc != TRDP_NODATA_ERR) {
            throw new TrdpException(rc, "tlc_process");
        }
    }

    // =========================================================================
    // Background processing thread
    // =========================================================================

    /**
     * Start a background daemon thread that calls {@link #processOnce} every
     * {@code periodMs} milliseconds.
     *
     * @param periodMs polling period in milliseconds
     */
    public synchronized void startProcessingThread(int periodMs) {
        if (scheduler != null && !scheduler.isShutdown()) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "trdp-process");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(
            () -> { try { processOnce(periodMs); } catch (Exception e) { LOG.warning("processOnce: " + e); } },
            0, periodMs, TimeUnit.MILLISECONDS
        );
    }

    /** Stop the background processing thread (if running). */
    public synchronized void stopProcessingThread() {
        if (scheduler != null) {
            scheduler.shutdown();
            scheduler = null;
        }
    }

    // =========================================================================
    // Process Data — publish
    // =========================================================================

    /**
     * Register a periodic PD publisher.
     *
     * @param comId      communication identifier
     * @param destIp     destination IP (unicast or multicast)
     * @param intervalUs publish period in microseconds
     * @return publisher handle (pass to {@link #put} / {@link #unpublish})
     */
    public Pointer publish(int comId, String destIp, int intervalUs) {
        return publish(comId, destIp, intervalUs, new byte[0],
                "0.0.0.0", 0, 0, 0, 0, FLAGS_DEFAULT, null);
    }

    /** Publish with initial data. */
    public Pointer publish(int comId, String destIp, int intervalUs, byte[] data) {
        return publish(comId, destIp, intervalUs, data,
                "0.0.0.0", 0, 0, 0, 0, FLAGS_DEFAULT, null);
    }

    /**
     * Full publish overload.
     *
     * @param callback optional callback invoked on each send; receives
     *                 ({@link PdInfo}, {@code byte[]}).  Pass {@code null}
     *                 if not needed.
     */
    public Pointer publish(int comId, String destIp, int intervalUs,
                           byte[] data, String srcIp,
                           int serviceId, int redId,
                           int etbTopoCnt, int opTrnTopoCnt,
                           byte pktFlags,
                           BiConsumer<PdInfo, byte[]> callback) {
        checkOpen();
        PointerByReference ref = new PointerByReference();
        TrdpLibrary.PdCallback cbPtr = makePdCallback(callback);
        byte[] payload = (data == null) ? new byte[0] : data;
        check(lib.tlp_publish(
                handle, ref, null, cbPtr,
                serviceId, comId, etbTopoCnt, opTrnTopoCnt,
                ipToInt(srcIp), ipToInt(destIp),
                intervalUs, redId, pktFlags,
                payload, payload.length
        ), "tlp_publish");
        return ref.getValue();
    }

    /** Update topology/addresses of an existing publisher. */
    public void republish(Pointer pubHandle, String srcIp, String destIp,
                          int etbTopoCnt, int opTrnTopoCnt) {
        checkOpen();
        check(lib.tlp_republish(handle, pubHandle,
                etbTopoCnt, opTrnTopoCnt,
                ipToInt(srcIp), ipToInt(destIp)), "tlp_republish");
    }

    /** Stop publishing and release the handle. */
    public void unpublish(Pointer pubHandle) {
        checkOpen();
        check(lib.tlp_unpublish(handle, pubHandle), "tlp_unpublish");
    }

    /** Update the publisher's payload (sent on the next cycle). */
    public void put(Pointer pubHandle, byte[] data) {
        checkOpen();
        check(lib.tlp_put(handle, pubHandle, data, data.length), "tlp_put");
    }

    /** Update payload and transmit immediately. */
    public void putImmediate(Pointer pubHandle, byte[] data) {
        checkOpen();
        check(lib.tlp_putImmediate(handle, pubHandle, data, data.length, null),
                "tlp_putImmediate");
    }

    /** Set redundancy state for a group. */
    public void setRedundant(int redId, boolean leader) {
        checkOpen();
        check(lib.tlp_setRedundant(handle, redId, leader ? RED_LEADER : RED_FOLLOWER),
                "tlp_setRedundant");
    }

    /** Return true if this session is the redundancy leader for {@code redId}. */
    public boolean getRedundant(int redId) {
        checkOpen();
        ByteByReference ref = new ByteByReference((byte) 0);
        check(lib.tlp_getRedundant(handle, redId, ref), "tlp_getRedundant");
        return ref.getValue() != 0;
    }

    // =========================================================================
    // Process Data — subscribe
    // =========================================================================

    /**
     * Subscribe to a periodic PD stream.
     *
     * @param comId      communication identifier to listen for
     * @param srcIp1     allowed source IP ({@code "0.0.0.0"} = any)
     * @param timeoutUs  receive timeout in microseconds (0 = infinite)
     * @return subscriber handle (pass to {@link #get} / {@link #unsubscribe})
     */
    public Pointer subscribe(int comId, String srcIp1, int timeoutUs) {
        return subscribe(comId, srcIp1, timeoutUs,
                "0.0.0.0", "0.0.0.0",
                0, 0, 0, FLAGS_DEFAULT, TO_DEFAULT, null);
    }

    /**
     * Full subscribe overload.
     *
     * @param callback optional callback invoked on each received packet;
     *                 receives ({@link PdInfo}, {@code byte[]}).
     */
    public Pointer subscribe(int comId, String srcIp1, int timeoutUs,
                             String srcIp2, String destIp,
                             int serviceId,
                             int etbTopoCnt, int opTrnTopoCnt,
                             int pktFlags, int toBehavior,
                             BiConsumer<PdInfo, byte[]> callback) {
        checkOpen();
        PointerByReference ref = new PointerByReference();
        TrdpLibrary.PdCallback cbPtr = makePdCallback(callback);
        check(lib.tlp_subscribe(
                handle, ref, null, cbPtr,
                serviceId, comId, etbTopoCnt, opTrnTopoCnt,
                ipToInt(srcIp1), ipToInt(srcIp2), ipToInt(destIp),
                (byte) pktFlags, timeoutUs, toBehavior
        ), "tlp_subscribe");
        return ref.getValue();
    }

    /** Update topology/addresses of an existing subscriber. */
    public void resubscribe(Pointer subHandle, String srcIp1, String srcIp2,
                            String destIp, int etbTopoCnt, int opTrnTopoCnt) {
        checkOpen();
        check(lib.tlp_resubscribe(handle, subHandle, etbTopoCnt, opTrnTopoCnt,
                ipToInt(srcIp1), ipToInt(srcIp2), ipToInt(destIp)), "tlp_resubscribe");
    }

    /** Unsubscribe and release the handle. */
    public void unsubscribe(Pointer subHandle) {
        checkOpen();
        check(lib.tlp_unsubscribe(handle, subHandle), "tlp_unsubscribe");
    }

    /**
     * Read the most recently received PD data.
     *
     * @param subHandle subscriber handle
     * @param maxSize   maximum payload size to allocate
     * @return a two-element array {@code [PdInfo, byte[]]} or {@code null}
     *         when no data has been received yet ({@code TRDP_NODATA_ERR}).
     */
    public Object[] get(Pointer subHandle, int maxSize) {
        checkOpen();
        byte[]              buf    = new byte[maxSize];
        IntByReference      size   = new IntByReference(maxSize);
        TrdpLibrary.TrdpPdInfo info = new TrdpLibrary.TrdpPdInfo();

        int rc = lib.tlp_get(handle, subHandle, info, buf, size);
        if (rc == TRDP_NO_ERR) {
            info.read();
            PdInfo pdInfo = pdInfoFromC(info);
            byte[] data   = Arrays.copyOf(buf, size.getValue());
            return new Object[]{ pdInfo, data };
        }
        if (rc == TRDP_NODATA_ERR) return null;
        throw new TrdpException(rc, "tlp_get");
    }

    /** Convenience overload with default max size 1500. */
    public Object[] get(Pointer subHandle) { return get(subHandle, 1500); }

    /** Send a PD pull-request ({@code tlp_request}). */
    public void pdRequest(Pointer subHandle, int comId, String destIp,
                          byte[] data, String srcIp,
                          int serviceId, int redId,
                          int etbTopoCnt, int opTrnTopoCnt,
                          byte pktFlags,
                          int replyComId, String replyIp) {
        checkOpen();
        byte[] payload = (data == null) ? new byte[0] : data;
        check(lib.tlp_request(
                handle, subHandle, serviceId, comId,
                etbTopoCnt, opTrnTopoCnt,
                ipToInt(srcIp), ipToInt(destIp),
                redId, pktFlags, payload, payload.length,
                replyComId, ipToInt(replyIp)
        ), "tlp_request");
    }

    // =========================================================================
    // Message Data — MD (requires MD_SUPPORT=1 in libtrdp)
    // =========================================================================

    /**
     * Send a one-way MD notification ({@code tlm_notify}).
     *
     * @param callback receives ({@link MdInfo}, {@code byte[]}) or {@code null}
     */
    public void mdNotify(int comId, String destIp, byte[] data,
                         String srcIp, int etbTopoCnt, int opTrnTopoCnt,
                         byte pktFlags, int qos, int ttl,
                         String srcUri, String destUri,
                         BiConsumer<MdInfo, byte[]> callback) {
        checkOpen();
        TrdpLibrary.MdCallback cbPtr = makeMdCallback(callback);
        TrdpLibrary.TrdpComParam sp  = new TrdpLibrary.TrdpComParam(qos, ttl, 0);
        byte[] payload = (data == null) ? new byte[0] : data;
        check(lib.tlm_notify(
                handle, null, cbPtr, comId,
                etbTopoCnt, opTrnTopoCnt,
                ipToInt(srcIp), ipToInt(destIp),
                pktFlags, sp, payload, payload.length,
                srcUri.isEmpty() ? null : srcUri,
                destUri.isEmpty() ? null : destUri
        ), "tlm_notify");
    }

    /** Convenience MD notify with defaults. */
    public void mdNotify(int comId, String destIp, byte[] data,
                         BiConsumer<MdInfo, byte[]> callback) {
        mdNotify(comId, destIp, data, "0.0.0.0", 0, 0,
                FLAGS_DEFAULT, 2, 64, "", "", callback);
    }

    /**
     * Send an MD request ({@code tlm_request}).
     *
     * @param callback receives ({@link MdInfo}, {@code byte[]}) for each reply
     * @return 16-byte session UUID
     */
    public byte[] mdRequest(int comId, String destIp, byte[] data,
                            String srcIp, int etbTopoCnt, int opTrnTopoCnt,
                            byte pktFlags, int numReplies, int replyTimeoutUs,
                            int qos, int ttl,
                            String srcUri, String destUri,
                            BiConsumer<MdInfo, byte[]> callback) {
        checkOpen();
        TrdpLibrary.MdCallback cbPtr = makeMdCallback(callback);
        TrdpLibrary.TrdpComParam sp  = new TrdpLibrary.TrdpComParam(qos, ttl, 0);
        byte[] sessionId = new byte[16];
        byte[] payload   = (data == null) ? new byte[0] : data;
        check(lib.tlm_request(
                handle, null, cbPtr, sessionId,
                comId, etbTopoCnt, opTrnTopoCnt,
                ipToInt(srcIp), ipToInt(destIp),
                pktFlags, numReplies, replyTimeoutUs, sp,
                payload, payload.length,
                srcUri.isEmpty() ? null : srcUri,
                destUri.isEmpty() ? null : destUri
        ), "tlm_request");
        return sessionId;
    }

    /** Convenience MD request with defaults. */
    public byte[] mdRequest(int comId, String destIp, byte[] data,
                            BiConsumer<MdInfo, byte[]> callback) {
        return mdRequest(comId, destIp, data, "0.0.0.0", 0, 0,
                FLAGS_DEFAULT, 1, 1_000_000, 2, 64, "", "", callback);
    }

    /** Send MD reply ({@code tlm_reply}). */
    public void mdReply(byte[] sessionId, int comId, byte[] data,
                        int userStatus, int qos, int ttl, String srcUri) {
        checkOpen();
        TrdpLibrary.TrdpComParam sp = new TrdpLibrary.TrdpComParam(qos, ttl, 0);
        byte[] payload = (data == null) ? new byte[0] : data;
        check(lib.tlm_reply(handle, sessionId, comId, userStatus, sp,
                payload, payload.length,
                srcUri.isEmpty() ? null : srcUri), "tlm_reply");
    }

    /** Convenience MD reply with defaults. */
    public void mdReply(byte[] sessionId, int comId, byte[] data) {
        mdReply(sessionId, comId, data, 0, 2, 64, "");
    }

    /** Send MD reply-query (requires subsequent confirm) ({@code tlm_replyQuery}). */
    public void mdReplyQuery(byte[] sessionId, int comId, byte[] data,
                             int userStatus, int confirmTimeoutUs,
                             int qos, int ttl, String srcUri) {
        checkOpen();
        TrdpLibrary.TrdpComParam sp = new TrdpLibrary.TrdpComParam(qos, ttl, 0);
        byte[] payload = (data == null) ? new byte[0] : data;
        check(lib.tlm_replyQuery(handle, sessionId, comId, userStatus,
                confirmTimeoutUs, sp, payload, payload.length,
                srcUri.isEmpty() ? null : srcUri), "tlm_replyQuery");
    }

    /** Confirm receipt of a reply ({@code tlm_confirm}). */
    public void mdConfirm(byte[] sessionId, int userStatus, int qos, int ttl) {
        checkOpen();
        TrdpLibrary.TrdpComParam sp = new TrdpLibrary.TrdpComParam(qos, ttl, 0);
        check(lib.tlm_confirm(handle, sessionId, (short) userStatus, sp),
                "tlm_confirm");
    }

    /** Abort an MD session ({@code tlm_abortSession}). */
    public void mdAbort(byte[] sessionId) {
        checkOpen();
        check(lib.tlm_abortSession(handle, sessionId), "tlm_abortSession");
    }

    /**
     * Add an MD listener ({@code tlm_addListener}).
     *
     * @param callback receives ({@link MdInfo}, {@code byte[]})
     * @return listener handle (pass to {@link #mdDelListener})
     */
    public Pointer mdAddListener(int comId, BiConsumer<MdInfo, byte[]> callback,
                                 String srcIp1, String srcIp2, String mcDestIp,
                                 int etbTopoCnt, int opTrnTopoCnt,
                                 byte pktFlags, boolean comIdListener,
                                 String srcUri, String destUri) {
        checkOpen();
        PointerByReference ref  = new PointerByReference();
        TrdpLibrary.MdCallback cbPtr = makeMdCallback(callback);
        check(lib.tlm_addListener(
                handle, ref, null, cbPtr,
                comIdListener ? (byte) 1 : (byte) 0,
                comId, etbTopoCnt, opTrnTopoCnt,
                ipToInt(srcIp1), ipToInt(srcIp2), ipToInt(mcDestIp),
                pktFlags,
                srcUri.isEmpty() ? null : srcUri,
                destUri.isEmpty() ? null : destUri
        ), "tlm_addListener");
        return ref.getValue();
    }

    /** Convenience listener with all-source / no-multicast filter. */
    public Pointer mdAddListener(int comId, BiConsumer<MdInfo, byte[]> callback) {
        return mdAddListener(comId, callback, "0.0.0.0", "0.0.0.0", "0.0.0.0",
                0, 0, FLAGS_DEFAULT, true, "", "");
    }

    /** Re-register a listener with updated topology/addresses. */
    public void mdReaddListener(Pointer lisHandle, String srcIp, String srcIp2,
                                String mcDestIp, int etbTopoCnt, int opTrnTopoCnt) {
        checkOpen();
        check(lib.tlm_readdListener(handle, lisHandle, etbTopoCnt, opTrnTopoCnt,
                ipToInt(srcIp), ipToInt(srcIp2), ipToInt(mcDestIp)),
                "tlm_readdListener");
    }

    /** Remove an MD listener. */
    public void mdDelListener(Pointer lisHandle) {
        checkOpen();
        check(lib.tlm_delListener(handle, lisHandle), "tlm_delListener");
    }

    // =========================================================================
    // Statistics
    // =========================================================================

    /** Return global session statistics. */
    public Statistics getStatistics() {
        checkOpen();
        TrdpLibrary.TrdpStatistics s = new TrdpLibrary.TrdpStatistics();
        check(lib.tlc_getStatistics(handle, s), "tlc_getStatistics");
        s.read();
        return new Statistics(
            Integer.toUnsignedLong(s.version),
            Integer.toUnsignedLong(s.upTime),
            Integer.toUnsignedLong(s.statisticTime),
            intToIp(s.ownIpAddr),
            intToIp(s.leaderIpAddr),
            Integer.toUnsignedLong(s.numRed),
            Integer.toUnsignedLong(s.numJoin),
            Integer.toUnsignedLong(s.pd.numSubs),
            Integer.toUnsignedLong(s.pd.numPub),
            Integer.toUnsignedLong(s.pd.numSend),
            Integer.toUnsignedLong(s.pd.numRcv),
            Integer.toUnsignedLong(s.pd.numTimeout),
            Integer.toUnsignedLong(s.pd.numCrcErr),
            Integer.toUnsignedLong(s.udpMd.numSend),
            Integer.toUnsignedLong(s.udpMd.numRcv),
            Integer.toUnsignedLong(s.udpMd.numReplyTimeout),
            Integer.toUnsignedLong(s.tcpMd.numSend),
            Integer.toUnsignedLong(s.tcpMd.numRcv)
        );
    }

    /** Return per-subscription statistics (up to {@code maxCount} entries). */
    public List<Statistics.SubsStats> getSubsStatistics(int maxCount) {
        checkOpen();
        ShortByReference n = new ShortByReference((short) maxCount);
        TrdpLibrary.TrdpSubsStatistics[] arr = new TrdpLibrary.TrdpSubsStatistics[maxCount];
        for (int i = 0; i < maxCount; i++) arr[i] = new TrdpLibrary.TrdpSubsStatistics();
        check(lib.tlc_getSubsStatistics(handle, n, arr), "tlc_getSubsStatistics");
        int count = Short.toUnsignedInt(n.getValue());
        List<Statistics.SubsStats> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            arr[i].read();
            TrdpLibrary.TrdpSubsStatistics s = arr[i];
            result.add(new Statistics.SubsStats(
                    s.comId, intToIp(s.joinedAddr), intToIp(s.filterAddr),
                    Integer.toUnsignedLong(s.timeout),
                    s.status,
                    Integer.toUnsignedLong(s.numRecv),
                    Integer.toUnsignedLong(s.numMissed)
            ));
        }
        return Collections.unmodifiableList(result);
    }

    /** Return per-publisher statistics (up to {@code maxCount} entries). */
    public List<Statistics.PubStats> getPubStatistics(int maxCount) {
        checkOpen();
        ShortByReference n = new ShortByReference((short) maxCount);
        TrdpLibrary.TrdpPubStatistics[] arr = new TrdpLibrary.TrdpPubStatistics[maxCount];
        for (int i = 0; i < maxCount; i++) arr[i] = new TrdpLibrary.TrdpPubStatistics();
        check(lib.tlc_getPubStatistics(handle, n, arr), "tlc_getPubStatistics");
        int count = Short.toUnsignedInt(n.getValue());
        List<Statistics.PubStats> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            arr[i].read();
            TrdpLibrary.TrdpPubStatistics s = arr[i];
            result.add(new Statistics.PubStats(
                    s.comId, intToIp(s.destAddr),
                    Integer.toUnsignedLong(s.cycle),
                    Integer.toUnsignedLong(s.redId),
                    Integer.toUnsignedLong(s.redState),
                    Integer.toUnsignedLong(s.numPut),
                    Integer.toUnsignedLong(s.numSend)
            ));
        }
        return Collections.unmodifiableList(result);
    }

    /** Return multicast group addresses joined by this session. */
    public List<String> getJoinAddresses(int maxCount) {
        checkOpen();
        ShortByReference n   = new ShortByReference((short) maxCount);
        int[]            arr = new int[maxCount];
        check(lib.tlc_getJoinStatistics(handle, n, arr), "tlc_getJoinStatistics");
        int count = Short.toUnsignedInt(n.getValue());
        List<String> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) result.add(intToIp(arr[i]));
        return Collections.unmodifiableList(result);
    }

    /** Reset all statistic counters. */
    public void resetStatistics() {
        checkOpen();
        check(lib.tlc_resetStatistics(handle), "tlc_resetStatistics");
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private void checkOpen() {
        if (!open) throw new IllegalStateException("TrdpSession is not open");
    }

    private static void check(int rc, String fn) {
        if (rc != TRDP_NO_ERR) throw new TrdpException(rc, fn);
    }

    /** Wrap a PD callback (keeps strong ref to prevent GC). */
    private TrdpLibrary.PdCallback makePdCallback(BiConsumer<PdInfo, byte[]> cb) {
        if (cb == null) return null;
        TrdpLibrary.PdCallback jnaCb = (pRefCon, appHandle, pMsg, pData, dataSize) -> {
            try {
                pMsg.read();
                PdInfo info = pdInfoFromC(pMsg);
                byte[] data = (dataSize > 0) ? pData.getByteArray(0, dataSize) : new byte[0];
                cb.accept(info, data);
            } catch (Exception e) {
                LOG.warning("PD callback exception: " + e);
            }
        };
        callbackRefs.add(jnaCb);
        return jnaCb;
    }

    /** Wrap an MD callback (keeps strong ref to prevent GC). */
    private TrdpLibrary.MdCallback makeMdCallback(BiConsumer<MdInfo, byte[]> cb) {
        if (cb == null) return null;
        TrdpLibrary.MdCallback jnaCb = (pRefCon, appHandle, pMsg, pData, dataSize) -> {
            try {
                pMsg.read();
                MdInfo info = mdInfoFromC(pMsg);
                byte[] data = (dataSize > 0) ? pData.getByteArray(0, dataSize) : new byte[0];
                cb.accept(info, data);
            } catch (Exception e) {
                LOG.warning("MD callback exception: " + e);
            }
        };
        callbackRefs.add(jnaCb);
        return jnaCb;
    }

    // ── C struct → Java data class conversions ────────────────────────────────

    private static PdInfo pdInfoFromC(TrdpLibrary.TrdpPdInfo c) {
        return new PdInfo(
            intToIp(c.srcIpAddr),
            intToIp(c.destIpAddr),
            c.comId,
            Integer.toUnsignedLong(c.seqCount),
            Short.toUnsignedInt(c.msgType),
            Integer.toUnsignedLong(c.etbTopoCnt),
            Integer.toUnsignedLong(c.opTrnTopoCnt),
            Integer.toUnsignedLong(c.replyComId),
            intToIp(c.replyIpAddr),
            c.resultCode,
            Integer.toUnsignedLong(c.serviceId)
        );
    }

    private static MdInfo mdInfoFromC(TrdpLibrary.TrdpMdInfo c) {
        String srcUri  = cstrToJava(c.srcUserURI);
        String destUri = cstrToJava(c.destUserURI);
        return new MdInfo(
            intToIp(c.srcIpAddr),
            intToIp(c.destIpAddr),
            c.comId,
            Integer.toUnsignedLong(c.seqCount),
            Short.toUnsignedInt(c.msgType),
            Integer.toUnsignedLong(c.etbTopoCnt),
            Integer.toUnsignedLong(c.opTrnTopoCnt),
            c.sessionId,
            c.resultCode,
            Short.toUnsignedInt(c.userStatus),
            c.replyStatus,
            c.aboutToDie != 0,
            Integer.toUnsignedLong(c.numReplies),
            Integer.toUnsignedLong(c.numExpReplies),
            srcUri, destUri,
            Integer.toUnsignedLong(c.replyTimeout)
        );
    }

    // ── IP address utilities ──────────────────────────────────────────────────

    /**
     * Convert dotted-decimal to big-endian {@code int} (network byte order).
     * Works correctly for addresses ≥ 128.x.x.x (e.g. multicast 239.x.x.x).
     */
    static int ipToInt(String ip) {
        try {
            byte[] b = InetAddress.getByName(ip).getAddress();
            return ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16)
                 | ((b[2] & 0xFF) <<  8) |  (b[3] & 0xFF);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Invalid IP address: " + ip, e);
        }
    }

    /** Convert big-endian {@code int} to dotted-decimal string. */
    static String intToIp(int addr) {
        return ((addr >>> 24) & 0xFF) + "."
             + ((addr >>> 16) & 0xFF) + "."
             + ((addr >>>  8) & 0xFF) + "."
             +  (addr         & 0xFF);
    }

    /** Null-terminated {@code byte[]} → Java String (ASCII). */
    private static String cstrToJava(byte[] bytes) {
        int len = 0;
        while (len < bytes.length && bytes[len] != 0) len++;
        return new String(bytes, 0, len, StandardCharsets.US_ASCII);
    }
}
