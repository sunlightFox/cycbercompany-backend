package io.github.yourname.agentstudio.nodeclient.config;

import java.io.IOException;

/**
 * 节点配置中长期凭证的最小加密边界。
 *
 * <p>节点配置除了服务器地址等普通信息，还含有 {@code nodeSecret}。这个接口将“如何保护
 * 字节内容”与 JSON 文件格式分离：配置读写逻辑不需要知道 DPAPI、Keychain 或其他平台实现的细节。
 * 目前 Windows 使用当前登录用户绑定的 DPAPI；其他平台仍会写入受权限限制的普通文件，不能误称为
 * 已加密。
 */
interface NodeConfigProtector {

    /** 配置文件中保存的保护方案标识，便于拒绝错误平台或未知格式。 */
    String protectionId();

    /** 是否真正提供静态数据加密。 */
    boolean protectsAtRest();

    /** 将 UTF-8 JSON 明文转换成可以写入配置文件的密文字节。 */
    byte[] protect(byte[] plaintext) throws IOException;

    /** 将配置文件中的密文字节恢复为 UTF-8 JSON 明文。 */
    byte[] unprotect(byte[] ciphertext) throws IOException;

    /** 根据节点实际操作系统选择默认实现，避免在非 Windows 上假装使用 DPAPI。 */
    static NodeConfigProtector forCurrentPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        return os.contains("windows") ? new WindowsDpapiNodeConfigProtector() : new PlaintextNodeConfigProtector();
    }
}
