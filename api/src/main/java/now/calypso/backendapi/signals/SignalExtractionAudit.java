package now.calypso.backendapi.signals;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import now.calypso.backend.data.SignalIntent;
import now.calypso.backendapi.ai.AiDecisionLog;

public final class SignalExtractionAudit {
    private SignalExtractionAudit() {
    }

    public static void record(
            long accountId,
            String promptId,
            String operation,
            String sourceId,
            String question,
            String answer,
            List<String> conversationLines,
            List<ExtractedSignal> signals) {
        List<ExtractedSignal> normalized = signals == null ? List.of() : signals;
        AiDecisionLog.record(
                "signal_extraction",
                operation == null || operation.isBlank() ? "unknown_source" : operation,
                normalized.isEmpty() ? "no_signals" : "signals_extracted",
                accountId < 0L ? null : Long.valueOf(accountId),
                null,
                details(promptId, sourceId, question, answer, conversationLines, normalized));
    }

    public static Map<String, Object> details(
            String promptId,
            String sourceId,
            String question,
            String answer,
            List<String> conversationLines,
            List<ExtractedSignal> signals) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        if (promptId != null && !promptId.isBlank()) {
            out.put("promptId", promptId.trim());
        }
        if (sourceId != null && !sourceId.isBlank()) {
            out.put("sourceId", sourceId.trim());
        }
        out.put("questionChars", length(question));
        out.put("answerChars", length(answer));
        out.put("conversationLineCount", conversationLines == null ? 0 : conversationLines.size());

        List<ExtractedSignal> normalized = signals == null ? List.of() : signals;
        ArrayList<String> tokens = new ArrayList<>();
        ArrayList<String> negativeTokens = new ArrayList<>();
        LinkedHashMap<String, Integer> intents = new LinkedHashMap<>();
        int concreteCount = 0;
        int broadCount = 0;
        int weakCount = 0;
        double absValenceSum = 0.0;
        int valenceCount = 0;
        for (ExtractedSignal signal : normalized) {
            if (signal == null || signal.token() == null || signal.token().isBlank()) {
                continue;
            }
            String token = signal.token().trim();
            tokens.add(token);
            Double valenceMaybe = signal.valence();
            double valence = valenceMaybe == null ? 0.0 : valenceMaybe.doubleValue();
            if (valence < -0.05) {
                negativeTokens.add(token);
            }
            if (Math.abs(valence) < 0.12) {
                weakCount += 1;
            }
            absValenceSum += Math.abs(valence);
            valenceCount += 1;

            SignalIntent intent = signal.intent();
            String intentKey = intent == null ? "self" : intent.name().toLowerCase();
            intents.put(intentKey, intents.getOrDefault(intentKey, 0) + 1);

            String category = SignalConceptRegistry.categoryForConcept(token);
            if (SignalTaxonomy.isConcreteCategory(category)) {
                concreteCount += 1;
            } else if (isBroadCategory(category, token)) {
                broadCount += 1;
            }
        }
        out.put("signalCount", tokens.size());
        out.put("tokens", compact(tokens, 18));
        out.put("negativeTokens", compact(negativeTokens, 12));
        out.put("intentCounts", intents);
        out.put("concreteSignalCount", concreteCount);
        out.put("broadSignalCount", broadCount);
        out.put("weakValenceCount", weakCount);
        out.put("avgAbsValence", valenceCount == 0 ? 0.0 : absValenceSum / (double) valenceCount);
        return out;
    }

    private static boolean isBroadCategory(String category, String token) {
        if (category == null || category.isBlank()) {
            return token != null && !token.contains("_");
        }
        return "broad_category".equals(category)
                || "topic".equals(category)
                || "activity".equals(category)
                || "lifestyle".equals(category);
    }

    private static int length(String raw) {
        return raw == null ? 0 : raw.length();
    }

    private static List<String> compact(List<String> raw, int limit) {
        if (raw == null || raw.isEmpty() || limit <= 0) {
            return List.of();
        }
        ArrayList<String> out = new ArrayList<>();
        for (String item : raw) {
            if (item == null || item.isBlank()) {
                continue;
            }
            out.add(item.trim());
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }
}
