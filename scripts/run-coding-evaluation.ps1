param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("minimal-full-stack", "failed-test-minimal-fix", "split-frontend-backend", "existing-repository-feature", "long-task-recovery")]
    [string]$Scenario,

    [Parameter(Mandatory = $true)]
    [string]$Prompt,

    [Parameter(Mandatory = $true)]
    [string]$WorkingDirectory,

    # WorkingDirectory identifies the host fixture for preflight safety checks.
    # Native coding tools are confined to the selected node workspace, therefore
    # this value sent to the Run API must remain relative to that workspace.
    [string]$RunWorkingDirectory = "",

    [string]$BaseUrl = "http://localhost:8080",
    [string]$NodeId = "auto",
    [string[]]$NodeLabels = @(),
    [string]$ModelProfileId,
    [string[]]$ToolNames = @(),
    [ValidateSet("on-request", "auto-approve", "full-access")]
    [string]$ApprovalMode = "auto-approve",
    [switch]$ApproveHighRisk,
    [switch]$SkipPreflight,
    [switch]$SkipModelProbe,
    [int]$TimeoutSeconds = 900,
    [string]$OutputDirectory = "evaluation-results"
)

$ErrorActionPreference = "Stop"
$script:transientFailures = [System.Collections.Generic.List[object]]::new()
. (Join-Path $PSScriptRoot "studio-http.ps1")

function Get-ScenarioToolNames {
    param([string]$ScenarioName)

    switch ($ScenarioName) {
        "minimal-full-stack" { return @("fs.*", "project.*", "shell.run", "process.*", "browser.*", "git.*") }
        "failed-test-minimal-fix" { return @("fs.*", "project.*", "shell.run", "git.*") }
        "split-frontend-backend" { return @("fs.*", "project.*", "shell.run", "process.*", "browser.*", "git.*") }
        "existing-repository-feature" { return @("fs.*", "project.*", "shell.run", "git.*") }
        "long-task-recovery" { return @("fs.*", "project.*", "shell.run", "process.*", "git.*") }
        default { throw "No default tool profile exists for scenario: $ScenarioName" }
    }
}

if ($null -eq $ToolNames -or $ToolNames.Count -eq 0) {
    $ToolNames = Get-ScenarioToolNames $Scenario
}

function Resolve-RunWorkingDirectory {
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path) -or $Path.Trim() -eq ".") { return "" }
    $normalized = $Path.Trim().Replace("\\", "/")
    $segments = $normalized -split "/"
    if ($normalized.StartsWith("/") -or $normalized.StartsWith("//") -or $normalized -match "^[A-Za-z]:" -or ($segments -contains "..")) {
        throw "RunWorkingDirectory must be relative to the node workspace and must not leave it."
    }
    return $normalized
}

$resolvedRunWorkingDirectory = Resolve-RunWorkingDirectory $RunWorkingDirectory

# 在创建会话和 Run 之前先做只读检查。这样端口指向了其他服务、节点离线或
# 工作区填成桌面时，会立刻报出原因，不会留下一个必然失败的评测 Run。
if (-not $SkipPreflight) {
    $preflightArguments = @{
        Scenario = $Scenario
        WorkingDirectory = $WorkingDirectory
        BaseUrl = $BaseUrl
        NodeId = $NodeId
        NodeLabels = $NodeLabels
        ToolNames = $ToolNames
        RequireFixtureMarker = $true
    }
    if (-not [string]::IsNullOrWhiteSpace($ModelProfileId)) {
        $preflightArguments.ModelProfileId = $ModelProfileId
    }
    # 正式评测随后一定会调用模型，因此默认先做一次只读连通性探测。这样 API key、模型路由
    # 或工具调用能力的问题会在创建 Conversation/Run 前暴露。只有排查节点与夹具时才显式跳过。
    if (-not $SkipModelProbe) {
        $preflightArguments.ProbeModel = $true
    }
    & (Join-Path $PSScriptRoot "test-coding-evaluation-preflight.ps1") @preflightArguments | Out-Host
}

function Invoke-StudioJson {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [object]$Body = $null
    )
    return Invoke-StudioJsonUtf8 -BaseUrl $BaseUrl -Method $Method -Path $Path -Body $Body -ApiToken $env:AGENT_STUDIO_API_TOKEN
}

function Test-TransientApiFailure {
    param([System.Management.Automation.ErrorRecord]$ErrorRecord)

    $statusCode = $null
    $response = $ErrorRecord.Exception.Response
    if ($null -ne $response) {
        try { $statusCode = [int]$response.StatusCode } catch { }
    }
    if ($statusCode -in @(429, 502, 503, 504)) {
        return $true
    }

    $message = $ErrorRecord.Exception.Message
    return $message -match "(?i)(connection.*(reset|refused|closed)|connect.*timed out|temporarily unavailable|gateway timeout|bad gateway|service unavailable)"
}

function Invoke-StudioJsonWithRetry {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [object]$Body = $null,
        [Parameter(Mandatory = $true)][datetime]$Deadline
    )

    $attempt = 0
    while ($true) {
        try {
            return Invoke-StudioJson -Method $Method -Path $Path -Body $Body
        } catch {
            if (-not (Test-TransientApiFailure $_) -or (Get-Date) -ge $Deadline) {
                throw
            }
            $attempt++
            $delaySeconds = [Math]::Min(5, $attempt)
            $failure = [ordered]@{
                at = (Get-Date).ToString("o")
                method = $Method
                path = $Path
                attempt = $attempt
                message = $_.Exception.Message
            }
            $script:transientFailures.Add($failure)
            Write-Warning "Transient API failure for $Method $Path; retrying in $delaySeconds second(s)."
            Start-Sleep -Seconds $delaySeconds
        }
    }
}

function Get-StudioJsonBestEffort {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][datetime]$Deadline
    )

    try {
        return Invoke-StudioJsonWithRetry -Method Get -Path $Path -Deadline $Deadline
    } catch {
        return [ordered]@{
            unavailable = $true
            error = $_.Exception.Message
        }
    }
}

# 这个脚本故意把“创建任务”和“读取评分”放在一起，减少人工漏掉评测报告的概率。
# 它不会伪造工具证据；评分只来自后端保存的 Run 审计。
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

$conversation = Invoke-StudioJson -Method Post -Path "/api/v1/conversations" -Body @{ title = "Coding evaluation: $Scenario" }
$runRequest = @{
    conversationId = $conversation.id
    text = $Prompt
    toolNames = $ToolNames
    nodeId = $NodeId
    workingDirectory = $resolvedRunWorkingDirectory
    nodeLabels = $NodeLabels
    approvalMode = $ApprovalMode
}
if (-not [string]::IsNullOrWhiteSpace($ModelProfileId)) {
    $runRequest.modelProfileId = $ModelProfileId
}
$run = Invoke-StudioJson -Method Post -Path "/api/v1/runs" -Body $runRequest
$runId = $run.runId
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$approved = @{}
$pollingError = $null

Write-Host "Started coding evaluation run $runId for scenario $Scenario"

while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 2
    try {
        $current = Invoke-StudioJsonWithRetry -Method Get -Path "/api/v1/runs/$runId" -Deadline $deadline
    } catch {
        $pollingError = $_.Exception.Message
        Write-Warning "Stopped polling run ${runId}: $pollingError"
        break
    }
    if ($ApproveHighRisk -and $current.status -eq "WAITING_APPROVAL") {
        $approvals = Invoke-StudioJsonWithRetry -Method Get -Path "/api/v1/node-tool-approvals" -Deadline $deadline
        foreach ($approval in @($approvals)) {
            if ($approval.runId -eq $runId -and -not $approved.ContainsKey($approval.id)) {
                Invoke-StudioJsonWithRetry -Method Post -Path "/api/v1/node-tool-approvals/$($approval.id)/decision" -Body @{ approved = $true } -Deadline $deadline | Out-Null
                $approved[$approval.id] = $true
                Write-Host "Approved node tool request $($approval.id)"
            }
        }
    }
    if (@("SUCCEEDED", "NEEDS_VERIFICATION", "FAILED", "CANCELLED", "TIMED_OUT") -contains $current.status) {
        break
    }
}

# A restart may outlive the polling deadline. Keep the created Run ID and write the
# best state that can be read once the backend is reachable again.
$finalFetchDeadline = (Get-Date).AddSeconds(30)
$finalRun = Get-StudioJsonBestEffort -Path "/api/v1/runs/$runId" -Deadline $finalFetchDeadline
$evaluation = Get-StudioJsonBestEffort -Path "/api/v1/runs/$runId/coding-evaluation?scenario=$Scenario" -Deadline $finalFetchDeadline
$evidence = Get-StudioJsonBestEffort -Path "/api/v1/runs/$runId/coding-evidence" -Deadline $finalFetchDeadline
$quality = Get-StudioJsonBestEffort -Path "/api/v1/runs/$runId/coding-quality" -Deadline $finalFetchDeadline

$safeTimestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$reportPath = Join-Path $OutputDirectory "$safeTimestamp-$Scenario-$runId.json"
$summaryPath = Join-Path $OutputDirectory "$safeTimestamp-$Scenario-$runId.md"

[ordered]@{
    scenario = $Scenario
    run = $finalRun
    evaluation = $evaluation
    evidence = $evidence
    quality = $quality
    pollingError = $pollingError
    transientFailures = @($script:transientFailures)
} | ConvertTo-Json -Depth 30 | Set-Content -Encoding UTF8 -Path $reportPath

$summary = @()
$summary += "# Coding evaluation: $Scenario"
$summary += ""
$summary += "- Run ID: $runId"
$summary += "- Status: $($finalRun.status)"
$summary += "- Score: $($evaluation.score)"
$summary += "- Passed: $($evaluation.passed)"
$summary += "- Polling error: $pollingError"
$summary += "- Transient API retries: $($script:transientFailures.Count)"
$summary += "- JSON report: $reportPath"
$summary += ""
$summary += "## Checks"
foreach ($check in @($evaluation.checks)) {
    $summary += "- $($check.category): $($check.earnedPoints)/$($check.maximumPoints) - $($check.evidence)"
}
$summary | Set-Content -Encoding UTF8 -Path $summaryPath

Write-Host "Evaluation score: $($evaluation.score), passed: $($evaluation.passed)"
Write-Host "Report written to $reportPath"
Write-Host "Summary written to $summaryPath"

if ($evaluation.unavailable -or -not $evaluation.passed) {
    exit 2
}
