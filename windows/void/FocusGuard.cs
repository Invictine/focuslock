using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;

namespace VoidApp;

/// <summary>Answers whether a foreground window belongs to the current work session.</summary>
internal sealed class FocusGuard
{
    private readonly HashSet<string> allowedPaths = new(StringComparer.OrdinalIgnoreCase);
    private static readonly HashSet<string> SafeWindowsProcesses = new(StringComparer.OrdinalIgnoreCase)
    {
        "consent.exe", "credentialuibroker.exe", "applicationframehost.exe",
        "textinputhost.exe", "runtimebroker.exe", "taskmgr.exe", "systemsettings.exe",
        // Windows may surface Search and Start while switching or opening system UI.
        "searchhost.exe", "startmenuexperiencehost.exe", "shellexperiencehost.exe",
    };

    internal void Allow(string fullExePath, Process? launched)
    {
        string path = CanonicalPath(fullExePath);
        if (path.Length != 0)
            allowedPaths.Add(path);

        // Shell activation can start a different executable than the configured launcher
        // (for example, a packaged app behind a System32 forwarding stub). Trust the actual
        // process path when the OS exposes it; some packaged activations still hand off to a
        // separate host process, which cannot be inferred from the launcher Process handle.
        try
        {
            string actualPath = CanonicalPath(launched?.MainModule?.FileName);
            if (actualPath.Length != 0)
                allowedPaths.Add(actualPath);
        }
        catch (Exception ex) when (ex is InvalidOperationException or System.ComponentModel.Win32Exception or NotSupportedException)
        {
            // Path-based allowance remains available when the launcher exits too quickly.
        }

        // The executable path is the handoff identity: this also permits an already-running
        // copy (notably Chrome) when the newly launched process exits after forwarding its URL.
        // No PID-only exception is kept, so PID reuse cannot grant access to an unrelated process.
    }

    internal void Clear() => allowedPaths.Clear();

    internal bool IsAllowed(nint hWnd)
    {
        var seen = new HashSet<nint>();
        nint current = hWnd;
        for (int depth = 0; current != 0 && depth < 12 && seen.Add(current); depth++)
        {
            _ = NativeMethods.GetWindowThreadProcessId(current, out uint pid);
            if (pid == (uint)Environment.ProcessId)
                return true;

            string className = NativeMethods.WindowClass(current);
            string? path = ProcessPath(pid);
            if (path is not null)
            {
                if (allowedPaths.Contains(path))
                    return true;
                if (string.Equals(Path.GetFileName(path), "explorer.exe", StringComparison.OrdinalIgnoreCase))
                {
                    if (className == "#32770")
                        return true;
                    // Explorer's desktop and taskbar remain blocked. An owned dialog from
                    // an allowed work app is admitted by following the owner chain below.
                }
                else if (IsTrustedWindowsProcess(path))
                    return true;
            }

            current = NativeMethods.GetWindow(current, NativeMethods.GetWindowCommand.Owner);
        }
        return false;
    }

    internal IReadOnlyList<nint> WorkWindows()
    {
        var workWindows = new List<nint>();
        foreach (nint hWnd in NativeMethods.TopLevelWindows(visibleOnly: true))
        {
            if (NativeMethods.GetWindowThreadProcessId(hWnd, out uint pid) == 0 ||
                pid == (uint)Environment.ProcessId ||
                NativeMethods.GetWindow(hWnd, NativeMethods.GetWindowCommand.Owner) != 0)
                continue;
            string? path = ProcessPath(pid);
            if (path is not null && allowedPaths.Contains(path))
                workWindows.Add(hWnd);
        }
        return workWindows;
    }

    internal bool HasWorkWindow() => WorkWindows().Count != 0;

    private static string? ProcessPath(uint pid)
    {
        if (pid == 0)
            return null;
        try
        {
            using Process process = Process.GetProcessById((int)pid);
            return CanonicalPath(process.MainModule?.FileName);
        }
        catch (Exception ex) when (ex is ArgumentException or InvalidOperationException or
                                   System.ComponentModel.Win32Exception or NotSupportedException)
        {
            return null;
        }
    }

    private static bool IsTrustedWindowsProcess(string path)
    {
        string name = Path.GetFileName(path);
        if (!SafeWindowsProcesses.Contains(name))
            return false;

        string windows = CanonicalPath(Environment.GetFolderPath(Environment.SpecialFolder.Windows));
        if (windows.Length == 0)
            return false;
        string system32 = Path.Combine(windows, "System32") + Path.DirectorySeparatorChar;
        string sysWow64 = Path.Combine(windows, "SysWOW64") + Path.DirectorySeparatorChar;
        string systemApps = Path.Combine(windows, "SystemApps") + Path.DirectorySeparatorChar;
        string fullPath = CanonicalPath(path);
        return fullPath.StartsWith(system32, StringComparison.OrdinalIgnoreCase) ||
               fullPath.StartsWith(sysWow64, StringComparison.OrdinalIgnoreCase) ||
               fullPath.StartsWith(systemApps, StringComparison.OrdinalIgnoreCase);
    }

    private static string CanonicalPath(string? path)
    {
        if (string.IsNullOrWhiteSpace(path))
            return string.Empty;
        try { return Path.GetFullPath(path); }
        catch (Exception ex) when (ex is ArgumentException or IOException or NotSupportedException)
        { return string.Empty; }
    }
}
