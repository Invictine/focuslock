[CmdletBinding()]
param(
  [string]$ExePath,
  [string]$MakensisPath,
  [string]$EvidenceRoot
)
$ErrorActionPreference = 'Stop'
if (!$ExePath) { $ExePath = Join-Path $PSScriptRoot '..\desktop\src-tauri\target\release\focuslock-desktop.exe' }
if (!$MakensisPath) { $MakensisPath = Join-Path $env:LOCALAPPDATA 'tauri\NSIS\makensis.exe' }
if (!$EvidenceRoot) { $EvidenceRoot = Join-Path ([IO.Path]::GetTempPath()) ('focuslock-strict-uninstall-' + [guid]::NewGuid().ToString('N')) }
$exe = [IO.Path]::GetFullPath($ExePath)
$makensis = [IO.Path]::GetFullPath($MakensisPath)
$hook = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\desktop\src-tauri\installer-hooks.nsh'))
foreach ($path in @($exe,$makensis,$hook)) { if (!(Test-Path -LiteralPath $path)) { throw "Required path missing: $path" } }
New-Item -ItemType Directory -Force -Path $EvidenceRoot | Out-Null
$oldAppData = $env:APPDATA; $oldLocalAppData = $env:LOCALAPPDATA
$results = [Collections.Generic.List[object]]::new()

function Invoke-Case([string]$Name, [string]$State, [bool]$ShouldBlock, [bool]$Update) {
  $root = Join-Path $EvidenceRoot $Name; $install = Join-Path $root 'install'; $appData = Join-Path $root 'appdata'
  New-Item -ItemType Directory -Force -Path $install,$appData | Out-Null
  Copy-Item -LiteralPath $exe -Destination (Join-Path $install 'focuslock-desktop.exe')
  Set-Content -LiteralPath (Join-Path $install 'sentinel.txt') -Value 'sentinel' -NoNewline
  if (![string]::IsNullOrEmpty($State)) { $statePath = Join-Path $appData 'com.focuslock.desktop\strict-uninstall-v1.json'; New-Item -ItemType Directory -Force -Path (Split-Path $statePath) | Out-Null; Set-Content -LiteralPath $statePath -Value $State -NoNewline }
  $out = Join-Path $root 'setup.exe'; $script = Join-Path $root 'harness.nsi'; $hookPath = $hook; $exePath = $exe; $outPath = $out; $installPath = $install
  $scriptText = @"
!include "LogicLib.nsh"
!define PRODUCTNAME "FocusLock QA"
!define MAINBINARYNAME "focuslock-desktop"
!include "$hookPath"
Name "FocusLock QA"
OutFile "$outPath"
InstallDir "$installPath"
RequestExecutionLevel user
SilentInstall silent
Section
  SetOutPath `$INSTDIR
  File /oname=focuslock-desktop.exe "$exePath"
  WriteUninstaller `$INSTDIR\uninstall.exe
SectionEnd
Section Uninstall
  !insertmacro NSIS_HOOK_PREUNINSTALL
  Delete `$INSTDIR\sentinel.txt
  Delete `$INSTDIR\focuslock-desktop.exe
  Delete `$INSTDIR\uninstall.exe
SectionEnd
"@
  Set-Content -LiteralPath $script -Value $scriptText
  $compileOutput = & $makensis /V1 $script 2>&1
  if ($LASTEXITCODE -ne 0) { throw "NSIS compilation failed: $Name`n$($compileOutput -join [Environment]::NewLine)" }
  if (!(Test-Path -LiteralPath $out)) { throw "NSIS compilation failed: $Name" }
  $env:APPDATA = $appData; $env:LOCALAPPDATA = $appData
  $setup = Start-Process -FilePath $out -ArgumentList @('/S', '/D=' + $install) -WindowStyle Hidden -Wait -PassThru
  if ($setup.ExitCode -ne 0) { throw "NSIS setup failed: $Name (exit $($setup.ExitCode))" }
  $probe = Start-Process -FilePath (Join-Path $install 'focuslock-desktop.exe') -ArgumentList '--focuslock-check-uninstall' -WindowStyle Hidden -Wait -PassThru
  $expectedProbe = if ($Name -eq 'missing-executable') { 0 } elseif ($ShouldBlock) { if ($Name -eq 'malformed') { 11 } else { 10 } } else { 0 }
  if ($probe.ExitCode -ne $expectedProbe) { throw "Probe $Name returned $($probe.ExitCode), expected $expectedProbe" }
  if ($Name -eq 'missing-executable') { Remove-Item -LiteralPath (Join-Path $install 'focuslock-desktop.exe') }
  $args = @('/S'); if ($Update) { $args += '/UPDATE' }; $args += ('_?=' + $install)
  $p = Start-Process -FilePath (Join-Path $install 'uninstall.exe') -ArgumentList $args -WindowStyle Hidden -Wait -PassThru
  $exeStill = Test-Path -LiteralPath (Join-Path $install 'focuslock-desktop.exe'); $sentinelStill = Test-Path -LiteralPath (Join-Path $install 'sentinel.txt'); $blocked = $exeStill -and $sentinelStill
  $ok = if ($Name -eq 'missing-executable') { $p.ExitCode -ne 0 -and $sentinelStill } elseif ($ShouldBlock) { $p.ExitCode -ne 0 -and $blocked } else { $p.ExitCode -eq 0 -and !$exeStill -and !$sentinelStill }
  $results.Add([pscustomobject]@{case=$Name; exitCode=$p.ExitCode; passed=$ok; exePresent=$exeStill; sentinelPresent=$sentinelStill})
  if (!$ok) { throw "Case $Name failed: exit=$($p.ExitCode), exe=$exeStill, sentinel=$sentinelStill" }
}
try {
  $future = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + 3600000; $past = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() - 3600000
  Invoke-Case 'active' ('{"locks":[{"accountId":"qa","sessionId":"qa-session","endsAt":' + $future + '}]}') $true $false
  Invoke-Case 'active-update' ('{"locks":[{"accountId":"qa","sessionId":"qa-session","endsAt":' + $future + '}]}') $true $true
  Invoke-Case 'malformed' '{bad json' $true $false
  Invoke-Case 'expired' ('{"locks":[{"accountId":"qa","sessionId":"qa-session","endsAt":' + $past + '}]}') $false $false
  Invoke-Case 'missing' $null $false $false
  Invoke-Case 'legacy-indefinite' '{"locks":[{"accountId":"qa","sessionId":"qa-session","endsAt":null}]}' $true $false
  Invoke-Case 'missing-executable' $null $true $false
  Write-Output ("PASS: {0} cases; evidence: {1}" -f $results.Count, $EvidenceRoot)
} finally {
  $env:APPDATA = $oldAppData; $env:LOCALAPPDATA = $oldLocalAppData
  $results | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $EvidenceRoot 'summary.json')
}
