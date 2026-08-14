package io.github.yourname.cycbercompany.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SystemStatusControllerTest {

    @Test
    void reportsReadyWhenCoreTablesAreReadable() {
        var jdbc = jdbc("ready");
        jdbc.execute("create table model_profile (id varchar(255) primary key)");
        jdbc.execute("create table agent_definition (id varchar(255) primary key)");
        jdbc.execute("create table run_execution_outbox (id varchar(255) primary key)");

        var response = new SystemStatusController(jdbc).status();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(
                new SystemStatusController.SystemStatusView("READY", null, "Control plane is ready."));
    }

    @Test
    void reportsPersistenceUnavailableWithoutLeakingDatabaseDetails() {
        var response = new SystemStatusController(jdbc("unhealthy")).status();

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isEqualTo(new SystemStatusController.SystemStatusView(
                "UNHEALTHY", "PERSISTENCE_UNAVAILABLE", "The control-plane data store is unavailable."));
    }

    private static JdbcTemplate jdbc(String databaseName) {
        return new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:system-status-" + databaseName + ";DB_CLOSE_DELAY=-1", "sa", ""));
    }
}
