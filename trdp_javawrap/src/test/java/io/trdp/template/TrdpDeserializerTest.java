package io.trdp.template;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TrdpDeserializer")
class TrdpDeserializerTest {

    // ── bytes() ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("bytes()")
    class Bytes {

        @Test
        @DisplayName("returns the same array instance (identity)")
        void identity() {
            byte[] data = {4, 5, 6};
            assertThat(TrdpDeserializer.bytes().deserialize(data)).isSameAs(data);
        }

        @Test
        @DisplayName("passes null through without throwing")
        void nullPassthrough() {
            assertThat(TrdpDeserializer.bytes().deserialize(null)).isNull();
        }

        @Test
        @DisplayName("returns empty array unchanged")
        void emptyArray() {
            byte[] empty = new byte[0];
            assertThat(TrdpDeserializer.bytes().deserialize(empty)).isSameAs(empty);
        }
    }

    // ── string() — UTF-8 ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("string()")
    class StringUtf8 {

        @Test
        @DisplayName("decodes UTF-8 bytes to String")
        void asciiDecode() {
            byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
            assertThat(TrdpDeserializer.string().deserialize(data)).isEqualTo("hello");
        }

        @Test
        @DisplayName("decodes multi-byte UTF-8 characters")
        void multiByteDecode() {
            String original = "caf\u00e9";
            byte[] data = original.getBytes(StandardCharsets.UTF_8);
            assertThat(TrdpDeserializer.string().deserialize(data)).isEqualTo(original);
        }

        @Test
        @DisplayName("returns empty string for null data")
        void nullReturnsEmptyString() {
            assertThat(TrdpDeserializer.string().deserialize(null)).isEqualTo("");
        }

        @Test
        @DisplayName("returns empty string for empty byte array")
        void emptyBytesGivesEmptyString() {
            assertThat(TrdpDeserializer.string().deserialize(new byte[0])).isEqualTo("");
        }
    }

    // ── string(Charset) ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("string(Charset)")
    class StringWithCharset {

        @Test
        @DisplayName("decodes using the specified charset")
        void iso88591() {
            String original = "hello";
            byte[] data = original.getBytes(StandardCharsets.ISO_8859_1);
            assertThat(TrdpDeserializer.string(StandardCharsets.ISO_8859_1).deserialize(data))
                    .isEqualTo(original);
        }
    }

    // ── round-trip ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("round-trip with TrdpSerializer")
    class RoundTrip {

        @Test
        @DisplayName("string round-trip preserves content")
        void stringRoundTrip() {
            String original = "trdp-protocol";
            byte[] serialized   = TrdpSerializer.string().serialize(original);
            String deserialized = TrdpDeserializer.string().deserialize(serialized);
            assertThat(deserialized).isEqualTo(original);
        }

        @Test
        @DisplayName("bytes round-trip is identity")
        void bytesRoundTrip() {
            byte[] original     = {0x01, (byte) 0xFF, 0x42};
            byte[] serialized   = TrdpSerializer.bytes().serialize(original);
            byte[] deserialized = TrdpDeserializer.bytes().deserialize(serialized);
            assertThat(deserialized).containsExactly(original);
        }
    }
}
