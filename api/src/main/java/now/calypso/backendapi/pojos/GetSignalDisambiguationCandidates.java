package now.calypso.backendapi.pojos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public class GetSignalDisambiguationCandidates {
    public final List<Candidate> candidates;

    public GetSignalDisambiguationCandidates(@JsonProperty("candidates") List<Candidate> candidates) {
        this.candidates = candidates == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(candidates));
    }

    public static final class Candidate {
        public final String key;
        public final String term;
        public final String question;
        public final String promptId;
        public final String source;
        public final String sourceId;
        public final String context;
        public final int seenCount;
        public final long firstSeen;
        public final long lastSeen;

        public Candidate(
                @JsonProperty("key") String key,
                @JsonProperty("term") String term,
                @JsonProperty("question") String question,
                @JsonProperty("promptId") String promptId,
                @JsonProperty("source") String source,
                @JsonProperty("sourceId") String sourceId,
                @JsonProperty("context") String context,
                @JsonProperty("seenCount") int seenCount,
                @JsonProperty("firstSeen") long firstSeen,
                @JsonProperty("lastSeen") long lastSeen) {
            this.key = key;
            this.term = term;
            this.question = question;
            this.promptId = promptId;
            this.source = source;
            this.sourceId = sourceId;
            this.context = context;
            this.seenCount = seenCount;
            this.firstSeen = firstSeen;
            this.lastSeen = lastSeen;
        }
    }
}
