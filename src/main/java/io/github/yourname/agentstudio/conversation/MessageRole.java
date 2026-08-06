package io.github.yourname.agentstudio.conversation;

/** 消息在模型对话中的角色。 */
public enum MessageRole {
    /** 用户输入。 */
    USER,
    /** 模型或 Agent 生成的回答。 */
    ASSISTANT,
    /** 平台内部提示，不一定展示给用户。 */
    SYSTEM
}
