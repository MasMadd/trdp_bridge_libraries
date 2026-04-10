package io.trdp.template;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Converts a value of type {@code T} to a {@code byte[]} payload for transmission.
 *
 * <p>Built-in factories:
 * <pre>{@code
 *   TrdpSerializer<byte[]>  raw  = TrdpSerializer.bytes();
 *   TrdpSerializer<String>  utf8 = TrdpSerializer.string();
 *   TrdpSerializer<String>  lat1 = TrdpSerializer.string(StandardCharsets.ISO_8859_1);
 * }</pre>
 *
 * <p>Custom lambda example:
 * <pre>{@code
 *   TrdpSerializer<MyDto> json = value -> objectMapper.writeValueAsBytes(value);
 * }</pre>
 *
 * @param <T> the application-level type to serialize
 */
@FunctionalInterface
public interface TrdpSerializer<T> {

    /**
     * Serialize {@code value} to bytes.
     *
     * @param value the value to serialize; may be {@code null} — implementations
     *              should return an empty array rather than throw in that case
     * @return serialized byte array, never {@code null}
     */
    byte[] serialize(T value);

    // ── Built-in factories ────────────────────────────────────────────────────

    /** Identity serializer for raw byte arrays. */
    static TrdpSerializer<byte[]> bytes() {
        return value -> (value != null) ? value : new byte[0];
    }

    /** UTF-8 string serializer. */
    static TrdpSerializer<String> string() {
        return string(StandardCharsets.UTF_8);
    }

    /** String serializer using the given charset. */
    static TrdpSerializer<String> string(Charset charset) {
        return value -> (value != null) ? value.getBytes(charset) : new byte[0];
    }
}
