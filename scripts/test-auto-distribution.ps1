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

function Write-QueuedJob([string]$StateRoot, [string]$SourceRoot, [string]$Commit, [DateTime]$QueuedAt) {
    $job = [ordered]@{
        commit = $Commit.ToLowerInvariant()
        sourceRoot = $SourceRoot
        queuedAt = $QueuedAt.ToUniversalTime().ToString('o')
    }
    [IO.File]::WriteAllText(
        (Join-Path (Join-Path $StateRoot 'jobs') "$Commit.json"),
        ($job | ConvertTo-Json),
        (New-Object Text.UTF8Encoding($false))
    )
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
param([string]$ConfigPath, [string]$StatePath, [string]$BuildVariant, [string]$Notes)
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
    Write-Output "fixture runner startup marker=$marker variant=$BuildVariant notes=$Notes"
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
    $alphaLog = Get-Content -LiteralPath (Join-Path (Join-Path $stateRoot 'logs') "$alpha.log") -Raw
    $betaLog = Get-Content -LiteralPath (Join-Path (Join-Path $stateRoot 'logs') "$beta.log") -Raw
    Assert-True ($alphaLog -match 'variant=Performance notes=FocusLock Android performance build') 'Automatic distribution did not select the optimized performance variant.'
    Assert-True ($betaLog -match 'variant=Performance notes=FocusLock Android performance build') 'Automatic distribution did not select the optimized performance variant for the next queued commit.'

    $enqueueScript = Join-Path $scripts 'auto-distribute-android.ps1'
    $repeat = Invoke-PowerShell $enqueueScript @('-Enqueue', '-SourceRoot', $fixtureRoot, '-StateRoot', $stateRoot, '-Commit', $alpha) $fixtureRoot
    Assert-True (($repeat -join "`n") -match 'already (queued or )?resolved') 'Repeated enqueue was not idempotent.'
    Assert-True (@(Get-ChildItem -LiteralPath $results -Filter '*.json' -File).Count -eq 2) 'Repeated enqueue created an extra result.'

    # Queue an ancestor chain without starting a background worker, then verify
    # coalescing happens only between jobs, never by interrupting an active build.
    $originalHooksPath = ([string](Invoke-Git $fixtureRoot @('config', '--get', 'core.hooksPath'))).Trim()
    $emptyHooksPath = Join-Path $fixtureRoot 'artifacts\coalesce-empty-hooks'
    New-Item -ItemType Directory -Path $emptyHooksPath -Force | Out-Null
    $coalesceStateRoot = Join-Path $fixtureRoot 'artifacts\coalesce-state'
    foreach ($directory in @('jobs', 'results', 'logs', 'builds')) {
        New-Item -ItemType Directory -Path (Join-Path $coalesceStateRoot $directory) -Force | Out-Null
    }
    Assert-True (Test-Path -LiteralPath (Join-Path $coalesceStateRoot 'results') -PathType Container) 'Coalescing fixture results directory was not created.'
    $tracePath = Join-Path $fixtureRoot 'artifacts\runner-trace.txt'
    $traceBeforeCoalesce = @(Get-Content -LiteralPath $tracePath)
    Invoke-Git $fixtureRoot @('config', 'core.hooksPath', $emptyHooksPath) | Out-Null
    try {
        [IO.File]::WriteAllText((Join-Path $fixtureRoot 'marker.txt'), 'queued-ancestor-one', (New-Object Text.UTF8Encoding($false)))
        $ancestorOne = New-Commit $fixtureRoot 'queued ancestor one'
        [IO.File]::WriteAllText((Join-Path $fixtureRoot 'marker.txt'), 'queued-ancestor-two', (New-Object Text.UTF8Encoding($false)))
        $ancestorTwo = New-Commit $fixtureRoot 'queued ancestor two'
        [IO.File]::WriteAllText((Join-Path $fixtureRoot 'marker.txt'), 'queued-descendant-three', (New-Object Text.UTF8Encoding($false)))
        $descendantThree = New-Commit $fixtureRoot 'queued descendant three'
        $queuedNow = [DateTime]::UtcNow
        Write-QueuedJob $coalesceStateRoot $fixtureRoot $ancestorOne $queuedNow.AddSeconds(-3)
        Write-QueuedJob $coalesceStateRoot $fixtureRoot $ancestorTwo $queuedNow.AddSeconds(-2)
        Write-QueuedJob $coalesceStateRoot $fixtureRoot $descendantThree $queuedNow.AddSeconds(-1)

        Invoke-PowerShell $enqueueScript @('-Worker', '-StateRoot', $coalesceStateRoot) $fixtureRoot | Out-Null

        $ancestorOneResult = Get-Result (Join-Path $coalesceStateRoot 'results') $ancestorOne
        $ancestorTwoResult = Get-Result (Join-Path $coalesceStateRoot 'results') $ancestorTwo
        $descendantResult = Get-Result (Join-Path $coalesceStateRoot 'results') $descendantThree
        Assert-True ($ancestorOneResult.status -eq 'superseded' -and $ancestorOneResult.supersededBy -eq $descendantThree) 'First ancestor did not retain a superseded result pointing at the delivered descendant.'
        Assert-True ($ancestorTwoResult.status -eq 'superseded' -and $ancestorTwoResult.supersededBy -eq $descendantThree) 'Second ancestor did not retain a superseded result pointing at the delivered descendant.'
        Assert-True ($descendantResult.status -eq 'completed') 'Newest queued descendant did not complete.'
        $coalesceTrace = @(Get-Content -LiteralPath $tracePath | Select-Object -Skip $traceBeforeCoalesce.Count)
        Assert-True ($coalesceTrace.Count -eq 2 -and $coalesceTrace[0] -eq 'START|queued-descendant-three' -and $coalesceTrace[1] -eq 'END|queued-descendant-three') 'Coalescing ran an obsolete ancestor snapshot or skipped the newest snapshot.'

        # Divergent commits share a base but neither contains the other. Both must run.
        $baseBranch = ([string](Invoke-Git $fixtureRoot @('branch', '--show-current'))).Trim()
        Invoke-Git $fixtureRoot @('checkout', '-q', '-b', 'coalesce-left') | Out-Null
        [IO.File]::WriteAllText((Join-Path $fixtureRoot 'marker.txt'), 'divergent-left', (New-Object Text.UTF8Encoding($false)))
        $divergentLeft = New-Commit $fixtureRoot 'divergent left'
        Invoke-Git $fixtureRoot @('checkout', '-q', $baseBranch) | Out-Null
        [IO.File]::WriteAllText((Join-Path $fixtureRoot 'marker.txt'), 'divergent-right', (New-Object Text.UTF8Encoding($false)))
        $divergentRight = New-Commit $fixtureRoot 'divergent right'
        $queuedNow = [DateTime]::UtcNow
        Write-QueuedJob $coalesceStateRoot $fixtureRoot $divergentLeft $queuedNow.AddSeconds(-2)
        Write-QueuedJob $coalesceStateRoot $fixtureRoot $divergentRight $queuedNow.AddSeconds(-1)
        Invoke-PowerShell $enqueueScript @('-Worker', '-StateRoot', $coalesceStateRoot) $fixtureRoot | Out-Null
        Assert-True ((Get-Result (Join-Path $coalesceStateRoot 'results') $divergentLeft).status -eq 'completed') 'A divergent queued commit was incorrectly superseded.'
        Assert-True ((Get-Result (Join-Path $coalesceStateRoot 'results') $divergentRight).status -eq 'completed') 'The other divergent queued commit was incorrectly superseded.'
        $leftLog = Get-Content -LiteralPath (Join-Path (Join-Path $coalesceStateRoot 'logs') "$divergentLeft.log") -Raw
        $rightLog = Get-Content -LiteralPath (Join-Path (Join-Path $coalesceStateRoot 'logs') "$divergentRight.log") -Raw
        Assert-True ($leftLog -match 'runner startup marker=divergent-left' -and $rightLog -match 'runner startup marker=divergent-right') 'Divergent queued commits did not both run.'

        # Identical commit history in separate repositories is not enough to coalesce:
        # each checkout can carry distinct local distribution configuration.
        $cloneRoot = Join-Path $artifactsRoot ('auto-hook-clone-' + [guid]::NewGuid().ToString('N'))
        if (-not $cloneRoot.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to create a clone fixture outside artifacts: $cloneRoot"
        }
        try {
            Invoke-Git $fixtureRoot @('clone', '--no-hardlinks', $fixtureRoot, $cloneRoot) | Out-Null
            Invoke-Git $cloneRoot @('config', 'core.hooksPath', $emptyHooksPath) | Out-Null
            [IO.File]::WriteAllText((Join-Path $cloneRoot 'marker.txt'), 'separate-repository-descendant', (New-Object Text.UTF8Encoding($false)))
            $separateRepoDescendant = New-Commit $cloneRoot 'separate repository descendant'
            $queuedNow = [DateTime]::UtcNow
            Write-QueuedJob $coalesceStateRoot $fixtureRoot $descendantThree $queuedNow.AddSeconds(-2)
            Write-QueuedJob $coalesceStateRoot $cloneRoot $separateRepoDescendant $queuedNow.AddSeconds(-1)
            Invoke-PowerShell $enqueueScript @('-Worker', '-StateRoot', $coalesceStateRoot) $fixtureRoot | Out-Null
            Assert-True ((Get-Result (Join-Path $coalesceStateRoot 'results') $descendantThree).status -eq 'completed') 'A commit was superseded using ancestry from a different repository.'
            Assert-True ((Get-Result (Join-Path $coalesceStateRoot 'results') $separateRepoDescendant).status -eq 'completed') 'A descendant in a different repository did not run.'
        } finally {
            if (Test-Path -LiteralPath $cloneRoot) {
                $resolvedClone = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $cloneRoot).Path)
                if ($resolvedClone.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase) -and (Split-Path -Leaf $resolvedClone).StartsWith('auto-hook-clone-')) {
                    Remove-Item -LiteralPath $resolvedClone -Recurse -Force
                }
            }
        }
    } finally {
        Invoke-Git $fixtureRoot @('config', 'core.hooksPath', $originalHooksPath) | Out-Null
    }

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
    Write-Output 'PASS: queued ancestors receive superseded results when a later descendant runs; divergent commits both run.'
    Write-Output 'PASS: matching history in separate repositories is not coalesced.'
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
