package io.github.yourname.agentstudio.node;

import io.github.yourname.agentstudio.tool.RiskLevel;

/**
 * 服务端定义的节点工具策略。
 *
 * <p>节点客户端只负责声明自己能执行什么，以及在收到命令后执行。工具的风险、
 * 默认是否开放和是否需要人工审批，必须由服务端统一决定，不能信任客户端上报。
 */
public record NodeToolPolicy(RiskLevel riskLevel, boolean enabledByDefault, boolean requiresApproval) {
}
