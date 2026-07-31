package io.github.yourname.agentstudio.node;

import java.util.regex.Pattern;

/**
 * 节点审计记录的展示层脱敏工具。
 *
 * <p>它不是密钥管理方案，不能代替“不要把密钥放进命令行”的规范；
 * 作用是在调用详情、审批详情等 API 返回前减少二次泄露风险。
 */
final class SensitiveValueMasker {

    private static final String SENSITIVE_NAMES = "(?:api[_-]?key|token|secret|password|authorization)";
    private static final Pattern JSON_VALUE = Pattern.compile(
            "(?i)(\\\"" + SENSITIVE_NAMES + "\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")");
    private static final Pattern ASSIGNMENT_VALUE = Pattern.compile(
            "(?i)(" + SENSITIVE_NAMES + "\\s*[=:]\\s*)([^\\s,;]+)");
    private static final Pattern BEARER_VALUE = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~+/-]+={0,2}");

    private SensitiveValueMasker() {
    }

    static String mask(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String masked = JSON_VALUE.matcher(value).replaceAll("$1***$2");
        masked = ASSIGNMENT_VALUE.matcher(masked).replaceAll("$1***");
        return BEARER_VALUE.matcher(masked).replaceAll("$1***");
    }
}
