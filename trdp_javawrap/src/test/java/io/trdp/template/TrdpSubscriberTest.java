package io.trdp.template;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import io.trdp.PdInfo;
import io.trdp.TrdpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TrdpSubscriber}.
 *
 * <p>{@link TrdpSession} and {@link PdInfo} are mocked — no native library required.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TrdpSubscriber")
class TrdpSubscriberTest {

    @Mock
    TrdpSession session;

    @Mock
    PdInfo pdInfo;

    Pointer handle;

    @BeforeEach
    void setUp() {
        handle = new Memory(8);
    }

    // ── poll() ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("poll()")
    class Poll {

        @Test
        @DisplayName("returns null when session reports no data (NODATA_ERR)")
        void returnsNullWhenNoData() {
            when(session.get(handle)).thenReturn(null);
            TrdpSubscriber<String> sub = new TrdpSubscriber<>(session, handle, TrdpDeserializer.string());

            assertThat(sub.poll()).isNull();
        }

        @Test
        @DisplayName("deserializes payload and returns value")
        void deserializesPayload() {
            byte[] payload = "trdp-message".getBytes(StandardCharsets.UTF_8);
            when(session.get(handle)).thenReturn(new Object[]{pdInfo, payload});
            TrdpSubscriber<String> sub = new TrdpSubscriber<>(session, handle, TrdpDeserializer.string());

            assertThat(sub.poll()).isEqualTo("trdp-message");
        }

        @Test
        @DisplayName("uses the provided custom deserializer")
        void customDeserializer() {
            TrdpDeserializer<Integer> intDes = data -> data[0] & 0xFF;
            when(session.get(handle)).thenReturn(new Object[]{pdInfo, new byte[]{(byte) 99}});
            TrdpSubscriber<Integer> sub = new TrdpSubscriber<>(session, handle, intDes);

            assertThat(sub.poll()).isEqualTo(99);
        }

        @Test
        @DisplayName("throws IllegalStateException after close()")
        void throwsAfterClose() {
            TrdpSubscriber<String> sub = new TrdpSubscriber<>(session, handle, TrdpDeserializer.string());
            sub.close();

            assertThatIllegalStateException().isThrownBy(sub::poll)
                    .withMessageContaining("closed");
        }
    }

    // ── receive() ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("receive()")
    class Receive {

        @Test
        @DisplayName("returns empty Optional when no data available")
        void returnsEmptyWhenNoData() {
            when(session.get(handle)).thenReturn(null);
            TrdpSubscriber<String> sub = new TrdpSubscriber<>(session, handle, TrdpDeserializer.string());

            assertThat(sub.receive()).isEmpty();
        }

        @Test
        @DisplayName("returns Optional containing (PdInfo, deserializedValue)")
        void returnsEntryWithMetadata() {
            byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
            when(session.get(handle)).thenReturn(new Object[]{pdInfo, payload});
            TrdpSubscriber<String> sub = new TrdpSubscriber<>(session, handle, TrdpDeserializer.string());

            Optional<Map.Entry<PdInfo, String>> result = sub.receive();
            assertThat(result).isPresent();
            assertThat(result.get().getKey()).isSameAs(pdInfo);
            assertThat(result.get().getValue()).isEqualTo("hello");
        }

        @Test
        @DisplayName("throws IllegalStateException after close()")
        void throwsAfterClose() {
            TrdpSubscriber<String> sub = new TrdpSubscriber<>(session, handle, TrdpDeserializer.string());
            sub.close();

            assertThatIllegalStateException().isThrownBy(sub::receive)
                    .withMessageContaining("closed");
        }
    }

    // ── close() ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("close()")
    class Close {

        @Test
        @DisplayName("calls session.unsubscribe with the handle")
        void callsUnsubscribe() {
            TrdpSubscriber<String> sub = new TrdpSubscriber<>(session, handle, TrdpDeserializer.string());
            sub.close();

            verify(session).unsubscribe(handle);
        }

        @Test
        @DisplayName("is idempotent — unsubscribe called exactly once even if closed twice")
        void idempotent() {
            TrdpSubscriber<String> sub = new TrdpSubscriber<>(session, handle, TrdpDeserializer.string());
            sub.close();
            sub.close();

            verify(session, times(1)).unsubscribe(handle);
        }

        @Test
        @DisplayName("can be used in try-with-resources")
        void tryWithResources() {
            try (TrdpSubscriber<byte[]> sub =
                         new TrdpSubscriber<>(session, handle, TrdpDeserializer.bytes())) {
                assertThat(sub).isNotNull();
            }
            verify(session).unsubscribe(handle);
        }
    }
}
