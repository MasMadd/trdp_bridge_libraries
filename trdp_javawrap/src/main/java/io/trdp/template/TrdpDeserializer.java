package io.trdp.template;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Converts a raw {@code byte[]} payload received from TRDP into a value of type {@code T}.
 *
 * <p>Built-in factories:
 * <pre>{@code
 *   TrdpDeserializer<byte[]>  raw  = TrdpDeserializer.bytes();
 *   TrdpDeserializer<String>  utf8 = TrdpDeserializer.string();
 *   TrdpDeserializer<String>  lat1 = TrdpDeserializer.string(StandardCharsets.ISO_8859_1);
 * }</pre>
 *
 * <p>Custom lambda example:
 * <pre>{@code
 *   TrdpDeserializer<MyDto> json = data -> objectMapper.readValue(data, MyDto.class);
 * }</pre>
 *
 * @param <T> the application-level type to produce
 */
@FunctionalInterface
public interface TrdpDeserializer<T> {

    /**
     * Deserialize {@code data} into a value of type {@code T}.
     *
     * @param data raw bytes from the wire; may be empty but never {@code null}
     * @return deserialized value; may be {@code null} if the implementation chooses
     */
    T deserialize(byte[] data);

    // ── Built-in factories ────────────────────────────────────────────────────

    /** Identity deserializer — returns the byte array as-is. */
    static TrdpDeserializer<byte[]> bytes() {
        return data -> data;
    }

    /** UTF-8 string deserializer. */
    static TrdpDeserializer<String> string() {
        return string(StandardCharsets.UTF_8);
    }

    /** String deserializer using the given charset. */
    static TrdpDeserializer<String> string(Charset charset) {
        return data -> (data != null) ? new String(data, charset) : "";
    }
}
