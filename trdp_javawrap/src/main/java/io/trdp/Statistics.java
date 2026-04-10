package io.trdp;

/**
 * Session statistics returned by {@link TrdpSession#getStatistics()}.
 *
 * <p>Contains the most commonly needed counters from {@code TRDP_STATISTICS_T},
 * {@code TRDP_SUBS_STATISTICS_T} and {@code TRDP_PUB_STATISTICS_T}.
 */
public final class Statistics {

    // ── General ──────────────────────────────────────────────────────────────
    public final long   version;
    public final long   upTimeSec;
    public final long   statisticTimeSec;
    public final String ownIp;
    public final String leaderIp;
    public final long   numRedundancyGroups;
    public final long   numJoinedMcGroups;

    // ── Process Data ─────────────────────────────────────────────────────────
    public final long pdNumSubs;
    public final long pdNumPub;
    public final long pdNumSent;
    public final long pdNumReceived;
    public final long pdNumTimeout;
    public final long pdNumCrcErrors;

    // ── Message Data (UDP) ────────────────────────────────────────────────────
    public final long udpMdNumSent;
    public final long udpMdNumReceived;
    public final long udpMdNumTimeout;

    // ── Message Data (TCP) ────────────────────────────────────────────────────
    public final long tcpMdNumSent;
    public final long tcpMdNumReceived;

    Statistics(long version, long upTimeSec, long statisticTimeSec,
               String ownIp, String leaderIp,
               long numRed, long numJoin,
               long pdSubs, long pdPub, long pdSent, long pdRcv,
               long pdTimeout, long pdCrc,
               long udpSent, long udpRcv, long udpTimeout,
               long tcpSent, long tcpRcv) {
        this.version            = version;
        this.upTimeSec          = upTimeSec;
        this.statisticTimeSec   = statisticTimeSec;
        this.ownIp              = ownIp;
        this.leaderIp           = leaderIp;
        this.numRedundancyGroups = numRed;
        this.numJoinedMcGroups  = numJoin;
        this.pdNumSubs          = pdSubs;
        this.pdNumPub           = pdPub;
        this.pdNumSent          = pdSent;
        this.pdNumReceived      = pdRcv;
        this.pdNumTimeout       = pdTimeout;
        this.pdNumCrcErrors     = pdCrc;
        this.udpMdNumSent       = udpSent;
        this.udpMdNumReceived   = udpRcv;
        this.udpMdNumTimeout    = udpTimeout;
        this.tcpMdNumSent       = tcpSent;
        this.tcpMdNumReceived   = tcpRcv;
    }

    @Override
    public String toString() {
        return "Statistics{upTime=" + upTimeSec + "s, ownIp=" + ownIp
                + ", pdSubs=" + pdNumSubs + ", pdPub=" + pdNumPub
                + ", pdSent=" + pdNumSent + ", pdRcv=" + pdNumReceived + "}";
    }

    // ── Per-subscription / per-publisher ─────────────────────────────────────

    /** Per-subscription statistics (mirrors {@code TRDP_SUBS_STATISTICS_T}). */
    public static final class SubsStats {
        public final int    comId;
        public final String joinedAddr;
        public final String filterAddr;
        public final long   timeoutUs;
        public final int    status;
        public final long   numReceived;
        public final long   numMissed;

        SubsStats(int comId, String joinedAddr, String filterAddr,
                  long timeoutUs, int status, long numRcv, long numMissed) {
            this.comId       = comId;
            this.joinedAddr  = joinedAddr;
            this.filterAddr  = filterAddr;
            this.timeoutUs   = timeoutUs;
            this.status      = status;
            this.numReceived = numRcv;
            this.numMissed   = numMissed;
        }

        @Override
        public String toString() {
            return "SubsStats{comId=" + comId + ", rcv=" + numReceived + "}";
        }
    }

    /** Per-publisher statistics (mirrors {@code TRDP_PUB_STATISTICS_T}). */
    public static final class PubStats {
        public final int    comId;
        public final String destAddr;
        public final long   cycleUs;
        public final long   redId;
        public final long   redState;
        public final long   numPut;
        public final long   numSent;

        PubStats(int comId, String destAddr, long cycleUs,
                 long redId, long redState, long numPut, long numSent) {
            this.comId    = comId;
            this.destAddr = destAddr;
            this.cycleUs  = cycleUs;
            this.redId    = redId;
            this.redState = redState;
            this.numPut   = numPut;
            this.numSent  = numSent;
        }

        @Override
        public String toString() {
            return "PubStats{comId=" + comId + ", sent=" + numSent + "}";
        }
    }
}
