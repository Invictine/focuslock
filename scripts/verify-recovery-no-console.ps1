param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$TaskName,

    [ValidateRange(1, 3600)]
    [int]$ObserveSeconds = 5,

    [switch]$Natural
)

$ErrorActionPreference = 'Stop'

$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction Stop
$initialTaskInfo = Get-ScheduledTaskInfo -TaskName $TaskName -ErrorAction Stop

$observerType = 'FocusLock.RecoveryNoConsoleObserver'
if (-not ($observerType -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace FocusLock {
    public sealed class ConsoleWindowEvent {
        public string TimestampUtc { get; set; }
        public string Hwnd { get; set; }
        public string ClassName { get; set; }
        public string Title { get; set; }
        public uint ProcessId { get; set; }
        public string ProcessName { get; set; }
        public string ProcessPath { get; set; }
    }

    public sealed class RecoveryNoConsoleObserver {
        private delegate bool EnumWindowsProc(IntPtr hwnd, IntPtr lParam);

        [DllImport("user32.dll")]
        private static extern bool EnumWindows(EnumWindowsProc callback, IntPtr lParam);
        [DllImport("user32.dll")]
        private static extern bool IsWindowVisible(IntPtr hwnd);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern int GetClassName(IntPtr hwnd, StringBuilder className, int maxCount);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern int GetWindowText(IntPtr hwnd, StringBuilder title, int maxCount);
        [DllImport("user32.dll")]
        private static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint processId);

        private static readonly HashSet<string> TerminalClasses = new HashSet<string>(StringComparer.Ordinal) {
            "ConsoleWindowClass",
            "CASCADIA_HOSTING_WINDOW_CLASS",
            "VirtualConsoleClass"
        };

        private readonly HashSet<IntPtr> baseline;
        private readonly List<ConsoleWindowEvent> events = new List<ConsoleWindowEvent>();
        private readonly object eventsLock = new object();
        private readonly Task worker;
        private readonly ManualResetEventSlim started = new ManualResetEventSlim(false);

        private RecoveryNoConsoleObserver(int observeMilliseconds) {
            baseline = CaptureVisibleTerminalWindows();
            worker = Task.Run(() => Observe(observeMilliseconds));
            started.Wait();
        }

        public static RecoveryNoConsoleObserver Start(int observeMilliseconds) {
            return new RecoveryNoConsoleObserver(observeMilliseconds);
        }

        public void Wait() { worker.GetAwaiter().GetResult(); }

        public ConsoleWindowEvent[] GetEvents() {
            lock (eventsLock) return events.ToArray();
        }

        public int BaselineWindowCount { get { return baseline.Count; } }

        private void Observe(int observeMilliseconds) {
            started.Set();
            var timer = Stopwatch.StartNew();
            while (timer.ElapsedMilliseconds < observeMilliseconds) {
                EnumWindows((hwnd, unused) => {
                    if (!IsWindowVisible(hwnd) || baseline.Contains(hwnd)) return true;
                    var className = new StringBuilder(256);
                    if (GetClassName(hwnd, className, className.Capacity) <= 0 ||
                        !TerminalClasses.Contains(className.ToString())) return true;

                    uint processId;
                    GetWindowThreadProcessId(hwnd, out processId);
                    var title = new StringBuilder(1024);
                    GetWindowText(hwnd, title, title.Capacity);
                    string processName = null;
                    string processPath = null;
                    try {
                        using (var process = Process.GetProcessById((int)processId)) {
                            processName = process.ProcessName;
                            try { processPath = process.MainModule.FileName; } catch { }
                        }
                    } catch { }

                    var item = new ConsoleWindowEvent {
                        TimestampUtc = DateTimeOffset.UtcNow.ToString("o"),
                        Hwnd = "0x" + hwnd.ToInt64().ToString("X"),
                        ClassName = className.ToString(),
                        Title = title.ToString(),
                        ProcessId = processId,
                        ProcessName = processName,
                        ProcessPath = processPath
                    };
                    lock (eventsLock) {
                        if (!events.Exists(existing => existing.Hwnd == item.Hwnd)) events.Add(item);
                    }
                    return true;
                }, IntPtr.Zero);
                Thread.Sleep(2);
            }
        }

        private static HashSet<IntPtr> CaptureVisibleTerminalWindows() {
            var handles = new HashSet<IntPtr>();
            EnumWindows((hwnd, unused) => {
                if (!IsWindowVisible(hwnd)) return true;
                var className = new StringBuilder(256);
                if (GetClassName(hwnd, className, className.Capacity) > 0 &&
                    TerminalClasses.Contains(className.ToString())) handles.Add(hwnd);
                return true;
            }, IntPtr.Zero);
            return handles;
        }
    }
}
'@
}

$observeMilliseconds = $ObserveSeconds * 1000
$observer = [FocusLock.RecoveryNoConsoleObserver]::Start($observeMilliseconds)
$triggeredAt = [DateTimeOffset]::UtcNow
$startError = $null

if (-not $Natural) {
    try {
        Start-ScheduledTask -TaskName $TaskName -ErrorAction Stop
    } catch {
        $startError = $_.Exception.Message
    }
}

$observer.Wait()
$events = @($observer.GetEvents())
$finalTaskInfo = Get-ScheduledTaskInfo -TaskName $TaskName -ErrorAction SilentlyContinue
$finalTask = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
$taskRanDuringObservation = $false
if ($finalTaskInfo -and $finalTaskInfo.LastRunTime -gt $initialTaskInfo.LastRunTime) {
    $taskRanDuringObservation = $true
}
# IgnoreNew suppresses minute triggers while the persistent supervisor runs.
# In that case LastRunTime stays unchanged and we observe the existing task.
$taskRemainedRunning = $task.State -eq 'Running' -and $finalTask -and $finalTask.State -eq 'Running'

$reportDirectory = Join-Path $PSScriptRoot '..\build\recovery-no-console'
$reportDirectory = [IO.Path]::GetFullPath($reportDirectory)
New-Item -ItemType Directory -Path $reportDirectory -Force | Out-Null
$reportPath = Join-Path $reportDirectory ("recovery-no-console-{0:yyyyMMdd-HHmmss-fff}.json" -f [DateTime]::UtcNow)

$passed = ($events.Count -eq 0) -and (-not $startError) -and ($taskRanDuringObservation -or $taskRemainedRunning)
$report = [ordered]@{
    taskName = $TaskName
    mode = if ($Natural) { 'natural-trigger' } else { 'explicit-start' }
    observeSeconds = $ObserveSeconds
    triggeredAtUtc = $triggeredAt.ToString('o')
    completedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
    baselineVisibleTerminalWindowCount = $observer.BaselineWindowCount
    taskRanDuringObservation = $taskRanDuringObservation
    taskRemainedRunning = $taskRemainedRunning
    taskLastRunTime = if ($finalTaskInfo) { $finalTaskInfo.LastRunTime } else { $null }
    startError = $startError
    passed = $passed
    newVisibleConsoleWindows = $events
}
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $reportPath -Encoding UTF8

Write-Output ("Report: {0}" -f $reportPath)
if ($events.Count -gt 0) {
    Write-Error ("Detected {0} newly visible terminal-class window(s) during recovery observation." -f $events.Count)
    exit 1
}
if ($startError) {
    Write-Error ("Could not start the scheduled task: {0}" -f $startError)
    exit 1
}
if (-not ($taskRanDuringObservation -or $taskRemainedRunning)) {
    Write-Error 'The scheduled task did not record a run during the observation interval; the result is inconclusive.'
    exit 1
}

Write-Output 'No newly visible terminal-class windows appeared during the scheduled task run.'
