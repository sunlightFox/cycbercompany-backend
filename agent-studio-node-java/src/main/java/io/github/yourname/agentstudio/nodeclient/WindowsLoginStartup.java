package io.github.yourname.agentstudio.nodeclient;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * A current-user Windows Startup entry for the packaged companion.
 *
 * <p>The entry is opt-in from the native window. It starts only the packaged
 * companion executable and is deliberately kept outside the registry so users
 * can inspect and remove it from their Startup folder.</p>
 */
final class WindowsLoginStartup {

    private static final String MARKER = ":: CycberCompany managed login startup";
    private static final String FILE_NAME = "AgentStudioNode-startup.cmd";
    private static final Charset STARTUP_ENCODING = StandardCharsets.UTF_16LE;

    private final Path startupFile;
    private final Path executable;

    WindowsLoginStartup(Path startupFolder, Path executable) {
        this.startupFile = startupFolder.resolve(FILE_NAME).toAbsolutePath().normalize();
        this.executable = executable.toAbsolutePath().normalize();
    }

    static Optional<WindowsLoginStartup> forPackagedApp() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return Optional.empty();
        }
        String appPath = System.getProperty("jpackage.app-path", "").trim();
        String appData = System.getenv("APPDATA");
        if (appPath.isEmpty() || appData == null || appData.isBlank()) {
            return Optional.empty();
        }
        Path executable = Path.of(appPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(executable)) {
            return Optional.empty();
        }
        Path startupFolder = Path.of(appData, "Microsoft", "Windows", "Start Menu", "Programs", "Startup");
        return Optional.of(new WindowsLoginStartup(startupFolder, executable));
    }

    boolean isEnabled() throws IOException {
        if (!Files.isRegularFile(startupFile)) {
            return false;
        }
        return Files.readString(startupFile, STARTUP_ENCODING).equals(content());
    }

    void setEnabled(boolean enabled) throws IOException {
        if (enabled) {
            if (Files.exists(startupFile) && !isEnabled()) {
                throw new IOException("The existing startup entry is not managed by CycberCompany: " + startupFile);
            }
            Files.createDirectories(startupFile.getParent());
            Files.writeString(startupFile, content(), STARTUP_ENCODING);
            return;
        }
        if (!Files.exists(startupFile)) {
            return;
        }
        if (!isEnabled()) {
            throw new IOException("The existing startup entry is not managed by CycberCompany: " + startupFile);
        }
        Files.delete(startupFile);
    }

    Path startupFile() {
        return startupFile;
    }

    private String content() {
        // cmd.exe recognizes UTF-16LE command files through the BOM. This keeps a
        // packaged executable path usable for non-ASCII Windows user names.
        return "\uFEFF" + MARKER + "\r\n"
                + "@echo off\r\n"
                + "start \"\" \"" + executable + "\" --background\r\n";
    }
}
