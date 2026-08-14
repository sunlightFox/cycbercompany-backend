[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$Server,
  [string]$Workspace = (Get-Location).Path
)
$ErrorActionPreference = "Stop"
$gradle = Join-Path (Get-Location) "gradlew.bat"
if (-not (Test-Path -LiteralPath $gradle -PathType Leaf)) { throw "Run this script from the Java backend project root." }
$registerArgs = "register --server $Server --workspace `"$Workspace`""
& $gradle --no-daemon ':cycbercompany-node-java:run' "--args=$registerArgs"
& $gradle --no-daemon ':cycbercompany-node-java:run' '--args=start'
