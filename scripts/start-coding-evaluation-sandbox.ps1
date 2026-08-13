[CmdletBinding(DefaultParameterSetName = "Default")]
param(
    [Parameter(Mandatory = $true, ParameterSetName = "Default")]
    [ValidateSet("minimal-full-stack", "failed-test-minimal-fix", "split-frontend-backend", "existing-repository-feature", "long-task-recovery")]
    [string]$Scenario,
    [Parameter(Mandatory = $true, ParameterSetName = "Default")]
    [string]$WorkingDirectory,
    [Parameter(ParameterSetName = "Default")]
    [string]$BaseUrl = "http://localhost:8080",
    [Parameter(ParameterSetName = "Default")]
    [string]$NodeName = "coding-evaluation-sandbox",
    [Parameter(ParameterSetName = "Default")]
    [string[]]$NodeLabels = @("coding-evaluation"),
    [Parameter(ParameterSetName = "Default")]
    [ValidateSet("workspace", "system")]
    [string]$NodeAccess = "workspace",
    [Parameter(ParameterSetName = "Default")]
    [string]$ConfigDirectory,
    [Parameter(ParameterSetName = "Default")]
    [string]$LauncherPath,
    [Parameter(ParameterSetName = "Default")]
    [switch]$EnableNodesOnly,
    [Parameter(ParameterSetName = "Default")]
    [switch]$AsJson
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "studio-http.ps1")


function Invoke-StudioJson {
    param([string]$Method, [string]$Path, [object]$Body = $null)
    try {
        Invoke-StudioJsonUtf8 -BaseUrl $script:BaseUrl -Method $Method -Path $Path -Body $Body -ApiToken $env:AGENT_STUDIO_API_TOKEN
    } catch {
        # 不打印令牌或请求体；URL 和服务端返回的安全摘要足以定位注册/配置错误。
        throw "Studio request failed: $Method $Path. $($_.Exception.Message)"
    }
}

function Invoke-StudioJsonWithRetry {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [object]$Body = $null,
        [int]$Attempts = 6
    )

    $lastError = $null
    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        try {
            return Invoke-StudioJson -Method $Method -Path $Path -Body $Body
        } catch {
            $lastError = $_
            if ($attempt -eq $Attempts) {
                break
            }
            Start-Sleep -Milliseconds ([Math]::Min(2000, 250 * $attempt))
        }
    }
    throw "Studio request failed after $Attempts attempts: $Method $Path. $($lastError.Exception.Message)"
}

function Stop-SandboxSetup { param([string]$Message); throw "Coding evaluation sandbox setup failed: $Message" }

function Get-FixtureNodeLabel {
    param([Parameter(Mandatory = $true)][string]$Workspace)

    $normalized = $Workspace.TrimEnd('\', '/').ToLowerInvariant()
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($normalized)
    $algorithm = [System.Security.Cryptography.SHA256]::Create()
    try { $hash = $algorithm.ComputeHash($bytes) } finally { $algorithm.Dispose() }
    $hex = [System.BitConverter]::ToString($hash).Replace('-', '').ToLowerInvariant()
    return "evaluation-fixture-$($hex.Substring(0, 16))"
}

function Test-StudioBackend {
    try {
        $health = Invoke-RestMethod -Method Get -Uri "$script:BaseUrl/actuator/health" -TimeoutSec 10
        if ($health.status -eq "UP") { return }
    } catch { }
    try {
        $models = @(Invoke-StudioJson -Method Get -Path "/api/v1/models")
        if ($null -eq $models) { throw "Model API returned no response." }
    } catch {
        Stop-SandboxSetup "Cannot reach a CycberCompany backend at $script:BaseUrl. $($_.Exception.Message)"
    }
}

if (-not (Test-Path -LiteralPath $WorkingDirectory -PathType Container)) { Stop-SandboxSetup "WorkingDirectory does not exist: $WorkingDirectory" }
$workspace = (Resolve-Path -LiteralPath $WorkingDirectory).Path.TrimEnd('\', '/')
$root = [System.IO.Path]::GetPathRoot($workspace).TrimEnd('\', '/')
if ([string]::IsNullOrWhiteSpace($workspace) -or $workspace -eq $root) { Stop-SandboxSetup "WorkingDirectory must be a dedicated child directory, never a drive root." }
$markerPath = Join-Path $workspace ".agent-studio-evaluation-fixture"
if (-not (Test-Path -LiteralPath $markerPath -PathType Leaf)) { Stop-SandboxSetup "WorkingDirectory is not a CycberCompany evaluation fixture: $workspace" }
$marker = Get-Content -LiteralPath $markerPath -Raw -Encoding UTF8
if ($marker -notmatch ('(?m)^scenario={0}\r?$' -f [regex]::Escape($Scenario))) { Stop-SandboxSetup "Fixture marker scenario does not match the requested scenario." }
$fixtureNodeLabel = Get-FixtureNodeLabel $workspace

$script:BaseUrl = $BaseUrl.Trim().TrimEnd('/')
if ([string]::IsNullOrWhiteSpace($script:BaseUrl)) { Stop-SandboxSetup "BaseUrl must not be empty." }
Test-StudioBackend

$settings = Invoke-StudioJson -Method Get -Path "/api/v1/execution-settings"
$previousExecutionMode = $settings.mode
if ($EnableNodesOnly -and $settings.mode -ne "NODES_ONLY") {
    $settings = Invoke-StudioJson -Method Patch -Path "/api/v1/execution-settings" -Body @{ mode = "NODES_ONLY" }
}
if ($settings.mode -ne "NODES_ONLY") {
    Stop-SandboxSetup "Backend execution mode is $($settings.mode). Coding evaluation requires a dedicated backend already configured for NODES_ONLY."
}

if ([string]::IsNullOrWhiteSpace($ConfigDirectory)) {
    $workspaceParent = [System.IO.Path]::GetDirectoryName($workspace)
    if ([string]::IsNullOrWhiteSpace($workspaceParent)) { Stop-SandboxSetup "Could not determine the fixture parent directory." }
    $ConfigDirectory = Join-Path $workspaceParent ".agent-studio-node-config"
}
$configRoot = [System.IO.Path]::GetFullPath($ConfigDirectory)
if ($configRoot.StartsWith($workspace + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase) -or $configRoot -eq $workspace) { Stop-SandboxSetup "ConfigDirectory must be outside the evaluation workspace because it contains the node credential." }
New-Item -ItemType Directory -Force -Path $configRoot | Out-Null

if ([string]::IsNullOrWhiteSpace($LauncherPath)) { $LauncherPath = Join-Path $PSScriptRoot "..\agent-studio-node-java\build\install\agent-studio-node-java\bin\agent-studio-node-java.bat" }
$launcher = [System.IO.Path]::GetFullPath($LauncherPath)
if (-not (Test-Path -LiteralPath $launcher -PathType Leaf)) { Stop-SandboxSetup "Node launcher was not found: $launcher. Run .\gradlew.bat :agent-studio-node-java:installDist first." }

$registration = Invoke-StudioJson -Method Post -Path "/api/v1/node-registration-tokens" -Body @{ ttlSeconds = 600 }
if ([string]::IsNullOrWhiteSpace($registration.registrationToken)) { Stop-SandboxSetup "Backend did not return a registrationToken." }
$safeName = $NodeName -replace '[^A-Za-z0-9._-]', '_'
$configPath = Join-Path $configRoot "$safeName-$Scenario.json"
$registerOutput = & $launcher register --server $script:BaseUrl --token $registration.registrationToken --name $NodeName --workspace $workspace --access $NodeAccess --config $configPath
if ($LASTEXITCODE -ne 0) { Stop-SandboxSetup "Node client registration failed." }
$nodeIdLine = @($registerOutput | Where-Object { $_ -like "nodeId=*" } | Select-Object -First 1)
if ($nodeIdLine.Count -ne 1) { Stop-SandboxSetup "Node client registration did not report a node ID." }
$nodeId = $nodeIdLine[0].Substring("nodeId=".Length)
if ([string]::IsNullOrWhiteSpace($nodeId)) { Stop-SandboxSetup "Node client reported an empty node ID." }

$stdoutPath = Join-Path $configRoot "$safeName-$Scenario.out.log"
$stderrPath = Join-Path $configRoot "$safeName-$Scenario.err.log"
$stdinPath = Join-Path $configRoot "$safeName-$Scenario.in.log"
if (-not (Test-Path -LiteralPath $stdinPath -PathType Leaf)) {
    New-Item -ItemType File -Path $stdinPath -Force | Out-Null
}
$nodeProcess = Start-Process -FilePath $launcher -ArgumentList @("start", "--config", $configPath) -WorkingDirectory $configRoot -RedirectStandardInput $stdinPath -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath -WindowStyle Hidden -PassThru

$deadline = (Get-Date).AddSeconds(20)
$detail = $null
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 500
    try {
        $candidate = Invoke-StudioJson -Method Get -Path "/api/v1/nodes/$nodeId"
        # Confirm the WebSocket first. Read tools from the detail response after the node is online.
        if ($candidate.node.status -eq "ONLINE") { $detail = $candidate; break }
    } catch { }
}
if ($null -eq $detail) { Stop-SandboxSetup "Node did not become ONLINE with reported tools within 20 seconds. Inspect $stderrPath." }

# Set scheduling metadata only after the first capability heartbeat has completed.
# This avoids a stale registration snapshot overwriting SANDBOX metadata.
$schedulingBody = @{
    name = $NodeName
    enabled = $true
    kind = "SANDBOX"
    labels = @($NodeLabels + $fixtureNodeLabel | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique)
}
$null = Invoke-StudioJsonWithRetry -Method Patch -Path "/api/v1/nodes/$nodeId" -Body $schedulingBody
$settledDeadline = (Get-Date).AddSeconds(10)
$detail = $null
while ((Get-Date) -lt $settledDeadline) {
    $candidate = Invoke-StudioJsonWithRetry -Method Get -Path "/api/v1/nodes/$nodeId" -Body $null -Attempts 3
    if ($candidate.node.kind -eq "SANDBOX" -and $candidate.node.status -eq "ONLINE" -and @($candidate.tools).Count -gt 0) {
        $detail = $candidate
        break
    }
    Start-Sleep -Milliseconds 250
}
if ($null -eq $detail) {
    Stop-SandboxSetup "Node did not retain the required ONLINE SANDBOX state after registration."
}

# Workspace mutations remain approval-protected, but a disposable evaluation node must expose
# them or it cannot execute the documented coding scenarios. These policies are applied only
# after this node is explicitly marked SANDBOX and its workspace was validated as a fixture.
$requiredWorkspaceTools = @("fs.write", "fs.apply_patch", "fs.apply_patch_batch", "shell.run", "process.start", "process.stop")
foreach ($toolName in $requiredWorkspaceTools) {
    $null = Invoke-StudioJsonWithRetry -Method Patch -Path "/api/v1/nodes/$nodeId/tools/$toolName" -Body @{ enabled = $true }
}
$detail = Invoke-StudioJsonWithRetry -Method Get -Path "/api/v1/nodes/$nodeId" -Body $null -Attempts 3
$enabledToolNames = @($detail.tools | Where-Object { $_.enabled -eq $true } | ForEach-Object { $_.name })
$missingWorkspaceTools = @($requiredWorkspaceTools | Where-Object { $_ -notin $enabledToolNames })
if ($missingWorkspaceTools.Count -gt 0) {
    Stop-SandboxSetup "Sandbox is missing required enabled workspace tools: $($missingWorkspaceTools -join ', ')"
}

$report = [ordered]@{ ready = $true; nodeId = $nodeId; nodeKind = $detail.node.kind; nodeLabels = @($detail.node.labels); fixtureNodeLabel = $fixtureNodeLabel; toolCount = @($detail.tools).Count; enabledWorkspaceTools = $requiredWorkspaceTools; previousExecutionMode = $previousExecutionMode; executionMode = $settings.mode; workspace = $workspace; configPath = $configPath; processId = $nodeProcess.Id; stdinPath = $stdinPath; stdoutPath = $stdoutPath; stderrPath = $stderrPath }
$reportJson = $report | ConvertTo-Json -Depth 6
if ($AsJson) {
    # Keep the machine-readable contract on stdout even when this script is called
    # from another PowerShell process that captures host output.
    [Console]::Out.WriteLine($reportJson)
} else {
    Write-Host "Coding evaluation sandbox is ready"
    $report.GetEnumerator() | ForEach-Object { Write-Host "- $($_.Key): $($_.Value)" }
}
return
