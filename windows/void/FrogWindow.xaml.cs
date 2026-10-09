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
    private const int EmergencyHotkey = 2;
    private const int WmHotkey = 0x0312;
    private const int WmReturnToFrog = 0x8003;
    private const uint ModAlt = 0x0001, ModControl = 0x0002, ModShift = 0x0004;
    private readonly FocusLockProtocol protocol;
    private readonly Config config;
    private HwndSource? source;
    private FocusLockSessionState? state;
    private bool allowClose;
    private bool recoveryHotkeyAvailable;
    private bool emergencyHotkeyAvailable;
    private bool applyingState;
    private readonly DispatcherTimer heartbeat = new() { Interval = TimeSpan.FromSeconds(1) };
    private long lastStateTimestamp;
    private string? toolLayoutKey;
    private string? domainLayoutKey;

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
        state = next;
        lastStateTimestamp = Stopwatch.GetTimestamp();
        TitleText.Text = string.IsNullOrWhiteSpace(next.Title) ? "Your next task" : next.Title;
        ProjectText.Text = next.ProjectName ?? "";
        PhaseText.Text = next.Phase switch
        {
            "not_armed" => "NOT ARMED",
            "pick_frog" => "PICK YOUR FROG",
            "working" => "WORKING",
            "complete" => "COMPLETE",
            _ => next.Phase.ToUpperInvariant(),
        };
        TimerText.Text = FormatTime(next.RemainingSeconds);
        ProgressText.Text = $"{FormatMinutes(next.TrackedSeconds)} / {FormatMinutes(next.RequiredSeconds)} minutes · {next.CycleDate}";
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
            var button = new Button { Content = tool.Label, Tag = tool, MinWidth = 112 };
            button.Click += Tool_Click;
            ToolsPanel.Children.Add(button);
        }
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
            Background = new System.Windows.Media.SolidColorBrush(System.Windows.Media.Color.FromRgb(10, 12, 10)),
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
        try { Process.Start(new ProcessStartInfo("https://" + domain) { UseShellExecute = true }); Topmost = false; StatusText.Text = ""; }
        catch (Exception ex) { ShowActionError(domain, ex); }
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
        source = HwndSource.FromHwnd(new WindowInteropHelper(this).Handle);
        source?.AddHook(WindowMessage);
        heartbeat.Start();
        recoveryHotkeyAvailable = NativeMethods.RegisterHotKey(new WindowInteropHelper(this).Handle, ToggleHotkey, ModControl | ModAlt, 0x20);
        emergencyHotkeyAvailable = NativeMethods.RegisterHotKey(new WindowInteropHelper(this).Handle, EmergencyHotkey, ModControl | ModAlt | ModShift, 0x7B);
        if (!recoveryHotkeyAvailable || !emergencyHotkeyAvailable)
            RecoveryText.Text = $"Hotkey unavailable: {string.Join(" and ", new[] { !recoveryHotkeyAvailable ? "Ctrl+Alt+Space" : null, !emergencyHotkeyAvailable ? "Ctrl+Alt+Shift+F12" : null }.Where(x => x is not null))}. Use Back to desktop to close this screen.";
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
            else if (wParam.ToInt32() == EmergencyHotkey)
            {
                allowClose = true;
                protocol.WriteAction(new FocusLockAction { Action = "closed" });
                Close();
            }
            handled = true;
        }
        return 0;
    }

    private void RestoreFrogSurface()
    {
        Show();
        WindowState = WindowState.Maximized;
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
        if (emergencyHotkeyAvailable) NativeMethods.UnregisterHotKey(hwnd, EmergencyHotkey);
        source?.RemoveHook(WindowMessage);
        protocol.RequestStop();
        heartbeat.Stop();
        base.OnClosed(e);
    }

    private static string FormatTime(long seconds) => TimeSpan.FromSeconds(seconds).ToString(seconds >= 3600 ? @"h\:mm\:ss" : @"m\:ss");
    private static string FormatMinutes(long seconds) => (seconds / 60.0).ToString("0.#", System.Globalization.CultureInfo.InvariantCulture);
}
