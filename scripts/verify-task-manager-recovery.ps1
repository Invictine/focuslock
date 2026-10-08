param(
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$Exe,
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$DataDir,
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$TaskName
)

$ErrorActionPreference = 'Stop'
$reportDir = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\build\task-manager-recovery'))
$startedPids = [Collections.Generic.HashSet[int]]::new()
$validatedQa = $false
$createdTask = $false
$registryBackup = $null
$report = [ordered]@{
    startedAt = [DateTimeOffset]::Now.ToString('o')
    exe = $null
    dataDir = $null
    taskName = $TaskName
    assertions = [Collections.Generic.List[object]]::new()
    passed = $false
    initialError = $null
    failedTaskInfo = $null
    processDiagnostics = @()
    recoveryLatencySeconds = [ordered]@{ supervisorRecovery = $null; scheduledTaskRecovery = $null }
    cleanupErrors = [Collections.Generic.List[string]]::new()
}

function Assert-Check([bool]$Condition, [string]$Name, [string]$Details = '') {
    $report.assertions.Add([ordered]@{ name = $Name; passed = $Condition; details = $Details })
    New-Item -ItemType Directory -Path $reportDir -Force | Out-Null
    $report | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $reportDir 'report.json') -Encoding UTF8
    if (-not $Condition) { throw "Assertion failed: $Name. $Details" }
}

function Get-Sha256Hex([string]$Value) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value))).Replace('-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
}

function Get-ExactExeProcesses([string]$ImagePath) {
    $expected = [IO.Path]::GetFullPath($ImagePath)
    Get-CimInstance Win32_Process | Where-Object {
        try { $_.ExecutablePath -and [string]::Equals([IO.Path]::GetFullPath([string]$_.ExecutablePath), $expected, [StringComparison]::OrdinalIgnoreCase) }
        catch { $false }
    }
}

function Get-SameNameProcessDiagnostics([string]$ImagePath) {
    $name = [IO.Path]::GetFileName($ImagePath).Replace("'", "''")
    Get-CimInstance Win32_Process -Filter "Name='$name'" -ErrorAction SilentlyContinue | ForEach-Object {
        [pscustomobject]@{
            pid = [int]$_.ProcessId
            parentPid = [int]$_.ParentProcessId
            executablePath = [string]$_.ExecutablePath
            commandLine = [string]$_.CommandLine
        }
    }
}

function Get-UnverifiedQaProcesses([string]$ImagePath, [string]$AppData) {
    $expected = [IO.Path]::GetFullPath($ImagePath)
    Get-SameNameProcessDiagnostics $ImagePath | Where-Object {
        (-not $_.executablePath) -and ($_.commandLine -like "*$expected*" -or $_.commandLine -like "*$AppData*")
    }
}

function Get-RecoveryProcesses([string]$ImagePath, [string]$AppData) {
    Get-ExactExeProcesses $ImagePath | Where-Object {
        [string]$_.CommandLine -match '(?i)--focuslock-supervisor' -and [string]$_.CommandLine -like "*$AppData*"
    }
}

function Get-DesktopProcesses([string]$ImagePath) {
    Get-ExactExeProcesses $ImagePath | Where-Object { [string]$_.CommandLine -notmatch '(?i)--focuslock-supervisor' }
}

function Test-RecoveryTaskAction($Actions, [string]$ImagePath, [string]$AppData) {
    foreach ($action in @($Actions)) {
        $execute = [string]$action.Execute
        $arguments = [string]$action.Arguments
        if ([IO.Path]::GetFileName($execute) -ieq 'powershell.exe' -and
            $arguments -match '(?i)(?:^|\s)-NoProfile(?:\s|$)' -and
            $arguments -match '(?i)(?:^|\s)-NonInteractive(?:\s|$)' -and
            $arguments -match '(?i)(?:^|\s)-WindowStyle\s+Hidden(?:\s|$)' -and
            $arguments -match '(?i)(?:^|\s)-EncodedCommand\s+(?<encoded>[A-Za-z0-9+/=]+)') {
            try {
                $bootstrap = [Text.Encoding]::Unicode.GetString([Convert]::FromBase64String($Matches.encoded))
                $hasExe = $bootstrap.IndexOf($ImagePath, [StringComparison]::OrdinalIgnoreCase) -ge 0
                $hasDataDir = $bootstrap.IndexOf($AppData, [StringComparison]::OrdinalIgnoreCase) -ge 0
                $hasSupervisorMode = $bootstrap.Contains('--focuslock-supervisor')
                $hasWaitAndExit = $bootstrap -match '(?is)\$child\.WaitForExit\s*\(\s*\)' -and $bootstrap -match '(?i)\$child\.ExitCode'
                if ($hasExe -and $hasDataDir -and $hasSupervisorMode -and $hasWaitAndExit -and $bootstrap -match '(?i)Start-Process') { return $true }
            } catch { return $false }
        }
        if ([string]::Equals([IO.Path]::GetFullPath($execute), $ImagePath, [StringComparison]::OrdinalIgnoreCase) -and
            $arguments -match '(?i)--focuslock-supervisor' -and $arguments.Contains($AppData)) { return $true }
    }
    return $false
}

function Stop-ExactProcess([int]$Id, [string]$ImagePath) {
    if ($Id -le 0) { return }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$Id" -ErrorAction SilentlyContinue
    if ($process -and $process.ExecutablePath -and
        [string]::Equals([IO.Path]::GetFullPath([string]$process.ExecutablePath), [IO.Path]::GetFullPath($ImagePath), [StringComparison]::OrdinalIgnoreCase)) {
        Stop-Process -Id $Id -Force -ErrorAction SilentlyContinue
    }
}

function Wait-Until([scriptblock]$Predicate, [int]$Seconds, [string]$Failure) {
    $until = [DateTime]::UtcNow.AddSeconds($Seconds)
    while ([DateTime]::UtcNow -lt $until) {
        if (& $Predicate) { return $true }
        Start-Sleep -Seconds 1
    }
    throw $Failure
}

function ConvertTo-StableJsonValue($Value) {
    if ($null -eq $Value) { return $null }
    if ($Value -is [System.Array]) { return ,@($Value | ForEach-Object { ConvertTo-StableJsonValue $_ }) }
    if ($Value -is [Collections.IDictionary]) {
        $stable = [ordered]@{}
        foreach ($key in ($Value.Keys | Sort-Object)) { $stable[$key] = ConvertTo-StableJsonValue $Value[$key] }
        return $stable
    }
    if ($Value -is [System.Management.Automation.PSCustomObject]) {
        $stable = [ordered]@{}
        foreach ($property in ($Value.PSObject.Properties | Sort-Object Name)) {
            $stable[$property.Name] = ConvertTo-StableJsonValue $property.Value
        }
        return $stable
    }
    return $Value
}

function Get-RuleHash([string]$ActivityPath) {
    $state = Get-Content -LiteralPath $ActivityPath -Raw | ConvertFrom-Json
    $rules = [ordered]@{}
    foreach ($key in @('config','blockedTargets','blockedReasons','permanentTargets','browserProtectionRequired','browserProtectionEnabled','browserProtectionLockedUntilMs')) {
        if ($state.PSObject.Properties.Name -contains $key) { $rules[$key] = $state.$key }
    }
    $json = ConvertTo-Json -InputObject (ConvertTo-StableJsonValue $rules) -Depth 30 -Compress
    return Get-Sha256Hex $json
}

function Get-NativeHostRegistryBackup {
    $bases = @(
        'Software\Google\Chrome\NativeMessagingHosts\com.focuslock.browser',
        'Software\Microsoft\Edge\NativeMessagingHosts\com.focuslock.browser',
        'Software\BraveSoftware\Brave-Browser\NativeMessagingHosts\com.focuslock.browser',
        'Software\Vivaldi\NativeMessagingHosts\com.focuslock.browser',
        'Software\Opera Software\Opera Stable\NativeMessagingHosts\com.focuslock.browser',
        'Software\Opera Software\Opera GX Stable\NativeMessagingHosts\com.focuslock.browser'
    )
    $entries = [Collections.Generic.List[object]]::new()
    foreach ($viewName in @('Registry32','Registry64')) {
        $view = [Enum]::Parse([Microsoft.Win32.RegistryView], $viewName)
        $hive = [Microsoft.Win32.RegistryKey]::OpenBaseKey([Microsoft.Win32.RegistryHive]::CurrentUser, $view)
        try {
            foreach ($subKeyPath in $bases) {
                $key = $hive.OpenSubKey($subKeyPath, $false)
                $keyExisted = $null -ne $key
                $defaultPresent = $false
                $defaultValue = $null
                if ($key) {
                    try {
                        $defaultPresent = @($key.GetValueNames()) -contains ''
                        if ($defaultPresent) {
                            if ($key.GetValueKind('') -ne [Microsoft.Win32.RegistryValueKind]::String) {
                                throw "Native host default value has an unexpected type at $subKeyPath ($viewName)."
                            }
                            $defaultValue = [string]$key.GetValue('', $null, [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
                        }
                    } finally { $key.Dispose() }
                }
                $entries.Add([pscustomobject]@{
                    Path = $subKeyPath
                    View = $viewName
                    KeyExisted = $keyExisted
                    DefaultPresent = $defaultPresent
                    DefaultValue = $defaultValue
                })
            }
        } finally { $hive.Dispose() }
    }
    return ,$entries.ToArray()
}

function Restore-NativeHostRegistry([object[]]$Entries) {
    if ($Entries.Count -ne 12) { throw 'Native-host registry backup is incomplete; no restoration was attempted.' }
    $errors = [Collections.Generic.List[string]]::new()
    foreach ($entry in $Entries) {
        try {
            $view = [Enum]::Parse([Microsoft.Win32.RegistryView], [string]$entry.View)
            $hive = [Microsoft.Win32.RegistryKey]::OpenBaseKey([Microsoft.Win32.RegistryHive]::CurrentUser, $view)
            try {
                $key = $hive.OpenSubKey([string]$entry.Path, $true)
                try {
                    if ($entry.KeyExisted) {
                        if (-not $key) { $key = $hive.CreateSubKey([string]$entry.Path) }
                        if ($entry.DefaultPresent) {
                            $key.SetValue('', [string]$entry.DefaultValue, [Microsoft.Win32.RegistryValueKind]::String)
                        } elseif (@($key.GetValueNames()) -contains '') {
                            $key.DeleteValue('', $false)
                        }
                    } elseif ($key) {
                        if (@($key.GetValueNames()) -contains '') { $key.DeleteValue('', $false) }
                        $empty = (@($key.GetValueNames()).Count -eq 0) -and (@($key.GetSubKeyNames()).Count -eq 0)
                        if ($empty) {
                            $key.Dispose()
                            $key = $null
                            $hive.DeleteSubKey([string]$entry.Path, $false)
                        }
                    }
                } finally { if ($key) { $key.Dispose() } }

                $verify = $hive.OpenSubKey([string]$entry.Path, $false)
                try {
                    if ([bool]$entry.KeyExisted -ne ($null -ne $verify)) { throw "Native-host key existence did not restore at $($entry.Path) ($($entry.View))." }
                    $present = $false
                    $value = $null
                    if ($verify) {
                        $present = @($verify.GetValueNames()) -contains ''
                        if ($present) {
                            if ($verify.GetValueKind('') -ne [Microsoft.Win32.RegistryValueKind]::String) { throw "Native-host default value type did not restore at $($entry.Path) ($($entry.View))." }
                            $value = [string]$verify.GetValue('', $null, [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
                        }
                    }
                    if ($present -ne [bool]$entry.DefaultPresent -or ($present -and $value -cne [string]$entry.DefaultValue)) {
                        throw "Native-host default value did not restore at $($entry.Path) ($($entry.View))."
                    }
                } finally { if ($verify) { $verify.Dispose() } }
            } finally { $hive.Dispose() }
        } catch {
            $errors.Add($_.Exception.Message)
        }
    }
    if ($errors.Count) { throw ($errors -join ' | ') }
}

try {
    $Exe = [IO.Path]::GetFullPath($Exe)
    $DataDir = [IO.Path]::GetFullPath($DataDir)
    $report.exe = $Exe
    $report.dataDir = $DataDir
    Assert-Check (Test-Path -LiteralPath $Exe -PathType Leaf) 'release_executable_exists' $Exe

    $qaLeaf = [IO.Path]::GetFileName($DataDir)
    $allowedRoots = @([Environment]::GetFolderPath('ApplicationData'), [Environment]::GetFolderPath('LocalApplicationData')) |
        Where-Object { $_ } | ForEach-Object { [IO.Path]::GetFullPath($_).TrimEnd('\') + '\' }
    $insideAllowedRoot = $false
    foreach ($root in $allowedRoots) {
        if ($DataDir.StartsWith($root, [StringComparison]::OrdinalIgnoreCase) -and
            [IO.Path]::GetDirectoryName($DataDir).TrimEnd('\').Equals($root.TrimEnd('\'), [StringComparison]::OrdinalIgnoreCase)) { $insideAllowedRoot = $true }
    }
    Assert-Check ($insideAllowedRoot -and $qaLeaf.StartsWith('com.focuslock.browserqa.', [StringComparison]::OrdinalIgnoreCase)) 'isolated_qa_data_directory' $DataDir

    $expectedTaskName = 'FocusLock Recovery ' + (Get-Sha256Hex $DataDir).Substring(0, 16)
    Assert-Check ($TaskName -ceq $expectedTaskName) 'expected_task_name' "Expected '$expectedTaskName'."
    $existingTask = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    Assert-Check (-not $existingTask) 'no_preexisting_task' "Task '$TaskName' already exists; refusing to modify or remove it."
    $existingProcesses = @(Get-ExactExeProcesses $Exe)
    Assert-Check ($existingProcesses.Count -eq 0) 'no_preexisting_qa_processes' "Found $($existingProcesses.Count) processes using the QA executable; close them first."

    $activityPath = Join-Path $DataDir 'activity-v1.json'
    Assert-Check (Test-Path -LiteralPath $activityPath -PathType Leaf) 'rule_state_fixture_available' 'Seed activity-v1.json with QA rule data before running acceptance.'
    $baselineRuleHash = Get-RuleHash $activityPath
    $registryBackup = Get-NativeHostRegistryBackup
    New-Item -ItemType Directory -Path $reportDir -Force | Out-Null
    $registryBackupPath = Join-Path $reportDir 'registry-backup.json'
    $registryBackup | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $registryBackupPath -Encoding UTF8

    # No task, process, or fixture mutation occurs before all ownership checks above pass.
    $validatedQa = $true
    $desktop = Start-Process -FilePath $Exe -ArgumentList @('--background','--recovery-test-task') -WindowStyle Hidden -PassThru -RedirectStandardError (Join-Path $reportDir 'desktop-startup.stderr.log') -RedirectStandardOutput (Join-Path $reportDir 'desktop-startup.stdout.log')
    [void]$startedPids.Add([int]$desktop.Id)
    Assert-Check ($desktop.Id -gt 0) 'qa_desktop_launched' "PID $($desktop.Id)"

    Wait-Until { if ($desktop.HasExited) { throw ('QA desktop exited during startup: ' + $desktop.ExitCode + '; ' + (Get-Content -LiteralPath (Join-Path $reportDir 'desktop-startup.stderr.log') -Raw -ErrorAction SilentlyContinue)) }; Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue } 90 'QA recovery task was not registered.' | Out-Null
    $task = Get-ScheduledTask -TaskName $TaskName
    Assert-Check (Test-RecoveryTaskAction $task.Actions $Exe $DataDir) 'scheduled_task_action' 'Action is the exact QA supervisor command or a hidden PowerShell bootstrap that launches and waits for it.'
    $createdTask = $true
    Wait-Until { $t = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue; $t -and $t.State -eq 'Running' } 90 'QA recovery task did not enter Running state.' | Out-Null
    Assert-Check ([bool]($task.Triggers | Where-Object { $_.Repetition.Interval -eq 'PT1M' })) 'scheduled_task_repeats_each_minute' 'Expected one-minute repetition.'
    Assert-Check ($task.Settings.MultipleInstances -eq 'IgnoreNew') 'task_ignores_new_instances' "MultipleInstances=$($task.Settings.MultipleInstances)."

    Wait-Until { @(Get-RecoveryProcesses $Exe $DataDir).Count -gt 0 } 90 'Supervisor process was not found.' | Out-Null
    $supervisor = @(Get-RecoveryProcesses $Exe $DataDir) | Select-Object -First 1
    [void]$startedPids.Add([int]$supervisor.ProcessId)
    Assert-Check ([int]$supervisor.ParentProcessId -ne [int]$desktop.Id) 'supervisor_not_desktop_child' "Desktop PID $($desktop.Id); supervisor PID $($supervisor.ProcessId), parent $($supervisor.ParentProcessId)."
    $ownerPath = Join-Path $DataDir 'recovery-owner.json'
    Wait-Until {
        if (-not (Test-Path -LiteralPath $ownerPath)) { return $false }
        try { $o = Get-Content -LiteralPath $ownerPath -Raw | ConvertFrom-Json; $o.pid -gt 0 -and $o.created_at -and -not [string]::IsNullOrWhiteSpace([string]$o.generation) -and $o.attempt -ge 0 }
        catch { $false }
    } 90 'recovery-owner.json missing or malformed.' | Out-Null
    $owner = Get-Content -LiteralPath $ownerPath -Raw | ConvertFrom-Json
    $ownerProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$([int]$owner.pid)" -ErrorAction SilentlyContinue
    Assert-Check ($ownerProcess -and $ownerProcess.ExecutablePath -and
        [string]::Equals([IO.Path]::GetFullPath([string]$ownerProcess.ExecutablePath), $Exe, [StringComparison]::OrdinalIgnoreCase)) 'owner_pid_is_qa_process' "Owner PID $($owner.pid)."
    Assert-Check ((Get-Process -Id ([int]$supervisor.ProcessId) -ErrorAction SilentlyContinue) -ne $null) 'supervisor_alive' "PID $($supervisor.ProcessId)"

    # Case one: the persistent supervisor must recover the desktop within 45 seconds.
    $persistentSupervisorPid = [int]$supervisor.ProcessId
    & taskkill.exe /PID $desktop.Id /F /T | Out-Null
    $caseOneStarted = [DateTimeOffset]::UtcNow
    $desktop = $null
    Wait-Until {
        if (-not (Test-Path -LiteralPath $ownerPath)) { return $false }
        try { $o = Get-Content -LiteralPath $ownerPath -Raw | ConvertFrom-Json; [int]$o.pid -gt 0 -and [int]$o.pid -ne [int]$owner.pid }
        catch { $false }
    } 45 'Desktop owner did not recover within 45 seconds while the supervisor was alive.' | Out-Null
    $ownerAfterProcessCrash = Get-Content -LiteralPath $ownerPath -Raw | ConvertFrom-Json
    $processAfterCrash = Get-CimInstance Win32_Process -Filter "ProcessId=$([int]$ownerAfterProcessCrash.pid)" -ErrorAction SilentlyContinue
    Assert-Check ($processAfterCrash -and [string]::Equals([IO.Path]::GetFullPath([string]$processAfterCrash.ExecutablePath), $Exe, [StringComparison]::OrdinalIgnoreCase)) 'desktop_recovered_with_live_supervisor' "New owner PID $($ownerAfterProcessCrash.pid)."
    $report.recoveryLatencySeconds.supervisorRecovery = [Math]::Round(([DateTimeOffset]::UtcNow - $caseOneStarted).TotalSeconds, 3)
    $report.assertions.Add([ordered]@{ name = 'supervisor_recovery_latency'; passed = $true; details = "$($report.recoveryLatencySeconds.supervisorRecovery) seconds from desktop termination to live owner." })
    Assert-Check ((Get-Process -Id $persistentSupervisorPid -ErrorAction SilentlyContinue) -ne $null) 'same_supervisor_survived_desktop_crash' "Supervisor PID $persistentSupervisorPid."
    [void]$startedPids.Add([int]$ownerAfterProcessCrash.pid)
    $desktopProcesses = @(Get-DesktopProcesses $Exe)
    Assert-Check ($desktopProcesses.Count -eq 1) 'one_desktop_after_first_recovery' "Found $($desktopProcesses.Count) exact-path desktop processes."

    # Case two: stop both exact QA PIDs and allow the minute task to recreate recovery.
    $oldOwnerPid = [int]$ownerAfterProcessCrash.pid
    & taskkill.exe /PID $oldOwnerPid /F /T | Out-Null
    Stop-ExactProcess $persistentSupervisorPid $Exe
    $caseTwoStarted = [DateTimeOffset]::UtcNow
    Wait-Until {
        if (-not (Test-Path -LiteralPath $ownerPath)) { return $false }
        try { $o = Get-Content -LiteralPath $ownerPath -Raw | ConvertFrom-Json; [int]$o.pid -gt 0 -and [int]$o.pid -ne $oldOwnerPid }
        catch { $false }
    } 100 'Scheduled task did not recreate recovery and the desktop owner within 100 seconds.' | Out-Null
    $ownerAfterTaskRecovery = Get-Content -LiteralPath $ownerPath -Raw | ConvertFrom-Json
    $taskRecoveredOwnerProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$([int]$ownerAfterTaskRecovery.pid)" -ErrorAction SilentlyContinue
    Assert-Check ($taskRecoveredOwnerProcess -and [string]::Equals([IO.Path]::GetFullPath([string]$taskRecoveredOwnerProcess.ExecutablePath), $Exe, [StringComparison]::OrdinalIgnoreCase)) 'scheduled_task_recovered_desktop' "Owner PID $($ownerAfterTaskRecovery.pid)."
    $report.recoveryLatencySeconds.scheduledTaskRecovery = [Math]::Round(([DateTimeOffset]::UtcNow - $caseTwoStarted).TotalSeconds, 3)
    $report.assertions.Add([ordered]@{ name = 'scheduled_task_recovery_latency'; passed = $true; details = "$($report.recoveryLatencySeconds.scheduledTaskRecovery) seconds from supervisor and desktop termination to live owner." })
    [void]$startedPids.Add([int]$ownerAfterTaskRecovery.pid)
    $newSupervisors = @(Get-RecoveryProcesses $Exe $DataDir)
    Assert-Check ($newSupervisors.Count -eq 1 -and (Get-Process -Id ([int]$newSupervisors[0].ProcessId) -ErrorAction SilentlyContinue)) 'scheduled_supervisor_recreated' "Found $($newSupervisors.Count) matching supervisors."
    [void]$startedPids.Add([int]$newSupervisors[0].ProcessId)
    $desktopProcesses = @(Get-DesktopProcesses $Exe)
    Assert-Check ($desktopProcesses.Count -eq 1) 'one_desktop_after_task_recovery' "Found $($desktopProcesses.Count) exact-path desktop processes."
    Assert-Check (@(Get-RecoveryProcesses $Exe $DataDir).Count -eq 1) 'no_duplicate_supervisors' 'Exactly one matching supervisor is running.'
    Assert-Check ((Get-RuleHash $activityPath) -ceq $baselineRuleHash) 'rule_state_preserved' 'Stable config and block rules hash unchanged.'

    # Disarm the owner fixture, kill the desktop, and observe longer than one minute.
    Remove-Item -LiteralPath $ownerPath -Force
    foreach ($p in @(Get-DesktopProcesses $Exe)) {
        & taskkill.exe /PID $p.ProcessId /F /T | Out-Null
        [void]$startedPids.Add([int]$p.ProcessId)
    }
    1..66 | ForEach-Object { Start-Sleep -Seconds 1 }
    $resurrected = @(Get-DesktopProcesses $Exe)
    Assert-Check ($resurrected.Count -eq 0) 'intentional_quit_disarmed' 'No exact-path QA desktop returned during the 66-second observation; recovery-owner.json was removed first.'
    $report.assertions.Add([ordered]@{ name = 'intentional_quit_simulation'; passed = $true; details = 'This verifies the owner-fixture disarm protocol only.' })
    $report.passed = $true
} catch {
    $report.initialError = $_.Exception.Message
} finally {
    $report.finishedAt = [DateTimeOffset]::Now.ToString('o')
    if (-not $report.passed) {
        $report.processDiagnostics = @(Get-SameNameProcessDiagnostics $Exe)
        $failedTask = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
        if ($failedTask) {
            $taskInfo = Get-ScheduledTaskInfo -TaskName $TaskName -ErrorAction SilentlyContinue
            $report.failedTaskInfo = [ordered]@{
                currentState = [string]$failedTask.State
                lastTaskResult = if ($taskInfo) { $taskInfo.LastTaskResult } else { $null }
                capturedAt = [DateTimeOffset]::Now.ToString('o')
            }
        }
    }
    if ($validatedQa) {
        $taskGone = $true
        try {
            foreach ($desktopProcess in @(Get-DesktopProcesses $Exe)) {
                $stopMarker = Join-Path $DataDir ("recovery-{0}.stop" -f [int]$desktopProcess.ProcessId)
                [IO.File]::WriteAllText($stopMarker, 'intentional QA cleanup')
            }
            Remove-Item -LiteralPath (Join-Path $DataDir 'recovery-owner.json') -Force -ErrorAction SilentlyContinue
        } catch {
            $report.passed = $false
            $report.cleanupErrors.Add("Could not disarm QA recovery before cleanup: $($_.Exception.Message)")
        }
        try {
            if ($createdTask) {
                Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
                Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
            }
            $taskGone = -not (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue)
        } catch {
            $taskGone = $false
            $report.passed = $false
            $report.cleanupErrors.Add("Could not stop or inspect the QA recovery task: $($_.Exception.Message)")
        }
        if (-not $taskGone) {
            $report.passed = $false
            $report.cleanupErrors.Add("QA recovery task '$TaskName' remains registered; registry restoration was skipped to prevent it rewriting the browser host values.")
        }
        for ($pass = 0; $pass -lt 8; $pass++) {
            $watchdogs = @(Get-ExactExeProcesses $Exe | Where-Object { [string]$_.CommandLine -match '(?i)--focuslock-(?:supervisor|watchdog)' })
            foreach ($process in $watchdogs) { Stop-ExactProcess ([int]$process.ProcessId) $Exe }
            Start-Sleep -Milliseconds 300
            $desktops = @(Get-DesktopProcesses $Exe)
            foreach ($process in $desktops) { Stop-ExactProcess ([int]$process.ProcessId) $Exe }
            Start-Sleep -Milliseconds 300
            if (@(Get-ExactExeProcesses $Exe).Count -eq 0) { break }
        }
        $remainingProcesses = @(Get-ExactExeProcesses $Exe)
        $unverifiedProcesses = @(Get-UnverifiedQaProcesses $Exe $DataDir)
        if ($remainingProcesses.Count -gt 0 -or $unverifiedProcesses.Count -gt 0) {
            $report.passed = $false
            $report.cleanupErrors.Add("Exact QA processes remaining: $($remainingProcesses.Count); unverified same-name QA candidates: $($unverifiedProcesses.Count). Registry restoration was skipped.")
            $report.processDiagnostics = @(Get-SameNameProcessDiagnostics $Exe)
        } elseif ($registryBackup -and $taskGone) {
            try { Restore-NativeHostRegistry $registryBackup }
            catch {
                $report.passed = $false
                $report.cleanupErrors.Add($_.Exception.Message)
            }
        }
        if ($taskGone -and (Test-Path -LiteralPath $DataDir -PathType Container)) {
            Remove-Item -LiteralPath (Join-Path $DataDir 'recovery-owner.json') -Force -ErrorAction SilentlyContinue
        }
    }
    if (-not $report.passed -and -not $report.failedTaskInfo) {
        $failedTask = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
        if ($failedTask) {
            $taskInfo = Get-ScheduledTaskInfo -TaskName $TaskName -ErrorAction SilentlyContinue
            $report.failedTaskInfo = [ordered]@{
                currentState = [string]$failedTask.State
                lastTaskResult = if ($taskInfo) { $taskInfo.LastTaskResult } else { $null }
                capturedAt = [DateTimeOffset]::Now.ToString('o')
            }
        }
    }
    New-Item -ItemType Directory -Path $reportDir -Force | Out-Null
    $reportPath = Join-Path $reportDir 'report.json'
    $report | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $reportPath -Encoding UTF8
}

Write-Host "Report: $reportPath"
if (-not $report.passed) {
    $failureText = @($report.initialError) + @($report.cleanupErrors) | Where-Object { $_ }
    if ($failureText.Count) { Write-Error ($failureText -join ' | ') }
    exit 1
}
Write-Host 'Task Manager recovery acceptance passed.'
