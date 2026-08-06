<#
.SYNOPSIS
Stops the personal local launcher started by start-personal-local.ps1.
#>
[CmdletBinding()]
param(
    [string]$ExpectedWindowsUser
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "windows-elevation.ps1")

if (-not (Test-AgentStudioAdministrator)) {
    $exitCode = Invoke-AgentStudioElevatedScript -ScriptPath $PSCommandPath -Parameters @{
        ExpectedWindowsUser = Get-AgentStudioWindowsUserName
    }
    exit $exitCode
}

Assert-AgentStudioWindowsUser -ExpectedUser $ExpectedWindowsUser

$projectRoot = Split-Path -Parent $PSScriptRoot
$composeRoot = Join-Path (Split-Path -Parent $projectRoot) "spring-agent-studio-web"
$statePath = Join-Path $env:USERPROFILE ".agent-studio-node\personal-local.state.json"
$launcherPort = 8094

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
    param(
        $ProcessId,
        [string]$Label,
        [string[]]$ExpectedCommandPatterns
    )
    if ($null -eq $ProcessId) {
        return
    }
    try {
        $numericId = [int]$ProcessId
        $process = Get-CimInstance Win32_Process -Filter "ProcessId = $numericId" -ErrorAction Stop
        if ($null -eq $process) {
            Write-Host "Skipped $Label process $ProcessId; it is no longer running."
            return
        }
        $identityMatches = $true
        foreach ($pattern in @($ExpectedCommandPatterns)) {
            if ($pattern -and $process.CommandLine -notlike $pattern) {
                $identityMatches = $false
                break
            }
        }
        if (-not $identityMatches) {
            Write-Host "Skipped $Label process $ProcessId; command identity did not match the saved process."
            return
        }
        Stop-Process -Id $numericId -Force -ErrorAction Stop
        Write-Host "Stopped $Label process $ProcessId."
    } catch {
        Write-Host "Skipped $Label process $ProcessId."
    }
}

if (Test-Path $statePath) {
    $state = Get-Content $statePath -Raw | ConvertFrom-Json
    Stop-ManagedProcess `
        -ProcessId $state.nodePid `
        -Label "node" `
        -ExpectedCommandPatterns @("*start-local*", "*agent-studio-node-java*")
    Stop-ManagedProcess `
        -ProcessId $state.launcherPid `
        -Label "local launcher" `
        -ExpectedCommandPatterns @("*local-executor-launcher.ps1*", "*-Port $launcherPort*")
    Remove-Item $statePath -Force -ErrorAction SilentlyContinue
}

Write-Host "Personal local launcher stopped."
