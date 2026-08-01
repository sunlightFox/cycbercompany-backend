package io.github.yourname.agentstudio.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.yourname.agentstudio.config.AppProperties;
import io.github.yourname.agentstudio.security.ActorContext;
import io.github.yourname.agentstudio.tool.RiskLevel;
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
}
