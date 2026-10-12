using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using Microsoft.Win32;
using System.Windows.Threading;

namespace VoidApp;

public partial class FrogWindow : Window
{
    private const int ToggleHotkey = 1;
    private const int WmHotkey = 0x0312;
    private const int WmReturnToFrog = 0x8003;
    private const uint ModAlt = 0x0001, ModControl = 0x0002, ModShift = 0x0004;
    private readonly FocusLockProtocol protocol;
    private readonly Config config;
    private HwndSource? source;
    private FocusLockSessionState? state;
    private bool allowClose;
    private bool recoveryHotkeyAvailable;
    private bool applyingState;
    private readonly DispatcherTimer heartbeat = new() { Interval = TimeSpan.FromSeconds(1) };
    private long lastStateTimestamp;
    private string? toolLayoutKey;
    private string? domainLayoutKey;
    private readonly Dictionary<string, FocusLockTool> essentialTools = new(StringComparer.OrdinalIgnoreCase);
    private bool pickerLoaded;
    private bool environmentStarted;

    public FrogWindow(FocusLockProtocol protocol, Config config)
    {
        this.protocol = protocol;
        this.config = config;
        InitializeComponent();
        heartbeat.Tick += (_, _) =>
        {
            if (state is not null && Stopwatch.GetElapsedTime(lastStateTimestamp) > TimeSpan.FromSeconds(10))
            {
                StatusText.Text = "FocusLock connection timed out. Closing safely.";
                allowClose = true;
                Close();
            }
        };
        for (int i = 1; i <= 480; i++) DurationBox.Items.Add(i);
    }

    public void ApplyState(FocusLockSessionState next)
    {
        applyingState = true;
        var previousPhase = state?.Phase;
        var previousCycle = state?.CycleDate;
        state = next;
        if (previousCycle is not null && previousCycle != next.CycleDate)
        {
            TaskInput.Clear();
            essentialTools.Clear(); EssentialChoices.Children.Clear(); pickerLoaded = false;
            toolLayoutKey = domainLayoutKey = null;
            UseTaskButton.IsEnabled = true;
            TaskStep.Visibility = Visibility.Visible; ToolsStep.Visibility = Visibility.Collapsed;
            StatusText.Text = "";
        }
        lastStateTimestamp = Stopwatch.GetTimestamp();
        TitleText.Text = next.Phase == "pick_frog" ? "Pick one task" : next.Title;
        ProjectText.Text = next.ProjectName ?? "";
        PhaseText.Text = next.Phase switch
        {
            "not_armed" => "NOT ARMED",
            "pick_frog" => "The one thing you want to finish today.",
            "grace" => "Time to get ready",
            "working" => "",
            "complete" => "Done for today",
            _ => next.Phase.ToUpperInvariant(),
        };
        PickerPanel.Visibility = next.Phase == "pick_frog" ? Visibility.Visible : Visibility.Collapsed;
        FocusPanel.Visibility = next.Phase == "pick_frog" ? Visibility.Collapsed : Visibility.Visible;
        TimerControls.Visibility = next.Phase == "working" ? Visibility.Visible : Visibility.Collapsed;
        TickBox.Visibility = next.Phase == "working" ? Visibility.Visible : Visibility.Collapsed;
        TimerText.Text = FormatTime(next.Phase == "grace" ? next.GraceRemainingSeconds : next.RemainingSeconds);
        GraceTimerText.Text = FormatTime(next.GraceRemainingSeconds);
        if (previousPhase != next.Phase)
        {
            ConfigurePresentation();
            if (IsLoaded && next.Phase is ("pick_frog" or "working")) { Show(); Topmost = true; Activate(); }
            if (IsLoaded && next.Phase == "pick_frog") TaskInput.Focus();
        }
        ProgressText.Text = next.Phase == "grace" ? "Eat the frog starts after this countdown" : $"{FormatMinutes(next.TrackedSeconds)} / {FormatMinutes(next.RequiredSeconds)} minutes";
        if (next.Phase == "pick_frog") LoadEssentialTools(next);
        if (next.Phase == "working") { UseTaskButton.IsEnabled = true; if (StatusText.Text == "Saving your task…") StatusText.Text = ""; }
        // A manually opened countdown does not change the desktop environment.
        if (IsLoaded && next.Phase is ("pick_frog" or "working")) BeginEnvironment();
        TickBox.IsChecked = next.TickedOff;
        TickBox.IsEnabled = !next.TickedOff;
        TimerButton.Content = next.Running ? "Pause focus" : "Start focus";
        DurationBox.SelectedItem = Math.Clamp(next.BlockMinutes, 1, 480);
        DurationBox.IsEnabled = !next.Running;
        var nextToolKey = string.Join("\n", next.Tools.Select(tool => $"{tool.Id}\0{tool.Label}\0{tool.ExecutablePath}\0{tool.AppUserModelId}"));
        var nextDomainKey = string.Join("\n", next.Domains);
        if (!string.Equals(toolLayoutKey, nextToolKey, StringComparison.Ordinal))
        {
            toolLayoutKey = nextToolKey;
            RebuildToolButtons(next);
        }
        if (!string.Equals(domainLayoutKey, nextDomainKey, StringComparison.Ordinal))
        {
            domainLayoutKey = nextDomainKey;
            RebuildDomainButtons(next);
        }
        applyingState = false;
    }

    private void RebuildToolButtons(FocusLockSessionState next)
    {
        ToolsPanel.Children.Clear();
        foreach (var tool in next.Tools)
        {
            var launchTool = essentialTools.GetValueOrDefault(tool.Id) ?? tool;
            var button = new Button { Content = launchTool.Label, Tag = launchTool };
            button.Click += Tool_Click;
            ToolsPanel.Children.Add(button);
        }
    }

    private void BeginEnvironment()
    {
        if (environmentStarted) return;
        if (!recoveryHotkeyAvailable) return;
        try
        {
            TaskbarSession.Begin();
            DisplaySession.UsePrimaryOnly();
            environmentStarted = true;
            Left = 0; Top = 0;
            Width = SystemParameters.PrimaryScreenWidth; Height = SystemParameters.PrimaryScreenHeight;
        }
        catch (Exception ex)
        {
            TaskbarSession.Restore();
            StatusText.Text = "Could not enter single-monitor mode: " + ex.Message;
        }
    }

    private void ConfigurePresentation()
    {
        bool grace = state?.Phase == "grace";
        if (grace && environmentStarted)
        {
            TaskbarSession.Restore();
            environmentStarted = false;
        }
        ShowInTaskbar = !grace; // The focus surface remains reachable through Alt+Tab.
        GracePanel.Visibility = grace ? Visibility.Visible : Visibility.Collapsed;
        ContentScroll.Visibility = grace ? Visibility.Collapsed : Visibility.Visible;
        FooterPanel.Visibility = grace ? Visibility.Collapsed : Visibility.Visible;
        RecoveryText.Visibility = grace ? Visibility.Collapsed : Visibility.Visible;
        Width = grace ? 240 : SystemParameters.PrimaryScreenWidth;
        Height = grace ? 120 : SystemParameters.PrimaryScreenHeight;
        Left = grace ? Math.Max(0, SystemParameters.WorkArea.Right - Width - 24) : 0;
        Top = grace ? SystemParameters.WorkArea.Top + 24 : 0;
    }

    private void Window_MouseDown(object sender, System.Windows.Input.MouseButtonEventArgs e)
    {
        if (state?.Phase == "grace" && e.ChangedButton == System.Windows.Input.MouseButton.Left) DragMove();
    }

    private void LoadEssentialTools(FocusLockSessionState snapshot)
    {
        var snapshotKey = string.Join("\n", snapshot.Tools.Select(tool => $"{tool.Id}\0{tool.Label}\0{tool.ExecutablePath}\0{tool.AppUserModelId}")) + "\n--\n" + string.Join("\n", snapshot.Domains);
        if (pickerLoaded && string.Equals(toolLayoutKey, snapshotKey, StringComparison.Ordinal)) return;
        pickerLoaded = true;
        toolLayoutKey = snapshotKey;
        essentialTools.Clear();
        EssentialChoices.Children.Clear();
        foreach (var tool in snapshot.Tools) AddEssential(tool, true);
        RebuildEssentialDomains(snapshot);
    }
    private void AddEssential(FocusLockTool tool, bool selected = false)
    {
        if (essentialTools.ContainsKey(tool.Id))
        {
            return;
        }
        essentialTools.Add(tool.Id, tool);
        var button = new Button { Content = tool.Label, Tag = tool };
        button.Click += Tool_Click;
        EssentialChoices.Children.Add(button);
    }

    private void Continue_Click(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrWhiteSpace(TaskInput.Text)) { StatusText.Text = "Enter a task first."; TaskInput.Focus(); return; }
        StatusText.Text = "";
        TaskStep.Visibility = Visibility.Collapsed; ToolsStep.Visibility = Visibility.Visible;
    }

    private void BackToTask_Click(object sender, RoutedEventArgs e)
    {
        ToolsStep.Visibility = Visibility.Visible; TaskStep.Visibility = Visibility.Visible;
        TaskInput.Focus();
    }

    private void UseTask_Click(object sender, RoutedEventArgs e)
    {
        if (!CanUseLatestState() || state?.Phase != "pick_frog") return;
        try
        {
            var domains = state.Domains.ToList();
            var apps = state.Tools.Select(tool => tool.Id).ToList();
            if (apps.Count > 64 || domains.Count > 64) throw new InvalidDataException("Choose up to 64 essential apps and websites.");
            protocol.WriteAction(new FocusLockAction { Action = "select_frog", Title = TaskInput.Text.Trim(), AppIds = apps, Domains = domains });
            UseTaskButton.IsEnabled = false;
            StatusText.Text = "Saving your task…";
        }
        catch (InvalidDataException ex) { StatusText.Text = ex.Message; }
    }

    private void RebuildDomainButtons(FocusLockSessionState next)
    {
        DomainsPanel.Children.Clear();
        foreach (var domain in next.Domains)
        {
            var button = new Button { Content = domain, Tag = domain };
            button.Click += Domain_Click;
            DomainsPanel.Children.Add(button);
        }
    }

    private void RebuildEssentialDomains(FocusLockSessionState next)
    {
        EssentialDomains.Children.Clear();
        foreach (var domain in next.Domains)
        {
            var button = new Button { Content = domain, Tag = domain };
            button.Click += Domain_Click;
            EssentialDomains.Children.Add(button);
        }
    }

    private async void Tool_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button { Tag: FocusLockTool tool } || !CanUseLatestState()) return;
        try
        {
            var mappedPath = config.Apps.FirstOrDefault(app => !string.IsNullOrWhiteSpace(app.Path) &&
                string.Equals(Path.GetFileName(app.Path), tool.Id, StringComparison.OrdinalIgnoreCase))?.Path;
            var launchPath = tool.ExecutablePath ?? mappedPath;
            var args = ResolveArguments(tool);
            if (!string.IsNullOrWhiteSpace(launchPath))
            {
                if (!File.Exists(launchPath))
                {
                    launchPath = BrowseForTool(tool.Id);
                    if (launchPath is null) return;
                }
                EnsureMatchingExecutable(tool.Id, launchPath);
                Process.Start(new ProcessStartInfo(launchPath) { Arguments = args, UseShellExecute = true });
            }
            else
            {
                StatusText.Text = $"Finding the installed app for {tool.Id}…";
                var installed = await InstalledApps.LoadAsync();
                var candidates = InstalledApps.FindByExecutableName(installed, tool.Id);
                if (!string.IsNullOrWhiteSpace(tool.AppUserModelId))
                    candidates = candidates.Where(app => string.Equals(app.AppUserModelId, tool.AppUserModelId, StringComparison.OrdinalIgnoreCase)).ToList();
                if (candidates.Count > 1)
                {
                    var selected = ChooseInstalledApp(candidates);
                    if (selected is null) { StatusText.Text = ""; return; }
                    candidates = [selected];
                }
                if (candidates.Count == 1)
                {
                    var app = candidates[0];
                    var saved = config.Apps.FirstOrDefault(entry => string.Equals(entry.AppUserModelId, app.AppUserModelId, StringComparison.OrdinalIgnoreCase));
                    PackagedAppLauncher.Activate(app.AppUserModelId, saved?.Args);
                }
                else
                {
                    launchPath = BrowseForTool(tool.Id);
                    if (launchPath is null) { StatusText.Text = ""; return; }
                    EnsureMatchingExecutable(tool.Id, launchPath);
                    Process.Start(new ProcessStartInfo(launchPath) { Arguments = args, UseShellExecute = true });
                }
            }
            Topmost = false;
            StatusText.Text = "";
        }
        catch (Exception ex) { ShowActionError(tool.Id, ex); }
    }

    private string ResolveArguments(FocusLockTool tool)
    {
        var mapped = config.Apps.FirstOrDefault(app =>
            (!string.IsNullOrWhiteSpace(app.Path) && string.Equals(Path.GetFileName(app.Path), tool.Id, StringComparison.OrdinalIgnoreCase)) ||
            (!string.IsNullOrWhiteSpace(tool.AppUserModelId) && string.Equals(app.AppUserModelId, tool.AppUserModelId, StringComparison.OrdinalIgnoreCase)));
        return mapped?.Args ?? "";
    }

    private string? BrowseForTool(string executableId)
    {
        var dialog = new OpenFileDialog { Title = $"Choose {executableId}", Filter = "Applications (*.exe)|*.exe", CheckFileExists = true };
        if (dialog.ShowDialog(this) != true) return null;
        EnsureMatchingExecutable(executableId, dialog.FileName);
        return dialog.FileName;
    }

    private static void EnsureMatchingExecutable(string executableId, string path)
    {
        if (!string.Equals(Path.GetFileName(path), executableId, StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException($"Choose an executable named {executableId} so it matches FocusLock's approved tool.");
    }

    private InstalledApp? ChooseInstalledApp(IReadOnlyList<InstalledApp> apps)
    {
        var chooser = new Window
        {
            Title = "Choose installed app",
            Owner = this,
            Width = 420,
            Height = 300,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            ResizeMode = ResizeMode.NoResize,
            Background = System.Windows.Media.Brushes.Black,
            Foreground = System.Windows.Media.Brushes.White,
        };
        var layout = new System.Windows.Controls.DockPanel { Margin = new Thickness(16) };
        var actions = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right };
        var accept = new Button { Content = "Open selected", MinWidth = 110, Margin = new Thickness(4) };
        var cancel = new Button { Content = "Cancel", MinWidth = 80, Margin = new Thickness(4) };
        System.Windows.Controls.DockPanel.SetDock(actions, Dock.Bottom);
        actions.Children.Add(accept);
        actions.Children.Add(cancel);
        var list = new ListBox { ItemsSource = apps, DisplayMemberPath = nameof(InstalledApp.Name), Margin = new Thickness(0, 0, 0, 12) };
        layout.Children.Add(actions);
        layout.Children.Add(list);
        chooser.Content = layout;
        accept.Click += (_, _) => { if (list.SelectedItem is not null) chooser.DialogResult = true; };
        cancel.Click += (_, _) => chooser.DialogResult = false;
        list.MouseDoubleClick += (_, _) => { if (list.SelectedItem is not null) chooser.DialogResult = true; };
        return chooser.ShowDialog() == true ? (InstalledApp?)list.SelectedItem : null;
    }

    private void Domain_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button { Tag: string domain } || !CanUseLatestState()) return;
        try { LaunchChrome("https://" + domain); Topmost = false; StatusText.Text = ""; }
        catch (Exception ex) { ShowActionError(domain, ex); }
    }

    private static void LaunchChrome(string url)
    {
        var candidates = new[]
        {
            "chrome.exe",
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Google\\Chrome\\Application\\chrome.exe"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Google\\Chrome\\Application\\chrome.exe"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), "Google\\Chrome\\Application\\chrome.exe"),
        };
        var executable = candidates.FirstOrDefault(path => path.Equals("chrome.exe", StringComparison.OrdinalIgnoreCase) || File.Exists(path));
        if (executable is null) throw new FileNotFoundException("Google Chrome was not found.");
        Process.Start(new ProcessStartInfo(executable, url) { UseShellExecute = true });
    }

    private bool CanUseLatestState()
    {
        if (state is null || Stopwatch.GetElapsedTime(lastStateTimestamp) > TimeSpan.FromSeconds(10))
        {
            StatusText.Text = "FocusLock connection is stale. Reopen the launcher to continue.";
            return false;
        }
        return true;
    }

    private void ShowActionError(string id, Exception ex)
    {
        StatusText.Text = $"Couldn't open {id}: {ex.Message}";
        protocol.WriteAction(new FocusLockAction { Action = "launch_error", Message = ex.Message });
    }

    private void TimerButton_Click(object sender, RoutedEventArgs e)
    {
        if (CanUseLatestState()) { StatusText.Text = ""; protocol.WriteAction(new FocusLockAction { Action = "toggle_timer" }); }
    }

    private void TickBox_Checked(object sender, RoutedEventArgs e)
    {
        if (applyingState) return;
        if (CanUseLatestState())
        {
            StatusText.Text = "";
            protocol.WriteAction(new FocusLockAction { Action = "tick_off" });
        }
        else TickBox.IsChecked = false;
    }

    private void DurationBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (IsLoaded && !DurationBox.IsEnabled && state?.Running == true) return;
        if (!IsLoaded || applyingState || DurationBox.SelectedItem is not int minutes || !CanUseLatestState()) return;
        StatusText.Text = "";
        protocol.WriteAction(new FocusLockAction { Action = "set_duration", Minutes = minutes });
    }

    private void Window_Loaded(object sender, RoutedEventArgs e)
    {
        ConfigurePresentation();
        source = HwndSource.FromHwnd(new WindowInteropHelper(this).Handle);
        source?.AddHook(WindowMessage);
        heartbeat.Start();
        recoveryHotkeyAvailable = NativeMethods.RegisterHotKey(new WindowInteropHelper(this).Handle, ToggleHotkey, ModControl | ModAlt, 0x20);
        // The host owns the emergency shortcut so it can disable Eat the Frog persistently.
        if (!recoveryHotkeyAvailable)
            RecoveryText.Text = "Hotkey unavailable: Ctrl+Alt+Space. Use Back to desktop to close this screen.";
        if (state?.Phase is "pick_frog" or "working") BeginEnvironment();
        if (state?.Phase == "pick_frog") TaskInput.Focus();
    }

    public void ShowConnectionEnded(string reason) => StatusText.Text = reason;
    public void CloseFromHost() { allowClose = true; Close(); }

    private nint WindowMessage(nint hwnd, int message, nint wParam, nint lParam, ref bool handled)
    {
        if (message == WmReturnToFrog)
        {
            RestoreFrogSurface();
            handled = true;
            return 0;
        }
        if (message == WmHotkey)
        {
            if (wParam.ToInt32() == ToggleHotkey)
            {
                RestoreFrogSurface();
            }
            handled = true;
        }
        return 0;
    }

    private void RestoreFrogSurface()
    {
        Show();
        ConfigurePresentation();
        Topmost = true;
        Activate();
    }

    private void OpenFocusLock_Click(object sender, RoutedEventArgs e)
    {
        if (!CanUseLatestState()) return;
        protocol.WriteAction(new FocusLockAction { Action = "open_focuslock" });
        Topmost = false;
        Hide();
    }

    private void CloseSurface_Click(object sender, RoutedEventArgs e) => CloseSurface();

    private void Window_KeyDown(object sender, System.Windows.Input.KeyEventArgs e)
    {
        if (e.Key == System.Windows.Input.Key.Escape) { CloseSurface(); e.Handled = true; }
    }

    private void CloseSurface()
    {
        allowClose = true;
        protocol.WriteAction(new FocusLockAction { Action = "closed" });
        Close();
    }

    private void Window_Closing(object? sender, CancelEventArgs e)
    {
        if (!allowClose)
        {
            allowClose = true;
            protocol.WriteAction(new FocusLockAction { Action = "closed" });
        }
    }

    protected override void OnClosed(EventArgs e)
    {
        var hwnd = new WindowInteropHelper(this).Handle;
        if (recoveryHotkeyAvailable) NativeMethods.UnregisterHotKey(hwnd, ToggleHotkey);
        source?.RemoveHook(WindowMessage);
        protocol.RequestStop();
        heartbeat.Stop();
        if (TaskbarSession.Active) TaskbarSession.Restore();
        base.OnClosed(e);
    }

    private static string FormatTime(long seconds) => TimeSpan.FromSeconds(seconds).ToString(seconds >= 3600 ? @"h\:mm\:ss" : @"m\:ss");
    private static string FormatMinutes(long seconds) => (seconds / 60.0).ToString("0.#", System.Globalization.CultureInfo.InvariantCulture);
}
