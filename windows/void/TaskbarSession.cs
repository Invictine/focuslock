using System.Diagnostics;
namespace VoidApp;
internal static class TaskbarSession
{
    internal static bool Active { get; private set; }
    private static Process? recovery;
    internal static void Begin()
    {
        if (Active) return;
        if (recovery is { HasExited: false }) { Active = true; Hide(); return; }
        recovery?.Dispose();
        using var self = Process.GetCurrentProcess();
        string readyName = $"Local\\Void.Recovery.{self.Id}.{self.StartTime.ToUniversalTime().Ticks}";
        string displayRecovery = System.IO.Path.Combine(Config.UserDirectory, $"display-{self.Id}-{Guid.NewGuid():N}.bin");
        DisplaySession.InitializeRecovery(displayRecovery);
        using var ready = new EventWaitHandle(false, EventResetMode.ManualReset, readyName);
        var start = new ProcessStartInfo(Environment.ProcessPath!) { UseShellExecute = false, CreateNoWindow = true };
        // Also supports launching the DLL through dotnet during development.
        if (string.Equals(System.IO.Path.GetFileNameWithoutExtension(Environment.ProcessPath), "dotnet", StringComparison.OrdinalIgnoreCase))
            start.ArgumentList.Add(System.IO.Path.Combine(AppContext.BaseDirectory, "Void.dll"));
        start.ArgumentList.Add("--recover");
        start.ArgumentList.Add(self.Id.ToString());
        start.ArgumentList.Add(self.StartTime.ToUniversalTime().Ticks.ToString());
        start.ArgumentList.Add(readyName);
        start.ArgumentList.Add(displayRecovery);
        recovery = Process.Start(start) ?? throw new InvalidOperationException("Could not start taskbar recovery.");
        if (!ready.WaitOne(TimeSpan.FromSeconds(5)))
            throw new InvalidOperationException("Recovery helper did not start; taskbar remains visible.");
        Active = true;
        Hide();
    }
    internal static void Hide()
    {
        if (Active) foreach (var bar in NativeMethods.Taskbars()) NativeMethods.ShowWindow(bar, 0);
    }
    internal static void Restore()
    {
        Active = false;
        DisplaySession.Restore();
        try { if (recovery is { HasExited: false }) recovery.Kill(); } catch { }
        recovery?.Dispose();
        recovery = null;
        foreach (var bar in NativeMethods.Taskbars()) NativeMethods.ShowWindow(bar, 5);
    }
}
