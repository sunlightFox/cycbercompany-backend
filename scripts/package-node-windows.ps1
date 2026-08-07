[CmdletBinding()]
param(
    [ValidateSet('app-image', 'msi')]
    [string]$Type = 'app-image',
    [string]$OutputDirectory = (Join-Path (Get-Location) 'build\windows-node'),
    [string]$Name = 'AgentStudioNode',
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string]$Version = '0.0.1',
    [string]$Server = 'http://127.0.0.1:8080',
    [string]$Workspace = $env:USERPROFILE,
    [switch]$ManualStart,
    [string]$SigningCertificateThumbprint,
    [ValidatePattern('^https://')]
    [string]$TimestampUrl = 'https://timestamp.digicert.com'
)

$ErrorActionPreference = 'Stop'

function Get-GitCommit([string]$RepositoryRoot) {
    $commit = (& git -C $RepositoryRoot rev-parse HEAD 2>$null).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($commit)) {
        return 'unknown'
    }
    return $commit
}

function Sign-PackageFile([System.IO.FileInfo]$File, [string]$Thumbprint, [string]$Timestamp) {
    if ([string]::IsNullOrWhiteSpace($Thumbprint)) {
        return [ordered]@{ requested = $false; targetFile = $null; status = 'NOT_REQUESTED'; certificateThumbprint = $null }
    }
    $signTool = Get-Command signtool.exe -ErrorAction SilentlyContinue
    if ($null -eq $signTool) {
        $signTool = Get-Command signtool -ErrorAction SilentlyContinue
    }
    if ($null -eq $signTool) {
        throw 'Signing was requested but signtool was not found. Install the Windows SDK signing tools or omit -SigningCertificateThumbprint.'
    }
    & $signTool.Source sign /sha1 $Thumbprint /fd SHA256 /tr $Timestamp /td SHA256 $File.FullName
    if ($LASTEXITCODE -ne 0) {
        throw "signtool failed with exit code $LASTEXITCODE for $($File.FullName)"
    }
    $status = (Get-AuthenticodeSignature -FilePath $File.FullName).Status.ToString()
    if ($status -ne 'Valid') {
        throw "Authenticode verification failed for $($File.FullName): $status"
    }
    return [ordered]@{ requested = $true; targetFile = $File.Name; status = $status; certificateThumbprint = $Thumbprint }
}

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
if ($Type -eq 'msi') {
    $candle = Get-Command candle.exe -ErrorAction SilentlyContinue
    $light = Get-Command light.exe -ErrorAction SilentlyContinue
    if ($null -eq $candle -or $null -eq $light) {
        throw 'MSI packaging requires WiX Toolset v3 on PATH (candle.exe and light.exe). Install it before running this command.'
    }
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
        '--app-version', $Version,
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

    $signing = $null
    if ($Type -eq 'msi') {
        $artifact = Get-ChildItem -LiteralPath $destination -Filter '*.msi' -File |
            Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
        if ($null -eq $artifact) { throw "jpackage did not produce an MSI under $destination" }
        $signing = Sign-PackageFile -File $artifact -Thumbprint $SigningCertificateThumbprint -Timestamp $TimestampUrl
    } else {
        $appDirectory = Join-Path $destination $Name
        $launcher = Get-Item -LiteralPath (Join-Path $appDirectory "$Name.exe") -ErrorAction Stop
        $signing = Sign-PackageFile -File $launcher -Thumbprint $SigningCertificateThumbprint -Timestamp $TimestampUrl
        $archivePath = Join-Path $destination "$Name-$Version-windows.zip"
        if (Test-Path -LiteralPath $archivePath) {
            throw "Refusing to overwrite existing release archive: $archivePath"
        }
        Compress-Archive -LiteralPath $appDirectory -DestinationPath $archivePath
        $artifact = Get-Item -LiteralPath $archivePath
    }

    $hash = (Get-FileHash -LiteralPath $artifact.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $manifest = [ordered]@{
        schemaVersion = 1
        product = $Name
        version = $Version
        platform = 'windows'
        packageType = $Type
        artifactFile = $artifact.Name
        sha256 = $hash
        sizeBytes = [int64]$artifact.Length
        builtAtUtc = [DateTime]::UtcNow.ToString('O')
        sourceCommit = Get-GitCommit $root
        serverBinding = 'loopback'
        signing = $signing
    }
    $manifestPath = Join-Path $destination "$Name-$Version-windows.manifest.json"
    $checksumPath = "$manifestPath.sha256"
    $manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $manifestPath -Encoding utf8
    "$hash *$($artifact.Name)" | Set-Content -LiteralPath $checksumPath -Encoding ascii

    Write-Host "Windows node package created: $($artifact.FullName)"
    Write-Host "Release manifest: $manifestPath"
    Write-Host "SHA-256: $hash"
    Write-Host "Source commit: $($manifest.sourceCommit)"
    Write-Host "Authenticode: $($signing.status)"
    Write-Host "Server: $Server"
    Write-Host "Start when the GUI opens after setup: $(-not $ManualStart)"
}
finally {
    Pop-Location
}
