package io.github.yourname.cycbercompany.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void addsFencingTokenToLegacyNodeConnectionTableIdempotently() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:legacy-node-connection;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("create table node_connection (id varchar(255) primary key, name varchar(255))");

        var migration = new H2RunEventSchemaCompatibility(jdbc);
        migration.run(new DefaultApplicationArguments());
        migration.run(new DefaultApplicationArguments());

        assertThat(jdbc.queryForObject(
                "select column_name from information_schema.columns "
                        + "where upper(table_name) = 'NODE_CONNECTION' and upper(column_name) = 'FENCING_TOKEN'",
                String.class)).isEqualTo("FENCING_TOKEN");
        assertThat(jdbc.update("insert into node_connection (id, name) values (?, ?)", "node-1", "test"))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select fencing_token from node_connection where id = ?", Long.class, "node-1"))
                .isZero();
    }

    @Test
    void addsLegacyNodeInvocationAuditColumnsIdempotently() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:legacy-node-invocation;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("""
                create table node_tool_invocation (
                    id varchar(255) primary key,
                    status varchar(255),
                    arguments_json clob,
                    result_json clob,
                    error_message clob
                )
                """);

        var migration = new H2RunEventSchemaCompatibility(jdbc);
        migration.run(new DefaultApplicationArguments());
        migration.run(new DefaultApplicationArguments());

        assertThat(jdbc.update("insert into node_tool_invocation (id, status) values (?, ?)",
                "inv-1", "DISPATCHED")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select dispatch_attempt from node_tool_invocation where id = ?", Integer.class, "inv-1"))
                .isZero();
    }

    @Test
    void addsRunEventSequenceUniquenessToLegacySchemaIdempotently() throws Exception {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:legacy-run-event-sequence;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("""
                create table run_event (
                    id bigint primary key,
                    tenant_id varchar(255) not null,
                    run_id varchar(255) not null,
                    sequence bigint not null
                )
                """);

        var migration = new H2RunEventSchemaCompatibility(jdbc);
        migration.run(new DefaultApplicationArguments());
        migration.run(new DefaultApplicationArguments());

        assertThat(jdbc.update("insert into run_event (id, tenant_id, run_id, sequence) values (1, 'tenant', 'run', 1)"))
                .isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update(
                "insert into run_event (id, tenant_id, run_id, sequence) values (2, 'tenant', 'run', 1)"))
                .isInstanceOf(Exception.class);
    }

    @Test
    void addsLastEventSequenceToLegacyAgentRunSchemaIdempotently() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:legacy-agent-run-sequence;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("create table agent_run (id varchar(255) primary key, status varchar(255))");

        var migration = new H2RunEventSchemaCompatibility(jdbc);
        migration.run(new DefaultApplicationArguments());
        migration.run(new DefaultApplicationArguments());

        assertThat(jdbc.update(
                "insert into agent_run (id, status) values (?, ?)", "run-1", "QUEUED"))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select last_event_sequence from agent_run where id = ?", Long.class, "run-1"))
                .isZero();
    }
}
