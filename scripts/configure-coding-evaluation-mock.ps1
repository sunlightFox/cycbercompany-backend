[CmdletBinding()]
param(
    [string]$BaseUrl = "http://127.0.0.1:8084",
    [string]$ProviderBaseUrl = "http://host.docker.internal:18081/v1",
    [string]$ModelProfileId = "evaluation-openai-mock",
    [switch]$SetDefault
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "studio-http.ps1")

$normalizedBaseUrl = $BaseUrl.Trim().TrimEnd('/')
if ([string]::IsNullOrWhiteSpace($normalizedBaseUrl)) { throw "BaseUrl must not be empty." }
$normalizedProviderUrl = $ProviderBaseUrl.Trim().TrimEnd('/')
if ([string]::IsNullOrWhiteSpace($normalizedProviderUrl)) { throw "ProviderBaseUrl must not be empty." }

function Invoke-StudioJson {
    param([string]$Method, [string]$Path, [object]$Body = $null)
    Invoke-StudioJsonUtf8 -BaseUrl $normalizedBaseUrl -Method $Method -Path $Path -Body $Body -ApiToken $env:CYCBERCOMPANY_API_TOKEN
}

$profile = Invoke-StudioJson -Method Post -Path "/api/v1/models" -Body @{
    id = $ModelProfileId
    providerType = "OPENAI_COMPATIBLE"
    baseUrl = $normalizedProviderUrl
    modelName = "evaluation-mock"
    credentialRef = ""
    # This non-secret sentinel exists only to satisfy the OpenAI-compatible
    # gateway's authentication precondition. The local mock never logs headers.
    apiKey = "evaluation-mock"
    capabilities = @("TEXT", "TOOLS")
    enabled = $true
}

if ($SetDefault) {
    Invoke-StudioJson -Method Patch -Path "/api/v1/models/settings/default" -Body @{ modelProfileId = $ModelProfileId } | Out-Null
}

$probe = Invoke-StudioJson -Method Post -Path "/api/v1/models/$ModelProfileId/test" -Body @{}
if ($probe.success -ne $true) { throw "The evaluation mock profile was saved but its connectivity probe failed." }

[ordered]@{
    ready = $true
    baseUrl = $normalizedBaseUrl
    modelProfileId = $profile.id
    providerBaseUrl = $normalizedProviderUrl
    defaultUpdated = [bool]$SetDefault
} | ConvertTo-Json -Depth 5
