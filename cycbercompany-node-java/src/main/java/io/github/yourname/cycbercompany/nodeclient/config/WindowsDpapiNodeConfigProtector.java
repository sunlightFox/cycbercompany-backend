package io.github.yourname.cycbercompany.nodeclient.config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * 用 Windows DPAPI 保护节点长期凭证。
 *
 * <p>DPAPI 的 {@code CurrentUser} 作用域把密文绑定到当前 Windows 登录用户；即使有人复制
 * {@code config.json}，在另一个账户或机器上也不能恢复 nodeSecret。Java 标准库没有稳定的 DPAPI
 * 包装器，因此这里调用系统自带的 {@code powershell.exe}。敏感明文经标准输入传递，绝不放在命令行、
 * 日志或异常文本里。
 */
final class WindowsDpapiNodeConfigProtector implements NodeConfigProtector {

    private static final long TIMEOUT_SECONDS = 15;
    private static final int MAX_RESULT_BYTES = 128 * 1024;
    private static final String ENTROPY = "cycbercompany-node-config-v1";
    private static final String OUTPUT_MARKER = "CYCBERCOMPANY_DPAPI:";

    @Override
    public String protectionId() {
        return "windows-dpapi-current-user-v1";
    }

    @Override
    public boolean protectsAtRest() {
        return true;
    }

    @Override
    public byte[] protect(byte[] plaintext) throws IOException {
        return invoke("protect", plaintext);
    }

    @Override
    public byte[] unprotect(byte[] ciphertext) throws IOException {
        return invoke("unprotect", ciphertext);
    }

    private static byte[] invoke(String operation, byte[] input) throws IOException {
        if (input == null || input.length == 0) {
            throw new IOException("Node config protection input must not be empty.");
        }
        try {
            Process process = new ProcessBuilder(
                    "powershell.exe",
                    "-NoProfile",
                    "-NonInteractive",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-EncodedCommand",
                    encodedScript(operation))
                    .redirectErrorStream(true)
                    .start();

            // 长期凭证只通过子进程 stdin 进入 DPAPI；它不会出现在进程参数或 PowerShell 历史中。
            try (OutputStream stream = process.getOutputStream()) {
                stream.write(Base64.getEncoder().encode(input));
                stream.write('\n');
            }

            byte[] output = readBounded(process);
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Windows DPAPI did not respond before the configured timeout.");
            }
            if (process.exitValue() != 0) {
                // 不返回 PowerShell 输出，避免将错误信息中的意外敏感内容带到上层日志。
                throw new IOException("Windows DPAPI rejected the node config operation.");
            }
            try {
                String text = new String(output, StandardCharsets.UTF_8);
                int marker = text.lastIndexOf(OUTPUT_MARKER);
                if (marker < 0) {
                    throw new IOException("Windows DPAPI did not return a marked protected payload.");
                }
                String suffix = text.substring(marker + OUTPUT_MARKER.length()).trim();
                int end = 0;
                while (end < suffix.length()) {
                    char current = suffix.charAt(end);
                    if (!((current >= 'A' && current <= 'Z')
                            || (current >= 'a' && current <= 'z')
                            || (current >= '0' && current <= '9')
                            || current == '+' || current == '/' || current == '=')) {
                        break;
                    }
                    end++;
                }
                String encoded = suffix.substring(0, end);
                if (encoded.isBlank()) {
                    throw new IOException("Windows DPAPI returned an empty protected payload.");
                }
                return Base64.getDecoder().decode(encoded);
            } catch (IllegalArgumentException ex) {
                throw new IOException("Windows DPAPI returned an invalid protected payload.", ex);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while protecting the node config.", ex);
        }
    }

    private static byte[] readBounded(Process process) throws IOException {
        try (var stream = process.getInputStream(); var captured = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8_192];
            for (int read; (read = stream.read(buffer)) != -1;) {
                if (captured.size() + read > MAX_RESULT_BYTES) {
                    process.destroyForcibly();
                    throw new IOException("Windows DPAPI returned an unexpectedly large payload.");
                }
                captured.write(buffer, 0, read);
            }
            return captured.toByteArray();
        }
    }

    /** 脚本本身是固定文本；操作值只来自内部常量，不能由模型或配置文件控制。 */
    private static String encodedScript(String operation) {
        String script = """
                $ErrorActionPreference = 'Stop'
                [void](Add-Type -AssemblyName System.Security)
                $inputBase64 = [Console]::In.ReadToEnd().Trim()
                if ([string]::IsNullOrWhiteSpace($inputBase64)) { throw 'Missing input' }
                $inputBytes = [Convert]::FromBase64String($inputBase64)
                $entropy = [Text.Encoding]::UTF8.GetBytes('%s')
                if ('%s' -eq 'protect') {
                    $result = [Security.Cryptography.ProtectedData]::Protect(
                        $inputBytes, $entropy, [Security.Cryptography.DataProtectionScope]::CurrentUser)
                } else {
                    $result = [Security.Cryptography.ProtectedData]::Unprotect(
                        $inputBytes, $entropy, [Security.Cryptography.DataProtectionScope]::CurrentUser)
                }
                [Console]::Out.Write('%s' + [Convert]::ToBase64String($result))
                """.formatted(ENTROPY, operation, OUTPUT_MARKER);
        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    }
}
