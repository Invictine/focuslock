using System.Media;
using System.Windows;
using System.Windows.Controls;

namespace VoidApp;

public partial class MainWindow
{
    private FocusSession? currentSession;
    private FocusLock? breakLock;
    private bool completionAnnounced;
    private bool breakAnnounced;

    private void RefreshMenu()
    {
        Heading.Text = config.Title;
        Choices.Items.Clear();
        foreach (var app in config.Apps) Choices.Items.Add(app);
        Choices.Items.Add("Exit Focus");
        Choices.SelectedIndex = 0;
        RefreshSummary();
    }
    private void RefreshSummary()
    {
        try
        {
            var today = SessionHistory.Load().Where(s => s.StartedAt.LocalDateTime.Date == DateTime.Today).ToList();
            TodaySummary.Text = today.Count == 0 ? "Choose an app. Make time for one thing." : $"Today · {today.Count(s => s.Completed)} blocks completed · {today.Sum(s => (long)s.FocusedSeconds) / 60} minutes focused";
        }
        catch { TodaySummary.Text = "History unavailable · open History for details"; }
    }
    private void Settings_Click(object sender, RoutedEventArgs e)
    {
        CancelHold();
        var settings = new SettingsWindow(config) { Owner = this };
        if (settings.ShowDialog() == true && settings.SavedConfig is { } saved)
        {
            config = saved; RefreshMenu();
            try { Startup.Apply(config.StartWithWindows); Status.Text = "Settings saved."; }
            catch (Exception ex) { Status.Text = "Settings saved, but Windows startup could not be updated: " + ex.Message; }
        }
        Choices.Focus();
    }
    private void History_Click(object sender, RoutedEventArgs e)
    {
        CancelHold(); new HistoryWindow { Owner = this }.ShowDialog(); Choices.Focus();
    }
    private void Preset_Click(object sender, RoutedEventArgs e)
    {
        MinutesInput.Text = ((Button)sender).Tag.ToString(); MinutesInput.Focus(); MinutesInput.SelectAll();
    }
    private void RecordSession(bool completed)
    {
        if (currentSession is null) return;
        currentSession.Completed = completed;
        currentSession.FocusedSeconds = (int)Math.Clamp(currentSession.PlannedMinutes * 60d - focusLock.Remaining.TotalSeconds, 0, currentSession.PlannedMinutes * 60d);
        try { SessionHistory.Record(currentSession); currentSession = null; RefreshSummary(); }
        catch (Exception ex) { Status.Text = "Could not save this session: " + ex.Message; }
    }
    private void AnnounceCompletion()
    {
        if (completionAnnounced) return;
        completionAnnounced = true;
        RecordSession(true);
        if (config.CompletionSound) SystemSounds.Asterisk.Play();
        // Bring the completion screen forward once so it is visible while the work app is open.
        ShowFocus();
    }
    private void Break_Click(object sender, RoutedEventArgs e)
    {
        if (focusLock.IsLocked) return;
        RecordSession(true);
        activeApp = null; guard.Clear(); workInFront = false;
        breakLock = new FocusLock(); breakLock.Start(config.BreakMinutes); breakAnnounced = false;
        ShowPanel(BreakPanel); UpdateBreakTimer(); Status.Text = "";
    }
    private void UpdateBreakTimer()
    {
        if (breakLock is null) return;
        long seconds = (long)Math.Ceiling(breakLock.Remaining.TotalSeconds);
        BreakTimerText.Text = $"{seconds / 60:00}:{seconds % 60:00}";
        BreakHint.Text = breakLock.IsLocked ? "Stretch, get water, rest your eyes." : "Ready for your next block.";
        if (!breakLock.IsLocked && !breakAnnounced)
        {
            breakAnnounced = true;
            if (config.CompletionSound) SystemSounds.Asterisk.Play();
            ShowFocus();
        }
    }
    private void EndBreak_Click(object sender, RoutedEventArgs e)
    {
        breakLock = null; focusLock = new FocusLock(); NewBlock_Click(sender, e);
    }
    private void BreakExit_Click(object sender, RoutedEventArgs e) => ReturnToLauncher();
}
