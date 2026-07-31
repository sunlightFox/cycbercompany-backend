package io.github.yourname.agentstudio.nodeclient;

/**
 * 节点本地配置。
 *
 * <p>nodeSecret 是节点长期凭证，本地保存时第一版先明文存储；后续 Windows 可接 DPAPI，
 * macOS 接 Keychain，Linux 接 Secret Service 或文件权限加固。
 */
public record NodeConfig(
        String serverUrl,
        String nodeId,
        String nodeSecret,
        String websocketUrl,
        String name,
        String workspaceRoot,
        String accessMode) {

    public NodeAccessMode resolvedAccessMode() {
        return NodeAccessMode.from(accessMode);
    }
}
