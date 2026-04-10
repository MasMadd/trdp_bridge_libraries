package io.trdp.template;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import io.trdp.TrdpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TrdpPublisher}.
 *
 * <p>{@link TrdpSession} is mocked — no native library required.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TrdpPublisher")
class TrdpPublisherTest {

    @Mock
    TrdpSession session;

    /** Reuse a fixed Memory block as a stand-in for the TRDP publisher handle. */
    Pointer handle;

    @BeforeEach
    void setUp() {
        handle = new Memory(8);
    }

    // ── send() ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("send()")
    class Send {

        @Test
        @DisplayName("serializes the value and calls session.put with the handle")
        void delegatesToSessionPut() {
            TrdpPublisher<String> pub = new TrdpPublisher<>(session, handle, TrdpSerializer.string());
            pub.send("hello");

            verify(session).put(handle, "hello".getBytes(StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("uses the provided serializer")
        void customSerializer() {
            TrdpSerializer<Integer> intSer = v -> new byte[]{v.byteValue()};
            TrdpPublisher<Integer> pub = new TrdpPublisher<>(session, handle, intSer);
            pub.send(42);

            verify(session).put(handle, new byte[]{42});
        }

        @Test
        @DisplayName("throws IllegalStateException after close()")
        void throwsAfterClose() {
            TrdpPublisher<String> pub = new TrdpPublisher<>(session, handle, TrdpSerializer.string());
            pub.close();

            assertThatIllegalStateException().isThrownBy(() -> pub.send("x"))
                    .withMessageContaining("closed");
        }
    }

    // ── sendImmediate() ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("sendImmediate()")
    class SendImmediate {

        @Test
        @DisplayName("serializes the value and calls session.putImmediate")
        void delegatesToSessionPutImmediate() {
            TrdpPublisher<String> pub = new TrdpPublisher<>(session, handle, TrdpSerializer.string());
            pub.sendImmediate("urgent");

            verify(session).putImmediate(handle, "urgent".getBytes(StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("throws IllegalStateException after close()")
        void throwsAfterClose() {
            TrdpPublisher<String> pub = new TrdpPublisher<>(session, handle, TrdpSerializer.string());
            pub.close();

            assertThatIllegalStateException().isThrownBy(() -> pub.sendImmediate("x"))
                    .withMessageContaining("closed");
        }
    }

    // ── close() ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("close()")
    class Close {

        @Test
        @DisplayName("calls session.unpublish with the handle")
        void callsUnpublish() {
            TrdpPublisher<String> pub = new TrdpPublisher<>(session, handle, TrdpSerializer.string());
            pub.close();

            verify(session).unpublish(handle);
        }

        @Test
        @DisplayName("is idempotent — unpublish called exactly once even if closed twice")
        void idempotent() {
            TrdpPublisher<String> pub = new TrdpPublisher<>(session, handle, TrdpSerializer.string());
            pub.close();
            pub.close();

            verify(session, times(1)).unpublish(handle);
        }

        @Test
        @DisplayName("can be used in try-with-resources")
        void tryWithResources() {
            try (TrdpPublisher<byte[]> pub = new TrdpPublisher<>(session, handle, TrdpSerializer.bytes())) {
                assertThat(pub).isNotNull();
            }
            verify(session).unpublish(handle);
        }
    }
}
