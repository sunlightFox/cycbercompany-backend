package io.github.yourname.agentstudio.node;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.security.ActorContext;
import io.github.yourname.agentstudio.tool.RegisteredTool;
import io.github.yourname.agentstudio.tool.RiskLevel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 节点管理服务。
 *
 * <p>它是后端控制中心的核心入口：REST 注册和 WebSocket 连接都必须通过这里校验，
 * 避免“节点通道”和“节点管理 API”各自实现一套安全逻辑。
 */
@Service
public class NodeService {

    private static final int DEFAULT_TOKEN_TTL_SECONDS = 10 * 60;
    private static final int MAX_TOKEN_TTL_SECONDS = 24 * 60 * 60;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final NodeConnectionRepository nodes;
    private final NodeRegistrationTokenRepository tokens;
    private final NodeToolRepository tools;
    private final NodeToolInvocationRepository invocations;
    private final NodeToolApprovalRepository approvals;
    private final NodeSessionRegistry sessions;
    private final ObjectMapper objectMapper;

    public NodeService(
            NodeConnectionRepository nodes,
            NodeRegistrationTokenRepository tokens,
            NodeToolRepository tools,
            NodeToolInvocationRepository invocations,
            NodeToolApprovalRepository approvals,
            NodeSessionRegistry sessions,
            ObjectMapper objectMapper) {
        this.nodes = nodes;
        this.tokens = tokens;
        this.tools = tools;
        this.invocations = invocations;
        this.approvals = approvals;
        this.sessions = sessions;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public NodeRegistrationTokenView createRegistrationToken(CreateNodeRegistrationTokenCommand command, ActorContext actor) {
        int ttlSeconds = command == null || command.ttlSeconds() == null || command.ttlSeconds() <= 0
                ? DEFAULT_TOKEN_TTL_SECONDS
                : Math.min(command.ttlSeconds(), MAX_TOKEN_TTL_SECONDS);
        String plainToken = "nr_" + randomToken(32);
        Instant now = Instant.now();
        var entity = tokens.save(new NodeRegistrationTokenEntity(
                UUID.randomUUID().toString(),
                actor.tenantId(),
                sha256(plainToken),
                now.plusSeconds(ttlSeconds),
                now));
        return new NodeRegistrationTokenView(
                entity.id(),
                plainToken,
                entity.expiresAt(),
                "agent-node register --server http://localhost:8080 --token " + plainToken);
    }

    @Transactional
    public RegisterNodeResult register(RegisterNodeCommand command, ActorContext actor) {
        Instant now = Instant.now();
        NodeRegistrationTokenEntity token = tokens.findByTenantIdAndTokenHash(actor.tenantId(), sha256(command.registrationToken()))
                .orElseThrow(() -> new IllegalArgumentException("Invalid node registration token."));
        if (token.used()) {
            throw new IllegalArgumentException("Node registration token has already been used.");
        }
        if (token.expired(now)) {
            throw new IllegalArgumentException("Node registration token has expired.");
        }
        token.markUsed(now);

        String nodeSecret = "ns_" + randomToken(48);
        var entity = nodes.save(new NodeConnectionEntity(
                "node_" + UUID.randomUUID(),
                actor.tenantId(),
                command.name() == null || command.name().isBlank() ? defaultNodeName(command) : command.name().trim(),
                command.hostname(),
                command.osName(),
                command.osArch(),
                command.clientVersion(),
                sha256(nodeSecret),
                now));
        return new RegisterNodeResult(
                entity.id(),
                nodeSecret,
                "/api/v1/node-channel?nodeId=" + entity.id() + "&nodeSecret=" + nodeSecret,
                NodeConnectionView.from(entity));
    }

    @Transactional(readOnly = true)
    public List<NodeConnectionView> list(ActorContext actor) {
        return nodes.findByTenantIdOrderByCreatedAtDesc(actor.tenantId()).stream()
                .map(NodeConnectionView::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public NodeDetailView get(String id, ActorContext actor) {
        NodeConnectionEntity node = requireNode(id, actor);
        return new NodeDetailView(
                NodeConnectionView.from(node),
                tools.findByTenantIdAndNodeIdOrderByNameAsc(actor.tenantId(), id).stream()
                        .map(NodeToolView::from)
                        .toList());
    }

    @Transactional
    public NodeConnectionView update(String id, UpdateNodeCommand command, ActorContext actor) {
        NodeConnectionEntity node = requireNode(id, actor);
        boolean enabled = command == null || command.enabled() == null ? node.enabled() : command.enabled();
        node.update(command == null ? null : command.name(), enabled, Instant.now());
        return NodeConnectionView.from(nodes.save(node));
    }

    @Transactional
    public void delete(String id, ActorContext actor) {
        NodeConnectionEntity node = requireNode(id, actor);
        tools.deleteByTenantIdAndNodeId(actor.tenantId(), id);
        nodes.delete(node);
    }

    @Transactional(readOnly = true)
    public List<NodeToolView> listTools(String nodeId, ActorContext actor) {
        requireNode(nodeId, actor);
        return tools.findByTenantIdAndNodeIdOrderByNameAsc(actor.tenantId(), nodeId).stream()
                .map(NodeToolView::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean isReadyForToolExecution(String nodeId, ActorContext actor) {
        NodeConnectionEntity node = requireNode(nodeId, actor);
        return node.enabled() && node.status() == NodeStatus.ONLINE && sessions.isConnected(nodeId);
    }

    @Transactional
    public NodeToolView updateTool(String nodeId, String toolName, UpdateNodeToolCommand command, ActorContext actor) {
        requireNode(nodeId, actor);
        NodeToolEntity tool = tools.findByTenantIdAndNodeIdAndName(actor.tenantId(), nodeId, toolName)
                .orElseThrow(() -> new IllegalArgumentException("Node tool not found: " + toolName));
        tool.updatePolicy(command == null ? null : command.enabled(), command == null ? null : command.requiresApproval(), Instant.now());
        return NodeToolView.from(tools.save(tool));
    }

    @Transactional
    public NodeToolCallResult callTool(String nodeId, String toolName, CallNodeToolCommand command, ActorContext actor) {
        return executeTool(nodeId, toolName, command, actor, false);
    }

    @Transactional(noRollbackFor = Exception.class)
    public NodeToolCallResult callToolForRun(
            String runId,
            String toolCallId,
            String nodeId,
            String toolName,
            CallNodeToolCommand command,
            ActorContext actor) {
        Instant now = Instant.now();
        NodeToolInvocationEntity invocation = invocations.save(new NodeToolInvocationEntity(
                "nodeinv_" + UUID.randomUUID(),
                actor.tenantId(),
                runId,
                toolCallId,
                nodeId,
                toolName,
                toJson(command == null ? null : command.arguments()),
                now));
        invocation.start(now);
        invocations.save(invocation);
        try {
            NodeToolCallResult result = executeTool(nodeId, toolName, command, actor, false);
            if ("SUCCEEDED".equalsIgnoreCase(result.status())) {
                invocation.succeed(toJson(result.result()), Instant.now());
            } else if ("APPROVAL_REQUIRED".equalsIgnoreCase(result.status())) {
                invocation.fail(NodeToolInvocationStatus.APPROVAL_REQUIRED, result.errorMessage(), Instant.now());
            } else {
                invocation.fail(NodeToolInvocationStatus.FAILED, result.errorMessage(), Instant.now());
            }
            invocations.save(invocation);
            return result;
        } catch (Exception ex) {
            invocation.fail(NodeToolInvocationStatus.FAILED, ex.getMessage(), Instant.now());
            invocations.save(invocation);
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public List<NodeToolInvocationView> listRunInvocations(String runId, ActorContext actor) {
        return invocations.findByTenantIdAndRunIdOrderByCreatedAtAsc(actor.tenantId(), runId).stream()
                .map(NodeToolInvocationView::from)
                .toList();
    }

    @Transactional
    public NodeToolApprovalView requestToolApproval(
            String nodeId,
            String toolName,
            CallNodeToolCommand command,
            ActorContext actor) {
        requireNode(nodeId, actor);
        NodeToolEntity tool = tools.findByTenantIdAndNodeIdAndName(actor.tenantId(), nodeId, toolName)
                .orElseThrow(() -> new IllegalArgumentException("Node tool not found: " + toolName));
        if (!tool.enabled()) {
            throw new IllegalArgumentException("Node tool is disabled: " + toolName);
        }
        if (!tool.requiresApproval()) {
            throw new IllegalArgumentException("Node tool does not require approval: " + toolName);
        }
        int timeoutSeconds = timeoutSeconds(command);
        NodeToolApprovalEntity approval = approvals.save(new NodeToolApprovalEntity(
                "nodeapproval_" + UUID.randomUUID(),
                actor.tenantId(),
                nodeId,
                toolName,
                toJson(command == null || command.arguments() == null ? Map.of() : command.arguments()),
                timeoutSeconds,
                actor.userId(),
                Instant.now()));
        return NodeToolApprovalView.from(approval);
    }

    @Transactional(readOnly = true)
    public List<NodeToolApprovalView> listToolApprovals(ActorContext actor) {
        return approvals.findByTenantIdOrderByCreatedAtDesc(actor.tenantId()).stream()
                .map(NodeToolApprovalView::from)
                .toList();
    }

    /**
     * Executes exactly one previously persisted request after a human decision.
     * A second decision is rejected by the entity transition check (and database versioning).
     */
    @Transactional(noRollbackFor = Exception.class)
    public NodeToolApprovalDecisionView decideToolApproval(
            String approvalId,
            DecideNodeToolApprovalCommand command,
            ActorContext actor) {
        NodeToolApprovalEntity approval = approvals.findByIdAndTenantId(approvalId, actor.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Node tool approval not found: " + approvalId));
        boolean approved = command != null && command.approved();
        approval.decide(approved ? NodeToolApprovalStatus.APPROVED : NodeToolApprovalStatus.REJECTED, actor.userId(), Instant.now());
        // Flush the irreversible decision before dispatching so concurrent submissions cannot execute twice.
        approvals.saveAndFlush(approval);
        if (!approved) {
            return new NodeToolApprovalDecisionView(NodeToolApprovalView.from(approval), null);
        }

        NodeToolCallResult execution;
        try {
            execution = executeTool(
                    approval.nodeId(),
                    approval.toolName(),
                    new CallNodeToolCommand(readArguments(approval.argumentsJson()), approval.timeoutSeconds()),
                    actor,
                    true);
            approval.recordExecution(
                    execution.status(),
                    toJson(execution.result()),
                    execution.errorMessage(),
                    Instant.now());
        } catch (Exception ex) {
            execution = new NodeToolCallResult(
                    null,
                    approval.nodeId(),
                    approval.toolName(),
                    "FAILED",
                    null,
                    ex.getMessage());
            approval.recordExecution("FAILED", null, ex.getMessage(), Instant.now());
        }
        approvals.save(approval);
        return new NodeToolApprovalDecisionView(NodeToolApprovalView.from(approval), execution);
    }

    private NodeToolCallResult executeTool(
            String nodeId,
            String toolName,
            CallNodeToolCommand command,
            ActorContext actor,
            boolean bypassApproval) {
        NodeConnectionEntity node = requireNode(nodeId, actor);
        NodeToolEntity tool = tools.findByTenantIdAndNodeIdAndName(actor.tenantId(), nodeId, toolName)
                .orElseThrow(() -> new IllegalArgumentException("Node tool not found: " + toolName));
        if (!tool.enabled()) {
            throw new IllegalArgumentException("Node tool is disabled: " + toolName);
        }
        if (tool.requiresApproval() && !bypassApproval) {
            NodeToolApprovalView approval = requestToolApproval(nodeId, toolName, command, actor);
            return new NodeToolCallResult(
                    null,
                    nodeId,
                    toolName,
                    "APPROVAL_REQUIRED",
                    Map.of("approvalId", approval.id(), "status", approval.status().name()),
                    "Node tool requires approval before it can execute.");
        }
        if (!node.enabled() || node.status() != NodeStatus.ONLINE || !sessions.isConnected(nodeId)) {
            throw new IllegalArgumentException("Node is not online or enabled: " + nodeId);
        }
        int timeoutSeconds = timeoutSeconds(command);
        return sessions.invoke(
                nodeId,
                toolName,
                command == null ? null : command.arguments(),
                Duration.ofSeconds(timeoutSeconds));
    }

    private int timeoutSeconds(CallNodeToolCommand command) {
        return command == null || command.timeoutSeconds() == null || command.timeoutSeconds() <= 0
                ? 30
                : Math.min(command.timeoutSeconds(), 120);
    }

    private Map<String, Object> readArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(argumentsJson, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored approval arguments cannot be read.", ex);
        }
    }

    @Transactional(readOnly = true)
    public List<RegisteredTool> enabledRegisteredTools(ActorContext actor) {
        return tools.findByTenantIdOrderByNodeIdAscNameAsc(actor.tenantId()).stream()
                .filter(NodeToolEntity::enabled)
                .filter(tool -> nodes.findByIdAndTenantId(tool.nodeId(), actor.tenantId())
                        .filter(NodeConnectionEntity::enabled)
                        .filter(node -> node.status() == NodeStatus.ONLINE)
                        .isPresent())
                .map(tool -> new RegisteredTool(
                        "node:" + tool.nodeId() + ":" + tool.name(),
                        "Execute node tool " + tool.name() + " on node " + tool.nodeId()
                                + (tool.description() == null || tool.description().isBlank() ? "" : ": " + tool.description()),
                        tool.riskLevel(),
                        tool.requiresApproval()))
                .toList();
    }

    /**
     * WebSocket 握手校验：当前第一版使用 nodeId + nodeSecret。
     *
     * <p>生产版可以升级为 HMAC(timestamp + nonce)，避免密钥出现在 URL 中；第一版先保证协议闭环。
     */
    @Transactional
    public NodeConnectionEntity authenticateNode(String nodeId, String nodeSecret) {
        NodeConnectionEntity node = nodes.findById(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + nodeId));
        if (!node.enabled()) {
            throw new IllegalArgumentException("Node is disabled: " + nodeId);
        }
        if (nodeSecret == null || nodeSecret.isBlank() || !MessageDigest.isEqual(
                sha256(nodeSecret).getBytes(StandardCharsets.UTF_8),
                node.secretHash().getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("Invalid node secret.");
        }
        node.markOnline(Instant.now());
        return nodes.save(node);
    }

    @Transactional
    public void heartbeat(String nodeId, String hostname, String osName, String osArch, String clientVersion) {
        NodeConnectionEntity node = nodes.findById(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + nodeId));
        Instant now = Instant.now();
        node.refreshMetadata(hostname, osName, osArch, clientVersion, now);
        node.markOnline(now);
        nodes.save(node);
    }

    @Transactional
    public void markOffline(String nodeId) {
        nodes.findById(nodeId).ifPresent(node -> {
            node.markOffline(Instant.now());
            nodes.save(node);
        });
    }

    @Transactional
    public List<NodeToolView> saveCapabilities(String nodeId, List<NodeCapabilityPayload> capabilities) {
        NodeConnectionEntity node = nodes.findById(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + nodeId));
        if (capabilities == null) {
            return listToolsForNodeEntity(node);
        }
        Instant now = Instant.now();
        for (NodeCapabilityPayload capability : capabilities) {
            if (capability.name() == null || capability.name().isBlank()) {
                continue;
            }
            String name = capability.name().trim();
            RiskLevel risk = capability.riskLevel() == null ? RiskLevel.MEDIUM : capability.riskLevel();
            boolean requiresApproval = capability.requiresApproval() != null
                    ? capability.requiresApproval()
                    : risk == RiskLevel.HIGH;
            boolean defaultEnabled = capability.enabled() != null
                    ? capability.enabled()
                    : risk != RiskLevel.HIGH;
            String schemaJson = toJson(capability.inputSchema());
            NodeToolEntity tool = tools.findByTenantIdAndNodeIdAndName(node.tenantId(), node.id(), name)
                    .orElseGet(() -> new NodeToolEntity(
                            node.tenantId(),
                            node.id(),
                            name,
                            capability.description(),
                            risk,
                            defaultEnabled,
                            requiresApproval,
                            schemaJson,
                            now));
            // Reconnection reports capabilities again. Preserve policy set through the management API.
            tool.refreshCapability(capability.description(), risk, schemaJson, now);
            tools.save(tool);
        }
        return listToolsForNodeEntity(node);
    }

    @Transactional
    public void markStaleNodesOffline(Duration staleAfter) {
        Instant cutoff = Instant.now().minus(staleAfter);
        for (NodeConnectionEntity node : nodes.findAll()) {
            if (node.status() == NodeStatus.ONLINE && node.lastSeenAt() != null && node.lastSeenAt().isBefore(cutoff)) {
                node.markOffline(Instant.now());
                nodes.save(node);
            }
        }
    }

    private List<NodeToolView> listToolsForNodeEntity(NodeConnectionEntity node) {
        return tools.findByTenantIdAndNodeIdOrderByNameAsc(node.tenantId(), node.id()).stream()
                .map(NodeToolView::from)
                .toList();
    }

    private NodeConnectionEntity requireNode(String id, ActorContext actor) {
        return nodes.findByIdAndTenantId(id, actor.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + id));
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid node tool input schema: " + ex.getMessage(), ex);
        }
    }

    private static String defaultNodeName(RegisterNodeCommand command) {
        if (command.hostname() != null && !command.hostname().isBlank()) {
            return command.hostname().trim();
        }
        return "Unnamed node";
    }

    private static String randomToken(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java platform.", ex);
        }
    }
}
