package io.trdp.template;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import io.trdp.MdInfo;
import io.trdp.TrdpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TrdpMdTemplate}.
 *
 * <p>{@link TrdpSession} is mocked — no native library required.
 * Tests cover:
 * <ul>
 *   <li>notify delegation</li>
 *   <li>asyncRequest future completion (success)</li>
 *   <li>asyncRequest future completion (timeout)</li>
 *   <li>typed asyncRequest deserialization</li>
 *   <li>listener wiring with deserialization</li>
 *   <li>MdListenerHandle AutoCloseable behaviour</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TrdpMdTemplate")
class TrdpMdTemplateTest {

    @Mock
    TrdpSession session;

    TrdpMdTemplate md;

    @BeforeEach
    void setUp() {
        md = new TrdpMdTemplate(session);
    }

    // ── notify() ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("notify()")
    class Notify {

        @Test
        @DisplayName("delegates raw bytes to session.mdNotify with null callback")
        void rawBytes() {
            byte[] data = {1, 2, 3};
            md.notify(3000, "10.0.1.2", data);

            verify(session).mdNotify(eq(3000), eq("10.0.1.2"), eq(data), isNull());
        }

        @Test
        @DisplayName("typed overload serializes the value before delegating")
        void typedOverload() {
            byte[] expected = "ping".getBytes(StandardCharsets.UTF_8);
            md.notify(3000, "10.0.1.2", "ping", TrdpSerializer.string());

            verify(session).mdNotify(eq(3000), eq("10.0.1.2"), eq(expected), isNull());
        }
    }

    // ── asyncRequest() — success path ─────────────────────────────────────────

    @Nested
    @DisplayName("asyncRequest() — success")
    class AsyncRequestSuccess {

        @Test
        @DisplayName("future completes with the reply payload when callback is invoked")
        void completesWithReply() throws Exception {
            byte[] replyData = "pong".getBytes(StandardCharsets.UTF_8);

            // Invoke the callback synchronously inside the mock (simulates immediate reply)
            doAnswer(inv -> {
                @SuppressWarnings("unchecked")
                BiConsumer<MdInfo, byte[]> cb = inv.getArgument(3);
                cb.accept(mock(MdInfo.class), replyData);
                return new byte[16];
            }).when(session).mdRequest(anyInt(), anyString(), any(byte[].class), any());

            byte[] result = md.asyncRequest(3000, "10.0.1.2", "ping".getBytes(), 1_000)
                    .get(2, TimeUnit.SECONDS);

            assertThat(result).isEqualTo(replyData);
        }

        @Test
        @DisplayName("future completes only once — second callback invocation is a no-op")
        void completesOnce() throws Exception {
            doAnswer(inv -> {
                @SuppressWarnings("unchecked")
                BiConsumer<MdInfo, byte[]> cb = inv.getArgument(3);
                cb.accept(mock(MdInfo.class), "first".getBytes());
                cb.accept(mock(MdInfo.class), "second".getBytes()); // should be ignored
                return new byte[16];
            }).when(session).mdRequest(anyInt(), anyString(), any(byte[].class), any());

            byte[] result = md.asyncRequest(3000, "10.0.1.2", new byte[0], 1_000)
                    .get(2, TimeUnit.SECONDS);

            assertThat(new String(result, StandardCharsets.UTF_8)).isEqualTo("first");
        }
    }

    // ── asyncRequest() — timeout path ─────────────────────────────────────────

    @Nested
    @DisplayName("asyncRequest() — timeout")
    class AsyncRequestTimeout {

        @Test
        @DisplayName("future completes exceptionally with TimeoutException when no reply arrives")
        void completesExceptionallyOnTimeout() {
            // mdRequest returns without invoking the callback → timeout fires
            when(session.mdRequest(anyInt(), anyString(), any(byte[].class), any()))
                    .thenReturn(new byte[16]);

            CompletableFuture<byte[]> future =
                    md.asyncRequest(3000, "10.0.1.2", new byte[0], 80 /*ms*/);

            assertThatThrownBy(() -> future.get(3, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(TimeoutException.class)
                    .hasMessageContaining("80 ms");
        }
    }

    // ── asyncRequest() — typed overload ───────────────────────────────────────

    @Nested
    @DisplayName("asyncRequest() — typed")
    class AsyncRequestTyped {

        @Test
        @DisplayName("serializes request, deserializes reply")
        void serializeDeserialize() throws Exception {
            doAnswer(inv -> {
                @SuppressWarnings("unchecked")
                BiConsumer<MdInfo, byte[]> cb = inv.getArgument(3);
                cb.accept(mock(MdInfo.class), "pong".getBytes(StandardCharsets.UTF_8));
                return new byte[16];
            }).when(session).mdRequest(anyInt(), anyString(), any(byte[].class), any());

            String result = md.<String, String>asyncRequest(
                    3000, "10.0.1.2",
                    "ping",
                    TrdpSerializer.string(),
                    TrdpDeserializer.string(),
                    1_000
            ).get(2, TimeUnit.SECONDS);

            assertThat(result).isEqualTo("pong");
            // Verify that the serialized "ping" bytes were passed to the session
            verify(session).mdRequest(
                    eq(3000), eq("10.0.1.2"),
                    eq("ping".getBytes(StandardCharsets.UTF_8)),
                    any());
        }
    }

    // ── reply() ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("reply()")
    class Reply {

        @Test
        @DisplayName("delegates raw bytes to session.mdReply")
        void rawBytes() {
            byte[] sessionId = new byte[16];
            byte[] data = "pong".getBytes();
            md.reply(sessionId, 3001, data);

            verify(session).mdReply(sessionId, 3001, data);
        }

        @Test
        @DisplayName("typed overload serializes the value before delegating")
        void typedOverload() {
            byte[] sessionId = new byte[16];
            md.reply(sessionId, 3001, "pong", TrdpSerializer.string());

            verify(session).mdReply(sessionId, 3001, "pong".getBytes(StandardCharsets.UTF_8));
        }
    }

    // ── addListener() — typed ─────────────────────────────────────────────────

    @Nested
    @DisplayName("addListener()")
    class AddListener {

        @Test
        @DisplayName("typed listener deserializes bytes before invoking callback")
        @SuppressWarnings("unchecked")
        void deserializesBeforeCallback() {
            Pointer lisHandle = new Memory(8);
            AtomicReference<BiConsumer<MdInfo, byte[]>> capturedCb = new AtomicReference<>();

            when(session.mdAddListener(anyInt(), any()))
                    .thenAnswer(inv -> {
                        capturedCb.set(inv.getArgument(1));
                        return lisHandle;
                    });

            AtomicReference<String> received = new AtomicReference<>();
            md.addListener(3000, TrdpDeserializer.string(),
                    (info, msg) -> received.set(msg));

            // Simulate TRDP firing the raw callback
            MdInfo info = mock(MdInfo.class);
            capturedCb.get().accept(info, "decoded-message".getBytes(StandardCharsets.UTF_8));

            assertThat(received.get()).isEqualTo("decoded-message");
        }

        @Test
        @DisplayName("returns an MdListenerHandle that removes the listener on close()")
        void handleRemovesListenerOnClose() {
            Pointer lisHandle = new Memory(8);
            when(session.mdAddListener(anyInt(), any())).thenReturn(lisHandle);

            MdListenerHandle handle = md.addListener(3000,
                    (BiConsumer<MdInfo, byte[]>) (info, data) -> {});
            handle.close();

            verify(session).mdDelListener(lisHandle);
        }
    }

    // ── MdListenerHandle ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("MdListenerHandle")
    class ListenerHandle {

        @Test
        @DisplayName("close() is idempotent — mdDelListener called exactly once")
        void idempotentClose() {
            Pointer lisHandle = new Memory(8);
            when(session.mdAddListener(anyInt(), any())).thenReturn(lisHandle);

            MdListenerHandle handle = md.addListener(3000,
                    (BiConsumer<MdInfo, byte[]>) (info, data) -> {});
            handle.close();
            handle.close();  // second close must be a no-op

            verify(session, times(1)).mdDelListener(lisHandle);
        }

        @Test
        @DisplayName("can be used in try-with-resources")
        void tryWithResources() {
            Pointer lisHandle = new Memory(8);
            when(session.mdAddListener(anyInt(), any())).thenReturn(lisHandle);

            try (MdListenerHandle h = md.addListener(3000,
                    (BiConsumer<MdInfo, byte[]>) (info, data) -> {})) {
                assertThat(h).isNotNull();
            }
            verify(session).mdDelListener(lisHandle);
        }
    }
}
