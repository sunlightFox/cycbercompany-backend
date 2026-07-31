package io.github.yourname.agentstudio.orchestration;

import java.util.List;
import java.sql.Connection;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Removes the legacy Hibernate-generated H2 enum constraint after startup. */
@Component
@Order(100)
class H2RunEventSchemaCompatibility implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    H2RunEventSchemaCompatibility(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            if (!"H2".equalsIgnoreCase(product)) {
                return;
            }
            List<String> constraints = jdbc.queryForList(
                    """
                    select constraint_name from information_schema.table_constraints
                    where upper(table_name) = 'RUN_EVENT' and constraint_type = 'CHECK'
                    """,
                    String.class);
            for (String constraint : constraints) {
                jdbc.execute("alter table run_event drop constraint if exists \"" + constraint.replace("\"", "\"\"") + "\"");
            }
        } catch (Exception ignored) {
            // A missing table or a database-specific information schema must not
            // prevent application startup; the normal ORM schema creation still applies.
        }
    }
}
