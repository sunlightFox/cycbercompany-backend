package io.github.yourname.agentstudio.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.model.ModelGateway;
import io.github.yourname.agentstudio.node.CallNodeToolCommand;
import io.github.yourname.agentstudio.node.NodeService;
import io.github.yourname.agentstudio.node.NodeToolCallResult;
import io.github.yourname.agentstudio.node.NodeToolView;
import io.github.yourname.agentstudio.security.ActorContext;
import io.github.yourname.agentstudio.tool.RiskLevel;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CodingToolAdapterScopeTest {

    @Test
    void exposesEnabledBrowserToolsToCodingRuns() {
        NodeService nodes = mock(NodeService.class);
        CodingToolAdapter adapter = new CodingToolAdapter(nodes, new ObjectMapper());
        ActorContext actor = new ActorContext("tenant", "user", java.util.Set.of(), java.util.Set.of());
        NodeToolView browser = new NodeToolView(
                21L, "node-1", "browser.open", "Open browser", RiskLevel.MEDIUM,
                true, false, "{\"type\":\"object\"}", Instant.now(), Instant.now());
        when(nodes.isReadyForToolExecution("node-1", actor)).thenReturn(true);
        when(nodes.listTools("node-1", actor)).thenReturn(List.of(browser));

        assertThat(adapter.availableTools("node-1", actor))
                .extracting(CodingToolAdapter.AvailableTool::nodeToolName)
                .containsExactly("browser.open");
    }

    @Test
    void forwardsFilePathsAndCommandDirectoriesInsideTheRunScope() {
        NodeService nodes = mock(NodeService.class);
        CodingToolAdapter adapter = new CodingToolAdapter(nodes, new ObjectMapper());
        ActorContext actor = new ActorContext("tenant", "user", java.util.Set.of(), java.util.Set.of());
        var tool = new CodingToolAdapter.AvailableTool(
                "node_tool_5", "node-1", "shell.run", new ModelGateway.ModelTool("node_tool_5", "Shell", Map.of()));
        when(nodes.callToolForRun(any(), any(), any(), any(), any(), any())).thenReturn(
                new NodeToolCallResult("invocation-1", "node-1", "shell.run", "SUCCEEDED", Map.of(), null));

        adapter.execute(
                "run-1",
                tool,
                new ModelGateway.ModelToolCall("call-1", "node_tool_5", Map.of("command", "javac App.java", "cwd", "backend")),
                actor,
                CodingWorkspaceScope.from("projects/task-board"));

        ArgumentCaptor<CallNodeToolCommand> command = ArgumentCaptor.forClass(CallNodeToolCommand.class);
        verify(nodes).callToolForRun(eq("run-1"), eq("call-1"), eq("node-1"), eq("shell.run"), command.capture(), eq(actor));
        assertThat(command.getValue().arguments())
                .containsEntry("command", "javac App.java")
                .containsEntry("cwd", "projects/task-board/backend");
    }

    @Test
    void forwardsSearchRootsInsideTheRunScope() {
        NodeService nodes = mock(NodeService.class);
        CodingToolAdapter adapter = new CodingToolAdapter(nodes, new ObjectMapper());
        ActorContext actor = new ActorContext("tenant", "user", java.util.Set.of(), java.util.Set.of());
        var tool = new CodingToolAdapter.AvailableTool(
                "node_tool_6", "node-1", "fs.search", new ModelGateway.ModelTool("node_tool_6", "Search", Map.of()));
        when(nodes.callToolForRun(any(), any(), any(), any(), any(), any())).thenReturn(
                new NodeToolCallResult("invocation-2", "node-1", "fs.search", "SUCCEEDED", Map.of(), null));

        adapter.execute(
                "run-1",
                tool,
                new ModelGateway.ModelToolCall("call-2", "node_tool_6", Map.of("path", "src", "query", "TaskController")),
                actor,
                CodingWorkspaceScope.from("projects/task-board"));

        ArgumentCaptor<CallNodeToolCommand> command = ArgumentCaptor.forClass(CallNodeToolCommand.class);
        verify(nodes).callToolForRun(eq("run-1"), eq("call-2"), eq("node-1"), eq("fs.search"), command.capture(), eq(actor));
        assertThat(command.getValue().arguments())
                .containsEntry("path", "projects/task-board/src")
                .containsEntry("query", "TaskController");
    }
}
