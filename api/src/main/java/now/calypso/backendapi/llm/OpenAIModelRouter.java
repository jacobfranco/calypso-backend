package now.calypso.backendapi.llm;

import java.util.ArrayList;
import java.util.List;

import com.openai.models.ChatModel;

public final class OpenAIModelRouter {
    private static final String FALLBACK_1_ENV = "CALYPSO_MODEL_FALLBACK_1";
    private static final String FALLBACK_2_ENV = "CALYPSO_MODEL_FALLBACK_2";
    private static final String DEFAULT_FALLBACK_1 = "gpt-5-mini";
    private static final String DEFAULT_FALLBACK_2 = "gpt-4.1-mini";

    private OpenAIModelRouter() {
    }

    public static List<ChatModel> modelChain(String primaryEnv, String defaultPrimary) {
        ArrayList<ChatModel> chain = new ArrayList<>();
        addUnique(chain, resolveModel(System.getenv(primaryEnv), defaultPrimary));
        addUnique(chain, resolveModel(System.getenv(FALLBACK_1_ENV), DEFAULT_FALLBACK_1));
        addUnique(chain, resolveModel(System.getenv(FALLBACK_2_ENV), DEFAULT_FALLBACK_2));
        return chain;
    }

    public static ChatModel resolveModel(String rawMaybe, String fallback) {
        String normalized = rawMaybe == null ? null : rawMaybe.trim();
        if (normalized == null || normalized.isEmpty()) {
            normalized = fallback;
        }
        return ChatModel.of(normalized);
    }

    private static void addUnique(List<ChatModel> out, ChatModel candidate) {
        if (candidate == null) {
            return;
        }
        for (ChatModel existing : out) {
            if (existing != null && existing.asString().equals(candidate.asString())) {
                return;
            }
        }
        out.add(candidate);
    }
}
