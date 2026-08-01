package io.github.yourname.agentstudio.orchestration;

import java.util.List;
import java.sql.Connection;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Removes legacy Hibernate-generated H2 enum constraints after startup. */
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
            dropCheckConstraints("run_event");
            // Existing local databases were created before WAITING_APPROVAL and
            // CANCELLED existed. Hibernate ddl-auto does not widen its H2 CHECK
            // constraint, which otherwise makes an approval suspension fail.
            dropCheckConstraints("agent_run");
            widenLegacyRunStatusEnum();
        } catch (Exception ignored) {
            // A missing table or a database-specific information schema must not
            // prevent application startup; the normal ORM schema creation still applies.
        }
    }

    private void dropCheckConstraints(String tableName) {
        List<String> constraints = jdbc.queryForList(
                """
                select constraint_name from information_schema.table_constraints
                where upper(table_name) = ? and constraint_type = 'CHECK'
                """,
                String.class,
                tableName.toUpperCase(java.util.Locale.ROOT));
        for (String constraint : constraints) {
            jdbc.execute("alter table " + tableName + " drop constraint if exists \""
                    + constraint.replace("\"", "\"\"") + "\"");
        }
    }

    private void widenLegacyRunStatusEnum() {
        List<String> dataTypes = jdbc.queryForList(
                """
                select data_type from information_schema.columns
                where upper(table_name) = 'AGENT_RUN' and upper(column_name) = 'STATUS'
                """,
                String.class);
        if (dataTypes.stream().anyMatch("ENUM"::equalsIgnoreCase)) {
            // Hibernate 7 generated H2 ENUM columns for older @Enumerated values.
            // H2 does not extend that type when Java enum constants are added.
            jdbc.execute("alter table agent_run alter column status varchar(255)");
        }
    }
}
