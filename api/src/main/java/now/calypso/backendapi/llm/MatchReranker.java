package now.calypso.backendapi.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.models.ChatModel;
import com.openai.models.responses.StructuredResponse;
import com.openai.models.responses.StructuredResponseCreateParams;
import com.openai.models.responses.StructuredResponseOutputItem;
import com.openai.models.responses.StructuredResponseOutputMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import now.calypso.backendapi.silhouette.SilhouetteDigest;
import now.calypso.backendapi.silhouette.SilhouetteState;

public final class MatchReranker {
    private static final Logger LOG = LoggerFactory.getLogger(MatchReranker.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MODEL_ENV = "CALYPSO_MODEL_MATCH_RERANK";
    private static final String MODEL_DEFAULT = "gpt-5.4-mini";

    private static final String SYSTEM_PROMPT = """
            You are Calypso's match reranker.

            You evaluate whether candidates should be ranked highly as potential matches.

            You are given compressed silhouettes for a viewer and multiple candidates.
            Each silhouette contains one or more modes. A mode is a coherent relationship configuration: how the user may show up, what they are drawn to, what creates spark, what sustains connection, what aesthetics resonate, and what tends to repel them.

            Important principles:
            - Do not treat a user as one fixed personality.
            - Compare mode pairs, not whole users.
            - A strong match requires bidirectional fit:
              viewer.self should fit candidate.seeking
              candidate.self should fit viewer.seeking
            - Separate spark from sustainability.
            - Spark means immediate attraction, intrigue, chemistry, aesthetic resonance, or curiosity.
            - Sustainability means the pairing is likely to remain emotionally workable over time.
            - Do not over-penalize sparse data.
            - If either profile is sparse, favor plausible exploration and learning value.
            - Anti-pattern conflicts matter more when confidence is high.
            - Do not moralize or pathologize users.
            - Do not expose sensitive psychological labels.
            - Prefer concrete reasoning grounded in the supplied silhouettes.
            - If a trait, need, anti-pattern, or attraction pattern is not present in the silhouettes or shared signals, do not infer it strongly. Mark it as missingInfo instead.

            Return JSON only in this exact schema:
            {"rankedCandidates":[{"candidateId":"...","finalScore":0.0,"sparkScore":0.0,"sustainabilityScore":0.0,"learningValueScore":0.0,"confidence":0.0,"bestModePair":{"viewerModeId":"...","candidateModeId":"..."},"fitSummaryInternal":"...","whyItWorks":[],"risks":[],"recommendedUse":"rank_high","conversationSeeds":[],"missingInfo":[]}]}

            Score rules:
            - All scores must be 0.0 to 1.0.
            - candidateId must match supplied candidate ids exactly.
            - recommendedUse must be rank_high, rank_mid, explore, or deprioritize.
            - Keep fitSummaryInternal under 160 characters.
            - Keep arrays short, concrete, and grounded in supplied data.
            - No markdown, no prose outside JSON.
            """;

    private static volatile Function<RerankRequest, RerankResult> TEST_OVERRIDE = null;

    private MatchReranker() {
    }

    public static void setTestOverride(Function<RerankRequest, RerankResult> override) {
        TEST_OVERRIDE = override;
    }

    public static void clearTestOverride() {
        TEST_OVERRIDE = null;
    }

    public static boolean hasTestOverride() {
        return TEST_OVERRIDE != null;
    }

    public static RerankResult rerank(OpenAIClient client, RerankRequest request) {
        Function<RerankRequest, RerankResult> override = TEST_OVERRIDE;
        if (override != null) {
            return sanitize(override.apply(request), request);
        }
        if (client == null || request == null || request.candidates == null || request.candidates.isEmpty()) {
            return emptyResult();
        }
        String input = buildInput(request);
        Exception lastError = null;
        for (ChatModel model : OpenAIModelRouter.modelChain(MODEL_ENV, MODEL_DEFAULT)) {
            long startedAt = System.currentTimeMillis();
            try {
                StructuredResponseCreateParams<RerankResult> params = StructuredResponseCreateParams
                        .<RerankResult>builder()
                        .model(model)
                        .instructions(SYSTEM_PROMPT)
                        .input(input)
                        .temperature(0.10)
                        .maxOutputTokens(1400L)
                        .text(RerankResult.class)
                        .build();
                StructuredResponse<RerankResult> response = client.responses().create(params);
                long latencyMs = Math.max(0L, System.currentTimeMillis() - startedAt);
                LlmTelemetry.recordStructuredResponse(
                        "tier3_mode_pair_rerank",
                        nonBlank(request.surface, "unknown"),
                        null,
                        model,
                        response,
                        latencyMs,
                        1400L);
                RerankResult parsed = extractPayload(response);
                if (parsed == null) {
                    continue;
                }
                return sanitize(parsed, request);
            } catch (Exception ex) {
                lastError = ex;
                LOG.warn("Mode-pair match reranker failed with model {}. Trying fallback if available.",
                        model.asString(), ex);
                long latencyMs = Math.max(0L, System.currentTimeMillis() - startedAt);
                LlmTelemetry.recordFailure(
                        "tier3_mode_pair_rerank",
                        nonBlank(request.surface, "unknown"),
                        null,
                        model,
                        latencyMs,
                        1400L,
                        ex);
            }
        }
        if (lastError != null) {
            LOG.warn("Mode-pair match reranker exhausted model chain; using stage2 order.");
        }
        return emptyResult();
    }

    private static RerankResult emptyResult() {
        return new RerankResult();
    }

    private static String buildInput(RerankRequest request) {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("viewer", request.viewer == null ? new SilhouetteDigest().toMap() : request.viewer.toMap());
        ArrayList<Object> candidates = new ArrayList<>();
        if (request.candidates != null) {
            for (Candidate candidate : request.candidates) {
                if (candidate == null || candidate.candidateId == null || candidate.candidateId.isBlank()) {
                    continue;
                }
                LinkedHashMap<String, Object> item = new LinkedHashMap<>();
                item.put("candidateId", candidate.candidateId.trim());
                item.put("digest", candidate.digest == null ? new SilhouetteDigest().toMap() : candidate.digest.toMap());
                item.put("stage2Normalized", clamp01(candidate.stage2Normalized));
                item.put("signals", signalMaps(candidate.signals));
                item.put("sharedSignals", stringList(candidate.sharedSignals, 12, 48));
                candidates.add(item);
            }
        }
        root.put("candidates", candidates);
        LinkedHashMap<String, Object> context = new LinkedHashMap<>();
        context.put("rankingGoal", rankingGoal(request.rankingGoal));
        context.put("hardFiltersAlreadyPassed", true);
        context.put("viewerMaturity", request.viewer == null ? "empty" : SilhouetteState.normalizeMaturity(request.viewer.maturity));
        context.put("surface", nonBlank(request.surface, "unknown"));
        context.put("viewerSignals", signalMaps(request.viewerSignals));
        root.put("context", context);
        try {
            return JSON.writeValueAsString(root);
        } catch (Exception ignored) {
            return root.toString();
        }
    }

    private static List<Object> signalMaps(List<Signal> signals) {
        ArrayList<Object> out = new ArrayList<>();
        if (signals == null) {
            return out;
        }
        for (Signal signal : signals) {
            if (signal == null || signal.token == null || signal.token.isBlank()) {
                continue;
            }
            LinkedHashMap<String, Object> item = new LinkedHashMap<>();
            item.put("token", signal.token.trim());
            item.put("intent", nonBlank(signal.intent, "self"));
            item.put("weight", clamp01(signal.weight));
            item.put("valence", clampSigned(signal.valence));
            out.add(item);
        }
        return out;
    }

    private static List<String> stringList(List<String> values, int maxItems, int maxChars) {
        ArrayList<String> out = new ArrayList<>();
        if (values == null) {
            return out;
        }
        for (String value : values) {
            String text = value == null ? "" : value.trim();
            if (text.isBlank()) {
                continue;
            }
            if (text.length() > maxChars) {
                text = text.substring(0, maxChars).trim();
            }
            out.add(text);
            if (out.size() >= maxItems) {
                break;
            }
        }
        return out;
    }

    private static String rankingGoal(String raw) {
        if (raw == null || raw.isBlank()) {
            return "balance";
        }
        String normalized = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "discover", "balance", "precision" -> normalized;
            case "exploratory" -> "discover";
            case "focused" -> "precision";
            default -> "balance";
        };
    }

    private static String nonBlank(String raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static RerankResult extractPayload(StructuredResponse<RerankResult> response) {
        if (response == null || response.output() == null) {
            return null;
        }
        Optional<RerankResult> first = response.output().stream()
                .map(StructuredResponseOutputItem::message)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .flatMap(MatchReranker::payloadsFromMessage)
                .findFirst();
        return first.orElse(null);
    }

    private static Stream<RerankResult> payloadsFromMessage(StructuredResponseOutputMessage<RerankResult> message) {
        if (message == null || message.content() == null) {
            return Stream.empty();
        }
        return message.content().stream()
                .map(content -> content == null ? null : content.outputText().orElse(null))
                .filter(java.util.Objects::nonNull);
    }

    private static RerankResult sanitize(RerankResult raw, RerankRequest request) {
        RerankResult out = new RerankResult();
        if (raw == null || request == null || request.candidates == null || request.candidates.isEmpty()) {
            return out;
        }
        List<Decision> decisions = raw.rankedCandidates == null ? List.of() : raw.rankedCandidates;
        if (decisions.isEmpty()) {
            return out;
        }
        Set<String> allowedIds = new LinkedHashSet<>();
        for (Candidate candidate : request.candidates) {
            if (candidate == null || candidate.candidateId == null || candidate.candidateId.isBlank()) {
                continue;
            }
            allowedIds.add(candidate.candidateId.trim());
        }
        if (allowedIds.isEmpty()) {
            return out;
        }
        for (Decision decision : decisions) {
            if (decision == null || decision.candidateId == null || decision.candidateId.isBlank()) {
                continue;
            }
            String id = decision.candidateId.trim();
            if (!allowedIds.contains(id)) {
                continue;
            }
            Decision normalized = new Decision();
            normalized.candidateId = id;
            normalized.finalScore = clamp01(decision.finalScore);
            normalized.sparkScore = clamp01(decision.sparkScore);
            normalized.sustainabilityScore = clamp01(decision.sustainabilityScore);
            normalized.learningValueScore = clamp01(decision.learningValueScore);
            normalized.confidence = clamp01(decision.confidence);
            normalized.bestModePair = sanitizeModePair(decision.bestModePair);
            normalized.fitSummaryInternal = trim(decision.fitSummaryInternal, 160);
            normalized.whyItWorks = stringList(decision.whyItWorks, 4, 120);
            normalized.risks = stringList(decision.risks, 4, 120);
            normalized.recommendedUse = recommendedUse(decision.recommendedUse);
            normalized.conversationSeeds = stringList(decision.conversationSeeds, 4, 120);
            normalized.missingInfo = stringList(decision.missingInfo, 5, 120);
            out.rankedCandidates.add(normalized);
        }
        return out;
    }

    private static BestModePair sanitizeModePair(BestModePair raw) {
        BestModePair out = new BestModePair();
        if (raw == null) {
            return out;
        }
        out.viewerModeId = trim(raw.viewerModeId, 80);
        out.candidateModeId = trim(raw.candidateModeId, 80);
        return out;
    }

    private static String recommendedUse(String raw) {
        if (raw == null || raw.isBlank()) {
            return "explore";
        }
        String normalized = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "rank_high", "rank_mid", "explore", "deprioritize" -> normalized;
            default -> "explore";
        };
    }

    private static String trim(String raw, int maxChars) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.length() <= maxChars) {
            return trimmed;
        }
        return trimmed.substring(0, maxChars).trim();
    }

    private static Double clamp01(Double value) {
        if (value == null || !Double.isFinite(value.doubleValue())) {
            return 0.5;
        }
        double v = value.doubleValue();
        if (v < 0.0) {
            return 0.0;
        }
        if (v > 1.0) {
            return 1.0;
        }
        return v;
    }

    private static Double clampSigned(Double value) {
        if (value == null || !Double.isFinite(value.doubleValue())) {
            return 0.0;
        }
        double v = value.doubleValue();
        if (v < -1.0) {
            return -1.0;
        }
        if (v > 1.0) {
            return 1.0;
        }
        return v;
    }

    public static final class RerankRequest {
        public String surface;
        public String rankingGoal;
        public SilhouetteDigest viewer;
        public List<Signal> viewerSignals = new ArrayList<>();
        public List<Candidate> candidates = new ArrayList<>();
    }

    public static final class Candidate {
        public String candidateId;
        public Double stage2Normalized;
        public SilhouetteDigest digest;
        public List<Signal> signals = new ArrayList<>();
        public List<String> sharedSignals = new ArrayList<>();
    }

    public static final class Signal {
        public String token;
        public String intent;
        public Double weight;
        public Double valence;
    }

    public static final class RerankResult {
        public List<Decision> rankedCandidates = new ArrayList<>();
    }

    public static final class Decision {
        public String candidateId;
        public Double finalScore;
        public Double sparkScore;
        public Double sustainabilityScore;
        public Double learningValueScore;
        public Double confidence;
        public BestModePair bestModePair;
        public String fitSummaryInternal;
        public List<String> whyItWorks = new ArrayList<>();
        public List<String> risks = new ArrayList<>();
        public String recommendedUse;
        public List<String> conversationSeeds = new ArrayList<>();
        public List<String> missingInfo = new ArrayList<>();
    }

    public static final class BestModePair {
        public String viewerModeId;
        public String candidateModeId;
    }
}
