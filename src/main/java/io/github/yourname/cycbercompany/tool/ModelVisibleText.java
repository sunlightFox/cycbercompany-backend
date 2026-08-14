package io.github.yourname.cycbercompany.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Normalizes provider-controlled labels before they enter model-visible tool metadata. */
public final class ModelVisibleText {

    private static final int MAX_SCHEMA_DEPTH = 12;
    private static final int MAX_SCHEMA_ENTRIES = 512;
    private static final int MAX_SCHEMA_KEY_CHARACTERS = 160;
    private static final int MAX_SCHEMA_TEXT_CHARACTERS = 1_000;
    private static final String UNTRUSTED_ANNOTATION_PREFIX = "Provider annotation (untrusted data): ";

    private ModelVisibleText() {
    }

    public static String oneLine(String value, String fallback, int maxCharacters) {
        String normalized = value == null ? "" : value
                .replaceAll("[\\p{Cntrl}\\r\\n]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isBlank()) {
            normalized = fallback == null ? "" : fallback.trim();
        }
        int limit = Math.max(1, maxCharacters);
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "...";
    }

    /**
     * Bounds and normalizes a provider-controlled JSON Schema before exposing it to a model.
     * Structural keywords remain intact, while free-form annotations are explicitly labelled as
     * untrusted metadata and every provider-controlled string is forced onto one bounded line.
     */
    public static Map<String, Object> schema(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return Map.of("type", "object", "properties", Map.of());
        }
        SanitizationBudget budget = new SanitizationBudget(MAX_SCHEMA_ENTRIES);
        Object sanitized = sanitizeSchemaValue(value, null, 0, budget);
        if (sanitized instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }
        return Map.of("type", "object", "properties", Map.of());
    }

    private static Object sanitizeSchemaValue(
            Object value,
            String parentKey,
            int depth,
            SanitizationBudget budget) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (depth > MAX_SCHEMA_DEPTH || !budget.take()) {
            if (value instanceof Map<?, ?>) {
                return Map.of();
            }
            if (value instanceof Iterable<?>) {
                return List.of();
            }
            return "[Provider schema content omitted]";
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!budget.hasRemaining()) {
                    break;
                }
                String key = oneLine(String.valueOf(entry.getKey()), "field", MAX_SCHEMA_KEY_CHARACTERS);
                Object sanitizedValue = sanitizeSchemaValue(entry.getValue(), key, depth + 1, budget);
                // Map.copyOf is used at the immutable tool-binding boundary and rejects null values.
                // Omitting a provider's null annotation/default is safer than failing all tool discovery.
                if (sanitizedValue != null) {
                    sanitized.put(key, sanitizedValue);
                }
            }
            return Collections.unmodifiableMap(sanitized);
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> sanitized = new ArrayList<>();
            for (Object item : iterable) {
                if (!budget.hasRemaining()) {
                    break;
                }
                sanitized.add(sanitizeSchemaValue(item, parentKey, depth + 1, budget));
            }
            return Collections.unmodifiableList(sanitized);
        }
        String text = oneLine(String.valueOf(value), "", MAX_SCHEMA_TEXT_CHARACTERS);
        if (isAnnotation(parentKey) && !text.isBlank()) {
            return UNTRUSTED_ANNOTATION_PREFIX + text;
        }
        return text;
    }

    private static boolean isAnnotation(String key) {
        return "description".equals(key) || "title".equals(key) || "$comment".equals(key);
    }

    private static final class SanitizationBudget {
        private int remaining;

        private SanitizationBudget(int remaining) {
            this.remaining = remaining;
        }

        private boolean take() {
            if (remaining <= 0) {
                return false;
            }
            remaining--;
            return true;
        }

        private boolean hasRemaining() {
            return remaining > 0;
        }
    }
}
