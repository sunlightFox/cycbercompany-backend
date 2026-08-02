package io.github.yourname.agentstudio.node;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * 节点控制通道的统一消息信封。
 *
 * <p>业务字段全部放在 payload 中，信封只保存协议、连接和链路信息。这样以后新增工具字段时，
 * 不需要改变心跳、能力上报和取消消息共同依赖的校验逻辑。
 */
public record NodeProtocolEnvelope(
        String protocolVersion,
        String type,
        String messageId,
        String sessionId,
        long sequence,
        String correlationId,
        Instant sentAt,
        Instant expiresAt,
        String traceId,
        long fencingToken,
        JsonNode payload) {

    public static final String CURRENT_VERSION = "1.1";

    public boolean expired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }
}
