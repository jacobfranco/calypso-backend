package now.calypso.backendapi.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public final class AiDecisionLog {
    private static final int DEFAULT_RECENT_LIMIT = 800;
    private static final int MAX_RECENT_EVENTS = parsePositiveInt(
            System.getenv("CALYPSO_AI_DECISION_RECENT_LIMIT"),
            DEFAULT_RECENT_LIMIT);
    private static final int MAX_STRING_CHARS = 420;
    private static final int MAX_LIST_ITEMS = 24;
    private static final int MAX_MAP_ENTRIES = 48;
    private static final Object RECENT_LOCK = new Object();
    private static final ArrayDeque<Map<String, Object>> RECENT = new ArrayDeque<>();
    private static final ConcurrentHashMap<String, Aggregate> AGGREGATES = new ConcurrentHashMap<>();

    private AiDecisionLog() {
    }

    public static void record(
            String surface,
            String stage,
            String action,
            Long accountId,
            Long targetAccountId,
            Map<String, Object> details) {
        String normalizedSurface = normalize(surface, "unknown_surface");
        String normalizedStage = normalize(stage, "unknown_stage");
        String normalizedAction = normalize(action, "unknown_action");

        String key = normalizedSurface + "|" + normalizedStage + "|" + normalizedAction;
        AGGREGATES.computeIfAbsent(
                key,
                ignored -> new Aggregate(normalizedSurface, normalizedStage, normalizedAction))
                .count.increment();

        LinkedHashMap<String, Object> event = new LinkedHashMap<>();
        event.put("createdAt", System.currentTimeMillis());
        event.put("surface", normalizedSurface);
        event.put("stage", normalizedStage);
        event.put("action", normalizedAction);
        if (accountId != null && accountId.longValue() >= 0L) {
            event.put("accountId", accountId.longValue());
        }
        if (targetAccountId != null && targetAccountId.longValue() >= 0L) {
            event.put("targetAccountId", targetAccountId.longValue());
        }
        Map<String, Object> sanitizedDetails = sanitizeMap(details);
        if (!sanitizedDetails.isEmpty()) {
            event.put("details", sanitizedDetails);
        }

        synchronized (RECENT_LOCK) {
            RECENT.addFirst(event);
            while (RECENT.size() > MAX_RECENT_EVENTS) {
                RECENT.removeLast();
            }
        }
    }

    public static Map<String, Object> snapshot(int limit) {
        int bounded = Math.max(1, Math.min(1000, limit <= 0 ? 120 : limit));
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", System.currentTimeMillis());

        long total = 0L;
        ArrayList<Map<String, Object>> byDecision = new ArrayList<>();
        LinkedHashMap<String, LongAdder> bySurface = new LinkedHashMap<>();
        LinkedHashMap<String, LongAdder> byAction = new LinkedHashMap<>();
        for (Map.Entry<String, Aggregate> entry : AGGREGATES.entrySet()) {
            if (entry == null || entry.getValue() == null) {
                continue;
            }
            Aggregate aggregate = entry.getValue();
            long count = aggregate.count.sum();
            if (count <= 0L) {
                continue;
            }
            total += count;
            bySurface.computeIfAbsent(aggregate.surface, ignored -> new LongAdder()).add(count);
            byAction.computeIfAbsent(aggregate.action, ignored -> new LongAdder()).add(count);
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("decisionKey", entry.getKey());
            row.put("surface", aggregate.surface);
            row.put("stage", aggregate.stage);
            row.put("action", aggregate.action);
            row.put("count", count);
            byDecision.add(row);
        }
        byDecision.sort(Comparator.comparingLong(row -> -longField(row, "count")));
        out.put("totals", Map.of("decisions", total));
        out.put("byDecision", byDecision);
        out.put("bySurface", aggregateRows(bySurface, "surface"));
        out.put("byAction", aggregateRows(byAction, "action"));

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

    public static void clearForTests() {
        synchronized (RECENT_LOCK) {
            RECENT.clear();
        }
        AGGREGATES.clear();
    }

    private static ArrayList<Map<String, Object>> aggregateRows(Map<String, LongAdder> counts, String field) {
        ArrayList<Map<String, Object>> rows = new ArrayList<>();
        if (counts == null || counts.isEmpty()) {
            return rows;
        }
        for (Map.Entry<String, LongAdder> entry : counts.entrySet()) {
            if (entry == null || entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            long count = entry.getValue().sum();
            if (count <= 0L) {
                continue;
            }
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put(field, entry.getKey());
            row.put("count", count);
            rows.add(row);
        }
        rows.sort(Comparator.comparingLong(row -> -longField(row, "count")));
        return rows;
    }

    private static Map<String, Object> sanitizeMap(Map<String, Object> raw) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        int count = 0;
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (entry == null || entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            Object value = sanitizeValue(entry.getValue(), 0);
            if (value == null) {
                continue;
            }
            out.put(sanitizeKey(entry.getKey()), value);
            count += 1;
            if (count >= MAX_MAP_ENTRIES) {
                break;
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object sanitizeValue(Object raw, int depth) {
        if (raw == null || depth > 3) {
            return null;
        }
        if (raw instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            return trimmed.length() > MAX_STRING_CHARS ? trimmed.substring(0, MAX_STRING_CHARS).trim() : trimmed;
        }
        if (raw instanceof Number || raw instanceof Boolean) {
            return raw;
        }
        if (raw instanceof Enum<?> e) {
            return e.name().toLowerCase(Locale.ROOT);
        }
        if (raw instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            int count = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry == null || entry.getKey() == null) {
                    continue;
                }
                Object value = sanitizeValue(entry.getValue(), depth + 1);
                if (value == null) {
                    continue;
                }
                out.put(sanitizeKey(String.valueOf(entry.getKey())), value);
                count += 1;
                if (count >= MAX_MAP_ENTRIES) {
                    break;
                }
            }
            return out.isEmpty() ? null : out;
        }
        if (raw instanceof Collection<?> collection) {
            ArrayList<Object> out = new ArrayList<>();
            for (Object item : collection) {
                Object value = sanitizeValue(item, depth + 1);
                if (value == null) {
                    continue;
                }
                out.add(value);
                if (out.size() >= MAX_LIST_ITEMS) {
                    break;
                }
            }
            return out.isEmpty() ? null : out;
        }
        if (raw.getClass().isArray()) {
            ArrayList<Object> out = new ArrayList<>();
            int length = java.lang.reflect.Array.getLength(raw);
            for (int i = 0; i < length && out.size() < MAX_LIST_ITEMS; i++) {
                Object value = sanitizeValue(java.lang.reflect.Array.get(raw, i), depth + 1);
                if (value != null) {
                    out.add(value);
                }
            }
            return out.isEmpty() ? null : out;
        }
        return sanitizeValue(String.valueOf(raw), depth + 1);
    }

    private static String sanitizeKey(String raw) {
        String normalized = raw == null || raw.isBlank()
                ? "field"
                : raw.trim()
                        .replaceAll("[^A-Za-z0-9_.:-]+", "_")
                        .replaceAll("_+", "_")
                        .replaceAll("^_+|_+$", "");
        if (normalized.isBlank()) {
            normalized = "field";
        }
        if (normalized.length() <= 80) {
            return normalized;
        }
        return normalized.substring(0, 80);
    }

    private static String normalize(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String normalized = raw.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.:-]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        return normalized.isBlank() ? fallback : normalized;
    }

    private static long longField(Map<String, Object> row, String key) {
        if (row == null || key == null) {
            return 0L;
        }
        Object raw = row.get(key);
        if (raw instanceof Number n) {
            return n.longValue();
        }
        return 0L;
    }

    private static int parsePositiveInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static final class Aggregate {
        final String surface;
        final String stage;
        final String action;
        final LongAdder count = new LongAdder();

        Aggregate(String surface, String stage, String action) {
            this.surface = surface;
            this.stage = stage;
            this.action = action;
        }
    }
}
