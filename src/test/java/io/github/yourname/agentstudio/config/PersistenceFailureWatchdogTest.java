package io.github.yourname.agentstudio.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PersistenceFailureWatchdogTest {

    @Test
    void exitsAfterConfiguredNumberOfConsecutivePersistenceFailures() {
        AtomicInteger exits = new AtomicInteger();
        JdbcTemplate unavailable = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:missing-watchdog;DB_CLOSE_DELAY=-1", "sa", ""));
        PersistenceFailureWatchdog watchdog = new PersistenceFailureWatchdog(
                unavailable, new PersistenceProperties(true, 1_000, 2), ignored -> exits.incrementAndGet());
        watchdog.onApplicationEvent(null);

        watchdog.verifyStore();
        assertThat(exits).hasValue(0);
        watchdog.verifyStore();

        assertThat(exits).hasValue(1);
    }

    @Test
    void successfulCheckResetsTheFailureBudget() {
        AtomicInteger exits = new AtomicInteger();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:watchdog-ok;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("create table model_profile (id varchar(32))");
        jdbc.execute("create table agent_definition (id varchar(32))");
        jdbc.execute("create table run_execution_outbox (id varchar(32))");
        PersistenceFailureWatchdog watchdog = new PersistenceFailureWatchdog(
                jdbc, new PersistenceProperties(true, 1_000, 2), ignored -> exits.incrementAndGet());
        watchdog.onApplicationEvent(null);

        watchdog.verifyStore();
        watchdog.verifyStore();

        assertThat(exits).hasValue(0);
    }
}
