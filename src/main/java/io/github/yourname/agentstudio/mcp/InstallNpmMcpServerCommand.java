package io.github.yourname.agentstudio.mcp;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

/**
 * Convenience installer for the most common MCP distribution style: npm
 * packages launched through {@code npx -y <package>}.
 */
public record InstallNpmMcpServerCommand(
        String id,
        @NotBlank String name,
        String description,
        @NotBlank String npmPackage,
        List<String> packageArgs,
        Map<String, String> env,
        Boolean enabled,
        Boolean refreshTools) {
}
