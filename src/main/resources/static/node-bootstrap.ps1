[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$Server,
  [Parameter(Mandatory = $true)][string]$Token,
  [string]$Workspace = (Get-Location).Path
)
$ErrorActionPreference = "Stop"
$gradle = Join-Path (Get-Location) "gradlew.bat"
if (-not (Test-Path -LiteralPath $gradle -PathType Leaf)) { throw "Run this script from the Java backend project root." }
$registerArgs = "register --server $Server --token $Token --workspace `"$Workspace`""
& $gradle --no-daemon ':agent-studio-node-java:run' "--args=$registerArgs"
& $gradle --no-daemon ':agent-studio-node-java:run' '--args=start'
