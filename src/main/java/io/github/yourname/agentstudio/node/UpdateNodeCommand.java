package io.github.yourname.agentstudio.node;

import java.util.Set;

/**
 * 管理节点的可见名称、启用状态和受信任沙箱调度信息。
 *
 * <p>kind/labels 只会由管理 API 写入，节点客户端不能在心跳或能力上报中自行把自己升级为
 * SANDBOX。缺少 labels 表示保持原值，显式传空数组则清空标签。
 */
public record UpdateNodeCommand(String name, Boolean enabled, NodeKind kind, Set<String> labels) {

    /** Keeps existing API clients that only update a name or enabled flag source-compatible. */
    public UpdateNodeCommand(String name, Boolean enabled) {
        this(name, enabled, null, null);
    }
}
