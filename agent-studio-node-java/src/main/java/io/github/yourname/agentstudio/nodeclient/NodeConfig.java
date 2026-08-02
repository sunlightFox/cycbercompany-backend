package io.github.yourname.agentstudio.nodeclient;

/**
 * 节点本地配置。
 *
 * <p>nodeSecret 是节点长期凭证。Windows 节点保存时使用当前登录用户绑定的 DPAPI；
 * 其他平台当前依赖仅当前用户可读写的配置文件，后续可接入 Keychain 或 Secret Service。
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
