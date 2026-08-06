package io.github.yourname.agentstudio.config;

import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Verifies that the database can read the tables used by the control plane.
 *
 * <p>A plain connection validation can still report H2 as healthy after a
 * damaged page is encountered. Reading small core tables makes the readiness
 * signal useful to launchers and clients instead of exposing repeated 500s.
 */
@Component("corePersistence")
public final class CorePersistenceHealthIndicator extends AbstractHealthIndicator {

    private final JdbcTemplate jdbcTemplate;

    public CorePersistenceHealthIndicator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        jdbcTemplate.queryForObject("select count(*) from model_profile", Long.class);
        jdbcTemplate.queryForObject("select count(*) from agent_definition", Long.class);
        jdbcTemplate.queryForObject("select count(*) from run_execution_outbox", Long.class);
        builder.up();
    }
}
