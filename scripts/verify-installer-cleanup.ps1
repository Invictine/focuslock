[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$cleanup = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\desktop\src-tauri\installer\cleanup.ps1'))
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('focuslock-cleanup-fixture-' + [guid]::NewGuid().ToString('N'))
$oldAppData = $env:APPDATA
$oldLocalAppData = $env:LOCALAPPDATA
$fixtureProcess = $null
$taskName = $null
try {
    $env:APPDATA = Join-Path $fixtureRoot 'roaming'
    $env:LOCALAPPDATA = Join-Path $fixtureRoot 'local'
    $install = Join-Path $fixtureRoot 'install'
    $data = Join-Path $env:APPDATA 'com.focuslock.desktop'
    $startup = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\Startup'
    New-Item -ItemType Directory -Path $install,$data,$startup,$env:LOCALAPPDATA -Force | Out-Null
    $exe = Join-Path $install 'focuslock-desktop.exe'
    Copy-Item -LiteralPath "$env:SystemRoot\System32\cmd.exe" -Destination $exe
    foreach ($name in @('recovery-owner.json','recovery-ready.json','recovery-123.stop','activity-v1.json')) {
        Set-Content -LiteralPath (Join-Path $data $name) -Value 'fixture'
    }
    $shell = New-Object -ComObject WScript.Shell
    $link = Join-Path $startup 'FocusLock.lnk'
    $shortcut = $shell.CreateShortcut($link)
    $shortcut.TargetPath = "$env:SystemRoot\System32\wscript.exe"
    $shortcut.Arguments = '"C:\fixture\focuslock.vbs"'
    $shortcut.Save()
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $digest = $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($data)) } finally { $sha.Dispose() }
    $taskName = 'FocusLock Recovery ' + ([BitConverter]::ToString($digest).Replace('-','').ToLowerInvariant().Substring(0,16))
    if (Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue) { throw 'Fixture task collision' }
    $action = New-ScheduledTaskAction -Execute "$env:SystemRoot\System32\cmd.exe" -Argument '/c exit 0'
    Register-ScheduledTask -TaskName $taskName -Action $action -Description 'FocusLock desktop recovery' | Out-Null
    $fixtureProcess = Start-Process -FilePath $exe -ArgumentList '/c "ping -n 60 127.0.0.1 >nul"' -WindowStyle Hidden -PassThru
    $result = Start-Process -FilePath "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -ArgumentList ('-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "'+$cleanup+'" -InstallDir "'+$install+'" -Upgrade') -WindowStyle Hidden -PassThru -Wait
    if ($result.ExitCode -ne 0) { throw "Cleanup failed: $($result.ExitCode)" }
    $fixtureProcess.Refresh()
    if (!$fixtureProcess.HasExited) { throw 'Fixture process survived cleanup' }
    if (Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue) { throw 'Recovery task survived cleanup' }
    foreach ($name in @('recovery-owner.json','recovery-ready.json','recovery-123.stop')) {
        if (Test-Path -LiteralPath (Join-Path $data $name)) { throw "Recovery file survived: $name" }
    }
    if (Test-Path -LiteralPath $link) { throw 'Legacy startup shortcut survived' }
    if (!(Test-Path -LiteralPath (Join-Path $data 'activity-v1.json')) -or !(Test-Path -LiteralPath $exe)) { throw 'User data or install files were removed' }
    $shortcut = $shell.CreateShortcut($link)
    $shortcut.TargetPath = "$env:SystemRoot\System32\notepad.exe"
    $shortcut.Save()
    $result = Start-Process -FilePath "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -ArgumentList ('-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "'+$cleanup+'" -InstallDir "'+$install+'"') -WindowStyle Hidden -PassThru -Wait
    if ($result.ExitCode -ne 0 -or !(Test-Path -LiteralPath $link)) { throw 'Repeat cleanup failed or removed an unrelated shortcut' }
    Write-Output 'PASS: native cleanup stops fixture process/task, removes legacy startup/recovery, preserves unrelated shortcut and user data; repeat cleanup succeeds.'
} finally {
    $env:APPDATA = $oldAppData
    $env:LOCALAPPDATA = $oldLocalAppData
    if ($fixtureProcess -and !$fixtureProcess.HasExited) { Stop-Process -Id $fixtureProcess.Id -Force -ErrorAction SilentlyContinue }
    if ($taskName) { Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue }
    $resolved = [IO.Path]::GetFullPath($fixtureRoot)
    $tempPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if (!$resolved.StartsWith($tempPrefix,[StringComparison]::OrdinalIgnoreCase) -or (Split-Path $resolved -Leaf) -notlike 'focuslock-cleanup-fixture-*') { throw 'Unsafe fixture cleanup path' }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
