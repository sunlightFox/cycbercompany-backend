package io.github.yourname.cycbercompany.memory;

import java.util.Locale;

final class MemoryKey {
    private MemoryKey() {}

    static String infer(MemoryScope scope, MemoryType type, String content) {
        String value = content == null ? "" : content.trim().toLowerCase(Locale.ROOT);
        if (scope == MemoryScope.AGENT && (value.contains("喜欢") || value.contains("偏好")
                || value.contains("prefer") || value.contains("likes"))
                && (value.contains("红") || value.contains("蓝") || value.contains("绿") || value.contains("颜色")
                        || value.contains("red") || value.contains("blue") || value.contains("green") || value.contains("color"))) {
            return "agent.preference.color";
        }
        return scope.name().toLowerCase(Locale.ROOT) + "." + type.name().toLowerCase(Locale.ROOT)
                + "." + Integer.toHexString(value.replaceAll("\\s+", " ").hashCode());
    }
}
