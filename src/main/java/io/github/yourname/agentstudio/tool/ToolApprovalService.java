package io.github.yourname.agentstudio.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.security.ActorContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 通用工具审批的持久化、精确参数绑定和一次性决策逻辑。 */
@Service
public class ToolApprovalService {

    private static final Duration APPROVAL_TTL = Duration.ofMinutes(10);
    private static final Set<String> APPROVER_ROLES = Set.of("ADMIN", "TOOL_APPROVER", "LOCAL_USER");

    private final ToolApprovalRepository repository;
    private final ObjectMapper objectMapper;

    public ToolApprovalService(ToolApprovalRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ToolApprovalView request(ToolInvocationRequest request) {
        return repository.findByTenantIdAndRunIdAndToolCallId(
                        request.actor().tenantId(), request.runId(), request.toolCallId())
                .map(ToolApprovalView::from)
                .orElseGet(() -> create(request));
    }

    private ToolApprovalView create(ToolInvocationRequest request) {
        Instant now = Instant.now();
        String bindingJson = json(request.binding());
        String argumentsJson = json(request.arguments());
        ToolApprovalEntity entity = new ToolApprovalEntity(
                "toolapproval_" + UUID.randomUUID(),
                request.actor().tenantId(),
                request.actor().userId(),
                request.runId(),
                request.toolCallId(),
                request.binding(),
                bindingJson,
                argumentsJson,
                sha256(argumentsJson),
                request.timeoutSeconds(),
                request.workspaceScope().relativePath(),
                now,
                now.plus(APPROVAL_TTL));
        return ToolApprovalView.from(repository.save(entity));
    }

    @Transactional(readOnly = true)
    public List<ToolApprovalView> list(ActorContext actor) {
        return repository.findByTenantIdOrderByRequestedAtDesc(actor.tenantId()).stream()
                .map(ToolApprovalView::from)
                .toList();
    }

    @Transactional
    ApprovalExecution approve(String approvalId, DecideToolApprovalCommand command, ActorContext actor) {
        requireApprover(actor);
        ToolApprovalEntity entity = repository.findByIdAndTenantId(approvalId, actor.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Tool approval not found: " + approvalId));
        boolean approved = command != null && Boolean.TRUE.equals(command.approved());
        if (approved
                && entity.requesterId().equals(actor.userId())
                && !actor.roles().contains("LOCAL_USER")) {
            throw new IllegalArgumentException("The requester cannot approve their own tool call.");
        }
        entity.decide(approved, actor.userId(), Instant.now());
        repository.save(entity);
        ResolvedToolBinding binding = read(entity.bindingJson(), ResolvedToolBinding.class);
        Map<String, Object> arguments = read(
                entity.argumentsJson(), new TypeReference<Map<String, Object>>() { });
        ToolInvocationRequest invocation = new ToolInvocationRequest(
                entity.runId(), entity.toolCallId(), binding, arguments, entity.timeoutSeconds(),
                CodingWorkspaceScope.from(entity.workingDirectory()), actor, entity.id());
        return new ApprovalExecution(entity, invocation, approved);
    }

    @Transactional
    ToolApprovalDecisionView complete(ApprovalExecution approval, ToolProviderResult result) {
        ToolApprovalEntity entity = repository.findById(approval.entity().id()).orElseThrow();
        entity.complete(result);
        entity.resultJson(json(result));
        repository.save(entity);
        return new ToolApprovalDecisionView(ToolApprovalView.from(entity), result);
    }

    ToolApprovalDecisionView rejected(ApprovalExecution approval) {
        return new ToolApprovalDecisionView(ToolApprovalView.from(approval.entity()), null);
    }

    private static void requireApprover(ActorContext actor) {
        if (actor.roles() == null || actor.roles().stream().noneMatch(APPROVER_ROLES::contains)) {
            throw new IllegalArgumentException("Tool approval requires ADMIN, TOOL_APPROVER, or LOCAL_USER role.");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to serialize tool approval payload.", ex);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to restore tool approval payload.", ex);
        }
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to restore tool approval arguments.", ex);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }

    record ApprovalExecution(ToolApprovalEntity entity, ToolInvocationRequest invocation, boolean approved) {
    }
}
