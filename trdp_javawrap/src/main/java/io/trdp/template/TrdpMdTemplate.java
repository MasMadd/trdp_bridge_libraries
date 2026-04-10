package io.trdp.template;

import io.trdp.MdInfo;
import io.trdp.TrdpSession;

import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/**
 * High-level template for TRDP Message Data (MD) communication.
 *
 * <p>Analogous to Spring Kafka's {@code ReplyingKafkaTemplate}, this class wraps the
 * request / reply pattern of TRDP MD behind a clean, Java-idiomatic API — including
 * a {@link CompletableFuture}-based async request.
 *
 * <p>MD functions require the native library to have been compiled with
 * {@code MD_SUPPORT=1}.
 *
 * <h2>Quick start</h2>
 * <pre>{@code
 * TrdpMdTemplate md = new TrdpMdTemplate(template);
 *
 * // Fire-and-forget notification
 * md.notify(3000, "10.0.1.2", "ping".getBytes());
 *
 * // Async request — returns CompletableFuture
 * md.asyncRequest(3000, "10.0.1.2", "ping".getBytes(), 1_000)
 *   .thenAccept(reply -> System.out.println("reply: " + new String(reply)))
 *   .exceptionally(ex -> { System.err.println("timeout: " + ex); return null; });
 *
 * // Typed async request
 * CompletableFuture<MyReply> future = md.asyncRequest(
 *     3000, "10.0.1.2",
 *     myRequest, TrdpSerializer.string().andThen(…),
 *     TrdpDeserializer.string().andThen(…),
 *     1_000);
 *
 * // Server side: add a listener, send a reply
 * try (MdListenerHandle h = md.addListener(3000,
 *         TrdpDeserializer.string(),
 *         (info, msg) -> {
 *             System.out.println("request: " + msg);
 *             md.reply(info.sessionId, 3001, "pong".getBytes());
 *         })) {
 *     Thread.sleep(5_000);
 * }
 * }</pre>
 *
 * <h2>Thread safety</h2>
 * All methods delegate to the underlying {@link TrdpSession}, which is not thread-safe.
 * Use from the same thread as the session, or add external synchronisation.
 */
public class TrdpMdTemplate {

    /**
     * Shared single-thread scheduler used exclusively for request-timeout tasks.
     * Daemon so it does not prevent JVM exit.
     */
    private static final ScheduledExecutorService TIMEOUT_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "trdp-md-timeout");
                t.setDaemon(true);
                return t;
            });

    private final TrdpSession session;

    // ── Constructors ──────────────────────────────────────────────────────────

    /**
     * Create an MD template from an existing session.
     */
    public TrdpMdTemplate(TrdpSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    /**
     * Create an MD template from an existing {@link TrdpTemplate},
     * sharing its underlying session.
     */
    public TrdpMdTemplate(TrdpTemplate template) {
        this(template.getSession());
    }

    // ── Notify (fire and forget) ──────────────────────────────────────────────

    /**
     * Send a one-way MD notification ({@code tlm_notify}). No reply is expected.
     *
     * @param comId  communication identifier
     * @param destIp destination IP address
     * @param data   payload bytes
     */
    public void notify(int comId, String destIp, byte[] data) {
        session.mdNotify(comId, destIp, data, null);
    }

    /**
     * Send a typed one-way notification. The value is serialized before sending.
     *
     * @param <T>        application value type
     * @param comId      communication identifier
     * @param destIp     destination IP address
     * @param value      value to send
     * @param serializer serializer for {@code T}
     */
    public <T> void notify(int comId, String destIp, T value, TrdpSerializer<T> serializer) {
        session.mdNotify(comId, destIp, serializer.serialize(value), null);
    }

    // ── Request / reply (synchronous callback) ────────────────────────────────

    /**
     * Send an MD request and deliver replies to {@code callback}.
     *
     * <p>The callback is invoked on the processing thread for each reply received
     * (and once more on timeout / abort). Do not block or throw from the callback.
     *
     * @param comId    communication identifier
     * @param destIp   destination IP address
     * @param data     payload bytes
     * @param callback receives ({@link MdInfo}, reply {@code byte[]})
     * @return 16-byte session UUID that identifies this request
     */
    public byte[] request(int comId, String destIp, byte[] data,
                          BiConsumer<MdInfo, byte[]> callback) {
        return session.mdRequest(comId, destIp, data, callback);
    }

    // ── Async request (CompletableFuture) ─────────────────────────────────────

    /**
     * Send an MD request and return a {@link CompletableFuture} that completes
     * with the first reply payload.
     *
     * <p>The future completes exceptionally with a {@link TimeoutException} if no
     * reply is received within {@code timeoutMs} milliseconds.
     *
     * <pre>{@code
     * md.asyncRequest(3000, "10.0.1.2", "ping".getBytes(), 1_000)
     *   .thenAccept(reply -> System.out.println(new String(reply)))
     *   .exceptionally(ex -> { log.warn("no reply: {}", ex.getMessage()); return null; });
     * }</pre>
     *
     * @param comId     communication identifier
     * @param destIp    destination IP address
     * @param data      payload bytes
     * @param timeoutMs timeout in milliseconds before completing exceptionally
     * @return future completing with the first reply payload
     */
    public CompletableFuture<byte[]> asyncRequest(int comId, String destIp,
                                                   byte[] data, int timeoutMs) {
        CompletableFuture<byte[]> future = new CompletableFuture<>();

        // Schedule timeout — completeExceptionally is a no-op if future already done
        ScheduledFuture<?> timeoutTask = TIMEOUT_SCHEDULER.schedule(
                () -> future.completeExceptionally(
                        new TimeoutException("MD reply timeout after " + timeoutMs + " ms")),
                timeoutMs, TimeUnit.MILLISECONDS);

        session.mdRequest(comId, destIp, data, (info, payload) -> {
            timeoutTask.cancel(false);  // cancel the timer if reply arrives first
            future.complete(payload);   // no-op if timeout already fired
        });

        return future;
    }

    /**
     * Typed async request — serializes the request, sends it, and deserializes the reply.
     *
     * <pre>{@code
     * CompletableFuture<String> f = md.asyncRequest(
     *     3000, "10.0.1.2",
     *     "ping",
     *     TrdpSerializer.string(),
     *     TrdpDeserializer.string(),
     *     1_000);
     * }</pre>
     *
     * @param <REQ>        request type
     * @param <REP>        reply type
     * @param comId        communication identifier
     * @param destIp       destination IP address
     * @param request      request value
     * @param serializer   serializer for {@code REQ}
     * @param deserializer deserializer for {@code REP}
     * @param timeoutMs    timeout in milliseconds
     * @return future completing with the deserialized reply
     */
    public <REQ, REP> CompletableFuture<REP> asyncRequest(
            int comId, String destIp,
            REQ request, TrdpSerializer<REQ> serializer,
            TrdpDeserializer<REP> deserializer,
            int timeoutMs) {
        return asyncRequest(comId, destIp, serializer.serialize(request), timeoutMs)
                .thenApply(deserializer::deserialize);
    }

    // ── Reply ─────────────────────────────────────────────────────────────────

    /**
     * Send an MD reply ({@code tlm_reply}).
     *
     * <p>Call this from inside an {@link MdListenerHandle} callback after receiving a
     * request.  The {@code sessionId} is obtained from {@link MdInfo#sessionId}.
     *
     * @param sessionId 16-byte session UUID from the incoming {@link MdInfo}
     * @param comId     reply communication identifier
     * @param data      reply payload
     */
    public void reply(byte[] sessionId, int comId, byte[] data) {
        session.mdReply(sessionId, comId, data);
    }

    /**
     * Send a typed MD reply.
     *
     * @param <T>        reply value type
     * @param sessionId  16-byte session UUID
     * @param comId      reply communication identifier
     * @param value      reply value
     * @param serializer serializer for {@code T}
     */
    public <T> void reply(byte[] sessionId, int comId, T value, TrdpSerializer<T> serializer) {
        session.mdReply(sessionId, comId, serializer.serialize(value));
    }

    /**
     * Send an MD reply that requires a confirmation ({@code tlm_replyQuery}).
     *
     * @param sessionId        16-byte session UUID
     * @param comId            reply communication identifier
     * @param data             reply payload
     * @param confirmTimeoutUs time the requester has to confirm (microseconds)
     */
    public void replyQuery(byte[] sessionId, int comId, byte[] data, int confirmTimeoutUs) {
        session.mdReplyQuery(sessionId, comId, data, 0, confirmTimeoutUs, 2, 64, "");
    }

    /**
     * Send a confirmation for a received reply ({@code tlm_confirm}).
     *
     * @param sessionId  16-byte session UUID
     * @param userStatus application status code (0 = OK)
     */
    public void confirm(byte[] sessionId, int userStatus) {
        session.mdConfirm(sessionId, userStatus, 2, 64);
    }

    /**
     * Abort an in-progress MD session ({@code tlm_abortSession}).
     *
     * @param sessionId 16-byte session UUID
     */
    public void abort(byte[] sessionId) {
        session.mdAbort(sessionId);
    }

    // ── Listener ─────────────────────────────────────────────────────────────

    /**
     * Add a raw-byte MD listener for the given {@code comId}.
     *
     * <p>Returns a {@link MdListenerHandle} that removes the listener when closed.
     *
     * @param comId    communication identifier to listen for
     * @param callback receives ({@link MdInfo} metadata, raw payload {@code byte[]})
     * @return an {@link AutoCloseable} handle to remove the listener
     */
    public MdListenerHandle addListener(int comId, BiConsumer<MdInfo, byte[]> callback) {
        return new MdListenerHandle(session, session.mdAddListener(comId, callback));
    }

    /**
     * Add a typed MD listener for the given {@code comId}.
     *
     * <p>Incoming bytes are deserialized before the callback is invoked.
     *
     * <pre>{@code
     * try (MdListenerHandle h = md.addListener(3000,
     *         TrdpDeserializer.string(),
     *         (info, msg) -> System.out.println("got: " + msg))) {
     *     Thread.sleep(5_000);
     * }
     * }</pre>
     *
     * @param <T>          application value type
     * @param comId        communication identifier to listen for
     * @param deserializer deserializer applied to incoming bytes
     * @param callback     receives ({@link MdInfo} metadata, deserialized value)
     * @return an {@link AutoCloseable} handle to remove the listener
     */
    public <T> MdListenerHandle addListener(int comId,
                                             TrdpDeserializer<T> deserializer,
                                             BiConsumer<MdInfo, T> callback) {
        BiConsumer<MdInfo, byte[]> rawCb =
                (info, data) -> callback.accept(info, deserializer.deserialize(data));
        return new MdListenerHandle(session, session.mdAddListener(comId, rawCb));
    }
}
