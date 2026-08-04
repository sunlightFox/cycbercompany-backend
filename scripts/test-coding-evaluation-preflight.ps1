param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("minimal-full-stack", "failed-test-minimal-fix", "split-frontend-backend", "existing-repository-feature", "long-task-recovery")]
    [string]$Scenario,

    [Parameter(Mandatory = $true)]
    [string]$WorkingDirectory,

    [string]$BaseUrl = "http://localhost:8080",
    [string]$NodeId = "auto",
    [string[]]$NodeLabels = @(),
    [string]$ModelProfileId,
    [string[]]$ToolNames = @("fs.*", "project.*", "shell.run", "process.*", "browser.*", "git.*"),
    [switch]$RequireFixtureMarker,
    [switch]$ProbeModel,
    [switch]$AsJson
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "studio-http.ps1")

function Invoke-StudioJson {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [object]$Body = $null
    )

    return Invoke-StudioJsonUtf8 -BaseUrl $script:normalizedBaseUrl -Method $Method -Path $Path -Body $Body -ApiToken $env:AGENT_STUDIO_API_TOKEN
}

function Stop-Preflight {
    param([string]$Message)
    throw "Coding evaluation preflight failed: $Message"
}

function Test-StudioBackend {
    # In the Docker development topology, the backend deliberately binds only to
    # the shared container loopback interface. Vite proxies /api but not /actuator,
    # so accept a successful model API read as proof that this BaseUrl reaches the
    # intended backend when the direct health endpoint is unavailable.
    try {
        $health = Invoke-RestMethod -Method Get -Uri "$($script:normalizedBaseUrl)/actuator/health" -TimeoutSec 10
        if ($health.status -eq "UP") {
            return
        }
    } catch { }
    try {
        $models = @(Invoke-StudioJson -Method Get -Path "/api/v1/models")
        if ($null -eq $models) {
            throw "Model API returned no response."
        }
    } catch {
        Stop-Preflight "Cannot reach an Agent Studio backend at $script:normalizedBaseUrl. $($_.Exception.Message)"
    }
}

function Resolve-SafeWorkingDirectory {
    param([string]$Path)

    if ([string]::IsNullOrWhiteSpace($Path)) {
        Stop-Preflight "WorkingDirectory is required."
    }
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
        Stop-Preflight "WorkingDirectory does not exist or is not a directory: $Path"
    }
    $resolved = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path.TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar)
    $root = [System.IO.Path]::GetPathRoot($resolved).TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar)
    if ([string]::IsNullOrWhiteSpace($resolved) -or $resolved -eq $root) {
        Stop-Preflight "WorkingDirectory must be a dedicated child directory, never a drive root."
    }
    return $resolved
}

function Get-FixtureNodeLabel {
    param([Parameter(Mandatory = $true)][string]$Workspace)

    $normalized = $Workspace.TrimEnd('\', '/').ToLowerInvariant()
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($normalized)
    $algorithm = [System.Security.Cryptography.SHA256]::Create()
    try { $hash = $algorithm.ComputeHash($bytes) } finally { $algorithm.Dispose() }
    $hex = [System.BitConverter]::ToString($hash).Replace('-', '').ToLowerInvariant()
    return "evaluation-fixture-$($hex.Substring(0, 16))"
}

function Test-NodeTools {
    param([object]$NodeDetail, [string[]]$Patterns)

    # 显式两层循环避免嵌套 Where-Object 的 $_ 变量互相遮蔽。
    $availableNames = @($NodeDetail.tools | ForEach-Object { $_.name })
    foreach ($requiredPattern in @($Patterns)) {
        $matched = $false
        foreach ($availableName in $availableNames) {
            if ($availableName -like $requiredPattern) {
                $matched = $true
                break
            }
        }
        if (-not $matched) {
            return $false
        }
    }
    return $true
}

function Test-NodeLabels {
    param([object]$Node, [string[]]$RequiredLabels)

    $actualLabels = @($Node.labels)
    foreach ($requiredLabel in @($RequiredLabels)) {
        if ($requiredLabel -notin $actualLabels) {
            return $false
        }
    }
    return $true
}

function Find-UsableNode {
    param([object[]]$Nodes, [string[]]$RequiredLabels, [string[]]$Patterns)

    foreach ($candidate in @($Nodes)) {
        if ($candidate.enabled -ne $true -or $candidate.status -ne "ONLINE" -or $candidate.kind -ne "SANDBOX") {
            continue
        }
        if (-not (Test-NodeLabels -Node $candidate -RequiredLabels $RequiredLabels)) {
            continue
        }
        $detail = Invoke-StudioJson -Method Get -Path "/api/v1/nodes/$($candidate.id)"
        if (Test-NodeTools -NodeDetail $detail -Patterns $Patterns) {
            return $detail
        }
    }
    return $null
}

# 规范化 URL 后再拼接路径，避免 base URL 末尾的 / 导致意外双斜杠。
$script:normalizedBaseUrl = $BaseUrl.Trim().TrimEnd('/')
if ([string]::IsNullOrWhiteSpace($script:normalizedBaseUrl)) {
    Stop-Preflight "BaseUrl must not be empty."
}

$safeWorkingDirectory = Resolve-SafeWorkingDirectory $WorkingDirectory
$markerPath = Join-Path $safeWorkingDirectory ".agent-studio-evaluation-fixture"
if ($RequireFixtureMarker -and -not (Test-Path -LiteralPath $markerPath -PathType Leaf)) {
    Stop-Preflight "WorkingDirectory is not a fixture created by new-coding-evaluation-fixture.ps1: $safeWorkingDirectory"
}
if (Test-Path -LiteralPath $markerPath -PathType Leaf) {
    $marker = Get-Content -LiteralPath $markerPath -Raw -Encoding UTF8
    # 单引号使末尾 $ 保持正则锚点的字面含义；\r? 兼容 Windows 的 CRLF 行尾。
    $expectedMarkerPattern = '(?m)^scenario={0}\r?$' -f [regex]::Escape($Scenario)
    if ($marker -notmatch $expectedMarkerPattern) {
        Stop-Preflight "Fixture marker scenario does not match the requested scenario."
    }
}
$fixtureNodeLabel = if ($RequireFixtureMarker) { Get-FixtureNodeLabel $safeWorkingDirectory } else { $null }
$requiredNodeLabels = @($NodeLabels)
if (-not [string]::IsNullOrWhiteSpace($fixtureNodeLabel)) {
    $requiredNodeLabels += $fixtureNodeLabel
}

# Direct health is preferred, with the authenticated API as a safe fallback for
# the Vite-proxied local development URL.
Test-StudioBackend

try {
    $models = @(Invoke-StudioJson -Method Get -Path "/api/v1/models")
    $settings = Invoke-StudioJson -Method Get -Path "/api/v1/models/settings"
} catch {
    Stop-Preflight "The healthy HTTP service does not expose the Agent Studio model API. $($_.Exception.Message)"
}

$resolvedModelId = if ([string]::IsNullOrWhiteSpace($ModelProfileId)) { $settings.defaultModelProfileId } else { $ModelProfileId }
if ([string]::IsNullOrWhiteSpace($resolvedModelId)) {
    Stop-Preflight "No modelProfileId was supplied and the backend has no default model."
}
$model = @($models | Where-Object { $_.id -eq $resolvedModelId }) | Select-Object -First 1
if ($null -eq $model) {
    Stop-Preflight "Model profile was not found: $resolvedModelId"
}
if ($model.enabled -ne $true) {
    Stop-Preflight "Model profile is disabled: $resolvedModelId"
}
$requiredCapabilities = @("TEXT", "TOOLS")
$missingCapabilities = @($requiredCapabilities | Where-Object { $_ -notin @($model.capabilities) })
if ($missingCapabilities.Count -gt 0) {
    Stop-Preflight "Model $resolvedModelId is missing required capabilities: $($missingCapabilities -join ', ')"
}

if ($ProbeModel) {
    try {
        $probe = Invoke-StudioJson -Method Post -Path "/api/v1/models/$resolvedModelId/test" -Body @{}
        if ($probe.success -ne $true) {
            Stop-Preflight "Model connectivity probe failed for $resolvedModelId."
        }
    } catch {
        Stop-Preflight "Model connectivity probe failed for $resolvedModelId. $($_.Exception.Message)"
    }
}

try {
    if ($NodeId -eq "auto") {
        $registeredNodes = @(Invoke-StudioJson -Method Get -Path "/api/v1/nodes")
        $node = Find-UsableNode -Nodes $registeredNodes -RequiredLabels $requiredNodeLabels -Patterns $ToolNames
        if ($null -eq $node) {
            Stop-Preflight "No online SANDBOX node matches the requested labels and tool patterns."
        }
    } else {
        $node = Invoke-StudioJson -Method Get -Path "/api/v1/nodes/$NodeId"
        if ($node.node.enabled -ne $true -or $node.node.status -ne "ONLINE") {
            Stop-Preflight "Selected node is not enabled and ONLINE: $NodeId"
        }
        if (-not (Test-NodeLabels -Node $node.node -RequiredLabels $requiredNodeLabels)) {
            Stop-Preflight "Selected node is not bound to this evaluation fixture. Start a fresh sandbox for the requested WorkingDirectory."
        }
        if (-not (Test-NodeTools -NodeDetail $node -Patterns $ToolNames)) {
            Stop-Preflight "Selected node is missing one or more required tool patterns: $($ToolNames -join ', ')"
        }
    }
} catch {
    if ($_.Exception.Message -like "Coding evaluation preflight failed:*") { throw }
    Stop-Preflight "Node readiness check failed. $($_.Exception.Message)"
}

$report = [ordered]@{
    ready = $true
    scenario = $Scenario
    baseUrl = $script:normalizedBaseUrl
    modelProfileId = $resolvedModelId
    modelProbeRan = [bool]$ProbeModel
    # ready=true 仅说明本地夹具、节点与模型配置可用；没有 ProbeModel 时不能把它理解为
    # 外部模型已经真实连通，避免评测人员误把静态配置检查当成端到端验收。
    modelConnectivity = if ($ProbeModel) { "VERIFIED" } else { "NOT_PROBED" }
    nodeId = $node.node.id
    nodeKind = $node.node.kind
    fixtureNodeLabel = $fixtureNodeLabel
    workingDirectory = $safeWorkingDirectory
    fixtureMarkerVerified = (Test-Path -LiteralPath $markerPath -PathType Leaf)
}
if ($AsJson) {
    $report | ConvertTo-Json -Depth 6
} else {
    Write-Host "Coding evaluation preflight passed"
    $report.GetEnumerator() | ForEach-Object { Write-Host "- $($_.Key): $($_.Value)" }
}
