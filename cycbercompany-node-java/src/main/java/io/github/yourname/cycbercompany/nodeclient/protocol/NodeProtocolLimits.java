package io.github.yourname.cycbercompany.nodeclient.protocol;

/**
 * 节点控制协议的统一大小预算。
 *
 * <p>WebSocket 容器通常还有自己的缓冲区上限，但不能只依赖容器：节点可能收到分片消息，
 * 工具也可能返回远大于控制帧的文件内容。因此这里给协议本身定义更小、更明确的预算。
 */
public final class NodeProtocolLimits {

    /** 单条控制消息的最大 UTF-8 字节数，给 256 KiB 容器缓冲区留出安全余量。 */
    public static final int MAX_CONTROL_MESSAGE_BYTES = 192 * 1024;

    /** tool.result 中 result 字段序列化后的最大字节数。 */
    public static final int MAX_TOOL_RESULT_BYTES = 128 * 1024;

    /** 单个 stdout、stderr、文件正文或页面文本字段的最大字节数。 */
    public static final int MAX_RESULT_TEXT_BYTES = 48 * 1024;

    /** 错误消息只用于诊断摘要，不能携带无限长的命令输出。 */
    public static final int MAX_ERROR_MESSAGE_BYTES = 8 * 1024;

    private NodeProtocolLimits() {
    }
}
