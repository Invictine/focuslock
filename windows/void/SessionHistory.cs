using System.IO;
using System.Text.Json;

namespace VoidApp;

public sealed class FocusSession
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public string AppName { get; set; } = "";
    public DateTimeOffset StartedAt { get; set; } = DateTimeOffset.Now;
    public int PlannedMinutes { get; set; }
    public int FocusedSeconds { get; set; }
    public bool Completed { get; set; }
}

public static class SessionHistory
{
    private const int MaximumRecords = 500;
    private static readonly JsonSerializerOptions JsonOptions = new() { PropertyNameCaseInsensitive = true, WriteIndented = true };
    public static string HistoryPath => Path.Combine(Config.UserDirectory, "history.json");

    /// <summary>Returns saved sessions newest first. Corrupt history is preserved and reported.</summary>
    public static List<FocusSession> Load()
    {
        if (!File.Exists(HistoryPath)) return [];
        try
        {
            var sessions = JsonSerializer.Deserialize<List<FocusSession>>(File.ReadAllText(HistoryPath), JsonOptions)
                ?? throw new InvalidDataException("The session history file contains no JSON value.");
            if (sessions.Any(s => s is null || s.Id == Guid.Empty || string.IsNullOrWhiteSpace(s.AppName) ||
                s.PlannedMinutes < 1 || s.FocusedSeconds < 0))
                throw new InvalidDataException("The session history file contains an invalid session record.");
            return sessions.OrderByDescending(s => s.StartedAt).Take(MaximumRecords).ToList();
        }
        catch (JsonException ex)
        {
            throw new InvalidDataException($"Session history at '{HistoryPath}' is corrupt; the original file was preserved.", ex);
        }
    }

    public static void Record(FocusSession session)
    {
        ArgumentNullException.ThrowIfNull(session);
        if (session.Id == Guid.Empty) session.Id = Guid.NewGuid();
        if (string.IsNullOrWhiteSpace(session.AppName)) throw new ArgumentException("A session needs an app name.", nameof(session));
        if (session.PlannedMinutes < 1) throw new ArgumentOutOfRangeException(nameof(session), "PlannedMinutes must be at least 1.");
        if (session.FocusedSeconds < 0) throw new ArgumentOutOfRangeException(nameof(session), "FocusedSeconds cannot be negative.");

        var sessions = Load();
        sessions.RemoveAll(existing => existing.Id == session.Id);
        sessions.Add(session);
        sessions = sessions.OrderByDescending(s => s.StartedAt).Take(MaximumRecords).ToList();
        Directory.CreateDirectory(Config.UserDirectory);
        var tempPath = Path.Combine(Config.UserDirectory, $"history.{Guid.NewGuid():N}.tmp");
        try
        {
            File.WriteAllText(tempPath, JsonSerializer.Serialize(sessions, JsonOptions));
            File.Move(tempPath, HistoryPath, overwrite: true);
        }
        finally
        {
            if (File.Exists(tempPath)) File.Delete(tempPath);
        }
    }
}
