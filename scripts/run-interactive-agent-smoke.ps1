[CmdletBinding()]
param(
    [string]$BaseUrl = "http://127.0.0.1:8084",
    [Parameter(Mandatory = $true)]
    [string]$NodeId,
    [Parameter(Mandatory = $true)]
    [string]$WorkingDirectory,
    [string]$HealthUrl = "http://127.0.0.1:18092/",
    [string]$ServerCommand = "py -3 backend/server.py",
    [string]$OutputDirectory = ".tmp-evaluation-results"
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "studio-http.ps1")

$base = $BaseUrl.Trim().TrimEnd("/")
$workspace = [System.IO.Path]::GetFullPath($WorkingDirectory).TrimEnd("\", "/")
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$fixtureRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot ".tmp-coding-evaluation-fixtures"))
$marker = Join-Path $workspace ".agent-studio-evaluation-fixture"

if (-not (Test-Path -LiteralPath $workspace -PathType Container)) {
    throw "WorkingDirectory does not exist: $workspace"
}
if (-not $workspace.StartsWith($fixtureRoot + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Smoke tests are restricted to a child of .tmp-coding-evaluation-fixtures."
}
if (-not (Test-Path -LiteralPath $marker -PathType Leaf)) {
    throw "WorkingDirectory is missing the evaluation fixture marker."
}

function Invoke-Studio {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [object]$Body = $null,
        [int]$TimeoutSeconds = 120
    )
    Invoke-StudioJsonUtf8 -BaseUrl $base -Method $Method -Path $Path -Body $Body -ApiToken $env:AGENT_STUDIO_API_TOKEN -TimeoutSeconds $TimeoutSeconds
}

function Invoke-NodeTool {
    param(
        [Parameter(Mandatory = $true)][string]$ToolName,
        [hashtable]$Arguments = @{},
        [int]$TimeoutSeconds = 30
    )

    $request = Invoke-Studio -Method POST -Path "/api/v1/nodes/$NodeId/tools/$ToolName/call" -Body @{
        arguments = $Arguments
        timeoutSeconds = $TimeoutSeconds
    } -TimeoutSeconds ($TimeoutSeconds + 15)

    if ($request.status -ne "APPROVAL_REQUIRED") {
        return $request
    }

    $approvalId = [string]$request.result.approvalId
    if ([string]::IsNullOrWhiteSpace($approvalId)) {
        throw "Tool $ToolName requested approval without an approval ID."
    }
    $decision = Invoke-Studio -Method POST -Path "/api/v1/node-tool-approvals/$approvalId/decision" -Body @{
        approved = $true
    }
    if ($decision.execution.status -ne "SUCCEEDED") {
        throw "Approved tool $ToolName did not succeed: $($decision.execution.errorMessage)"
    }
    return $decision.execution
}

function Assert-Succeeded {
    param([Parameter(Mandatory = $true)]$Result, [Parameter(Mandatory = $true)][string]$Label)
    if ($Result.status -ne "SUCCEEDED") {
        throw "$Label failed with status $($Result.status): $($Result.errorMessage)"
    }
}

$checks = [ordered]@{
    nodeOnline = $false
    directRead = $false
    approvalResume = $false
    managedProcessReady = $false
    browserTrace = $false
    browserVerified = $false
    processStopped = $false
}
$evidence = [ordered]@{}
$processId = $null
$traceStarted = $false
$startedAt = Get-Date

try {
    $node = Invoke-Studio -Method GET -Path "/api/v1/nodes/$NodeId"
    $nodeView = if ($null -ne $node.node) { $node.node } else { $node }
    if ($nodeView.status -ne "ONLINE" -or $nodeView.kind -ne "SANDBOX") {
        throw "Node is not an ONLINE SANDBOX: status=$($nodeView.status), kind=$($nodeView.kind)"
    }
    $checks.nodeOnline = $true
    $evidence.node = @{
        id = $NodeId
        status = $nodeView.status
        kind = $nodeView.kind
        labels = @($nodeView.labels)
    }

    $list = Invoke-NodeTool -ToolName "fs.list" -Arguments @{ path = "." } -TimeoutSeconds 20
    Assert-Succeeded $list "fs.list"
    $checks.directRead = $true
    $evidence.directRead = @{
        invocationId = $list.invocationId
        entryCount = @($list.result.entries).Count
    }

    $approval = Invoke-NodeTool -ToolName "shell.run" -Arguments @{
        command = "echo interactive-agent-smoke"
    } -TimeoutSeconds 20
    Assert-Succeeded $approval "approved shell.run"
    $checks.approvalResume = $true
    $evidence.approvalResume = @{
        invocationId = $approval.invocationId
        exitCode = $approval.result.exitCode
        stdout = $approval.result.stdout
    }

    $started = Invoke-NodeTool -ToolName "process.start" -Arguments @{
        command = $ServerCommand
    } -TimeoutSeconds 30
    Assert-Succeeded $started "process.start"
    $processId = [string]$started.result.processId
    if ([string]::IsNullOrWhiteSpace($processId)) {
        throw "process.start returned no processId."
    }
    $evidence.processStart = @{
        invocationId = $started.invocationId
        processId = $processId
    }

    $ready = Invoke-NodeTool -ToolName "process.wait_http" -Arguments @{
        processId = $processId
        url = $HealthUrl
        expectedStatus = 200
        timeoutMs = 30000
    } -TimeoutSeconds 40
    Assert-Succeeded $ready "process.wait_http"
    $checks.managedProcessReady = [bool]$ready.result.ready
    $evidence.processReady = @{
        invocationId = $ready.invocationId
        processId = $processId
        statusCode = $ready.result.statusCode
        ready = $ready.result.ready
    }

    $trace = Invoke-NodeTool -ToolName "browser.trace.start" -Arguments @{} -TimeoutSeconds 30
    Assert-Succeeded $trace "browser.trace.start"
    $traceStarted = $true
    $opened = Invoke-NodeTool -ToolName "browser.open" -Arguments @{
        url = $HealthUrl
        headless = $true
    } -TimeoutSeconds 30
    Assert-Succeeded $opened "browser.open"
    $clicked = Invoke-NodeTool -ToolName "browser.click" -Arguments @{
        selector = "#load"
        timeoutMs = 10000
    } -TimeoutSeconds 30
    Assert-Succeeded $clicked "browser.click"
    $verified = Invoke-NodeTool -ToolName "browser.verify" -Arguments @{
        checks = @(
            @{ type = "textContains"; value = "Ada Engineer" },
            @{ type = "responseStatus"; value = "200"; urlContains = "/api/profile" }
        )
    } -TimeoutSeconds 30
    Assert-Succeeded $verified "browser.verify"
    $checks.browserVerified = [bool]$verified.result.verified
    $evidence.browser = @{
        openInvocationId = $opened.invocationId
        clickInvocationId = $clicked.invocationId
        verifyInvocationId = $verified.invocationId
        verified = $verified.result.verified
    }

    $traceStopped = Invoke-NodeTool -ToolName "browser.trace.stop" -Arguments @{} -TimeoutSeconds 30
    Assert-Succeeded $traceStopped "browser.trace.stop"
    $traceStarted = $false
    $checks.browserTrace = $true
    $evidence.trace = @{
        invocationId = $traceStopped.invocationId
        artifactId = $traceStopped.result.artifact.id
        sizeBytes = $traceStopped.result.artifact.sizeBytes
    }
}
finally {
    if ($traceStarted) {
        try { Invoke-NodeTool -ToolName "browser.trace.stop" -Arguments @{} -TimeoutSeconds 30 | Out-Null } catch { }
    }
    if (-not [string]::IsNullOrWhiteSpace($processId)) {
        try {
            $stopped = Invoke-NodeTool -ToolName "process.stop" -Arguments @{ processId = $processId } -TimeoutSeconds 40
            if ($stopped.status -eq "SUCCEEDED") {
                $checks.processStopped = -not [bool]$stopped.result.active
                $evidence.processStop = @{
                    invocationId = $stopped.invocationId
                    processId = $processId
                    active = $stopped.result.active
                    exitCode = $stopped.result.exitCode
                }
            }
        } catch {
            $evidence.processStopError = $_.Exception.Message
        }
    }
}

$passed = @($checks.Values | Where-Object { $_ -ne $true }).Count -eq 0
$report = [ordered]@{
    scenario = "interactive-agent-smoke"
    passed = $passed
    startedAt = $startedAt.ToString("o")
    finishedAt = (Get-Date).ToString("o")
    baseUrl = $base
    nodeId = $NodeId
    workspace = $workspace
    checks = $checks
    evidence = $evidence
}

New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$jsonPath = Join-Path $OutputDirectory "$stamp-interactive-agent-smoke.json"
$report | ConvertTo-Json -Depth 20 | Set-Content -Encoding UTF8 -Path $jsonPath

if (-not $passed) {
    throw "Interactive agent smoke failed. Report: $jsonPath"
}

[ordered]@{
    passed = $true
    report = $jsonPath
    nodeId = $NodeId
    processId = $processId
    checks = $checks
} | ConvertTo-Json -Depth 10
