package now.calypso.backendapi.llm;

import com.openai.client.OpenAIClient;
import com.openai.models.ChatModel;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputText;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper that asks the Responses API for JSON-only output and returns the raw
 * payload SignalExtractor expects.
 */
public final class OpenAIJson {
    private static final Logger LOG = LoggerFactory.getLogger(OpenAIJson.class);
    private static final String EMPTY_RESULT = "{\"signals\":[]}";
    private static final String MODEL_ENV = "CALYPSO_MODEL_SIGNAL_EXTRACT";
    private static final String MODEL_DEFAULT = "gpt-5-nano";

    private static volatile BiFunction<String, String, String> TEST_OVERRIDE = null;
    private static volatile ChatModel PINNED_MODEL = null;

    public static final class CallSpec {
        public final String stage;
        public final String surface;
        public final String promptId;
        public final long maxOutputTokens;

        private CallSpec(String stage, String surface, String promptId, long maxOutputTokens) {
            this.stage = stage == null || stage.isBlank() ? "signal_extract" : stage.trim();
            this.surface = surface == null || surface.isBlank() ? "generic" : surface.trim();
            this.promptId = promptId == null ? null : promptId.trim();
            this.maxOutputTokens = maxOutputTokens <= 0L ? 320L : maxOutputTokens;
        }

        public static CallSpec signalExtract(String surface, String promptId, long maxOutputTokens) {
            return new CallSpec("signal_extract", surface, promptId, maxOutputTokens);
        }

        public static CallSpec silhouettePatch(String surface, String promptId, long maxOutputTokens) {
            return new CallSpec("silhouette_patch", surface, promptId, maxOutputTokens);
        }

        public static CallSpec custom(String stage, String surface, String promptId, long maxOutputTokens) {
            return new CallSpec(stage, surface, promptId, maxOutputTokens);
        }
    }

    private static final class CallResult {
        final String raw;
        final Response response;

        CallResult(String raw, Response response) {
            this.raw = raw;
            this.response = response;
        }
    }

    /** Single signal object in structured output. */
    public static class SignalOut {
        public String token;
        public String intent;
        public Double valence;
    }

    /** Structured-output target (the model is constrained to this shape). */
    public static class SignalsOut {
        public List<SignalOut> signals = new ArrayList<>();
    }

    public static String call(OpenAIClient client, String system, String user) {
        return call(client, system, user, CallSpec.signalExtract("generic", null, 320L));
    }

    public static String call(OpenAIClient client, String system, String user, CallSpec spec) {
        BiFunction<String, String, String> override = TEST_OVERRIDE;
        if (override != null) {
            return override.apply(system, user);
        }
        if (client == null) {
            LOG.warn("OpenAI client is null; returning empty signal payload.");
            return EMPTY_RESULT;
        }

        String systemText = system == null ? "" : system;
        String userText = user == null ? "" : user;
        long promptChars = (long) systemText.length() + (long) userText.length();
        CallSpec effectiveSpec = spec == null ? CallSpec.signalExtract("generic", null, 320L) : spec;
        ChatModel pinned = PINNED_MODEL;
        if (pinned != null) {
            long startedAt = System.currentTimeMillis();
            try {
                CallResult result = callPlainResponses(client, systemText, userText, pinned, effectiveSpec.maxOutputTokens);
                long latencyMs = Math.max(0L, System.currentTimeMillis() - startedAt);
                LlmTelemetry.recordResponse(
                        effectiveSpec.stage,
                        effectiveSpec.surface,
                        effectiveSpec.promptId,
                        pinned,
                        result.response,
                        latencyMs,
                        effectiveSpec.maxOutputTokens,
                        promptChars);
                String raw = result.raw;
                if (raw != null && !raw.isBlank()) {
                    return raw;
                }
            } catch (Exception ex) {
                LOG.warn("Pinned signal extraction model {} failed; unpinning and retrying chain.",
                        pinned.asString(), ex);
                long latencyMs = Math.max(0L, System.currentTimeMillis() - startedAt);
                LlmTelemetry.recordFailure(
                        effectiveSpec.stage,
                        effectiveSpec.surface,
                        effectiveSpec.promptId,
                        pinned,
                        latencyMs,
                        effectiveSpec.maxOutputTokens,
                        ex,
                        promptChars);
            }
            PINNED_MODEL = null;
        }

        Exception lastError = null;
        for (ChatModel model : OpenAIModelRouter.modelChain(MODEL_ENV, MODEL_DEFAULT)) {
            if (pinned != null && model != null
                    && model.asString().equals(pinned.asString())) {
                continue;
            }
            long startedAt = System.currentTimeMillis();
            try {
                CallResult result = callPlainResponses(client, systemText, userText, model, effectiveSpec.maxOutputTokens);
                long latencyMs = Math.max(0L, System.currentTimeMillis() - startedAt);
                LlmTelemetry.recordResponse(
                        effectiveSpec.stage,
                        effectiveSpec.surface,
                        effectiveSpec.promptId,
                        model,
                        result.response,
                        latencyMs,
                        effectiveSpec.maxOutputTokens,
                        promptChars);
                String raw = result.raw;
                if (raw != null && !raw.isBlank()) {
                    PINNED_MODEL = model;
                    return raw;
                }
                LOG.info("Signal extraction model {} returned blank output.", model.asString());
            } catch (Exception ex) {
                lastError = ex;
                LOG.warn("Signal extraction call failed with model {}. Trying fallback model if available.",
                        model.asString(), ex);
                long latencyMs = Math.max(0L, System.currentTimeMillis() - startedAt);
                LlmTelemetry.recordFailure(
                        effectiveSpec.stage,
                        effectiveSpec.surface,
                        effectiveSpec.promptId,
                        model,
                        latencyMs,
                        effectiveSpec.maxOutputTokens,
                        ex,
                        promptChars);
            }
        }
        if (lastError != null) {
            LOG.warn("Signal extraction exhausted model chain; returning empty payload.");
        }
        return EMPTY_RESULT;
    }

    private static CallResult callPlainResponses(OpenAIClient client, String systemText, String userText, ChatModel model,
            long maxOutputTokens) {
        if (client == null)
            return new CallResult(EMPTY_RESULT, null);
        ResponseCreateParams params = ResponseCreateParams.builder()
                .model(model)
                .instructions(systemText)
                .input(userText)
                .temperature(0.1)
                .maxOutputTokens(maxOutputTokens <= 0L ? 320L : maxOutputTokens)
                .build();
        Response resp = client.responses().create(params);
        String raw = collectOutputText(resp);
        if (raw == null)
            return new CallResult(EMPTY_RESULT, resp);
        return new CallResult(raw.trim(), resp);
    }

    private static String collectOutputText(Response resp) {
        if (resp == null || resp.output() == null)
            return "";
        StringBuilder buf = new StringBuilder();
        for (ResponseOutputItem item : resp.output()) {
            if (item == null)
                continue;
            Optional<ResponseOutputMessage> msg = item.message();
            if (msg.isEmpty())
                continue;
            for (ResponseOutputMessage.Content content : msg.get().content()) {
                if (content == null)
                    continue;
                Optional<ResponseOutputText> text = content.outputText();
                if (text.isEmpty())
                    continue;
                String chunk = text.get().text();
                if (chunk != null)
                    buf.append(chunk);
            }
        }
        return buf.toString().trim();
    }

    public static void setTestOverride(BiFunction<String, String, String> override) {
        TEST_OVERRIDE = override;
    }

    public static void clearTestOverride() {
        TEST_OVERRIDE = null;
    }

    private OpenAIJson() {
    }
}
