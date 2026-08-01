package io.github.yourname.agentstudio.tool;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 工具发现、权限交集和执行路由的唯一入口。
 *
 * <p>Provider 返回事实能力，Router 再应用 Agent 与 Run 的限制。工具集合只会缩小，不会因为
 * Skill、模型或节点自报而扩大。解析结果可以直接写进 RunSpec，恢复时不重新选择 Provider。
 */
@Service
public class ToolRouter {

    private final Map<String, ToolProvider> providers;
    private final ToolApprovalService approvals;

    public ToolRouter(List<ToolProvider> providers) {
        this(providers, null);
    }

    @Autowired
    public ToolRouter(List<ToolProvider> providers, ToolApprovalService approvals) {
        Map<String, ToolProvider> indexed = new LinkedHashMap<>();
        for (ToolProvider provider : providers == null ? List.<ToolProvider>of() : providers) {
            ToolProvider duplicate = indexed.putIfAbsent(provider.providerId(), provider);
            if (duplicate != null) {
                throw new IllegalStateException("Duplicate ToolProvider ID: " + provider.providerId());
            }
        }
        this.providers = Map.copyOf(indexed);
        this.approvals = approvals;
    }

    public List<ResolvedToolBinding> resolve(
            ToolDiscoveryRequest request,
            List<String> requestedTools,
            String agentAllowList) {
        List<String> agentRules = parseRules(agentAllowList);
        if (agentRules.isEmpty()) {
            return List.of();
        }
        List<String> runRules = requestedTools == null ? List.of() : requestedTools.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();

        Map<String, ToolDescriptor> discovered = new LinkedHashMap<>();
        for (ToolProvider provider : providers.values().stream()
                .sorted(Comparator.comparing(ToolProvider::providerId))
                .toList()) {
            for (ToolDescriptor descriptor : provider.discover(request)) {
                if (!provider.providerId().equals(descriptor.providerId())) {
                    throw new IllegalStateException(
                            "ToolProvider returned a descriptor owned by another provider: " + descriptor.bindingId());
                }
                ToolDescriptor duplicate = discovered.putIfAbsent(descriptor.bindingId(), descriptor);
                if (duplicate != null) {
                    throw new IllegalStateException("Duplicate tool binding ID: " + descriptor.bindingId());
                }
            }
        }

        List<ResolvedToolBinding> result = new ArrayList<>();
        Map<String, String> modelNames = new HashMap<>();
        for (ToolDescriptor descriptor : discovered.values()) {
            if (!matchesAny(agentRules, descriptor)) {
                continue;
            }
            if (!runRules.isEmpty() && !matchesAny(runRules, descriptor)) {
                continue;
            }
            String modelName = modelName(descriptor);
            String previous = modelNames.putIfAbsent(modelName, descriptor.bindingId());
            if (previous != null && !previous.equals(descriptor.bindingId())) {
                throw new IllegalStateException("Model tool name collision: " + modelName);
            }
            result.add(ResolvedToolBinding.from(descriptor, modelName));
        }
        return List.copyOf(result);
    }

    public ToolProviderResult invoke(ToolInvocationRequest request) {
        ToolProvider provider = providers.get(request.binding().providerId());
        if (provider == null) {
            throw new IllegalArgumentException("Tool provider is not available: " + request.binding().providerId());
        }
        // P1 先把 MCP 纳入通用审批。Node 的直接 API 仍保留原审批实体，P3 协议迁移时再统一历史入口。
        if ("mcp".equals(request.binding().providerId())
                && request.binding().requiresApproval()
                && (request.approvalId() == null || request.approvalId().isBlank())) {
            if (approvals == null) {
                return new ToolProviderResult(
                        "FAILED", false, Map.of(), "MCP tool approval service is unavailable.", null);
            }
            ToolApprovalView approval = approvals.request(request);
            return new ToolProviderResult(
                    "APPROVAL_REQUIRED", false, Map.of(), "", approval.id());
        }
        return provider.invoke(request);
    }

    public ToolApprovalDecisionView decideApproval(
            String approvalId,
            DecideToolApprovalCommand command,
            io.github.yourname.agentstudio.security.ActorContext actor) {
        if (approvals == null) {
            throw new IllegalStateException("Tool approval service is unavailable.");
        }
        ToolApprovalService.ApprovalExecution approval = approvals.approve(approvalId, command, actor);
        if (!approval.approved()) {
            return approvals.rejected(approval);
        }
        ToolProvider provider = providers.get(approval.invocation().binding().providerId());
        if (provider == null) {
            return approvals.complete(approval, new ToolProviderResult(
                    "FAILED", false, Map.of(), "Tool provider is no longer available.", null));
        }
        return approvals.complete(approval, provider.invoke(approval.invocation()));
    }

    public List<ToolApprovalView> listApprovals(io.github.yourname.agentstudio.security.ActorContext actor) {
        return approvals == null ? List.of() : approvals.list(actor);
    }

    public List<ToolCleanupResult> cleanup(String runId, io.github.yourname.agentstudio.security.ActorContext actor) {
        List<ToolCleanupResult> results = new ArrayList<>();
        for (ToolProvider provider : providers.values()) {
            results.addAll(provider.cleanup(runId, actor));
        }
        return List.copyOf(results);
    }

    private static List<String> parseRules(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(text.split("[,\\s]+"))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private static boolean matchesAny(List<String> rules, ToolDescriptor descriptor) {
        return rules.stream().anyMatch(rule -> matches(rule, descriptor));
    }

    static boolean matches(String rawRule, ToolDescriptor descriptor) {
        String rule = rawRule == null ? "" : rawRule.trim().toLowerCase(Locale.ROOT);
        if (rule.equals("*")) {
            return true;
        }
        List<String> candidates = List.of(
                descriptor.bindingId().toLowerCase(Locale.ROOT),
                descriptor.logicalName().toLowerCase(Locale.ROOT),
                descriptor.providerToolName().toLowerCase(Locale.ROOT),
                descriptor.providerId().toLowerCase(Locale.ROOT) + ":*");
        if (candidates.contains(rule)) {
            return true;
        }
        if (rule.endsWith(".*")) {
            String prefix = rule.substring(0, rule.length() - 1);
            return descriptor.logicalName().toLowerCase(Locale.ROOT).startsWith(prefix)
                    || descriptor.providerToolName().toLowerCase(Locale.ROOT).startsWith(prefix);
        }
        return false;
    }

    private static String modelName(ToolDescriptor descriptor) {
        String readable = descriptor.logicalName().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        if (readable.isBlank()) {
            readable = "tool";
        }
        if (readable.length() > 36) {
            readable = readable.substring(0, 36);
        }
        return "tool_" + readable + "_" + shortDigest(descriptor.bindingId());
    }

    private static String shortDigest(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 is not available in this Java runtime.", ex);
        }
    }
}
