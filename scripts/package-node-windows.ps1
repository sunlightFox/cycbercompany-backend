[CmdletBinding()]
param(
    [ValidateSet('app-image', 'msi')]
    [string]$Type = 'app-image',
    [string]$OutputDirectory = (Join-Path (Get-Location) 'build\windows-node'),
    [string]$Name = 'AgentStudioNode',
    [string]$Server = 'http://127.0.0.1:8080',
    [string]$Workspace = $env:USERPROFILE,
    [switch]$ManualStart
)

$ErrorActionPreference = 'Stop'
$root = (Get-Item (Join-Path $PSScriptRoot '..')).FullName
$serverUri = $null
if (-not [Uri]::TryCreate($Server, [UriKind]::Absolute, [ref]$serverUri) `
        -or $serverUri.Scheme -notin @('http', 'https') `
        -or -not $serverUri.IsLoopback) {
    throw 'Server must be a loopback HTTP(S) URL, for example http://127.0.0.1:8080.'
}
if (-not (Test-Path -LiteralPath $Workspace -PathType Container)) {
    throw "Workspace must be an existing directory: $Workspace"
}
$Workspace = (Resolve-Path -LiteralPath $Workspace).Path
$gradle = Join-Path $root 'gradlew.bat'
if (-not (Test-Path -LiteralPath $gradle -PathType Leaf)) {
    throw "Could not find gradlew.bat under $root"
}

$javaHome = $env:JAVA_HOME
if ([string]::IsNullOrWhiteSpace($javaHome)) {
    throw 'JAVA_HOME must point to a JDK 21 installation (jpackage is part of the JDK).'
}
$jpackage = Join-Path $javaHome 'bin\jpackage.exe'
if (-not (Test-Path -LiteralPath $jpackage -PathType Leaf)) {
    throw "jpackage.exe was not found under JAVA_HOME: $javaHome"
}

Push-Location $root
try {
    & $gradle --no-daemon ':agent-studio-node-java:installDist'
    if ($LASTEXITCODE -ne 0) { throw "Gradle installDist failed with exit code $LASTEXITCODE" }

    $installLib = Join-Path $root 'agent-studio-node-java\build\install\agent-studio-node-java\lib'
    $mainJar = Get-ChildItem -LiteralPath $installLib -Filter 'agent-studio-node-java-*.jar' |
        Where-Object { $_.Name -notmatch '-plain\.jar$' } | Select-Object -First 1
    if ($null -eq $mainJar) { throw "Could not locate the node client jar under $installLib" }

    New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
    $destination = (Resolve-Path -LiteralPath $OutputDirectory).Path
    $arguments = @(
        '--type', $Type,
        '--name', $Name,
        '--input', $installLib,
        '--main-jar', $mainJar.Name,
        '--main-class', 'io.github.yourname.agentstudio.nodeclient.AgentStudioNodeApplication',
        '--dest', $destination,
        '--app-version', '0.0.1',
        '--arguments', 'gui',
        '--arguments', '--server',
        '--arguments', $Server,
        '--arguments', '--workspace',
        '--arguments', $Workspace
    )
    if (-not $ManualStart) {
        $arguments += @('--arguments', '--auto-start')
    }
    if ($Type -eq 'msi') {
        $arguments += @('--win-menu', '--win-shortcut', '--win-dir-chooser')
    }
    & $jpackage @arguments
    if ($LASTEXITCODE -ne 0) { throw "jpackage failed with exit code $LASTEXITCODE" }
    Write-Host "Windows node package created under $destination"
    Write-Host "Server: $Server"
    Write-Host "Workspace: $Workspace"
    Write-Host "Start when the GUI opens: $(-not $ManualStart)"
}
finally {
    Pop-Location
}
