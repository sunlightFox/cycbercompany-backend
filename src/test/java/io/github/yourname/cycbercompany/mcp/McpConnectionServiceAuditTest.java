package io.github.yourname.cycbercompany.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.yourname.cycbercompany.config.AppProperties;
import io.github.yourname.cycbercompany.security.ActorContext;
import io.github.yourname.cycbercompany.tool.RiskLevel;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class McpConnectionServiceAuditTest {

    @Test
    void approvalRequiredToolIsDeniedAndAuditedWithoutPlainArguments() throws Exception {
        AppProperties properties = mock(AppProperties.class);
        AppProperties.McpStore store = new AppProperties.McpStore(Files.createTempDirectory("mcp-audit-test"));
        when(properties.mcp()).thenReturn(store);
        McpStdioClient stdio = mock(McpStdioClient.class);
        McpToolInvocationRepository invocations = mock(McpToolInvocationRepository.class);
        AtomicReference<McpToolInvocationEntity> saved = new AtomicReference<>();
        when(invocations.save(any(McpToolInvocationEntity.class))).thenAnswer(call -> {
            McpToolInvocationEntity entity = call.getArgument(0);
            saved.set(entity);
            return entity;
        });
        McpConnectionService service = new McpConnectionService(
                properties, new ObjectMapper().registerModule(new JavaTimeModule()), stdio, invocations);
        service.ensureConfigDirectoryExists();
        service.create(new CreateMcpConnectionCommand(
                "dangerous",
                "Dangerous MCP",
                "test",
                McpTransportType.STDIO,
                true,
                "test-command",
                List.of(),
                null,
                Map.of(),
                Map.of(),
                List.of(new UpsertMcpToolCommand(
                        "delete_record", "delete", "{}", RiskLevel.HIGH, true, true))));

        assertThatThrownBy(() -> service.callTool(
                        "dangerous",
                        "delete_record",
                        new CallMcpToolCommand(Map.of("recordId", "secret-record-value")),
                        "run-1",
                        ActorContext.local()))
                .hasMessageContaining("requires approval");

        McpToolInvocationEntity audit = saved.get();
        assertThat(audit.status()).isEqualTo(McpToolInvocationStatus.DENIED);
        assertThat(audit.argumentKeys()).isEqualTo("recordId");
        assertThat(audit.argumentsSha256()).hasSize(64).doesNotContain("secret-record-value");
        assertThat(audit.errorCategory()).isEqualTo("APPROVAL_REQUIRED");
        verify(stdio, never()).callTool(any(), any(), any());
    }

    @Test
    void refreshPreservesAdministratorToolPolicy() throws Exception {
        AppProperties properties = mock(AppProperties.class);
        AppProperties.McpStore store = new AppProperties.McpStore(Files.createTempDirectory("mcp-refresh-test"));
        when(properties.mcp()).thenReturn(store);
        McpStdioClient stdio = mock(McpStdioClient.class);
        McpToolInvocationRepository invocations = mock(McpToolInvocationRepository.class);
        McpConnectionService service = new McpConnectionService(
                properties, new ObjectMapper().registerModule(new JavaTimeModule()), stdio, invocations);
        service.ensureConfigDirectoryExists();
        service.create(new CreateMcpConnectionCommand(
                "catalog",
                "Catalog MCP",
                "test",
                McpTransportType.STDIO,
                true,
                "test-command",
                List.of(),
                null,
                Map.of(),
                Map.of(),
                List.of(new UpsertMcpToolCommand(
                        "search", "old description", "{\"type\":\"object\"}", RiskLevel.HIGH, true, false))));
        when(stdio.listTools(any())).thenReturn(List.of(new UpsertMcpToolCommand(
                "search", "new description", "{\"type\":\"object\",\"properties\":{\"query\":{}}}",
                RiskLevel.LOW, false, true)));

        McpToolView refreshed = service.refreshTools("catalog").tools().getFirst();

        assertThat(refreshed.description()).isEqualTo("new description");
        assertThat(refreshed.inputSchema()).contains("query");
        assertThat(refreshed.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(refreshed.requiresApproval()).isTrue();
        assertThat(refreshed.enabled()).isFalse();
    }

    @Test
    void refreshFailureIsPersistedAsConnectionDiagnostic() throws Exception {
        AppProperties properties = mock(AppProperties.class);
        AppProperties.McpStore store = new AppProperties.McpStore(Files.createTempDirectory("mcp-discovery-error-test"));
        when(properties.mcp()).thenReturn(store);
        McpStdioClient stdio = mock(McpStdioClient.class);
        McpConnectionService service = new McpConnectionService(
                properties, new ObjectMapper().registerModule(new JavaTimeModule()), stdio, mock(McpToolInvocationRepository.class));
        service.ensureConfigDirectoryExists();
        service.create(new CreateMcpConnectionCommand(
                "broken", "Broken MCP", "", McpTransportType.STDIO, true,
                "test-command", List.of(), null, Map.of(), Map.of(), List.of()));
        when(stdio.listTools(any())).thenThrow(new IllegalStateException("server did not answer tools/list"));

        assertThatThrownBy(() -> service.refreshTools("broken"))
                .hasMessageContaining("server did not answer tools/list");

        McpConnectionView connection = service.getConnection("broken");
        assertThat(connection.status()).isEqualTo(McpConnectionStatus.ERROR);
        assertThat(connection.lastError()).contains("tools/list");
    }

    @Test
    void importsPastedMcpServersJsonAsEnabledConnections() throws Exception {
        AppProperties properties = mock(AppProperties.class);
        AppProperties.McpStore store = new AppProperties.McpStore(Files.createTempDirectory("mcp-import-json-test"));
        when(properties.mcp()).thenReturn(store);
        McpConnectionService service = new McpConnectionService(
                properties,
                new ObjectMapper().registerModule(new JavaTimeModule()),
                mock(McpStdioClient.class),
                mock(McpToolInvocationRepository.class));
        service.ensureConfigDirectoryExists();

        List<McpConnectionView> imported = service.importJson(new ImportMcpConnectionsCommand("""
                {
                  "mcpServers": {
                    "weather": {
                      "description": "Weather tools",
                      "command": "npx",
                      "args": ["-y", "@h1deya/mcp-server-weather"],
                      "env": { "WEATHER_CACHE": "env:WEATHER_CACHE" }
                    }
                  }
                }
                """, false, null, false));

        assertThat(imported).singleElement().satisfies(connection -> {
            assertThat(connection.id()).isEqualTo("weather");
            assertThat(connection.enabled()).isTrue();
            assertThat(connection.status()).isEqualTo(McpConnectionStatus.NEEDS_DISCOVERY);
            assertThat(connection.command()).isEqualTo("npx");
            assertThat(connection.args()).containsExactly("-y", "@h1deya/mcp-server-weather");
            assertThat(connection.envKeys()).containsExactly("WEATHER_CACHE");
            assertThat(connection.metadata()).containsEntry("importSource", "raw-json");
        });
    }

    @Test
    void npmInstallDefaultsToEnabledForOneClickInstalls() throws Exception {
        AppProperties properties = mock(AppProperties.class);
        AppProperties.McpStore store = new AppProperties.McpStore(Files.createTempDirectory("mcp-install-enabled-test"));
        when(properties.mcp()).thenReturn(store);
        McpConnectionService service = new McpConnectionService(
                properties,
                new ObjectMapper().registerModule(new JavaTimeModule()),
                mock(McpStdioClient.class),
                mock(McpToolInvocationRepository.class));
        service.ensureConfigDirectoryExists();

        McpConnectionView installed = service.installNpm(new InstallNpmMcpServerCommand(
                "weather",
                "Weather MCP",
                "Weather tools",
                "@h1deya/mcp-server-weather",
                List.of(),
                Map.of(),
                null,
                false));

        assertThat(installed.enabled()).isTrue();
        assertThat(installed.status()).isEqualTo(McpConnectionStatus.NEEDS_DISCOVERY);
        assertThat(installed.metadata()).containsEntry("npmPackage", "@h1deya/mcp-server-weather");
    }
}
