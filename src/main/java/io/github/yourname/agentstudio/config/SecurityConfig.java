package io.github.yourname.agentstudio.config;

import static org.springframework.security.config.Customizer.withDefaults;

import io.github.yourname.agentstudio.security.ApiTokenAuthenticationFilter;
import io.github.yourname.agentstudio.security.SecurityProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 本地优先开发模式下的 Web 安全配置。
 *
 * <p>当前版本为了方便本机学习，允许访问业务接口；但仍保留 Spring Security 这层边界，
 * 因为领域服务已经依赖可信 {@code ActorContext}。接入 OAuth、JWT 或公司 SSO 时，
 * 主要替换边缘认证适配层，无须重写业务服务。
 */
@Configuration
class SecurityConfig {

    @Bean
    SecurityFilterChain applicationSecurity(
            HttpSecurity http,
            SecurityProperties security,
            ApiTokenAuthenticationFilter tokenFilter) throws Exception {
        http
                // REST API 使用 token/请求头式身份时通常不依赖浏览器 Cookie，因此开发版关闭 CSRF。
                .csrf(csrf -> csrf.disable())
                .cors(withDefaults())
                // H2 Console 以 frame 方式渲染，限制为 same-origin 才能在保留基本保护的前提下使用它。
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(tokenFilter, AnonymousAuthenticationFilter.class);
        if (security.tokenMode()) {
            http.authorizeHttpRequests(auth -> auth
                    .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/nodes/register").permitAll()
                    // 节点下载使用自己的长期凭据，进入控制器后由 NodeService 校验。
                    .requestMatchers(HttpMethod.GET, "/api/v1/node/skill-bundles/**").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/node/artifacts").permitAll()
                    .requestMatchers("/api/v1/node-channel").permitAll()
                    .anyRequest().authenticated());
        } else {
            http.authorizeHttpRequests(auth -> auth
                    .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                    .requestMatchers("/h2-console/**").permitAll()
                    .anyRequest().permitAll());
        }
        return http.build();
    }
}
