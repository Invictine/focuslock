function Read-JavaProperties([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Android update properties file not found: $Path" }
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match '^\s*(?:#|!|$)') { continue }
        if ($line -notmatch '^\s*([^=:\s]+)\s*[=:]\s*(.*?)\s*$') { continue }
        $values[$Matches[1]] = $Matches[2].Trim()
    }
    return $values
}

function Assert-AndroidUpdateConfig([string]$PropertiesPath, [object]$DistributionConfig) {
    $properties = Read-JavaProperties $PropertiesPath
    foreach ($key in @('firebase.applicationId', 'firebase.apiKey', 'firebase.projectId', 'firebase.senderId')) {
        if (-not $properties.ContainsKey($key) -or [string]::IsNullOrWhiteSpace([string]$properties[$key])) {
            throw "Android updater property '$key' is missing or blank in local.properties."
        }
    }
    $applicationId = [string]$properties['firebase.applicationId']
    $projectId = [string]$properties['firebase.projectId']
    $senderId = [string]$properties['firebase.senderId']
    if ($applicationId -ne [string]$DistributionConfig.appId) { throw 'Firebase applicationId in local.properties does not match distribution config appId.' }
    if ($projectId -ne [string]$DistributionConfig.projectId) { throw 'Firebase projectId in local.properties does not match distribution config projectId.' }
    if ($applicationId -notmatch '^1:([0-9]+):android:[0-9a-fA-F]+$' -or $Matches[1] -ne $senderId) { throw 'Firebase senderId in local.properties does not match the configured Firebase applicationId.' }
}
