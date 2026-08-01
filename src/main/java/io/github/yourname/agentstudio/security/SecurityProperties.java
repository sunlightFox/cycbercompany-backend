package io.github.yourname.agentstudio.security;

import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * HTTP 调用者认证配置。
 *
 * <p>LOCAL 只用于绑定环回地址的个人本机模式；TOKEN 用于远程个人部署。生产配置通过
 * {@code AGENT_STUDIO_API_TOKEN} 环境变量注入令牌，仓库文件中不保存真实凭据。
 */
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(
        Mode mode,
        String apiToken,
        String tenantId,
        String userId) {

    public SecurityProperties {
        mode = mode == null ? Mode.LOCAL : mode;
        apiToken = apiToken == null ? "" : apiToken.trim();
        tenantId = blankToDefault(tenantId, "personal");
        userId = blankToDefault(userId, "remote-owner");
    }

    public boolean tokenMode() {
        return mode == Mode.TOKEN;
    }

    @Override
    public String toString() {
        return "SecurityProperties[mode=" + mode
                + ", apiToken=<redacted>, tenantId=" + tenantId
                + ", userId=" + userId + "]";
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public enum Mode {
        LOCAL,
        TOKEN;

        public static Mode from(String value) {
            return value == null || value.isBlank() ? LOCAL : valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }
}
