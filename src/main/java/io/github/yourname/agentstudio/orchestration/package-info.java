/**
 * Run 编排模块。
 *
 * <p>这是“用户一句话变成一次可审计执行”的核心层：创建 Run、固定 RunSpec 快照、
 * 进入会话队列、调用模型、组织工具结果、发布 SSE 事件，并在取消/审批/重试时维护状态机。
 * 其他模块提供能力，本模块负责把能力编排成一次完整运行。
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {
        "security", "conversation", "agent", "model", "knowledge", "tool", "skill", "mcp", "node"})
package io.github.yourname.agentstudio.orchestration;
