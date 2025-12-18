package now.calypso.backendapi.pojos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import now.calypso.backend.data.SignalRecord;
import now.calypso.backend.data.Signals;

public class GetSignals {
    public final long accountId;
    public final List<String> tokens;
    public final List<GetSignalRecord> records;

    private static List<String> toTokenList(List<SignalRecord> thriftRecords) {
        List<String> toks = new ArrayList<>();
        if (thriftRecords != null) {
            for (SignalRecord r : thriftRecords) {
                if (r == null)
                    continue;
                if (r.getToken() != null)
                    toks.add(r.getToken());
            }
        }
        return toks;
    }

    private static List<GetSignalRecord> toRecordDtos(List<SignalRecord> thriftRecords) {
        List<GetSignalRecord> recs = new ArrayList<>();
        if (thriftRecords != null) {
            for (SignalRecord r : thriftRecords) {
                if (r == null)
                    continue;
                recs.add(new GetSignalRecord(r));
            }
        }
        return recs;
    }

    public GetSignals(long accountId, List<SignalRecord> thriftRecords) {
        this(accountId, toTokenList(thriftRecords), toRecordDtos(thriftRecords));
    }

    @JsonCreator
    public GetSignals(
            @JsonProperty("accountId") long accountId,
            @JsonProperty("tokens") List<String> tokens,
            @JsonProperty("records") List<GetSignalRecord> records) {
        this.accountId = accountId;
        this.tokens = tokens == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(tokens));
        this.records = records == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(records));
    }

    public GetSignals(Signals thrift) {
        this(thrift == null ? 0L : thrift.getAccountId(), thrift == null ? null : thrift.getRecords());
    }

    public static final class GetSignalRecord {
        public final String token;
        public final String source;
        public final String sourceId;
        public final Long firstSeen;
        public final Long lastSeen;
        public final Integer count;
        public final String lastContext;
        public final String intent;
        public final Double confidence;
        public final Double importance;

        public GetSignalRecord(SignalRecord r) {
            this.token = r.getToken();
            this.source = r.getSource();
            this.sourceId = r.getSourceId();
            this.firstSeen = r.isSetFirstSeen() ? r.getFirstSeen() : null;
            this.lastSeen = r.isSetLastSeen() ? r.getLastSeen() : null;
            this.count = r.isSetCount() ? r.getCount() : null;
            this.lastContext = r.getLastContext();
            this.intent = r.isSetIntent() && r.getIntent() != null ? r.getIntent().name() : null;
            this.confidence = r.isSetConfidence() ? r.getConfidence() : null;
            this.importance = r.isSetImportance() ? r.getImportance() : null;
        }

        @JsonCreator
        public GetSignalRecord(
                @JsonProperty("token") String token,
                @JsonProperty("source") String source,
                @JsonProperty("sourceId") String sourceId,
                @JsonProperty("firstSeen") Long firstSeen,
                @JsonProperty("lastSeen") Long lastSeen,
                @JsonProperty("count") Integer count,
                @JsonProperty("lastContext") String lastContext,
                @JsonProperty("intent") String intent,
                @JsonProperty("confidence") Double confidence,
                @JsonProperty("importance") Double importance) {
            this.token = token;
            this.source = source;
            this.sourceId = sourceId;
            this.firstSeen = firstSeen;
            this.lastSeen = lastSeen;
            this.count = count;
            this.lastContext = lastContext;
            this.intent = intent;
            this.confidence = confidence;
            this.importance = importance;
        }
    }
}
