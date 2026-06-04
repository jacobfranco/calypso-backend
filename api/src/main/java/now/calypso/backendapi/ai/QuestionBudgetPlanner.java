package now.calypso.backendapi.ai;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class QuestionBudgetPlanner {
    private QuestionBudgetPlanner() {
    }

    public static Selection select(Collection<QuestionCandidate> candidates) {
        ArrayList<QuestionCandidate> eligible = new ArrayList<>();
        int considered = 0;
        if (candidates != null) {
            for (QuestionCandidate candidate : candidates) {
                if (candidate == null) {
                    continue;
                }
                considered += 1;
                if (!candidate.askable()) {
                    continue;
                }
                eligible.add(candidate);
            }
        }
        eligible.sort(Comparator
                .comparingDouble((QuestionCandidate c) -> c.utility).reversed()
                .thenComparing(Comparator.comparingInt((QuestionCandidate c) -> c.priority).reversed())
                .thenComparingLong(c -> c.sequence));
        return new Selection(eligible.isEmpty() ? null : eligible.get(0), considered, eligible);
    }

    public static Optional<QuestionCandidate> pick(Collection<QuestionCandidate> candidates) {
        return Optional.ofNullable(select(candidates).selected);
    }

    public static final class QuestionCandidate {
        public final String surface;
        public final String key;
        public final String question;
        public final double utility;
        public final int priority;
        public final long sequence;
        public final Map<String, Object> metadata;

        public QuestionCandidate(
                String surface,
                String key,
                String question,
                double utility,
                int priority,
                long sequence,
                Map<String, Object> metadata) {
            this.surface = surface == null ? "" : surface.trim();
            this.key = key == null ? "" : key.trim();
            this.question = question == null ? "" : question.trim();
            this.utility = clamp01(utility);
            this.priority = priority;
            this.sequence = sequence;
            this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }

        boolean askable() {
            return !question.isBlank() && utility > 0.0;
        }

        public Map<String, Object> auditSummary() {
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            if (!surface.isBlank()) {
                out.put("surface", surface);
            }
            if (!key.isBlank()) {
                out.put("key", key);
            }
            out.put("utility", utility);
            out.put("priority", priority);
            if (!question.isBlank()) {
                out.put("question", question);
            }
            if (!metadata.isEmpty()) {
                out.put("metadata", metadata);
            }
            return out;
        }
    }

    public static final class Selection {
        public final QuestionCandidate selected;
        public final int consideredCount;
        public final List<QuestionCandidate> eligible;

        Selection(QuestionCandidate selected, int consideredCount, List<QuestionCandidate> eligible) {
            this.selected = selected;
            this.consideredCount = Math.max(0, consideredCount);
            this.eligible = eligible == null ? List.of() : List.copyOf(eligible);
        }

        public int eligibleCount() {
            return eligible.size();
        }

        public List<Map<String, Object>> topAlternatives(int limit) {
            int bounded = Math.max(0, limit);
            ArrayList<Map<String, Object>> out = new ArrayList<>();
            for (QuestionCandidate candidate : eligible) {
                if (candidate == null) {
                    continue;
                }
                if (selected != null && candidate == selected) {
                    continue;
                }
                out.add(candidate.auditSummary());
                if (out.size() >= bounded) {
                    break;
                }
            }
            return out;
        }
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }
}
