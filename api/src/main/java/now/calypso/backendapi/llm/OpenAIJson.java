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
        ChatModel pinned = PINNED_MODEL;
        if (pinned != null) {
            try {
                String raw = callPlainResponses(client, systemText, userText, pinned);
                if (raw != null && !raw.isBlank()) {
                    return raw;
                }
            } catch (Exception ex) {
                LOG.warn("Pinned signal extraction model {} failed; unpinning and retrying chain.",
                        pinned.asString(), ex);
            }
            PINNED_MODEL = null;
        }

        Exception lastError = null;
        for (ChatModel model : OpenAIModelRouter.modelChain(MODEL_ENV, MODEL_DEFAULT)) {
            if (pinned != null && model != null
                    && model.asString().equals(pinned.asString())) {
                continue;
            }
            try {
                String raw = callPlainResponses(client, systemText, userText, model);
                if (raw != null && !raw.isBlank()) {
                    PINNED_MODEL = model;
                    return raw;
                }
                LOG.info("Signal extraction model {} returned blank output.", model.asString());
            } catch (Exception ex) {
                lastError = ex;
                LOG.warn("Signal extraction call failed with model {}. Trying fallback model if available.",
                        model.asString(), ex);
            }
        }
        if (lastError != null) {
            LOG.warn("Signal extraction exhausted model chain; returning empty payload.");
        }
        return EMPTY_RESULT;
    }

    private static String callPlainResponses(OpenAIClient client, String systemText, String userText, ChatModel model) {
        if (client == null)
            return EMPTY_RESULT;
        ResponseCreateParams params = ResponseCreateParams.builder()
                .model(model)
                .instructions(systemText)
                .input(userText)
                .temperature(0.1)
                .maxOutputTokens(320L)
                .build();
        Response resp = client.responses().create(params);
        String raw = collectOutputText(resp);
        if (raw == null)
            return EMPTY_RESULT;
        return raw.trim();
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
