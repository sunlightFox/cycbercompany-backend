package io.github.yourname.cycbercompany.execution;

import io.github.yourname.cycbercompany.config.LocalExecutorProperties;
import io.github.yourname.cycbercompany.node.NodeToolPolicy;
import io.github.yourname.cycbercompany.node.NodeToolPolicyCatalog;
import io.github.yourname.cycbercompany.nodeclient.NodeAccessMode;
import io.github.yourname.cycbercompany.nodeclient.protocol.NodeCapability;
import io.github.yourname.cycbercompany.nodeclient.runtime.ToolExecutionResult;
import io.github.yourname.cycbercompany.nodeclient.runtime.ToolRegistry;
import io.github.yourname.cycbercompany.tool.ToolDescriptor;
import io.github.yourname.cycbercompany.tool.ToolDiscoveryRequest;
import io.github.yourname.cycbercompany.tool.ToolInvocationRequest;
import io.github.yourname.cycbercompany.tool.ToolProvider;
import io.github.yourname.cycbercompany.tool.ToolProviderResult;
import io.github.yourname.cycbercompany.tool.RegisteredTool;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;

/**
 * The local execution implementation hosted by the server process.  It reuses
 * the client runtime, but does not use node registration, secrets, or WebSocket
 * transport.  The same runtime can therefore be extracted into a client later.
 */
@Service
public final class InProcessLocalToolProvider implements ToolProvider {

    public static final String PROVIDER_ID = "in_process_local";
    public static final String TARGET_ID = "in-process-local";

    private final ToolRegistry tools;

    public InProcessLocalToolProvider(LocalExecutorProperties properties) {
        Path workspace = workspace(properties);
        tools = new ToolRegistry(
                HttpClient.newHttpClient(), workspace, NodeAccessMode.SYSTEM,
                desktopRoot(), null, null);
    }

    /** Compatibility constructor for focused runtime tests. */
    InProcessLocalToolProvider() {
        this(new LocalExecutorProperties(true, Path.of(System.getProperty("user.home"))));
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public List<ToolDescriptor> discover(ToolDiscoveryRequest request) {
        if (!TARGET_ID.equals(request.nodeId())) {
            return List.of();
        }
        return tools.capabilities().stream()
                .map(this::descriptor)
                .toList();
    }

    @Override
    public ToolProviderResult invoke(ToolInvocationRequest request) {
        if (!PROVIDER_ID.equals(request.binding().providerId())) {
            throw new IllegalArgumentException("In-process local provider cannot invoke " + request.binding().bindingId());
        }
        String tool = request.binding().providerToolName();
        ToolExecutionResult result = tools.execute(tool, request.arguments(), request.runId());
        return new ToolProviderResult(
                result.success() ? "SUCCEEDED" : "FAILED",
                result.success(),
                result.result(),
                result.errorMessage(),
                null);
    }

    /** Tools advertised by a running local server, without requiring a node record. */
    public List<RegisteredTool> registeredTools() {
        return tools.capabilities().stream()
                .map(this::descriptor)
                .map(tool -> new RegisteredTool(
                        tool.logicalName(), tool.description(), tool.riskLevel(), tool.requiresApproval()))
                .toList();
    }

    private ToolDescriptor descriptor(NodeCapability capability) {
        NodeToolPolicy policy = NodeToolPolicyCatalog.managedLocalPolicyFor(capability.name());
        return new ToolDescriptor(
                PROVIDER_ID + ":" + capability.name(),
                capability.name(),
                PROVIDER_ID,
                capability.name(),
                capability.description(),
                policy.riskLevel(),
                policy.requiresApproval(),
                capability.inputSchema(),
                Map.of("target", TARGET_ID, "execution", "in-process"));
    }

    private static Path desktopRoot() {
        String profile = System.getenv("USERPROFILE");
        Path fromProfile = profile == null || profile.isBlank() ? null : Path.of(profile, "Desktop");
        if (fromProfile != null && Files.isDirectory(fromProfile)) {
            return fromProfile;
        }
        Path fromHome = Path.of(System.getProperty("user.home"), "Desktop");
        return Files.isDirectory(fromHome) ? fromHome : null;
    }

    private static Path workspace(LocalExecutorProperties properties) {
        Path configured = properties == null ? null : properties.workspace();
        Path workspace = configured == null ? Path.of(System.getProperty("user.home")) : configured;
        try {
            return Files.createDirectories(workspace.toAbsolutePath().normalize());
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot create the in-process local executor workspace: " + workspace, ex);
        }
    }

    @PreDestroy
    void close() {
        tools.close();
    }
}
