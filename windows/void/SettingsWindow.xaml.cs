using System.Collections.ObjectModel;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using Microsoft.Win32;

namespace VoidApp;

public partial class SettingsWindow : Window
{
    private readonly ObservableCollection<AppEntry> apps;
    private AppEntry? editing;
    private bool selecting;
    private bool appDirty;
    public Config? SavedConfig { get; private set; }

    public SettingsWindow(Config config)
    {
        InitializeComponent();
        apps = new(config.Apps.Select(a => new AppEntry { Name = a.Name, Path = a.Path, Args = a.Args, AppUserModelId = a.AppUserModelId }));
        AppList.ItemsSource = apps;
        TitleInput.Text = config.Title;
        FocusMinutesInput.Text = config.LockMinutes.ToString();
        BreakMinutesInput.Text = config.BreakMinutes.ToString();
        GuardChoice.IsChecked = config.Guard;
        CloseChoice.IsChecked = config.CloseOtherApps;
        SoundChoice.IsChecked = config.CompletionSound;
        StartupChoice.IsChecked = config.StartWithWindows;
        PrimaryMonitorChoice.IsChecked = config.PrimaryMonitorOnly;
        StorageLocation.Text = Config.UserDirectory;
        foreach (var input in new[] { AppNameInput, TargetInput, ArgsInput }) input.TextChanged += (_, _) => { if (!selecting) appDirty = true; };
        Loaded += async (_, _) =>
        {
            try
            {
                var installed = await InstalledApps.LoadAsync();
                PackagedApps.ItemsSource = installed.OrderBy(a => a.Name).ToList();
                DiscoveryHint.Text = installed.Count == 0 ? "No packaged apps found. Browse for an executable above." : "Choose an app to fill its name and target.";
            }
            catch (Exception ex) { DiscoveryHint.Text = ex.Message; }
        };
    }

    private void AppList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        // Avoid quietly discarding an unapplied edit when choosing another row.
        if (selecting) return;
        if (appDirty)
        {
            selecting = true; AppList.SelectedItem = editing; selecting = false;
            ErrorText.Text = "Apply this app edit first, or choose New to discard it.";
            return;
        }
        editing = AppList.SelectedItem as AppEntry;
        selecting = true;
        AppNameInput.Text = editing?.Name ?? "";
        TargetInput.Text = !string.IsNullOrWhiteSpace(editing?.AppUserModelId) ? editing.AppUserModelId : editing?.Path ?? "";
        ArgsInput.Text = editing?.Args ?? "";
        PackagedApps.SelectedIndex = -1;
        selecting = false;
        ApplyAppButton.Content = editing is null ? "Add app" : "Apply app edit";
        ErrorText.Text = "";
    }
    private void New_Click(object sender, RoutedEventArgs e)
    {
        appDirty = false; editing = null;
        selecting = true; AppList.SelectedIndex = -1; selecting = false;
        AppList_SelectionChanged(AppList, new SelectionChangedEventArgs(SelectorEvent(), Array.Empty<object>(), Array.Empty<object>()));
        AppNameInput.Focus();
    }
    private static RoutedEvent SelectorEvent() => System.Windows.Controls.Primitives.Selector.SelectionChangedEvent;
    private void Remove_Click(object sender, RoutedEventArgs e)
    {
        if (editing is null) return;
        appDirty = false;
        var item = editing; editing = null; apps.Remove(item);
        AppList_SelectionChanged(AppList, new SelectionChangedEventArgs(SelectorEvent(), Array.Empty<object>(), Array.Empty<object>()));
    }
    private void Up_Click(object sender, RoutedEventArgs e) => Move(-1);
    private void Down_Click(object sender, RoutedEventArgs e) => Move(1);
    private void Move(int delta)
    {
        int index = AppList.SelectedIndex;
        if (appDirty) { ErrorText.Text = "Apply your app edit before moving it."; return; }
        if (index < 0 || index + delta < 0 || index + delta >= apps.Count) return;
        selecting = true; apps.Move(index, index + delta); AppList.SelectedIndex = index + delta; selecting = false;
    }
    private void Browse_Click(object sender, RoutedEventArgs e)
    {
        var picker = new OpenFileDialog { Title = "Choose a work app", Filter = "Windows apps (*.exe)|*.exe", CheckFileExists = true };
        if (picker.ShowDialog(this) != true) return;
        TargetInput.Text = picker.FileName;
        if (string.IsNullOrWhiteSpace(AppNameInput.Text)) AppNameInput.Text = Path.GetFileNameWithoutExtension(picker.FileName);
        PackagedApps.SelectedIndex = -1;
    }
    private void PackagedApps_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (selecting || PackagedApps.SelectedItem is not InstalledApp app) return;
        AppNameInput.Text = app.Name; TargetInput.Text = app.AppUserModelId; ArgsInput.Text = "";
    }
    private void ApplyApp_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            string name = AppNameInput.Text.Trim(), target = TargetInput.Text.Trim().Trim('"');
            if (name.Length == 0 || target.Length == 0) throw new InvalidDataException("Enter an app name and choose its target.");
            bool packaged = target.Contains('!') && !target.Contains('\\') && !target.Contains('/');
            if (!packaged)
            {
                target = Path.GetFullPath(Environment.ExpandEnvironmentVariables(target), AppContext.BaseDirectory);
                if (!File.Exists(target) || !target.EndsWith(".exe", StringComparison.OrdinalIgnoreCase))
                    throw new InvalidDataException("Choose an existing .exe file, or an installed Windows app.");
            }
            var item = new AppEntry { Name = name, Path = packaged ? "" : target, AppUserModelId = packaged ? target : "", Args = ArgsInput.Text };
            int index = editing is null ? -1 : apps.IndexOf(editing);
            appDirty = false;
            selecting = true;
            if (index < 0) apps.Add(item); else apps[index] = item;
            AppList.SelectedItem = item;
            selecting = false;
            AppList_SelectionChanged(AppList, new SelectionChangedEventArgs(SelectorEvent(), Array.Empty<object>(), Array.Empty<object>()));
            ErrorText.Text = "";
        }
        catch (Exception ex) { ErrorText.Text = ex.Message; }
    }
    private void Save_Click(object sender, RoutedEventArgs e)
    {
        if (appDirty) { ErrorText.Text = "Apply your app edit before saving settings."; return; }
        if (!int.TryParse(FocusMinutesInput.Text, out int focus) || focus is < 1 or > 1440)
        { ErrorText.Text = "Focus duration must be between 1 and 1440 minutes."; FocusMinutesInput.Focus(); return; }
        if (!int.TryParse(BreakMinutesInput.Text, out int rest) || rest is < 1 or > 60)
        { ErrorText.Text = "Break duration must be between 1 and 60 minutes."; BreakMinutesInput.Focus(); return; }
        try
        {
            var result = new Config { Title = TitleInput.Text.Trim(), Apps = apps.ToList(), LockMinutes = focus, BreakMinutes = rest,
                Guard = GuardChoice.IsChecked == true, CloseOtherApps = CloseChoice.IsChecked == true,
                CompletionSound = SoundChoice.IsChecked == true, StartWithWindows = StartupChoice.IsChecked == true,
                PrimaryMonitorOnly = PrimaryMonitorChoice.IsChecked == true };
            result.Save();
            SavedConfig = result;
            DialogResult = true;
        }
        catch (Exception ex) { ErrorText.Text = "Could not save settings: " + ex.Message; }
    }
    private void Cancel_Click(object sender, RoutedEventArgs e) => DialogResult = false;
}
