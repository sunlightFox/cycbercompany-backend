package io.github.yourname.agentstudio.node;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

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
        // "*" 仅适合本地学习环境，部署前应改为受信任的来源列表。
        registry.addHandler(handler, "/api/v1/node-channel")
                .setAllowedOrigins("*");
    }
}
