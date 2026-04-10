package io.trdp.template;

import com.sun.jna.Pointer;
import io.trdp.PdInfo;
import io.trdp.TrdpSession;

import java.util.AbstractMap;
import java.util.Map;
import java.util.Optional;

/**
 * Typed PD subscriber handle returned by {@link TrdpTemplate.SubscriberBuilder#register()}.
 *
 * <p>Provides two consumption styles:
 * <ul>
 *   <li><b>Push</b> — register an {@code onMessage} callback in the builder; the callback
 *       is invoked on the processing thread each time a packet arrives.</li>
 *   <li><b>Pull</b> — call {@link #poll()} or {@link #receive()} at any time to read the
 *       most recently received value (non-blocking).</li>
 * </ul>
 *
 * <p>Example (pull):
 * <pre>{@code
 *   TrdpSubscriber<String> sub = template
 *       .subscriber(TrdpDeserializer.string())
 *       .comId(2000)
 *       .src("10.0.1.2")
 *       .timeoutMs(500)
 *       .register();
 *
 *   String value = sub.poll();           // null if nothing received yet
 *   Optional<Map.Entry<PdInfo,String>> r = sub.receive(); // with metadata
 *   sub.close();
 * }</pre>
 *
 * <p>Example (push):
 * <pre>{@code
 *   template.subscriber(TrdpDeserializer.string())
 *       .comId(2000)
 *       .src("0.0.0.0")
 *       .onMessage((info, s) -> System.out.println("got: " + s))
 *       .register();
 * }</pre>
 *
 * <p>Implements {@link AutoCloseable} for use in try-with-resources.
 *
 * <p><b>Thread safety:</b> not thread-safe — use from the same thread as the session.
 *
 * @param <T> application value type
 */
public final class TrdpSubscriber<T> implements AutoCloseable {

    private final TrdpSession        session;
    private final Pointer            handle;
    private final TrdpDeserializer<T> deserializer;
    private       boolean             closed;

    /** Package-private: created exclusively by {@link TrdpTemplate.SubscriberBuilder}. */
    TrdpSubscriber(TrdpSession session, Pointer handle, TrdpDeserializer<T> deserializer) {
        this.session      = session;
        this.handle       = handle;
        this.deserializer = deserializer;
    }

    // ── Pull API ──────────────────────────────────────────────────────────────

    /**
     * Return the most recently received value, or {@code null} if no packet has
     * arrived yet ({@code TRDP_NODATA_ERR}).  Does not block.
     *
     * @throws IllegalStateException if this subscriber has already been closed
     */
    public T poll() {
        checkOpen();
        Object[] raw = session.get(handle);
        if (raw == null) return null;
        return deserializer.deserialize((byte[]) raw[1]);
    }

    /**
     * Return the most recently received message together with its {@link PdInfo}
     * metadata, or {@link Optional#empty()} if no data yet.  Does not block.
     *
     * @throws IllegalStateException if this subscriber has already been closed
     */
    public Optional<Map.Entry<PdInfo, T>> receive() {
        checkOpen();
        Object[] raw = session.get(handle);
        if (raw == null) return Optional.empty();
        T value = deserializer.deserialize((byte[]) raw[1]);
        return Optional.of(new AbstractMap.SimpleImmutableEntry<>((PdInfo) raw[0], value));
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Unsubscribe and release the underlying TRDP handle.
     * Calling {@code close()} more than once is safe (idempotent).
     */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            session.unsubscribe(handle);
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void checkOpen() {
        if (closed) throw new IllegalStateException("TrdpSubscriber is already closed");
    }
}
