package io.github.yourname.agentstudio.node;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 把模型或 API 提交的参数转换为服务端真正允许下发给节点的参数。
 *
 * <p>隐藏策略字段会先被删除再由服务端重建，因此调用方不能伪造私网放行规则。截图路径也
 * 不允许由调用方决定，节点只能把文件写入自己的受管 Artifact 目录。
 */
@Component
public final class NodeToolRequestPolicy {

    public static final String BROWSER_POLICY_ARGUMENT = "_agentStudioBrowserPolicy";
    public static final String ALLOWED_PRIVATE_HOSTS = "allowedPrivateHosts";

    private final BrowserPolicyProperties browserPolicy;

    public NodeToolRequestPolicy(BrowserPolicyProperties browserPolicy) {
        this.browserPolicy = browserPolicy;
    }

    public Map<String, Object> prepare(String toolName, Map<String, Object> arguments) {
        Map<String, Object> prepared = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        prepared.remove(BROWSER_POLICY_ARGUMENT);

        if ("browser.open".equals(toolName)) {
            String safeUrl = BrowserUrlPolicy.requireAllowed(stringValue(prepared.get("url")), browserPolicy);
            prepared.put("url", safeUrl);
            prepared.put(BROWSER_POLICY_ARGUMENT, Map.of(
                    ALLOWED_PRIVATE_HOSTS, browserPolicy.allowedPrivateHosts()));
        } else if ("browser.screenshot".equals(toolName)) {
            prepared.remove("path");
        }

        NodeToolArgumentValidator.validate(toolName, prepared);
        return Map.copyOf(prepared);
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }
}
