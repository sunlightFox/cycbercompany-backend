package io.github.yourname.cycbercompany.orchestration;

import java.util.Arrays;

/**
 * 编码能力评测的固定场景。
 *
 * <p>这里的标识符会写入自动化报告和持续集成基线，因此使用稳定的英文值；界面层可用
 * {@link #label()} 展示中文名称。新增场景应新增枚举值，而不是复用旧值改变含义，避免
 * 历史评测结果失去可比性。
 */
public enum CodingEvaluationScenario {
    MINIMAL_FULL_STACK("minimal-full-stack", "最小全栈待办应用"),
    FAILED_TEST_MINIMAL_FIX("failed-test-minimal-fix", "测试失败后的最小修复"),
    SPLIT_FRONTEND_BACKEND("split-frontend-backend", "前后端分离仓库"),
    EXISTING_REPOSITORY_FEATURE("existing-repository-feature", "存量仓库小功能"),
    LONG_TASK_RECOVERY("long-task-recovery", "长任务恢复");

    private final String wireValue;
    private final String label;

    CodingEvaluationScenario(String wireValue, String label) {
        this.wireValue = wireValue;
        this.label = label;
    }

    public String wireValue() {
        return wireValue;
    }

    public String label() {
        return label;
    }

    /** 接口同时接受稳定标识符和枚举名，方便脚本与 Java 调用方使用。 */
    public static CodingEvaluationScenario from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("coding evaluation scenario is required");
        }
        return Arrays.stream(values())
                .filter(item -> item.wireValue.equalsIgnoreCase(value.trim())
                        || item.name().equalsIgnoreCase(value.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported coding evaluation scenario: " + value));
    }
}
