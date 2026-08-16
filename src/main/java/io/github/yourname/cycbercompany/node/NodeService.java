package io.github.yourname.cycbercompany.node;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.cycbercompany.security.ActorContext;
import io.github.yourname.cycbercompany.execution.ExecutionMode;
import io.github.yourname.cycbercompany.execution.ExecutionSettingsService;
import io.github.yourname.cycbercompany.tool.RegisteredTool;
import io.github.yourname.cycbercompany.tool.RiskLevel;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.annotation.Propagation;
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

    private static final int TOOL_APPROVAL_TTL_SECONDS = 5 * 60;
    private static final String TOOL_APPROVER_ROLE = "NODE_TOOL_APPROVER";
    private static final Duration NODE_RECONNECT_GRACE = Duration.ofSeconds(8);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final NodeConnectionRepository nodes;
    private final NodeRegistrationTokenRepository tokens;
    private final NodeToolRepository tools;
    private final NodeToolInvocationRepository invocations;
    private final NodeToolApprovalRepository approvals;
    private final NodeSessionRegistry sessions;
    private final ObjectMapper objectMapper;
    private final NodeToolRequestPolicy requestPolicy;
    private final ExecutionSettingsService executionSettings;
    /**
     * 仅用于在同一个匹配沙箱池中分散新 Run；Run 创建后会持久化具体 nodeId，所以此内存
     * 游标丢失不会影响已经排队或恢复中的任务，也不会改变它们的执行位置。
     */
    private final ConcurrentMap<String, AtomicLong> sandboxRoutingCursors = new ConcurrentHashMap<>();

    @Autowired
    public NodeService(
            NodeConnectionRepository nodes,
            NodeRegistrationTokenRepository tokens,
            NodeToolRepository tools,
            NodeToolInvocationRepository invocations,
            NodeToolApprovalRepository approvals,
            NodeSessionRegistry sessions,
            ObjectMapper objectMapper,
            NodeToolRequestPolicy requestPolicy,
            ExecutionSettingsService executionSettings) {
        this.nodes = nodes;
        this.tokens = tokens;
        this.tools = tools;
        this.invocations = invocations;
        this.approvals = approvals;
        this.sessions = sessions;
        this.objectMapper = objectMapper;
        this.requestPolicy = requestPolicy;
        this.executionSettings = executionSettings;
    }

    /** 保留给不启动 Spring 容器的单元测试，使用与生产默认值相同的拒绝私网策略。 */
    NodeService(
            NodeConnectionRepository nodes,
            NodeRegistrationTokenRepository tokens,
            NodeToolRepository tools,
            NodeToolInvocationRepository invocations,
            NodeToolApprovalRepository approvals,
            NodeSessionRegistry sessions,
            ObjectMapper objectMapper) {
        this(
                nodes,
                tokens,
                tools,
                invocations,
                approvals,
                sessions,
                objectMapper,
                new NodeToolRequestPolicy(BrowserPolicyProperties.secureDefaults()));
    }

    /** Keeps direct unit-test construction independent from application settings. */
    NodeService(
            NodeConnectionRepository nodes,
            NodeRegistrationTokenRepository tokens,
            NodeToolRepository tools,
            NodeToolInvocationRepository invocations,
            NodeToolApprovalRepository approvals,
            NodeSessionRegistry sessions,
            ObjectMapper objectMapper,
            NodeToolRequestPolicy requestPolicy) {
        this(nodes, tokens, tools, invocations, approvals, sessions, objectMapper, requestPolicy, null);
    }

    @Transactional
    public RegisterNodeResult register(RegisterNodeCommand command) {
        Instant now = Instant.now();
        String nodeSecret = "ns_" + randomToken(48);
        var entity = nodes.save(new NodeConnectionEntity(
                "node_" + UUID.randomUUID(),
                "local",
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
                "/api/v1/node-channel",
                NodeConnectionView.from(entity));
    }

    /**
     * Provisions the companion that runs on the same personal computer as the local installation.
     * It deliberately uses the same node protocol, approval checks, and audit trail as registered
     * devices while remaining an implementation detail of personal-local mode.
     */
    @Transactional
    public RegisterNodeResult bootstrapLocalExecutor(BootstrapLocalExecutorCommand command, ActorContext actor) {
        if (!executionMode(actor).usesManagedLocalExecutor()) {
            throw new IllegalArgumentException("This installation is configured for registered nodes only.");
        }
        Instant now = Instant.now();
        String secret = "ns_" + randomToken(48);
        NodeConnectionEntity entity = nodes.findByTenantIdAndKind(actor.tenantId(), NodeKind.MANAGED_LOCAL)
                .orElseGet(() -> new NodeConnectionEntity(
                        "local_" + UUID.randomUUID(),
                        actor.tenantId(),
                        localExecutorName(command),
                        command == null ? null : command.hostname(),
                        command == null ? null : command.osName(),
                        command == null ? null : command.osArch(),
                        command == null ? null : command.clientVersion(),
                        sha256(secret),
                        NodeKind.MANAGED_LOCAL,
                        now));
        if (entity.createdAt().isBefore(now)) {
            entity.refreshMetadata(
                    command == null ? null : command.hostname(),
                    command == null ? null : command.osName(),
                    command == null ? null : command.osArch(),
                    command == null ? null : command.clientVersion(),
                    now);
            entity.rotateSecret(sha256(secret), now);
            sessions.disconnect(entity.id(), "local executor reprovisioned");
        }
        entity = nodes.save(entity);
        return new RegisterNodeResult(
                entity.id(), secret, "/api/v1/node-channel", NodeConnectionView.from(entity));
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
        Instant now = Instant.now();
        NodeKind requestedKind = command == null ? null : command.kind();
        Set<String> requestedLabels = command == null ? null : normalizedLabels(command.labels());
        validateSchedulingMetadataUpdate(node, requestedKind, requestedLabels);
        node.update(command == null ? null : command.name(), enabled, now);
        node.updateSchedulingMetadata(requestedKind, requestedLabels, now);
        return NodeConnectionView.from(nodes.save(node));
    }

    @Transactional
    public void delete(String id, ActorContext actor) {
        NodeConnectionEntity node = requireNode(id, actor);
        tools.deleteByTenantIdAndNodeId(actor.tenantId(), id);
        nodes.delete(node);
    }

    @Transactional
    public NodeConnectionView disconnect(String id, ActorContext actor) {
        NodeConnectionEntity node = requireNode(id, actor);
        boolean shutdownRequested = sessions.requestShutdown(node.id(), "server requested client shutdown");
        node.markOffline(Instant.now());
        if (!shutdownRequested) {
            sessions.disconnect(node.id(), "server requested client shutdown");
        }
        return NodeConnectionView.from(nodes.save(node));
    }

    /**
     * 轮换长期节点密钥，并立即断开用旧密钥建立的连接。
     *
     * <p>返回值中的明文只出现一次；调用方需要把它更新到节点本机配置。后端仍只保存摘要。
     */
    @Transactional
    public RotateNodeSecretResult rotateSecret(String id, ActorContext actor) {
        NodeConnectionEntity node = requireNode(id, actor);
        String nodeSecret = "ns_" + randomToken(48);
        Instant now = Instant.now();
        node.rotateSecret(sha256(nodeSecret), now);
        nodes.saveAndFlush(node);
        sessions.disconnect(node.id(), "node credential rotated");
        return new RotateNodeSecretResult(node.id(), nodeSecret, "/api/v1/node-channel", now);
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

    /** Identifies a persisted managed-local node without exposing its credentials or tools. */
    @Transactional(readOnly = true)
    public boolean isManagedLocalNode(String nodeId, ActorContext actor) {
        if (nodeId == null || nodeId.isBlank() || actor == null) {
            return false;
        }
        return nodes.findByIdAndTenantId(nodeId, actor.tenantId())
                .map(node -> node.kind() == NodeKind.MANAGED_LOCAL)
                .orElse(false);
    }

    /** Enforces the selected execution topology for explicit targets as well as auto-routing. */
    @Transactional(readOnly = true)
    public void validateExecutionTarget(String nodeId, ActorContext actor) {
        if (nodeId == null || nodeId.isBlank()) {
            return;
        }
        NodeConnectionEntity node = requireNode(nodeId, actor);
        ExecutionMode mode = executionMode(actor);
        if (mode == ExecutionMode.PERSONAL_LOCAL && node.kind() != NodeKind.MANAGED_LOCAL) {
            throw new IllegalArgumentException("Personal local mode only permits this computer.");
        }
        if (mode == ExecutionMode.NODES_ONLY
                && node.kind() != NodeKind.REGISTERED
                && node.kind() != NodeKind.SANDBOX) {
            throw new IllegalArgumentException(
                    "This installation is configured for registered nodes only; trusted sandbox nodes are also allowed.");
        }
    }

    /**
     * Resolves the local computer target for an explicitly requested system operation.
     *
     * <p>Automatic selection is deliberately limited to one ready node. Choosing between
     * multiple computers is a user decision and must never be guessed by the server.
     */
    @Transactional(readOnly = true)
    public String resolveComputerControlNodeId(ActorContext actor) {
        ExecutionMode mode = executionMode(actor);
        List<NodeConnectionEntity> candidates = nodes.findByTenantIdOrderByCreatedAtDesc(actor.tenantId()).stream()
                .filter(node -> mode == ExecutionMode.NODES_ONLY
                        ? node.kind() == NodeKind.REGISTERED
                        : node.kind() == NodeKind.MANAGED_LOCAL)
                .filter(node -> node.enabled()
                        && node.status() == NodeStatus.ONLINE
                        && sessions.isConnected(node.id()))
                .filter(node -> tools.findByTenantIdAndNodeIdOrderByNameAsc(actor.tenantId(), node.id()).stream()
                        .anyMatch(tool -> tool.enabled() && tool.name().startsWith("system.")))
                .toList();
        if (candidates.isEmpty()) {
            if (mode == ExecutionMode.NODES_ONLY) {
                throw new IllegalArgumentException(
                        "Computer control requires one connected node with enabled system tools.");
            }
            throw new LocalComputerControlNotReadyException();
        }
        if (candidates.size() > 1) {
            throw new IllegalArgumentException(
                    "More than one computer-control node is connected. Select the target node explicitly.");
        }
        return candidates.getFirst().id();
    }

    /**
     * 从管理员明确标记的 SANDBOX 节点中选择一个在线候选。
     *
     * <p>这里绝不把 REGISTERED（通常是个人电脑）放进自动候选集。标签要求采用“全部匹配”，
     * 已指定且在沙箱池内存在的节点工具也必须启用；普通后端/MCP 工具不会被误判为节点能力。
     * 当前选择按稳定节点 ID 排序，避免数据库返回顺序改变造成难以审计的漂移。
     */
    @Transactional(readOnly = true)
    public String resolveSandboxNodeId(
            List<String> requestedLabels,
            List<String> requestedToolNames,
            ActorContext actor) {
        if (executionMode(actor) != ExecutionMode.NODES_ONLY) {
            throw new IllegalArgumentException("Automatic sandbox routing is available only in nodes-only mode.");
        }
        Set<String> normalizedRequestedLabels = normalizedLabels(requestedLabels);
        Set<String> requiredLabels = normalizedRequestedLabels == null ? Set.of() : normalizedRequestedLabels;
        List<NodeConnectionEntity> pool = nodes.findByTenantIdOrderByCreatedAtDesc(actor.tenantId()).stream()
                .filter(node -> node.kind() == NodeKind.SANDBOX)
                .filter(node -> node.enabled() && node.status() == NodeStatus.ONLINE && sessions.isConnected(node.id()))
                .sorted(java.util.Comparator.comparing(NodeConnectionEntity::id))
                .toList();
        Set<String> requiredTools = nodeToolsToRequire(pool, actor.tenantId(), requestedToolNames);
        List<NodeConnectionEntity> candidates = pool.stream()
                .filter(node -> node.labels().containsAll(requiredLabels))
                .filter(node -> enabledToolNames(actor.tenantId(), node.id()).containsAll(requiredTools))
                .toList();
        if (candidates.isEmpty()) {
            String labelDetail = requiredLabels.isEmpty() ? "" : " labels=" + requiredLabels;
            String toolDetail = requiredTools.isEmpty() ? "" : " tools=" + requiredTools;
            throw new IllegalArgumentException("No connected trusted sandbox matches the requested routing constraints."
                    + labelDetail + toolDetail);
        }
        String routingKey = sandboxRoutingKey(actor.tenantId(), requiredLabels, requiredTools);
        long turn = sandboxRoutingCursors
                .computeIfAbsent(routingKey, ignored -> new AtomicLong())
                .getAndIncrement();
        // 候选已经按 ID 排序。轮询只发生在管理员明确纳入的 SANDBOX 池内，绝不把个人
        // REGISTERED 设备放进来；Run 随后会把选中的具体 ID 固化到不可变快照。
        int index = (int) Math.floorMod(turn, candidates.size());
        return candidates.get(index).id();
    }

    private Set<String> nodeToolsToRequire(
            List<NodeConnectionEntity> sandboxPool, String tenantId, List<String> requestedToolNames) {
        if (requestedToolNames == null || requestedToolNames.isEmpty()) {
            return Set.of();
        }
        Set<String> availableSomewhere = new LinkedHashSet<>();
        for (NodeConnectionEntity node : sandboxPool) {
            availableSomewhere.addAll(enabledToolNames(tenantId, node.id()));
        }
        Set<String> required = new LinkedHashSet<>();
        for (String rawName : requestedToolNames) {
            if (rawName == null || rawName.isBlank()) {
                continue;
            }
            String toolName = rawName.trim();
            if (availableSomewhere.contains(toolName)) {
                required.add(toolName);
            }
        }
        return Set.copyOf(required);
    }

    private Set<String> enabledToolNames(String tenantId, String nodeId) {
        return tools.findByTenantIdAndNodeIdOrderByNameAsc(tenantId, nodeId).stream()
                .filter(NodeToolEntity::enabled)
                .map(NodeToolEntity::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String sandboxRoutingKey(String tenantId, Set<String> labels, Set<String> tools) {
        String labelPart = labels.stream().sorted().collect(java.util.stream.Collectors.joining(","));
        String toolPart = tools.stream().sorted().collect(java.util.stream.Collectors.joining(","));
        return tenantId + "|labels=" + labelPart + "|tools=" + toolPart;
    }

    private static Set<String> normalizedLabels(java.util.Collection<String> labels) {
        if (labels == null) {
            return null;
        }
        if (labels.size() > 16) {
            throw new IllegalArgumentException("A node may have at most 16 scheduling labels.");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String label : labels) {
            if (label == null || label.isBlank()) {
                continue;
            }
            String value = label.trim().toLowerCase(Locale.ROOT);
            if (!value.matches("[a-z0-9][a-z0-9_.-]{0,63}")) {
                throw new IllegalArgumentException(
                        "Invalid node label '" + label + "'. Use lowercase letters, digits, dot, underscore, or hyphen.");
            }
            normalized.add(value);
        }
        return Set.copyOf(normalized);
    }

    /** Prevents a node client from turning a personal local companion into an auto-routable sandbox. */
    private static void validateSchedulingMetadataUpdate(
            NodeConnectionEntity node, NodeKind requestedKind, Set<String> requestedLabels) {
        if (node.kind() == NodeKind.MANAGED_LOCAL) {
            if (requestedKind != null && requestedKind != NodeKind.MANAGED_LOCAL) {
                throw new IllegalArgumentException("The managed local computer cannot be converted into a sandbox.");
            }
            if (requestedLabels != null) {
                throw new IllegalArgumentException("The managed local computer does not support sandbox scheduling labels.");
            }
            return;
        }
        if (requestedKind == NodeKind.MANAGED_LOCAL) {
            throw new IllegalArgumentException("Only the local bootstrap flow may create a managed local computer.");
        }
    }

    private ExecutionMode executionMode(ActorContext actor) {
        return executionSettings == null ? ExecutionMode.PERSONAL_LOCAL : executionSettings.mode(actor);
    }

    private static String localExecutorName(BootstrapLocalExecutorCommand command) {
        if (command != null && command.name() != null && !command.name().isBlank()) {
            return command.name().trim();
        }
        return "This computer";
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
    public void setSystemAccess(String nodeId, boolean enabled, ActorContext actor) {
        NodeConnectionEntity node = requireNode(nodeId, actor);
        if (!node.features().contains("system-access.v1")) {
            throw new IllegalArgumentException("This node does not support system access.");
        }
        Instant now = Instant.now();
        tools.findByTenantIdAndNodeIdOrderByNameAsc(actor.tenantId(), nodeId).stream()
                .filter(tool -> tool.name().startsWith("system."))
                .forEach(tool -> {
                    tool.updatePolicy(enabled, !enabled, now);
                    tools.save(tool);
                });
    }

    @Transactional(noRollbackFor = Exception.class)
    public NodeToolCallResult callTool(String nodeId, String toolName, CallNodeToolCommand command, ActorContext actor) {
        return executeAuditedTool(
                null,
                "api_" + UUID.randomUUID(),
                nodeId,
                toolName,
                prepareCommand(toolName, command),
                actor,
                false);
    }

    @Transactional(noRollbackFor = Exception.class)
    public NodeToolCallResult callToolForRun(
            String runId,
            String toolCallId,
            String nodeId,
            String toolName,
            CallNodeToolCommand command,
            ActorContext actor) {
        return callToolForRun(runId, toolCallId, nodeId, toolName, command, actor, false);
    }

    /** Executes a Run-bound tool after its selected approval mode has bypassed the pause gate. */
    @Transactional(noRollbackFor = Exception.class)
    public NodeToolCallResult callToolForRun(
            String runId,
            String toolCallId,
            String nodeId,
            String toolName,
            CallNodeToolCommand command,
            ActorContext actor,
            boolean bypassApproval) {
        return executeAuditedTool(
                runId,
                toolCallId,
                nodeId,
                toolName,
                prepareCommand(toolName, command),
                actor,
                bypassApproval);
    }

    private NodeToolCallResult executeAuditedTool(
            String runId,
            String toolCallId,
            String nodeId,
            String toolName,
            CallNodeToolCommand preparedCommand,
            ActorContext actor,
            boolean bypassApproval) {
        Instant now = Instant.now();
        NodeToolInvocationEntity invocation = invocations.save(new NodeToolInvocationEntity(
                "nodeinv_" + UUID.randomUUID(),
                actor.tenantId(),
                runId,
                toolCallId,
                nodeId,
                toolName,
                toJson(preparedCommand.arguments()),
                now));
        try {
            NodeToolCallResult result = executeTool(
                    nodeId, toolName, preparedCommand, actor, bypassApproval, runId, invocation);
            if ("SUCCEEDED".equalsIgnoreCase(result.status())) {
                // 通道收到 tool.result 时已先做会话/摘要校验并落库；这里保留同步路径的兜底。
                if (!invocation.terminal()) {
                    String resultJson = toJson(result.result());
                    invocation.succeed(resultJson, sha256(resultJson), Instant.now());
                }
            } else if ("APPROVAL_REQUIRED".equalsIgnoreCase(result.status())) {
                if (runId != null && !runId.isBlank()) {
                    linkApprovalToRun(result, runId, toolCallId, actor);
                }
                invocation.fail(NodeToolInvocationStatus.APPROVAL_REQUIRED, result.errorMessage(), Instant.now());
                linkApprovalToInvocation(result, invocation.id(), actor);
            } else if ("UNKNOWN".equalsIgnoreCase(result.status())) {
                invocation.unknown(result.errorMessage(), Instant.now());
            } else {
                invocation.fail(NodeToolInvocationStatus.FAILED, result.errorMessage(), Instant.now());
            }
            invocations.save(invocation);
            return result;
        } catch (Exception ex) {
            // 发送前的本地 schema/大小校验尚未产生远程副作用；只有真正传输中断才标 UNKNOWN。
            if (ex instanceof IllegalArgumentException) {
                invocation.fail(NodeToolInvocationStatus.FAILED, ex.getMessage(), Instant.now());
            } else {
                invocation.unknown(ex.getMessage(), Instant.now());
            }
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

    /**
     * 手动要求节点核对某个 Run 中尚未确认的命令状态。
     *
     * <p>这是网络中断后的恢复辅助操作，不是“重试”接口。方法唯一允许发送的协议消息是
     * {@code tool.status}：节点客户端从它自己的持久 Journal 返回已知结果。即使原命令是写文件、
     * 浏览器点击或桌面操作，也不会在这里重新产生副作用。
     *
     * <p>节点离线、被禁用或控制帧发送失败时，仅在响应摘要中标记为不可用；不把原调用错误地改成
     * 成功、失败或取消。调用方可以等节点恢复后再次发起对账。
     */
    @Transactional(readOnly = true)
    public RunNodeReconciliationView requestRunReconciliation(String runId, ActorContext actor) {
        List<NodeToolInvocationStatus> pendingStatuses = List.of(
                NodeToolInvocationStatus.DISPATCHED,
                NodeToolInvocationStatus.ACCEPTED,
                NodeToolInvocationStatus.RUNNING,
                NodeToolInvocationStatus.UNKNOWN);
        List<NodeToolInvocationEntity> pending = invocations
                .findByTenantIdAndRunIdAndStatusInOrderByCreatedAtAsc(actor.tenantId(), runId, pendingStatuses);

        int requested = 0;
        int unavailable = 0;
        List<RunNodeReconciliationView.Invocation> results = new ArrayList<>();
        for (NodeToolInvocationEntity invocation : pending) {
            // 除了 WebSocket 仍打开外，还要确认节点属于当前租户、处于 ONLINE 且没有被管理员禁用。
            // 这样即便历史数据里存在过期 nodeId，也不会把控制消息误投递给别的节点会话。
            NodeConnectionEntity node = nodes.findByIdAndTenantId(invocation.nodeId(), actor.tenantId()).orElse(null);
            if (node == null
                    || !node.enabled()
                    || node.status() != NodeStatus.ONLINE
                    || !sessions.isConnected(invocation.nodeId())) {
                unavailable++;
                results.add(reconciliationUnavailable(invocation));
                continue;
            }
            try {
                // 注意：此处绝不能改为 sessions.invoke(...)；invoke 会发出 tool.invoke，从而可能重放副作用。
                sessions.sendControl(invocation.nodeId(), "tool.status", invocation.id(), Map.of(
                        "invocationId", invocation.id(),
                        "toolName", invocation.toolName(),
                        "argumentsDigest", invocation.argumentsDigest(),
                        "attempt", invocation.dispatchAttempt()));
                requested++;
                results.add(new RunNodeReconciliationView.Invocation(
                        invocation.id(),
                        invocation.nodeId(),
                        invocation.toolName(),
                        invocation.status(),
                        RunNodeReconciliationView.Outcome.STATUS_REQUESTED));
            } catch (Exception ignored) {
                // 发送期间节点可能刚断开。保持持久调用记录不变，等下次恢复后再安全地查询状态。
                unavailable++;
                results.add(reconciliationUnavailable(invocation));
            }
        }
        return new RunNodeReconciliationView(runId, requested, unavailable, List.copyOf(results));
    }

    private RunNodeReconciliationView.Invocation reconciliationUnavailable(NodeToolInvocationEntity invocation) {
        return new RunNodeReconciliationView.Invocation(
                invocation.id(),
                invocation.nodeId(),
                invocation.toolName(),
                invocation.status(),
                RunNodeReconciliationView.Outcome.NODE_UNAVAILABLE);
    }

    /**
     * 向一个 Run 中仍可能在节点端执行的调用发送协作式取消。
     *
     * <p>这里故意不把 invocation 直接改成 CANCELLED：WebSocket 发送成功或节点返回 ACK
     * 只能证明取消请求被接收，无法证明文件、进程、浏览器或桌面副作用已经回滚。最终状态
     * 仍须由 tool.result / tool.status 对账消息写入。
     */
    @Transactional(noRollbackFor = Exception.class)
    public int cancelRunInvocations(String runId, ActorContext actor) {
        if (runId == null || runId.isBlank()) {
            return 0;
        }
        List<NodeToolInvocationStatus> activeStatuses = List.of(
                NodeToolInvocationStatus.REQUESTED,
                NodeToolInvocationStatus.DISPATCHED,
                NodeToolInvocationStatus.ACCEPTED,
                NodeToolInvocationStatus.RUNNING);
        // PENDING 审批尚未下发到节点，但若不使其失效，用户取消 Run 后仍可能由另一个
        // 审批人批准并触发副作用。当前审批状态没有 CANCELLED，因此用 EXPIRED 表示它
        // 已不再允许决策，同时保留原始审批审计记录。
        Instant now = Instant.now();
        for (NodeToolApprovalEntity approval : approvals.findByTenantIdAndRunId(actor.tenantId(), runId)) {
            if (approval.status() == NodeToolApprovalStatus.PENDING) {
                approval.expire(actor.userId(), now);
                approvals.save(approval);
            }
        }
        int sent = 0;
        for (NodeToolInvocationEntity invocation : invocations
                .findByTenantIdAndRunIdOrderByCreatedAtAsc(actor.tenantId(), runId)) {
            if (!activeStatuses.contains(invocation.status())) {
                continue;
            }
            if (sessions.cancel(invocation.nodeId(), invocation.id(), "trace_" + invocation.id())) {
                sent++;
            }
        }
        return sent;
    }

    @Transactional(readOnly = true)
    public CodingRunEvidenceView codingEvidence(String runId, ActorContext actor) {
        List<NodeToolInvocationEntity> history = invocations.findByTenantIdAndRunIdOrderByCreatedAtAsc(actor.tenantId(), runId);

        // 这里的摘要会直接返回给用户，因此只保留“项目内相对路径”。
        // 调用记录来自数据库，不能假定旧数据或手工修复过的数据一定合法；
        // 绝对路径、包含 ".." 的路径都不应该通过这个接口泄露出去。
        List<String> changedFiles = history.stream()
                .filter(invocation -> "fs.write".equals(invocation.toolName())
                        || "fs.apply_patch".equals(invocation.toolName())
                        || "fs.apply_patch_batch".equals(invocation.toolName()))
                .filter(invocation -> invocation.status() == NodeToolInvocationStatus.SUCCEEDED)
                .flatMap(invocation -> evidenceFilePaths(invocation).stream())
                .distinct()
                .toList();
        PostChangeReviewEvidence reviewEvidence = postChangeReviewEvidence(history, changedFiles);

        List<String> succeededTools = history.stream()
                .filter(invocation -> invocation.status() == NodeToolInvocationStatus.SUCCEEDED)
                .map(NodeToolInvocationEntity::toolName)
                .distinct()
                .toList();

        int desktopSortableFiles = history.stream()
                .filter(invocation -> "system.desktop.organize.list".equals(invocation.toolName()))
                .filter(invocation -> invocation.status() == NodeToolInvocationStatus.SUCCEEDED)
                .mapToInt(this::desktopSortableFiles)
                .reduce((ignored, observed) -> observed)
                .orElse(-1);

        // shell.run 和 browser.* 是“已经做过验证”的证据。这里刻意只返回工具名称，
        // 不返回命令参数或标准输出，以免日志中的敏感信息被这个摘要接口暴露。
        List<String> verificationTools = history.stream()
                .filter(invocation -> invocation.status() == NodeToolInvocationStatus.SUCCEEDED)
                .map(NodeToolInvocationEntity::toolName)
                .filter(name -> "shell.run".equals(name) || isManagedProcessWaitHttp(name) || name.startsWith("browser."))
                .distinct()
                .toList();
        // 命令原文留在受权限保护的审计表中。交付门禁只需要知道它是不是可解释的测试、构建、静态检查
        // 或 HTTP 联调，因此这里输出有限的类别，既能拒绝 `echo done`，也不会把命令参数暴露给页面。
        List<String> commandVerifications = history.stream()
                .filter(invocation -> invocation.status() == NodeToolInvocationStatus.SUCCEEDED)
                .map(invocation -> isManagedProcessWaitHttp(invocation.toolName())
                        ? java.util.Optional.of("http")
                        : "shell.run".equals(invocation.toolName())
                                ? commandVerificationCategory(invocation)
                                : java.util.Optional.<String>empty())
                .flatMap(java.util.Optional::stream)
                .distinct()
                .toList();
        List<String> failedTools = unresolvedFailedTools(history);
        // Trace 的绝对路径仅保留在受控审计调用记录中。交付摘要只显示经过格式校验的文件名，
        // 既能证明可回放证据存在，也不会暴露节点用户目录或临时目录结构。
        List<String> browserTraceArtifacts = history.stream()
                .filter(invocation -> "browser.trace.stop".equals(invocation.toolName()))
                .filter(invocation -> invocation.status() == NodeToolInvocationStatus.SUCCEEDED)
                .map(this::traceArtifactName)
                .flatMap(java.util.Optional::stream)
                .distinct()
                .toList();
        boolean browserVerified = browserVerifiedAfterLastInteraction(history);
        // “页面上看起来成功”与“前端确实拿到了后端成功响应”是两种不同证据。
        // 只有任务明确要求前后端联调时，交付门禁才会强制后者；普通静态网页任务仍可只做可见状态断言。
        boolean browserApiVerified = browserApiVerifiedAfterLastInteraction(history);
        boolean desktopUiVerified = desktopUiVerifiedAfterLastControlAction(history);
        boolean desktopApplicationVerified = desktopApplicationVerifiedAfterStart(history);
        boolean managedProcessReady = managedProcessReadyAfterStart(history);
        boolean managedProcessReadyAfterLastProjectChange = managedProcessReadyAfterLastProjectChange(history);
        return new CodingRunEvidenceView(
                runId,
                history.size(),
                succeededTools,
                desktopSortableFiles,
                changedFiles,
                reviewEvidence.gitReviewed(),
                reviewEvidence.reviewedChangedFiles(),
                verificationTools,
                commandVerifications,
                browserTraceArtifacts,
                browserVerified,
                desktopUiVerified,
                failedTools,
                browserApiVerified,
                desktopApplicationVerified,
                managedProcessReady,
                managedProcessReadyAfterLastProjectChange);
    }

    /**
     * A model may correct a malformed tool call after reading the structured
     * failure (for example, by wrapping a quoted PowerShell script with
     * {@code cmd /c}). A failed read of a not-yet-created file is also resolved
     * when the same file is successfully written later in the run. Only failures
     * with no such recovery remain evidence for the delivery gate.
     */
    private List<String> unresolvedFailedTools(List<NodeToolInvocationEntity> history) {
        LinkedHashSet<String> unresolved = new LinkedHashSet<>();
        for (int index = 0; index < history.size(); index++) {
            NodeToolInvocationEntity failed = history.get(index);
            if (failed.status() != NodeToolInvocationStatus.FAILED) {
                continue;
            }
            boolean recovered = history.subList(index + 1, history.size()).stream()
                    .anyMatch(later -> later.status() == NodeToolInvocationStatus.SUCCEEDED
                            && (later.toolName().equals(failed.toolName())
                            || successfulWriteRecoversFailedRead(failed, later)));
            if (!recovered) {
                unresolved.add(failed.toolName());
            }
        }
        return List.copyOf(unresolved);
    }

    /**
     * A read-before-create probe is expected while scaffolding a new project.
     * Match the exact audited path, rather than merely accepting any later write,
     * so an unrelated successful edit cannot hide a failed read elsewhere.
     */
    private boolean successfulWriteRecoversFailedRead(
            NodeToolInvocationEntity failed,
            NodeToolInvocationEntity later) {
        if (!"fs.read".equals(failed.toolName())
                || !("fs.write".equals(later.toolName()) || "fs.apply_patch".equals(later.toolName()))) {
            return false;
        }
        Object failedPath = readJsonMap(failed.argumentsJson()).get("path");
        Object writtenPath = readJsonMap(later.argumentsJson()).get("path");
        return failedPath instanceof String failedText
                && writtenPath instanceof String writtenText
                && !failedText.isBlank()
                && failedText.equals(writtenText);
    }

    /**
     * 只采纳最后一次成功写入之后的审阅动作。
     *
     * <p>若模型先读取旧文件、随后改写代码，旧读取结果不能证明最终代码已经审过；同理，
     * 早于最后一次写入的 git.review 也不能作为交付依据。审计记录已按创建时间升序返回，
     * 因此这里用列表顺序而不是节点客户端提供的布尔字段来计算证据。
     */
    private PostChangeReviewEvidence postChangeReviewEvidence(
            List<NodeToolInvocationEntity> history,
            List<String> changedFiles) {
        if (changedFiles.isEmpty()) {
            return new PostChangeReviewEvidence(false, List.of());
        }
        int lastChangeIndex = -1;
        for (int index = 0; index < history.size(); index++) {
            NodeToolInvocationEntity invocation = history.get(index);
            if (invocation.status() == NodeToolInvocationStatus.SUCCEEDED && changesProjectFiles(invocation)) {
                lastChangeIndex = index;
            }
        }
        if (lastChangeIndex < 0) {
            return new PostChangeReviewEvidence(false, List.of());
        }

        boolean gitReviewed = false;
        LinkedHashSet<String> reviewed = new LinkedHashSet<>();
        for (int index = lastChangeIndex + 1; index < history.size(); index++) {
            NodeToolInvocationEntity invocation = history.get(index);
            if (invocation.status() != NodeToolInvocationStatus.SUCCEEDED) {
                continue;
            }
            if ("git.review".equals(invocation.toolName())) {
                gitReviewed = true;
                continue;
            }
            if ("fs.read".equals(invocation.toolName())) {
                evidenceFilePath(invocation)
                        .filter(changedFiles::contains)
                        .ifPresent(reviewed::add);
                continue;
            }
            if ("git.diff".equals(invocation.toolName())) {
                // 未指定 path 的 diff 覆盖整个工作树；指定目录则覆盖其下的变更文件。
                java.util.Optional<String> scope = evidenceFilePath(invocation);
                if (scope.isEmpty()) {
                    reviewed.addAll(changedFiles);
                } else {
                    changedFiles.stream()
                            .filter(path -> path.equals(scope.get()) || path.startsWith(scope.get() + "/"))
                            .forEach(reviewed::add);
                }
            }
        }
        return new PostChangeReviewEvidence(gitReviewed, List.copyOf(reviewed));
    }

    private static boolean changesProjectFiles(NodeToolInvocationEntity invocation) {
        return "fs.write".equals(invocation.toolName())
                || "fs.apply_patch".equals(invocation.toolName())
                || "fs.apply_patch_batch".equals(invocation.toolName());
    }

    /**
     * 判断浏览器验证是否对应最终的页面交互。
     *
     * <p>打开页面、获取快照、截图或导出 Trace 都不能证明表单提交、跳转或下载达到了预期。
     * 只有最后一次会改变页面、焦点或浏览器当前标签页的操作之后执行 browser.verify，才记为
     * 有效验证；这样旧的断言结果不会掩盖后续操作造成的回归。
     */
    private static boolean browserVerifiedAfterLastInteraction(List<NodeToolInvocationEntity> history) {
        int lastInteraction = -1;
        for (int index = 0; index < history.size(); index++) {
            NodeToolInvocationEntity invocation = history.get(index);
            if (invocation.status() == NodeToolInvocationStatus.SUCCEEDED
                    && changesBrowserPageState(invocation.toolName())) {
                lastInteraction = index;
            }
        }
        if (lastInteraction < 0) {
            return false;
        }
        for (int index = lastInteraction + 1; index < history.size(); index++) {
            NodeToolInvocationEntity invocation = history.get(index);
            if (invocation.status() == NodeToolInvocationStatus.SUCCEEDED
                    && "browser.verify".equals(invocation.toolName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断最后一次页面状态操作后是否有一条真正的 API 响应断言。
     *
     * <p>不能仅凭 browser.verify 调用成功就返回 true：它也可能只验证了文字、标题或 URL。
     * 这里必须在其结构化 checks 中找到已通过的 {@code responseStatus} 或
     * {@code responseUrlContains}，并且只采纳最后一次页面操作之后的那一次验证。
     */
    private boolean browserApiVerifiedAfterLastInteraction(List<NodeToolInvocationEntity> history) {
        int lastInteraction = -1;
        for (int index = 0; index < history.size(); index++) {
            NodeToolInvocationEntity invocation = history.get(index);
            if (invocation.status() == NodeToolInvocationStatus.SUCCEEDED
                    && changesBrowserPageState(invocation.toolName())) {
                lastInteraction = index;
            }
        }
        if (lastInteraction < 0) {
            return false;
        }
        for (int index = lastInteraction + 1; index < history.size(); index++) {
            NodeToolInvocationEntity invocation = history.get(index);
            if (invocation.status() == NodeToolInvocationStatus.SUCCEEDED
                    && "browser.verify".equals(invocation.toolName())
                    && hasPassedBrowserApiCheck(invocation)) {
                return true;
            }
        }
        return false;
    }

    /** 只读取已净化的布尔字段和检查类别；不把页面文本、URL 查询参数或响应正文带入交付摘要。 */
    private boolean hasPassedBrowserApiCheck(NodeToolInvocationEntity invocation) {
        Map<String, Object> result = readJsonMap(invocation.resultJson());
        if (!Boolean.TRUE.equals(result.get("verified")) || !(result.get("checks") instanceof List<?> checks)) {
            return false;
        }
        for (Object rawCheck : checks) {
            if (!(rawCheck instanceof Map<?, ?> check)) {
                continue;
            }
            String type = String.valueOf(check.get("type"));
            if (("responseStatus".equals(type) || "responseUrlContains".equals(type))
                    && Boolean.TRUE.equals(check.get("passed"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean changesBrowserPageState(String toolName) {
        return switch (toolName) {
            case "browser.open", "browser.switch_tab", "browser.close_tab", "browser.download", "browser.upload",
                    "browser.click", "browser.type", "browser.hover", "browser.press",
                    "browser.select_option" -> true;
            default -> false;
        };
    }

    /**
     * Windows UI Automation 控件可能会在点击或输入后重绘，因此只能采信最终操作之后的 verify
     * 或受审批的 read_value。后者不仅能找到目标控件，还能确认非密码 ValuePattern 的实际值。
     * 键盘命令没有控件选择器，不能安全地冒充对某个具体控件的验证，仍由调用方用新快照或
     * 明确的 UI 验证工具处理。
     */
    private static boolean desktopUiVerifiedAfterLastControlAction(List<NodeToolInvocationEntity> history) {
        int lastControlAction = -1;
        for (int index = 0; index < history.size(); index++) {
            NodeToolInvocationEntity invocation = history.get(index);
            if (invocation.status() == NodeToolInvocationStatus.SUCCEEDED
                    && changesDesktopControl(invocation.toolName())) {
                lastControlAction = index;
            }
        }
        if (lastControlAction < 0) {
            return false;
        }
        for (int index = lastControlAction + 1; index < history.size(); index++) {
            NodeToolInvocationEntity invocation = history.get(index);
            if (invocation.status() == NodeToolInvocationStatus.SUCCEEDED
                    && ("system.desktop.ui.verify".equals(invocation.toolName())
                    || "system.desktop.ui.read_value".equals(invocation.toolName()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean changesDesktopControl(String toolName) {
        return "system.desktop.ui.click".equals(toolName) || "system.desktop.ui.type".equals(toolName);
    }

    /**
     * 证明开发服务就绪时，必须把本次 Run 创建的受管进程和后续 HTTP 探测严格关联起来。
     *
     * <p>单独的 {@code process.start} 只表示进程已被创建，单独的 {@code process.wait_http}
     * 也可能是在检查其他 Run 遗留的服务。只有等待调用引用了此前同一 Run 返回的进程句柄，
     * 才能作为当前项目启动并就绪的交付证据。
     */
    private boolean managedProcessReadyAfterStart(List<NodeToolInvocationEntity> history) {
        return managedProcessReadyAfter(history, -1);
    }

    /**
     * 与普通“本 Run 曾就绪”不同，该证据从最后一次成功项目修改之后重新开始建立进程关联。
     *
     * <p>源代码改变后，即使旧开发进程还在运行，也不能假定它完成了热更新或加载了最新后端；
     * 因此前一个 process.start/process.wait_http 对不再能证明当前交付。模型必须重新启动受管服务，
     * 再用返回的同一 processId 执行 loopback 探测。
     */
    private boolean managedProcessReadyAfterLastProjectChange(List<NodeToolInvocationEntity> history) {
        int lastProjectChange = -1;
        for (int index = 0; index < history.size(); index++) {
            NodeToolInvocationEntity invocation = history.get(index);
            if (invocation.status() == NodeToolInvocationStatus.SUCCEEDED && changesProjectFiles(invocation)) {
                lastProjectChange = index;
            }
        }
        return managedProcessReadyAfter(history, lastProjectChange);
    }

    private boolean managedProcessReadyAfter(List<NodeToolInvocationEntity> history, int afterIndex) {
        Set<String> startedProcessIds = new LinkedHashSet<>();
        for (int index = Math.max(0, afterIndex + 1); index < history.size(); index++) {
            NodeToolInvocationEntity invocation = history.get(index);
            if (invocation.status() != NodeToolInvocationStatus.SUCCEEDED) {
                continue;
            }
            if (isManagedProcessStart(invocation.toolName())) {
                String processId = opaqueProcessId(readJsonMap(invocation.resultJson()).get("processId"));
                if (processId != null) {
                    startedProcessIds.add(processId);
                }
                continue;
            }
            if (isManagedProcessWaitHttp(invocation.toolName())) {
                String processId = opaqueProcessId(readJsonMap(invocation.argumentsJson()).get("processId"));
                if (processId != null && startedProcessIds.contains(processId)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 受管进程 ID 是节点生成的不透明标识，只接受有界的非空字符串，避免损坏审计记录扩大证据范围。 */
    private static String opaqueProcessId(Object value) {
        String processId = value == null ? null : value.toString().trim();
        return processId == null || processId.isEmpty() || processId.length() > 200 ? null : processId;
    }

    /**
     * 将“启动了进程”和“桌面上真的出现了可交互窗口”区分开。
     *
     * <p>应用启动工具只返回 Windows 创建的 PID。窗口创建是异步的，甚至可能因为
     * 单实例应用、权限或启动失败而根本不出现。因此每个成功启动所返回的 PID，都必须在
     * 后续一次成功的桌面会话快照中作为顶层可见窗口出现。这里仅信任服务端保存的调用
     * 历史和节点结果中的数值字段，不使用模型文本或进程名猜测。
     */
    private boolean desktopApplicationVerifiedAfterStart(List<NodeToolInvocationEntity> history) {
        Set<Long> pendingProcessIds = new LinkedHashSet<>();
        boolean startedApplication = false;
        for (NodeToolInvocationEntity invocation : history) {
            if (invocation.status() != NodeToolInvocationStatus.SUCCEEDED) {
                continue;
            }
            if ("system.desktop.application.start".equals(invocation.toolName())) {
                startedApplication = true;
                Long processId = positiveLong(readJsonMap(invocation.resultJson()).get("processId"));
                // 旧记录损坏或不符合协议时，也绝不能把它当成已经验证过的窗口。
                if (processId == null) {
                    return false;
                }
                pendingProcessIds.add(processId);
                continue;
            }
            if ("system.desktop.session.snapshot".equals(invocation.toolName())) {
                pendingProcessIds.removeAll(visibleWindowProcessIds(invocation));
            }
        }
        return !startedApplication || pendingProcessIds.isEmpty();
    }

    /** 读取快照中的窗口 PID；格式异常的旧审计记录只能提供空证据，不能导致交付判断异常。 */
    private Set<Long> visibleWindowProcessIds(NodeToolInvocationEntity invocation) {
        Object rawWindows = readJsonMap(invocation.resultJson()).get("windows");
        if (!(rawWindows instanceof List<?> windows)) {
            return Set.of();
        }
        Set<Long> processIds = new LinkedHashSet<>();
        for (Object rawWindow : windows) {
            if (rawWindow instanceof Map<?, ?> window) {
                Long processId = positiveLong(window.get("processId"));
                if (processId != null) {
                    processIds.add(processId);
                }
            }
        }
        return processIds;
    }

    /** JSON 既可能还原为 Number，也可能来自历史记录中的数字字符串。 */
    private static Long positiveLong(Object value) {
        String text = value instanceof Number number ? number.toString() : value instanceof String string ? string : null;
        if (text == null || !text.matches("[1-9][0-9]*")) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            // 超过 long 范围的恶意或损坏记录不能证明 PID 已被验证。
        }
        return null;
    }

    private record PostChangeReviewEvidence(boolean gitReviewed, List<String> reviewedChangedFiles) {
    }

    private int desktopSortableFiles(NodeToolInvocationEntity invocation) {
        try {
            Map<String, Object> result = objectMapper.readValue(invocation.resultJson(), new TypeReference<>() { });
            Object value = result.get("sortableFiles");
            if (value instanceof Number number) {
                return Math.max(-1, number.intValue());
            }
            if (value instanceof String text) {
                return Math.max(-1, Integer.parseInt(text));
            }
        } catch (Exception ignored) {
            // Malformed or old audit data cannot prove a no-op desktop task succeeded.
        }
        return -1;
    }

    /**
     * 根据已持久化的交付证据计算一个可解释评分。
     *
     * <p>浏览器验证是可选项：纯后端或命令行项目不会因为没有浏览器工具而扣分；
     * 一旦实际尝试了浏览器验证，它会成为额外的、可追踪的验证项。
     */
    @Transactional(readOnly = true)
    public CodingRunQualityView codingQuality(String runId, ActorContext actor) {
        CodingRunEvidenceView evidence = codingEvidence(runId, actor);
        List<CodingRunQualityView.CodingQualityCheckView> checks = new java.util.ArrayList<>();
        checks.add(qualityCheck(
                "changed-files",
                25,
                !evidence.changedFiles().isEmpty(),
                "至少成功写入或修补一个项目文件。"));
        checks.add(qualityCheck(
                "command-verification",
                45,
                !evidence.commandVerifications().isEmpty(),
                "至少成功执行一次构建、测试或其他命令验证。"));
        checks.add(qualityCheck(
                "clean-tool-run",
                20,
                evidence.failedTools().isEmpty(),
                "本次记录中没有失败的节点工具调用。"));

        boolean attemptedBrowser = evidence.verificationTools().stream().anyMatch(NodeService::changesBrowserPageState)
                || evidence.failedTools().stream().anyMatch(name -> name.startsWith("browser."));
        if (attemptedBrowser) {
            checks.add(qualityCheck(
                    "browser-verification",
                    10,
                    evidence.browserVerified(),
                    "执行浏览器页面交互后，应在最后一次交互之后成功执行 browser.verify。"));
        }
        boolean attemptedDesktopUi = evidence.succeededTools().stream().anyMatch(NodeService::changesDesktopControl)
                || evidence.failedTools().stream().anyMatch(NodeService::changesDesktopControl);
        if (attemptedDesktopUi) {
            checks.add(qualityCheck(
                    "desktop-ui-verification",
                    10,
                    evidence.desktopUiVerified(),
                    "执行 Windows UI Automation 点击或输入后，应成功执行 system.desktop.ui.verify。"));
        }
        boolean startedDesktopApplication = evidence.succeededTools().contains("system.desktop.application.start");
        if (startedDesktopApplication) {
            checks.add(qualityCheck(
                    "desktop-application-window-verification",
                    10,
                    evidence.desktopApplicationVerified(),
                    "启动桌面应用后，应在后续 system.desktop.session.snapshot 中确认相同 PID 的可见窗口。"));
        }
        int maximum = checks.stream().mapToInt(CodingRunQualityView.CodingQualityCheckView::maximumPoints).sum();
        int earned = checks.stream().mapToInt(CodingRunQualityView.CodingQualityCheckView::earnedPoints).sum();
        int score = maximum == 0 ? 0 : Math.round(earned * 100.0f / maximum);
        List<String> recommendations = checks.stream()
                .filter(check -> !check.passed())
                .map(CodingRunQualityView.CodingQualityCheckView::explanation)
                .toList();
        return new CodingRunQualityView(runId, score, qualityGrade(score), checks, recommendations);
    }

    /**
     * 只将明确具有验证意图的命令计入交付证据。这个分类不是执行授权，也不是命令解析器；
     * 它只是服务端审计层的保守标签，宁可不把未知命令计分，也不把任意成功退出的命令误当测试。
     */
    private java.util.Optional<String> commandVerificationCategory(NodeToolInvocationEntity invocation) {
        Object rawCommand = readJsonMap(invocation.argumentsJson()).get("command");
        if (!(rawCommand instanceof String command) || command.isBlank()) {
            return java.util.Optional.empty();
        }
        String normalized = command.toLowerCase(Locale.ROOT).replace('\\', '/');
        if (normalized.matches("(?s).*\\b(gradlew|mvn|npm|pnpm|yarn|bun|pytest|cargo|go|dotnet|vitest|jest).*\\b(test|tests|verify|check)\\b.*")) {
            return java.util.Optional.of("test");
        }
        // Plain Java fixture projects commonly use a self-contained main-method
        // test instead of a build tool. Require the conventional Test/Tests
        // suffix so arbitrary `java SomeProgram` commands never become evidence.
        if (normalized.matches("(?s).*\\bjava(?:\\.exe)?\\s+(?:[a-z_$][a-z0-9_$]*\\.)*[a-z_$][a-z0-9_$]*(?:test|tests)\\b.*")) {
            return java.util.Optional.of("test");
        }
        if (normalized.matches("(?s).*\\b(gradlew|mvn|npm|pnpm|yarn|bun|cargo|go|dotnet).*\\b(build|package|compile|assemble)\\b.*")) {
            return java.util.Optional.of("build");
        }
        if (normalized.matches("(?s).*\\b(eslint|ruff|flake8|checkstyle|spotbugs|lint)\\b.*")) {
            return java.util.Optional.of("lint");
        }
        if (normalized.matches("(?s).*\\b(tsc|mypy|pyright|typecheck)\\b.*")) {
            return java.util.Optional.of("typecheck");
        }
        if (normalized.matches("(?s).*\\b(curl|invoke-webrequest|invoke-restmethod|httpie)\\b.*")) {
            return java.util.Optional.of("http");
        }
        return java.util.Optional.empty();
    }

    private static CodingRunQualityView.CodingQualityCheckView qualityCheck(
            String name, int maximumPoints, boolean passed, String explanation) {
        return new CodingRunQualityView.CodingQualityCheckView(
                name, passed ? maximumPoints : 0, maximumPoints, passed, explanation);
    }

    private static String qualityGrade(int score) {
        if (score >= 90) {
            return "excellent";
        }
        if (score >= 70) {
            return "good";
        }
        if (score >= 40) {
            return "needs-verification";
        }
        return "incomplete";
    }

    /**
     * 从一次文件写入调用中取出可安全展示的工作区相对路径。
     *
     * <p>这不是文件系统权限校验（真正的权限校验在节点工具中完成），而是“展示层兜底”：
     * 即使历史 JSON 损坏或被人为写入了绝对路径，交付摘要也仍然可用且不会泄露机器目录。
     */
    private java.util.Optional<String> evidenceFilePath(NodeToolInvocationEntity invocation) {
        Object rawPath = readJsonMap(invocation.argumentsJson()).get("path");
        if (!(rawPath instanceof String path) || path.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            String normalized = Path.of(path.trim()).normalize().toString().replace('\\', '/');
            if (Path.of(path.trim()).isAbsolute()
                    || normalized.isBlank()
                    || ".".equals(normalized)
                    || "..".equals(normalized)
                    || normalized.startsWith("../")) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(normalized);
        } catch (InvalidPathException ex) {
            // 单条坏记录不应导致整个编码任务的交付信息无法查看。
            return java.util.Optional.empty();
        }
    }

    /** 从审计结果中提取可安全展示的 Trace 文件名。 */
    private java.util.Optional<String> traceArtifactName(NodeToolInvocationEntity invocation) {
        Map<String, Object> result = readJsonMap(invocation.resultJson());
        Object rawPath = result.containsKey("artifactPath") ? result.get("artifactPath") : result.get("path");
        if (!(rawPath instanceof String path) || path.isBlank()) {
            Object artifact = result.get("artifact");
            if (artifact instanceof Map<?, ?> artifactMap) {
                rawPath = artifactMap.get("filename");
            }
        }
        if (!(rawPath instanceof String path) || path.isBlank()) {
            return java.util.Optional.empty();
        }
        String normalized = path.trim().replace('\\', '/');
        String filename = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (!filename.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,254}\\.zip")) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(filename);
    }

    /**
     * Stops only process handles that this exact run previously started. This intentionally bypasses
     * the normal approval gate: allowing an autonomous run to start a managed process also grants it
     * the narrower ability to clean up that same handle, not to stop arbitrary node processes.
     */
    @Transactional(noRollbackFor = Exception.class)
    public List<NodeToolCallResult> cleanupManagedProcessesForRun(String runId, ActorContext actor) {
        List<NodeToolInvocationEntity> history = invocations.findByTenantIdAndRunIdOrderByCreatedAtAsc(actor.tenantId(), runId);
        List<ManagedProcessTarget> invocationTargets = history.stream()
                .filter(invocation -> invocation.status() == NodeToolInvocationStatus.SUCCEEDED)
                .filter(invocation -> isManagedProcessStart(invocation.toolName()))
                .map(this::managedProcessTarget)
                .flatMap(java.util.Optional::stream)
                .toList();
        List<ManagedProcessTarget> approvalTargets = approvals.findByTenantIdAndRunId(actor.tenantId(), runId).stream()
                .filter(approval -> approval.status() == NodeToolApprovalStatus.APPROVED)
                .filter(approval -> "SUCCEEDED".equalsIgnoreCase(approval.executionStatus()))
                .filter(approval -> isManagedProcessStart(approval.toolName()))
                .map(this::managedProcessTarget)
                .flatMap(java.util.Optional::stream)
                .toList();
        List<ManagedProcessTarget> targets = java.util.stream.Stream.concat(invocationTargets.stream(), approvalTargets.stream())
                .filter(target -> !wasStopped(history, target.processId()))
                .distinct()
                .toList();
        List<NodeToolCallResult> results = new java.util.ArrayList<>();
        for (ManagedProcessTarget target : targets) {
            results.add(stopManagedProcessForRun(runId, target, actor));
        }
        return results;
    }

    /** Releases browser pages created for this run without granting arbitrary browser-close access. */
    @Transactional(readOnly = true)
    public List<NodeToolCallResult> cleanupBrowserSessionsForRun(String runId, ActorContext actor) {
        List<String> browserNodes = invocations.findByTenantIdAndRunIdOrderByCreatedAtAsc(actor.tenantId(), runId).stream()
                .filter(invocation -> invocation.toolName().startsWith("browser."))
                .map(NodeToolInvocationEntity::nodeId)
                .distinct()
                .toList();
        List<NodeToolCallResult> results = new java.util.ArrayList<>();
        for (String nodeId : browserNodes) {
            try {
                results.add(sessions.invoke(
                        nodeId,
                        "browser.close_session",
                        Map.of(),
                        Duration.ofSeconds(10),
                        runId));
            } catch (Exception ex) {
                results.add(new NodeToolCallResult(null, nodeId, "browser.close_session", "FAILED", null, ex.getMessage()));
            }
        }
        return results;
    }

    @Transactional
    public NodeToolApprovalView requestToolApproval(
            String nodeId,
            String toolName,
            CallNodeToolCommand command,
            ActorContext actor) {
        CallNodeToolCommand preparedCommand = prepareCommand(toolName, command);
        requireNode(nodeId, actor);
        NodeToolEntity tool = tools.findByTenantIdAndNodeIdAndName(actor.tenantId(), nodeId, toolName)
                .orElseThrow(() -> new IllegalArgumentException("Node tool not found: " + toolName));
        if (!tool.enabled()) {
            throw new IllegalArgumentException("Node tool is disabled: " + toolName);
        }
        if (!tool.requiresApproval()) {
            throw new IllegalArgumentException("Node tool does not require approval: " + toolName);
        }
        // 审批记录必须保存经过服务端校验的参数，避免批准后才发现语义请求不合法。
        int timeoutSeconds = timeoutSeconds(preparedCommand);
        Instant now = Instant.now();
        String argumentsJson = toJson(preparedCommand.arguments());
        NodeToolApprovalEntity approval = approvals.save(new NodeToolApprovalEntity(
                "nodeapproval_" + UUID.randomUUID(),
                actor.tenantId(),
                nodeId,
                toolName,
                argumentsJson,
                sha256(argumentsJson),
                TOOL_APPROVER_ROLE,
                timeoutSeconds,
                actor.userId(),
                now,
                now.plusSeconds(TOOL_APPROVAL_TTL_SECONDS)));
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
    // Persist the approval decision before invoking a node. Keeping the database transaction
    // open while a shell/desktop command runs blocks cancellation and can make H2 time out on
    // the same approval row. Repository calls below commit the state transition before the
    // potentially long-running node invocation.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public NodeToolApprovalDecisionView decideToolApproval(
            String approvalId,
            DecideNodeToolApprovalCommand command,
            ActorContext actor) {
        NodeToolApprovalEntity approval = approvals.findByIdAndTenantId(approvalId, actor.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Node tool approval not found: " + approvalId));
        boolean approved = command != null && command.approved();
        Instant now = Instant.now();
        requireApprovalRole(actor, approval.requiredRole());
        if (approval.expired(now)) {
            approval.expire(actor.userId(), now);
            approvals.saveAndFlush(approval);
            return new NodeToolApprovalDecisionView(NodeToolApprovalView.from(approval), null);
        }
        if (!MessageDigest.isEqual(
                sha256(approval.argumentsJson()).getBytes(StandardCharsets.UTF_8),
                blankToEmpty(approval.argumentsSha256()).getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalStateException("Node tool approval arguments no longer match the approved digest.");
        }
        approval.decide(approved ? NodeToolApprovalStatus.APPROVED : NodeToolApprovalStatus.REJECTED, actor.userId(), now);
        // Flush the irreversible decision before dispatching so concurrent submissions cannot execute twice.
        approvals.saveAndFlush(approval);
        if (!approved) {
            return new NodeToolApprovalDecisionView(NodeToolApprovalView.from(approval), null);
        }

        NodeToolCallResult execution;
        try {
            NodeToolInvocationEntity invocation = approval.invocationId() == null
                    ? null
                    : invocations.findByIdAndNodeId(approval.invocationId(), approval.nodeId()).orElse(null);
            execution = executeTool(
                    approval.nodeId(),
                    approval.toolName(),
                    new CallNodeToolCommand(readArguments(approval.argumentsJson()), approval.timeoutSeconds()),
                    actor,
                    true,
                    approval.runId(),
                    invocation);
            recordApprovalExecution(approval, execution);
        } catch (Exception ex) {
            execution = new NodeToolCallResult(
                    null,
                    approval.nodeId(),
                    approval.toolName(),
                    "FAILED",
                    null,
                    ex.getMessage());
            recordApprovalExecution(approval, execution);
        }
        NodeToolApprovalEntity persisted = persistApprovalExecution(approval, execution);
        return new NodeToolApprovalDecisionView(NodeToolApprovalView.from(persisted), execution);
    }

    /**
     * The run cancellation path may expire the same approval while the node is
     * returning its result. Refresh once after an optimistic-lock conflict so
     * cancellation wins without turning a completed stop into a REST error.
     */
    private NodeToolApprovalEntity persistApprovalExecution(
            NodeToolApprovalEntity approval,
            NodeToolCallResult execution) {
        String resultJson = toJson(execution.result());
        Instant now = Instant.now();
        int updated = approvals.recordExecution(
                approval.id(),
                approval.tenantId(),
                execution.status(),
                resultJson,
                execution.errorMessage(),
                now);
        if (updated > 0) {
            return approvals.findByIdAndTenantId(approval.id(), approval.tenantId()).orElse(approval);
        }
        // The decision was deliberately committed before the node call. The
        // entity instance loaded for that decision is therefore detached (and
        // can carry the pre-decision @Version). Reload the row before recording
        // the result so a normal successful execution is not lost as a stale
        // merge after the long-running node call.
        NodeToolApprovalEntity target = approvals.findByIdAndTenantId(
                approval.id(), approval.tenantId()).orElse(approval);
        target.recordExecution(execution.status(), resultJson, execution.errorMessage(), now);
        try {
            return approvals.saveAndFlush(target);
        } catch (OptimisticLockingFailureException conflict) {
            NodeToolApprovalEntity latest = approvals.findByIdAndTenantId(
                    approval.id(),
                    approval.tenantId()).orElse(approval);
            latest.recordExecution(execution.status(), resultJson, execution.errorMessage(), Instant.now());
            try {
                return approvals.saveAndFlush(latest);
            } catch (OptimisticLockingFailureException ignored) {
                // The cancellation transaction has already committed its
                // terminal state; the invocation ledger still contains the
                // authoritative execution result.
                return latest;
            }
        }
    }

    /** 支持单文件写入和跨文件 batch patch，同时只输出安全的工作区相对路径。 */
    private List<String> evidenceFilePaths(NodeToolInvocationEntity invocation) {
        Map<String, Object> arguments = readJsonMap(invocation.argumentsJson());
        if ("fs.apply_patch_batch".equals(invocation.toolName())) {
            Object rawChanges = arguments.get("changes");
            if (rawChanges instanceof List<?> changes) {
                List<String> paths = new ArrayList<>();
                for (Object rawChange : changes) {
                    if (rawChange instanceof Map<?, ?> change) {
                        Object rawPath = change.get("path");
                        if (rawPath != null) {
                            evidenceFilePath(rawPath.toString()).ifPresent(paths::add);
                        }
                    }
                }
                return paths;
            }
            return List.of();
        }
        return evidenceFilePath(arguments.get("path") == null ? null : arguments.get("path").toString())
                .stream().toList();
    }

    private java.util.Optional<String> evidenceFilePath(String path) {
        if (path == null || path.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            String trimmed = path.trim();
            String normalized = Path.of(trimmed).normalize().toString().replace('\\', '/');
            // Path.isAbsolute() follows the host OS. A Windows audit record can
            // therefore look relative when the backend runs on Linux; reject
            // drive-letter and UNC forms explicitly before exposing the path.
            boolean machineLocalPath = normalized.matches("^[A-Za-z]:($|/.*)")
                    || normalized.startsWith("//");
            if (Path.of(trimmed).isAbsolute()
                    || machineLocalPath
                    || normalized.isBlank()
                    || ".".equals(normalized)
                    || "..".equals(normalized)
                    || normalized.startsWith("../")) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(normalized);
        } catch (InvalidPathException ex) {
            return java.util.Optional.empty();
        }
    }

    private void recordApprovalExecution(NodeToolApprovalEntity approval, NodeToolCallResult execution) {
        if (approval.runId() == null || approval.runId().isBlank()
                || approval.toolCallId() == null || approval.toolCallId().isBlank()) {
            return;
        }
        java.util.Optional<NodeToolInvocationEntity> linked = approval.invocationId() == null
                ? invocations.findFirstByTenantIdAndRunIdAndToolCallIdOrderByCreatedAtDesc(
                        approval.tenantId(), approval.runId(), approval.toolCallId())
                : invocations.findByIdAndNodeId(approval.invocationId(), approval.nodeId());
        linked.ifPresent(invocation -> {
            if ("SUCCEEDED".equalsIgnoreCase(execution.status())) {
                String resultJson = toJson(execution.result());
                invocation.succeed(resultJson, sha256(resultJson), Instant.now());
            } else if ("UNKNOWN".equalsIgnoreCase(execution.status())) {
                invocation.unknown(execution.errorMessage(), Instant.now());
            } else {
                invocation.fail(NodeToolInvocationStatus.FAILED, execution.errorMessage(), Instant.now());
            }
            invocations.save(invocation);
        });
    }

    private NodeToolCallResult executeTool(
            String nodeId,
            String toolName,
            CallNodeToolCommand command,
            ActorContext actor,
            boolean bypassApproval,
            String executionSessionId) {
        return executeTool(nodeId, toolName, command, actor, bypassApproval, executionSessionId, null);
    }

    private NodeToolCallResult executeTool(
            String nodeId,
            String toolName,
            CallNodeToolCommand command,
            ActorContext actor,
            boolean bypassApproval,
            String executionSessionId,
            NodeToolInvocationEntity invocation) {
        command = prepareCommand(toolName, command);
        NodeConnectionEntity node = requireNode(nodeId, actor);
        NodeToolEntity tool = tools.findByTenantIdAndNodeIdAndName(actor.tenantId(), nodeId, toolName)
                .orElseThrow(() -> new IllegalArgumentException("Node tool not found: " + toolName));
        if (!tool.enabled()) {
            throw new IllegalArgumentException("Node tool is disabled: " + toolName);
        }
        // 所有入口（直接调用、编码运行、审批恢复）都在服务端经过同一套规则。
        // 客户端永远不会自行决定某个桌面或系统操作是否可以执行。
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
        node = requireReadyNode(nodeId, actor, node);
        int timeoutSeconds = timeoutSeconds(command);
        if (invocation != null) {
            Instant now = Instant.now();
            Instant deadline = now.plusSeconds(timeoutSeconds);
            String argumentsJson = toJson(command == null ? Map.of() : command.arguments());
            String argumentsDigest = sha256(argumentsJson);
            String policyRevision = "policy_" + (node.capabilityRevision() == null
                    ? "default"
                    : node.capabilityRevision());
            invocation.dispatch(
                    Math.max(1, invocation.dispatchAttempt() + 1),
                    deadline,
                    argumentsDigest,
                    invocation.id(),
                    policyRevision,
                    now);
            invocations.saveAndFlush(invocation);
            return sessions.invoke(nodeId, new NodeInvocationDispatch(
                    invocation.id(),
                    invocation.runId(),
                    invocation.toolCallId(),
                    toolName,
                    command == null ? Map.of() : command.arguments(),
                    "workspace-default",
                    executionSessionId,
                    deadline,
                    policyRevision,
                    argumentsDigest,
                    invocation.dispatchAttempt(),
                    invocation.id(),
                    "trace_" + invocation.id()), Duration.ofSeconds(timeoutSeconds));
        }
        if (executionSessionId == null || executionSessionId.isBlank()) {
            return sessions.invoke(
                    nodeId,
                    toolName,
                    command == null ? null : command.arguments(),
                    Duration.ofSeconds(timeoutSeconds));
        }
        return sessions.invoke(
                nodeId,
                toolName,
                command == null ? null : command.arguments(),
                Duration.ofSeconds(timeoutSeconds),
                executionSessionId);
    }

    private CallNodeToolCommand prepareCommand(String toolName, CallNodeToolCommand command) {
        Map<String, Object> arguments = requestPolicy.prepare(
                toolName,
                command == null ? null : command.arguments());
        return new CallNodeToolCommand(arguments, command == null ? null : command.timeoutSeconds());
    }

    private NodeConnectionEntity requireReadyNode(String nodeId, ActorContext actor, NodeConnectionEntity current) {
        if (readyForDispatch(nodeId, current)) {
            return current;
        }
        sessions.awaitConnected(nodeId, NODE_RECONNECT_GRACE);
        NodeConnectionEntity refreshed = requireNode(nodeId, actor);
        if (readyForDispatch(nodeId, refreshed)) {
            return refreshed;
        }
        throw new IllegalArgumentException("Node is not online or enabled: " + nodeId);
    }

    private boolean readyForDispatch(String nodeId, NodeConnectionEntity node) {
        return node.enabled() && node.status() == NodeStatus.ONLINE && sessions.isConnected(nodeId);
    }

    private static void requireApprovalRole(ActorContext actor, String requiredRole) {
        String role = blankToEmpty(requiredRole);
        if (role.isBlank()) {
            role = TOOL_APPROVER_ROLE;
        }
        if (!actor.roles().contains(role) && !actor.roles().contains("LOCAL_USER")) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Actor does not have the required node approval role: " + role);
        }
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private void linkApprovalToRun(NodeToolCallResult result, String runId, String toolCallId, ActorContext actor) {
        Object approvalId = result.result() == null ? null : result.result().get("approvalId");
        if (approvalId == null || approvalId.toString().isBlank()) {
            throw new IllegalStateException("Approval-required tool result did not contain an approval ID.");
        }
        NodeToolApprovalEntity approval = approvals.findByIdAndTenantId(approvalId.toString(), actor.tenantId())
                .orElseThrow(() -> new IllegalStateException("Created node tool approval was not found: " + approvalId));
        approval.linkToRun(runId, toolCallId);
        approvals.save(approval);
    }

    private NodeToolCallResult stopManagedProcessForRun(String runId, ManagedProcessTarget target, ActorContext actor) {
        Instant now = Instant.now();
        String stopToolName = target.stopToolName();
        NodeToolInvocationEntity invocation = invocations.save(new NodeToolInvocationEntity(
                "nodeinv_" + UUID.randomUUID(),
                actor.tenantId(),
                runId,
                "cleanup_" + target.processId(),
                target.nodeId(),
                stopToolName,
                toJson(Map.of("processId", target.processId())),
                now));
        invocation.start(now);
        invocations.save(invocation);
        try {
            NodeToolCallResult result = executeTool(
                    target.nodeId(),
                    stopToolName,
                    new CallNodeToolCommand(Map.of("processId", target.processId()), 30),
                    actor,
                    true,
                    runId);
            if ("SUCCEEDED".equalsIgnoreCase(result.status())) {
                invocation.succeed(toJson(result.result()), Instant.now());
            } else {
                invocation.fail(NodeToolInvocationStatus.FAILED, result.errorMessage(), Instant.now());
            }
            invocations.save(invocation);
            return result;
        } catch (Exception ex) {
            invocation.fail(NodeToolInvocationStatus.FAILED, ex.getMessage(), Instant.now());
            invocations.save(invocation);
            return new NodeToolCallResult(null, target.nodeId(), stopToolName, "FAILED", null, ex.getMessage());
        }
    }

    private java.util.Optional<ManagedProcessTarget> managedProcessTarget(NodeToolInvocationEntity invocation) {
        Map<String, Object> result = readJsonMap(invocation.resultJson());
        Object processId = result.get("processId");
        if (processId == null || processId.toString().isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new ManagedProcessTarget(
                invocation.nodeId(), processId.toString(), stopToolNameForStart(invocation.toolName())));
    }

    private java.util.Optional<ManagedProcessTarget> managedProcessTarget(NodeToolApprovalEntity approval) {
        Map<String, Object> result = readJsonMap(approval.resultJson());
        Object processId = result.get("processId");
        if (processId == null || processId.toString().isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new ManagedProcessTarget(
                approval.nodeId(), processId.toString(), stopToolNameForStart(approval.toolName())));
    }

    private boolean wasStopped(List<NodeToolInvocationEntity> history, String processId) {
        return history.stream()
                .filter(invocation -> invocation.status() == NodeToolInvocationStatus.SUCCEEDED)
                .filter(invocation -> isManagedProcessStop(invocation.toolName()))
                .map(invocation -> readJsonMap(invocation.argumentsJson()).get("processId"))
                .filter(java.util.Objects::nonNull)
                .anyMatch(processId::equals);
    }

    private static boolean isManagedProcessStart(String toolName) {
        return "process.start".equals(toolName) || "system.process.start".equals(toolName);
    }

    private static boolean isManagedProcessWaitHttp(String toolName) {
        return "process.wait_http".equals(toolName) || "system.process.wait_http".equals(toolName);
    }

    private static boolean isManagedProcessStop(String toolName) {
        return "process.stop".equals(toolName) || "system.process.stop".equals(toolName);
    }

    private static String stopToolNameForStart(String startToolName) {
        return "system.process.start".equals(startToolName) ? "system.process.stop" : "process.stop";
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

    private Map<String, Object> readJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException ex) {
            return Map.of();
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
     * WebSocket 握手校验：nodeId 和 nodeSecret 只能来自握手请求头，不能出现在 URL query。
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
        Instant now = Instant.now();
        // 每次成功认证都递增 token。即使旧 WebSocket 在网络抖动后继续回包，也不能覆盖新会话。
        node.advanceFencingToken(now);
        node.markOnline(now);
        return nodes.save(node);
    }

    /** 把审批恢复和第一次审计调用绑死，避免审批通过后再创建新的传输调用。 */
    private void linkApprovalToInvocation(NodeToolCallResult result, String invocationId, ActorContext actor) {
        Object approvalId = result.result() == null ? null : result.result().get("approvalId");
        if (approvalId == null || invocationId == null || invocationId.isBlank()) {
            return;
        }
        approvals.findByIdAndTenantId(approvalId.toString(), actor.tenantId()).ifPresent(approval -> {
            approval.linkInvocation(invocationId);
            approvals.save(approval);
        });
    }

    /**
     * 节点已经把调用写入本地 journal，但还不能据此宣称副作用执行成功。
     *
     * <p>中间态同样必须绑定调度时的工具、参数摘要和 attempt。只按 invocationId 更新会让
     * 迟到或伪造的状态帧推进错误调用，破坏断线后的对账边界。
     */
    @Transactional
    public void acceptInvocation(
            String nodeId, String invocationId, String toolName, String argumentsDigest, int attempt) {
        findMatchingInvocation(nodeId, invocationId, toolName, argumentsDigest, attempt).ifPresent(invocation -> {
            invocation.accept(Instant.now());
            invocations.save(invocation);
        });
    }

    /** 进度消息只推进服务端状态，不把节点日志原文保存进控制面审计字段。 */
    @Transactional
    public void startInvocation(
            String nodeId, String invocationId, String toolName, String argumentsDigest, int attempt) {
        findMatchingInvocation(nodeId, invocationId, toolName, argumentsDigest, attempt).ifPresent(invocation -> {
            invocation.start(Instant.now());
            invocations.save(invocation);
        });
    }

    private Optional<NodeToolInvocationEntity> findMatchingInvocation(
            String nodeId, String invocationId, String toolName, String argumentsDigest, int attempt) {
        return invocations.findByIdAndNodeId(invocationId, nodeId)
                .filter(invocation -> java.util.Objects.equals(invocation.toolName(), toolName))
                .filter(invocation -> java.util.Objects.equals(invocation.argumentsDigest(), argumentsDigest))
                .filter(invocation -> invocation.dispatchAttempt() == attempt);
    }

    /**
     * 将“没有对应内存等待 Future”的节点终态与持久调用记录对账。
     * 这是控制面或连接重启后的恢复路径，因此必须重新校验工具名、参数摘要和 attempt，
     * 不能仅凭 invocationId 接受一个晚到或错配的结果。
     */
    @Transactional
    public boolean reconcileInvocationResult(
            NodeToolCallResult result, String toolName, String argumentsDigest, int attempt) {
        if (result == null || result.invocationId() == null || result.invocationId().isBlank()) {
            return false;
        }
        return invocations.findByIdAndNodeId(result.invocationId(), result.nodeId()).map(invocation -> {
            if (!java.util.Objects.equals(invocation.toolName(), toolName)
                    || !java.util.Objects.equals(invocation.argumentsDigest(), argumentsDigest)
                    || invocation.dispatchAttempt() != attempt) {
                return false;
            }
            // 已确认的确定终态不允许被网络迟到帧覆盖；UNKNOWN 则允许被同一调用的对账结果收敛。
            if (invocation.terminal() && invocation.status() != NodeToolInvocationStatus.UNKNOWN) {
                return false;
            }
            Instant now = Instant.now();
            if ("SUCCEEDED".equalsIgnoreCase(result.status())) {
                String resultJson = toJson(result.result());
                invocation.succeed(resultJson, sha256(resultJson), now);
            } else if ("CANCELLED".equalsIgnoreCase(result.status())) {
                invocation.fail(NodeToolInvocationStatus.CANCELLED, result.errorMessage(), now);
            } else if ("TIMED_OUT".equalsIgnoreCase(result.status())) {
                invocation.fail(NodeToolInvocationStatus.TIMED_OUT, result.errorMessage(), now);
            } else if ("UNKNOWN".equalsIgnoreCase(result.status())) {
                invocation.unknown(result.errorMessage(), now);
            } else {
                invocation.fail(NodeToolInvocationStatus.FAILED, result.errorMessage(), now);
            }
            invocations.save(invocation);
            return true;
        }).orElse(false);
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

    /**
     * 返回节点重连后需要向本地 journal 查询的调用摘要。
     * 只返回 invocationId、工具名、参数摘要和 attempt；服务端不会据此自动重放调用。
     */
    @Transactional(readOnly = true)
    public List<NodeInvocationReconciliation> reconciliationRequests(String nodeId, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        List<NodeToolInvocationStatus> statuses = List.of(
                NodeToolInvocationStatus.DISPATCHED,
                NodeToolInvocationStatus.ACCEPTED,
                NodeToolInvocationStatus.RUNNING,
                NodeToolInvocationStatus.UNKNOWN);
        return invocations.findByNodeIdAndStatusInOrderByCreatedAtAsc(
                        nodeId, statuses, org.springframework.data.domain.PageRequest.of(0, boundedLimit))
                .stream()
                .map(invocation -> new NodeInvocationReconciliation(
                        invocation.id(), invocation.toolName(), invocation.argumentsDigest(), invocation.dispatchAttempt()))
                .toList();
    }

    @Transactional
    public void markOffline(String nodeId) {
        nodes.findById(nodeId).ifPresent(node -> {
            node.markOffline(Instant.now());
            nodes.save(node);
        });
    }

    public record NodeInvocationReconciliation(
            String invocationId, String toolName, String argumentsDigest, int attempt) {
    }

    @Transactional
    public List<NodeToolView> saveCapabilities(String nodeId, List<NodeCapabilityPayload> capabilities) {
        return saveCapabilities(nodeId, null, Map.of(), java.util.Set.of(), capabilities);
    }

    @Transactional
    public List<NodeToolView> saveCapabilities(
            String nodeId,
            String capabilityRevision,
            Map<String, String> runtimeVersions,
            java.util.Set<String> features,
            List<NodeCapabilityPayload> capabilities) {
        NodeConnectionEntity node = nodes.findById(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + nodeId));
        if (capabilities == null) {
            return listToolsForNodeEntity(node);
        }
        Instant now = Instant.now();
        node.updateCapabilitySnapshot(capabilityRevision, runtimeVersions, features, now);
        nodes.save(node);
        Set<String> reportedNames = capabilities.stream()
                .filter(java.util.Objects::nonNull)
                .map(NodeCapabilityPayload::name)
                .filter(name -> name != null && !name.isBlank())
                .map(String::trim)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (NodeToolEntity existing : tools.findByTenantIdAndNodeIdOrderByNameAsc(node.tenantId(), node.id())) {
            if (!reportedNames.contains(existing.name())) {
                tools.delete(existing);
            }
        }
        for (NodeCapabilityPayload capability : capabilities) {
            if (capability == null) {
                continue;
            }
            if (capability.name() == null || capability.name().isBlank()) {
                continue;
            }
            String name = capability.name().trim();
            // 风险、审批和默认启用状态只由服务端策略目录产生。不能把节点上报的
            // 元数据当成权限来源，否则被篡改或过期的客户端可能扩大权限。
            NodeToolPolicy policy = node.kind() == NodeKind.MANAGED_LOCAL
                    ? NodeToolPolicyCatalog.managedLocalPolicyFor(name)
                    : NodeToolPolicyCatalog.policyFor(name);
            String schemaJson = toJson(capability.inputSchema());
            NodeToolEntity tool = tools.findByTenantIdAndNodeIdAndName(node.tenantId(), node.id(), name)
                    .orElseGet(() -> new NodeToolEntity(
                            node.tenantId(),
                            node.id(),
                            name,
                            capability.description(),
                            policy.riskLevel(),
                            policy.enabledByDefault(),
                            policy.requiresApproval(),
                            schemaJson,
                            now));
            // Reconnection reports capabilities again. Preserve policy set through the management API.
            tool.refreshCapability(
                    capability.description(), policy.riskLevel(), schemaJson, capability.version(), now);
            // A registered node is the user's chosen execution target. Re-apply the product's
            // full-access policy on every capability report so upgrades repair stale disabled or
            // approval-required flags instead of silently exposing only a subset of its tools.
            tool.updatePolicy(true, false, now);
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

    private record ManagedProcessTarget(String nodeId, String processId, String stopToolName) {
    }
}
