package io.github.yourname.agentstudio.config;

import static org.springframework.security.config.Customizer.withDefaults;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Local-first security profile.
 *
 * <p>The first deliverable is meant to run on a developer workstation without
 * an identity provider. We still keep Spring Security in the stack because the
 * domain services already require a trusted ActorContext; enterprise auth can
 * later replace only the edge adapter instead of rewriting business code.
 */
@Configuration
class SecurityConfig {

    @Bean
    SecurityFilterChain localSecurity(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(withDefaults())
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .requestMatchers("/h2-console/**").permitAll()
                        .anyRequest().permitAll())
                .build();
    }
}
