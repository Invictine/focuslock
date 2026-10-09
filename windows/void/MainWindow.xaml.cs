using System.Diagnostics;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Threading;

namespace VoidApp;
public partial class MainWindow : Window
{
    private Config config = new();
    private readonly FocusGuard guard = new();
    private FocusLock focusLock = new();
    private readonly DispatcherTimer monitor = new() { Interval = TimeSpan.FromMilliseconds(500) };
    private readonly DispatcherTimer holdTimer = new() { Interval = TimeSpan.FromMilliseconds(50) };
    private readonly Stopwatch hold = new();
    private AppEntry? pendingApp, activeApp;
    private FrameworkElement? holdTarget;
    private nint handle;
    private bool exiting, hotkeysReady, ready, mouseHold, workInFront;
    private DateTime launchTime;
    public event Action? ResidentRequested;
    public event Action? FocusStarted;

    public IReadOnlyList<RecentPreset> GetRecentPresets() => RecentPresets.ForApps(config.Apps);
    public void OpenHome() => ShowFocus();
    public void ExitApplication() => Quit(true);
    public void QuickLaunch(RecentPreset preset)
    {
        if (focusLock.IsLocked || breakLock is not null) { ShowFocus(); return; }
        var current = GetRecentPresets().FirstOrDefault(p => p.Path == preset.Path && p.AppUserModelId == preset.AppUserModelId && p.Args == preset.Args && p.Minutes == preset.Minutes && p.CloseOtherApps == preset.CloseOtherApps);
        if (current is null) { ShowFocus(); Status.Text = "This preset has changed. Choose an app to start a new block."; return; }
        ShowFocus(); AskDuration(current.ToAppEntry());
        MinutesInput.Text = current.Minutes.ToString(); CloseAppsChoice.IsChecked = current.CloseOtherApps;
        StartBlock_Click(this, new RoutedEventArgs());
    }

    private void ReturnToLauncher()
    {
        if (!CanExit()) return;
        RecordSession(activeApp is not null && !focusLock.IsLocked);
        CancelHold(); monitor.Stop(); ready = false;
        activeApp = pendingApp = null; breakLock = null; focusLock = new FocusLock();
        guard.Clear(); workInFront = false; Topmost = false;
        if (TaskbarSession.Active) TaskbarSession.Restore();
        ShowPanel(Menu); RefreshSummary(); Hide();
        ResidentRequested?.Invoke();
    }
    private void Minimize_Click(object sender, RoutedEventArgs e) => ReturnToLauncher();

    private void BeginFocusEnvironment()
    {
        if (ready) return;
        if (!hotkeysReady) throw new InvalidOperationException("Void could not reserve both recovery shortcuts. Close the app using those shortcuts and try again.");
        TaskbarSession.Begin(); ready = true;
        if (config.PrimaryMonitorOnly)
        {
            try { DisplaySession.UsePrimaryOnly(); }
            catch (Exception ex) { Status.Text = "Primary monitor mode: " + ex.Message; }
        }
        Width = SystemParameters.PrimaryScreenWidth; Height = SystemParameters.PrimaryScreenHeight;
        monitor.Start();
    }

    public MainWindow()
    {
        InitializeComponent();
        Left = 0; Top = 0;
        Width = SystemParameters.PrimaryScreenWidth; Height = SystemParameters.PrimaryScreenHeight;
        Choices.MaxHeight = Height * 0.5;
        try { config = Config.Load(); Startup.Apply(config.StartWithWindows); }
        catch (Exception ex) { Status.Text = "config.json: " + ex.Message; }
        RefreshMenu();
        SourceInitialized += (_, _) =>
        {
            handle = new WindowInteropHelper(this).Handle;
            HwndSource.FromHwnd(handle).AddHook(WindowMessage);
            bool returnKey = NativeMethods.RegisterHotKey(handle, 1, 0x4003, 0x20);
            bool emergencyKey = NativeMethods.RegisterHotKey(handle, 2, 0x4007, 0x7B);
            hotkeysReady = returnKey && emergencyKey;
        };
        Loaded += (_, _) =>
        {
            Choices.Focus();
        };
        Closing += (_, e) => { if (!exiting) { e.Cancel = true; ReturnToLauncher(); } };
        Closed += (_, _) =>
        {
            monitor.Stop(); CancelHold();
            NativeMethods.UnregisterHotKey(handle, 1); NativeMethods.UnregisterHotKey(handle, 2);
            if (TaskbarSession.Active) TaskbarSession.Restore();
        };
        monitor.Tick += (_, _) => Monitor();
        holdTimer.Tick += (_, _) => HoldTick();
        PreviewKeyDown += OnKeyDown;
        PreviewKeyUp += (_, e) => { if (e.Key == Key.Enter) CancelHold(); };
        PreviewMouseLeftButtonUp += (_, _) => CancelHold();
        Deactivated += (_, _) => CancelHold();
        Choices.SelectionChanged += (_, _) => CancelHold();
        Choices.PreviewMouseLeftButtonDown += (_, e) =>
        {
            if (ItemsControl.ContainerFromElement(Choices, e.OriginalSource as DependencyObject) is not ListBoxItem item) return;
            Choices.SelectedIndex = Choices.ItemContainerGenerator.IndexFromContainer(item);
            if (Choices.SelectedItem is AppEntry app) AskDuration(app); else BeginHold(true, item);
            e.Handled = true;
        };
    }

    private void ShowPanel(FrameworkElement panel)
    {
        foreach (var p in new FrameworkElement[] { Menu, DurationPanel, SessionPanel, ExitPanel, BreakPanel })
            p.Visibility = p == panel ? Visibility.Visible : Visibility.Collapsed;
    }
    private void AskDuration(AppEntry app)
    {
        pendingApp = app; DurationContext.Text = app.Name;
        MinutesInput.Text = Math.Max(1, config.LockMinutes).ToString(); Status.Text = "";
        CloseAppsChoice.IsChecked = config.CloseOtherApps;
        ShowPanel(DurationPanel); MinutesInput.Focus(); MinutesInput.SelectAll();
    }
    private void Back_Click(object sender, RoutedEventArgs e) { pendingApp = null; ShowPanel(Menu); Status.Text = ""; Choices.Focus(); }
    private void StartBlock_Click(object sender, RoutedEventArgs e)
    {
        if (pendingApp is null) return;
        if (!int.TryParse(MinutesInput.Text, out int minutes) || minutes is < 1 or > 1440)
        { Status.Text = "Enter a whole number of minutes between 1 and 1440."; MinutesInput.Focus(); return; }
        StartButton.IsEnabled = false;
        try
        {
            RecordSession(activeApp is not null && !focusLock.IsLocked);
            BeginFocusEnvironment();
            guard.Clear();
            LaunchApp(pendingApp);
            activeApp = pendingApp; pendingApp = null;
            focusLock = new FocusLock(); focusLock.Start(minutes);
            currentSession = new FocusSession { AppName = activeApp.Name, StartedAt = DateTimeOffset.Now, PlannedMinutes = minutes };
            string recentError = "";
            try { RecentPresets.Record(activeApp, minutes, CloseAppsChoice.IsChecked == true); }
            catch (Exception ex) { recentError = "Could not save this quick-launch preset: " + ex.Message; }
            FocusStarted?.Invoke();
            completionAnnounced = false;
            SessionContext.Text = activeApp.Name;
            ResumeButton.Content = "Return to " + activeApp.Name;
            ShowPanel(SessionPanel); UpdateTimer();
            Status.Text = recentError;
            // Close requests happen only after successful activation, once per new block.
            try
            {
                int requested = CloseAppsChoice.IsChecked == true ? OtherWindows.RequestClose(guard) : 0;
                if (requested > 0) Status.Text = "Other apps may ask you to save work. Handle those prompts normally.";
            }
            catch { Status.Text = "Some apps could not be asked to close. Your focus block is still running."; }
            BringWorkForward();
        }
        catch (Exception ex)
        {
            monitor.Stop(); ready = false; if (TaskbarSession.Active) TaskbarSession.Restore(); ResidentRequested?.Invoke();
            workInFront = false; Topmost = false; Activate();
            ShowPanel(DurationPanel); Status.Text = "Could not start focus: " + ex.Message;
            MinutesInput.Focus();
        }
        finally { StartButton.IsEnabled = true; }
    }
    private void LaunchApp(AppEntry app)
    {
        string path = "";
        if (string.IsNullOrWhiteSpace(app.AppUserModelId))
        {
            path = Path.GetFullPath(Environment.ExpandEnvironmentVariables(app.Path), AppContext.BaseDirectory);
            if (!File.Exists(path)) throw new FileNotFoundException("App not found. Check its path in config.json.");
        }
        Topmost = false;
        using var process = string.IsNullOrWhiteSpace(app.AppUserModelId)
            ? Process.Start(new ProcessStartInfo(path, app.Args ?? "") { UseShellExecute = true, WorkingDirectory = Path.GetDirectoryName(path)! })
            : PackagedAppLauncher.Activate(app.AppUserModelId, app.Args);
        guard.Allow(path, process);
        launchTime = DateTime.UtcNow; workInFront = true;
    }
    private void Resume_Click(object sender, RoutedEventArgs e)
    {
        if (activeApp is null) return;
        try
        {
            if (!guard.HasWorkWindow()) LaunchApp(activeApp);
            workInFront = true; Topmost = false; BringWorkForward();
        }
        catch (Exception ex) { ShowFocus(); Status.Text = "Could not open app: " + ex.Message; }
    }
    private void BringWorkForward()
    {
        var windows = guard.WorkWindows();
        if (windows.Count == 0) return;
        nint target = windows[0];
        Topmost = false;
        if (NativeMethods.IsIconic(target)) NativeMethods.ShowWindow(target, 9);
        NativeMethods.SetForegroundWindow(target);
    }
    private void NewBlock_Click(object sender, RoutedEventArgs e)
    {
        if (focusLock.IsLocked) return;
        RecordSession(true);
        activeApp = null; focusLock = new FocusLock(); guard.Clear(); workInFront = false;
        ShowPanel(Menu); RefreshSummary(); Status.Text = ""; Choices.Focus();
    }
    private void UpdateTimer()
    {
        if (activeApp is null) return;
        long seconds = (long)Math.Ceiling(focusLock.Remaining.TotalSeconds);
        TimerText.Text = $"{seconds / 60:00}:{seconds % 60:00}";
        SessionProgress.Value = currentSession is { } session ? 100 * (1 - focusLock.Remaining.TotalSeconds / (session.PlannedMinutes * 60d)) : 100;
        TimerHint.Text = focusLock.IsLocked ? "Exit unlocks when the timer ends" : "Block complete";
        NewBlockButton.Visibility = focusLock.IsLocked ? Visibility.Collapsed : Visibility.Visible;
        DesktopButton.Visibility = focusLock.IsLocked ? Visibility.Collapsed : Visibility.Visible;
        BreakButton.Visibility = focusLock.IsLocked ? Visibility.Collapsed : Visibility.Visible;
        BreakButton.Content = $"Take a {config.BreakMinutes}-minute break";
        if (!focusLock.IsLocked && Status.Text == "Exit Focus unlocks when the timer ends.") Status.Text = "";
        if (!focusLock.IsLocked) AnnounceCompletion();
    }
    private void Monitor()
    {
        TaskbarSession.Hide(); UpdateTimer(); UpdateBreakTimer();
        if (breakLock is not null) return;
        var foreground = NativeMethods.GetForegroundWindow();
        // Save prompts from apps asked to close must remain usable, including behind Void.
        if (OtherWindows.IsClosingApp(foreground)) { Topmost = false; return; }
        if (workInFront)
        {
            if (foreground == handle)
            {
                if (DateTime.UtcNow - launchTime < TimeSpan.FromSeconds(2)) BringWorkForward();
                else ShowFocus();
                return;
            }
            if (!guard.HasWorkWindow() && DateTime.UtcNow - launchTime > TimeSpan.FromSeconds(15))
            { ShowFocus(); return; }
        }
        if (config.Guard && foreground != 0 && DateTime.UtcNow - launchTime > TimeSpan.FromSeconds(2) && !guard.IsAllowed(foreground)) ShowFocus();
    }
    private void ShowFocus()
    {
        workInFront = false; CancelHold();
        ShowPanel(breakLock is not null ? BreakPanel : activeApp is null ? Menu : SessionPanel); UpdateTimer();
        Show(); Topmost = ready; Activate(); NativeMethods.SetForegroundWindow(handle);
        if (activeApp is null && breakLock is null) Choices.Focus(); else if (activeApp is not null) ResumeButton.Focus();
    }
    private void OnKeyDown(object sender, KeyEventArgs e)
    {
        if (ExitPanel.Visibility == Visibility.Visible)
        {
            if (e.Key == Key.Escape) { ShowFocus(); e.Handled = true; }
            else if (e.Key == Key.Enter) { if (!e.IsRepeat && ExitText.Text == "EXIT") ReturnToLauncher(); e.Handled = true; }
            return;
        }
        if (DurationPanel.Visibility == Visibility.Visible)
        {
            if (e.Key == Key.Escape) { Back_Click(this, e); e.Handled = true; }
            else if (e.Key == Key.Enter && MinutesInput.IsKeyboardFocused)
            { if (!e.IsRepeat) StartBlock_Click(this, e); e.Handled = true; }
            return;
        }
        if (Menu.Visibility == Visibility.Visible && (Choices.IsKeyboardFocusWithin || e.OriginalSource == this))
        {
            if (e.Key is Key.Up or Key.Down)
            {
                Choices.SelectedIndex = (Choices.SelectedIndex + (e.Key == Key.Down ? 1 : Choices.Items.Count - 1)) % Choices.Items.Count;
                Choices.ScrollIntoView(Choices.SelectedItem); e.Handled = true;
            }
            else if (e.Key == Key.Enter)
            {
                if (!e.IsRepeat) { if (Choices.SelectedItem is AppEntry app) AskDuration(app); else BeginHold(false, Choices); }
                e.Handled = true;
            }
        }
        else if (SessionPanel.Visibility == Visibility.Visible && e.Key == Key.Enter && SessionExit.IsKeyboardFocused)
        { if (!e.IsRepeat) BeginHold(false, SessionExit); e.Handled = true; }
        if (e.Key == Key.Escape) { if (activeApp is null && breakLock is null) ReturnToLauncher(); e.Handled = true; }
    }
    private bool CanExit()
    {
        if (!focusLock.IsLocked) return true;
        Status.Text = "Exit Focus unlocks when the timer ends."; return false;
    }
    private void Exit_MouseDown(object sender, MouseButtonEventArgs e) { BeginHold(true, SessionExit); e.Handled = true; }
    private void BeginHold(bool mouse, FrameworkElement target)
    {
        if (!CanExit()) return;
        mouseHold = mouse; holdTarget = target; hold.Restart(); holdTimer.Start();
        if (mouse) target.CaptureMouse();
    }
    private void HoldTick()
    {
        if (!IsActive || (mouseHold ? Mouse.LeftButton != MouseButtonState.Pressed || holdTarget?.IsMouseOver != true : !Keyboard.IsKeyDown(Key.Enter)))
        { CancelHold(); return; }
        Status.Text = $"Hold to exit · {Math.Max(0, 3 - hold.Elapsed.TotalSeconds):0.0}s";
        if (hold.Elapsed.TotalSeconds < 3) return;
        CancelHold(); ShowPanel(ExitPanel); ExitText.Clear(); ExitText.Focus();
    }
    private void CancelHold()
    {
        if (!hold.IsRunning) return;
        holdTimer.Stop(); hold.Reset(); holdTarget?.ReleaseMouseCapture(); holdTarget = null; Status.Text = "";
    }
    private nint WindowMessage(nint hwnd, int msg, nint wParam, nint lParam, ref bool handled)
    {
        if (msg == 0x007E) // WM_DISPLAYCHANGE: keep the focus surface on the primary screen.
        {
            Dispatcher.BeginInvoke(() => { Left = 0; Top = 0; Width = SystemParameters.PrimaryScreenWidth; Height = SystemParameters.PrimaryScreenHeight; Choices.MaxHeight = Height * 0.5; });
        }
        if (msg == 0x8001) { handled = true; ShowFocus(); }
        if (msg == 0x8002) { handled = true; if (activeApp is not null || breakLock is not null) ShowFocus(); else ReturnToLauncher(); }
        if (msg == 0x0312) { handled = true; if (wParam == 2) Quit(true); else ShowFocus(); }
        return 0;
    }
    private void Quit(bool emergency = false)
    {
        if (!emergency && !CanExit()) return;
        RecordSession(activeApp is not null && !focusLock.IsLocked);
        exiting = true; if (TaskbarSession.Active) TaskbarSession.Restore(); Close();
    }
}
