package now.calypso.backendapi.pojos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import now.calypso.backendapi.signals.SignalConceptRegistry;

public class GetSignalConceptRegistry {
    public final long version;
    public final List<Concept> concepts;

    public static GetSignalConceptRegistry fromEntries(long version, List<SignalConceptRegistry.ConceptEntry> entries) {
        ArrayList<Concept> out = new ArrayList<>();
        if (entries != null) {
            for (SignalConceptRegistry.ConceptEntry entry : entries) {
                if (entry == null || entry.concept == null || entry.concept.isBlank()) {
                    continue;
                }
                out.add(new Concept(entry.concept, entry.category, entry.aliases, entry.parents));
            }
        }
        return new GetSignalConceptRegistry(version, out);
    }

    @JsonCreator
    public GetSignalConceptRegistry(
            @JsonProperty("version") long version,
            @JsonProperty("concepts") List<Concept> concepts) {
        this.version = version;
        this.concepts = concepts == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(concepts));
    }

    public static final class Concept {
        public final String concept;
        public final String category;
        public final List<String> aliases;
        public final Map<String, Double> parents;

        @JsonCreator
        public Concept(
                @JsonProperty("concept") String concept,
                @JsonProperty("category") String category,
                @JsonProperty("aliases") List<String> aliases,
                @JsonProperty("parents") Map<String, Double> parents) {
            this.concept = concept;
            this.category = category;
            this.aliases = aliases == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(aliases));
            this.parents = parents == null ? Collections.emptyMap() : Collections.unmodifiableMap(parents);
        }
    }
}
