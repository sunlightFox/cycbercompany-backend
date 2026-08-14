package io.github.yourname.cycbercompany.model;

/** 模型在运行时能够提供的能力标签。 */
public enum ModelCapability {
    /** 普通文本输入和输出。 */
    TEXT,
    /** 图片等视觉输入。 */
    VISION,
    /** 音频输入。 */
    AUDIO_INPUT,
    /** 原生工具调用。 */
    TOOLS,
    /** 结构化 JSON 输出。 */
    JSON_OUTPUT,
    /** 文本向量化，用于知识库检索。 */
    EMBEDDING
}
