package io.github.yourname.agentstudio.orchestration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;

/**
 * 编码任务的宿主侧固定步骤。
 *
 * <p>步骤名称由服务端定义，模型只能通过真实工具调用推进它们。这样模型的自然语言总结不会被当成
 * "已经测试" 或 "已经审阅" 的证据。每个步骤的证据说明同样由宿主定义，API 不需要返回命令、路径
 * 或工具原始输出也能解释为什么一个任务还不能交付。
 */
public enum CodingWorkflowStep {
    INSPECT("inspect", List.of("成功的项目只读检查")),
    PLAN("plan", List.of("修改前完成项目检查")),
    IMPLEMENT("implement", List.of("成功的项目文件修改")),
    VERIFY("verify", List.of("修改后的构建、测试或界面验证")),
    REVIEW("review", List.of("修改后的差异或文件审阅")),
    DELIVER("deliver", List.of("服务端交付门禁通过"));

    private final String wireValue;
    private final List<String> requiredEvidence;

    CodingWorkflowStep(String wireValue, List<String> requiredEvidence) {
        this.wireValue = wireValue;
        this.requiredEvidence = List.copyOf(requiredEvidence);
    }

    /** API 和 JSON 使用小写稳定值，避免 Java 枚举名称成为外部合同。 */
    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    public List<String> requiredEvidence() {
        return requiredEvidence;
    }

    @JsonCreator
    public static CodingWorkflowStep fromWireValue(String value) {
        for (CodingWorkflowStep step : values()) {
            if (step.wireValue.equalsIgnoreCase(value) || step.name().equalsIgnoreCase(value)) {
                return step;
            }
        }
        throw new IllegalArgumentException("Unknown coding workflow step: " + value);
    }
}
