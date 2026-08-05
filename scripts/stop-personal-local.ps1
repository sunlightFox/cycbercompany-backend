<#
.SYNOPSIS
Stops the personal local launcher started by start-personal-local.ps1.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$composeRoot = Join-Path (Split-Path -Parent $projectRoot) "spring-agent-studio-web"
$statePath = Join-Path $env:USERPROFILE ".agent-studio-node\personal-local.state.json"

if ([System.IO.Directory]::Exists($composeRoot)) {
    Push-Location $composeRoot
    try {
        & docker compose down --remove-orphans | Out-Null
    } catch {
    } finally {
        Pop-Location
    }
} else {
    Write-Host "Skipped Docker stack; compose workspace not found: $composeRoot"
}

function Stop-ManagedProcess {
    param($ProcessId, [string]$Label)
    if ($null -eq $ProcessId) {
        return
    }
    try {
        Stop-Process -Id ([int]$ProcessId) -Force -ErrorAction Stop
        Write-Host "Stopped $Label process $ProcessId."
    } catch {
        Write-Host "Skipped $Label process $ProcessId."
    }
}

if (Test-Path $statePath) {
    $state = Get-Content $statePath -Raw | ConvertFrom-Json
    Stop-ManagedProcess -ProcessId $state.nodePid -Label "node"
    Remove-Item $statePath -Force -ErrorAction SilentlyContinue
}

Write-Host "Personal local launcher stopped."
