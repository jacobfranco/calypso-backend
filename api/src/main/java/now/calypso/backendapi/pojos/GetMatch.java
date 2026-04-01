package now.calypso.backendapi.pojos;

import java.util.Map;

public class GetMatch {
    public final GetAccount account;
    public final double score;
    public final long computedAt;
    public final Map<String, Object> scorerDebug;

    public GetMatch(GetAccount account, double score, long computedAt) {
        this(account, score, computedAt, null);
    }

    public GetMatch(GetAccount account, double score, long computedAt, Map<String, Object> scorerDebug) {
        this.account = account;
        this.score = score;
        this.computedAt = computedAt;
        this.scorerDebug = scorerDebug;
    }
}
