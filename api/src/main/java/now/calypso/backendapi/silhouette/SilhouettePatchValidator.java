package now.calypso.backendapi.silhouette;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SilhouettePatchValidator {
    private static final Set<String> GENERIC_LABELS = Set.of(
            "aesthetic",
            "aesthetics",
            "activity",
            "attraction",
            "boundaries",
            "boundary",
            "connection",
            "dislike",
            "energy",
            "hobbies",
            "humor",
            "interests",
            "lifestyle",
            "music",
            "nostalgia",
            "personality",
            "preference",
            "preferences",
            "relationships",
            "social energy",
            "style",
            "taste",
            "values",
            "vibe");

    private SilhouettePatchValidator() {
    }

    public static ValidationResult validate(String promptId, SilhouettePatch patch, String evidenceExcerpt) {
        if (patch == null || patch.ops == null || patch.ops.isEmpty()) {
            return new ValidationResult(SilhouettePatch.empty(), 0, 0, Map.of());
        }
        SilhouettePatch sanitized = SilhouettePatch.fromMap(patch.toMap());
        SilhouettePatch out = new SilhouettePatch();
        LinkedHashMap<String, Integer> droppedByReason = new LinkedHashMap<>();
        String evidence = normalized(evidenceExcerpt);
        for (SilhouettePatch.Op op : sanitized.ops) {
            String dropReason = dropReason(promptId, op, evidence);
            if (dropReason != null) {
                droppedByReason.put(dropReason, droppedByReason.getOrDefault(dropReason, 0) + 1);
                continue;
            }
            repairOp(op);
            out.ops.add(op);
        }
        return new ValidationResult(out, patch.ops.size(), out.ops.size(), droppedByReason);
    }

    private static String dropReason(String promptId, SilhouettePatch.Op op, String evidence) {
        if (op == null || op.op == null || op.op.isBlank()) {
            return "empty_op";
        }
        String operation = op.op.trim();
        if ("add_open_question".equals(operation)) {
            return genericOpenQuestion(op.openQuestion) ? "generic_open_question" : null;
        }
        if (lowConfidenceWithoutEvidence(op)) {
            return "low_confidence_without_evidence";
        }
        String label = opLabel(op);
        if (label.isBlank() && !"add_evidence".equals(operation)) {
            return "missing_label";
        }
        String normalizedLabel = normalized(label).replace('_', ' ');
        if (isGenericLabel(normalizedLabel) && !hasSpecificEvidence(op, evidence)) {
            return "generic_label_without_evidence";
        }
        if (isPromptEcho(promptId, normalizedLabel) && !hasSpecificEvidence(op, evidence)) {
            return "prompt_echo_without_evidence";
        }
        if (isLowValueSingleWord(normalizedLabel) && !hasSpecificEvidence(op, evidence)) {
            return "low_value_single_word";
        }
        return null;
    }

    private static void repairOp(SilhouettePatch.Op op) {
        if (op == null) {
            return;
        }
        if (op.concept != null && (op.concept.id == null || op.concept.id.isBlank())
                && op.concept.label != null && !op.concept.label.isBlank()) {
            op.concept.id = SilhouetteModelUtils.normalizeId(null, "concept", op.concept.label);
        }
        if (op.antiPattern != null && (op.antiPattern.id == null || op.antiPattern.id.isBlank())
                && op.antiPattern.label != null && !op.antiPattern.label.isBlank()) {
            op.antiPattern.id = SilhouetteModelUtils.normalizeId(null, "anti", op.antiPattern.label);
        }
    }

    private static boolean lowConfidenceWithoutEvidence(SilhouettePatch.Op op) {
        if (op == null || op.confidence == null) {
            return false;
        }
        return op.confidence.doubleValue() < 0.15 && !hasSpecificEvidence(op, "");
    }

    private static boolean hasSpecificEvidence(SilhouettePatch.Op op, String eventEvidence) {
        String ownEvidence = "";
        if (op != null && op.evidence != null && op.evidence.value != null) {
            ownEvidence = normalized(op.evidence.value);
        }
        if (wordCount(ownEvidence) >= 5) {
            return true;
        }
        String event = eventEvidence == null ? "" : eventEvidence;
        String combined = (ownEvidence + " " + event).trim();
        String label = normalized(opLabel(op)).replace('_', ' ');
        return !label.isBlank() && combined.contains(label);
    }

    private static boolean isGenericLabel(String normalizedLabel) {
        if (normalizedLabel == null || normalizedLabel.isBlank()) {
            return false;
        }
        return GENERIC_LABELS.contains(normalizedLabel);
    }

    private static boolean isLowValueSingleWord(String normalizedLabel) {
        if (normalizedLabel == null || normalizedLabel.isBlank()) {
            return false;
        }
        return wordCount(normalizedLabel) <= 1 && normalizedLabel.length() < 7 && !hasDigit(normalizedLabel);
    }

    private static boolean isPromptEcho(String promptId, String normalizedLabel) {
        if (promptId == null || promptId.isBlank() || normalizedLabel == null || normalizedLabel.isBlank()) {
            return false;
        }
        String prompt = promptId.toLowerCase(Locale.ROOT).replace('.', ' ').replace('_', ' ');
        return wordCount(normalizedLabel) <= 3 && prompt.contains(normalizedLabel);
    }

    private static boolean genericOpenQuestion(String question) {
        String normalized = normalized(question);
        if (normalized.isBlank()) {
            return true;
        }
        return normalized.equals("what else")
                || normalized.equals("tell me more")
                || normalized.equals("why")
                || normalized.equals("why is that")
                || normalized.length() < 10;
    }

    private static String opLabel(SilhouettePatch.Op op) {
        if (op == null) {
            return "";
        }
        if (op.concept != null && op.concept.label != null && !op.concept.label.isBlank()) {
            return op.concept.label;
        }
        if (op.antiPattern != null && op.antiPattern.label != null && !op.antiPattern.label.isBlank()) {
            return op.antiPattern.label;
        }
        if (op.tension != null) {
            String a = op.tension.a == null ? "" : op.tension.a.trim();
            String b = op.tension.b == null ? "" : op.tension.b.trim();
            String label = (a + " " + b).trim();
            if (!label.isBlank()) {
                return label;
            }
        }
        if (op.mode != null && op.mode.label != null && !op.mode.label.isBlank()) {
            return op.mode.label;
        }
        return op.label == null ? "" : op.label;
    }

    private static String normalized(String raw) {
        return raw == null ? "" : raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s_'-]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static int wordCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int words = 0;
        for (String token : text.trim().split("\\s+")) {
            if (!token.isBlank()) {
                words += 1;
            }
        }
        return words;
    }

    private static boolean hasDigit(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (Character.isDigit(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    public static final class ValidationResult {
        public final SilhouettePatch patch;
        public final int inputOps;
        public final int outputOps;
        public final Map<String, Integer> droppedByReason;

        ValidationResult(
                SilhouettePatch patch,
                int inputOps,
                int outputOps,
                Map<String, Integer> droppedByReason) {
            this.patch = patch == null ? SilhouettePatch.empty() : patch;
            this.inputOps = Math.max(0, inputOps);
            this.outputOps = Math.max(0, outputOps);
            this.droppedByReason = droppedByReason == null ? Map.of() : Map.copyOf(droppedByReason);
        }

        public int droppedOps() {
            return Math.max(0, inputOps - outputOps);
        }

        public Map<String, Object> auditDetails(String promptId, String source, String sourceId) {
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            if (promptId != null && !promptId.isBlank()) {
                out.put("promptId", promptId.trim());
            }
            if (source != null && !source.isBlank()) {
                out.put("source", source.trim());
            }
            if (sourceId != null && !sourceId.isBlank()) {
                out.put("sourceId", sourceId.trim());
            }
            out.put("inputOps", inputOps);
            out.put("outputOps", outputOps);
            out.put("droppedOps", droppedOps());
            if (!droppedByReason.isEmpty()) {
                out.put("droppedByReason", droppedByReason);
            }
            ArrayList<String> appliedOps = new ArrayList<>();
            for (SilhouettePatch.Op op : patch.ops) {
                if (op != null && op.op != null && !op.op.isBlank()) {
                    appliedOps.add(op.op);
                }
            }
            out.put("appliedOps", appliedOps);
            return out;
        }
    }
}
