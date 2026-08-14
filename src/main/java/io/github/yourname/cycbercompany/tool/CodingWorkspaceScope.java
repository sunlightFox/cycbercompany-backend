package io.github.yourname.cycbercompany.tool;

import java.util.ArrayList;
import java.util.List;

/** A workspace-relative project boundary supplied for one coding run. */
public record CodingWorkspaceScope(String relativePath) {

    private static final CodingWorkspaceScope ROOT = new CodingWorkspaceScope("");

    public static CodingWorkspaceScope from(String requestedPath) {
        if (requestedPath == null || requestedPath.isBlank() || ".".equals(requestedPath.trim())) {
            return ROOT;
        }
        String value = requestedPath.trim().replace('\\', '/');
        if (value.startsWith("/") || value.startsWith("//") || value.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("Coding workingDirectory must be workspace-relative.");
        }
        List<String> segments = new ArrayList<>();
        for (String segment : value.split("/")) {
            if (segment.isBlank() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("Coding workingDirectory must not leave the node workspace.");
            }
            segments.add(segment);
        }
        if (segments.isEmpty()) {
            return ROOT;
        }
        return new CodingWorkspaceScope(String.join("/", segments));
    }

    public boolean isRoot() {
        return relativePath.isBlank();
    }

    /** Resolves a tool-provided relative path under this run's project boundary. */
    public String resolve(String requestedPath) {
        String child = normalizeChild(requestedPath);
        if (isRoot()) {
            return child.isBlank() ? "." : child;
        }
        // Models sometimes repeat the selected project directory despite being
        // instructed that tool paths are already relative to it. Accept only
        // that exact, still-in-scope prefix instead of producing project/project.
        if (child.equals(relativePath)) {
            child = "";
        } else if (child.startsWith(relativePath + "/")) {
            child = child.substring(relativePath.length() + 1);
        }
        return child.isBlank() ? relativePath : relativePath + "/" + child;
    }

    private static String normalizeChild(String requestedPath) {
        if (requestedPath == null || requestedPath.isBlank() || ".".equals(requestedPath.trim())) {
            return "";
        }
        String value = requestedPath.trim().replace('\\', '/');
        if (value.startsWith("/") || value.startsWith("//") || value.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("Coding tool paths must be relative to the selected workingDirectory.");
        }
        List<String> segments = new ArrayList<>();
        for (String segment : value.split("/")) {
            if (segment.isBlank() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("Coding tool paths must not leave the selected workingDirectory.");
            }
            segments.add(segment);
        }
        return String.join("/", segments);
    }
}
