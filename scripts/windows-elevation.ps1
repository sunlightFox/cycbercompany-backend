function Test-AgentStudioAdministrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Get-AgentStudioWindowsUserName {
    return [Security.Principal.WindowsIdentity]::GetCurrent().Name
}

function Assert-AgentStudioWindowsUser {
    param([AllowNull()][string]$ExpectedUser)

    if ([string]::IsNullOrWhiteSpace($ExpectedUser)) {
        return
    }
    $actualUser = Get-AgentStudioWindowsUserName
    if (-not [string]::Equals($actualUser, $ExpectedUser, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Personal local mode must be elevated as the same Windows user. Expected '$ExpectedUser' but got '$actualUser'. Choose the same account in UAC, or set AGENT_STUDIO_API_TOKEN in the target user's User/Machine environment intentionally."
    }
}

function ConvertTo-AgentStudioPowerShellLiteral {
    param([AllowNull()][string]$Value)

    if ($null -eq $Value) {
        return "`$null"
    }
    return "'" + $Value.Replace("'", "''") + "'"
}

function Get-AgentStudioSecretHandoffRoot {
    $baseDir = [Environment]::GetFolderPath("LocalApplicationData")
    if ([string]::IsNullOrWhiteSpace($baseDir)) {
        $baseDir = [System.IO.Path]::GetTempPath()
    }
    return Join-Path $baseDir "AgentStudio\handoff"
}

function Remove-AgentStudioOldSecretHandoffFiles {
    param([int]$OlderThanMinutes = 30)

    $root = Get-AgentStudioSecretHandoffRoot
    if (-not (Test-Path -LiteralPath $root -PathType Container)) {
        return
    }
    $cutoff = [DateTime]::UtcNow.AddMinutes(-[Math]::Max(1, $OlderThanMinutes))
    try {
        Get-ChildItem -LiteralPath $root -Filter "agent-studio-*.tmp" -File -ErrorAction SilentlyContinue |
            Where-Object { $_.LastWriteTimeUtc -lt $cutoff } |
            ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force -ErrorAction SilentlyContinue }
    } catch {
        # Cleanup is best-effort; creating the new protected handoff file should still proceed.
    }
}

function New-AgentStudioSecretHandoffFile {
    param(
        [Parameter(Mandatory = $true)][string]$Value,
        [string]$NamePrefix = "agent-studio-secret"
    )

    if ([string]::IsNullOrWhiteSpace($Value)) {
        return $null
    }

    Remove-AgentStudioOldSecretHandoffFiles
    $root = Get-AgentStudioSecretHandoffRoot
    New-Item -ItemType Directory -Force -Path $root | Out-Null

    $safePrefix = $NamePrefix -replace '[^A-Za-z0-9_.-]', '-'
    $path = Join-Path $root ("$safePrefix-$([Guid]::NewGuid().ToString('N')).tmp")
    $encoding = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText($path, $Value, $encoding)

    try {
        $currentUser = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
        $acl = Get-Acl -LiteralPath $path
        $acl.SetAccessRuleProtection($true, $false)
        $rule = [System.Security.AccessControl.FileSystemAccessRule]::new(
            $currentUser,
            [System.Security.AccessControl.FileSystemRights]::FullControl,
            [System.Security.AccessControl.AccessControlType]::Allow)
        $acl.SetAccessRule($rule)
        Set-Acl -LiteralPath $path -AclObject $acl
    } catch {
        Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
        throw "Could not protect the temporary secret handoff file: $($_.Exception.Message)"
    }

    return $path
}

function Read-AgentStudioSecretHandoffFile {
    param([AllowNull()][string]$Path)

    if ([string]::IsNullOrWhiteSpace($Path)) {
        return $null
    }
    try {
        if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
            return $null
        }
        return [System.IO.File]::ReadAllText($Path).Trim()
    } finally {
        Remove-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue
    }
}

function Invoke-AgentStudioElevatedScript {
    param(
        [Parameter(Mandatory = $true)][string]$ScriptPath,
        [Parameter(Mandatory = $true)][hashtable]$Parameters
    )

    $commandParts = @("& $(ConvertTo-AgentStudioPowerShellLiteral $ScriptPath)")
    foreach ($entry in $Parameters.GetEnumerator()) {
        if ($null -eq $entry.Value) {
            continue
        }
        if ($entry.Value -is [bool]) {
            if ($entry.Value) {
                $commandParts += "-$($entry.Key)"
            }
            continue
        }
        $commandParts += "-$($entry.Key) $(ConvertTo-AgentStudioPowerShellLiteral ([string]$entry.Value))"
    }

    $command = $commandParts -join " "
    $encodedCommand = [Convert]::ToBase64String(
        [Text.Encoding]::Unicode.GetBytes($command))
    $elevatedProcess = Start-Process -FilePath "powershell.exe" -Verb RunAs -WorkingDirectory (Get-Location).Path `
        -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass", "-EncodedCommand", $encodedCommand) `
        -Wait -PassThru
    return $elevatedProcess.ExitCode
}
