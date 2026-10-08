[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'android-update-config.ps1')
$tempRoot = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ('focuslock-update-config-' + [guid]::NewGuid().ToString('N'))))
$tempPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
New-Item -ItemType Directory -Path $tempRoot -Force | Out-Null
try {
    if (-not $tempRoot.StartsWith($tempPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Test directory escaped the temporary directory.' }
    $config = [pscustomobject]@{ appId = '1:123456789:android:abcdef0123456789'; projectId = 'demo-project' }
    $sentinel = 'AIza-SENTINEL-DO-NOT-PRINT'
    $values = [ordered]@{
        'firebase.applicationId' = $config.appId
        'firebase.apiKey' = $sentinel
        'firebase.projectId' = $config.projectId
        'firebase.senderId' = '123456789'
    }
    $propertiesPath = Join-Path $tempRoot 'local.properties'
    function Write-Fixture([hashtable]$Override = @{}) {
        $copy = [ordered]@{}; foreach ($key in $values.Keys) { $copy[$key] = $values[$key] }
        foreach ($key in $Override.Keys) {
            if ($null -eq $Override[$key]) { $copy.Remove($key) } else { $copy[$key] = $Override[$key] }
        }
        [IO.File]::WriteAllLines($propertiesPath, @($copy.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }), (New-Object Text.UTF8Encoding($false)))
    }
    function Assert-Rejected([string]$Label, [hashtable]$Override, [string]$Expected) {
        Write-Fixture $Override
        try { Assert-AndroidUpdateConfig $propertiesPath $config | Out-Null; throw "$Label was accepted." }
        catch { if ($_.Exception.Message -notmatch $Expected) { throw "$Label failed with an unexpected error." }; if ($_.Exception.Message.Contains($sentinel)) { throw "$Label exposed the API key." } }
    }
    Write-Fixture
    if ($null -ne (Assert-AndroidUpdateConfig $propertiesPath $config)) { throw 'Valid preflight returned a value.' }
    foreach ($key in $values.Keys) { Assert-Rejected "missing $key" @{ $key = $null } ([regex]::Escape($key)) ; Assert-Rejected "blank $key" @{ $key = '  ' } ([regex]::Escape($key)) }
    Assert-Rejected 'application mismatch' @{ 'firebase.applicationId' = '1:123456789:android:1111111111111111' } 'applicationId'
    Assert-Rejected 'project mismatch' @{ 'firebase.projectId' = 'other-project' } 'projectId'
    Assert-Rejected 'sender mismatch' @{ 'firebase.senderId' = '987654321' } 'senderId'
    Write-Output 'PASS update distribution config preflight fixtures'
} finally {
    if ($tempRoot.StartsWith($tempPrefix, [StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $tempRoot)) { Remove-Item -LiteralPath $tempRoot -Recurse -Force }
}
