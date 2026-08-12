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
            Map.entry("git.review", READ_ONLY),
            Map.entry("git.stage", HIGH_RISK),
            Map.entry("git.commit", HIGH_RISK),
            Map.entry("project.inspect", READ_ONLY),
            Map.entry("project.discover", READ_ONLY),
            Map.entry("project.map", READ_ONLY),
            Map.entry("project.symbols", READ_ONLY),
            Map.entry("project.references", READ_ONLY),
            Map.entry("project.diagnose", READ_ONLY),
            Map.entry("fs.list", READ_ONLY),
            Map.entry("fs.read", READ_ONLY),
            Map.entry("fs.search", READ_ONLY),
            Map.entry("fs.write", WORKSPACE_WRITE),
            Map.entry("fs.apply_patch", WORKSPACE_WRITE),
            Map.entry("fs.apply_patch_batch", WORKSPACE_WRITE),
            Map.entry("shell.run", HIGH_RISK),
            Map.entry("process.start", HIGH_RISK),
            Map.entry("process.status", READ_ONLY),
            Map.entry("process.logs", READ_ONLY),
            // 仅允许托管进程的字面量回环 GET，节点端不会返回响应正文；可作为本机联调就绪证据。
            Map.entry("process.wait_http", READ_ONLY),
            Map.entry("process.stop", HIGH_RISK),
            Map.entry("system.process.start", SYSTEM_RISK),
            Map.entry("system.process.status", SYSTEM_RISK),
            Map.entry("system.process.logs", SYSTEM_RISK),
            Map.entry("system.process.wait_http", SYSTEM_RISK),
            Map.entry("system.process.stop", SYSTEM_RISK),
            Map.entry("browser.open", new NodeToolPolicy(RiskLevel.MEDIUM, true, false)),
            Map.entry("browser.snapshot", READ_ONLY),
            Map.entry("browser.verify", READ_ONLY),
            Map.entry("browser.tabs", READ_ONLY),
            Map.entry("browser.switch_tab", READ_ONLY),
            Map.entry("browser.close_tab", new NodeToolPolicy(RiskLevel.MEDIUM, true, true)),
            Map.entry("browser.download", new NodeToolPolicy(RiskLevel.MEDIUM, true, true)),
            Map.entry("browser.upload", new NodeToolPolicy(RiskLevel.HIGH, false, true)),
            Map.entry("browser.wait", READ_ONLY),
            Map.entry("browser.wait_response", READ_ONLY),
            Map.entry("browser.screenshot", READ_ONLY),
            Map.entry("browser.click", new NodeToolPolicy(RiskLevel.MEDIUM, true, false)),
            Map.entry("browser.type", new NodeToolPolicy(RiskLevel.MEDIUM, true, false)),
            Map.entry("browser.hover", new NodeToolPolicy(RiskLevel.LOW, true, false)),
            Map.entry("browser.press", new NodeToolPolicy(RiskLevel.MEDIUM, true, false)),
            Map.entry("browser.select_option", new NodeToolPolicy(RiskLevel.MEDIUM, true, false)),
            Map.entry("browser.trace.start", READ_ONLY),
            Map.entry("browser.trace.stop", READ_ONLY),
            Map.entry("skill.resource.read", READ_ONLY),
            // 只有显式启用 Docker Runtime 的节点才会上报脚本能力，执行仍逐次审批。
            Map.entry("skill.script.run", new NodeToolPolicy(RiskLevel.HIGH, true, true)),
            Map.entry("system.fs.list", SYSTEM_RISK),
            Map.entry("system.fs.read", SYSTEM_RISK),
            Map.entry("system.fs.search", SYSTEM_RISK),
            Map.entry("system.fs.write", SYSTEM_RISK),
            Map.entry("system.fs.apply_patch", SYSTEM_RISK),
            Map.entry("system.fs.mkdir", SYSTEM_RISK),
            Map.entry("system.fs.move", SYSTEM_RISK),
            Map.entry("system.fs.delete", SYSTEM_RISK),
            Map.entry("system.desktop.organize.list", SYSTEM_RISK),
            Map.entry("system.desktop.organize.mkdir", SYSTEM_RISK),
            Map.entry("system.desktop.organize.write", SYSTEM_RISK),
            Map.entry("system.desktop.organize.move", SYSTEM_RISK),
            Map.entry("system.desktop.organize.delete", SYSTEM_RISK),
            Map.entry("system.shell.run", SYSTEM_RISK),
            Map.entry("system.software.query", SYSTEM_RISK),
            Map.entry("system.software.install", SYSTEM_RISK),
            Map.entry("system.software.uninstall", SYSTEM_RISK),
            Map.entry("system.service.query", SYSTEM_RISK),
            Map.entry("system.service.stop", SYSTEM_RISK),
            Map.entry("system.service.set_start_mode", SYSTEM_RISK),
            Map.entry("system.os_process.query", SYSTEM_RISK),
            Map.entry("system.os_process.terminate", SYSTEM_RISK),
            Map.entry("system.privilege.query", READ_ONLY),
            Map.entry("system.uninstall.preflight", READ_ONLY),
            Map.entry("system.uninstall.execute", SYSTEM_RISK),
            Map.entry("system.desktop.set_wallpaper", SYSTEM_RISK),
            Map.entry("system.desktop.session.snapshot", SYSTEM_RISK),
            Map.entry("system.desktop.application.start", SYSTEM_RISK),
            Map.entry("system.desktop.screenshot", SYSTEM_RISK),
            Map.entry("system.desktop.window.activate", SYSTEM_RISK),
            Map.entry("system.desktop.ui.snapshot", SYSTEM_RISK),
            Map.entry("system.desktop.ui.verify", SYSTEM_RISK),
            Map.entry("system.desktop.ui.wait", SYSTEM_RISK),
            Map.entry("system.desktop.ui.read_value", SYSTEM_RISK),
            Map.entry("system.desktop.ui.click", SYSTEM_RISK),
            Map.entry("system.desktop.ui.type", SYSTEM_RISK),
            Map.entry("system.desktop.keyboard.press", SYSTEM_RISK),
            Map.entry("system.desktop.clipboard.get", SYSTEM_RISK),
            Map.entry("system.desktop.clipboard.set", SYSTEM_RISK));

    private NodeToolPolicyCatalog() {
    }

    /**
     * 未登记的能力默认禁用且需要审批，避免新客户端上线时意外获得执行权限。
     */
    public static NodeToolPolicy policyFor(String toolName) {
        return POLICIES.getOrDefault(toolName, new NodeToolPolicy(RiskLevel.HIGH, false, true));
    }

    /**
     * The backend-owned local executor is explicitly opted into unrestricted local operation.
     * Registered and sandbox nodes always use {@link #policyFor(String)} instead.
     */
    public static NodeToolPolicy managedLocalPolicyFor(String toolName) {
        NodeToolPolicy base = policyFor(toolName);
        return new NodeToolPolicy(base.riskLevel(), true, false);
    }
}
