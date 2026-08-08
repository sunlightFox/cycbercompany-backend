<#!
.SYNOPSIS
Hosts the loopback-only launcher used by the Nodes page to start the Windows companion.

.DESCRIPTION
The web application may run inside Docker, while the companion must run as the signed-in
Windows user. This bridge only launches the repository's fixed start-local command and never
accepts an executable or server URL from the browser.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Server,
    [Parameter(Mandatory = $true)][string]$Workspace,
    [Parameter(Mandatory = $true)][string]$ProjectRoot,
    [int]$Port = 8094
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "windows-elevation.ps1")
$Server = $Server.TrimEnd("/")
$Workspace = [System.IO.Path]::GetFullPath($Workspace)
$ProjectRoot = [System.IO.Path]::GetFullPath($ProjectRoot)
$configDir = Join-Path $env:USERPROFILE ".agent-studio-node"
$configPath = Join-Path $configDir "local-executor.json"
$listener = [System.Net.HttpListener]::new()
$listener.Prefixes.Add("http://127.0.0.1:$Port/")
$script:startupProcess = $null

function Write-JsonResponse {
    param($Context, [int]$StatusCode, $Payload)
    $bytes = [System.Text.Encoding]::UTF8.GetBytes(($Payload | ConvertTo-Json -Depth 5 -Compress))
    $Context.Response.StatusCode = $StatusCode
    $Context.Response.ContentType = "application/json; charset=utf-8"
    $origin = $Context.Request.Headers["Origin"]
    if ($origin -and $origin -match '^https?://(localhost|127\.0\.0\.1)(:\d+)?$') {
        $Context.Response.Headers.Add("Access-Control-Allow-Origin", $origin)
    } else {
        $Context.Response.Headers.Add("Access-Control-Allow-Origin", "http://127.0.0.1")
    }
    $Context.Response.Headers.Add("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
    $Context.Response.Headers.Add("Access-Control-Allow-Headers", "Content-Type, Accept")
    if ($StatusCode -eq 204) {
        $Context.Response.ContentLength64 = 0
        $Context.Response.Close()
        return
    }
    $Context.Response.ContentLength64 = $bytes.Length
    $Context.Response.OutputStream.Write($bytes, 0, $bytes.Length)
    $Context.Response.Close()
}

function Read-RequestBody {
    param($Context)
    $maxChars = 4096
    if ($Context.Request.ContentLength64 -gt $maxChars) {
        throw "Launcher request body is too large."
    }
    $reader = [System.IO.StreamReader]::new($Context.Request.InputStream, $Context.Request.ContentEncoding)
    try {
        $buffer = [char[]]::new($maxChars + 1)
        $chunks = [System.Text.StringBuilder]::new()
        while (-not $reader.EndOfStream) {
            $remaining = $maxChars + 1 - $chunks.Length
            if ($remaining -le 0) {
                throw "Launcher request body is too large."
            }
            $count = $reader.Read($buffer, 0, [Math]::Min($buffer.Length, $remaining))
            if ($count -le 0) {
                break
            }
            [void]$chunks.Append($buffer, 0, $count)
            if ($chunks.Length -gt $maxChars) {
                throw "Launcher request body is too large."
            }
        }
        return $chunks.ToString()
    } finally {
        $reader.Dispose()
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

$script:agentStudioApiToken = Resolve-AgentStudioApiToken
if (-not [string]::IsNullOrWhiteSpace($script:agentStudioApiToken)) {
    [Environment]::SetEnvironmentVariable("AGENT_STUDIO_API_TOKEN", $script:agentStudioApiToken, "Process")
}

function Get-AgentStudioApiHeaders {
    if ([string]::IsNullOrWhiteSpace($script:agentStudioApiToken)) {
        return @{}
    }
    return @{ Authorization = "Bearer $script:agentStudioApiToken" }
}

function Test-NodeOnline {
    try {
        $nodes = Invoke-RestMethod -UseBasicParsing -TimeoutSec 3 `
            -Headers (Get-AgentStudioApiHeaders) `
            -Uri "$($Server.TrimEnd('/'))/api/v1/nodes"
        $items = $nodes
        if ($null -ne $nodes.PSObject.Properties["value"]) {
            $items = $nodes.value
        }
        foreach ($node in @($items)) {
            if ($node.kind -eq "MANAGED_LOCAL" -and $node.enabled -and "$($node.status)".ToUpperInvariant() -eq "ONLINE") {
                return $true
            }
        }
    } catch {
    }
    return $false
}

function Get-StartupProcess {
    if ($null -eq $script:startupProcess) {
        return $null
    }
    try {
        if ($script:startupProcess.HasExited) {
            $script:startupProcess = $null
            return $null
        }
        return $script:startupProcess
    } catch {
        $script:startupProcess = $null
        return $null
    }
}

function Resolve-Workspace {
    param([string]$Requested)
    $candidate = if ([string]::IsNullOrWhiteSpace($Requested)) { $Workspace } else { $Requested }
    $resolved = [System.IO.Path]::GetFullPath($candidate)
    if (-not [System.IO.Directory]::Exists($resolved)) {
        throw "Workspace must be an existing directory: $resolved"
    }
    return $resolved
}

function Start-LocalNode {
    param([string]$NodeWorkspace)
    $gradle = Join-Path $ProjectRoot "gradlew.bat"
    if (-not [System.IO.File]::Exists($gradle)) {
        throw "The local node launcher was not found: $gradle"
    }
    New-Item -ItemType Directory -Force -Path $configDir | Out-Null
    $apiTokenHandoffFile = $null
    $startedProcess = $false
    $expectedWindowsUser = if (Test-AgentStudioAdministrator) { $null } else { Get-AgentStudioWindowsUserName }
    try {
        if (-not (Test-AgentStudioAdministrator) -and -not [string]::IsNullOrWhiteSpace($script:agentStudioApiToken)) {
            $apiTokenHandoffFile = New-AgentStudioSecretHandoffFile `
                -Value $script:agentStudioApiToken `
                -NamePrefix "agent-studio-api-token"
        }
        $bootstrapCommand = @()
        if (-not [string]::IsNullOrWhiteSpace($expectedWindowsUser) -or -not [string]::IsNullOrWhiteSpace($apiTokenHandoffFile)) {
            $bootstrapCommand += ". $(ConvertTo-AgentStudioPowerShellLiteral (Join-Path $PSScriptRoot "windows-elevation.ps1"))"
        }
        if (-not [string]::IsNullOrWhiteSpace($expectedWindowsUser)) {
            $bootstrapCommand += "Assert-AgentStudioWindowsUser -ExpectedUser $(ConvertTo-AgentStudioPowerShellLiteral $expectedWindowsUser)"
        }
        if (-not [string]::IsNullOrWhiteSpace($apiTokenHandoffFile)) {
            $bootstrapCommand += "`$apiTokenFile = $(ConvertTo-AgentStudioPowerShellLiteral $apiTokenHandoffFile)"
            $bootstrapCommand += "`$apiToken = Read-AgentStudioSecretHandoffFile -Path `$apiTokenFile"
            $bootstrapCommand += "if (-not [string]::IsNullOrWhiteSpace(`$apiToken)) { [Environment]::SetEnvironmentVariable('AGENT_STUDIO_API_TOKEN', `$apiToken, 'Process') }"
        }
        $arguments = "start-local --server $Server --workspace `"$NodeWorkspace`" --config `"$configPath`""
        $gradleArgs = "--args=$arguments"
        $bootstrapCommand += "& $(ConvertTo-AgentStudioPowerShellLiteral $gradle) --no-daemon ':agent-studio-node-java:run' $(ConvertTo-AgentStudioPowerShellLiteral $gradleArgs)"
        $command = $bootstrapCommand -join "; "
        $startParameters = @{
            FilePath = "powershell.exe"
            ArgumentList = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", $command)
            WorkingDirectory = $ProjectRoot
            WindowStyle = "Hidden"
            PassThru = $true
        }
        if (-not (Test-AgentStudioAdministrator)) {
            $startParameters.Verb = "RunAs"
        }
        $process = Start-Process @startParameters
        $startedProcess = $true
        return $process
    } finally {
        if (-not $startedProcess -and -not [string]::IsNullOrWhiteSpace($apiTokenHandoffFile)) {
            Remove-Item -LiteralPath $apiTokenHandoffFile -Force -ErrorAction SilentlyContinue
        }
    }
}

try {
    $listener.Start()
    while ($listener.IsListening) {
        $context = $listener.GetContext()
        try {
            $origin = $context.Request.Headers["Origin"]
            if ($origin -and $origin -notmatch '^https?://(localhost|127\.0\.0\.1)(:\d+)?$') {
                Write-JsonResponse $context 403 @{ error = "Only a local web origin may use the launcher." }
                continue
            }
            if ($context.Request.HttpMethod -eq "OPTIONS") {
                Write-JsonResponse $context 204 @{}
                continue
            }
            switch ($context.Request.Url.AbsolutePath) {
                "/health" {
                    if ($context.Request.HttpMethod -ne "GET") {
                        Write-JsonResponse $context 405 @{ error = "Use GET for launcher health." }
                        continue
                    }
                    $online = Test-NodeOnline
                    $startupProcess = if ($online) { $null } else { Get-StartupProcess }
                    Write-JsonResponse $context 200 @{
                        service = "agent-studio-local-executor-launcher"
                        pid = $PID
                        port = $Port
                        server = $Server
                        workspace = $Workspace
                        projectRoot = $ProjectRoot
                        available = $true
                        online = $online
                        starting = $null -ne $startupProcess
                        startupPid = if ($null -ne $startupProcess) { $startupProcess.Id } else { $null }
                        elevated = (Test-AgentStudioAdministrator)
                    }
                    continue
                }
                "/start" {
                    if ($context.Request.HttpMethod -ne "POST") {
                        Write-JsonResponse $context 405 @{ error = "Use POST to start the local executor." }
                        continue
                    }
                    $body = if ($context.Request.HasEntityBody) { Read-RequestBody $context } else { "{}" }
                    $request = if ($body.Trim()) { $body | ConvertFrom-Json } else { [pscustomobject]@{} }
                    if (Test-NodeOnline) {
                        Write-JsonResponse $context 200 @{
                            started = $false
                            online = $true
                            elevated = (Test-AgentStudioAdministrator)
                            message = "Local executor is already online."
                        }
                        continue
                    }
                    $startupProcess = Get-StartupProcess
                    if ($null -ne $startupProcess) {
                        Write-JsonResponse $context 202 @{
                            started = $false
                            starting = $true
                            online = $false
                            elevated = (Test-AgentStudioAdministrator)
                            pid = $startupProcess.Id
                            message = "Local executor is already starting."
                        }
                        continue
                    }
                    $nodeWorkspace = Resolve-Workspace $request.workspace
                    $process = Start-LocalNode $nodeWorkspace
                    $script:startupProcess = $process
                    Write-JsonResponse $context 202 @{
                        started = $true
                        starting = $true
                        online = $false
                        elevated = (Test-AgentStudioAdministrator)
                        pid = $process.Id
                        message = "Local executor is starting with an administrator token."
                    }
                    continue
                }
                default {
                    Write-JsonResponse $context 404 @{ error = "Unknown launcher endpoint." }
                    continue
                }
            }
        } catch {
            try { Write-JsonResponse $context 400 @{ error = $_.Exception.Message } } catch { }
        }
    }
} finally {
    if ($listener.IsListening) { $listener.Stop() }
    $listener.Close()
}
