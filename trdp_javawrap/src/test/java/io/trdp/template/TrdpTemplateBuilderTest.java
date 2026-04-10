package io.trdp.template;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import io.trdp.PdInfo;
import io.trdp.TrdpSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TrdpTemplate} builder API and wiring.
 *
 * <p>All calls to the native TRDP stack go through a mocked {@link TrdpSession}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TrdpTemplate builder")
class TrdpTemplateBuilderTest {

    @Mock
    TrdpSession session;

    // ── TrdpTemplate.Builder validation ──────────────────────────────────────

    @Nested
    @DisplayName("TrdpTemplate.Builder")
    class BuilderValidation {

        @Test
        @DisplayName("throws NullPointerException when ownIp is not set")
        void requiresOwnIp() {
            assertThatNullPointerException()
                    .isThrownBy(() -> TrdpTemplate.builder().build())
                    .withMessageContaining("ownIp");
        }
    }

    // ── TrdpTemplate wrapping an existing session ─────────────────────────────

    @Nested
    @DisplayName("TrdpTemplate(TrdpSession)")
    class WrapExistingSession {

        @Test
        @DisplayName("getSession() returns the wrapped session")
        void getSession() {
            TrdpTemplate t = new TrdpTemplate(session);
            assertThat(t.getSession()).isSameAs(session);
        }

        @Test
        @DisplayName("close() does NOT close the wrapped session")
        void closeDoesNotCloseExternalSession() {
            TrdpTemplate t = new TrdpTemplate(session);
            t.close();
            verify(session, never()).close();
        }

        @Test
        @DisplayName("start() delegates to session.startProcessingThread")
        void startDelegatesToSession() {
            TrdpTemplate t = new TrdpTemplate(session, 15);
            t.start();
            verify(session).startProcessingThread(15);
        }

        @Test
        @DisplayName("stop() delegates to session.stopProcessingThread")
        void stopDelegatesToSession() {
            TrdpTemplate t = new TrdpTemplate(session);
            t.stop();
            verify(session).stopProcessingThread();
        }
    }

    // ── PublisherBuilder ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("PublisherBuilder")
    class PublisherBuilderTests {

        @Test
        @DisplayName("throws NullPointerException when dest() is missing")
        void requiresDest() {
            TrdpTemplate t = new TrdpTemplate(session);
            assertThatNullPointerException()
                    .isThrownBy(() -> t.publisher(TrdpSerializer.string())
                            .comId(1000).intervalMs(100).register())
                    .withMessageContaining("dest");
        }

        @Test
        @DisplayName("throws IllegalStateException when interval is not set (remains 0)")
        void requiresInterval() {
            TrdpTemplate t = new TrdpTemplate(session);
            assertThatIllegalStateException()
                    .isThrownBy(() -> t.publisher(TrdpSerializer.string())
                            .comId(1000).dest("239.0.0.1").register());
        }

        @Test
        @DisplayName("register() calls session.publish with correct arguments")
        void registersPublisher() {
            Pointer handle = new Memory(8);
            when(session.publish(
                    eq(1000), eq("239.192.0.0"), eq(50_000),
                    any(byte[].class), eq("0.0.0.0"),
                    eq(0), eq(0), eq(0), eq(0),
                    eq(TrdpSession.FLAGS_DEFAULT),
                    isNull()))
                    .thenReturn(handle);

            TrdpTemplate t = new TrdpTemplate(session);
            TrdpPublisher<String> pub = t.publisher(TrdpSerializer.string())
                    .comId(1000).dest("239.192.0.0").intervalMs(50)
                    .register();

            assertThat(pub).isNotNull();
        }

        @Test
        @DisplayName("intervalMs() is converted to intervalUs correctly")
        void intervalMsConversion() {
            Pointer handle = new Memory(8);
            when(session.publish(
                    anyInt(), anyString(), eq(100_000),   // 100 ms → 100 000 µs
                    any(byte[].class), anyString(),
                    anyInt(), anyInt(), anyInt(), anyInt(),
                    anyByte(), isNull()))
                    .thenReturn(handle);

            TrdpTemplate t = new TrdpTemplate(session);
            t.publisher(TrdpSerializer.bytes())
                    .comId(1000).dest("239.0.0.1").intervalMs(100)
                    .register();

            verify(session).publish(anyInt(), anyString(), eq(100_000),
                    any(), anyString(), anyInt(), anyInt(), anyInt(), anyInt(),
                    anyByte(), isNull());
        }

        @Test
        @DisplayName("initialValue() is serialized and passed to session.publish")
        void initialValueSerialized() {
            Pointer handle = new Memory(8);
            byte[] expectedBytes = "init".getBytes(StandardCharsets.UTF_8);

            when(session.publish(
                    anyInt(), anyString(), anyInt(),
                    eq(expectedBytes), anyString(),
                    anyInt(), anyInt(), anyInt(), anyInt(),
                    anyByte(), isNull()))
                    .thenReturn(handle);

            TrdpTemplate t = new TrdpTemplate(session);
            t.publisher(TrdpSerializer.string())
                    .comId(1000).dest("239.0.0.1").intervalMs(100)
                    .initialValue("init")
                    .register();

            verify(session).publish(anyInt(), anyString(), anyInt(),
                    eq(expectedBytes), anyString(),
                    anyInt(), anyInt(), anyInt(), anyInt(),
                    anyByte(), isNull());
        }
    }

    // ── SubscriberBuilder ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("SubscriberBuilder")
    class SubscriberBuilderTests {

        @Test
        @DisplayName("register() calls session.subscribe with correct arguments")
        void registersSubscriber() {
            Pointer handle = new Memory(8);
            when(session.subscribe(
                    eq(2000), eq("10.0.1.2"), eq(500_000),
                    eq("0.0.0.0"), eq("0.0.0.0"),
                    eq(0), eq(0), eq(0),
                    eq(TrdpSession.FLAGS_DEFAULT), eq(TrdpSession.TO_DEFAULT),
                    isNull()))
                    .thenReturn(handle);

            TrdpTemplate t = new TrdpTemplate(session);
            TrdpSubscriber<String> sub = t.subscriber(TrdpDeserializer.string())
                    .comId(2000).src("10.0.1.2").timeoutMs(500)
                    .register();

            assertThat(sub).isNotNull();
        }

        @Test
        @DisplayName("timeoutMs() is converted to timeoutUs correctly")
        void timeoutMsConversion() {
            Pointer handle = new Memory(8);
            when(session.subscribe(
                    anyInt(), anyString(), eq(200_000),   // 200 ms → 200 000 µs
                    anyString(), anyString(),
                    anyInt(), anyInt(), anyInt(),
                    anyInt(), anyInt(), isNull()))
                    .thenReturn(handle);

            TrdpTemplate t = new TrdpTemplate(session);
            t.subscriber(TrdpDeserializer.bytes())
                    .comId(2000).timeoutMs(200).register();

            verify(session).subscribe(anyInt(), anyString(), eq(200_000),
                    anyString(), anyString(), anyInt(), anyInt(), anyInt(),
                    anyInt(), anyInt(), isNull());
        }

        @Test
        @DisplayName("keepLastOnTimeout() sets TO_KEEP_LAST_VALUE flag")
        void keepLastOnTimeout() {
            Pointer handle = new Memory(8);
            when(session.subscribe(
                    anyInt(), anyString(), anyInt(),
                    anyString(), anyString(),
                    anyInt(), anyInt(), anyInt(),
                    anyInt(), eq(TrdpSession.TO_KEEP_LAST_VALUE), isNull()))
                    .thenReturn(handle);

            TrdpTemplate t = new TrdpTemplate(session);
            t.subscriber(TrdpDeserializer.bytes())
                    .comId(2000).keepLastOnTimeout().register();

            verify(session).subscribe(anyInt(), anyString(), anyInt(),
                    anyString(), anyString(), anyInt(), anyInt(), anyInt(),
                    anyInt(), eq(TrdpSession.TO_KEEP_LAST_VALUE), isNull());
        }

        @Test
        @DisplayName("onMessage callback wraps the raw byte[] callback with deserialization")
        @SuppressWarnings("unchecked")
        void onMessageCallback() {
            Pointer handle = new Memory(8);
            AtomicReference<BiConsumer<PdInfo, byte[]>> capturedCb = new AtomicReference<>();

            when(session.subscribe(
                    anyInt(), anyString(), anyInt(),
                    anyString(), anyString(),
                    anyInt(), anyInt(), anyInt(),
                    anyInt(), anyInt(), notNull()))
                    .thenAnswer(inv -> {
                        capturedCb.set(inv.getArgument(10));
                        return handle;
                    });

            AtomicReference<String> received = new AtomicReference<>();
            TrdpTemplate t = new TrdpTemplate(session);
            t.subscriber(TrdpDeserializer.string())
                    .comId(2000)
                    .onMessage((info, msg) -> received.set(msg))
                    .register();

            // Simulate TRDP calling back with raw bytes
            PdInfo info = mock(PdInfo.class);
            capturedCb.get().accept(info, "from-trdp".getBytes(StandardCharsets.UTF_8));

            assertThat(received.get()).isEqualTo("from-trdp");
        }
    }
}
