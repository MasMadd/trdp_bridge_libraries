package io.trdp.template;

import com.sun.jna.Pointer;
import io.trdp.TrdpSession;

/**
 * Typed PD publisher handle returned by {@link TrdpTemplate.PublisherBuilder#register()}.
 *
 * <p>Wraps the raw {@link Pointer} returned by {@link TrdpSession#publish} and serializes
 * application values to bytes automatically before passing them to the TRDP stack.
 *
 * <p>Example:
 * <pre>{@code
 *   TrdpPublisher<String> pub = template
 *       .publisher(TrdpSerializer.string())
 *       .comId(1000)
 *       .dest("239.192.0.0")
 *       .intervalMs(100)
 *       .register();
 *
 *   pub.send("hello");       // queued for next cycle
 *   pub.sendImmediate("hi"); // sent at once
 *   pub.close();             // stop publishing
 * }</pre>
 *
 * <p>Implements {@link AutoCloseable} for use in try-with-resources.
 *
 * <p><b>Thread safety:</b> not thread-safe — use from the same thread as the session.
 *
 * @param <T> application value type
 */
public final class TrdpPublisher<T> implements AutoCloseable {

    private final TrdpSession          session;
    private final Pointer              handle;
    private final TrdpSerializer<T>    serializer;
    private       boolean              closed;

    /** Package-private: created exclusively by {@link TrdpTemplate.PublisherBuilder}. */
    TrdpPublisher(TrdpSession session, Pointer handle, TrdpSerializer<T> serializer) {
        this.session    = session;
        this.handle     = handle;
        this.serializer = serializer;
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    /**
     * Update the publisher payload. The new value is serialized and handed to the
     * TRDP stack; it will be transmitted on the next scheduled cycle.
     *
     * @param value value to publish; must not be {@code null}
     * @throws IllegalStateException if this publisher has already been closed
     */
    public void send(T value) {
        checkOpen();
        session.put(handle, serializer.serialize(value));
    }

    /**
     * Update the payload <em>and</em> trigger an immediate out-of-cycle transmission.
     *
     * @param value value to publish; must not be {@code null}
     * @throws IllegalStateException if this publisher has already been closed
     */
    public void sendImmediate(T value) {
        checkOpen();
        session.putImmediate(handle, serializer.serialize(value));
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Stop publishing and release the underlying TRDP handle.
     * Calling {@code close()} more than once is safe (idempotent).
     */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            session.unpublish(handle);
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void checkOpen() {
        if (closed) throw new IllegalStateException("TrdpPublisher is already closed");
    }
}
