package io.github.yourname.agentstudio.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将构建或测试命令的原始输出转换成模型更容易采取行动的、有限的失败摘要。
 *
 * <p>原始 stdout/stderr 仍然保留在节点调用记录中供排障；本类只从已返回给后端的结果提取
 * 测试名称和源码位置，并不会读取额外文件或执行额外命令。
 */
public final class CodingFailureSummary {

    private static final int MAX_ITEMS = 8;
    private static final Pattern SOURCE_LOCATION = Pattern.compile(
            "(?m)(?:^|\\s)([^\\s:]+\\.(?:java|kt|kts|ts|tsx|js|jsx|py)):(\\d+)(?::(\\d+))?");
    private static final Pattern GRADLE_TEST = Pattern.compile("(?m)^\\s*([^\\r\\n]+?\\(\\))\\s+FAILED\\s*$");
    private static final Pattern MAVEN_TEST = Pattern.compile("(?m)^\\[ERROR\\].*?--\\s+in\\s+([^\\r\\n]+)$");
    private static final Pattern JEST_TEST = Pattern.compile("(?m)^FAIL\\s+([^\\r\\n]+)$");

    private CodingFailureSummary() {
    }

    /** 只有失败的 shell.run 结果才需要诊断；其他工具维持原始语义。 */
    public static Map<String, Object> from(
            String toolName,
            boolean succeeded,
            Map<String, Object> result,
            String errorMessage) {
        if (succeeded || !"shell.run".equals(toolName)) {
            return Map.of();
        }
        String output = text(result, "stderr") + "\n" + text(result, "stdout");
        List<Map<String, Object>> locations = sourceLocations(output);
        List<String> failedTests = failedTests(output);
        LinkedHashSet<String> searchTerms = new LinkedHashSet<>();
        locations.forEach(location -> searchTerms.add(location.get("path").toString()));
        searchTerms.addAll(failedTests);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("kind", Boolean.TRUE.equals(result == null ? null : result.get("timedOut"))
                ? "command_timeout"
                : "command_failure");
        summary.put("message", errorMessage == null || errorMessage.isBlank()
                ? "The command failed. Inspect the captured output before changing code."
                : errorMessage);
        summary.put("sourceLocations", locations);
        summary.put("failedTests", failedTests);
        summary.put("suggestedSearchTerms", searchTerms.stream().limit(MAX_ITEMS).toList());
        summary.put("nextStep", locations.isEmpty() && failedTests.isEmpty()
                ? "Read the captured stderr/stdout, then inspect only the files named by the error."
                : "Use fs.search or fs.read on the reported locations before making one focused correction.");
        return summary;
    }

    private static List<Map<String, Object>> sourceLocations(String output) {
        List<Map<String, Object>> locations = new ArrayList<>();
        Matcher matcher = SOURCE_LOCATION.matcher(output);
        while (matcher.find() && locations.size() < MAX_ITEMS) {
            Map<String, Object> location = new LinkedHashMap<>();
            location.put("path", matcher.group(1).replace('\\', '/'));
            location.put("line", Integer.parseInt(matcher.group(2)));
            if (matcher.group(3) != null) {
                location.put("column", Integer.parseInt(matcher.group(3)));
            }
            // 同一位置在编译器摘要和详细堆栈中可能出现多次，摘要只保留一次。
            if (!locations.contains(location)) {
                locations.add(location);
            }
        }
        return locations;
    }

    private static List<String> failedTests(String output) {
        LinkedHashSet<String> tests = new LinkedHashSet<>();
        collect(GRADLE_TEST, output, tests);
        collect(MAVEN_TEST, output, tests);
        collect(JEST_TEST, output, tests);
        return tests.stream().limit(MAX_ITEMS).toList();
    }

    private static void collect(Pattern pattern, String output, LinkedHashSet<String> values) {
        Matcher matcher = pattern.matcher(output);
        while (matcher.find() && values.size() < MAX_ITEMS) {
            values.add(matcher.group(1).trim());
        }
    }

    private static String text(Map<String, Object> result, String name) {
        if (result == null || result.get(name) == null) {
            return "";
        }
        return result.get(name).toString();
    }
}
