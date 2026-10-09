using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Text;

namespace VoidApp;

/// <summary>Requests normal shutdown of unrelated apps and recognizes their pending dialogs.</summary>
internal static class OtherWindows
{
    private const uint WmClose = 0x0010;
    private const int GwlExStyle = -20;
    private const int WsExToolWindow = 0x00000080;

    private static readonly HashSet<string> ProtectedProcesses = new(StringComparer.OrdinalIgnoreCase)
    {
        "explorer", "dwm", "sihost", "taskmgr", "taskhostw", "ctfmon",
        "consent", "credentialuibroker", "applicationframehost", "textinputhost",
        "runtimebroker", "searchhost", "startmenuexperiencehost", "shellexperiencehost",
        "systemsettings",
        "system", "registry", "smss", "csrss", "wininit", "services", "lsass", "winlogon",
        "fontdrvhost",
    };

    private static readonly HashSet<string> ShellClasses = new(StringComparer.OrdinalIgnoreCase)
    {
        "Progman", "WorkerW", "Shell_TrayWnd", "Shell_SecondaryTrayWnd",
    };

    private static readonly HashSet<ProcessIdentity> ClosingProcesses = [];
    private static readonly HashSet<WindowRequest> RequestedWindows = [];

    /// <summary>Posts WM_CLOSE once to each eligible window; apps can save, prompt, or refuse.</summary>
    internal static int RequestClose(FocusGuard guard)
    {
        RequestedWindows.Clear(); ClosingProcesses.Clear();
        int requested = 0;
        foreach (nint hWnd in NativeMethods.TopLevelWindows(visibleOnly: true))
        {
            if (!NativeMethods.IsWindowVisible(hWnd) ||
                NativeMethods.GetWindow(hWnd, NativeMethods.GetWindowCommand.Owner) != 0 ||
                (NativeMethods.GetWindowLong(hWnd, GwlExStyle) & WsExToolWindow) != 0 ||
                ShellClasses.Contains(NativeMethods.WindowClass(hWnd)) ||
                !HasTitle(hWnd) ||
                NativeMethods.GetWindowThreadProcessId(hWnd, out uint pid) == 0 ||
                pid == (uint)Environment.ProcessId ||
                guard.IsAllowed(hWnd) ||
                !TryGetProcessIdentity(pid, out ProcessIdentity identity, out string processName) ||
                ProtectedProcesses.Contains(processName))
                continue;

            var request = new WindowRequest(hWnd, identity);
            if (RequestedWindows.Contains(request))
                continue;

            // PostMessage is asynchronous: the target handles WM_CLOSE on its own thread,
            // including any save confirmation. Void never terminates or waits for the app.
            if (NativeMethods.PostMessage(hWnd, WmClose, 0, 0))
            {
                RequestedWindows.Add(request);
                ClosingProcesses.Add(identity);
                requested++;
            }
        }
        return requested;
    }

    /// <summary>True while a requested app, or a dialog owned by it, is still running.</summary>
    internal static bool IsClosingApp(nint hWnd)
    {
        var seen = new HashSet<nint>();
        nint current = hWnd;
        for (int depth = 0; current != 0 && depth < 12 && seen.Add(current); depth++)
        {
            if (NativeMethods.GetWindowThreadProcessId(current, out uint pid) != 0 &&
                TryGetProcessIdentity(pid, out ProcessIdentity identity, out _) &&
                ClosingProcesses.Contains(identity))
                return true;
            current = NativeMethods.GetWindow(current, NativeMethods.GetWindowCommand.Owner);
        }
        return false;
    }

    private static bool HasTitle(nint hWnd)
    {
        int length = NativeMethods.GetWindowTextLength(hWnd);
        if (length <= 0)
            return false;
        var title = new StringBuilder(Math.Min(length + 1, 1024));
        return NativeMethods.GetWindowText(hWnd, title, title.Capacity) > 0;
    }

    private static bool TryGetProcessIdentity(uint pid, out ProcessIdentity identity, out string processName)
    {
        identity = default;
        processName = string.Empty;
        if (pid == 0)
            return false;
        try
        {
            using Process process = Process.GetProcessById((int)pid);
            identity = new ProcessIdentity(pid, process.StartTime.ToUniversalTime().Ticks);
            processName = process.ProcessName;
            return true;
        }
        catch (Exception ex) when (ex is ArgumentException or InvalidOperationException or
                                   System.ComponentModel.Win32Exception or NotSupportedException)
        {
            return false;
        }
    }

    private readonly record struct ProcessIdentity(uint Pid, long StartTimeUtcTicks);
    private readonly record struct WindowRequest(nint Hwnd, ProcessIdentity Process);
}
