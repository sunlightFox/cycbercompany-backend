<#!
.SYNOPSIS
Starts the Spring Agent Studio compose stack with its implicit companion for the current Windows desktop.

.DESCRIPTION
The companion stays outside the backend process. It therefore operates on this signed-in
user's files and desktop rather than a Docker container or a remote server filesystem.
#>
[CmdletBinding()]
param(
    [string]$Server = "http://127.0.0.1:8083",
    [string]$Workspace = (Get-Location).Path,
    [switch]$SkipBackend,
    [switch]$Foreground
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$composeRoot = Join-Path (Split-Path -Parent $projectRoot) "spring-agent-studio-web"
$workspacePath = [System.IO.Path]::GetFullPath($Workspace)
$nodeConfigDir = Join-Path $env:USERPROFILE ".agent-studio-node"
$statePath = Join-Path $nodeConfigDir "personal-local.state.json"
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$logDir = Join-Path $projectRoot "logs"
New-Item -ItemType Directory -Force -Path $nodeConfigDir | Out-Null
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

if (-not [System.IO.Directory]::Exists($composeRoot)) {
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

$nodeOnline = {
    param([string]$ServerUrl)
    try {
        $nodes = Invoke-RestMethod -TimeoutSec 3 -Uri "$($ServerUrl.TrimEnd('/'))/api/v1/nodes"
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

$healthUrl = "$($Server.TrimEnd('/'))/actuator/health"
$startedBackend = $false
$backendProcess = $null
$startedNode = $false
$nodeProcess = $null

function Save-State {
    param(
        $BackendPid,
        $NodePid
    )
    $state = [ordered]@{
        server = $Server
        workspace = $workspacePath
        backendPid = $BackendPid
        nodePid = $NodePid
        backendOutLog = $backendOut
        backendErrLog = $backendErr
        nodeOutLog = $nodeOut
        nodeErrLog = $nodeErr
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
    $nodeArgs = @(
        ':agent-studio-node-java:run',
        "--args=start-local --server $NodeServer --workspace `"$workspacePath`" --config `"$($nodeConfigDir)\local-executor.json`""
    )
    return Start-Process -FilePath (Join-Path $projectRoot "gradlew.bat") `
        -ArgumentList $nodeArgs `
        -WorkingDirectory $projectRoot `
        -RedirectStandardOutput $nodeOut `
        -RedirectStandardError $nodeErr `
        -WindowStyle Hidden `
        -PassThru
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

if (-not $SkipBackend -and -not (Test-AgentStudioHealth -HealthUrl $healthUrl)) {
    $backendProcess = Start-Backend
    $startedBackend = $true

    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    while ([DateTime]::UtcNow -lt $deadline -and -not (Test-AgentStudioHealth -HealthUrl $healthUrl)) {
        Start-Sleep -Seconds 1
    }
}

if (-not (Test-AgentStudioHealth -HealthUrl $healthUrl)) {
    Stop-BackendStack
    throw "Agent Studio backend did not become healthy at $healthUrl"
}

if (& $nodeOnline $Server) {
    $existingNodePid = Get-NodeProcessId -NodeServer $Server
    if ($startedBackend) {
        Save-State -BackendPid $null -NodePid $existingNodePid
    } elseif ($existingNodePid) {
        Save-State -BackendPid $null -NodePid $existingNodePid
    }
    Write-Host "Local executor is already online at $Server."
    return
}

if ($Foreground) {
    try {
        Push-Location $projectRoot
        & .\gradlew.bat ':agent-studio-node-java:run' "--args=start-local --server $Server --workspace `"$workspacePath`" --config `"$($nodeConfigDir)\local-executor.json`""
    } finally {
        Pop-Location
        Stop-BackendStack
    }
    return
}

$nodeProcess = Start-Node -NodeServer $Server
$startedNode = $true

$deadline = [DateTime]::UtcNow.AddSeconds(120)
while ([DateTime]::UtcNow -lt $deadline -and -not (& $nodeOnline $Server)) {
    Start-Sleep -Seconds 1
}

if (-not (& $nodeOnline $Server)) {
    if ($startedNode -and $nodeProcess -and -not $nodeProcess.HasExited) {
        Stop-Process -Id $nodeProcess.Id
    }
    Stop-BackendStack
    Clear-State
    throw "Agent Studio local executor did not become online at $Server"
}

if ($startedBackend) {
    Save-State -BackendPid $null -NodePid $nodeProcess.Id
} else {
    Save-State -BackendPid $null -NodePid $nodeProcess.Id
}
Write-Host "Agent Studio backend and local executor are running."
Write-Host "Server: $Server"
Write-Host "Workspace: $workspacePath"
Write-Host "Stop script: .\scripts\stop-personal-local.ps1"
