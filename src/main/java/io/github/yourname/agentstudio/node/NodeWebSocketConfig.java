package io.github.yourname.agentstudio.node;

import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
// 节点使用独立 WebSocket 长连接；浏览器端的运行进度则走 SSE，两者职责不同。
class NodeWebSocketConfig implements WebSocketConfigurer {

    private final NodeChannelWebSocketHandler handler;

    NodeWebSocketConfig(NodeChannelWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 节点客户端不是浏览器，不需要跨站 Origin。保持默认同源策略，避免网页建立节点通道。
        registry.addHandler(handler, "/api/v1/node-channel");
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.nodes.websocket", name = "buffer-configured", havingValue = "true", matchIfMissing = true)
    ServletServerContainerFactoryBean nodeWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(NodeProtocolLimits.MAX_CONTAINER_BUFFER_BYTES);
        container.setMaxBinaryMessageBufferSize(NodeProtocolLimits.MAX_CONTAINER_BUFFER_BYTES);
        return container;
    }
}
