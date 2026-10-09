using System.ComponentModel;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Interop;

namespace VoidApp;

/// <summary>A small always-on-top entry point that stays out of the task switcher.</summary>
public partial class LauncherWindow : Window
{
    private const int WmDisplayChange = 0x007E;
    private const int WmSettingChange = 0x001A;
    private readonly Action openHome;
    private readonly Action<RecentPreset> quickLaunch;
    private readonly Func<IReadOnlyList<RecentPreset>> loadRecents;
    private readonly Action exitApp;
    private HwndSource? hwndSource;
    private bool hasPlacedInitially;

    public LauncherWindow(Action openHome, Action<RecentPreset> quickLaunch,
        Func<IReadOnlyList<RecentPreset>> loadRecents, Action exitApp)
    {
        this.openHome = openHome ?? throw new ArgumentNullException(nameof(openHome));
        this.quickLaunch = quickLaunch ?? throw new ArgumentNullException(nameof(quickLaunch));
        this.loadRecents = loadRecents ?? throw new ArgumentNullException(nameof(loadRecents));
        this.exitApp = exitApp ?? throw new ArgumentNullException(nameof(exitApp));
        InitializeComponent();

        SourceInitialized += (_, _) =>
        {
            hwndSource = HwndSource.FromHwnd(new WindowInteropHelper(this).Handle);
            hwndSource?.AddHook(WindowMessage);
        };
        LocationChanged += (_, _) => ClampToWorkArea();
        Closed += (_, _) => hwndSource?.RemoveHook(WindowMessage);
    }

    /// <summary>Rebuild the recent-preset menu from the latest saved block history.</summary>
    public void RefreshRecents() => BuildRecentMenu();

    private void Window_Loaded(object sender, RoutedEventArgs e)
    {
        if (!hasPlacedInitially)
        {
            PlaceAtBottomRight();
            hasPlacedInitially = true;
        }
        BuildRecentMenu();
    }

    private void Window_Closing(object? sender, CancelEventArgs e)
    {
        // Keep the persistent launcher visible for ordinary close requests. The Quit menu action
        // owns application shutdown and the framework will close this window during dispatcher exit.
        e.Cancel = true;
    }

    private void Home_Click(object sender, RoutedEventArgs e) => openHome();

    private void Recent_Click(object sender, RoutedEventArgs e)
    {
        BuildRecentMenu();
        if (RecentButton.ContextMenu is { } menu)
        {
            menu.PlacementTarget = RecentButton;
            menu.Placement = System.Windows.Controls.Primitives.PlacementMode.Top;
            menu.IsOpen = true;
        }
    }

    private void BuildRecentMenu()
    {
        var menu = new ContextMenu
        {
            Style = (Style)FindResource("LauncherMenu"),
            Background = new System.Windows.Media.SolidColorBrush(System.Windows.Media.Color.FromRgb(10, 10, 10)),
            Foreground = System.Windows.Media.Brushes.White,
            BorderBrush = new System.Windows.Media.SolidColorBrush(System.Windows.Media.Color.FromRgb(42, 42, 42))
        };
        AutomationProperties.SetName(menu, "Void launcher actions");
        menu.Resources[typeof(MenuItem)] = FindResource("LauncherMenuItem");
        menu.Resources[typeof(Separator)] = FindResource("LauncherSeparator");
        IReadOnlyList<RecentPreset> recents;
        try
        {
            recents = loadRecents();
            foreach (var preset in recents.Take(5))
            {
                string appsSetting = preset.CloseOtherApps ? "Close other apps: On" : "Close other apps: Off";
                var item = new MenuItem
                {
                    Header = $"{preset.Name} · {preset.Minutes} min",
                    ToolTip = $"Quick launch this preset\nSaved setting — {appsSetting}"
                };
                AutomationProperties.SetName(item, $"Quick launch {preset.Name}, {preset.Minutes} minutes. {appsSetting}.");
                item.Click += (_, _) => quickLaunch(preset);
                menu.Items.Add(item);
            }
            if (menu.Items.Count == 0)
                menu.Items.Add(DisabledRow("Start a block to save a preset", "Recent presets"));
        }
        catch
        {
            menu.Items.Add(DisabledRow("Recent presets unavailable", "Recent presets unavailable"));
        }

        menu.Items.Add(new Separator());
        var homeItem = new MenuItem { Header = "Open Void homepage" };
        AutomationProperties.SetName(homeItem, "Open Void homepage");
        homeItem.Click += (_, _) => openHome();
        menu.Items.Add(homeItem);
        var quitItem = new MenuItem { Header = "Quit Void" };
        AutomationProperties.SetName(quitItem, "Quit Void");
        quitItem.Click += (_, _) => exitApp();
        menu.Items.Add(quitItem);
        RecentButton.ContextMenu = menu;
    }

    private static MenuItem DisabledRow(string label, string automationName)
    {
        var row = new MenuItem { Header = label, IsEnabled = false };
        AutomationProperties.SetName(row, automationName);
        return row;
    }

    private void Capsule_MouseLeftButtonDown(object sender, System.Windows.Input.MouseButtonEventArgs e)
    {
        if (e.ButtonState != System.Windows.Input.MouseButtonState.Pressed) return;
        DragMove();
        ClampToWorkArea();
        e.Handled = true;
    }

    private void DragHandle_MouseLeftButtonDown(object sender, System.Windows.Input.MouseButtonEventArgs e) =>
        Capsule_MouseLeftButtonDown(sender, e);

    private void PlaceAtBottomRight()
    {
        Rect area = SystemParameters.WorkArea;
        Left = area.Right - Width - 24;
        Top = area.Bottom - Height - 24;
        ClampToWorkArea();
    }

    private void ClampToWorkArea()
    {
        if (!IsLoaded) return;
        Rect area = SystemParameters.WorkArea;
        double maxLeft = Math.Max(area.Left, area.Right - ActualWidth);
        double maxTop = Math.Max(area.Top, area.Bottom - ActualHeight);
        double nextLeft = Math.Clamp(Left, area.Left, maxLeft);
        double nextTop = Math.Clamp(Top, area.Top, maxTop);
        if (Math.Abs(Left - nextLeft) > 0.1) Left = nextLeft;
        if (Math.Abs(Top - nextTop) > 0.1) Top = nextTop;
    }

    private nint WindowMessage(nint hwnd, int message, nint wParam, nint lParam, ref bool handled)
    {
        if (message is WmDisplayChange or WmSettingChange)
            Dispatcher.BeginInvoke(new Action(ClampToWorkArea));
        return 0;
    }
}
