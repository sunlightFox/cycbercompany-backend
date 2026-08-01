package io.github.yourname.agentstudio.node;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/** 对浏览器顶层导航做服务端 SSRF 校验。 */
final class BrowserUrlPolicy {

    private static final Set<String> CLOUD_METADATA_HOSTS = Set.of(
            "169.254.169.254",
            "100.100.100.200",
            "metadata.google.internal");

    private BrowserUrlPolicy() {
    }

    static String requireAllowed(String rawUrl, BrowserPolicyProperties policy) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("browser.open requires a non-empty URL.");
        }
        String trimmed = rawUrl.trim();
        if (trimmed.length() > policy.maxUrlLength()) {
            throw new IllegalArgumentException("Browser URL exceeds the configured length limit.");
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
        boolean explicitlyAllowed = policy.allowedPrivateHostSet().contains(host);
        if (CLOUD_METADATA_HOSTS.contains(host)) {
            throw new IllegalArgumentException("Browser URL targets a blocked cloud metadata host.");
        }
        if (isLocalhostName(host) && !explicitlyAllowed) {
            throw new IllegalArgumentException("Browser URL targets loopback or a private host that is not allowed.");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException ex) {
            // DNS 失败时采用 fail-closed，避免校验和真正导航看到不同的地址。
            throw new IllegalArgumentException("Browser URL host could not be resolved safely.");
        }
        for (InetAddress address : addresses) {
            if (isBlockedAddress(address) && !explicitlyAllowed) {
                throw new IllegalArgumentException("Browser URL targets loopback or a private host that is not allowed.");
            }
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

    private static boolean isLocalhostName(String host) {
        return "localhost".equals(host) || host.endsWith(".localhost");
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
            // fc00::/7 是 IPv6 Unique Local Address，Java 的 isSiteLocalAddress 并非所有版本都覆盖。
            return (Byte.toUnsignedInt(bytes[0]) & 0xfe) == 0xfc;
        }
        return false;
    }
}
