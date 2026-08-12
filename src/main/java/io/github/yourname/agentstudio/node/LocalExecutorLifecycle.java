package io.github.yourname.agentstudio.node;

import io.github.yourname.agentstudio.config.AppProperties;
import io.github.yourname.agentstudio.config.LocalExecutorProperties;
import io.github.yourname.agentstudio.nodeclient.AgentStudioNodeApplication;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Starts the trusted same-host executor after the web server is ready. */
@Component
public final class LocalExecutorLifecycle implements ApplicationRunner, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(LocalExecutorLifecycle.class);

    private final AppProperties properties;
    private final LocalExecutorProperties localExecutorProperties;
    private final Environment environment;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean running;

    public LocalExecutorLifecycle(
            AppProperties properties, LocalExecutorProperties localExecutorProperties, Environment environment) {
        this.properties = properties;
        this.localExecutorProperties = localExecutorProperties;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!localExecutorProperties.enabled()) {
            log.info("Managed local executor is disabled by configuration.");
            return;
        }
        Path workspace = workspace();
        String serverUrl = serverUrl();
        log.info("Starting managed local executor against {} with workspace {}.", serverUrl, workspace);
        executor.submit(() -> {
            try {
                AgentStudioNodeApplication.main(new String[]{
                        "start-local",
                        "--server", serverUrl,
                        "--name", localName(),
                        "--workspace", workspace.toString(),
                        "--config", workspace.resolve(".agent-studio-local-executor.json").toString()
                });
            } catch (Exception ex) {
                log.error("Managed local executor stopped unexpectedly.", ex);
            }
        });
        running = true;
    }

    private Path workspace() {
        Path configured = localExecutorProperties.workspace();
        Path workspace = configured == null
                ? properties.dataDir().resolve("workspace")
                : configured;
        try {
            return Files.createDirectories(workspace.toAbsolutePath().normalize());
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot create local executor workspace: " + workspace, ex);
        }
    }

    private static String localName() {
        try {
            return InetAddress.getLocalHost().getHostName() + " (local executor)";
        } catch (Exception ignored) {
            return "This computer";
        }
    }

    private String serverUrl() {
        String configured = localExecutorProperties.serverUrl();
        if (configured != null && !configured.isBlank()) {
            return configured.trim().replaceAll("/+$", "");
        }
        String scheme = environment.getProperty("server.ssl.enabled", Boolean.class, false) ? "https" : "http";
        String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "8080"));
        return scheme + "://127.0.0.1:" + port;
    }

    @Override public void start() { }
    @Override public void stop() { executor.shutdownNow(); running = false; }
    @Override public boolean isRunning() { return running; }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return Integer.MAX_VALUE; }
}
