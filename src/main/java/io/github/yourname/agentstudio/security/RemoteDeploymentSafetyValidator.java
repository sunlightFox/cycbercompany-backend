package io.github.yourname.agentstudio.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 在应用接受远程请求前检查部署边界。
 *
 * <p>只要监听地址不是环回，就必须同时满足真实 API 认证、TLS 和关闭 H2 Console。任何一项
 * 缺失都让 Spring 启动失败，避免把本地学习配置误当成远程控制服务暴露出去。
 */
@Component
public final class RemoteDeploymentSafetyValidator implements InitializingBean {

    private static final int MIN_API_TOKEN_LENGTH = 32;

    private final SecurityProperties security;
    private final Environment environment;

    public RemoteDeploymentSafetyValidator(SecurityProperties security, Environment environment) {
        this.security = security;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        String address = environment.getProperty("server.address", "127.0.0.1").trim();
        if (isLoopback(address)) {
            return;
        }
        if (allowsLocalProxy(address)) {
            return;
        }
        if (!security.tokenMode()) {
            throw unsafe("app.security.mode must be TOKEN");
        }
        if (security.apiToken().length() < MIN_API_TOKEN_LENGTH) {
            throw unsafe("AGENT_STUDIO_API_TOKEN must contain at least " + MIN_API_TOKEN_LENGTH + " characters");
        }
        if (!environment.getProperty("server.ssl.enabled", Boolean.class, false)) {
            throw unsafe("server.ssl.enabled must be true so node and API credentials use TLS/WSS");
        }
        if (environment.getProperty("spring.h2.console.enabled", Boolean.class, false)) {
            throw unsafe("spring.h2.console.enabled must be false");
        }
    }

    static boolean isLoopback(String address) {
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException ex) {
            return false;
        }
    }

    private boolean allowsLocalProxy(String address) {
        // Docker port forwarding reaches the container over its non-loopback interface even
        // when the host publishes that port to 127.0.0.1 only. The host mapping is outside
        // this process, so require an explicit, default-off operator assertion for that case.
        return "0.0.0.0".equals(address)
                && environment.getProperty("app.security.allow-local-proxy", Boolean.class, false);
    }

    private static IllegalStateException unsafe(String reason) {
        return new IllegalStateException("Unsafe remote deployment refused: " + reason + ".");
    }
}
