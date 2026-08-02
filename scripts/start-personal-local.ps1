<#!
.SYNOPSIS
Starts Spring Agent Studio with its implicit companion for the current Windows desktop.

.DESCRIPTION
The companion stays outside the backend process. It therefore operates on this signed-in
user's files and desktop rather than a Docker container or a remote server filesystem.
#>
[CmdletBinding()]
param(
    [string]$Server = "http://127.0.0.1:8080",
    [string]$Workspace = (Get-Location).Path,
    [switch]$SkipBackend
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$workspacePath = [System.IO.Path]::GetFullPath($Workspace)

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

$healthUrl = "$($Server.TrimEnd('/'))/actuator/health"
$startedBackend = $false
$backendProcess = $null

if (-not $SkipBackend -and -not (Test-AgentStudioHealth -HealthUrl $healthUrl)) {
    $backendProcess = Start-Process -FilePath (Join-Path $projectRoot "gradlew.bat") `
        -ArgumentList "bootRun" `
        -WorkingDirectory $projectRoot `
        -WindowStyle Hidden `
        -PassThru
    $startedBackend = $true

    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    while ([DateTime]::UtcNow -lt $deadline -and -not (Test-AgentStudioHealth -HealthUrl $healthUrl)) {
        Start-Sleep -Seconds 1
    }
}

if (-not (Test-AgentStudioHealth -HealthUrl $healthUrl)) {
    if ($startedBackend -and $backendProcess -and -not $backendProcess.HasExited) {
        Stop-Process -Id $backendProcess.Id
    }
    throw "Agent Studio backend did not become healthy at $healthUrl"
}

try {
    Push-Location $projectRoot
    & .\gradlew.bat ':agent-studio-node-java:run' "--args=start-local --server $Server --workspace `"$workspacePath`""
} finally {
    Pop-Location
    if ($startedBackend -and $backendProcess -and -not $backendProcess.HasExited) {
        Stop-Process -Id $backendProcess.Id
    }
}
