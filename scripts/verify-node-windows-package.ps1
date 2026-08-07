[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$Artifact,
    [Parameter(Mandatory)]
    [string]$Manifest,
    [switch]$RequireSigned
)

$ErrorActionPreference = 'Stop'
$artifactFile = Get-Item -LiteralPath $Artifact -ErrorAction Stop
$manifestFile = Get-Item -LiteralPath $Manifest -ErrorAction Stop
if ($artifactFile.PSIsContainer -or $manifestFile.PSIsContainer) {
    throw 'Artifact and manifest must both be files.'
}

$release = Get-Content -LiteralPath $manifestFile.FullName -Raw | ConvertFrom-Json
if ($release.schemaVersion -ne 1) {
    throw "Unsupported package manifest schema: $($release.schemaVersion)"
}
if ([System.IO.Path]::GetFileName([string]$release.artifactFile) -ne $artifactFile.Name) {
    throw "Artifact filename does not match manifest: expected $($release.artifactFile), got $($artifactFile.Name)"
}
if ([int64]$release.sizeBytes -ne [int64]$artifactFile.Length) {
    throw "Artifact size does not match manifest: expected $($release.sizeBytes), got $($artifactFile.Length)"
}
$actualHash = (Get-FileHash -LiteralPath $artifactFile.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualHash -ne ([string]$release.sha256).ToLowerInvariant()) {
    throw "SHA-256 does not match manifest: expected $($release.sha256), got $actualHash"
}

$signatureStatus = 'NOT_APPLICABLE'
if ($RequireSigned) {
    if ($release.packageType -ne 'msi') {
        throw 'RequireSigned is supported only for an MSI artifact. App-image ZIP signatures apply to the executable before archiving.'
    }
    $signatureStatus = (Get-AuthenticodeSignature -FilePath $artifactFile.FullName).Status.ToString()
    if ($signatureStatus -ne 'Valid' -or $release.signing.status -ne 'Valid') {
        throw "Authenticode verification failed: manifest=$($release.signing.status), artifact=$signatureStatus"
    }
}

[pscustomobject]@{
    product = $release.product
    version = $release.version
    artifact = $artifactFile.FullName
    sha256 = $actualHash
    signatureStatus = $signatureStatus
    sourceCommit = $release.sourceCommit
}
