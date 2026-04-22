package now.calypso.backendapi.llm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import com.openai.models.ChatModel;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseUsage;
import com.openai.models.responses.StructuredResponse;

public final class LlmTelemetry {
    private static final int DEFAULT_RECENT_LIMIT = 400;
    private static final int MAX_RECENT_EVENTS = parsePositiveInt(
            System.getenv("CALYPSO_LLM_TELEMETRY_RECENT_LIMIT"),
            DEFAULT_RECENT_LIMIT);
    private static final Object RECENT_LOCK = new Object();
    private static final ArrayDeque<Map<String, Object>> RECENT = new ArrayDeque<>();
    private static final ConcurrentHashMap<String, Aggregate> AGGREGATES = new ConcurrentHashMap<>();

    private LlmTelemetry() {
    }

    public static void recordResponse(
            String stage,
            String surface,
            String promptId,
            ChatModel model,
            Response response,
            long latencyMs,
            Long maxOutputTokens) {
        ResponseUsage usage = null;
        if (response != null) {
            Optional<ResponseUsage> usageMaybe = response.usage();
            usage = usageMaybe == null ? null : usageMaybe.orElse(null);
        }
        record(
                stage,
                surface,
                promptId,
                model,
                true,
                latencyMs,
                usage == null ? null : usage.inputTokens(),
                usage == null ? null : usage.outputTokens(),
                usage == null ? null : usage.totalTokens(),
                maxOutputTokens,
                null);
    }

    public static void recordStructuredResponse(
            String stage,
            String surface,
            String promptId,
            ChatModel model,
            StructuredResponse<?> response,
            long latencyMs,
            Long maxOutputTokens) {
        ResponseUsage usage = null;
        if (response != null) {
            Optional<ResponseUsage> usageMaybe = response.usage();
            usage = usageMaybe == null ? null : usageMaybe.orElse(null);
        }
        record(
                stage,
                surface,
                promptId,
                model,
                true,
                latencyMs,
                usage == null ? null : usage.inputTokens(),
                usage == null ? null : usage.outputTokens(),
                usage == null ? null : usage.totalTokens(),
                maxOutputTokens,
                null);
    }

    public static void recordFailure(
            String stage,
            String surface,
            String promptId,
            ChatModel model,
            long latencyMs,
            Long maxOutputTokens,
            Throwable error) {
        record(
                stage,
                surface,
                promptId,
                model,
                false,
                latencyMs,
                null,
                null,
                null,
                maxOutputTokens,
                errorKind(error));
    }

    public static Map<String, Object> snapshot(int limit) {
        int bounded = Math.max(1, Math.min(1000, limit <= 0 ? 120 : limit));
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", System.currentTimeMillis());

        long calls = 0L;
        long successes = 0L;
        long failures = 0L;
        long latencyMs = 0L;
        long inputTokens = 0L;
        long outputTokens = 0L;
        long totalTokens = 0L;
        ArrayList<Map<String, Object>> byStage = new ArrayList<>();
        for (Map.Entry<String, Aggregate> entry : AGGREGATES.entrySet()) {
            if (entry == null || entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            Aggregate aggregate = entry.getValue();
            long stageCalls = aggregate.calls.sum();
            long stageSuccesses = aggregate.successes.sum();
            long stageFailures = aggregate.failures.sum();
            long stageLatency = aggregate.latencyMs.sum();
            long stageInput = aggregate.inputTokens.sum();
            long stageOutput = aggregate.outputTokens.sum();
            long stageTotal = aggregate.totalTokens.sum();
            if (stageCalls <= 0L) {
                continue;
            }
            calls += stageCalls;
            successes += stageSuccesses;
            failures += stageFailures;
            latencyMs += stageLatency;
            inputTokens += stageInput;
            outputTokens += stageOutput;
            totalTokens += stageTotal;

            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("stageKey", entry.getKey());
            row.put("stage", aggregate.stage);
            row.put("surface", aggregate.surface);
            row.put("calls", stageCalls);
            row.put("successes", stageSuccesses);
            row.put("failures", stageFailures);
            row.put("avgLatencyMs", stageCalls <= 0L ? 0.0 : ((double) stageLatency / (double) stageCalls));
            row.put("inputTokens", stageInput);
            row.put("outputTokens", stageOutput);
            row.put("totalTokens", stageTotal);
            byStage.add(row);
        }
        byStage.sort(Comparator.comparingLong(row -> -longField(row, "totalTokens")));
        out.put("totals", Map.of(
                "calls", calls,
                "successes", successes,
                "failures", failures,
                "avgLatencyMs", calls <= 0L ? 0.0 : ((double) latencyMs / (double) calls),
                "inputTokens", inputTokens,
                "outputTokens", outputTokens,
                "totalTokens", totalTokens));
        out.put("byStage", byStage);

        ArrayList<Map<String, Object>> events = new ArrayList<>();
        synchronized (RECENT_LOCK) {
            int count = 0;
            for (Map<String, Object> event : RECENT) {
                if (event == null || event.isEmpty()) {
                    continue;
                }
                events.add(new LinkedHashMap<>(event));
                count += 1;
                if (count >= bounded) {
                    break;
                }
            }
        }
        out.put("events", events);
        return out;
    }

    private static void record(
            String stage,
            String surface,
            String promptId,
            ChatModel model,
            boolean success,
            long latencyMs,
            Long inputTokens,
            Long outputTokens,
            Long totalTokens,
            Long maxOutputTokens,
            String errorKind) {
        String normalizedStage = normalize(stage, "unknown_stage");
        String normalizedSurface = normalize(surface, "unknown_surface");
        String normalizedPromptId = sanitizePromptId(promptId);
        String normalizedModel = model == null ? "unknown_model" : normalize(model.asString(), "unknown_model");
        long normalizedLatency = Math.max(0L, latencyMs);
        long in = sanitizeNonNegative(inputTokens);
        long out = sanitizeNonNegative(outputTokens);
        long total = sanitizeNonNegative(totalTokens);
        if (total <= 0L && (in > 0L || out > 0L)) {
            total = in + out;
        }

        String stageKey = normalizedStage + "|" + normalizedSurface;
        Aggregate aggregate = AGGREGATES.computeIfAbsent(stageKey, ignored -> new Aggregate(normalizedStage, normalizedSurface));
        aggregate.calls.increment();
        if (success) {
            aggregate.successes.increment();
        } else {
            aggregate.failures.increment();
        }
        aggregate.latencyMs.add(normalizedLatency);
        if (in > 0L) {
            aggregate.inputTokens.add(in);
        }
        if (out > 0L) {
            aggregate.outputTokens.add(out);
        }
        if (total > 0L) {
            aggregate.totalTokens.add(total);
        }

        LinkedHashMap<String, Object> event = new LinkedHashMap<>();
        event.put("createdAt", System.currentTimeMillis());
        event.put("stage", normalizedStage);
        event.put("surface", normalizedSurface);
        event.put("promptId", normalizedPromptId);
        event.put("model", normalizedModel);
        event.put("success", success);
        event.put("latencyMs", normalizedLatency);
        event.put("inputTokens", in);
        event.put("outputTokens", out);
        event.put("totalTokens", total);
        if (maxOutputTokens != null && maxOutputTokens.longValue() > 0L) {
            event.put("maxOutputTokens", maxOutputTokens.longValue());
        }
        if (!success && errorKind != null && !errorKind.isBlank()) {
            event.put("error", errorKind);
        }
        synchronized (RECENT_LOCK) {
            RECENT.addFirst(event);
            while (RECENT.size() > MAX_RECENT_EVENTS) {
                RECENT.removeLast();
            }
        }
    }

    private static String normalize(String raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static String sanitizePromptId(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.length() <= 120) {
            return trimmed;
        }
        return trimmed.substring(0, 120).trim();
    }

    private static long sanitizeNonNegative(Long raw) {
        if (raw == null || raw.longValue() <= 0L) {
            return 0L;
        }
        return raw.longValue();
    }

    private static String errorKind(Throwable error) {
        if (error == null) {
            return "";
        }
        Throwable current = error;
        int depth = 0;
        while (current.getCause() != null && current.getCause() != current && depth < 5) {
            current = current.getCause();
            depth += 1;
        }
        String simple = current.getClass().getSimpleName();
        if (simple == null || simple.isBlank()) {
            simple = current.getClass().getName();
        }
        return simple.toLowerCase(Locale.ROOT);
    }

    private static int parsePositiveInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed <= 0) {
                return fallback;
            }
            return parsed;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long longField(Map<String, Object> map, String key) {
        if (map == null || map.isEmpty() || key == null || key.isBlank()) {
            return 0L;
        }
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private static final class Aggregate {
        final String stage;
        final String surface;
        final LongAdder calls = new LongAdder();
        final LongAdder successes = new LongAdder();
        final LongAdder failures = new LongAdder();
        final LongAdder latencyMs = new LongAdder();
        final LongAdder inputTokens = new LongAdder();
        final LongAdder outputTokens = new LongAdder();
        final LongAdder totalTokens = new LongAdder();

        Aggregate(String stage, String surface) {
            this.stage = stage;
            this.surface = surface;
        }
    }
}
