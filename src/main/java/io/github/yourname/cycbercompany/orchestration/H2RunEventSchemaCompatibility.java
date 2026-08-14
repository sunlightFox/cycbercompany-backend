package io.github.yourname.cycbercompany.orchestration;

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
            // This index is portable across the supported H2 and PostgreSQL
            // backends. It protects the SSE resume cursor even when a future
            // publisher bypasses the application-level row lock.
            ensureRunEventSequenceUniqueness();
            if (!"H2".equalsIgnoreCase(product)) {
                return;
            }
            dropCheckConstraints("run_event");
            // Existing local databases were created before WAITING_APPROVAL and
            // CANCELLED existed. Hibernate ddl-auto does not widen its H2 CHECK
            // constraint, which otherwise makes an approval suspension fail.
            dropCheckConstraints("agent_run");
            widenLegacyRunStatusEnum();
            ensureAgentRunEventSequence();
            ensureNodeConnectionFencingToken();
            ensureNodeToolInvocationSchema();
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

    private void ensureRunEventSequenceUniqueness() {
        if (!tableExists("run_event")) {
            return;
        }
        Integer duplicates = jdbc.queryForObject(
                "select count(*) from (select tenant_id, run_id, sequence from run_event "
                        + "group by tenant_id, run_id, sequence having count(*) > 1)",
                Integer.class);
        if (duplicates != null && duplicates > 0) {
            // Do not destroy historical events automatically. The publisher still
            // serializes new writes; an operator must reconcile legacy duplicates
            // before this database can enforce uniqueness.
            return;
        }
        jdbc.execute("create unique index if not exists uk_run_event_tenant_run_sequence "
                + "on run_event(tenant_id, run_id, sequence)");
    }

    private void ensureNodeConnectionFencingToken() {
        ensureColumn("node_connection", "fencing_token", "bigint default 0 not null");
    }

    private void ensureAgentRunEventSequence() {
        // Added after older local H2 databases were created. A non-null zero
        // default is required because Hibernate may rebuild an existing H2
        // table while adding the field.
        ensureColumn("agent_run", "last_event_sequence", "bigint default 0 not null");
    }

    private void ensureNodeToolInvocationSchema() {
        // These fields were added after the first local H2 databases were created.
        // Keep the migration explicit so old audit rows remain readable and new
        // dispatches can be persisted without requiring users to delete data.
        ensureColumn("node_tool_invocation", "dispatch_attempt", "int default 0 not null");
        ensureColumn("node_tool_invocation", "arguments_digest", "varchar(128)");
        ensureColumn("node_tool_invocation", "idempotency_key", "varchar(255)");
        ensureColumn("node_tool_invocation", "policy_revision", "varchar(255)");
        ensureColumn("node_tool_invocation", "result_digest", "varchar(128)");
        ensureColumn("node_tool_invocation", "deadline_at", "timestamp");
        ensureColumn("node_tool_invocation", "accepted_at", "timestamp");
        ensureColumn("node_tool_invocation", "started_at", "timestamp");
        ensureColumn("node_tool_invocation", "finished_at", "timestamp");

        List<String> dataTypes = jdbc.queryForList(
                """
                select data_type from information_schema.columns
                where upper(table_name) = 'NODE_TOOL_INVOCATION' and upper(column_name) = 'STATUS'
                """,
                String.class);
        if (dataTypes.stream().anyMatch("ENUM"::equalsIgnoreCase)) {
            jdbc.execute("alter table node_tool_invocation alter column status varchar(255)");
        }
    }

    private void ensureColumn(String tableName, String columnName, String definition) {
        if (!tableExists(tableName)) {
            return;
        }
        List<String> columns = jdbc.queryForList(
                """
                select column_name from information_schema.columns
                where upper(table_name) = ? and upper(column_name) = ?
                """,
                String.class,
                tableName.toUpperCase(java.util.Locale.ROOT),
                columnName.toUpperCase(java.util.Locale.ROOT));
        if (columns.isEmpty()) {
            jdbc.execute("alter table " + tableName + " add column " + columnName + " " + definition);
        }
    }

    private boolean tableExists(String tableName) {
        return !jdbc.queryForList(
                "select table_name from information_schema.tables where upper(table_name) = ?",
                String.class,
                tableName.toUpperCase(java.util.Locale.ROOT)).isEmpty();
    }
}
