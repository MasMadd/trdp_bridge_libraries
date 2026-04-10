package io.trdp;

/**
 * All {@code TRDP_ERR_T} error codes as defined in {@code trdp_types.h}.
 */
public enum TrdpErrCode {

    NO_ERR              (  0),
    PARAM_ERR           ( -1),
    INIT_ERR            ( -2),
    NOINIT_ERR          ( -3),
    TIMEOUT_ERR         ( -4),
    NODATA_ERR          ( -5),
    SOCK_ERR            ( -6),
    IO_ERR              ( -7),
    MEM_ERR             ( -8),
    SEMA_ERR            ( -9),
    QUEUE_ERR           (-10),
    QUEUE_FULL_ERR      (-11),
    MUTEX_ERR           (-12),
    THREAD_ERR          (-13),
    BLOCK_ERR           (-14),
    INTEGRATION_ERR     (-15),
    NOCONN_ERR          (-16),
    NOSESSION_ERR       (-30),
    SESSION_ABORT_ERR   (-31),
    NOSUB_ERR           (-32),
    NOPUB_ERR           (-33),
    NOLIST_ERR          (-34),
    CRC_ERR             (-35),
    WIRE_ERR            (-36),
    TOPO_ERR            (-37),
    COMID_ERR           (-38),
    STATE_ERR           (-39),
    APP_TIMEOUT_ERR     (-40),
    APP_REPLYTO_ERR     (-41),
    APP_CONFIRMTO_ERR   (-42),
    REPLYTO_ERR         (-43),
    CONFIRMTO_ERR       (-44),
    REQCONFIRMTO_ERR    (-45),
    PACKET_ERR          (-46),
    UNRESOLVED_ERR      (-47),
    XML_PARSER_ERR      (-48),
    INUSE_ERR           (-49),
    MARSHALLING_ERR     (-50),
    UNKNOWN_ERR         (-99);

    private final int value;

    TrdpErrCode(int value) { this.value = value; }

    public int getValue() { return value; }

    /** Return the enum constant for {@code code}, or {@code null} if not found. */
    public static TrdpErrCode fromCode(int code) {
        for (TrdpErrCode e : values()) {
            if (e.value == code) return e;
        }
        return null;
    }
}
