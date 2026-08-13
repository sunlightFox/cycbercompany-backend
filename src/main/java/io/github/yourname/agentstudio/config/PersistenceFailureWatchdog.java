package io.github.yourname.agentstudio.config;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Optional fail-fast policy for an externally supervised persistent-store failure.
 *
 * <p>H2 closes its store after an MVStore panic and cannot be repaired inside the same JVM. In a
 * supervised deployment an operator may opt in so the supervisor can restart the process. It is
 * deliberately disabled by default: a transient database or network failure must not turn one
 * failed Run into an application-wide outage.
 */
@Component
@ConditionalOnProperty(prefix = "app.persistence", name = "watchdog-enabled", havingValue = "true")
public final class PersistenceFailureWatchdog implements ApplicationListener<ContextRefreshedEvent> {

    private final JdbcTemplate jdbc;
    private final PersistenceProperties properties;
    private final ProcessExit processExit;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile boolean ready;

    @Autowired
    public PersistenceFailureWatchdog(
            JdbcTemplate jdbc,
            PersistenceProperties properties,
            ApplicationContext context) {
        this(jdbc, properties, status -> {
            Thread thread = new Thread(() -> System.exit(status), "persistence-failure-exit");
            thread.setDaemon(true);
            thread.start();
        });
    }

    PersistenceFailureWatchdog(JdbcTemplate jdbc, PersistenceProperties properties, ProcessExit processExit) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.processExit = processExit;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        ready = true;
    }

    @Scheduled(fixedDelayString = "${app.persistence.watchdog-interval-ms:30000}")
    void verifyStore() {
        if (!ready) {
            return;
        }
        try {
            jdbc.queryForObject("select count(*) from model_profile", Long.class);
            jdbc.queryForObject("select count(*) from agent_definition", Long.class);
            jdbc.queryForObject("select count(*) from run_execution_outbox", Long.class);
            consecutiveFailures.set(0);
        } catch (RuntimeException ex) {
            if (consecutiveFailures.incrementAndGet() >= properties.watchdogFailureThreshold()) {
                processExit.exit(1);
            }
        }
    }

    @FunctionalInterface
    interface ProcessExit {
        void exit(int status);
    }
}
