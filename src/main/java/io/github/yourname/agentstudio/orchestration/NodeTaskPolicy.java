package io.github.yourname.agentstudio.orchestration;

import io.github.yourname.agentstudio.tool.ResolvedToolBinding;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Narrows native node tools for task types whose intended side effects are well defined.
 *
 * <p>Desktop organization is deliberately not treated as a generic coding task. It must
 * inspect the target before changing it and must not expose unrelated system controls such as
 * wallpaper updates or arbitrary shell commands to the model.
 */
final class NodeTaskPolicy {

    private static final Set<String> DESKTOP_ORGANIZATION_TOOLS = Set.of(
            "system.desktop.organize.list",
            "system.desktop.organize.mkdir",
            "system.desktop.organize.write",
            "system.desktop.organize.move",
            "system.desktop.organize.delete");

    private static final NodeTaskPolicy UNRESTRICTED = new NodeTaskPolicy(false, false, Set.of(), null);
    private static final NodeTaskPolicy DESKTOP_ORGANIZATION = new NodeTaskPolicy(
            true,
            false,
            DESKTOP_ORGANIZATION_TOOLS,
            "system.desktop.organize.list");

    private final boolean desktopOrganization;
    /** 前后端联调任务的完成条件比普通网页浏览更严格：必须证明真实 API 请求成功。 */
    private final boolean fullStackApiEvidenceRequired;
    private final Set<String> allowedTools;
    private final String requiredFirstTool;

    private NodeTaskPolicy(
            boolean desktopOrganization,
            boolean fullStackApiEvidenceRequired,
            Set<String> allowedTools,
            String requiredFirstTool) {
        this.desktopOrganization = desktopOrganization;
        this.fullStackApiEvidenceRequired = fullStackApiEvidenceRequired;
        this.allowedTools = allowedTools;
        this.requiredFirstTool = requiredFirstTool;
    }

    static NodeTaskPolicy from(CreateRunCommand command) {
        String text = command == null || command.text() == null ? "" : command.text().toLowerCase(Locale.ROOT);
        boolean mentionsDesktop = text.contains("desktop") || text.contains("\u684c\u9762");
        boolean requestsOrganization = text.contains("organize")
                || text.contains("organise")
                || text.contains("tidy")
                || text.contains("clean")
                || text.contains("sort")
                || text.contains("\u6574\u7406")
                || text.contains("\u5206\u7c7b")
                || text.contains("\u5f52\u7c7b")
                || text.contains("\u6e05\u7406");
        boolean requestsDesktopDeletion = text.matches("(?s).*(delete|remove|trash|\u5220\u9664|\u5220\u6389|\u79fb\u9664).*")
                && text.matches("(?s).*[^\\s]+\\.[a-z0-9]{1,16}.*");
        boolean requestsDesktopTextFile = text.matches("(?s).*(create|write|make|\u521b\u5efa|\u65b0\u5efa|\u5199\u5165|\u751f\u6210).*")
                && text.matches("(?s).*[^\\s]+\\.[a-z0-9]{1,16}.*");
        if ((mentionsDesktop && (requestsOrganization || requestsDesktopTextFile)) || requestsDesktopDeletion) {
            return DESKTOP_ORGANIZATION;
        }
        boolean explicitlyRequestsIntegration = text.contains("fullstack")
                || text.contains("full-stack")
                || text.contains("front-end and back-end")
                || text.contains("frontend and backend")
                || text.contains("\u524d\u540e\u7aef")
                || text.contains("\u8054\u8c03");
        return explicitlyRequestsIntegration
                ? new NodeTaskPolicy(false, true, Set.of(), null)
                : UNRESTRICTED;
    }

    List<ResolvedToolBinding> filter(List<ResolvedToolBinding> bindings) {
        if (!desktopOrganization) {
            return bindings == null ? List.of() : List.copyOf(bindings);
        }
        return (bindings == null ? List.<ResolvedToolBinding>of() : bindings).stream()
                .filter(binding -> allowedTools.contains(binding.logicalName()))
                .toList();
    }

    boolean permits(String toolName) {
        return !desktopOrganization || allowedTools.contains(toolName);
    }

    boolean requiresFirstTool(String toolName) {
        return requiredFirstTool == null || requiredFirstTool.equals(toolName);
    }

    String requiredFirstToolName() {
        return requiredFirstTool;
    }

    boolean requiresDesktopOrganizationEvidence() {
        return desktopOrganization;
    }

    /** 是否要求浏览器验证中包含一次最后页面操作后的 API 响应证据。 */
    boolean requiresFullStackApiEvidence() {
        return fullStackApiEvidenceRequired;
    }

    boolean isRestricted() {
        return desktopOrganization;
    }
}
