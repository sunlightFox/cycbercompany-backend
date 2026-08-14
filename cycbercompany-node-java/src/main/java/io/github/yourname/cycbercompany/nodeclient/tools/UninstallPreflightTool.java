package io.github.yourname.cycbercompany.nodeclient.tools;

import io.github.yourname.cycbercompany.nodeclient.runtime.ToolExecutionResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read-only uninstall preflight for Windows remediation.
 *
 * <p>This tool aggregates privilege, package, service, and process facts into one structured
 * snapshot so the model can decide whether to stop a service, terminate a process, or retry an
 * uninstall from an elevated node.
 */
public final class UninstallPreflightTool {

    private static final int MAX_PROCESS_NAMES = 16;

    private final PrivilegeTool privilegeTool;
    private final SoftwareTool softwareTool;
    private final ServiceTool serviceTool;
    private final OsProcessTool osProcessTool;
    private final boolean windows;

    public UninstallPreflightTool(
            PrivilegeTool privilegeTool,
            SoftwareTool softwareTool,
            ServiceTool serviceTool,
            OsProcessTool osProcessTool) {
        this(privilegeTool, softwareTool, serviceTool, osProcessTool, isWindows());
    }

    UninstallPreflightTool(
            PrivilegeTool privilegeTool,
            SoftwareTool softwareTool,
            ServiceTool serviceTool,
            OsProcessTool osProcessTool,
            boolean windows) {
        this.privilegeTool = privilegeTool;
        this.softwareTool = softwareTool;
        this.serviceTool = serviceTool;
        this.osProcessTool = osProcessTool;
        this.windows = windows;
    }

    public ToolExecutionResult preflight(Map<String, Object> arguments) {
        if (!windows) {
            return ToolExecutionResult.failure("system.uninstall.preflight is available on Windows only.");
        }
        String packageId;
        String serviceName;
        List<String> processNames;
        try {
            packageId = requiredPackageId(arguments);
            serviceName = optionalServiceName(arguments);
            processNames = processNames(arguments);
        } catch (IllegalArgumentException ex) {
            return ToolExecutionResult.failure(ex.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operation", "preflight");
        result.put("packageId", packageId);
        if (serviceName != null) {
            result.put("serviceName", serviceName);
        }
        if (!processNames.isEmpty()) {
            result.put("processNames", processNames);
        }

        List<String> blockingSignals = new ArrayList<>();
        List<String> recommendedNextSteps = new ArrayList<>();

        ToolExecutionResult privilege = privilegeTool == null ? unavailable("system.privilege.query") : privilegeTool.query();
        putNested(result, "privilege", privilege);
        if (isReadableSuccess(privilege)) {
            Map<String, Object> privilegeSnapshot = map(privilege.result().get("privilege"));
            boolean privileged = booleanValue(privilegeSnapshot.get("isPrivileged"));
            boolean localSystem = booleanValue(privilegeSnapshot.get("isLocalSystem"));
            result.put("isPrivileged", privileged);
            result.put("isLocalSystem", localSystem);
            if (!privileged) {
                blockingSignals.add("Node process is not running with an elevated/admin token or LocalSystem.");
                recommendedNextSteps.add("Rerun the node with elevation before retrying uninstall.");
            }
        }

        ToolExecutionResult software = softwareTool == null
                ? unavailable("system.software.query")
                : softwareTool.query(Map.of("packageId", packageId));
        putNested(result, "software", software);
        if (isReadableSuccess(software)) {
            Map<String, Object> softwareSnapshot = map(software.result());
            boolean installed = booleanValue(softwareSnapshot.get("installed"));
            result.put("packageInstalled", installed);
            if (installed) {
                recommendedNextSteps.add("Attempt uninstall after services and processes are resolved.");
            }
        }

        if (serviceName != null && serviceTool != null) {
            ToolExecutionResult service = serviceTool.query(Map.of("serviceName", serviceName));
            putNested(result, "service", service);
            if (isReadableSuccess(service)) {
                Map<String, Object> serviceSnapshot = map(service.result().get("service"));
                result.put("serviceState", serviceSnapshot.get("state"));
                result.put("serviceStartMode", serviceSnapshot.get("startMode"));
                result.put("serviceCanStop", serviceSnapshot.get("canStop"));
                boolean running = "Running".equalsIgnoreCase(stringValue(serviceSnapshot.get("state")));
                if (running) {
                    blockingSignals.add("Service " + serviceName + " is running.");
                    recommendedNextSteps.add("Stop the service with system.service.stop before retrying uninstall.");
                    recommendedNextSteps.add("If the service must stay disabled, set start mode to disabled with system.service.set_start_mode.");
                }
            }
        }

        if (!processNames.isEmpty() && osProcessTool != null) {
            List<Map<String, Object>> processSnapshots = new ArrayList<>();
            for (String processName : processNames) {
                ToolExecutionResult process = osProcessTool.query(Map.of("processName", processName));
                Map<String, Object> wrapped = wrap(process);
                wrapped.put("processName", processName);
                processSnapshots.add(wrapped);
                if (isReadableSuccess(process)) {
                    Map<String, Object> snapshot = map(process.result().get("snapshot"));
                    result.put(processName + "Count", snapshot.get("count"));
                    int count = integerValue(snapshot.get("count"));
                    if (count > 0) {
                        blockingSignals.add("Process image " + processName + " has " + count + " matching instance(s).");
                        recommendedNextSteps.add("Terminate matching process IDs with system.os_process.terminate after confirming the query result.");
                    }
                }
            }
            result.put("processes", processSnapshots);
        }

        result.put("blockingSignals", List.copyOf(blockingSignals));
        result.put("recommendedNextSteps", List.copyOf(recommendedNextSteps));
        result.put("readyForUninstall", blockingSignals.isEmpty());
        result.put("limitations", "Read-only preflight only; it does not stop services, terminate processes, or uninstall packages.");
        return ToolExecutionResult.success(result);
    }

    private static ToolExecutionResult unavailable(String toolName) {
        return ToolExecutionResult.failure(toolName + " is unavailable on this node.");
    }

    private static void putNested(Map<String, Object> target, String key, ToolExecutionResult value) {
        target.put(key, wrap(value));
    }

    private static Map<String, Object> wrap(ToolExecutionResult value) {
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("success", value.success());
        wrapped.put("errorMessage", value.errorMessage());
        wrapped.put("result", value.result());
        return wrapped;
    }

    private static boolean isReadableSuccess(ToolExecutionResult value) {
        return value != null && value.success() && value.result() != null;
    }

    private static Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return Map.of();
    }

    private static List<String> processNames(Map<String, Object> arguments) {
        Object raw = arguments == null ? null : arguments.get("processNames");
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> values)) {
            throw new IllegalArgumentException("processNames must be an array of exact process image names.");
        }
        if (values.size() > MAX_PROCESS_NAMES) {
            throw new IllegalArgumentException("processNames cannot contain more than " + MAX_PROCESS_NAMES + " entries.");
        }
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            String processName = requiredProcessName(String.valueOf(value));
            result.add(processName);
        }
        return List.copyOf(result);
    }

    private static String requiredPackageId(Map<String, Object> arguments) {
        return WindowsToolArgumentPolicy.requireWingetPackageId(optionalString(arguments, "packageId"));
    }

    private static String optionalServiceName(Map<String, Object> arguments) {
        String serviceName = optionalString(arguments, "serviceName");
        if (serviceName == null) {
            return null;
        }
        return WindowsToolArgumentPolicy.requireWindowsServiceName(serviceName);
    }

    private static String optionalString(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static String requiredProcessName(String value) {
        return WindowsToolArgumentPolicy.requireWindowsProcessName(value, "processNames entries");
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && "true".equalsIgnoreCase(value.toString().trim());
    }

    private static int integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
