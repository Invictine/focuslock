using System.Windows;
using System.Diagnostics;
namespace VoidApp;
public partial class App : Application
{

    protected override void OnStartup(StartupEventArgs e)
    {
        if (e.Args.Length == 1 && e.Args[0] == "--focuslock")
        {
            StartFocusLockMode();
            return;
        }
        if (e.Args.Length is 4 or 5 && e.Args[0] == "--recover")
        {
            // A separate instance waits for the UI process, including a forced termination.
            try
            {
                using var parent = Process.GetProcessById(int.Parse(e.Args[1]));
                using var ready = EventWaitHandle.OpenExisting(e.Args[3]);
                ready.Set();
                if (parent.StartTime.ToUniversalTime().Ticks == long.Parse(e.Args[2])) parent.WaitForExit();
            }
            catch (Exception ex) when (ex is ArgumentException or InvalidOperationException or System.ComponentModel.Win32Exception or WaitHandleCannotBeOpenedException) { }
            finally
            {
                if (e.Args.Length == 5) DisplaySession.Restore(e.Args[4]);
                TaskbarSession.Restore();
            }
            Shutdown();
            return;
        }
        Shutdown();
    }

    private Mutex? integratedInstance;
    private FocusLockProtocol? integratedProtocol;
    private FrogWindow? integratedWindow;

    private void StartFocusLockMode()
    {
        integratedInstance = new Mutex(true, "Local\\FocusLock.Frog", out bool first);
        if (!first) { Shutdown(); return; }
        Config settings;
        try { settings = Config.LoadExistingReadOnly(); }
        catch { settings = new Config(); }
        integratedProtocol = new FocusLockProtocol();
        bool readySent = false;
        integratedProtocol.StateReceived += state => Dispatcher.BeginInvoke(new Action(() =>
        {
            if (integratedWindow is null)
            {
                integratedWindow = new FrogWindow(integratedProtocol, settings);
                MainWindow = integratedWindow;
                integratedWindow.ApplyState(state);
                integratedWindow.Show();
            }
            integratedWindow.ApplyState(state);
            if (!readySent)
            {
                readySent = true;
                integratedProtocol.WriteAction(new FocusLockAction { Action = "ready" });
            }
        }));
        integratedProtocol.Ended += reason => Dispatcher.BeginInvoke(new Action(() =>
        {
            if (integratedWindow is not null)
            {
                integratedWindow.ShowConnectionEnded(reason);
                integratedWindow.CloseFromHost();
            }
            else Shutdown();
        }));
        integratedProtocol.Start();
    }
    protected override void OnExit(ExitEventArgs e)
    {
        if (TaskbarSession.Active) TaskbarSession.Restore();
        integratedProtocol?.RequestStop();
        integratedInstance?.Dispose();
        base.OnExit(e);
    }
}
