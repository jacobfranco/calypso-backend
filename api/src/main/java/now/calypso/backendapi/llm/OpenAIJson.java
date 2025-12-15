package now.calypso.backendapi.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.StructuredChatCompletion;
import com.openai.models.chat.completions.StructuredChatCompletionCreateParams;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * v3.0.3 helper: calls Chat Completions with Structured Outputs and returns
 * a JSON string in the exact shape your extractor expects: {"signals":[...]}.
 */
public final class OpenAIJson {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static volatile BiFunction<String, String, String> TEST_OVERRIDE = null;

    /** Structured-output target (the model is constrained to this shape). */
    public static class SignalsOut {
        public List<String> signals = new ArrayList<>();
    }

    public static String call(OpenAIClient client, String system, String user) {
        BiFunction<String, String, String> override = TEST_OVERRIDE;
        if (override != null) {
            return override.apply(system, user);
        }
        // Start with ChatCompletionCreateParams; calling responseFormat(...) switches
        // the builder type.
        StructuredChatCompletionCreateParams<SignalsOut> params = ChatCompletionCreateParams.builder()
                .model(ChatModel.GPT_4_1_MINI) // pick any v3 model available to your org
                .addSystemMessage(system)
                .addUserMessage(user)
                .responseFormat(SignalsOut.class) // <- replaces old JSON_OBJECT
                .temperature(0.1)
                .maxCompletionTokens(512)
                .build();

        // IMPORTANT: Structured outputs return StructuredChatCompletion<T>, not
        // ChatCompletion.
        StructuredChatCompletion<SignalsOut> resp = client.chat().completions().create(params);

        // In v3, message.content() is Optional<T> where T == SignalsOut
        Optional<SignalsOut> first = resp.choices().stream()
                .map(c -> c.message().content())
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();

        SignalsOut payload = first.orElseGet(SignalsOut::new);

        try {
            return MAPPER.writeValueAsString(payload); // {"signals":[...]}
        } catch (Exception e) {
            return "{\"signals\":[]}";
        }
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
