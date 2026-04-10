package io.trdp.integration;

import io.trdp.PdInfo;
import io.trdp.template.*;
import org.junit.jupiter.api.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests for the TRDP PD (Process Data) template API.
 *
 * <p><b>Requirements:</b>
 * <ul>
 *   <li>{@code libtrdp.so} (Linux) or {@code trdp.dll} (Windows) must be available.</li>
 *   <li>The publisher and subscriber both use {@code 127.0.0.1} (loopback) so no
 *       physical network adapter or multicast routing is required.</li>
 * </ul>
 *
 * <h2>Running</h2>
 * <pre>
 *   # Build the shared library first (see README), then:
 *   mvn verify -Pintegration-test -Dtrdp.native.lib=/path/to/dir/containing/libtrdp.so
 * </pre>
 *
 * <p>If the native library is not found the tests are <em>skipped</em> (not failed)
 * via JUnit 5 {@link Assumptions}.
 */
@Tag("integration")
@DisplayName("TRDP PD loopback integration tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TrdpPdLoopbackIT {

    // ComIDs chosen to be unlikely to conflict with real deployments
    private static final int COM_ID_PUB = 55_100;
    private static final int COM_ID_SUB = 55_100;   // same ComID: pub sends, sub receives

    private static final String LOOPBACK = "127.0.0.1";

    // ── Setup / teardown ──────────────────────────────────────────────────────

    /**
     * Skip all tests in this class if the native library cannot be loaded.
     * The property {@code trdp.native.lib} is set by the Failsafe plugin via the
     * Maven profile (see {@code pom.xml}).
     */
    @BeforeAll
    static void checkNativeLibAvailable() {
        String libPath = System.getProperty("trdp.native.lib", "");
        boolean available = isNativeLibAvailable();
        assumeTrue(available,
                "Skipping integration tests: libtrdp not found. " +
                "Run with -Pintegration-test -Dtrdp.native.lib=/path/to/libdir");
    }

    private static boolean isNativeLibAvailable() {
        try {
            // Attempt to load — if it succeeds, the library is present
            com.sun.jna.Native.load("trdp", io.trdp.TrdpLibrary.class);
            return true;
        } catch (UnsatisfiedLinkError | Exception e) {
            return false;
        }
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * Smoke test: open and immediately close a session without any pub/sub.
     * Verifies that the native library initialises and tears down cleanly.
     */
    @Test
    @Order(1)
    @DisplayName("session opens and closes without error")
    void sessionLifecycle() {
        try (TrdpTemplate t = TrdpTemplate.builder().ownIp(LOOPBACK).build()) {
            assertThat(t.getSession()).isNotNull();
        }
    }

    /**
     * Publish a string value and verify that the same session can read it
     * back via a subscriber on the same loopback address.
     *
     * <p>TRDP PD is cyclic: the publisher transmits every {@code intervalUs}
     * microseconds.  After a short wait we poll the subscriber and expect at
     * least one packet to have arrived.
     */
    @Test
    @Order(2)
    @DisplayName("published value is received by subscriber on loopback")
    void publishThenReceive() throws InterruptedException {
        try (TrdpTemplate t = TrdpTemplate.builder()
                .ownIp(LOOPBACK)
                .pollPeriodMs(5)
                .build()) {

            TrdpPublisher<String> pub = t.publisher(TrdpSerializer.string())
                    .comId(COM_ID_PUB)
                    .dest(LOOPBACK)
                    .intervalMs(20)          // 20 ms cycle
                    .initialValue("hello-trdp")
                    .register();

            TrdpSubscriber<String> sub = t.subscriber(TrdpDeserializer.string())
                    .comId(COM_ID_SUB)
                    .src(LOOPBACK)
                    .timeoutMs(500)
                    .keepLastOnTimeout()
                    .register();

            t.start();

            // Give the stack time to send at least one cycle
            Thread.sleep(200);

            Optional<Map.Entry<PdInfo, String>> result = sub.receive();
            assertThat(result).as("subscriber should have received at least one packet").isPresent();
            assertThat(result.get().getValue()).isEqualTo("hello-trdp");
            assertThat(result.get().getKey().srcIp).isEqualTo(LOOPBACK);
            assertThat(result.get().getKey().comId).isEqualTo(COM_ID_SUB);
        }
    }

    /**
     * Verify that {@link TrdpPublisher#send(Object)} updates the payload and
     * the subscriber picks up the new value within a reasonable time.
     */
    @Test
    @Order(3)
    @DisplayName("put() updates the payload received by subscriber")
    void putUpdatesPayload() throws InterruptedException {
        try (TrdpTemplate t = TrdpTemplate.builder()
                .ownIp(LOOPBACK)
                .pollPeriodMs(5)
                .build()) {

            TrdpPublisher<String> pub = t.publisher(TrdpSerializer.string())
                    .comId(COM_ID_PUB)
                    .dest(LOOPBACK)
                    .intervalMs(20)
                    .register();

            TrdpSubscriber<String> sub = t.subscriber(TrdpDeserializer.string())
                    .comId(COM_ID_SUB)
                    .src(LOOPBACK)
                    .timeoutMs(500)
                    .register();

            t.start();

            // Publish initial value, let one cycle pass
            pub.send("first");
            Thread.sleep(100);

            // Update and wait for propagation
            pub.send("updated");
            Thread.sleep(100);

            assertThat(sub.poll()).isEqualTo("updated");
        }
    }

    /**
     * Verify that the push-style {@code onMessage} callback is invoked
     * when packets arrive and that it receives the correct deserialized value.
     */
    @Test
    @Order(4)
    @DisplayName("onMessage callback is invoked with deserialized payload")
    void onMessageCallback() throws InterruptedException {
        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);  // wait for ≥ 3 packets

        try (TrdpTemplate t = TrdpTemplate.builder()
                .ownIp(LOOPBACK)
                .pollPeriodMs(5)
                .build()) {

            TrdpPublisher<String> pub = t.publisher(TrdpSerializer.string())
                    .comId(COM_ID_PUB)
                    .dest(LOOPBACK)
                    .intervalMs(30)
                    .initialValue("cb-test")
                    .register();

            t.subscriber(TrdpDeserializer.string())
                    .comId(COM_ID_SUB)
                    .src(LOOPBACK)
                    .timeoutMs(500)
                    .onMessage((info, msg) -> {
                        received.add(msg);
                        latch.countDown();
                    })
                    .register();

            t.start();

            boolean triggered = latch.await(2, TimeUnit.SECONDS);
            assertThat(triggered).as("callback should have been invoked at least 3 times").isTrue();
            assertThat(received).allSatisfy(msg -> assertThat(msg).isEqualTo("cb-test"));
        }
    }
}
