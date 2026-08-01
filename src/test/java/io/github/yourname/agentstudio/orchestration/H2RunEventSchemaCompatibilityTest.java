package io.github.yourname.agentstudio.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class H2RunEventSchemaCompatibilityTest {

    @Test
    void widensLegacyRunStatusEnumSoQueuedRunsCanBePersisted() throws Exception {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:legacy-run-status;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("""
                create table agent_run (
                    id varchar(255) primary key,
                    status enum(
                        'CREATED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT'
                    )
                )
                """);

        new H2RunEventSchemaCompatibility(jdbc).run(new DefaultApplicationArguments());

        assertThat(jdbc.update("insert into agent_run (id, status) values (?, ?)", "run-1", "QUEUED"))
                .isEqualTo(1);
    }
}
