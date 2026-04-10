package io.trdp.template;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TrdpSerializer")
class TrdpSerializerTest {

    // ── bytes() ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("bytes()")
    class Bytes {

        @Test
        @DisplayName("returns the same array instance (identity)")
        void identity() {
            byte[] data = {1, 2, 3};
            assertThat(TrdpSerializer.bytes().serialize(data)).isSameAs(data);
        }

        @Test
        @DisplayName("returns empty array for null")
        void nullReturnsEmpty() {
            assertThat(TrdpSerializer.bytes().serialize(null)).isEmpty();
        }

        @Test
        @DisplayName("returns empty array unchanged")
        void emptyArray() {
            byte[] empty = new byte[0];
            assertThat(TrdpSerializer.bytes().serialize(empty)).isSameAs(empty);
        }
    }

    // ── string() — UTF-8 ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("string()")
    class StringUtf8 {

        @Test
        @DisplayName("encodes ASCII string to UTF-8 bytes")
        void asciiString() {
            assertThat(TrdpSerializer.string().serialize("hello"))
                    .isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("encodes multi-byte UTF-8 characters correctly")
        void multiByte() {
            String s = "caf\u00e9";  // café
            assertThat(TrdpSerializer.string().serialize(s))
                    .isEqualTo(s.getBytes(StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("returns empty array for null")
        void nullReturnsEmpty() {
            assertThat(TrdpSerializer.string().serialize(null)).isEmpty();
        }

        @Test
        @DisplayName("returns empty array for empty string")
        void emptyString() {
            assertThat(TrdpSerializer.string().serialize("")).isEmpty();
        }
    }

    // ── string(Charset) ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("string(Charset)")
    class StringWithCharset {

        @Test
        @DisplayName("encodes using the specified charset")
        void iso88591() {
            String s = "hello";
            assertThat(TrdpSerializer.string(StandardCharsets.ISO_8859_1).serialize(s))
                    .isEqualTo(s.getBytes(StandardCharsets.ISO_8859_1));
        }
    }

    // ── lambda / custom ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("custom lambda")
    class Custom {

        @Test
        @DisplayName("lambda serializer is called with the correct value")
        void lambdaIsInvoked() {
            TrdpSerializer<Integer> intSerializer =
                    value -> ByteBuffer.allocate(4).putInt(value).array();

            byte[] result = intSerializer.serialize(0x01020304);
            assertThat(result).containsExactly(0x01, 0x02, 0x03, 0x04);
        }

        @Test
        @DisplayName("round-trip: serialize then deserialize gives back original value")
        void roundTrip() {
            TrdpSerializer<String>   ser = TrdpSerializer.string();
            TrdpDeserializer<String> des = TrdpDeserializer.string();
            assertThat(des.deserialize(ser.serialize("round-trip"))).isEqualTo("round-trip");
        }
    }
}
