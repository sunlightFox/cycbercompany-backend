package io.github.yourname.agentstudio.node;

import io.github.yourname.agentstudio.tool.RiskLevel;
import java.util.Map;

/**
 * 节点工具的服务端默认策略目录。
 *
 * <p>这里是能力发现与权限决策的分界线：客户端可以报告工具存在，
 * 但不能通过报告较低风险或关闭审批来扩大自身权限。管理员后来在管理接口中
 * 修改的策略仍会保存到数据库，不会被节点重连覆盖。
 */
public final class NodeToolPolicyCatalog {

    private static final NodeToolPolicy READ_ONLY = new NodeToolPolicy(RiskLevel.LOW, true, false);
    private static final NodeToolPolicy WORKSPACE_WRITE = new NodeToolPolicy(RiskLevel.MEDIUM, false, true);
    private static final NodeToolPolicy HIGH_RISK = new NodeToolPolicy(RiskLevel.HIGH, false, true);
    private static final NodeToolPolicy SYSTEM_RISK = new NodeToolPolicy(RiskLevel.HIGH, true, true);

    private static final Map<String, NodeToolPolicy> POLICIES = Map.ofEntries(
            Map.entry("git.status", READ_ONLY),
            Map.entry("git.diff", READ_ONLY),
            Map.entry("git.stage", HIGH_RISK),
            Map.entry("git.commit", HIGH_RISK),
            Map.entry("project.inspect", READ_ONLY),
            Map.entry("project.discover", READ_ONLY),
            Map.entry("project.map", READ_ONLY),
            Map.entry("fs.list", READ_ONLY),
            Map.entry("fs.read", READ_ONLY),
            Map.entry("fs.search", READ_ONLY),
            Map.entry("fs.write", WORKSPACE_WRITE),
            Map.entry("fs.apply_patch", WORKSPACE_WRITE),
            Map.entry("shell.run", HIGH_RISK),
            Map.entry("process.start", HIGH_RISK),
            Map.entry("process.status", READ_ONLY),
            Map.entry("process.stop", HIGH_RISK),
            Map.entry("browser.open", new NodeToolPolicy(RiskLevel.MEDIUM, true, false)),
            Map.entry("browser.snapshot", READ_ONLY),
            Map.entry("browser.wait", READ_ONLY),
            Map.entry("browser.screenshot", READ_ONLY),
            Map.entry("browser.click", new NodeToolPolicy(RiskLevel.MEDIUM, true, false)),
            Map.entry("browser.type", new NodeToolPolicy(RiskLevel.MEDIUM, true, false)),
            Map.entry("browser.trace.start", READ_ONLY),
            Map.entry("browser.trace.stop", READ_ONLY),
            Map.entry("system.fs.list", SYSTEM_RISK),
            Map.entry("system.fs.read", SYSTEM_RISK),
            Map.entry("system.fs.search", SYSTEM_RISK),
            Map.entry("system.fs.write", SYSTEM_RISK),
            Map.entry("system.fs.apply_patch", SYSTEM_RISK),
            Map.entry("system.fs.mkdir", SYSTEM_RISK),
            Map.entry("system.fs.move", SYSTEM_RISK),
            Map.entry("system.fs.delete", SYSTEM_RISK),
            Map.entry("system.shell.run", SYSTEM_RISK),
            Map.entry("system.desktop.set_wallpaper", SYSTEM_RISK));

    private NodeToolPolicyCatalog() {
    }

    /**
     * 未登记的能力默认禁用且需要审批，避免新客户端上线时意外获得执行权限。
     */
    public static NodeToolPolicy policyFor(String toolName) {
        return POLICIES.getOrDefault(toolName, new NodeToolPolicy(RiskLevel.HIGH, false, true));
    }
}
