package now.calypso.backendapi.signals;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;

import now.calypso.backend.data.SignalIntent;
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

    public static List<ExtractedSignal> extractFreeform(OpenAIClient openAI, String text) {
        if (text == null || text.isBlank())
            return List.of();

        List<String> chunks = chunk(text, CHUNK_MAX_CHARS);
        LinkedHashMap<String, ExtractedSignal> acc = new LinkedHashMap<>();

        for (String c : chunks) {
            for (int i = 0; i < PER_CHUNK_PASSES && acc.size() < GLOBAL_SOFT_CAP; i++) {
                List<ExtractedSignal> raw = call(openAI, SignalPrompts.FREEFORM_SYSTEM_PROMPT,
                        SignalPrompts.freeformUserPrompt(c, tokens(acc.values())),
                        Math.min(PER_CALL_MAX, GLOBAL_SOFT_CAP - acc.size()), tokens(acc.values()));
                boolean any = merge(acc, raw);
                if (!any)
                    break;
            }
            if (acc.size() >= GLOBAL_SOFT_CAP)
                break;
        }

        return new ArrayList<>(acc.values());
    }

    public static List<ExtractedSignal> extractFromAgentConversation(OpenAIClient openAI, List<String> conversation,
            Collection<String> alreadyHave) {
        if (conversation == null || conversation.isEmpty())
            return List.of();
        Collection<String> normalizedAlreadyHave = normalizeAlreadyHave(alreadyHave);
        LinkedHashMap<String, ExtractedSignal> acc = new LinkedHashMap<>();
        List<ExtractedSignal> raw = call(openAI, SignalPrompts.AGENT_CHAT_SYSTEM_PROMPT,
                SignalPrompts.agentChatUserPrompt(conversation, normalizedAlreadyHave),
                PER_CALL_MAX, normalizedAlreadyHave);
        merge(acc, raw);
        if (!normalizedAlreadyHave.isEmpty())
            acc.values().removeIf(sig -> normalizedAlreadyHave.contains(sig.token()));
        return new ArrayList<>(acc.values());
    }

    public static List<ExtractedSignal> extractFromPromptAnswer(OpenAIClient openAI, String question, String answer,
            Collection<String> alreadyHave) {
        if ((question == null || question.isBlank()) && (answer == null || answer.isBlank()))
            return List.of();
        Collection<String> normalizedAlreadyHave = normalizeAlreadyHave(alreadyHave);
        List<ExtractedSignal> raw = call(openAI, SignalPrompts.PROMPT_RESPONSE_SYSTEM_PROMPT,
                SignalPrompts.promptResponseUserPrompt(question, answer, normalizedAlreadyHave),
                PER_CALL_MAX, normalizedAlreadyHave);
        LinkedHashMap<String, ExtractedSignal> acc = new LinkedHashMap<>();
        merge(acc, raw);
        if (!normalizedAlreadyHave.isEmpty())
            acc.values().removeIf(sig -> normalizedAlreadyHave.contains(sig.token()));
        return new ArrayList<>(acc.values());
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

    private static boolean merge(LinkedHashMap<String, ExtractedSignal> acc, List<ExtractedSignal> raw) {
        boolean any = false;
        for (ExtractedSignal sig : raw) {
            if (sig == null || sig.token() == null)
                continue;
            String key = key(sig.token(), sig.intent());
            if (!acc.containsKey(key)) {
                acc.put(key, sig);
                any = true;
            }
        }
        return any;
    }

    @SuppressWarnings("unchecked")
    private static List<ExtractedSignal> call(OpenAIClient openAI, String systemPrompt, String userPrompt,
            int maxSignals, Collection<String> alreadyHave) {
        try {
            String system = maybeFormat(systemPrompt, maxSignals);
            String raw = OpenAIJson.call(openAI, system, userPrompt);
            Map<String, Object> parsed = JSON.readValue(raw, new TypeReference<>() {
            });
            Object arr = parsed.get("signals");
            if (!(arr instanceof List<?> list))
                return List.of();

            List<ExtractedSignal> out = new ArrayList<>();
            for (Object o : list) {
                ExtractedSignal sig = parseSignal(o);
                if (sig != null && (alreadyHave == null || !alreadyHave.contains(sig.token())))
                    out.add(sig);
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static ExtractedSignal parseSignal(Object raw) {
        if (raw == null)
            return null;
        if (raw instanceof Map<?, ?> map) {
            Object tokenObj = map.get("token");
            Object intentObj = map.get("intent");
            Object confidenceObj = map.get("confidence");
            String token = tokenObj == null ? null : String.valueOf(tokenObj);
            SignalIntent intent = parseIntent(intentObj);
            Double confidence = parseConfidence(confidenceObj);
            return ExtractedSignal.from(token, intent, confidence);
        }
        return ExtractedSignal.from(String.valueOf(raw), SignalIntent.SELF, null);
    }

    private static SignalIntent parseIntent(Object raw) {
        if (raw == null)
            return SignalIntent.SELF;
        if (raw instanceof SignalIntent intent)
            return intent;
        String s = String.valueOf(raw).trim();
        if (s.isEmpty())
            return SignalIntent.SELF;
        try {
            return SignalIntent.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return SignalIntent.SELF;
        }
    }

    private static Double parseConfidence(Object raw) {
        if (raw == null)
            return null;
        try {
            return Double.valueOf(String.valueOf(raw));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String maybeFormat(String template, int maxSignals) {
        if (template.contains("%"))
            return template.formatted(maxSignals);
        return template;
    }

    private static Collection<String> normalizeAlreadyHave(Collection<String> alreadyHave) {
        if (alreadyHave == null || alreadyHave.isEmpty())
            return List.of();
        return SignalNormalizer.normalizeTokens(alreadyHave);
    }

    private static List<String> tokens(Collection<ExtractedSignal> signals) {
        if (signals == null || signals.isEmpty())
            return List.of();
        List<String> toks = new ArrayList<>(signals.size());
        for (ExtractedSignal sig : signals) {
            if (sig != null && sig.token() != null)
                toks.add(sig.token());
        }
        return toks;
    }

    private static String key(String token, SignalIntent intent) {
        String intentName = (intent == null) ? "UNKNOWN" : intent.name();
        return intentName + "|" + token;
    }
}
