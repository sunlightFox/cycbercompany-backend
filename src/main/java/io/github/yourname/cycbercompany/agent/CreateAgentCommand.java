package io.github.yourname.cycbercompany.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建 Agent 的 HTTP 输入对象。
 *
 * <p>Command 表示“调用方想做什么”，不等同于数据库 Entity。参数校验放在边界层，
 * 业务服务收到的对象已经满足非空和长度限制。
 */
public record CreateAgentCommand(
        @NotBlank @Size(max = 80) String name,
        @Size(max = 240) String description,
        @NotBlank @Size(max = 12000) String systemPrompt) {
}
