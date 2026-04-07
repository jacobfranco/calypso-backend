package now.calypso.backendapi.signals;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;

import now.calypso.backend.data.SignalIntent;

/**
 * Canonical concept registry for v3 signal handling.
 *
 * Goals:
 * - Keep extraction flexible (raw tags still accepted)
 * - Resolve common variants to stable canonical concepts for scoring
 * - Track unresolved drift candidates for admin promotion/rejection
 */
public final class SignalConceptRegistry {
    private static final int AUTO_READY_MIN_SEEN = 3;
    private static final double AUTO_READY_MIN_SCORE = 0.92;
    private static final double SUGGESTION_MIN_SCORE = 0.84;
    private static final double PROPAGATION_MIN_EDGE_WEIGHT = 0.15;
    private static final long AUTO_PROMOTION_INTERVAL_MS = TimeUnit.HOURS.toMillis(1);

    public enum ResolutionKind {
        CANONICAL,
        ALIAS,
        UNKNOWN
    }

    public record Resolution(String rawToken, String canonicalToken, ResolutionKind kind) {
    }

    public static final class ConceptEntry {
        public final String concept;
        public final List<String> aliases;
        public final Map<String, Double> parents;

        public ConceptEntry(String concept, List<String> aliases, Map<String, Double> parents) {
            this.concept = concept;
            this.aliases = aliases == null ? List.of() : List.copyOf(aliases);
            this.parents = parents == null ? Map.of() : Map.copyOf(parents);
        }
    }

    public static final class CandidateEntry {
        public final String rawToken;
        public final int seenCount;
        public final long firstSeen;
        public final long lastSeen;
        public final String lastSource;
        public final List<String> exampleContexts;
        public final List<CandidateAccountIntentObservation> observedAccountIntents;
        public final String suggestedCanonical;
        public final Double suggestionScore;
        public final boolean autoReady;

        public CandidateEntry(String rawToken, int seenCount, long firstSeen, long lastSeen, String lastSource,
                List<String> exampleContexts, List<CandidateAccountIntentObservation> observedAccountIntents,
                String suggestedCanonical, Double suggestionScore, boolean autoReady) {
            this.rawToken = rawToken;
            this.seenCount = seenCount;
            this.firstSeen = firstSeen;
            this.lastSeen = lastSeen;
            this.lastSource = lastSource;
            this.exampleContexts = exampleContexts == null ? List.of() : List.copyOf(exampleContexts);
            this.observedAccountIntents = observedAccountIntents == null ? List.of() : List.copyOf(observedAccountIntents);
            this.suggestedCanonical = suggestedCanonical;
            this.suggestionScore = suggestionScore;
            this.autoReady = autoReady;
        }
    }

    public static final class CandidateAccountIntentObservation {
        public final long accountId;
        public final SignalIntent intent;
        public final int seenCount;
        public final double averageValence;

        public CandidateAccountIntentObservation(long accountId, SignalIntent intent, int seenCount,
                double averageValence) {
            this.accountId = accountId;
            this.intent = intent;
            this.seenCount = seenCount;
            this.averageValence = averageValence;
        }
    }

    private static final class ConceptSeed {
        final String concept;
        final LinkedHashSet<String> aliases = new LinkedHashSet<>();
        final LinkedHashMap<String, Double> parents = new LinkedHashMap<>();

        ConceptSeed(String concept) {
            this.concept = concept;
        }
    }

    private static final class CandidateStats {
        private static final class AccountIntentStats {
            final long accountId;
            final SignalIntent intent;
            int seenCount;
            double valenceSum;

            AccountIntentStats(long accountId, SignalIntent intent) {
                this.accountId = accountId;
                this.intent = intent;
            }

            double averageValence() {
                if (seenCount <= 0) {
                    return 0.0;
                }
                return valenceSum / (double) seenCount;
            }
        }

        final String rawToken;
        int seenCount;
        long firstSeen;
        long lastSeen;
        String lastSource;
        final LinkedHashSet<String> exampleContexts = new LinkedHashSet<>();
        final LinkedHashMap<String, AccountIntentStats> accountIntentStatsByKey = new LinkedHashMap<>();
        String suggestedCanonical;
        Double suggestionScore;

        CandidateStats(String rawToken) {
            this.rawToken = rawToken;
        }

        synchronized void record(String source, String contextMaybe, Long accountId, SignalIntent intent,
                Double valenceMaybe) {
            long now = System.currentTimeMillis();
            if (seenCount == 0) {
                firstSeen = now;
            }
            seenCount += 1;
            lastSeen = now;
            if (source != null && !source.isBlank()) {
                lastSource = source.trim();
            }
            if (contextMaybe != null && !contextMaybe.isBlank()) {
                String compact = contextMaybe.trim();
                if (compact.length() > 180) {
                    compact = compact.substring(0, 180);
                }
                if (!compact.isBlank()) {
                    exampleContexts.add(compact);
                    while (exampleContexts.size() > 3) {
                        String oldest = exampleContexts.iterator().next();
                        exampleContexts.remove(oldest);
                    }
                }
            }
            if (accountId != null && accountId.longValue() >= 0L) {
                SignalIntent resolvedIntent = intent == null ? SignalIntent.SELF : intent;
                String key = accountId.longValue() + "|" + resolvedIntent.name();
                AccountIntentStats perAccount = accountIntentStatsByKey.computeIfAbsent(key,
                        k -> new AccountIntentStats(accountId.longValue(), resolvedIntent));
                perAccount.seenCount += 1;
                double valence = valenceMaybe == null ? 1.0 : valenceMaybe.doubleValue();
                if (Double.isNaN(valence)) {
                    valence = 0.0;
                }
                if (valence < -1.0) {
                    valence = -1.0;
                } else if (valence > 1.0) {
                    valence = 1.0;
                }
                perAccount.valenceSum += valence;
            }
            Suggestion suggestion = suggestCanonical(rawToken);
            this.suggestedCanonical = suggestion == null ? null : suggestion.canonical;
            this.suggestionScore = suggestion == null ? null : suggestion.score;
        }

        synchronized CandidateEntry snapshot() {
            boolean autoReady = seenCount >= AUTO_READY_MIN_SEEN
                    && suggestionScore != null
                    && suggestionScore.doubleValue() >= AUTO_READY_MIN_SCORE
                    && suggestedCanonical != null
                    && !suggestedCanonical.isBlank();
            return new CandidateEntry(
                    rawToken,
                    seenCount,
                    firstSeen,
                    lastSeen,
                    lastSource,
                    new ArrayList<>(exampleContexts),
                    snapshotAccountIntentObservations(),
                    suggestedCanonical,
                    suggestionScore,
                    autoReady);
        }

        synchronized List<CandidateAccountIntentObservation> snapshotAccountIntentObservations() {
            if (accountIntentStatsByKey.isEmpty()) {
                return List.of();
            }
            ArrayList<CandidateAccountIntentObservation> out = new ArrayList<>(accountIntentStatsByKey.size());
            for (AccountIntentStats stats : accountIntentStatsByKey.values()) {
                if (stats == null || stats.accountId < 0L || stats.seenCount <= 0) {
                    continue;
                }
                out.add(new CandidateAccountIntentObservation(
                        stats.accountId,
                        stats.intent,
                        stats.seenCount,
                        stats.averageValence()));
            }
            return out;
        }
    }

    private static final class PropagationNode {
        final String token;
        final double weight;
        final int depth;

        PropagationNode(String token, double weight, int depth) {
            this.token = token;
            this.weight = weight;
            this.depth = depth;
        }
    }

    private static final class Suggestion {
        final String canonical;
        final double score;

        Suggestion(String canonical, double score) {
            this.canonical = canonical;
            this.score = score;
        }
    }

    private static final Map<String, ConceptSeed> BASE_CONCEPTS;
    private static final Map<String, String> BASE_ALIAS_TO_CANONICAL;

    private static final Set<String> DYNAMIC_CANONICAL_CONCEPTS = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<String, String> DYNAMIC_ALIAS_TO_CANONICAL = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, CandidateStats> CANDIDATE_BY_RAW = new ConcurrentHashMap<>();
    private static final AtomicLong REGISTRY_VERSION = new AtomicLong(1L);
    private static final AtomicLong LAST_AUTO_PROMOTION_SWEEP_MS = new AtomicLong(0L);

    static {
        LinkedHashMap<String, ConceptSeed> seeds = new LinkedHashMap<>();

        addConcepts(seeds,
                "travel", "fashion", "anime", "manga", "gaming", "video_games", "sports", "soccer",
                "basketball", "football", "baseball", "hockey", "tennis", "fitness", "gym",
                "bodybuilding", "powerlifting", "running", "hiking", "yoga", "pilates", "cycling",
                "swimming", "martial_arts", "nutrition", "science", "cooking", "reading", "books",
                "writing", "music", "concerts", "nightlife", "club", "socializing",
                "coffee", "tea", "wine", "beer", "foodie", "baking", "entrepreneurship",
                "startup", "career", "ambition", "academics", "education", "phd",
                "tech", "software", "design", "art", "painting", "photography",
                "film", "cinema", "modeling", "streetwear", "high_fashion",
                "nature", "outdoors", "camping", "birdwatching", "adventure",
                "world_cup", "meditation", "mindfulness", "homebody", "cozy", "pets", "dogs", "cats",
                "family", "faith", "spirituality", "politics", "volunteering", "community",
                "communication", "kindness", "humor", "intelligence", "creativity",
                "discipline", "consistency", "morning_person", "night_owl",
                "relaxing", "rest", "self_care", "romance_novels", "romance_genre", "romance",
                "jojos_bizarre_adventure", "elden_ring", "soulslike", "cosplay", "kpop", "jpop",
                "rnb", "hip_hop", "jazz", "classical_music", "country_music", "edm", "metal", "underground_music",
                "indie_music", "board_games", "dnd", "pokemon", "nintendo", "playstation", "xbox",
                "pc_gaming", "streaming", "content_creation", "podcasts", "history", "philosophy",
                "economics", "finance", "investing", "real_estate", "ux", "product_management",
                "marketing", "sales", "leadership", "public_speaking", "languages",
                "beach", "mountains", "city_life", "suburban_life", "minimalism", "luxury_lifestyle",
                "thrifting", "sustainability", "vegan", "vegetarian", "meal_prep", "desserts",
                "cocktails", "karaoke", "dance", "festivals", "museum", "theater",
                "opera", "architecture", "cars", "motorcycles", "f1", "ufc", "wrestling",
                "healthcare", "law", "teaching", "nursing", "engineering", "data_science",
                "machine_learning", "ai", "cybersecurity", "open_source", "side_projects", "maker",
                "networking", "self_improvement", "journaling", "therapy",
                "mental_health", "greek_life", "solo_travel",
                "backpacking", "language_exchange",
                "street_food", "fine_dining",
                "long_term_planning", "commitment", "loyalty", "honesty", "empathy", "respect");

        // Keep aliases intentionally small and high-confidence.
        // We avoid broad shape canonicalization here so prompt outputs remain raw by
        // default unless the alias is very stable.
        addAliases(seeds, "jojos_bizarre_adventure", "jojo_bizarre_adventure", "jojo", "jjba");
        addAliases(seeds, "anime", "anime_fan", "otaku", "weeb", "shonen", "shounen");
        addAliases(seeds, "manga", "manga_fan");
        addAliases(seeds, "elden_ring", "eldenring");

        // Hierarchy edges for stage-2 propagation.
        addParent(seeds, "high_fashion", "fashion", 0.84);
        addParent(seeds, "streetwear", "fashion", 0.78);
        addParent(seeds, "fashion_modeling", "fashion", 0.72);
        addParent(seeds, "modeling", "fashion", 0.58);
        addParent(seeds, "jojos_bizarre_adventure", "anime", 0.88);
        addParent(seeds, "manga", "anime", 0.62);
        addParent(seeds, "elden_ring", "soulslike", 0.86);
        addParent(seeds, "soulslike", "gaming", 0.78);
        addParent(seeds, "pc_gaming", "gaming", 0.72);
        addParent(seeds, "nintendo", "gaming", 0.68);
        addParent(seeds, "xbox", "gaming", 0.68);
        addParent(seeds, "playstation", "gaming", 0.68);
        addParent(seeds, "board_games", "gaming", 0.52);
        addParent(seeds, "world_cup", "soccer", 0.90);
        addParent(seeds, "soccer", "sports", 0.78);
        addParent(seeds, "football", "sports", 0.78);
        addParent(seeds, "basketball", "sports", 0.78);
        addParent(seeds, "baseball", "sports", 0.74);
        addParent(seeds, "hockey", "sports", 0.74);
        addParent(seeds, "f1", "sports", 0.70);
        addParent(seeds, "ufc", "sports", 0.70);
        addParent(seeds, "wrestling", "sports", 0.66);
        addParent(seeds, "bodybuilding", "gym", 0.84);
        addParent(seeds, "powerlifting", "gym", 0.84);
        addParent(seeds, "gym", "fitness", 0.74);
        addParent(seeds, "pilates", "fitness", 0.66);
        addParent(seeds, "yoga", "fitness", 0.66);
        addParent(seeds, "running", "fitness", 0.64);
        addParent(seeds, "hiking", "outdoors", 0.70);
        addParent(seeds, "birdwatching", "nature", 0.74);
        addParent(seeds, "travel_photography", "photography", 0.72);
        addParent(seeds, "solo_travel", "travel", 0.72);
        addParent(seeds, "group_travel", "travel", 0.72);
        addParent(seeds, "backpacking", "travel", 0.76);
        addParent(seeds, "luxury_travel", "travel", 0.76);
        addParent(seeds, "budget_travel", "travel", 0.74);
        addParent(seeds, "weekend_trip", "travel", 0.68);
        addParent(seeds, "nightlife", "socializing", 0.72);
        addParent(seeds, "club", "nightlife", 0.78);
        addParent(seeds, "brunch", "socializing", 0.54);
        addParent(seeds, "coffee", "foodie", 0.42);
        addParent(seeds, "baking", "cooking", 0.68);
        addParent(seeds, "desserts", "foodie", 0.62);
        addParent(seeds, "cocktails", "foodie", 0.58);
        addParent(seeds, "mixology", "cocktails", 0.76);
        addParent(seeds, "street_food", "foodie", 0.68);
        addParent(seeds, "fine_dining", "foodie", 0.68);
        addParent(seeds, "startup", "entrepreneurship", 0.84);
        addParent(seeds, "app_builder", "software", 0.74);
        addParent(seeds, "software", "tech", 0.70);
        addParent(seeds, "data_science", "tech", 0.72);
        addParent(seeds, "machine_learning", "ai", 0.82);
        addParent(seeds, "ai", "tech", 0.66);
        addParent(seeds, "cybersecurity", "tech", 0.72);
        addParent(seeds, "career", "career_focus", 0.62);
        addParent(seeds, "career_focus", "ambition", 0.66);
        addParent(seeds, "career_growth", "career_focus", 0.78);
        addParent(seeds, "long_term_planning", "ambition", 0.64);
        addParent(seeds, "phd", "academic_ambition", 0.90);
        addParent(seeds, "education", "academic_ambition", 0.58);
        addParent(seeds, "morning_person", "early_morning_activity", 0.84);
        addParent(seeds, "early_riser", "morning_person", 0.82);
        addParent(seeds, "cozy", "homebody", 0.66);
        addParent(seeds, "rest", "relaxing", 0.70);
        addParent(seeds, "self_care", "relaxing", 0.58);
        addParent(seeds, "romance_novels", "reading", 0.78);
        addParent(seeds, "romance_genre", "romance", 0.74);
        addParent(seeds, "greek_life", "community", 0.62);

        BASE_CONCEPTS = Collections.unmodifiableMap(seeds);

        LinkedHashMap<String, String> aliasMap = new LinkedHashMap<>();
        for (ConceptSeed seed : seeds.values()) {
            aliasMap.put(seed.concept, seed.concept);
            for (String alias : seed.aliases) {
                aliasMap.put(alias, seed.concept);
            }
        }
        BASE_ALIAS_TO_CANONICAL = Collections.unmodifiableMap(aliasMap);
    }

    private SignalConceptRegistry() {
    }

    public static long version() {
        return REGISTRY_VERSION.get();
    }

    public static Resolution resolve(String rawToken) {
        String raw = SignalNormalizer.normalizeOne(rawToken);
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String dynamic = DYNAMIC_ALIAS_TO_CANONICAL.get(raw);
        if (dynamic != null && !dynamic.isBlank()) {
            return new Resolution(raw, dynamic, ResolutionKind.ALIAS);
        }

        String mapped = BASE_ALIAS_TO_CANONICAL.get(raw);
        if (mapped != null && !mapped.isBlank()) {
            ResolutionKind kind = Objects.equals(raw, mapped) ? ResolutionKind.CANONICAL : ResolutionKind.ALIAS;
            return new Resolution(raw, mapped, kind);
        }

        if (DYNAMIC_CANONICAL_CONCEPTS.contains(raw)) {
            return new Resolution(raw, raw, ResolutionKind.CANONICAL);
        }

        return new Resolution(raw, raw, ResolutionKind.UNKNOWN);
    }

    /**
     * Resolve token for persistence/scoring.
     * - Known canonical/alias mappings pass through.
     * - Unknown tokens can be auto-mapped only when suggestion confidence is very
     * high.
     * - Otherwise token remains UNKNOWN and callers can route it to drift queue.
     */
    public static Resolution resolveForWrite(String rawToken) {
        Resolution resolution = resolve(rawToken);
        if (resolution == null || resolution.kind != ResolutionKind.UNKNOWN) {
            return resolution;
        }
        Suggestion suggestion = suggestCanonical(resolution.rawToken());
        if (suggestion == null || suggestion.canonical == null || suggestion.canonical.isBlank()) {
            return resolution;
        }
        if (suggestion.score < AUTO_READY_MIN_SCORE) {
            return resolution;
        }
        return new Resolution(resolution.rawToken(), suggestion.canonical, ResolutionKind.ALIAS);
    }

    public static void observeUnresolved(String rawToken, String source, String contextMaybe) {
        observeUnresolved(rawToken, source, contextMaybe, null, null, null);
    }

    public static void observeUnresolved(String rawToken, String source, String contextMaybe, Long accountId,
            SignalIntent intent, Double valenceMaybe) {
        Resolution resolution = resolve(rawToken);
        if (resolution == null || resolution.kind != ResolutionKind.UNKNOWN) {
            return;
        }
        CandidateStats stats = CANDIDATE_BY_RAW.computeIfAbsent(resolution.rawToken(), CandidateStats::new);
        stats.record(source, contextMaybe, accountId, intent, valenceMaybe);
    }

    public static boolean promoteAlias(String rawToken, String canonicalToken) {
        String raw = SignalNormalizer.normalizeOne(rawToken);
        String canonical = SignalNormalizer.normalizeOne(canonicalToken);
        if (raw == null || raw.isBlank() || canonical == null || canonical.isBlank()) {
            return false;
        }
        if (!BASE_CONCEPTS.containsKey(canonical)) {
            DYNAMIC_CANONICAL_CONCEPTS.add(canonical);
        }
        DYNAMIC_ALIAS_TO_CANONICAL.put(raw, canonical);
        CANDIDATE_BY_RAW.remove(raw);
        REGISTRY_VERSION.incrementAndGet();
        return true;
    }

    public static boolean rejectCandidate(String rawToken) {
        String raw = SignalNormalizer.normalizeOne(rawToken);
        if (raw == null || raw.isBlank()) {
            return false;
        }
        boolean removed = CANDIDATE_BY_RAW.remove(raw) != null;
        if (removed) {
            REGISTRY_VERSION.incrementAndGet();
        }
        return removed;
    }

    public static List<CandidateAccountIntentObservation> candidateAccountIntentObservations(String rawToken) {
        String raw = SignalNormalizer.normalizeOne(rawToken);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        CandidateStats stats = CANDIDATE_BY_RAW.get(raw);
        if (stats == null) {
            return List.of();
        }
        return stats.snapshotAccountIntentObservations();
    }

    public static List<String> candidateExampleContexts(String rawToken) {
        String raw = SignalNormalizer.normalizeOne(rawToken);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        CandidateStats stats = CANDIDATE_BY_RAW.get(raw);
        if (stats == null) {
            return List.of();
        }
        CandidateEntry snapshot = stats.snapshot();
        if (snapshot == null || snapshot.exampleContexts == null || snapshot.exampleContexts.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(snapshot.exampleContexts);
    }

    /**
     * Lightweight cleaner pass. Runs at most hourly and auto-promotes only
     * high-confidence candidates that meet the ready thresholds.
     */
    public static int autoPromoteReadyCandidatesIfDue() {
        long now = System.currentTimeMillis();
        while (true) {
            long last = LAST_AUTO_PROMOTION_SWEEP_MS.get();
            if ((now - last) < AUTO_PROMOTION_INTERVAL_MS) {
                return 0;
            }
            if (LAST_AUTO_PROMOTION_SWEEP_MS.compareAndSet(last, now)) {
                return autoPromoteReadyCandidates();
            }
        }
    }

    static int autoPromoteReadyCandidates() {
        int promoted = 0;
        for (CandidateStats stats : CANDIDATE_BY_RAW.values()) {
            if (stats == null) {
                continue;
            }
            CandidateEntry snapshot = stats.snapshot();
            if (snapshot == null || !snapshot.autoReady) {
                continue;
            }
            if (snapshot.suggestedCanonical == null || snapshot.suggestedCanonical.isBlank()) {
                continue;
            }
            if (promoteAlias(snapshot.rawToken, snapshot.suggestedCanonical)) {
                promoted += 1;
            }
        }
        return promoted;
    }

    public static List<ConceptEntry> conceptsSnapshot() {
        LinkedHashMap<String, ConceptEntry> out = new LinkedHashMap<>();

        HashMap<String, LinkedHashSet<String>> aliasesByCanonical = new HashMap<>();
        for (Map.Entry<String, String> entry : BASE_ALIAS_TO_CANONICAL.entrySet()) {
            String alias = entry.getKey();
            String canonical = entry.getValue();
            if (alias == null || canonical == null || alias.equals(canonical)) {
                continue;
            }
            aliasesByCanonical.computeIfAbsent(canonical, k -> new LinkedHashSet<>()).add(alias);
        }
        for (Map.Entry<String, String> entry : DYNAMIC_ALIAS_TO_CANONICAL.entrySet()) {
            String alias = entry.getKey();
            String canonical = entry.getValue();
            if (alias == null || canonical == null || alias.equals(canonical)) {
                continue;
            }
            aliasesByCanonical.computeIfAbsent(canonical, k -> new LinkedHashSet<>()).add(alias);
        }

        for (ConceptSeed seed : BASE_CONCEPTS.values()) {
            List<String> aliases = new ArrayList<>(aliasesByCanonical.getOrDefault(seed.concept, new LinkedHashSet<>()));
            aliases.sort(String::compareTo);
            out.put(seed.concept, new ConceptEntry(seed.concept, aliases, seed.parents));
        }

        for (String dynamic : DYNAMIC_CANONICAL_CONCEPTS) {
            if (dynamic == null || dynamic.isBlank() || out.containsKey(dynamic)) {
                continue;
            }
            List<String> aliases = new ArrayList<>(aliasesByCanonical.getOrDefault(dynamic, new LinkedHashSet<>()));
            aliases.sort(String::compareTo);
            out.put(dynamic, new ConceptEntry(dynamic, aliases, Map.of()));
        }

        ArrayList<ConceptEntry> sorted = new ArrayList<>(out.values());
        sorted.sort((a, b) -> a.concept.compareTo(b.concept));
        return sorted;
    }

    public static List<CandidateEntry> candidateSnapshot(int limit) {
        int bounded = Math.max(1, Math.min(500, limit));
        ArrayList<CandidateEntry> entries = new ArrayList<>();
        for (CandidateStats stats : CANDIDATE_BY_RAW.values()) {
            if (stats == null) {
                continue;
            }
            entries.add(stats.snapshot());
        }
        entries.sort((a, b) -> {
            int byCount = Integer.compare(b.seenCount, a.seenCount);
            if (byCount != 0) {
                return byCount;
            }
            int byLastSeen = Long.compare(b.lastSeen, a.lastSeen);
            if (byLastSeen != 0) {
                return byLastSeen;
            }
            return a.rawToken.compareTo(b.rawToken);
        });
        if (entries.size() <= bounded) {
            return entries;
        }
        return new ArrayList<>(entries.subList(0, bounded));
    }

    public static List<String> normalizeAndCanonicalizeTokens(Collection<String> rawTokens) {
        if (rawTokens == null || rawTokens.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : rawTokens) {
            Resolution resolution = resolveForWrite(raw);
            if (resolution == null || resolution.canonicalToken() == null || resolution.canonicalToken().isBlank()) {
                continue;
            }
            if (resolution.kind() == ResolutionKind.UNKNOWN) {
                continue;
            }
            out.add(resolution.canonicalToken());
        }
        return new ArrayList<>(out);
    }

    /**
     * Returns concept weights for hierarchy propagation including the base concept
     * itself (weight=1.0) plus ancestors with multiplicative decay.
     */
    public static Map<String, Double> expandedConceptWeights(String concept, int maxDepth) {
        String normalized = SignalNormalizer.normalizeOne(concept);
        if (normalized == null || normalized.isBlank()) {
            return Map.of();
        }
        int boundedDepth = Math.max(0, Math.min(5, maxDepth));
        LinkedHashMap<String, Double> out = new LinkedHashMap<>();
        out.put(normalized, 1.0);
        if (boundedDepth == 0) {
            return out;
        }

        PriorityQueue<PropagationNode> queue = new PriorityQueue<>(
                (a, b) -> Double.compare(b.weight, a.weight));
        queue.add(new PropagationNode(normalized, 1.0, 0));
        while (!queue.isEmpty()) {
            PropagationNode node = queue.poll();
            if (node == null || node.depth >= boundedDepth) {
                continue;
            }
            Map<String, Double> parents = parentsFor(node.token);
            if (parents.isEmpty()) {
                continue;
            }
            for (Map.Entry<String, Double> edge : parents.entrySet()) {
                String parent = SignalNormalizer.normalizeOne(edge.getKey());
                if (parent == null || parent.isBlank()) {
                    continue;
                }
                double edgeWeight = edge.getValue() == null ? 0.0 : edge.getValue().doubleValue();
                if (Double.isNaN(edgeWeight) || edgeWeight <= 0.0) {
                    continue;
                }
                if (edgeWeight > 1.0) {
                    edgeWeight = 1.0;
                }
                if (edgeWeight < PROPAGATION_MIN_EDGE_WEIGHT) {
                    continue;
                }
                double propagated = node.weight * edgeWeight;
                if (propagated < PROPAGATION_MIN_EDGE_WEIGHT) {
                    continue;
                }
                double existing = out.getOrDefault(parent, 0.0);
                if (propagated <= existing + 1.0e-9) {
                    continue;
                }
                out.put(parent, propagated);
                queue.add(new PropagationNode(parent, propagated, node.depth + 1));
            }
        }
        return out;
    }

    public static Map<String, Double> parentsFor(String concept) {
        String normalized = SignalNormalizer.normalizeOne(concept);
        if (normalized == null || normalized.isBlank()) {
            return Map.of();
        }
        ConceptSeed seed = BASE_CONCEPTS.get(normalized);
        if (seed == null || seed.parents.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(seed.parents);
    }

    private static void addConcepts(Map<String, ConceptSeed> seeds, String... concepts) {
        if (seeds == null || concepts == null || concepts.length == 0) {
            return;
        }
        for (String concept : concepts) {
            String normalized = normalizeConcept(concept);
            if (normalized == null) {
                continue;
            }
            seeds.computeIfAbsent(normalized, ConceptSeed::new);
        }
    }

    private static void addAliases(Map<String, ConceptSeed> seeds, String canonical, String... aliases) {
        String canonicalToken = normalizeConcept(canonical);
        if (canonicalToken == null) {
            return;
        }
        ConceptSeed seed = seeds.computeIfAbsent(canonicalToken, ConceptSeed::new);
        if (aliases == null || aliases.length == 0) {
            return;
        }
        for (String alias : aliases) {
            String normalizedAlias = normalizeConcept(alias);
            if (normalizedAlias == null || normalizedAlias.equals(canonicalToken)) {
                continue;
            }
            seed.aliases.add(normalizedAlias);
        }
    }

    private static void addParent(Map<String, ConceptSeed> seeds, String child, String parent, double weight) {
        String childToken = normalizeConcept(child);
        String parentToken = normalizeConcept(parent);
        if (childToken == null || parentToken == null || childToken.equals(parentToken)) {
            return;
        }
        double bounded = weight;
        if (Double.isNaN(bounded) || bounded <= 0.0) {
            return;
        }
        if (bounded > 1.0) {
            bounded = 1.0;
        }
        ConceptSeed childSeed = seeds.computeIfAbsent(childToken, ConceptSeed::new);
        seeds.computeIfAbsent(parentToken, ConceptSeed::new);
        childSeed.parents.put(parentToken, bounded);
    }

    private static String normalizeConcept(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = SignalNormalizer.normalizeOne(raw);
        if (normalized == null || normalized.isBlank()) {
            return null;
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static Suggestion suggestCanonical(String rawToken) {
        String raw = SignalNormalizer.normalizeOne(rawToken);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (BASE_ALIAS_TO_CANONICAL.containsKey(raw) || DYNAMIC_ALIAS_TO_CANONICAL.containsKey(raw)
                || DYNAMIC_CANONICAL_CONCEPTS.contains(raw) || BASE_CONCEPTS.containsKey(raw)) {
            return null;
        }

        LinkedHashSet<String> candidateConcepts = new LinkedHashSet<>(BASE_CONCEPTS.keySet());
        candidateConcepts.addAll(DYNAMIC_CANONICAL_CONCEPTS);
        if (candidateConcepts.isEmpty()) {
            return null;
        }

        String[] rawParts = raw.split("_+");
        String best = null;
        double bestScore = -1.0;
        for (String concept : candidateConcepts) {
            if (concept == null || concept.isBlank()) {
                continue;
            }
            double score = similarityScore(raw, rawParts, concept);
            if (score > bestScore) {
                bestScore = score;
                best = concept;
            }
        }
        if (best == null || bestScore < SUGGESTION_MIN_SCORE) {
            return null;
        }
        return new Suggestion(best, bestScore);
    }

    private static double similarityScore(String raw, String[] rawParts, String concept) {
        if (raw == null || concept == null) {
            return 0.0;
        }
        if (raw.equals(concept)) {
            return 1.0;
        }
        double direct = normalizedEditSimilarity(raw, concept);

        String rawStem = stemToken(raw);
        String conceptStem = stemToken(concept);
        double stemmed = rawStem.equals(conceptStem) ? 0.98 : normalizedEditSimilarity(rawStem, conceptStem);

        String[] conceptParts = concept.split("_+");
        int shared = 0;
        for (String left : rawParts) {
            if (left == null || left.isBlank()) {
                continue;
            }
            for (String right : conceptParts) {
                if (right == null || right.isBlank()) {
                    continue;
                }
                if (left.equals(right) || stemToken(left).equals(stemToken(right))) {
                    shared += 1;
                    break;
                }
            }
        }
        int denom = Math.max(rawParts.length, conceptParts.length);
        double overlap = denom <= 0 ? 0.0 : ((double) shared / (double) denom);

        // Strong boost for composite wrappers containing an exact canonical token
        // (e.g., cooking_homemade_meals -> cooking, casual_gaming -> gaming).
        double compositeContainment = 0.0;
        if (conceptParts.length == 1 && rawParts.length > 1) {
            String single = conceptParts[0];
            for (String part : rawParts) {
                if (single.equals(part)) {
                    compositeContainment = 0.94;
                    break;
                }
                if (stemToken(single).equals(stemToken(part))) {
                    compositeContainment = Math.max(compositeContainment, 0.92);
                }
            }
        }

        return Math.max(compositeContainment, Math.max(stemmed, Math.max(direct, overlap)));
    }

    private static String stemToken(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        String t = token;
        boolean strippedSuffix = false;
        if (t.endsWith("ing") && t.length() > 5) {
            t = t.substring(0, t.length() - 3);
            strippedSuffix = true;
        } else if (t.endsWith("ed") && t.length() > 4) {
            t = t.substring(0, t.length() - 2);
            strippedSuffix = true;
        } else if (t.endsWith("es") && t.length() > 4) {
            t = t.substring(0, t.length() - 2);
            strippedSuffix = true;
        } else if (t.endsWith("s") && t.length() > 3) {
            t = t.substring(0, t.length() - 1);
            strippedSuffix = true;
        }
        if (strippedSuffix && t.length() >= 2 && t.charAt(t.length() - 1) == t.charAt(t.length() - 2)) {
            t = t.substring(0, t.length() - 1);
        }
        t = t.replaceAll("_(activity|session|fandom|development|direction|goal|goals|environment|lifestyle)$", "");
        return t;
    }

    private static double normalizedEditSimilarity(String a, String b) {
        if (a == null || b == null) {
            return 0.0;
        }
        if (a.equals(b)) {
            return 1.0;
        }
        int max = Math.max(a.length(), b.length());
        if (max == 0) {
            return 1.0;
        }
        int distance = levenshteinDistance(a, b);
        return Math.max(0.0, 1.0 - ((double) distance / (double) max));
    }

    private static int levenshteinDistance(String a, String b) {
        if (a == null || b == null) {
            return Integer.MAX_VALUE;
        }
        int n = a.length();
        int m = b.length();
        if (n == 0) {
            return m;
        }
        if (m == 0) {
            return n;
        }

        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int j = 0; j <= m; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                char cb = b.charAt(j - 1);
                int cost = ca == cb ? 0 : 1;
                int deletion = prev[j] + 1;
                int insertion = cur[j - 1] + 1;
                int substitution = prev[j - 1] + cost;
                cur[j] = Math.min(Math.min(deletion, insertion), substitution);
            }
            int[] swap = prev;
            prev = cur;
            cur = swap;
        }
        return prev[m];
    }
}
