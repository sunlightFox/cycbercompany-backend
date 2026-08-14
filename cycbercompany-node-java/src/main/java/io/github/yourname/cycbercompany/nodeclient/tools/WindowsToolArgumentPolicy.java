package io.github.yourname.cycbercompany.nodeclient.tools;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Shared validation helpers for structured Windows-oriented node tools.
 */
final class WindowsToolArgumentPolicy {

    private static final int MAX_PACKAGE_ID_CHARS = 200;
    private static final int MAX_SERVICE_NAME_CHARS = 256;
    private static final int MAX_PROCESS_NAME_CHARS = 128;

    private static final Pattern WINGET_PACKAGE_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0," + (MAX_PACKAGE_ID_CHARS - 1) + "}");
    private static final Pattern WINGET_VERSION =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+:-]{0,127}");
    private static final Pattern WINDOWS_SERVICE_NAME =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._$-]{0," + (MAX_SERVICE_NAME_CHARS - 1) + "}");
    private static final Pattern WINDOWS_PROCESS_NAME =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0," + (MAX_PROCESS_NAME_CHARS - 1) + "}\\.exe");

    private WindowsToolArgumentPolicy() {
    }

    static String requireWingetPackageId(String value) {
        String packageId = requireText(value, "packageId");
        if (!WINGET_PACKAGE_ID.matcher(packageId).matches()) {
            throw new IllegalArgumentException(
                    "packageId must be an exact winget id using only letters, numbers, '.', '_' or '-'. Display names, paths, wildcards, spaces, and shell metacharacters are not accepted.");
        }
        return packageId;
    }

    static String requireWingetVersion(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String version = value.trim();
        if (!WINGET_VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException(
                    "version must be a literal winget version without spaces, paths, wildcards, or shell metacharacters.");
        }
        return version;
    }

    static String requireWindowsServiceName(String value) {
        String serviceName = requireText(value, "serviceName");
        if (!WINDOWS_SERVICE_NAME.matcher(serviceName).matches()) {
            throw new IllegalArgumentException(
                    "serviceName must be an exact Windows service name using only letters, numbers, '.', '_', '$', or '-'. Display names, spaces, paths, wildcards, and shell metacharacters are not accepted.");
        }
        return serviceName;
    }

    static String requireWindowsProcessName(String value) {
        return requireWindowsProcessName(value, "processName");
    }

    static String requireWindowsProcessName(String value, String argumentName) {
        String processName = requireText(value, argumentName);
        if (!WINDOWS_PROCESS_NAME.matcher(processName).matches()) {
            throw new IllegalArgumentException(
                    argumentName + " must be exact Windows image names ending in .exe using only letters, numbers, '.', '_' or '-'. Display names, paths, wildcards, spaces, and shell metacharacters are not accepted.");
        }
        return processName;
    }

    static String placeholderError(String requested, String argumentName) {
        if (requested == null || requested.isBlank()) {
            return null;
        }
        String normalized = requested.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        List<String> placeholders = List.of(
                "<path>",
                "<absolute path>",
                "<file>",
                "<file path>",
                "<folder>",
                "<directory>",
                "<dir>",
                "<cwd>",
                "<workspace>",
                "<project root>",
                "<desktop>",
                "<desktoppath>",
                "<desktop path>",
                "<target>",
                "<destination>",
                "<source>");
        return placeholders.stream().anyMatch(normalized::contains)
                ? argumentName + " contains an unreplaced placeholder. Use a concrete path returned by an inspection tool or provided by the user, or omit the argument."
                : null;
    }

    private static String requireText(String value, String argumentName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required argument: " + argumentName);
        }
        return value.trim();
    }
}
