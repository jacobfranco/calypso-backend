package now.calypso.backendapi.signals;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.openai.client.OpenAIClient;

import now.calypso.backendapi.llm.OpenAIJson;

public final class SignalExtractor {
    private static final ObjectMapper JSON = new ObjectMapper();

    // Tunables
    private static final int CHUNK_MAX_CHARS = 800;
    private static final int PER_CALL_MAX = 12; // ask model for up to 12 per call
    private static final int PER_CHUNK_PASSES = 3; // try a few times until saturation
    private static final int GLOBAL_SOFT_CAP = 200; // safety stop

    private SignalExtractor() {
    }

    public static List<String> extract(OpenAIClient openAI, String text) {
        if (text == null || text.isBlank())
            return List.of();

        List<String> chunks = chunk(text, CHUNK_MAX_CHARS);
        LinkedHashSet<String> acc = new LinkedHashSet<>();

        for (String c : chunks) {
            for (int i = 0; i < PER_CHUNK_PASSES && acc.size() < GLOBAL_SOFT_CAP; i++) {
                List<String> raw = call(openAI, c, PER_CALL_MAX, acc);
                List<String> norm = SignalNormalizer.normalizeTokens(raw);
                boolean any = false;
                for (String t : norm)
                    if (acc.add(t))
                        any = true;
                if (!any)
                    break; // saturated for this chunk
            }
            if (acc.size() >= GLOBAL_SOFT_CAP)
                break;
        }

        return new ArrayList<>(acc);
    }

    private static List<String> chunk(String text, int maxChars) {
        text = text.trim();
        if (text.length() <= maxChars)
            return List.of(text);
        List<String> out = new ArrayList<>();
        String[] sentences = text.split("(?<=[.!?])\\s+");
        StringBuilder buf = new StringBuilder();
        for (String s : sentences) {
            if (buf.length() + s.length() + 1 > maxChars) {
                if (buf.length() > 0) {
                    out.add(buf.toString());
                    buf.setLength(0);
                }
            }
            if (s.length() > maxChars) {
                for (int i = 0; i < s.length(); i += maxChars)
                    out.add(s.substring(i, Math.min(s.length(), i + maxChars)));
            } else {
                if (buf.length() > 0)
                    buf.append(' ');
                buf.append(s);
            }
        }
        if (buf.length() > 0)
            out.add(buf.toString());
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> call(OpenAIClient openAI, String text, int maxSignals, Set<String> alreadyHave) {
        try {
            String system = """
                    You extract concise dating preference signals from text.
                    Return STRICT JSON: {"signals":["<short phrase>", ...]}.
                    Requirements:
                    - Up to %d items.
                    - Each ≤ 4 words, lowercase natural language (e.g., "likes doctors", "dislikes smoking").
                    - Include clear polarity if present (likes/dislikes).
                    - Do not include duplicates or items already in 'already_have'.
                    - If nothing clear, return {"signals": []}.
                    """.formatted(maxSignals);

            String user = """
                    text: %s

                    already_have: %s

                    Return JSON ONLY (no prose).
                    """.formatted(jsonQuote(text), JSON.writeValueAsString(alreadyHave));

            String raw = OpenAIJson.call(openAI, system, user);
            Map<String, Object> parsed = JSON.readValue(raw, new TypeReference<>() {
            });
            Object arr = parsed.get("signals");
            if (!(arr instanceof List<?> list))
                return List.of();

            List<String> out = new ArrayList<>();
            for (Object o : list)
                if (o != null)
                    out.add(String.valueOf(o));
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String jsonQuote(String s) {
        try {
            return JSON.writeValueAsString(s);
        } catch (Exception e) {
            return "\"" + s.replace("\"", "\\\"") + "\"";
        }
    }
}
