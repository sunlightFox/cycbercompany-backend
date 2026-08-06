/**
 * 会话与消息模块。
 *
 * <p>本模块只管理聊天会话、用户/助手消息、归档状态和消息历史读取。它不拼 Prompt，
 * 不调用模型，也不执行工具；这样会话数据可以保持简单、稳定，并被 Run 编排层复用。
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = "security")
package io.github.yourname.agentstudio.conversation;
