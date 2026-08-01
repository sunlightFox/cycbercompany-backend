package io.github.yourname.agentstudio.node;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.security.ActorContext;
import io.github.yourname.agentstudio.tool.RegisteredTool;
import io.github.yourname.agentstudio.tool.RiskLevel;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
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
    private static final int TOOL_APPROVAL_TTL_SECONDS = 5 * 60;
    private static final String TOOL_APPROVER_ROLE = "NODE_TOOL_APPROVER";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final NodeConnectionRepository nodes;
    private final NodeRegistrationTokenRepository tokens;
    private final NodeToolRepository tools;
    private final NodeToolInvocationRepository invocations;
    private final NodeToolApprovalRepository approvals;
    private final NodeSessionRegistry sessions;
    private final ObjectMapper objectMapper;
    private final NodeToolRequestPolicy requestPolicy;

    @Autowired
    public NodeService(
            NodeConnectionRepository nodes,
            NodeRegistrationTokenRepository tokens,
            NodeToolRepository tools,
            NodeToolInvocationRepository invocations,
            NodeToolApprovalRepository approvals,
            NodeSessionRegistry sessions,
            ObjectMapper objectMapper,
            NodeToolRequestPolicy requestPolicy) {
        this.nodes = nodes;
        this.tokens = tokens;
        this.tools = tools;
        this.invocations = invocations;
        this.approvals = approvals;
        this.sessions = sessions;
        this.objectMapper = objectMapper;
        this.requestPolicy = requestPolicy;
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
    public RegisterNodeResult register(RegisterNodeCommand command) {
        Instant now = Instant.now();
        NodeRegistrationTokenEntity token = tokens.findByTokenHash(sha256(command.registrationToken()))
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
                token.tenantId(),
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

    @Transactional
    public NodeToolView updateTool(String nodeId, String toolName, UpdateNodeToolCommand command, ActorContext actor) {
        requireNode(nodeId, actor);
        NodeToolEntity tool = tools.findByTenantIdAndNodeIdAndName(actor.tenantId(), nodeId, toolName)
                .orElseThrow(() -> new IllegalArgumentException("Node tool not found: " + toolName));
        tool.updatePolicy(command == null ? null : command.enabled(), command == null ? null : command.requiresApproval(), Instant.now());
        return NodeToolView.from(tools.save(tool));
    }

    @Transactional(noRollbackFor = Exception.class)
    public NodeToolCallResult callTool(String nodeId, String toolName, CallNodeToolCommand command, ActorContext actor) {
        return executeAuditedTool(
                null,
                "api_" + UUID.randomUUID(),
                nodeId,
                toolName,
                prepareCommand(toolName, command),
                actor);
    }

    @Transactional(noRollbackFor = Exception.class)
    public NodeToolCallResult callToolForRun(
            String runId,
            String toolCallId,
            String nodeId,
            String toolName,
            CallNodeToolCommand command,
            ActorContext actor) {
        return executeAuditedTool(
                runId,
                toolCallId,
                nodeId,
                toolName,
                prepareCommand(toolName, command),
                actor);
    }

    private NodeToolCallResult executeAuditedTool(
            String runId,
            String toolCallId,
            String nodeId,
            String toolName,
            CallNodeToolCommand preparedCommand,
            ActorContext actor) {
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
        invocation.start(now);
        invocations.save(invocation);
        try {
            NodeToolCallResult result = executeTool(nodeId, toolName, preparedCommand, actor, false, runId);
            if ("SUCCEEDED".equalsIgnoreCase(result.status())) {
                invocation.succeed(toJson(result.result()), Instant.now());
            } else if ("APPROVAL_REQUIRED".equalsIgnoreCase(result.status())) {
                if (runId != null && !runId.isBlank()) {
                    linkApprovalToRun(result, runId, toolCallId, actor);
                }
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

    @Transactional(readOnly = true)
    public CodingRunEvidenceView codingEvidence(String runId, ActorContext actor) {
        List<NodeToolInvocationEntity> history = invocations.findByTenantIdAndRunIdOrderByCreatedAtAsc(actor.tenantId(), runId);

        // 这里的摘要会直接返回给用户，因此只保留“项目内相对路径”。
        // 调用记录来自数据库，不能假定旧数据或手工修复过的数据一定合法；
        // 绝对路径、包含 ".." 的路径都不应该通过这个接口泄露出去。
        List<String> changedFiles = history.stream()
                .filter(invocation -> "fs.write".equals(invocation.toolName()) || "fs.apply_patch".equals(invocation.toolName()))
                .filter(invocation -> invocation.status() == NodeToolInvocationStatus.SUCCEEDED)
                .map(this::evidenceFilePath)
                .flatMap(java.util.Optional::stream)
                .distinct()
                .toList();

        // shell.run 和 browser.* 是“已经做过验证”的证据。这里刻意只返回工具名称，
        // 不返回命令参数或标准输出，以免日志中的敏感信息被这个摘要接口暴露。
        List<String> verificationTools = history.stream()
                .filter(invocation -> invocation.status() == NodeToolInvocationStatus.SUCCEEDED)
                .map(NodeToolInvocationEntity::toolName)
                .filter(name -> "shell.run".equals(name) || name.startsWith("browser."))
                .distinct()
                .toList();
        // 命令原文留在受权限保护的审计表中。交付门禁只需要知道它是不是可解释的测试、构建、静态检查
        // 或 HTTP 联调，因此这里输出有限的类别，既能拒绝 `echo done`，也不会把命令参数暴露给页面。
        List<String> commandVerifications = history.stream()
                .filter(invocation -> "shell.run".equals(invocation.toolName()))
                .filter(invocation -> invocation.status() == NodeToolInvocationStatus.SUCCEEDED)
                .map(this::commandVerificationCategory)
                .flatMap(java.util.Optional::stream)
                .distinct()
                .toList();
        List<String> failedTools = history.stream()
                .filter(invocation -> invocation.status() == NodeToolInvocationStatus.FAILED)
                .map(NodeToolInvocationEntity::toolName)
                .distinct()
                .toList();
        // Trace 的绝对路径仅保留在受控审计调用记录中。交付摘要只显示经过格式校验的文件名，
        // 既能证明可回放证据存在，也不会暴露节点用户目录或临时目录结构。
        List<String> browserTraceArtifacts = history.stream()
                .filter(invocation -> "browser.trace.stop".equals(invocation.toolName()))
                .filter(invocation -> invocation.status() == NodeToolInvocationStatus.SUCCEEDED)
                .map(this::traceArtifactName)
                .flatMap(java.util.Optional::stream)
                .distinct()
                .toList();
        boolean browserVerified = verificationTools.stream().anyMatch(name -> name.startsWith("browser."));
        return new CodingRunEvidenceView(
                runId,
                history.size(),
                changedFiles,
                verificationTools,
                commandVerifications,
                browserTraceArtifacts,
                browserVerified,
                failedTools);
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

        boolean attemptedBrowser = evidence.browserVerified()
                || evidence.failedTools().stream().anyMatch(name -> name.startsWith("browser."));
        if (attemptedBrowser) {
            checks.add(qualityCheck(
                    "browser-verification",
                    10,
                    evidence.browserVerified(),
                    "已尝试浏览器验证时，至少应有一次浏览器工具成功。"));
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
                .filter(invocation -> "process.start".equals(invocation.toolName()))
                .map(this::managedProcessTarget)
                .flatMap(java.util.Optional::stream)
                .toList();
        List<ManagedProcessTarget> approvalTargets = approvals.findByTenantIdAndRunId(actor.tenantId(), runId).stream()
                .filter(approval -> approval.status() == NodeToolApprovalStatus.APPROVED)
                .filter(approval -> "SUCCEEDED".equalsIgnoreCase(approval.executionStatus()))
                .filter(approval -> "process.start".equals(approval.toolName()))
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
    @Transactional(noRollbackFor = Exception.class)
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
            execution = executeTool(
                    approval.nodeId(),
                    approval.toolName(),
                    new CallNodeToolCommand(readArguments(approval.argumentsJson()), approval.timeoutSeconds()),
                    actor,
                    true,
                    approval.runId());
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
            boolean bypassApproval,
            String executionSessionId) {
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
        if (!node.enabled() || node.status() != NodeStatus.ONLINE || !sessions.isConnected(nodeId)) {
            throw new IllegalArgumentException("Node is not online or enabled: " + nodeId);
        }
        int timeoutSeconds = timeoutSeconds(command);
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
        NodeToolInvocationEntity invocation = invocations.save(new NodeToolInvocationEntity(
                "nodeinv_" + UUID.randomUUID(),
                actor.tenantId(),
                runId,
                "cleanup_" + target.processId(),
                target.nodeId(),
                "process.stop",
                toJson(Map.of("processId", target.processId())),
                now));
        invocation.start(now);
        invocations.save(invocation);
        try {
            NodeToolCallResult result = executeTool(
                    target.nodeId(),
                    "process.stop",
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
            return new NodeToolCallResult(null, target.nodeId(), "process.stop", "FAILED", null, ex.getMessage());
        }
    }

    private java.util.Optional<ManagedProcessTarget> managedProcessTarget(NodeToolInvocationEntity invocation) {
        Map<String, Object> result = readJsonMap(invocation.resultJson());
        Object processId = result.get("processId");
        if (processId == null || processId.toString().isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new ManagedProcessTarget(invocation.nodeId(), processId.toString()));
    }

    private java.util.Optional<ManagedProcessTarget> managedProcessTarget(NodeToolApprovalEntity approval) {
        Map<String, Object> result = readJsonMap(approval.resultJson());
        Object processId = result.get("processId");
        if (processId == null || processId.toString().isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new ManagedProcessTarget(approval.nodeId(), processId.toString()));
    }

    private boolean wasStopped(List<NodeToolInvocationEntity> history, String processId) {
        return history.stream()
                .filter(invocation -> invocation.status() == NodeToolInvocationStatus.SUCCEEDED)
                .filter(invocation -> "process.stop".equals(invocation.toolName()))
                .map(invocation -> readJsonMap(invocation.argumentsJson()).get("processId"))
                .filter(java.util.Objects::nonNull)
                .anyMatch(processId::equals);
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
        for (NodeCapabilityPayload capability : capabilities) {
            if (capability.name() == null || capability.name().isBlank()) {
                continue;
            }
            String name = capability.name().trim();
            // 风险、审批和默认启用状态只由服务端策略目录产生。不能把节点上报的
            // 元数据当成权限来源，否则被篡改或过期的客户端可能扩大权限。
            NodeToolPolicy policy = NodeToolPolicyCatalog.policyFor(name);
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

    private record ManagedProcessTarget(String nodeId, String processId) {
    }
}
