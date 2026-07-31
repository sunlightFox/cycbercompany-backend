package io.github.yourname.agentstudio.node;

import java.time.Duration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 心跳兜底监控。
 *
 * <p>正常情况下 WebSocket 断开会立刻标记离线；如果网络异常导致关闭事件没回来，
 * 定时任务会把长时间没心跳的节点修正为 OFFLINE。
 */
@Component
// 学习提示：关闭回调丢失时，定时任务会把超过阈值未心跳的 ONLINE 节点修正为 OFFLINE。
class NodeHeartbeatMonitor {

    private final NodeService nodes;

    NodeHeartbeatMonitor(NodeService nodes) {
        this.nodes = nodes;
    }

    @Scheduled(fixedDelayString = "${app.nodes.stale-check-ms:30000}")
    void markStaleNodesOffline() {
        // 检查频率和离线阈值分开设置，避免一次短暂网络抖动就误判节点离线。
        nodes.markStaleNodesOffline(Duration.ofSeconds(60));
    }
}
