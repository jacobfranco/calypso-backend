package now.calypso.backendapi.pojos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PostSignalsRequest {
    public List<String> tokens;
    public String text;
    public String source;
    public String context;

    public List<String> safeTokens() {
        if (tokens == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(tokens);
    }

    public boolean hasTokens() {
        return tokens != null && !tokens.isEmpty();
    }

    public boolean hasText() {
        return text != null && !text.isBlank();
    }

    public String sourceOrDefault() {
        return (source == null || source.isBlank()) ? "manual" : source.trim();
    }

    public String contextOrNull() {
        return (context == null || context.isBlank()) ? null : context.trim();
    }
}
