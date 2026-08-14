package io.github.yourname.cycbercompany.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Lazily owns an explicitly configured provider Worker. The command is a JSON
 * argv array, never a shell string, and the Worker remains outside Spring's JVM.
 */
@Service
public class MediaRuntimeProcessService {
    private final ObjectMapper mapper;
    private final String commandJson;
    private final String workingDirectory;
    private final AtomicReference<Process> process = new AtomicReference<>();

    public MediaRuntimeProcessService(ObjectMapper mapper,
                                      @Value("${app.demos.video.runtime-command:}") String commandJson,
                                      @Value("${app.demos.video.runtime-working-directory:}") String workingDirectory) {
        this.mapper = mapper;
        this.commandJson = commandJson == null ? "" : commandJson.trim();
        this.workingDirectory = workingDirectory == null ? "" : workingDirectory.trim();
    }

    /** Starts the configured Worker once, returning false when no command is configured. */
    public boolean ensureStarted() {
        Process current = process.get();
        if (current != null && current.isAlive()) return true;
        if (commandJson.isBlank()) return false;
        synchronized (process) {
            current = process.get();
            if (current != null && current.isAlive()) return true;
            try {
                List<String> command = parseCommand();
                ProcessBuilder builder = new ProcessBuilder(command)
                        .redirectError(ProcessBuilder.Redirect.INHERIT)
                        .redirectOutput(ProcessBuilder.Redirect.INHERIT);
                if (!workingDirectory.isBlank()) builder.directory(Path.of(workingDirectory).toFile());
                process.set(builder.start());
                return true;
            } catch (Exception ignored) {
                process.set(null);
                return false;
            }
        }
    }

    public boolean configured() {
        return !commandJson.isBlank();
    }

    private List<String> parseCommand() throws IOException {
        List<String> command = mapper.readValue(commandJson,
                mapper.getTypeFactory().constructCollectionType(List.class, String.class));
        if (command == null || command.isEmpty() || command.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IOException("Runtime command must be a non-empty JSON argv array.");
        }
        return List.copyOf(command);
    }

    @PreDestroy
    void stop() {
        Process current = process.getAndSet(null);
        if (current != null && current.isAlive()) current.destroy();
    }
}
