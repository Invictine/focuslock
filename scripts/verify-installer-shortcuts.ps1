param(
    [string] $Makensis = '',
    [switch] $KeepTemp
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$hook = Join-Path $repo 'desktop\src-tauri\installer-hooks.nsh'
if (-not (Test-Path -LiteralPath $hook)) { throw "Missing installer hook: $hook" }

if (-not $Makensis) {
    $command = Get-Command makensis.exe -ErrorAction SilentlyContinue
    if ($command) { $Makensis = $command.Source }
    if (-not $Makensis) {
        $candidates = Get-ChildItem "$env:LOCALAPPDATA\tauri\NSIS", "$env:ProgramFiles\NSIS*", "${env:ProgramFiles(x86)}\NSIS*" -Filter makensis.exe -Recurse -ErrorAction SilentlyContinue
        if ($candidates) { $Makensis = $candidates[0].FullName }
    }
}
if (-not $Makensis -or -not (Test-Path -LiteralPath $Makensis)) {
    throw 'makensis.exe was not found. Pass -Makensis C:\path\to\makensis.exe.'
}

$temp = Join-Path ([IO.Path]::GetTempPath()) ('focuslock-installer-shortcuts-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $temp | Out-Null
try {
    $source = Join-Path $temp 'shortcut-test.nsi'
    $out = Join-Path $temp 'ShortcutTest.exe'
    $hookInclude = $hook
    @"
!include MUI2.nsh
!include FileFunc.nsh
!include LogicLib.nsh
RequestExecutionLevel user
!define MAINBINARYNAME "FocusLock"
!define PRODUCTNAME "FocusLock"
!define VERSION "0.0.0"
!define MANUFACTURER "FocusLock"
!define INSTALLMODE "currentUser"
!define UNINSTKEY "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\FocusLock"
!define STARTMENUFOLDER ""
!define HOMEPAGE ""
!define ESTIMATEDSIZE 1
OutFile "$out"
Var NoShortcutMode
Var UpdateMode
!include "$hookInclude"

Function CreateOrUpdateStartMenuShortcut
  StrCmp `$UpdateMode 1 0 +2
    Return
  CreateDirectory "`$EXEDIR\\StartMenu"
  CreateShortcut "`$EXEDIR\StartMenu\FocusLock.lnk" "`$EXEDIR\ShortcutTest.exe" ""
FunctionEnd
Function CreateOrUpdateDesktopShortcut
  StrCmp `$UpdateMode 1 0 +2
    Return
  CreateShortcut "`$EXEDIR\Desktop\FocusLock.lnk" "`$EXEDIR\ShortcutTest.exe" ""
FunctionEnd
Function .onInit
  StrCpy `$UpdateMode 0
  StrCpy `$NoShortcutMode 0
  `${GetOptions} `$CMDLINE "/UPDATE" `$R0
  IfErrors +2 0
    StrCpy `$UpdateMode 1
  `${GetOptions} `$CMDLINE "/NS" `$R0
  IfErrors +2 0
    StrCpy `$NoShortcutMode 1
  CreateDirectory "`$EXEDIR\\Desktop"
FunctionEnd
AutoCloseWindow true
Section
  Push `$UpdateMode
  !insertmacro NSIS_HOOK_POSTINSTALL
  Pop `$R0
  StrCmp `$R0 `$UpdateMode +2 0
    SetErrorLevel 2
SectionEnd
"@ | Set-Content -LiteralPath $source -Encoding UTF8

    function Invoke-Case([string]$name, [string]$arguments, [bool]$expect) {
        $case = Join-Path $temp $name
        New-Item -ItemType Directory -Path $case | Out-Null
        Copy-Item $out (Join-Path $case 'ShortcutTest.exe')
        $run = Start-Process -FilePath (Join-Path $case 'ShortcutTest.exe') -ArgumentList $arguments -Wait -PassThru -WindowStyle Hidden
        if ($run.ExitCode -ne 0) { throw "Case $name exited $($run.ExitCode)" }
        $shell = New-Object -ComObject WScript.Shell
        foreach ($path in @((Join-Path $case 'StartMenu\FocusLock.lnk'),(Join-Path $case 'Desktop\FocusLock.lnk'))) {
            if ((Test-Path -LiteralPath $path) -ne $expect) { throw "Case $name expected shortcut=$expect at $path" }
            if ($expect) {
                $shortcut = $shell.CreateShortcut($path)
                if ($shortcut.TargetPath -ne (Join-Path $case 'ShortcutTest.exe') -or $shortcut.Arguments -ne '') { throw "Invalid shortcut target/arguments: $path" }
            }
        }
    }
    & $Makensis /V2 /NOCD "/DOUTFILE=$out" $source | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "makensis failed with exit code $LASTEXITCODE" }
    Invoke-Case 'fresh-silent' '/S' $true
    Invoke-Case 'update-missing' '/UPDATE /S' $true
    Invoke-Case 'explicit-opt-out' '/NS /S' $false
    Invoke-Case 'update-opt-out' '/UPDATE /NS /S' $false
    Invoke-Case 'interactive' '/P' $true
    Write-Output 'PASS: 5 native shortcut cases, targets/arguments and restored update mode.'
}
finally {
    $resolved = [IO.Path]::GetFullPath($temp)
    $tempPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if (!$resolved.StartsWith($tempPrefix,[StringComparison]::OrdinalIgnoreCase) -or (Split-Path $resolved -Leaf) -notlike 'focuslock-installer-shortcuts-*') { throw 'Unsafe fixture cleanup path' }
    if (-not $KeepTemp) { Remove-Item -LiteralPath $resolved -Recurse -Force -ErrorAction SilentlyContinue }
    else { Write-Output "Kept test directory: $temp" }
}
