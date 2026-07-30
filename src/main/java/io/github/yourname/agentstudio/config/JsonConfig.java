package io.github.yourname.agentstudio.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shared JSON mapper for API DTOs and local metadata files.
 *
 * <p>The explicit bean keeps the project independent from Spring Boot starter
 * defaults and ensures {@link java.time.Instant} values are serialized in a
 * stable ISO-8601 form.
 */
@Configuration
class JsonConfig {

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
