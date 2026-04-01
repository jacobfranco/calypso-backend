package now.calypso.backendapi.llm;

import com.openai.client.OpenAIClient;
import com.openai.models.ChatModel;
import com.openai.models.responses.StructuredResponse;
import com.openai.models.responses.StructuredResponseCreateParams;
import com.openai.models.responses.StructuredResponseOutputItem;
import com.openai.models.responses.StructuredResponseOutputMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

public final class MatchReranker {
    private static final Logger LOG = LoggerFactory.getLogger(MatchReranker.class);
    private static final String MODEL_ENV = "CALYPSO_MODEL_MATCH_RERANK";
    private static final String MODEL_DEFAULT = "gpt-5.4-mini";

    private static final String SYSTEM_PROMPT = """
            You are a matchmaking reranker that performs final manual review after deterministic scoring.

            Return JSON only in this exact schema:
            {"decisions":[{"id":"...","compatibility":0.0,"confidence":0.0,"hardBlocker":false,"reason":"..."}]}

            Rules:
            - id must match candidate ids exactly.
            - compatibility is [0,1] where 0.5 means neutral vs stage2.
            - confidence is [0,1] describing certainty in your compatibility estimate.
            - hardBlocker=true only for clear incompatibility signals.
            - reason must be <= 12 words and concrete.
            - Prefer small adjustments around stage2; do not ignore stage2 ranking without strong evidence.
            - Use semantics across related concepts (for example anime/cosplay/geek overlap) when useful.
            - No markdown, no extra keys, no explanations outside JSON.
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
            try {
                StructuredResponseCreateParams<RerankResult> params = StructuredResponseCreateParams
                        .<RerankResult>builder()
                        .model(model)
                        .instructions(SYSTEM_PROMPT)
                        .input(input)
                        .temperature(0.10)
                        .maxOutputTokens(800L)
                        .text(RerankResult.class)
                        .build();
                StructuredResponse<RerankResult> response = client.responses().create(params);
                RerankResult parsed = extractPayload(response);
                if (parsed == null) {
                    continue;
                }
                return sanitize(parsed, request);
            } catch (Exception ex) {
                lastError = ex;
                LOG.warn("Match reranker failed with model {}. Trying fallback if available.", model.asString(), ex);
            }
        }
        if (lastError != null) {
            LOG.warn("Match reranker exhausted model chain; using stage2 order.");
        }
        return emptyResult();
    }

    private static RerankResult emptyResult() {
        return new RerankResult();
    }

    private static String buildInput(RerankRequest request) {
        StringBuilder buf = new StringBuilder();
        buf.append("surface: ").append(nonBlank(request.surface, "unknown")).append("\n");
        buf.append("viewer_signals:\n");
        appendSignals(buf, request.viewerSignals);
        buf.append("candidates:\n");
        if (request.candidates != null) {
            for (Candidate candidate : request.candidates) {
                if (candidate == null || candidate.id == null || candidate.id.isBlank()) {
                    continue;
                }
                buf.append("- id=").append(candidate.id.trim())
                        .append(" stage2=").append(format01(candidate.stage2Normalized))
                        .append("\n");
                appendSignals(buf, candidate.signals);
            }
        }
        return buf.toString();
    }

    private static void appendSignals(StringBuilder buf, List<Signal> signals) {
        if (buf == null) {
            return;
        }
        if (signals == null || signals.isEmpty()) {
            buf.append("  []\n");
            return;
        }
        for (Signal signal : signals) {
            if (signal == null || signal.token == null || signal.token.isBlank()) {
                continue;
            }
            buf.append("  - token=").append(signal.token.trim())
                    .append(" intent=").append(nonBlank(signal.intent, "self"))
                    .append(" weight=").append(format01(signal.weight))
                    .append(" valence=").append(formatSigned(signal.valence))
                    .append("\n");
        }
    }

    private static String format01(Double value) {
        if (value == null || !Double.isFinite(value.doubleValue())) {
            return "0.0";
        }
        double v = value.doubleValue();
        if (v < 0.0) {
            v = 0.0;
        } else if (v > 1.0) {
            v = 1.0;
        }
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    private static String formatSigned(Double value) {
        if (value == null || !Double.isFinite(value.doubleValue())) {
            return "0.0";
        }
        double v = value.doubleValue();
        if (v < -1.0) {
            v = -1.0;
        } else if (v > 1.0) {
            v = 1.0;
        }
        return String.format(java.util.Locale.ROOT, "%.3f", v);
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
        if (raw == null || raw.decisions == null || raw.decisions.isEmpty() || request == null
                || request.candidates == null || request.candidates.isEmpty()) {
            return out;
        }
        Set<String> allowedIds = new LinkedHashSet<>();
        for (Candidate candidate : request.candidates) {
            if (candidate == null || candidate.id == null || candidate.id.isBlank()) {
                continue;
            }
            allowedIds.add(candidate.id.trim());
        }
        if (allowedIds.isEmpty()) {
            return out;
        }
        for (Decision decision : raw.decisions) {
            if (decision == null || decision.id == null || decision.id.isBlank()) {
                continue;
            }
            String id = decision.id.trim();
            if (!allowedIds.contains(id)) {
                continue;
            }
            Decision normalized = new Decision();
            normalized.id = id;
            normalized.compatibility = clamp01(decision.compatibility);
            normalized.confidence = clamp01(decision.confidence);
            normalized.hardBlocker = Boolean.TRUE.equals(decision.hardBlocker);
            String reason = decision.reason == null ? "" : decision.reason.trim();
            if (reason.length() > 120) {
                reason = reason.substring(0, 120).trim();
            }
            normalized.reason = reason;
            out.decisions.add(normalized);
        }
        return out;
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

    public static final class RerankRequest {
        public String surface;
        public List<Signal> viewerSignals = new ArrayList<>();
        public List<Candidate> candidates = new ArrayList<>();
    }

    public static final class Candidate {
        public String id;
        public Double stage2Normalized;
        public List<Signal> signals = new ArrayList<>();
    }

    public static final class Signal {
        public String token;
        public String intent;
        public Double weight;
        public Double valence;
    }

    public static final class RerankResult {
        public List<Decision> decisions = new ArrayList<>();
    }

    public static final class Decision {
        public String id;
        public Double compatibility;
        public Double confidence;
        public Boolean hardBlocker;
        public String reason;
    }
}
