[CmdletBinding()]
param([switch]$ValidateOnly)

$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$hookSource = Join-Path $repoRoot '.githooks\post-commit'
$enqueueScript = Join-Path $repoRoot 'scripts\auto-distribute-android.ps1'
if (-not (Test-Path -LiteralPath $hookSource -PathType Leaf)) { throw "Tracked hook source is missing: $hookSource" }
if (-not (Test-Path -LiteralPath $enqueueScript -PathType Leaf)) { throw "Queue script is missing: $enqueueScript" }
$git = Get-Command git -ErrorAction SilentlyContinue
if (-not $git) { throw 'Git was not found on PATH.' }
$rootLines = @(& git -C $repoRoot rev-parse --show-toplevel 2>$null)
$gitExitCode = $LASTEXITCODE
$root = $rootLines | Select-Object -First 1
$rootFull = if ($root) { [IO.Path]::GetFullPath([string]$root).TrimEnd('\', '/') } else { '' }
$repoRootFull = $repoRoot.TrimEnd('\', '/')
if ($gitExitCode -ne 0 -or -not [string]::Equals($rootFull, $repoRootFull, [StringComparison]::OrdinalIgnoreCase)) { throw 'Could not verify the FocusLock repository root.' }
$configuredHookLines = @(& git -C $repoRoot config --path --get core.hooksPath 2>$null)
$gitExitCode = $LASTEXITCODE
if ($gitExitCode -eq 0 -and $configuredHookLines.Count -gt 0) {
    $configuredHooks = [string]$configuredHookLines[0]
    if (-not [IO.Path]::IsPathRooted($configuredHooks)) { $configuredHooks = Join-Path $repoRoot $configuredHooks }
    $hookPath = Join-Path $configuredHooks 'post-commit'
} else {
    $hookLines = @(& git -C $repoRoot rev-parse --git-path hooks/post-commit 2>$null)
    $gitExitCode = $LASTEXITCODE
    $hookPath = $hookLines | Select-Object -First 1
}
if ($gitExitCode -ne 0 -or -not $hookPath) { throw 'Git could not resolve the active post-commit hook path.' }
if (-not [IO.Path]::IsPathRooted([string]$hookPath)) { $hookPath = Join-Path $repoRoot ([string]$hookPath) }
$hookPath = [IO.Path]::GetFullPath([string]$hookPath)
$sidecar = "$hookPath.focuslock-distribution-user"
$marker = '# FocusLock Android distribution hook'

if ($ValidateOnly) {
    Write-Output "Repository: $repoRoot"
    Write-Output "Active post-commit hook path: $hookPath"
    Write-Output 'Validation only: no hook was installed or distribution queued.'
    exit 0
}

$hooksDir = Split-Path -Parent $hookPath
New-Item -ItemType Directory -Path $hooksDir -Force | Out-Null
$newHook = Get-Content -LiteralPath $hookSource -Raw
$oldHookExists = Test-Path -LiteralPath $hookPath -PathType Leaf
$oldHook = if ($oldHookExists) { Get-Content -LiteralPath $hookPath -Raw } else { '' }
if ($oldHook -match [regex]::Escape($marker)) {
    Write-Output "Android distribution hook is already installed at $hookPath."
    exit 0
}

if (-not $oldHookExists) {
    $sourceText = (Get-Content -LiteralPath $hookSource -Raw).Replace("`r`n", "`n").Replace("`r", "`n")
    [IO.File]::WriteAllText($hookPath, $sourceText, (New-Object Text.UTF8Encoding($false)))
    Write-Output "Installed Android distribution hook at $hookPath."
    exit 0
}

# Preserve an existing hook byte-for-byte and chain it through a stable sidecar.
if (-not (Test-Path -LiteralPath $sidecar)) { [IO.File]::Copy($hookPath, $sidecar, $false) }
$newHookUnix = $hookPath + '.focuslock-distribution'
$sourceText = (Get-Content -LiteralPath $hookSource -Raw).Replace("`r`n", "`n").Replace("`r", "`n")
[IO.File]::WriteAllText($newHookUnix, $sourceText, (New-Object Text.UTF8Encoding($false)))
$wrapper = @"
#!/bin/sh
# FocusLock Android distribution hook wrapper
hook_dir=`$(dirname -- "`$0")
"`$hook_dir/post-commit.focuslock-distribution-user" "`$@"
"`$hook_dir/post-commit.focuslock-distribution" "`$@"
exit 0
"@
$wrapper = $wrapper.Replace("`r`n", "`n").Replace("`r", "`n")
[IO.File]::WriteAllText($hookPath, $wrapper, (New-Object Text.UTF8Encoding($false)))
Write-Output "Installed chained Android distribution hook at $hookPath; preserved the prior hook at $sidecar."
