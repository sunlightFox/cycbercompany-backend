package io.github.yourname.agentstudio.node;

/** 服务端与节点约定的控制通道大小预算。节点模块中保留同值常量，二者由边界测试约束。 */
final class NodeProtocolLimits {

    static final int MAX_CONTROL_MESSAGE_BYTES = 192 * 1024;
    static final int MAX_CONTAINER_BUFFER_BYTES = 256 * 1024;

    private NodeProtocolLimits() {
    }
}
