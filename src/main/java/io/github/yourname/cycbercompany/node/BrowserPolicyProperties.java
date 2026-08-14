package io.github.yourname.cycbercompany.node;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 服务端浏览器网络边界。
 *
 * <p>默认不允许节点浏览器访问环回、私网和链路本地地址。开发者确实需要做本机前后端
 * 联调时，只能通过服务端配置精确加入主机名或 IP；模型参数和节点能力上报都不能扩大
 * 这个列表。
 */
@ConfigurationProperties(prefix = "app.nodes.browser")
public record BrowserPolicyProperties(List<String> allowedPrivateHosts, int maxUrlLength) {

    private static final int DEFAULT_MAX_URL_LENGTH = 4_096;

    public BrowserPolicyProperties {
        allowedPrivateHosts = normalizeHosts(allowedPrivateHosts);
        maxUrlLength = maxUrlLength <= 0 ? DEFAULT_MAX_URL_LENGTH : maxUrlLength;
    }

    public static BrowserPolicyProperties secureDefaults() {
        return new BrowserPolicyProperties(List.of(), DEFAULT_MAX_URL_LENGTH);
    }

    public Set<String> allowedPrivateHostSet() {
        return Set.copyOf(allowedPrivateHosts);
    }

    private static List<String> normalizeHosts(List<String> hosts) {
        if (hosts == null || hosts.isEmpty()) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String host : hosts) {
            if (host != null && !host.isBlank()) {
                normalized.add(host.trim().toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(normalized);
    }
}
