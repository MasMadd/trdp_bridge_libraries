package io.trdp;

/**
 * Metadata from a received PD telegram (mirrors {@code TRDP_PD_INFO_T}).
 */
public final class PdInfo {

    /** Source IP address (dotted-decimal). */
    public final String srcIp;
    /** Destination IP address (dotted-decimal). */
    public final String destIp;
    /** Communication identifier. */
    public final int comId;
    /** Sequence counter. */
    public final long seqCount;
    /** Message type code. */
    public final int msgType;
    /** ETB topology counter. */
    public final long etbTopoCnt;
    /** Operational-train topology counter. */
    public final long opTrnTopoCnt;
    /** Reply ComID (pull requests). */
    public final long replyComId;
    /** Reply IP address (pull requests). */
    public final String replyIp;
    /** {@code TRDP_ERR_T} result code. */
    public final int resultCode;
    /** Service ID. */
    public final long serviceId;

    PdInfo(String srcIp, String destIp, int comId, long seqCount,
           int msgType, long etbTopoCnt, long opTrnTopoCnt,
           long replyComId, String replyIp, int resultCode, long serviceId) {
        this.srcIp        = srcIp;
        this.destIp       = destIp;
        this.comId        = comId;
        this.seqCount     = seqCount;
        this.msgType      = msgType;
        this.etbTopoCnt   = etbTopoCnt;
        this.opTrnTopoCnt = opTrnTopoCnt;
        this.replyComId   = replyComId;
        this.replyIp      = replyIp;
        this.resultCode   = resultCode;
        this.serviceId    = serviceId;
    }

    @Override
    public String toString() {
        return "PdInfo{srcIp=" + srcIp + ", comId=" + comId
                + ", seq=" + seqCount + ", rc=" + resultCode + "}";
    }
}
