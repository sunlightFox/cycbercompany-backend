package io.github.yourname.cycbercompany.nodeclient.tools;

import io.github.yourname.cycbercompany.nodeclient.runtime.ToolExecutionResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Guided Windows uninstall workflow.
 *
 * <p>This tool runs the read-only uninstall preflight, optionally stops a confirmed service,
 * optionally terminates confirmed OS process IDs, and then retries one exact winget uninstall.
 */
public final class UninstallWorkflowTool {

    private static final int MAX_PROCESS_NAMES = 16;

    private final UninstallPreflightTool preflightTool;
    private final SoftwareTool softwareTool;
    private final ServiceTool serviceTool;
    private final OsProcessTool osProcessTool;
    private final boolean windows;

    public UninstallWorkflowTool(
            UninstallPreflightTool preflightTool,
            SoftwareTool softwareTool,
            ServiceTool serviceTool,
            OsProcessTool osProcessTool) {
        this(preflightTool, softwareTool, serviceTool, osProcessTool, isWindows());
    }

    UninstallWorkflowTool(
            UninstallPreflightTool preflightTool,
            SoftwareTool softwareTool,
            ServiceTool serviceTool,
            OsProcessTool osProcessTool,
            boolean windows) {
        this.preflightTool = preflightTool;
        this.softwareTool = softwareTool;
        this.serviceTool = serviceTool;
        this.osProcessTool = osProcessTool;
        this.windows = windows;
    }

    public ToolExecutionResult execute(Map<String, Object> arguments) {
        if (!windows) {
            return ToolExecutionResult.failure("system.uninstall.execute is available on Windows only.");
        }
        if (preflightTool == null || softwareTool == null || serviceTool == null || osProcessTool == null) {
            return ToolExecutionResult.failure("system.uninstall.execute is unavailable on this node.");
        }

        String packageId;
        String serviceName;
        List<String> processNames;
        boolean stopService;
        boolean terminateProcesses;
        boolean retryUninstall;
        try {
            packageId = WindowsToolArgumentPolicy.requireWingetPackageId(optionalString(arguments, "packageId"));
            serviceName = optionalServiceName(arguments);
            processNames = processNames(arguments);
            stopService = booleanValue(arguments, "stopService", true);
            terminateProcesses = booleanValue(arguments, "terminateProcesses", true);
            retryUninstall = booleanValue(arguments, "retryUninstall", true);
        } catch (IllegalArgumentException ex) {
            return ToolExecutionResult.failure(ex.getMessage());
        }

        ToolExecutionResult preflight = preflightTool.preflight(arguments);
        if (!preflight.success()) {
            return preflight;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> preflightSnapshot = map(preflight.result());

        result.put("operation", "execute");
        result.put("packageId", packageId);
        if (serviceName != null) {
            result.put("serviceName", serviceName);
        }
        if (!processNames.isEmpty()) {
            result.put("processNames", processNames);
        }
        result.put("options", Map.of(
                "stopService", stopService,
                "terminateProcesses", terminateProcesses,
                "retryUninstall", retryUninstall));
        result.put("preflightBefore", wrap(preflight));
        result.put("readyForUninstallBefore", preflightSnapshot.get("readyForUninstall"));
        result.put("blockingSignalsBefore", preflightSnapshot.getOrDefault("blockingSignals", List.of()));

        List<Map<String, Object>> remediationSteps = new ArrayList<>();
        boolean anyRemediationAttempted = false;
        boolean anyRemediationFailure = false;

        if (serviceName != null) {
            Map<String, Object> serviceStep = new LinkedHashMap<>();
            serviceStep.put("serviceName", serviceName);
            serviceStep.put("stopRequested", stopService);
            String serviceState = stringValue(preflightSnapshot.get("serviceState"));
            serviceStep.put("preflightServiceState", serviceState);
            if (stopService && "Running".equalsIgnoreCase(serviceState)) {
                anyRemediationAttempted = true;
                ToolExecutionResult stopResult = serviceTool.stop(Map.of("serviceName", serviceName));
                serviceStep.put("attempted", true);
                serviceStep.put("stopResult", wrap(stopResult));
                serviceStep.put("serviceStopped", stopResult.success());
                if (!stopResult.success()) {
                    anyRemediationFailure = true;
                }
            } else {
                serviceStep.put("attempted", false);
                serviceStep.put("skippedReason", stopService
                        ? "Service was not reported as running during preflight."
                        : "Service stop was not requested.");
            }
            remediationSteps.add(serviceStep);
        }

        if (!processNames.isEmpty()) {
            for (String processName : processNames) {
                Map<String, Object> processStep = new LinkedHashMap<>();
                processStep.put("processName", processName);
                processStep.put("terminateRequested", terminateProcesses);
                int count = integerValue(preflightSnapshot.get(processName + "Count"));
                processStep.put("preflightCount", count);
                if (terminateProcesses && count > 0) {
                    anyRemediationAttempted = true;
                    ToolExecutionResult queryResult = osProcessTool.query(Map.of("processName", processName));
                    processStep.put("queryResult", wrap(queryResult));
                    if (!queryResult.success()) {
                        processStep.put("attempted", false);
                        processStep.put("skippedReason", "Process query failed before termination could run.");
                        anyRemediationFailure = true;
                        remediationSteps.add(processStep);
                        continue;
                    }
                    Map<String, Object> querySnapshot = map(queryResult.result().get("snapshot"));
                    List<Integer> processIds = processIds(querySnapshot.get("processes"));
                    processStep.put("attempted", true);
                    processStep.put("processIds", processIds);
                    if (processIds.isEmpty()) {
                        processStep.put("skippedReason", "Preflight did not expose explicit process IDs.");
                        anyRemediationFailure = true;
                    } else {
                        ToolExecutionResult terminateResult = osProcessTool.terminate(Map.of(
                                "processName", processName,
                                "processIds", processIds));
                        processStep.put("terminateResult", wrap(terminateResult));
                        processStep.put("terminated", terminateResult.success());
                        if (!terminateResult.success()) {
                            anyRemediationFailure = true;
                        }
                    }
                } else {
                    processStep.put("attempted", false);
                    processStep.put("skippedReason", terminateProcesses
                            ? "No matching processes were reported during preflight."
                            : "Process termination was not requested.");
                }
                remediationSteps.add(processStep);
            }
        }

        result.put("remediations", remediationSteps);

        ToolExecutionResult preflightAfter = null;
        if (anyRemediationAttempted) {
            preflightAfter = preflightTool.preflight(arguments);
            result.put("preflightAfterRemediation", wrap(preflightAfter));
            if (preflightAfter.success()) {
                Map<String, Object> afterSnapshot = map(preflightAfter.result());
                result.put("readyForUninstallAfter", afterSnapshot.get("readyForUninstall"));
                result.put("blockingSignalsAfter", afterSnapshot.getOrDefault("blockingSignals", List.of()));
            }
        }

        ToolExecutionResult uninstallResult = null;
        ToolExecutionResult uninstallVerification = null;
        boolean uninstallVerified = false;
        if (retryUninstall) {
            result.put("uninstallAttempted", true);
            uninstallResult = softwareTool.uninstall(Map.of("packageId", packageId));
            result.put("uninstallResult", wrap(uninstallResult));
            uninstallVerification = softwareTool.query(Map.of("packageId", packageId));
            result.put("uninstallVerification", wrap(uninstallVerification));
            uninstallVerified = uninstallVerification.success()
                    && Boolean.FALSE.equals(uninstallVerification.result().get("installed"));
            result.put("uninstallVerified", uninstallVerified);
            result.put("uninstallSucceeded", uninstallResult.success() || uninstallVerified);
        } else {
            result.put("uninstallAttempted", false);
        }

        result.put("limitations", "Does not bypass Windows ACLs, protected services, protected processes, or reboot requirements.");

        if (retryUninstall) {
            if (uninstallResult != null && (uninstallResult.success() || uninstallVerified)) {
                return ToolExecutionResult.success(result);
            }
            if (uninstallResult != null) {
                return ToolExecutionResult.failure(result,
                        uninstallResult.errorMessage() == null
                                ? "system.uninstall.execute could not complete the uninstall."
                                : uninstallResult.errorMessage());
            }
            return ToolExecutionResult.failure(result, "system.uninstall.execute could not start the uninstall step.");
        }

        if (anyRemediationFailure) {
            return ToolExecutionResult.failure(result, "One or more remediation steps failed.");
        }
        return ToolExecutionResult.success(result);
    }

    private static Map<String, Object> wrap(ToolExecutionResult value) {
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("success", value.success());
        wrapped.put("errorMessage", value.errorMessage());
        wrapped.put("result", value.result());
        return wrapped;
    }

    private static Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return Map.of();
    }

    private static String optionalString(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static String optionalServiceName(Map<String, Object> arguments) {
        String serviceName = optionalString(arguments, "serviceName");
        return serviceName == null ? null : WindowsToolArgumentPolicy.requireWindowsServiceName(serviceName);
    }

    private static boolean booleanValue(Map<String, Object> arguments, String key, boolean fallback) {
        Object value = arguments == null ? null : arguments.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String raw = value.toString().trim();
        if ("true".equalsIgnoreCase(raw)) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return false;
        }
        throw new IllegalArgumentException(key + " must be a boolean.");
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

    private static String requiredProcessName(String value) {
        return WindowsToolArgumentPolicy.requireWindowsProcessName(value, "processNames entries");
    }

    private static List<Integer> processIds(Object processesValue) {
        if (!(processesValue instanceof List<?> processes)) {
            return List.of();
        }
        List<Integer> result = new ArrayList<>();
        for (Object item : processes) {
            if (!(item instanceof Map<?, ?> process)) {
                continue;
            }
            int processId = integerValue(process.get("processId"));
            if (processId > 0) {
                result.add(processId);
            }
        }
        return List.copyOf(result);
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
