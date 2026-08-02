package io.github.yourname.agentstudio.nodeclient.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/** 节点客户端与控制面共用的 1.1 控制消息信封。 */
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
