package io.github.yourname.agentstudio.nodeclient.config;

import java.io.IOException;

/**
 * 非 Windows 平台的显式降级实现。
 *
 * <p>它存在的目的不是把明文伪装为加密，而是让 {@link NodeConfigStore} 能明确区分
 * “仅依赖文件权限”与“使用系统密钥保护”。后续接入 macOS Keychain 或 Linux Secret Service 时，
 * 只需替换这里的选择逻辑。
 */
final class PlaintextNodeConfigProtector implements NodeConfigProtector {

    @Override
    public String protectionId() {
        return "plaintext-file-permissions-v1";
    }

    @Override
    public boolean protectsAtRest() {
        return false;
    }

    @Override
    public byte[] protect(byte[] plaintext) throws IOException {
        throw new IOException("This platform has no node-config encryption provider.");
    }

    @Override
    public byte[] unprotect(byte[] ciphertext) throws IOException {
        throw new IOException("This platform cannot decrypt a protected node config.");
    }
}
