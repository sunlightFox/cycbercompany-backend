function Invoke-StudioJsonUtf8 {
    param(
        [Parameter(Mandatory = $true)][string]$BaseUrl,
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [object]$Body = $null,
        [string]$ApiToken,
        [int]$TimeoutSeconds = 120
    )

    $request = [System.Net.HttpWebRequest]::Create(($BaseUrl.TrimEnd('/') + $Path))
    # HTTP method tokens are case-sensitive in the servlet stack used by the
    # evaluation backend. Normalize callers such as `Patch` to `PATCH`.
    $request.Method = $Method.Trim().ToUpperInvariant()
    $request.Accept = "application/json"
    $request.UserAgent = "AgentStudio-Evaluation/1.0"
    $request.Timeout = [Math]::Max(1, $TimeoutSeconds) * 1000
    if (-not [string]::IsNullOrWhiteSpace($ApiToken)) {
        $request.Headers['Authorization'] = "Bearer $ApiToken"
    }
    if ($null -ne $Body) {
        $payload = [System.Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 20))
        $request.ContentType = "application/json; charset=utf-8"
        $request.ContentLength = $payload.Length
        $stream = $request.GetRequestStream()
        try { $stream.Write($payload, 0, $payload.Length) } finally { $stream.Dispose() }
    }

    $response = $request.GetResponse()
    try {
        $reader = [System.IO.StreamReader]::new($response.GetResponseStream(), [System.Text.UTF8Encoding]::new($false))
        try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally {
        $response.Dispose()
    }
    if ([string]::IsNullOrWhiteSpace($text)) { return $null }
    return $text | ConvertFrom-Json
}
