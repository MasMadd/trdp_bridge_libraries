package io.trdp.template;

import com.sun.jna.Pointer;
import io.trdp.TrdpSession;

/**
 * Opaque handle for an MD listener registered via {@link TrdpMdTemplate#addListener}.
 *
 * <p>Implements {@link AutoCloseable}: closing the handle removes the listener from the
 * TRDP stack.
 *
 * <pre>{@code
 * try (MdListenerHandle h = mdTemplate.addListener(3000,
 *         TrdpDeserializer.string(),
 *         (info, msg) -> System.out.println("received: " + msg))) {
 *     // listener is active
 * }
 * // listener removed automatically
 * }</pre>
 */
public final class MdListenerHandle implements AutoCloseable {

    private final TrdpSession session;
    private final Pointer     handle;
    private       boolean     closed;

    /** Package-private: created exclusively by {@link TrdpMdTemplate}. */
    MdListenerHandle(TrdpSession session, Pointer handle) {
        this.session = session;
        this.handle  = handle;
    }

    /**
     * Remove this listener from the TRDP stack and release resources.
     * Calling {@code close()} more than once is safe (idempotent).
     */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            session.mdDelListener(handle);
        }
    }
}
