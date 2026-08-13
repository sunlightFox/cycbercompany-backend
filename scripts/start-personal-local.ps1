<#!
.SYNOPSIS
Starts the CycberCompany compose stack with its implicit companion for the current Windows desktop.

.DESCRIPTION
The companion stays outside the backend process. It therefore operates on this signed-in
user's files and desktop rather than a Docker container or a remote server filesystem.
#>
[CmdletBinding()]
param(
    [string]$Server = "http://127.0.0.1:8083",
    [string]$Workspace = (Get-Location).Path,
    [switch]$SkipBackend,
    [switch]$Foreground,
    [switch]$NoElevation,
    [string]$ApiTokenFile,
    [string]$ExpectedWindowsUser
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "windows-elevation.ps1")

if (-not $NoElevation -and -not (Test-AgentStudioAdministrator)) {
    $apiTokenHandoffFile = $null
    try {
        $processToken = [Environment]::GetEnvironmentVariable("AGENT_STUDIO_API_TOKEN", "Process")
        if (-not [string]::IsNullOrWhiteSpace($processToken)) {
            $apiTokenHandoffFile = New-AgentStudioSecretHandoffFile `
                -Value $processToken `
                -NamePrefix "agent-studio-api-token"
        }
        $exitCode = Invoke-AgentStudioElevatedScript -ScriptPath $PSCommandPath -Parameters @{
            Server = $Server
            Workspace = $Workspace
            SkipBackend = [bool]$SkipBackend
            Foreground = [bool]$Foreground
            NoElevation = $true
            ApiTokenFile = $apiTokenHandoffFile
            ExpectedWindowsUser = Get-AgentStudioWindowsUserName
        }
    } finally {
        if (-not [string]::IsNullOrWhiteSpace($apiTokenHandoffFile)) {
            Remove-Item -LiteralPath $apiTokenHandoffFile -Force -ErrorAction SilentlyContinue
        }
    }
    exit $exitCode
}

Assert-AgentStudioWindowsUser -ExpectedUser $ExpectedWindowsUser

$projectRoot = Split-Path -Parent $PSScriptRoot
$composeRoot = Join-Path (Split-Path -Parent $projectRoot) "cycbercompany-web"
$workspacePath = [System.IO.Path]::GetFullPath($Workspace)
$nodeConfigDir = Join-Path $env:USERPROFILE ".agent-studio-node"
$statePath = Join-Path $nodeConfigDir "personal-local.state.json"
$launcherPort = 8094
$launcherScript = Join-Path $PSScriptRoot "local-executor-launcher.ps1"
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$logDir = Join-Path $projectRoot "logs"
New-Item -ItemType Directory -Force -Path $nodeConfigDir | Out-Null
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

if (-not $SkipBackend -and -not [System.IO.Directory]::Exists($composeRoot)) {
    throw "Compose workspace not found: $composeRoot"
}

if (-not [System.IO.Directory]::Exists($workspacePath)) {
    throw "Workspace must be an existing directory: $workspacePath"
}

function Test-AgentStudioHealth {
    param([string]$HealthUrl)
    try {
        $response = Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 -Uri $HealthUrl
        return $response.StatusCode -ge 200 -and $response.StatusCode -lt 300
    } catch {
        return $false
    }
}

function Assert-DockerAvailable {
    try {
        $dockerVersion = & docker version --format '{{.Server.Version}}' 2>$null
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($dockerVersion)) {
            throw "docker version returned exit code $LASTEXITCODE"
        }
    } catch {
        throw "Docker is not reachable. Start Docker Desktop and make sure the docker CLI works before running personal-local mode."
    }
}

function Resolve-AgentStudioApiToken {
    foreach ($scope in @("Process", "User", "Machine")) {
        $value = [Environment]::GetEnvironmentVariable("AGENT_STUDIO_API_TOKEN", $scope)
        if (-not [string]::IsNullOrWhiteSpace($value)) {
            return $value.Trim()
        }
    }
    return ""
}

$script:agentStudioApiToken = Read-AgentStudioSecretHandoffFile -Path $ApiTokenFile
if ([string]::IsNullOrWhiteSpace($script:agentStudioApiToken)) {
    $script:agentStudioApiToken = Resolve-AgentStudioApiToken
}
if (-not [string]::IsNullOrWhiteSpace($script:agentStudioApiToken)) {
    [Environment]::SetEnvironmentVariable("AGENT_STUDIO_API_TOKEN", $script:agentStudioApiToken, "Process")
}

function Get-AgentStudioApiHeaders {
    if ([string]::IsNullOrWhiteSpace($script:agentStudioApiToken)) {
        return @{}
    }
    return @{ Authorization = "Bearer $script:agentStudioApiToken" }
}

function Assert-AgentStudioApiReachable {
    param([string]$ServerUrl)
    $nodesUrl = "$($ServerUrl.TrimEnd('/'))/api/v1/nodes"
    try {
        $response = Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 -Headers (Get-AgentStudioApiHeaders) -Uri $nodesUrl
        if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300) {
            return
        }
        throw "unexpected HTTP $($response.StatusCode)"
    } catch {
        $statusCode = $null
        try {
            if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
                $statusCode = [int]$_.Exception.Response.StatusCode
            }
        } catch {
            $statusCode = $null
        }
        if ($statusCode -eq 401 -or $statusCode -eq 403) {
            throw "CycberCompany backend is healthy at $ServerUrl, but $nodesUrl rejected the request with HTTP $statusCode. If TOKEN mode is enabled, set AGENT_STUDIO_API_TOKEN in the current process, User environment, Machine environment, or compose environment."
        }
        throw "The health endpoint is reachable at $ServerUrl, but $nodesUrl is not a CycberCompany API endpoint. Check -Server and make sure the expected backend is running."
    }
}

$nodeOnline = {
    param([string]$ServerUrl)
    try {
        $nodes = Invoke-RestMethod -TimeoutSec 3 -Headers (Get-AgentStudioApiHeaders) `
            -Uri "$($ServerUrl.TrimEnd('/'))/api/v1/nodes"
        $items = $nodes
        if ($null -ne $nodes.PSObject.Properties["value"]) {
            $items = $nodes.value
        }
        foreach ($node in @($items)) {
            if ($node.kind -eq "MANAGED_LOCAL" -and $node.enabled -and ($node.status -as [string]).ToUpperInvariant() -eq "ONLINE") {
                return $true
            }
        }
        return $false
    } catch {
        return $false
    }
}

$backendOut = Join-Path $logDir "personal-local-compose-$timestamp.out.log"
$backendErr = Join-Path $logDir "personal-local-compose-$timestamp.err.log"
$nodeOut = Join-Path $logDir "personal-local-node-$timestamp.out.log"
$nodeErr = Join-Path $logDir "personal-local-node-$timestamp.err.log"
$launcherOut = Join-Path $logDir "personal-local-launcher-$timestamp.out.log"
$launcherErr = Join-Path $logDir "personal-local-launcher-$timestamp.err.log"

$healthUrl = "$($Server.TrimEnd('/'))/actuator/health"
$startedBackend = $false
$backendProcess = $null
$startedNode = $false
$nodeProcess = $null

function Save-State {
    param(
        $BackendPid,
        $NodePid,
        $LauncherPid
    )
    $backendOutLog = if (Test-Path $backendOut) { $backendOut } else { $null }
    $backendErrLog = if (Test-Path $backendErr) { $backendErr } else { $null }
    $nodeOutLog = if (Test-Path $nodeOut) { $nodeOut } else { $null }
    $nodeErrLog = if (Test-Path $nodeErr) { $nodeErr } else { $null }
    $state = [ordered]@{
        server = $Server
        workspace = $workspacePath
        backendPid = $BackendPid
        nodePid = $NodePid
        backendOutLog = $backendOutLog
        backendErrLog = $backendErrLog
        nodeOutLog = $nodeOutLog
        nodeErrLog = $nodeErrLog
        launcherPid = $LauncherPid
        launcherOutLog = if (Test-Path $launcherOut) { $launcherOut } else { $null }
        launcherErrLog = if (Test-Path $launcherErr) { $launcherErr } else { $null }
        nodePrivilege = if (Test-AgentStudioAdministrator) { "Administrator" } else { "Standard user" }
        startedAt = (Get-Date).ToString("o")
    }
    $state | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 $statePath
}

function Clear-State {
    Remove-Item $statePath -Force -ErrorAction SilentlyContinue
}

function Start-Backend {
    return Start-Process -FilePath "docker" `
        -ArgumentList @("compose", "up", "-d", "--build") `
        -WorkingDirectory $composeRoot `
        -RedirectStandardOutput $backendOut `
        -RedirectStandardError $backendErr `
        -WindowStyle Hidden `
        -PassThru
}

function Start-Node {
    param([string]$NodeServer)
    $gradle = Join-Path $projectRoot "gradlew.bat"
    $nodeArgs = "--args=start-local --server $NodeServer --workspace `"$workspacePath`" --config `"$($nodeConfigDir)\local-executor.json`""
    $nodeCommand = "& $(ConvertTo-AgentStudioPowerShellLiteral $gradle) --no-daemon ':agent-studio-node-java:run' $(ConvertTo-AgentStudioPowerShellLiteral $nodeArgs)"
    return Start-Process -FilePath "powershell.exe" `
        -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-Command", $nodeCommand) `
        -WorkingDirectory $projectRoot `
        -RedirectStandardOutput $nodeOut `
        -RedirectStandardError $nodeErr `
        -WindowStyle Hidden `
        -PassThru
}

function Test-LauncherHealth {
    $health = Get-LauncherHealth
    return $null -ne $health
}

function Get-LauncherHealth {
    try {
        $response = Invoke-RestMethod -TimeoutSec 2 -Uri "http://127.0.0.1:$launcherPort/health"
        if ($null -eq $response -or $response.service -ne "agent-studio-local-executor-launcher") {
            return $null
        }
        if ($null -eq $response.pid -or $null -eq $response.port -or [int]$response.port -ne $launcherPort) {
            return $null
        }
        return $response
    } catch {
        return $null
    }
}

function Get-LauncherProcessId {
    $health = Get-LauncherHealth
    if ($null -ne $health) {
        try {
            $process = Get-Process -Id ([int]$health.pid) -ErrorAction Stop
            $command = (Get-CimInstance Win32_Process -Filter "ProcessId = $([int]$health.pid)" -ErrorAction Stop).CommandLine
            $identityMatches = $null -ne $process -and $command -like "*local-executor-launcher.ps1*" -and $command -like "*-Port $launcherPort*"
            if ($identityMatches) {
                return [int]$health.pid
            }
        } catch {
        }
    }
    try {
        $match = Get-CimInstance Win32_Process |
            Where-Object {
                $_.CommandLine -like "*local-executor-launcher.ps1*" -and
                $_.CommandLine -like "*-Port $launcherPort*"
            } |
            Select-Object -First 1
        return $match.ProcessId
    } catch {
        return $null
    }
}

function Test-LauncherConfiguration {
    param($Health)
    if ($null -eq $Health) {
        return $false
    }
    try {
        return ([string]$Health.server).TrimEnd("/") -eq $Server.TrimEnd("/") `
            -and [System.IO.Path]::GetFullPath([string]$Health.workspace) -eq $workspacePath `
            -and [System.IO.Path]::GetFullPath([string]$Health.projectRoot) -eq $projectRoot
    } catch {
        return $false
    }
}

function Start-Launcher {
    $existingHealth = Get-LauncherHealth
    if ($null -ne $existingHealth) {
        $existingPid = Get-LauncherProcessId
        if (-not $existingPid) {
            throw "A local executor launcher is responding on port $launcherPort, but its process identity could not be confirmed."
        }
        if (Test-LauncherConfiguration -Health $existingHealth) {
            return $existingPid
        }
        Write-Host "Restarting the local executor launcher because its server or workspace configuration changed."
        Stop-Process -Id ([int]$existingPid) -Force -ErrorAction Stop
        $restartDeadline = [DateTime]::UtcNow.AddSeconds(10)
        while ([DateTime]::UtcNow -lt $restartDeadline -and $null -ne (Get-LauncherHealth)) {
            Start-Sleep -Milliseconds 250
        }
        if ($null -ne (Get-LauncherHealth)) {
            throw "The existing local executor launcher did not stop cleanly on port $launcherPort."
        }
    }
    if (-not (Test-Path -LiteralPath $launcherScript -PathType Leaf)) {
        throw "Local executor launcher was not found: $launcherScript"
    }
    $launcherCommand = "& $(ConvertTo-AgentStudioPowerShellLiteral $launcherScript) " +
        "-Server $(ConvertTo-AgentStudioPowerShellLiteral $Server) " +
        "-Workspace $(ConvertTo-AgentStudioPowerShellLiteral $workspacePath) " +
        "-ProjectRoot $(ConvertTo-AgentStudioPowerShellLiteral $projectRoot) " +
        "-Port $launcherPort"
    $process = Start-Process -FilePath "powershell.exe" `
        -ArgumentList @(
            "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
            "-Command", $launcherCommand) `
        -WorkingDirectory $projectRoot `
        -RedirectStandardOutput $launcherOut `
        -RedirectStandardError $launcherErr `
        -WindowStyle Hidden `
        -PassThru
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    while ([DateTime]::UtcNow -lt $deadline -and -not (Test-LauncherHealth)) {
        Start-Sleep -Milliseconds 250
    }
    if (-not (Test-LauncherHealth)) {
        if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force }
        throw "Local executor launcher did not become ready on port $launcherPort"
    }
    return $(if ($process.HasExited) { Get-LauncherProcessId } else { $process.Id })
}

function Stop-Launcher {
    $launcherPid = Get-LauncherProcessId
    if ($launcherPid) {
        Stop-Process -Id ([int]$launcherPid) -Force -ErrorAction SilentlyContinue
    }
}

function Get-NodeProcessId {
    param([string]$NodeServer)
    try {
        $match = Get-CimInstance Win32_Process |
            Where-Object {
                $_.CommandLine -like "*agent-studio-node-java*" -and
                $_.CommandLine -like "*start-local*" -and
                $_.CommandLine -like "*--server $NodeServer*"
            } |
            Select-Object -First 1
        return $match.ProcessId
    } catch {
        return $null
    }
}

function Stop-BackendStack {
    if (-not $startedBackend) {
        return
    }
    try {
        Push-Location $composeRoot
        & docker compose down --remove-orphans | Out-Null
    } catch {
    } finally {
        Pop-Location
    }
}

function Get-ProcessExitCode {
    param($Process)
    if ($null -eq $Process) {
        return $null
    }
    try {
        $Process.Refresh()
        if ($Process.HasExited) {
            return $Process.ExitCode
        }
    } catch {
    }
    return $null
}

if (-not $SkipBackend -and -not (Test-AgentStudioHealth -HealthUrl $healthUrl)) {
    Assert-DockerAvailable
    $backendProcess = Start-Backend
    $startedBackend = $true

    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    while ([DateTime]::UtcNow -lt $deadline -and -not (Test-AgentStudioHealth -HealthUrl $healthUrl)) {
        $backendExitCode = Get-ProcessExitCode -Process $backendProcess
        if ($null -ne $backendExitCode -and $backendExitCode -ne 0) {
            Stop-BackendStack
            throw "Docker compose failed with exit code $backendExitCode. See logs: $backendOut and $backendErr"
        }
        Start-Sleep -Seconds 1
    }
}

if (-not (Test-AgentStudioHealth -HealthUrl $healthUrl)) {
    Stop-BackendStack
    if ($SkipBackend) {
    throw "CycberCompany backend did not become healthy at $healthUrl. -SkipBackend was set, so start or select an existing backend with -Server."
    }
    throw "CycberCompany backend did not become healthy at $healthUrl. See logs: $backendOut and $backendErr"
}

try {
    Assert-AgentStudioApiReachable -ServerUrl $Server
} catch {
    Stop-BackendStack
    Clear-State
    throw
}

try {
    $launcherPid = Start-Launcher
} catch {
    Stop-BackendStack
    Clear-State
    throw
}

if (& $nodeOnline $Server) {
    $existingNodePid = Get-NodeProcessId -NodeServer $Server
    $existingState = $null
    if (Test-Path -LiteralPath $statePath) {
        try {
            $existingState = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
        } catch {
            $existingState = $null
        }
    }
    $managedExistingNode = $null -ne $existingState -and $existingState.server -eq $Server
    $needsElevationRestart = (Test-AgentStudioAdministrator) -and $existingNodePid -and $managedExistingNode `
        -and $existingState.nodePrivilege -ne "Administrator"
    if ($needsElevationRestart) {
        Write-Host "Restarting the existing personal node to apply the administrator token."
        Stop-Process -Id ([int]$existingNodePid) -Force
        $restartDeadline = [DateTime]::UtcNow.AddSeconds(15)
        while ([DateTime]::UtcNow -lt $restartDeadline -and (& $nodeOnline $Server)) {
            Start-Sleep -Milliseconds 500
        }
    } else {
        Save-State -BackendPid $null -NodePid $existingNodePid -LauncherPid $launcherPid
        Write-Host "Local executor is already online at $Server."
        return
    }
}

if ($Foreground) {
    try {
        Push-Location $projectRoot
        & .\gradlew.bat --no-daemon ':agent-studio-node-java:run' "--args=start-local --server $Server --workspace `"$workspacePath`" --config `"$($nodeConfigDir)\local-executor.json`""
    } finally {
        Pop-Location
        Stop-BackendStack
        Stop-Launcher
    }
    return
}

$nodeProcess = Start-Node -NodeServer $Server
$startedNode = $true

$deadline = [DateTime]::UtcNow.AddSeconds(120)
while ([DateTime]::UtcNow -lt $deadline -and -not (& $nodeOnline $Server)) {
    $nodeExitCode = Get-ProcessExitCode -Process $nodeProcess
    if ($null -ne $nodeExitCode) {
        Stop-Launcher
        Stop-BackendStack
        Clear-State
    throw "CycberCompany local executor exited before it became online. Exit code: $nodeExitCode. See logs: $nodeOut and $nodeErr"
    }
    Start-Sleep -Seconds 1
}

if (-not (& $nodeOnline $Server)) {
    if ($startedNode -and $nodeProcess -and -not $nodeProcess.HasExited) {
        Stop-Process -Id $nodeProcess.Id
    }
    Stop-Launcher
    Stop-BackendStack
    Clear-State
    throw "CycberCompany local executor did not become online at $Server. See logs: $nodeOut and $nodeErr"
}

$actualNodePid = Get-NodeProcessId -NodeServer $Server
Save-State -BackendPid $null -NodePid $(if ($actualNodePid) { $actualNodePid } else { $nodeProcess.Id }) -LauncherPid $launcherPid
Write-Host "CycberCompany backend and local executor are running."
Write-Host "Server: $Server"
Write-Host "Workspace: $workspacePath"
$nodePrivilege = if (Test-AgentStudioAdministrator) { "Administrator" } else { "Standard user" }
Write-Host "Node privilege: $nodePrivilege (current Windows user)"
Write-Host "Stop script: .\scripts\stop-personal-local.ps1"
