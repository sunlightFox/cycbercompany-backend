param(
    [int]$Port = 18120,
    [string]$ConfigPath = "$(Join-Path (Get-Location) 'output\tvbox-config.json')",
    [string]$EngineEndpoint = $env:VIDEO_DEMO_TVBOX_ENGINE_ENDPOINT,
    [string]$PythonCommand = "py"
)

$ErrorActionPreference = 'Stop'
$workspace = (Get-Location).Path
$workerPath = Join-Path $workspace 'scripts\tvbox-runtime-worker.py'
$adapterPath = Join-Path $workspace 'scripts\tvbox-compatible-adapter.py'

if (-not (Test-Path -LiteralPath $ConfigPath -PathType Leaf)) {
    throw "TVBox config not found: $ConfigPath"
}
if (-not (Get-Command $PythonCommand -ErrorAction SilentlyContinue)) {
    throw "Python launcher not found: $PythonCommand"
}

$listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($listener) {
    throw "Video Demo runtime port $Port is already in use by PID $($listener.OwningProcess)."
}

$env:TVBOX_RUNTIME_ADAPTER = ConvertTo-Json @($PythonCommand, '-3.12', $adapterPath) -Compress
$env:VIDEO_DEMO_TVBOX_CONFIG = (Resolve-Path -LiteralPath $ConfigPath).Path
if ($EngineEndpoint) {
    $env:VIDEO_DEMO_TVBOX_ENGINE_ENDPOINT = $EngineEndpoint
} else {
    Remove-Item Env:VIDEO_DEMO_TVBOX_ENGINE_ENDPOINT -ErrorAction SilentlyContinue
}

$logOut = Join-Path $workspace "tvbox-runtime-$Port.out.log"
$logErr = Join-Path $workspace "tvbox-runtime-$Port.err.log"
Start-Process -FilePath $PythonCommand -ArgumentList @('-3.12', $workerPath, '--port', $Port) `
    -WorkingDirectory $workspace -WindowStyle Hidden -RedirectStandardOutput $logOut -RedirectStandardError $logErr | Out-Null

$deadline = (Get-Date).AddSeconds(10)
do {
    Start-Sleep -Milliseconds 250
    try {
        $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/health" -TimeoutSec 2
        break
    } catch {
        $health = $null
    }
} while ((Get-Date) -lt $deadline)

if (-not $health) {
    throw "Video Demo runtime did not start. See $logErr"
}

$health | ConvertTo-Json -Depth 5
Write-Output "Endpoint: http://127.0.0.1:$Port/v1/media/search"
