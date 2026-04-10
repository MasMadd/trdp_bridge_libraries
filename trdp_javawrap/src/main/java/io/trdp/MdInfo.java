package io.trdp;

/**
 * Metadata from a received MD telegram (mirrors {@code TRDP_MD_INFO_T}).
 */
public final class MdInfo {

    public final String srcIp;
    public final String destIp;
    public final int    comId;
    public final long   seqCount;
    public final int    msgType;
    public final long   etbTopoCnt;
    public final long   opTrnTopoCnt;
    /** 16-byte session UUID (use with {@link TrdpSession#mdReply} etc.). */
    public final byte[] sessionId;
    public final int    resultCode;
    public final int    userStatus;
    public final int    replyStatus;
    public final boolean aboutToDie;
    public final long   numReplies;
    public final long   numExpReplies;
    public final String srcUserUri;
    public final String destUserUri;
    public final long   replyTimeoutUs;

    MdInfo(String srcIp, String destIp, int comId, long seqCount,
           int msgType, long etbTopoCnt, long opTrnTopoCnt,
           byte[] sessionId, int resultCode, int userStatus,
           int replyStatus, boolean aboutToDie,
           long numReplies, long numExpReplies,
           String srcUserUri, String destUserUri, long replyTimeoutUs) {
        this.srcIp          = srcIp;
        this.destIp         = destIp;
        this.comId          = comId;
        this.seqCount       = seqCount;
        this.msgType        = msgType;
        this.etbTopoCnt     = etbTopoCnt;
        this.opTrnTopoCnt   = opTrnTopoCnt;
        this.sessionId      = sessionId.clone();
        this.resultCode     = resultCode;
        this.userStatus     = userStatus;
        this.replyStatus    = replyStatus;
        this.aboutToDie     = aboutToDie;
        this.numReplies     = numReplies;
        this.numExpReplies  = numExpReplies;
        this.srcUserUri     = srcUserUri;
        this.destUserUri    = destUserUri;
        this.replyTimeoutUs = replyTimeoutUs;
    }

    @Override
    public String toString() {
        return "MdInfo{srcIp=" + srcIp + ", comId=" + comId
                + ", rc=" + resultCode + ", replies=" + numReplies + "}";
    }
}
