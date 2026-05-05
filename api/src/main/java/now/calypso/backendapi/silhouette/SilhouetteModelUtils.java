package now.calypso.backendapi.silhouette;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class SilhouetteModelUtils {
    private SilhouetteModelUtils() {
    }

    static String normalizeKey(Object raw) {
        if (raw == null) {
            return null;
        }
        String lower = raw.toString().trim().toLowerCase(Locale.ROOT);
        if (lower.isBlank()) {
            return null;
        }
        StringBuilder out = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.') {
                out.append(c);
            } else if (Character.isWhitespace(c)) {
                out.append('_');
            }
        }
        String normalized = out.toString().replaceAll("_+", "_");
        return normalized.isBlank() ? null : normalized;
    }

    static String normalizeId(Object raw, String prefix, String fallbackBasis) {
        String id = normalizeKey(raw);
        if (id != null) {
            return id;
        }
        String basis = normalizeKey(fallbackBasis);
        if (basis == null) {
            basis = "item";
        }
        String safePrefix = prefix == null || prefix.isBlank() ? "" : prefix.trim().toLowerCase(Locale.ROOT);
        return safePrefix.isBlank() ? basis : safePrefix + "_" + basis;
    }

    static String text(Object raw, int maxLen) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.toString().trim();
        if (trimmed.isBlank()) {
            return "";
        }
        int cap = Math.max(1, maxLen);
        if (trimmed.length() <= cap) {
            return trimmed;
        }
        String clipped = trimmed.substring(0, cap).trim();
        int lastSpace = clipped.lastIndexOf(' ');
        if (lastSpace >= cap * 0.6) {
            return clipped.substring(0, lastSpace).trim();
        }
        return clipped;
    }

    static double parseDouble(Object raw, double fallback) {
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        if (raw instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    static long parseLong(Object raw, long fallback) {
        if (raw instanceof Number n) {
            return n.longValue();
        }
        if (raw instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    static int parseInt(Object raw, int fallback) {
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    static double clamp01(double value) {
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

    static String oneOf(String raw, String fallback, String... allowed) {
        String key = normalizeKey(raw);
        if (key == null) {
            return fallback;
        }
        if (allowed != null) {
            for (String value : allowed) {
                if (key.equals(value)) {
                    return key;
                }
            }
        }
        return fallback;
    }

    static List<String> stringList(Object raw, int maxItems, int maxChars) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                String value = text(item, maxChars);
                if (!value.isBlank()) {
                    out.add(value);
                }
                if (out.size() >= maxItems) {
                    break;
                }
            }
        } else {
            String value = text(raw, maxChars);
            if (!value.isBlank()) {
                out.add(value);
            }
        }
        return new ArrayList<>(out);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> objectMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }
            out.put(entry.getKey().toString(), entry.getValue());
        }
        return out;
    }

    static Object first(Map<String, Object> map, String... keys) {
        if (map == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key != null && map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }

    static <T> ArrayList<T> mutableList(List<T> values) {
        return values == null ? new ArrayList<>() : new ArrayList<>(values);
    }
}
