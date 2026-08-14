package io.github.yourname.cycbercompany.nodeclient.tools;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 节点侧浏览器网络边界。
 *
 * <p>服务端已经做过一次业务授权，这里仍按真实 URL 和 DNS 结果复核，避免协议错误、旧服务端
 * 或跳转把浏览器带到本机文件、私网服务和云元数据地址。节点只接受服务端下发的精确私网主机
 * 列表，不自行扩大允许范围。
 */
final class BrowserNetworkPolicy {

    static final String POLICY_ARGUMENT = "_cycberCompanyBrowserPolicy";
    static final String ALLOWED_PRIVATE_HOSTS = "allowedPrivateHosts";
    private static final int MAX_URL_LENGTH = 4_096;
    private static final Set<String> CLOUD_METADATA_HOSTS = Set.of(
            "169.254.169.254",
            "100.100.100.200",
            "metadata.google.internal");

    private BrowserNetworkPolicy() {
    }

    static Set<String> allowedPrivateHosts(Map<String, Object> arguments) {
        Object rawPolicy = arguments == null ? null : arguments.get(POLICY_ARGUMENT);
        if (!(rawPolicy instanceof Map<?, ?> policy)) {
            return Set.of();
        }
        Object rawHosts = policy.get(ALLOWED_PRIVATE_HOSTS);
        if (!(rawHosts instanceof Iterable<?> hosts)) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (Object rawHost : hosts) {
            if (rawHost != null && !rawHost.toString().isBlank()) {
                normalized.add(normalizeHost(rawHost.toString()));
            }
        }
        return Set.copyOf(normalized);
    }

    static String requireAllowed(String rawUrl, Set<String> allowedPrivateHosts) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("browser.open requires a non-empty URL.");
        }
        String trimmed = rawUrl.trim();
        if (trimmed.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException("Browser URL exceeds the node length limit.");
        }
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Browser URL is invalid.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("Browser URL scheme must be http or https.");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("Browser URL must not contain embedded credentials.");
        }
        String host = normalizeHost(uri.getHost());
        if (host.isBlank()) {
            throw new IllegalArgumentException("Browser URL must contain a valid host.");
        }
        boolean explicitlyAllowed = allowedPrivateHosts.contains(host);
        if (CLOUD_METADATA_HOSTS.contains(host)) {
            throw new IllegalArgumentException("Browser URL targets a blocked cloud metadata host.");
        }
        if (("localhost".equals(host) || host.endsWith(".localhost")) && !explicitlyAllowed) {
            throw new IllegalArgumentException("Browser URL targets loopback or a private host that is not allowed by the server.");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (isBlockedAddress(address) && !explicitlyAllowed) {
                    throw new IllegalArgumentException(
                            "Browser URL targets loopback or a private host that is not allowed by the server.");
                }
            }
        } catch (UnknownHostException ex) {
            throw new IllegalArgumentException("Browser URL host could not be resolved safely.");
        }
        return uri.toASCIIString();
    }

    private static String normalizeHost(String host) {
        if (host == null) {
            return "";
        }
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("[") && normalized.endsWith("]")
                ? normalized.substring(1, normalized.length() - 1)
                : normalized;
    }

    private static boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address && bytes.length == 4) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            return first == 0
                    || first >= 224
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 192 && second == 0)
                    || (first == 198 && (second == 18 || second == 19));
        }
        if (address instanceof Inet6Address && bytes.length == 16) {
            return (Byte.toUnsignedInt(bytes[0]) & 0xfe) == 0xfc;
        }
        return false;
    }
}
