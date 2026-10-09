param(
    [string]$HelperPath = (Join-Path (Split-Path -Parent $PSScriptRoot) 'desktop\src-tauri\resources\void\FocusLock.Void.exe'),
    [string]$DotNetPath = $env:FOCUSLOCK_DOTNET
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$fixtureRoot = Join-Path $projectRoot 'artifacts\void-smoke'
New-Item -ItemType Directory -Path $fixtureRoot -Force | Out-Null
if (-not (Test-Path -LiteralPath $HelperPath)) { throw 'Build the helper before running this check.' }
if (-not $DotNetPath) { $DotNetPath = (Get-Command dotnet -ErrorAction Stop).Source }
# A disposable work window proves launching without touching any real user app.
@'
<Project Sdk="Microsoft.NET.Sdk"><PropertyGroup><OutputType>WinExe</OutputType><TargetFramework>net8.0-windows</TargetFramework><UseWPF>true</UseWPF><AssemblyName>FocusLockSmokeTool</AssemblyName></PropertyGroup></Project>
'@ | Set-Content -LiteralPath (Join-Path $fixtureRoot 'Tool.csproj') -Encoding utf8
@'
using System;
using System.Windows;
public static class Program {
    [STAThread] public static void Main() {
        new Application().Run(new Window { Title = "FocusLock disposable work tool", Width = 360, Height = 180, Content = "Approved tool launch verified" });
    }
}
'@ | Set-Content -LiteralPath (Join-Path $fixtureRoot 'Program.cs') -Encoding utf8
& $DotNetPath build (Join-Path $fixtureRoot 'Tool.csproj') -c Release --nologo -o (Join-Path $fixtureRoot 'tool')
if ($LASTEXITCODE -ne 0) { throw 'Could not build the disposable work tool.' }
$toolPath = Join-Path $fixtureRoot 'tool\FocusLockSmokeTool.exe'
Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class VoidSmokeNative {
    [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr window, uint message, IntPtr wParam, IntPtr lParam);
}
'@
$results = [System.Collections.Generic.List[string]]::new()
function New-Helper {
    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $HelperPath
    $start.Arguments = '--focuslock'
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.WindowStyle = [System.Diagnostics.ProcessWindowStyle]::Hidden
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.StandardInputEncoding = [System.Text.UTF8Encoding]::new($false)
    $start.StandardOutputEncoding = [System.Text.UTF8Encoding]::new($false)
    $start.Environment['VOID_USER_DIRECTORY'] = Join-Path $fixtureRoot 'isolated-user'
    $start.Environment['DOTNET_ROOT'] = Split-Path -Parent $DotNetPath
    $child = [System.Diagnostics.Process]::Start($start)
    $script:ownedHelper = $child
    return $child
}
function State-Json([int]$tracked = 0, [bool]$running = $false, [int]$remaining = 60) {
    return @{ protocolVersion = 1; title = 'Finish one important task'; projectName = $null; phase = 'working'; cycleDate = '2026-10-05'; trackedSeconds = $tracked; requiredSeconds = 60; tickedOff = $false; running = $running; remainingSeconds = $remaining; blockMinutes = 1; tools = @(@{id = 'FocusLockSmokeTool.exe'; label = 'Disposable work tool'; executablePath = $toolPath}); domains = @() } | ConvertTo-Json -Depth 5 -Compress
}
function Ready([System.Diagnostics.Process]$child) {
    $child.StandardInput.WriteLine((State-Json))
    $child.StandardInput.Flush()
    $read = $child.StandardOutput.ReadLineAsync()
    if (-not $read.Wait(7000)) { throw 'The helper never acknowledged readiness.' }
    if (-not $read.Result) { throw "The helper exited before readiness: $($child.StandardError.ReadToEnd())" }
    $reply = $read.Result | ConvertFrom-Json
    if ($reply.protocolVersion -ne 1 -or $reply.action -ne 'ready') { throw 'Unexpected startup reply.' }
}
function Get-Window([System.Diagnostics.Process]$child) {
    $condition = [System.Windows.Automation.PropertyCondition]::new([System.Windows.Automation.AutomationElement]::ProcessIdProperty, $child.Id)
    $deadline = [DateTime]::UtcNow.AddSeconds(5)
    do {
        $window = [System.Windows.Automation.AutomationElement]::RootElement.FindFirst([System.Windows.Automation.TreeScope]::Children, $condition)
        if ($window) { return $window }
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'The ready helper has no accessible window.'
}
function Find-Control($window, [string]$name) {
    $condition = [System.Windows.Automation.PropertyCondition]::new([System.Windows.Automation.AutomationElement]::NameProperty, $name)
    $control = $window.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $condition)
    if (-not $control) { throw "Control missing: $name" }
    return $control
}
function Click-Control($window, [string]$name) {
    $control = Find-Control $window $name
    $control.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke()
}
function Read-Action([System.Diagnostics.Process]$child, [string]$expected) {
    $read = $child.StandardOutput.ReadLineAsync()
    if (-not $read.Wait(3000)) { throw "No reply for $expected" }
    $reply = $read.Result | ConvertFrom-Json
    if ($reply.protocolVersion -ne 1 -or $reply.action -ne $expected) { throw "Unexpected action instead of $expected" }
}
$ownedHelper = $null
$ownedTool = $null
try {
    $child = New-Helper
    Ready $child
    $window = Get-Window $child
    Find-Control $window 'Finish one important task' | Out-Null
    $results.Add('Ready acknowledgement and task title rendered with null project.')
    Click-Control $window 'Start focus'
    Read-Action $child 'toggle_timer'
    $child.StandardInput.WriteLine((State-Json -tracked 30 -running $true -remaining 30))
    $child.StandardInput.Flush()
    Start-Sleep -Milliseconds 250
    Click-Control $window 'Pause focus'
    Read-Action $child 'toggle_timer'
    $results.Add('Start and pause controls emit host actions.')
    $child.StandardInput.WriteLine((State-Json -tracked 60 -remaining 0))
    $child.StandardInput.Flush()
    Start-Sleep -Milliseconds 250
    $taskDone = Find-Control $window 'Mark this task complete'
    $toggle = $taskDone.GetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern)
    if ($toggle.Current.ToggleState -ne [System.Windows.Automation.ToggleState]::Off) { throw 'Timer expiry auto-completed the task.' }
    $toggle.Toggle()
    Read-Action $child 'tick_off'
    $results.Add('Timer expiry does not mark the task done; task action is explicit.')
    Click-Control $window 'Disposable work tool'
    $deadline = [DateTime]::UtcNow.AddSeconds(5)
    do {
        $ownedTool = Get-Process -Name FocusLockSmokeTool -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $toolPath } | Select-Object -First 1
        if ($ownedTool) { break }
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $deadline)
    if (-not $ownedTool) { throw 'Approved disposable work tool did not launch.' }
    $results.Add('Approved work tool launched successfully.')
    Click-Control $window 'Open FocusLock'
    Read-Action $child 'open_focuslock'
    $handle = [IntPtr]$window.Current.NativeWindowHandle
    if (-not [VoidSmokeNative]::PostMessage($handle, 0x8003, [IntPtr]::Zero, [IntPtr]::Zero)) { throw 'Could not request return to the launcher.' }
    Start-Sleep -Milliseconds 250
    $window = Get-Window $child
    if ($window.Current.IsOffscreen) { throw 'The launcher did not restore after returning from FocusLock.' }
    $results.Add('The launcher restores through its owned-window return message.')
    $child.StandardInput.Close()
    if (-not $child.WaitForExit(5000)) { throw 'The helper stayed open after host EOF.' }
    $results.Add('Host disconnect closes the native window.')
    $child.Dispose()
    $ownedHelper = $null
    $ownedTool.CloseMainWindow() | Out-Null
    if (-not $ownedTool.WaitForExit(3000)) { $ownedTool.Kill() }
    $ownedTool.Dispose()
    $ownedTool = $null
    $child = New-Helper
    Ready $child
    $child.StandardInput.WriteLine('{"protocolVersion":99}')
    $child.StandardInput.Flush()
    if (-not $child.WaitForExit(5000)) { throw 'Malformed/unsupported state did not close the helper.' }
    $results.Add('Invalid protocol state closes safely.')
    $child.Dispose()
    $ownedHelper = $null
    $child = New-Helper
    Ready $child
    if (-not $child.WaitForExit(14000)) { throw 'Heartbeat loss did not close the helper.' }
    $results.Add('Heartbeat timeout closes the helper.')
    $child.Dispose()
    $ownedHelper = $null
    @{ passed = $true; checks = @($results); helper = $HelperPath } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $fixtureRoot 'result.json') -Encoding utf8
    $results | ForEach-Object { Write-Output "PASS: $_" }
} finally {
    # Cleanup only processes started and identified by this test.
    if ($ownedHelper) { if (-not $ownedHelper.HasExited) { $ownedHelper.Kill(); $ownedHelper.WaitForExit() }; $ownedHelper.Dispose() }
    if ($ownedTool) { if (-not $ownedTool.HasExited) { $ownedTool.CloseMainWindow() | Out-Null; if (-not $ownedTool.WaitForExit(2000)) { $ownedTool.Kill() } }; $ownedTool.Dispose() }
}
