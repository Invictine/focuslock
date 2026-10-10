[CmdletBinding()]
param(
    [switch]$Enqueue,
    [Alias('RunQueue')][switch]$Worker,
    [string]$StateRoot,
    [string]$SourceRoot,
    [string]$Commit
)

$ErrorActionPreference = 'Stop'
$scriptPath = (Resolve-Path -LiteralPath $PSCommandPath).Path

function Get-RepoRoot([string]$Path) {
    $lines = @(& git -C $Path rev-parse --show-toplevel 2>$null)
    $code = $LASTEXITCODE
    $root = $lines | Select-Object -First 1
    if ($code -ne 0 -or -not $root) { throw "Not inside a Git repository: $Path" }
    return [IO.Path]::GetFullPath([string]$root)
}

function Get-StateRoot([string]$Repo) {
    $lines = @(& git -C $Repo rev-parse --git-common-dir 2>$null)
    $code = $LASTEXITCODE
    $common = $lines | Select-Object -First 1
    if ($code -ne 0 -or -not $common) { throw 'Could not locate the shared Git directory.' }
    if (-not [IO.Path]::IsPathRooted([string]$common)) { $common = Join-Path $Repo ([string]$common) }
    return Join-Path ([IO.Path]::GetFullPath([string]$common)) 'focuslock-distribution'
}

function Write-AtomicJson([string]$Path, [object]$Value) {
    $temp = "$Path.$([guid]::NewGuid().ToString('N')).tmp"
    [IO.File]::WriteAllText($temp, ($Value | ConvertTo-Json -Depth 8), (New-Object Text.UTF8Encoding($false)))
    [IO.File]::Move($temp, $Path)
}

function Start-DistributionWorker([string]$Root) {
    $exe = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    if (-not (Test-Path -LiteralPath $exe)) { $exe = 'powershell.exe' }
    $command = "& '" + $scriptPath.Replace("'", "''") + "' -Worker -StateRoot '" + $Root.Replace("'", "''") + "'"
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
    $launchId = [guid]::NewGuid().ToString('N')
    $out = Join-Path (Join-Path $Root 'logs') "worker-$launchId.out.log"
    $err = Join-Path (Join-Path $Root 'logs') "worker-$launchId.err.log"
    Start-Process -FilePath $exe -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', $encoded) -WindowStyle Hidden -RedirectStandardOutput $out -RedirectStandardError $err | Out-Null
}

function Enqueue-Commit {
    if (-not $SourceRoot) { $SourceRoot = Get-RepoRoot (Split-Path -Parent $scriptPath) }
    $SourceRoot = Get-RepoRoot $SourceRoot
    if (-not $Commit) {
        $commitLines = @(& git -C $SourceRoot rev-parse HEAD 2>$null)
        $code = $LASTEXITCODE
        if ($code -ne 0 -or -not $commitLines) { throw "Could not read HEAD from $SourceRoot" }
        $Commit = ([string]($commitLines | Select-Object -First 1)).Trim()
    }
    if ($Commit -notmatch '^[0-9a-fA-F]{40}$') { throw "Invalid commit SHA: $Commit" }
    $root = if ($StateRoot) { [IO.Path]::GetFullPath($StateRoot) } else { Get-StateRoot $SourceRoot }
    foreach ($dir in @($root, (Join-Path $root 'jobs'), (Join-Path $root 'results'), (Join-Path $root 'logs'), (Join-Path $root 'builds'))) {
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
    }
    $jobPath = Join-Path (Join-Path $root 'jobs') "$Commit.json"
    $resultPath = Join-Path (Join-Path $root 'results') "$Commit.json"
    if (Test-Path -LiteralPath $resultPath) {
        Write-Output "Android distribution already resolved for $Commit. State: $root"
        return
    }
    $alreadyQueued = Test-Path -LiteralPath $jobPath
    $job = [ordered]@{ commit = $Commit.ToLowerInvariant(); sourceRoot = $SourceRoot; queuedAt = [DateTime]::UtcNow.ToString('o') }
    if (-not $alreadyQueued) {
        $temp = "$jobPath.$([guid]::NewGuid().ToString('N')).tmp"
        [IO.File]::WriteAllText($temp, ($job | ConvertTo-Json), (New-Object Text.UTF8Encoding($false)))
        try { [IO.File]::Move($temp, $jobPath) } catch {
            Remove-Item -LiteralPath $temp -Force -ErrorAction SilentlyContinue
            if (-not (Test-Path -LiteralPath $jobPath) -and -not (Test-Path -LiteralPath $resultPath)) { throw }
        }
    }
    try {
        Start-DistributionWorker $root
        Write-Output "Queued Android Firebase distribution for $Commit. State and logs: $root"
    } catch {
        [Console]::Error.WriteLine("Android distribution was queued for $Commit, but worker launch failed. Retry with: powershell.exe -NoProfile -ExecutionPolicy Bypass -File `"$scriptPath`" -Worker -StateRoot `"$root`". $($_.Exception.Message)")
    }
}

function Invoke-QueuedJob([string]$Root, [string]$JobPath) {
    $job = Get-Content -LiteralPath $JobPath -Raw | ConvertFrom-Json
    if ($job.commit -notmatch '^[0-9a-f]{40}$') { throw "Invalid SHA in queue entry $JobPath" }
    $commit = [string]$job.commit
    $logPath = Join-Path (Join-Path $Root 'logs') "$commit.log"
    $buildRoot = Join-Path (Join-Path $Root 'builds') $commit
    $archive = Join-Path (Join-Path $Root 'builds') "$commit.zip"
    $repoRoot = [IO.Path]::GetFullPath($buildRoot)
    $outcome = 'failed'
    $message = $null
    try {
        [IO.File]::WriteAllText($logPath, "Distribution started for source commit $commit at $([DateTime]::UtcNow.ToString('o')).`r`n", (New-Object Text.UTF8Encoding($false)))
        New-Item -ItemType Directory -Path $buildRoot -Force | Out-Null
        $gitRoot = Get-RepoRoot ([string]$job.sourceRoot)
        & git -C $gitRoot archive --format=zip --output=$archive $commit
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $archive)) { throw "git archive failed for $commit" }
        Expand-Archive -LiteralPath $archive -DestinationPath $buildRoot -Force
        $config = Join-Path $job.sourceRoot 'firebase-distribution.local.json'
        $localProps = Join-Path $job.sourceRoot 'local.properties'
        if (-not (Test-Path -LiteralPath $config -PathType Leaf)) { throw "Local Firebase config missing from source checkout: $config" }
        Copy-Item -LiteralPath $config -Destination (Join-Path $buildRoot 'firebase-distribution.local.json') -Force
        if (Test-Path -LiteralPath $localProps -PathType Leaf) { Copy-Item -LiteralPath $localProps -Destination (Join-Path $buildRoot 'local.properties') -Force }
        $runner = Join-Path $buildRoot 'scripts\distribute-android.ps1'
        if (-not (Test-Path -LiteralPath $runner -PathType Leaf)) { throw "Distribution script is absent from committed snapshot $commit" }
        $sourceNotes = "FocusLock Android performance build (source commit $commit)."
        $powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
        if (-not (Test-Path -LiteralPath $powershell)) { $powershell = 'powershell.exe' }
        $previousPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $distributorLines = @(& $powershell -NoProfile -ExecutionPolicy Bypass -File $runner -ConfigPath (Join-Path $buildRoot 'firebase-distribution.local.json') -StatePath (Join-Path $gitRoot 'artifacts\firebase-distribution-state.json') -BuildVariant Performance -Notes $sourceNotes 2>&1 | ForEach-Object { [string]$_ })
            $distributorExitCode = $LASTEXITCODE
        } finally { $ErrorActionPreference = $previousPreference }
        if ($distributorLines.Count -gt 0) { [IO.File]::AppendAllLines($logPath, [string[]]$distributorLines, (New-Object Text.UTF8Encoding($false))) }
        if ($distributorExitCode -ne 0) { throw "Distribution script failed with exit code $distributorExitCode. See $logPath" }
        $outcome = 'completed'
    } catch {
        $message = $_.Exception.Message
        [IO.File]::AppendAllText($logPath, "`r`nFAILED: $message`r`n", (New-Object Text.UTF8Encoding($false)))
    } finally {
        $result = [ordered]@{ commit = $commit; status = $outcome; finishedAt = [DateTime]::UtcNow.ToString('o'); log = $logPath; error = $message }
        Write-AtomicJson (Join-Path (Join-Path $Root 'results') "$commit.json") $result
        Remove-Item -LiteralPath $JobPath -Force
        $fullBuilds = [IO.Path]::GetFullPath((Join-Path $Root 'builds')).TrimEnd('\') + '\'
        $fullArchive = [IO.Path]::GetFullPath($archive)
        try {
            if ($fullArchive.StartsWith($fullBuilds, [StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $fullArchive)) { Remove-Item -LiteralPath $fullArchive -Force }
            if ($repoRoot.StartsWith($fullBuilds, [StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $repoRoot)) { Remove-Item -LiteralPath $repoRoot -Recurse -Force }
        } catch {
            [IO.File]::AppendAllText($logPath, "`r`nCLEANUP WARNING: $($_.Exception.Message)`r`n", (New-Object Text.UTF8Encoding($false)))
        }
    }
    Write-Output "$outcome $commit (log: $logPath)"
}

if ($Enqueue) { Enqueue-Commit; exit 0 }
if (-not $Worker) { throw 'Specify -Enqueue (hook mode) or -Worker (background mode).' }
if (-not $StateRoot) {
    $workerRepo = Get-RepoRoot (Split-Path -Parent $scriptPath)
    $StateRoot = Get-StateRoot $workerRepo
}
$stateRootFull = [IO.Path]::GetFullPath($StateRoot)
# Hooks inherit Git's repository-local environment. A detached background
# process must not accidentally apply it to git -C calls for another checkout.
foreach ($name in @('GIT_DIR', 'GIT_WORK_TREE', 'GIT_INDEX_FILE', 'GIT_COMMON_DIR', 'GIT_PREFIX', 'GIT_OBJECT_DIRECTORY', 'GIT_ALTERNATE_OBJECT_DIRECTORIES', 'GIT_QUARANTINE_PATH')) {
    Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
}
New-Item -ItemType Directory -Path $stateRootFull -Force | Out-Null
$lockPath = Join-Path $stateRootFull 'worker.lock'
$lock = $null
for ($attempt = 0; $attempt -lt 120 -and -not $lock; $attempt++) {
    try { $lock = New-Object IO.FileStream($lockPath, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None) }
    catch [IO.IOException] { Start-Sleep -Seconds 5 }
}
if (-not $lock) { throw "Could not acquire distribution queue lock. Jobs remain queued under $stateRootFull" }
try {
    while ($true) {
        $jobsDir = Join-Path $stateRootFull 'jobs'
        $next = Get-ChildItem -LiteralPath $jobsDir -Filter '*.json' -File -ErrorAction SilentlyContinue | Sort-Object { try { ([DateTime](Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json).queuedAt).ToUniversalTime() } catch { [DateTime]::MaxValue } } | Select-Object -First 1
        if (-not $next) { break }
        Invoke-QueuedJob $stateRootFull $next.FullName
    }
} finally { $lock.Dispose() }
