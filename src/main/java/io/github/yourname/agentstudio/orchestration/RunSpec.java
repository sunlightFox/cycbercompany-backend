package io.github.yourname.agentstudio.orchestration;

import io.github.yourname.agentstudio.security.ActorContext;
import io.github.yourname.agentstudio.skill.SkillRunBinding;
import io.github.yourname.agentstudio.skill.SkillAnalysis;
import io.github.yourname.agentstudio.skill.CompatibilityReport;
import io.github.yourname.agentstudio.tool.ApprovalMode;
import io.github.yourname.agentstudio.tool.ResolvedToolBinding;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一次 Run 在进入队列前固定的完整执行说明书。
 *
 * <p>数据库中的 RunSpec 才是 worker 的输入事实，HTTP 请求对象和 Java 闭包都不是。管理员后来
 * 修改 Agent、Skill、MCP 或节点能力时，已经创建的 Run 仍使用这里保存的原始快照。
 */
public record RunSpec(
        int version,
        String conversationId,
        String userText,
        String modelProfileId,
        String modelCapabilityRevision,
        String agentId,
        String agentSystemPrompt,
        String agentPromptDigest,
        String agentToolAllowList,
        List<SkillRunBinding> skillBindings,
        String skillSnapshotDigest,
        String skillInstructionsDigest,
        List<SkillAnalysis> skillAnalyses,
        CompatibilityReport compatibilityReport,
        List<String> knowledgeBaseIds,
        List<String> mcpConnectionIds,
        List<String> requestedToolNames,
        List<ResolvedToolBinding> toolBindings,
        String nodeId,
        RunExecutionMode executionMode,
        String workingDirectory,
        List<String> attachmentIds,
        String attachmentContext,
        String capabilityRevision,
        String policyRevision,
        String approvalMode,
        String tenantId,
        String userId,
        Set<String> actorRoles,
        Set<String> actorScopes) {

    // executionMode is optional on the wire so existing version-1 RunSpecs remain readable.
    public static final int CURRENT_VERSION = 1;

    public RunSpec {
        skillBindings = copy(skillBindings);
        skillAnalyses = copy(skillAnalyses);
        knowledgeBaseIds = copy(knowledgeBaseIds);
        mcpConnectionIds = copy(mcpConnectionIds);
        requestedToolNames = copy(requestedToolNames);
        toolBindings = copy(toolBindings);
        attachmentIds = copy(attachmentIds);
        actorRoles = copy(actorRoles);
        actorScopes = copy(actorScopes);
        attachmentContext = attachmentContext == null ? "" : attachmentContext;
        agentToolAllowList = agentToolAllowList == null ? "" : agentToolAllowList;
        approvalMode = ApprovalMode.from(approvalMode).wireValue();
        executionMode = executionMode == null
                ? RunExecutionMode.fromPersisted(nodeId, userText, workingDirectory, requestedToolNames)
                : executionMode;
    }

    public ActorContext actor() {
        return new ActorContext(tenantId, userId, actorRoles, actorScopes);
    }

    public CreateRunCommand commandSnapshot() {
        return new CreateRunCommand(
                conversationId,
                userText,
                modelProfileId,
                agentId,
                knowledgeBaseIds,
                skillBindings.stream().map(SkillRunBinding::skillId).toList(),
                mcpConnectionIds,
                requestedToolNames,
                nodeId,
                workingDirectory,
                attachmentIds,
                List.of(),
                approvalMode);
    }

    private static <T> List<T> copy(List<T> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<T> sanitized = new ArrayList<>();
        for (T value : values) {
            if (value != null) {
                sanitized.add(value);
            }
        }
        return sanitized.isEmpty() ? List.of() : List.copyOf(sanitized);
    }

    private static <T> Set<T> copy(Set<T> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<T> sanitized = new LinkedHashSet<>();
        for (T value : values) {
            if (value != null) {
                sanitized.add(value);
            }
        }
        return sanitized.isEmpty() ? Set.of() : Set.copyOf(sanitized);
    }
}
