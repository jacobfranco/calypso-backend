package now.calypso.backendapi.pojos;

public class GetMatch {
    public final GetAccount account;
    public final double score;
    public final long computedAt;

    public GetMatch(GetAccount account, double score, long computedAt) {
        this.account = account;
        this.score = score;
        this.computedAt = computedAt;
    }
}
