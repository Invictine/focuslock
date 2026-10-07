[CmdletBinding()]
param(
    [int]$MaxWaitSeconds = 40
)

$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$artifactsRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot 'artifacts'))
$fixtureRoot = [IO.Path]::GetFullPath((Join-Path $artifactsRoot ('auto-hook-test-' + [guid]::NewGuid().ToString('N'))))
$rootPrefix = $artifactsRoot.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
if (-not $fixtureRoot.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to create a test fixture outside artifacts: $fixtureRoot"
}

function Invoke-Git([string]$WorkingDirectory, [string[]]$Arguments) {
    $oldPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $output = & git -C $WorkingDirectory @Arguments 2>&1
    $ErrorActionPreference = $oldPreference
    if ($LASTEXITCODE -ne 0) { throw "git $($Arguments -join ' ') failed in $WorkingDirectory`n$($output -join "`n")" }
    return ($output | ForEach-Object { [string]$_ }) -join [Environment]::NewLine
}

function Invoke-PowerShell([string]$ScriptPath, [string[]]$Arguments, [string]$WorkingDirectory) {
    $output = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $ScriptPath @Arguments 2>&1
    $code = $LASTEXITCODE
    if ($code -ne 0) { throw "$ScriptPath failed ($code):`n$($output -join "`n")" }
    return @($output | ForEach-Object { [string]$_ })
}

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "ASSERTION FAILED: $Message" }
}

function Wait-ForResults([string]$ResultsDirectory, [string[]]$ExpectedCommits, [int]$TimeoutSeconds) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $missing = @($ExpectedCommits | Where-Object { -not (Test-Path -LiteralPath (Join-Path $ResultsDirectory "$_.json")) })
        if ($missing.Count -eq 0) { return }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for distribution results: $($missing -join ', ')"
}

function Get-Result([string]$ResultsDirectory, [string]$Commit) {
    return (Get-Content -LiteralPath (Join-Path $ResultsDirectory "$Commit.json") -Raw | ConvertFrom-Json)
}

function New-Commit([string]$Fixture, [string]$Message) {
    Invoke-Git $Fixture @('add', '--all') | Out-Null
    Invoke-Git $Fixture @('commit', '-m', $Message) | Out-Null
    return ([string](Invoke-Git $Fixture @('rev-parse', 'HEAD'))).Trim()
}

try {
    New-Item -ItemType Directory -Path $fixtureRoot -Force | Out-Null
    $scripts = Join-Path $fixtureRoot 'scripts'
    $hooks = Join-Path $fixtureRoot '.githooks'
    $customHooks = Join-Path $fixtureRoot '.fixture-hooks'
    New-Item -ItemType Directory -Path $scripts, $hooks, $customHooks, (Join-Path $fixtureRoot 'artifacts') -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $repoRoot 'scripts\auto-distribute-android.ps1') -Destination $scripts
    Copy-Item -LiteralPath (Join-Path $repoRoot 'scripts\install-android-distribution-hook.ps1') -Destination $scripts
    Copy-Item -LiteralPath (Join-Path $repoRoot '.githooks\post-commit') -Destination $hooks

    Get-Command git.exe -ErrorAction Stop | Out-Null
    Invoke-Git $fixtureRoot @('init', '-q', '-b', 'main') | Out-Null
    Invoke-Git $fixtureRoot @('config', 'user.name', 'Auto Hook Smoke Test') | Out-Null
    Invoke-Git $fixtureRoot @('config', 'user.email', 'auto-hook-test@example.invalid') | Out-Null
    Invoke-Git $fixtureRoot @('config', 'core.hooksPath', '.fixture-hooks') | Out-Null

    $priorHook = @'
#!/bin/sh
echo prior-hook-ran >> "__PRIOR_LOG__"
'@.Replace('__PRIOR_LOG__', (Join-Path $fixtureRoot 'prior-hook.log').Replace('\', '/'))
    $activeHook = Join-Path $customHooks 'post-commit'
    [IO.File]::WriteAllText($activeHook, $priorHook, (New-Object Text.UTF8Encoding($false)))
    $priorHash = (Get-FileHash -LiteralPath $activeHook -Algorithm SHA256).Hash

    $runner = @'
param([string]$ConfigPath, [string]$StatePath, [string]$Notes)
$ErrorActionPreference = 'Stop'
$folder = Split-Path -Parent $StatePath
$active = Join-Path $folder 'runner-active.lock'
$trace = Join-Path $folder 'runner-trace.txt'
$marker = Get-Content -LiteralPath (Join-Path $PSScriptRoot '..\marker.txt') -Raw
$mode = (Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json).mode
try { $lock = New-Object IO.FileStream($active, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None) }
catch [IO.IOException] { [IO.File]::AppendAllText($trace, "OVERLAP|$marker`n"); exit 19 }
try {
    [IO.File]::AppendAllText($trace, "START|$marker`n")
    Start-Sleep -Milliseconds 2000
    [IO.File]::AppendAllText($trace, "END|$marker`n")
    Write-Output "fixture runner startup marker=$marker notes=$Notes"
    if ($mode -eq 'fail') {
        $previousPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        $childText = "[Console]::Error.WriteLine('child stderr fixture'); exit 13"
        $childEncoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($childText))
        $childOutput = & powershell.exe -NoProfile -EncodedCommand $childEncoded 2>&1
        $childCode = $LASTEXITCODE
        $ErrorActionPreference = $previousPreference
        $childOutput | ForEach-Object { Write-Output ([string]$_) }
        exit $childCode
    }
    Write-Output "fixture runner completed marker=$marker"
} finally { $lock.Dispose(); Remove-Item -LiteralPath $active -Force -ErrorAction SilentlyContinue }
'@
    [IO.File]::WriteAllText((Join-Path $scripts 'distribute-android.ps1'), $runner, (New-Object Text.UTF8Encoding($false)))
    [IO.File]::WriteAllText((Join-Path $fixtureRoot 'firebase-distribution.local.json'), '{"mode":"success"}', (New-Object Text.UTF8Encoding($false)))
    [IO.File]::WriteAllText((Join-Path $fixtureRoot 'marker.txt'), 'snapshot-alpha', (New-Object Text.UTF8Encoding($false)))

    $installScript = Join-Path $scripts 'install-android-distribution-hook.ps1'
    $validateOutput = Invoke-PowerShell $installScript @('-ValidateOnly') $fixtureRoot
    Assert-True ((Get-FileHash -LiteralPath $activeHook -Algorithm SHA256).Hash -eq $priorHash) 'ValidateOnly modified the active hook.'
    Assert-True (-not (Test-Path -LiteralPath "$activeHook.focuslock-distribution-user")) 'ValidateOnly created a hook sidecar.'
    $configuredHooksPath = ([string](Invoke-Git $fixtureRoot @('config', '--get', 'core.hooksPath'))).Trim()
    Assert-True ($configuredHooksPath -eq '.fixture-hooks') "ValidateOnly changed core.hooksPath to '$configuredHooksPath'."

    Invoke-PowerShell $installScript @() $fixtureRoot | Out-Null
    $configuredHooksPath = ([string](Invoke-Git $fixtureRoot @('config', '--get', 'core.hooksPath'))).Trim()
    Assert-True ($configuredHooksPath -eq '.fixture-hooks') "Installer changed core.hooksPath to '$configuredHooksPath'."
    $priorSidecar = $activeHook + '.focuslock-distribution-user'
    Assert-True ((Get-FileHash -LiteralPath $priorSidecar -Algorithm SHA256).Hash -eq $priorHash) 'Installer did not preserve the prior hook byte-for-byte.'
    Assert-True ((Get-Content -LiteralPath $activeHook -Raw).Contains('# FocusLock Android distribution hook wrapper')) 'Installer wrapper is missing.'

    $clock = [Diagnostics.Stopwatch]::StartNew()
    $alpha = New-Commit $fixtureRoot 'alpha snapshot'
    $clock.Stop()
    Assert-True ($clock.Elapsed.TotalSeconds -lt 10) "Commit hook blocked for $([int]$clock.Elapsed.TotalSeconds) seconds."
    [IO.File]::WriteAllText((Join-Path $fixtureRoot 'marker.txt'), 'uncommitted-working-copy', (New-Object Text.UTF8Encoding($false)))
    [IO.File]::WriteAllText((Join-Path $fixtureRoot 'commit-note.txt'), 'beta', (New-Object Text.UTF8Encoding($false)))
    [IO.File]::WriteAllText((Join-Path $fixtureRoot 'marker.txt'), 'snapshot-beta', (New-Object Text.UTF8Encoding($false)))
    $beta = New-Commit $fixtureRoot 'beta snapshot'

    $commonDir = [string](Invoke-Git $fixtureRoot @('rev-parse', '--git-common-dir'))
    if (-not [IO.Path]::IsPathRooted($commonDir)) { $commonDir = Join-Path $fixtureRoot $commonDir }
    $stateRoot = Join-Path ([IO.Path]::GetFullPath($commonDir)) 'focuslock-distribution'
    $results = Join-Path $stateRoot 'results'
    Wait-ForResults $results @($alpha, $beta) $MaxWaitSeconds
    Assert-True ((Get-Content -LiteralPath (Join-Path $fixtureRoot 'prior-hook.log') -Raw).Split("`n", [StringSplitOptions]::RemoveEmptyEntries).Count -eq 2) 'Chained pre-existing hook did not run once per commit.'
    Assert-True ((Get-Result $results $alpha).status -eq 'completed') 'Alpha distribution did not complete.'
    Assert-True ((Get-Result $results $beta).status -eq 'completed') 'Beta distribution did not complete.'

    $trace = @(Get-Content -LiteralPath (Join-Path $fixtureRoot 'artifacts\runner-trace.txt'))
    Assert-True ($trace.Count -eq 4) "Expected four runner trace records, got $($trace.Count)."
    Assert-True (($trace -join '|') -notmatch 'OVERLAP') 'Distribution runners overlapped.'
    Assert-True ($trace[0] -eq 'START|snapshot-alpha' -and $trace[1] -eq 'END|snapshot-alpha') 'Alpha used the dirty working copy instead of its committed snapshot.'
    Assert-True ($trace[2] -eq 'START|snapshot-beta' -and $trace[3] -eq 'END|snapshot-beta') 'Beta snapshot marker or queue ordering was wrong.'

    $enqueueScript = Join-Path $scripts 'auto-distribute-android.ps1'
    $repeat = Invoke-PowerShell $enqueueScript @('-Enqueue', '-SourceRoot', $fixtureRoot, '-StateRoot', $stateRoot, '-Commit', $alpha) $fixtureRoot
    Assert-True (($repeat -join "`n") -match 'already (queued or )?resolved') 'Repeated enqueue was not idempotent.'
    Assert-True (@(Get-ChildItem -LiteralPath $results -Filter '*.json' -File).Count -eq 2) 'Repeated enqueue created an extra result.'

    [IO.File]::WriteAllText((Join-Path $fixtureRoot 'firebase-distribution.local.json'), '{"mode":"fail"}', (New-Object Text.UTF8Encoding($false)))
    [IO.File]::WriteAllText((Join-Path $fixtureRoot 'marker.txt'), 'snapshot-failure', (New-Object Text.UTF8Encoding($false)))
    $failure = New-Commit $fixtureRoot 'expected distribution failure'
    Wait-ForResults $results @($failure) $MaxWaitSeconds
    $failedResult = Get-Result $results $failure
    Assert-True ($failedResult.status -eq 'failed') 'Mock distribution failure was not recorded as failed.'
    Assert-True (Test-Path -LiteralPath $failedResult.log) 'Failed distribution log is missing.'
    $failureLog = Get-Content -LiteralPath $failedResult.log -Raw
    Assert-True ($failureLog -match 'fixture runner startup marker=snapshot-failure') 'Failure log is missing the mock runner startup context.'
    Assert-True ($failureLog -match 'child stderr fixture') 'Failure log is missing stderr from the mock child PowerShell process.'
    Assert-True ($failureLog -match 'exit code 13') "Failure log is missing the child process exit code. Log contents:`n$failureLog"
    Write-Output 'PASS: ValidateOnly is inert; installer preserves the configured hooks path and chains the previous hook.'
    Write-Output 'PASS: post-commit enqueue returns promptly; committed snapshots are distributed in serialized order.'
    Write-Output 'PASS: repeat enqueue is idempotent; a distribution failure is logged without failing git commit.'
    Write-Output 'Temporary fixture cleaned up.'
}
finally {
    if (Test-Path -LiteralPath $fixtureRoot) {
        $resolvedFixture = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $fixtureRoot).Path)
        if ($resolvedFixture.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase) -and (Split-Path -Leaf $resolvedFixture).StartsWith('auto-hook-test-')) {
            Remove-Item -LiteralPath $resolvedFixture -Recurse -Force
        }
    }
}
