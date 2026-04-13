package now.calypso.backendapi.pojos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import now.calypso.backend.data.SignalIntent;
import now.calypso.backendapi.signals.SignalConceptRegistry;

public class GetSignalConceptCandidates {
    public final long version;
    public final List<Candidate> candidates;

    public static GetSignalConceptCandidates fromEntries(long version, List<SignalConceptRegistry.CandidateEntry> entries) {
        ArrayList<Candidate> out = new ArrayList<>();
        if (entries != null) {
            for (SignalConceptRegistry.CandidateEntry entry : entries) {
                if (entry == null || entry.rawToken == null || entry.rawToken.isBlank()) {
                    continue;
                }
                out.add(new Candidate(entry.rawToken, entry.seenCount, entry.firstSeen, entry.lastSeen,
                        entry.lastSource, entry.exampleContexts, toObservedAccounts(entry.observedAccountIntents),
                        entry.suggestedCanonical, entry.suggestionScore,
                        entry.autoReady, null));
            }
        }
        return new GetSignalConceptCandidates(version, out);
    }

    public static GetSignalConceptCandidates fromBlockedEntries(
            long version,
            List<SignalConceptRegistry.BlockedCandidateEntry> entries) {
        ArrayList<Candidate> out = new ArrayList<>();
        if (entries != null) {
            for (SignalConceptRegistry.BlockedCandidateEntry entry : entries) {
                if (entry == null || entry.rawToken == null || entry.rawToken.isBlank()) {
                    continue;
                }
                out.add(new Candidate(entry.rawToken, entry.seenCount, entry.firstSeen, entry.lastSeen,
                        entry.lastSource, entry.exampleContexts, toObservedAccounts(entry.observedAccountIntents),
                        entry.suggestedCanonical, entry.suggestionScore,
                        false, entry.blockedAt));
            }
        }
        return new GetSignalConceptCandidates(version, out);
    }

    private static List<ObservedAccount> toObservedAccounts(
            List<SignalConceptRegistry.CandidateAccountIntentObservation> observations) {
        if (observations == null || observations.isEmpty()) {
            return Collections.emptyList();
        }
        ArrayList<ObservedAccount> out = new ArrayList<>(observations.size());
        for (SignalConceptRegistry.CandidateAccountIntentObservation observation : observations) {
            if (observation == null || observation.accountId < 0L) {
                continue;
            }
            out.add(new ObservedAccount(
                    observation.accountId,
                    observation.intent,
                    observation.seenCount,
                    observation.averageValence));
        }
        return out;
    }

    @JsonCreator
    public GetSignalConceptCandidates(
            @JsonProperty("version") long version,
            @JsonProperty("candidates") List<Candidate> candidates) {
        this.version = version;
        this.candidates = candidates == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(candidates));
    }

    public static final class Candidate {
        public final String rawToken;
        public final int seenCount;
        public final long firstSeen;
        public final long lastSeen;
        public final String lastSource;
        public final List<String> exampleContexts;
        public final List<ObservedAccount> observedAccounts;
        public final String suggestedCanonical;
        public final Double suggestionScore;
        public final boolean autoReady;
        public final Long blockedAt;

        @JsonCreator
        public Candidate(
                @JsonProperty("rawToken") String rawToken,
                @JsonProperty("seenCount") int seenCount,
                @JsonProperty("firstSeen") long firstSeen,
                @JsonProperty("lastSeen") long lastSeen,
                @JsonProperty("lastSource") String lastSource,
                @JsonProperty("exampleContexts") List<String> exampleContexts,
                @JsonProperty("observedAccounts") List<ObservedAccount> observedAccounts,
                @JsonProperty("suggestedCanonical") String suggestedCanonical,
                @JsonProperty("suggestionScore") Double suggestionScore,
                @JsonProperty("autoReady") boolean autoReady,
                @JsonProperty("blockedAt") Long blockedAt) {
            this.rawToken = rawToken;
            this.seenCount = seenCount;
            this.firstSeen = firstSeen;
            this.lastSeen = lastSeen;
            this.lastSource = lastSource;
            this.exampleContexts = exampleContexts == null ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(exampleContexts));
            this.observedAccounts = observedAccounts == null ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(observedAccounts));
            this.suggestedCanonical = suggestedCanonical;
            this.suggestionScore = suggestionScore;
            this.autoReady = autoReady;
            this.blockedAt = blockedAt;
        }
    }

    public static final class ObservedAccount {
        public final long accountId;
        public final SignalIntent intent;
        public final int seenCount;
        public final double averageValence;

        @JsonCreator
        public ObservedAccount(
                @JsonProperty("accountId") long accountId,
                @JsonProperty("intent") SignalIntent intent,
                @JsonProperty("seenCount") int seenCount,
                @JsonProperty("averageValence") double averageValence) {
            this.accountId = accountId;
            this.intent = intent;
            this.seenCount = seenCount;
            this.averageValence = averageValence;
        }
    }
}
