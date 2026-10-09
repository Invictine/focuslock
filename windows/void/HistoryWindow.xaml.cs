using System.Globalization;
using System.IO;
using System.Text;
using System.Windows;
using Microsoft.Win32;

namespace VoidApp;

public partial class HistoryWindow : Window
{
    private List<FocusSession> sessions = [];
    public HistoryWindow()
    {
        InitializeComponent();
        try
        {
            sessions = SessionHistory.Load();
            var today = sessions.Where(s => s.StartedAt.LocalDateTime.Date == DateTime.Today).ToList();
            SummaryText.Text = $"Today · {today.Count(s => s.Completed)} completed blocks · {today.Sum(s => (long)s.FocusedSeconds) / 60} focus minutes";
            SessionList.ItemsSource = sessions.Select(s => new
            {
                Started = s.StartedAt.LocalDateTime.ToString("dd MMM, HH:mm"), s.AppName,
                Duration = $"{s.FocusedSeconds / 60}m {s.FocusedSeconds % 60:00}s",
                Result = s.Completed ? "Completed" : "Ended early"
            }).ToList();
            EmptyText.Visibility = sessions.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
            ExportButton.IsEnabled = sessions.Count > 0;
            StatusText.Text = "Latest 500 sessions · stored locally";
        }
        catch (Exception ex)
        {
            EmptyText.Visibility = Visibility.Collapsed;
            SummaryText.Text = "History could not be loaded. Your saved file has been preserved.";
            StatusText.Text = ex.Message; ExportButton.IsEnabled = false;
        }
    }
    private void Export_Click(object sender, RoutedEventArgs e)
    {
        var picker = new SaveFileDialog { Title = "Export focus history", Filter = "CSV files (*.csv)|*.csv", FileName = $"Void-history-{DateTime.Today:yyyy-MM-dd}.csv" };
        if (picker.ShowDialog(this) != true) return;
        try
        {
            var csv = new StringBuilder("Started,App,Planned minutes,Focused seconds,Completed\r\n");
            foreach (var session in sessions)
                csv.AppendLine($"{session.StartedAt.ToString("O", CultureInfo.InvariantCulture)},{CsvCell(session.AppName)},{session.PlannedMinutes},{session.FocusedSeconds},{session.Completed}");
            File.WriteAllText(picker.FileName, csv.ToString(), new UTF8Encoding(true));
            StatusText.Text = "History exported.";
        }
        catch (Exception ex) { StatusText.Text = "Could not export: " + ex.Message; }
    }
    private static string CsvCell(string value)
    {
        // Treat names as text when opened in a spreadsheet, including formula-like names.
        if (value.TrimStart().StartsWith('=') || value.TrimStart().StartsWith('+') || value.TrimStart().StartsWith('-') || value.TrimStart().StartsWith('@')) value = "'" + value;
        return "\"" + value.Replace("\"", "\"\"") + "\"";
    }
    private void Close_Click(object sender, RoutedEventArgs e) => Close();
}
