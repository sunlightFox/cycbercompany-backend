package io.github.yourname.agentstudio.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class CorePersistenceHealthIndicatorTest {

    @Test
    void reportsUpWhenCoreControlPlaneTablesAreReadable() {
        var jdbc = jdbcTemplate("healthy");
        jdbc.execute("create table model_profile (id varchar(255) primary key)");
        jdbc.execute("create table agent_definition (id varchar(255) primary key)");
        jdbc.execute("create table run_execution_outbox (id varchar(255) primary key)");

        var health = new CorePersistenceHealthIndicator(jdbc).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void reportsDownWhenACoreControlPlaneTableCannotBeRead() {
        var jdbc = jdbcTemplate("missing-outbox");
        jdbc.execute("create table model_profile (id varchar(255) primary key)");
        jdbc.execute("create table agent_definition (id varchar(255) primary key)");

        var health = new CorePersistenceHealthIndicator(jdbc).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("error");
    }

    private static JdbcTemplate jdbcTemplate(String databaseName) {
        return new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:core-persistence-" + databaseName + ";DB_CLOSE_DELAY=-1", "sa", ""));
    }
}
