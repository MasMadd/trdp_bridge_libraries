package io.trdp.template;

import com.sun.jna.Pointer;
import io.trdp.PdInfo;
import io.trdp.TrdpSession;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * High-level template for TRDP Process Data (PD) communication.
 *
 * <p>Analogous to Spring Kafka's {@code KafkaTemplate} / {@code KafkaListenerContainer}
 * pair, this class provides a fluent builder API for creating typed publishers and
 * subscribers on top of a {@link TrdpSession}.
 *
 * <h2>Quick start</h2>
 * <pre>{@code
 * // Self-managed session (template opens and closes the session)
 * try (TrdpTemplate t = TrdpTemplate.builder()
 *         .ownIp("10.0.1.1")
 *         .pollPeriodMs(10)
 *         .build()) {
 *
 *     TrdpPublisher<String> pub = t.publisher(TrdpSerializer.string())
 *         .comId(1000)
 *         .dest("239.192.0.0")
 *         .intervalMs(100)
 *         .register();
 *
 *     TrdpSubscriber<String> sub = t.subscriber(TrdpDeserializer.string())
 *         .comId(2000)
 *         .src("0.0.0.0")
 *         .timeoutMs(500)
 *         .onMessage((info, msg) -> System.out.println("received: " + msg))
 *         .register();
 *
 *     t.start();   // begin background processing
 *     pub.send("hello");
 * }
 * }</pre>
 *
 * <h2>Wrapping an existing session</h2>
 * <pre>{@code
 * TrdpTemplate t = new TrdpTemplate(existingSession);
 * // The template does NOT close the session on t.close()
 * }</pre>
 *
 * <h2>Thread safety</h2>
 * {@code TrdpTemplate} is not thread-safe. Use {@link #start()} to delegate processing
 * to a background daemon thread, or call {@link TrdpSession#processOnce} manually from
 * a single thread.
 */
public class TrdpTemplate implements AutoCloseable {

    private final TrdpSession session;
    private final boolean     ownsSession;
    private final int         pollPeriodMs;

    // ── Constructors ──────────────────────────────────────────────────────────

    /**
     * Wrap an externally-managed session with a default poll period of 10 ms.
     * The template will <em>not</em> close the session when {@link #close()} is called.
     */
    public TrdpTemplate(TrdpSession session) {
        this(session, false, 10);
    }

    /**
     * Wrap an externally-managed session with a custom poll period.
     * The template will <em>not</em> close the session when {@link #close()} is called.
     *
     * @param pollPeriodMs background processing thread period in milliseconds
     */
    public TrdpTemplate(TrdpSession session, int pollPeriodMs) {
        this(session, false, pollPeriodMs);
    }

    private TrdpTemplate(TrdpSession session, boolean ownsSession, int pollPeriodMs) {
        this.session      = Objects.requireNonNull(session, "session");
        this.ownsSession  = ownsSession;
        this.pollPeriodMs = pollPeriodMs;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Start the background processing daemon thread.
     *
     * <p>If you prefer manual control, skip this call and instead drive the stack
     * yourself by calling {@link TrdpSession#processOnce} periodically.
     */
    public void start() {
        session.startProcessingThread(pollPeriodMs);
    }

    /** Stop the background processing thread (if running). */
    public void stop() {
        session.stopProcessingThread();
    }

    /**
     * Stop the processing thread. If this template owns the session (created via
     * {@link Builder#build()}), also closes the session.
     */
    @Override
    public void close() {
        stop();
        if (ownsSession) session.close();
    }

    /** Return the underlying {@link TrdpSession} for advanced / low-level use. */
    public TrdpSession getSession() {
        return session;
    }

    // ── Publisher builder factory ─────────────────────────────────────────────

    /**
     * Begin building a typed PD publisher.
     *
     * @param serializer converts application values to byte payloads
     * @param <T>        application value type
     * @return a {@link PublisherBuilder} for further configuration
     */
    public <T> PublisherBuilder<T> publisher(TrdpSerializer<T> serializer) {
        return new PublisherBuilder<>(this, serializer);
    }

    /** Convenience factory for raw {@code byte[]} publishers. */
    public PublisherBuilder<byte[]> publisher() {
        return publisher(TrdpSerializer.bytes());
    }

    /** Convenience factory for UTF-8 string publishers. */
    public PublisherBuilder<String> stringPublisher() {
        return publisher(TrdpSerializer.string());
    }

    // ── Subscriber builder factory ────────────────────────────────────────────

    /**
     * Begin building a typed PD subscriber.
     *
     * @param deserializer converts byte payloads to application values
     * @param <T>          application value type
     * @return a {@link SubscriberBuilder} for further configuration
     */
    public <T> SubscriberBuilder<T> subscriber(TrdpDeserializer<T> deserializer) {
        return new SubscriberBuilder<>(this, deserializer);
    }

    /** Convenience factory for raw {@code byte[]} subscribers. */
    public SubscriberBuilder<byte[]> subscriber() {
        return subscriber(TrdpDeserializer.bytes());
    }

    /** Convenience factory for UTF-8 string subscribers. */
    public SubscriberBuilder<String> stringSubscriber() {
        return subscriber(TrdpDeserializer.string());
    }

    // ── Static builder ────────────────────────────────────────────────────────

    /** @return a new {@link Builder} for creating a self-managed session. */
    public static Builder builder() {
        return new Builder();
    }

    // =========================================================================
    // PublisherBuilder
    // =========================================================================

    /**
     * Fluent builder for a typed {@link TrdpPublisher}.
     *
     * <p>Mandatory fields: {@link #comId(int)}, {@link #dest(String)},
     * and one of {@link #intervalUs(int)} / {@link #intervalMs(int)}.
     *
     * @param <T> application value type
     */
    public static final class PublisherBuilder<T> {

        private final TrdpTemplate      template;
        private final TrdpSerializer<T> serializer;

        // mandatory
        private int    comId;
        private String destIp;
        private int    intervalUs;

        // optional
        private String srcIp        = "0.0.0.0";
        private int    serviceId    = 0;
        private int    redId        = 0;
        private int    etbTopoCnt   = 0;
        private int    opTrnTopoCnt = 0;
        private byte   pktFlags     = TrdpSession.FLAGS_DEFAULT;
        private byte[] initialData  = new byte[0];

        private PublisherBuilder(TrdpTemplate template, TrdpSerializer<T> serializer) {
            this.template   = template;
            this.serializer = serializer;
        }

        /** Communication identifier (must match subscribers). */
        public PublisherBuilder<T> comId(int comId)            { this.comId = comId;             return this; }

        /** Destination IP — unicast or multicast (e.g. {@code "239.192.0.0"}). */
        public PublisherBuilder<T> dest(String destIp)         { this.destIp = destIp;           return this; }

        /** Publishing period in microseconds. */
        public PublisherBuilder<T> intervalUs(int us)          { this.intervalUs = us;           return this; }

        /** Publishing period in milliseconds (converted to microseconds). */
        public PublisherBuilder<T> intervalMs(int ms)          { this.intervalUs = ms * 1_000;   return this; }

        /** Source IP filter for the publisher ({@code "0.0.0.0"} = any). */
        public PublisherBuilder<T> src(String srcIp)           { this.srcIp = srcIp;             return this; }

        /** Service ID for service-oriented use (0 = none). */
        public PublisherBuilder<T> serviceId(int id)           { this.serviceId = id;            return this; }

        /** Redundancy group ID (0 = no redundancy). */
        public PublisherBuilder<T> redId(int id)               { this.redId = id;                return this; }

        /** ETB topology counter (0 = ignore). */
        public PublisherBuilder<T> etbTopoCnt(int v)           { this.etbTopoCnt = v;            return this; }

        /** Operational-train topology counter (0 = ignore). */
        public PublisherBuilder<T> opTrnTopoCnt(int v)         { this.opTrnTopoCnt = v;          return this; }

        /** Packet flags bitmask (see {@code TrdpSession.FLAGS_*}). */
        public PublisherBuilder<T> flags(byte flags)           { this.pktFlags = flags;          return this; }

        /** Initial payload to pre-load the publisher with. */
        public PublisherBuilder<T> initialValue(T value)       { this.initialData = serializer.serialize(value); return this; }

        /**
         * Register the publisher with TRDP and return the typed {@link TrdpPublisher} handle.
         *
         * @throws NullPointerException     if {@link #dest(String)} was not set
         * @throws IllegalStateException    if {@link #intervalUs(int)} / {@link #intervalMs(int)} was not set
         * @throws io.trdp.TrdpException    on TRDP API error
         */
        public TrdpPublisher<T> register() {
            Objects.requireNonNull(destIp, "dest() IP is required");
            if (intervalUs <= 0) throw new IllegalStateException("intervalUs / intervalMs must be > 0");

            Pointer handle = template.session.publish(
                    comId, destIp, intervalUs,
                    initialData, srcIp,
                    serviceId, redId, etbTopoCnt, opTrnTopoCnt,
                    pktFlags,
                    null   // callbacks are handled at the subscriber level
            );
            return new TrdpPublisher<>(template.session, handle, serializer);
        }
    }

    // =========================================================================
    // SubscriberBuilder
    // =========================================================================

    /**
     * Fluent builder for a typed {@link TrdpSubscriber}.
     *
     * <p>Mandatory field: {@link #comId(int)}.
     * All other fields are optional and default to permissive / disabled values.
     *
     * @param <T> application value type
     */
    public static final class SubscriberBuilder<T> {

        private final TrdpTemplate        template;
        private final TrdpDeserializer<T>  deserializer;

        // mandatory
        private int comId;

        // optional
        private String srcIp1       = "0.0.0.0";
        private String srcIp2       = "0.0.0.0";
        private String destIp       = "0.0.0.0";
        private int    timeoutUs    = 0;
        private int    serviceId    = 0;
        private int    etbTopoCnt   = 0;
        private int    opTrnTopoCnt = 0;
        private int    pktFlags     = TrdpSession.FLAGS_DEFAULT;
        private int    toBehavior   = TrdpSession.TO_DEFAULT;

        private BiConsumer<PdInfo, T> messageListener;

        private SubscriberBuilder(TrdpTemplate template, TrdpDeserializer<T> deserializer) {
            this.template     = template;
            this.deserializer = deserializer;
        }

        /** Communication identifier to subscribe to. */
        public SubscriberBuilder<T> comId(int comId)            { this.comId = comId;           return this; }

        /** Source IP filter ({@code "0.0.0.0"} = accept from any source). */
        public SubscriberBuilder<T> src(String srcIp)           { this.srcIp1 = srcIp;          return this; }

        /**
         * Source IP range filter (two IP addresses forming an inclusive range).
         * Both must be in the same subnet.
         */
        public SubscriberBuilder<T> srcRange(String ip1, String ip2) {
            this.srcIp1 = ip1; this.srcIp2 = ip2; return this;
        }

        /** Multicast destination group to join ({@code "0.0.0.0"} for unicast). */
        public SubscriberBuilder<T> dest(String destIp)         { this.destIp = destIp;         return this; }

        /** Receive timeout in microseconds (0 = infinite). */
        public SubscriberBuilder<T> timeoutUs(int us)           { this.timeoutUs = us;           return this; }

        /** Receive timeout in milliseconds (0 = infinite). */
        public SubscriberBuilder<T> timeoutMs(int ms)           { this.timeoutUs = ms * 1_000;  return this; }

        /** Service ID for service-oriented use (0 = none). */
        public SubscriberBuilder<T> serviceId(int id)           { this.serviceId = id;           return this; }

        /** ETB topology counter (0 = ignore). */
        public SubscriberBuilder<T> etbTopoCnt(int v)           { this.etbTopoCnt = v;           return this; }

        /** Operational-train topology counter (0 = ignore). */
        public SubscriberBuilder<T> opTrnTopoCnt(int v)         { this.opTrnTopoCnt = v;         return this; }

        /** Packet flags bitmask (see {@code TrdpSession.FLAGS_*}). */
        public SubscriberBuilder<T> flags(int flags)            { this.pktFlags = flags;         return this; }

        /** Keep the last valid value when the receive timeout fires. */
        public SubscriberBuilder<T> keepLastOnTimeout()         { this.toBehavior = TrdpSession.TO_KEEP_LAST_VALUE; return this; }

        /** Zero the data buffer when the receive timeout fires. */
        public SubscriberBuilder<T> zeroOnTimeout()             { this.toBehavior = TrdpSession.TO_SET_TO_ZERO;     return this; }

        /**
         * Register a push-style message listener.
         *
         * <p>The callback is invoked on the processing thread each time a packet arrives
         * for this subscription.  Do not block or throw from within the callback.
         *
         * <p>Registering a callback sets {@link TrdpSession#FLAGS_CALLBACK} automatically
         * unless you have already specified custom flags via {@link #flags(int)}.
         *
         * @param listener receives ({@link PdInfo} metadata, deserialized value)
         */
        public SubscriberBuilder<T> onMessage(BiConsumer<PdInfo, T> listener) {
            this.messageListener = listener;
            return this;
        }

        /**
         * Register the subscription with TRDP and return the typed {@link TrdpSubscriber} handle.
         *
         * @throws io.trdp.TrdpException on TRDP API error
         */
        public TrdpSubscriber<T> register() {
            TrdpDeserializer<T>       deser  = this.deserializer;
            BiConsumer<PdInfo, byte[]> rawCb = (messageListener == null) ? null :
                    (info, data) -> messageListener.accept(info, deser.deserialize(data));

            Pointer handle = template.session.subscribe(
                    comId, srcIp1, timeoutUs,
                    srcIp2, destIp,
                    serviceId, etbTopoCnt, opTrnTopoCnt,
                    pktFlags, toBehavior,
                    rawCb
            );
            return new TrdpSubscriber<>(template.session, handle, deser);
        }
    }

    // =========================================================================
    // Builder (self-managed session)
    // =========================================================================

    /**
     * Builder for a {@link TrdpTemplate} that owns and manages the underlying
     * {@link TrdpSession} lifecycle.
     */
    public static final class Builder {

        private String  ownIp;
        private String  leaderIp     = "0.0.0.0";
        private String  libName      = "trdp";
        private boolean autoInit     = true;
        private int     pollPeriodMs = 10;

        private Builder() {}

        /** Own IP address in dotted-decimal notation (required). */
        public Builder ownIp(String ip)       { this.ownIp = ip;         return this; }

        /** Redundancy leader IP ({@code "0.0.0.0"} = not used). */
        public Builder leaderIp(String ip)    { this.leaderIp = ip;      return this; }

        /** Native library name passed to JNA (default: {@code "trdp"}). */
        public Builder libName(String name)   { this.libName = name;     return this; }

        /**
         * When {@code true} (default), {@code tlc_init} / {@code tlc_terminate} are called
         * automatically.  Set to {@code false} when you share one {@code tlc_init} call
         * across multiple templates in the same process.
         */
        public Builder autoInit(boolean v)    { this.autoInit = v;       return this; }

        /** Background processing thread period in milliseconds (default: 10). */
        public Builder pollPeriodMs(int ms)   { this.pollPeriodMs = ms;  return this; }

        /**
         * Build the template, opening the TRDP session immediately.
         *
         * @throws NullPointerException  if {@link #ownIp(String)} was not set
         * @throws io.trdp.TrdpException if the session cannot be opened
         */
        public TrdpTemplate build() {
            Objects.requireNonNull(ownIp, "ownIp() is required");
            TrdpSession session = new TrdpSession(ownIp, leaderIp, libName, autoInit);
            return new TrdpTemplate(session, true, pollPeriodMs);
        }
    }
}
