package io.trdp;

/**
 * Thrown when a TRDP API call returns a non-zero {@code TRDP_ERR_T} code.
 */
public class TrdpException extends RuntimeException {

    private final int code;

    public TrdpException(int code, String function) {
        super(buildMessage(code, function));
        this.code = code;
    }

    /** The raw {@code TRDP_ERR_T} value. */
    public int getCode() { return code; }

    /** The {@link TrdpErrCode} enum constant, or {@code null} if the code is unknown. */
    public TrdpErrCode getErrCode() {
        return TrdpErrCode.fromCode(code);
    }

    private static String buildMessage(int code, String function) {
        TrdpErrCode ec = TrdpErrCode.fromCode(code);
        String name = (ec != null) ? ec.name() : "UNKNOWN";
        return function + " failed: " + name + " (" + code + ")";
    }
}
