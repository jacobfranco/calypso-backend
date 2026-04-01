package now.calypso.backendapi.agent;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.openai.client.OpenAIClient;
import com.openai.models.ChatModel;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputText;

import now.calypso.backend.data.AgentSession;
import now.calypso.backend.data.AgentMessage;
import now.calypso.backendapi.llm.OpenAIModelRouter;

public final class AgentResponder {
    private static final Logger LOG = LoggerFactory.getLogger(AgentResponder.class);
    private static final AtomicReference<Function<AgentSession, String>> TEST_OVERRIDE = new AtomicReference<>();
    private static final String MODEL_ENV = "CALYPSO_MODEL_AGENT";
    private static final String MODEL_DEFAULT = "gpt-5.4-mini";

    private AgentResponder() {
    }

    public static void setTestOverride(Function<AgentSession, String> override) {
        TEST_OVERRIDE.set(override);
    }

    public static void clearTestOverride() {
        TEST_OVERRIDE.set(null);
    }

    public static String generate(OpenAIClient client, AgentSession session) {
        Function<AgentSession, String> override = TEST_OVERRIDE.get();
        if (override != null) {
            return override.apply(copySession(session));
        }
        if (client == null)
            return AgentPrompts.fallbackResponse();
        try {
            List<AgentMessage> messages = (session == null || session.getMessages() == null)
                    ? List.of()
                    : session.getMessages();
            Exception lastError = null;
            for (ChatModel model : OpenAIModelRouter.modelChain(MODEL_ENV, MODEL_DEFAULT)) {
                try {
                    ResponseCreateParams params = ResponseCreateParams.builder()
                            .model(model)
                            .instructions(AgentPrompts.systemPrompt())
                            .input(AgentPrompts.buildUserInput(messages))
                            .temperature(0.65)
                            .maxOutputTokens(400L)
                            .build();
                    Response resp = client.responses().create(params);
                    String text = collectOutputText(resp);
                    if (text == null || text.isBlank())
                        continue;
                    return text.trim();
                } catch (Exception ex) {
                    lastError = ex;
                    LOG.warn("Agent generation failed with model {}. Trying fallback if available.", model.asString(), ex);
                }
            }
            if (lastError != null) {
                LOG.warn("Agent generation exhausted model chain; returning fallback response.");
            }
            return AgentPrompts.fallbackResponse();
        } catch (Exception ex) {
            LOG.warn("Agent response generation failed; returning fallback.", ex);
            return AgentPrompts.fallbackResponse();
        }
    }

    private static AgentSession copySession(AgentSession session) {
        return (session == null) ? null : new AgentSession(session);
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
}
