package now.calypso.backendapi.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.models.ChatModel;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.StructuredResponseCreateParams;
import com.openai.models.responses.StructuredResponseOutputItem;
import com.openai.models.responses.StructuredResponseOutputMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper that asks the Responses API for structured output and returns the JSON
 * blob SignalExtractor expects.
 */
public final class OpenAIJson {
    private static final Logger LOG = LoggerFactory.getLogger(OpenAIJson.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String EMPTY_RESULT = "{\"signals\":[]}";

    private static volatile BiFunction<String, String, String> TEST_OVERRIDE = null;

    /** Single signal object in structured output. */
    public static class SignalOut {
        public String token;
        public String intent;
        public Double confidence;
        public Double importance;
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

        try {
            StructuredResponseCreateParams<SignalsOut> params = StructuredResponseCreateParams.<SignalsOut>builder()
                    .model(ChatModel.GPT_4_1_MINI)
                    .instructions(systemText)
                    .input(userText)
                    .temperature(0.1)
                    .maxOutputTokens(512L)
                    .text(SignalsOut.class)
                    .build();

            var resp = client.responses().create(params);
            StructuredResult structured = extractPayload(resp);
            if (structured.hadStructuredContent) {
                return MAPPER.writeValueAsString(structured.payload);
            }
            LOG.info("Model {} did not return structured output; falling back to text parsing.",
                    ChatModel.GPT_5_NANO);
            return callPlainResponses(client, systemText, userText);
        } catch (Exception e) {
            LOG.warn("Structured OpenAI call failed; falling back to text parsing.", e);
            return callPlainResponses(client, systemText, userText);
        }
    }

    private static String callPlainResponses(OpenAIClient client, String systemText, String userText) {
        if (client == null)
            return EMPTY_RESULT;
        try {
            ResponseCreateParams params = ResponseCreateParams.builder()
                    .model(ChatModel.GPT_5_NANO)
                    .instructions(systemText)
                    .input(userText)
                    .temperature(0.1)
                    .maxOutputTokens(512L)
                    .build();
            Response resp = client.responses().create(params);
            String raw = collectOutputText(resp);
            if (raw == null || raw.isBlank())
                return EMPTY_RESULT;
            return raw;
        } catch (Exception ex) {
            LOG.warn("Fallback OpenAI call failed; returning empty payload.", ex);
            return EMPTY_RESULT;
        }
    }

    private static StructuredResult extractPayload(com.openai.models.responses.StructuredResponse<SignalsOut> resp) {
        StructuredResult result = new StructuredResult();
        if (resp == null || resp.output() == null)
            return result;
        Optional<SignalsOut> first = resp.output().stream()
                .map(StructuredResponseOutputItem::message)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .flatMap(OpenAIJson::payloadsFromMessage)
                .findFirst();
        if (first.isPresent()) {
            result.payload = first.get();
            result.hadStructuredContent = true;
        }
        return result;
    }

    private static java.util.stream.Stream<SignalsOut> payloadsFromMessage(
            StructuredResponseOutputMessage<SignalsOut> message) {
        if (message == null || message.content() == null)
            return java.util.stream.Stream.empty();
        return message.content().stream()
                .map(content -> content == null ? null : content.outputText().orElse(null))
                .filter(java.util.Objects::nonNull);
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

    private static final class StructuredResult {
        SignalsOut payload = new SignalsOut();
        boolean hadStructuredContent = false;
    }
}
