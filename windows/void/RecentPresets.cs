using System.IO;
using System.Text.Json;

namespace VoidApp;

/// <summary>A recently launched app preset. Name is presentation text; target fields identify the app.</summary>
public sealed class RecentPreset
{
    public string Name { get; set; } = "";
    public string Path { get; set; } = "";
    public string Args { get; set; } = "";
    public string AppUserModelId { get; set; } = "";
    public int Minutes { get; set; }
    public bool CloseOtherApps { get; set; }
    public DateTimeOffset LastUsedAt { get; set; }

    public AppEntry ToAppEntry() => new() { Name = Name, Path = Path, Args = Args, AppUserModelId = AppUserModelId };
}

/// <summary>Atomic local persistence for the most recently launched app presets.</summary>
public static class RecentPresets
{
    private const int MaximumEntries = 5;
    private static readonly JsonSerializerOptions JsonOptions = new() { PropertyNameCaseInsensitive = true, WriteIndented = true };
    public static string StoragePath => System.IO.Path.Combine(Config.UserDirectory, "recent-presets.json");

    /// <summary>Returns recent presets newest first. Corrupt data is preserved and reported as InvalidDataException.</summary>
    public static List<RecentPreset> Load()
    {
        if (!File.Exists(StoragePath)) return [];
        try
        {
            var entries = JsonSerializer.Deserialize<List<RecentPreset>>(File.ReadAllText(StoragePath), JsonOptions)
                ?? throw new JsonException("The preset list is empty.");
            if (entries.Any(e => !IsValid(e))) throw new JsonException("A preset contains invalid values.");
            return entries.OrderByDescending(e => e.LastUsedAt).Take(MaximumEntries).ToList();
        }
        catch (Exception ex) when (ex is JsonException or NotSupportedException)
        {
            throw new InvalidDataException($"Recent presets file '{StoragePath}' contains invalid data; the original file was preserved.", ex);
        }
    }

    /// <summary>Records a successful launch. Call only after launch succeeds.</summary>
    public static void Record(AppEntry app, int minutes, bool closeOtherApps)
    {
        ArgumentNullException.ThrowIfNull(app);
        if (minutes is < 1 or > 1440) throw new ArgumentOutOfRangeException(nameof(minutes));
        var entry = new RecentPreset { Name = app.Name, Path = app.Path, Args = app.Args, AppUserModelId = app.AppUserModelId,
            Minutes = minutes, CloseOtherApps = closeOtherApps, LastUsedAt = DateTimeOffset.UtcNow };
        if (!IsValid(entry)) throw new ArgumentException("App needs a name and exactly one valid launch target.", nameof(app));
        var entries = Load();
        entries.RemoveAll(e => SameLaunch(e, entry));
        entries.Insert(0, entry);
        Save(entries.Take(MaximumEntries).ToList());
    }

    /// <summary>Resolves presets to configured apps and uses their current display names.</summary>
    public static List<RecentPreset> ForApps(IEnumerable<AppEntry> apps)
    {
        ArgumentNullException.ThrowIfNull(apps);
        var configured = apps.Where(a => a is not null).ToList();
        var result = new List<RecentPreset>();
        foreach (var recent in Load())
        {
            var current = configured.FirstOrDefault(app => SameTarget(app, recent) &&
                string.Equals(app.Args ?? "", recent.Args, StringComparison.Ordinal));
            if (current is null) continue;
            result.Add(new RecentPreset { Name = current.Name, Path = current.Path, Args = current.Args,
                AppUserModelId = current.AppUserModelId, Minutes = recent.Minutes,
                CloseOtherApps = recent.CloseOtherApps, LastUsedAt = recent.LastUsedAt });
        }
        return result.OrderByDescending(e => e.LastUsedAt).Take(MaximumEntries).ToList();
    }

    private static void Save(List<RecentPreset> entries)
    {
        Directory.CreateDirectory(Config.UserDirectory);
        var temp = System.IO.Path.Combine(Config.UserDirectory, $"recent-presets.{Guid.NewGuid():N}.tmp");
        try
        {
            File.WriteAllText(temp, JsonSerializer.Serialize(entries, JsonOptions));
            File.Move(temp, StoragePath, overwrite: true);
        }
        finally { if (File.Exists(temp)) File.Delete(temp); }
    }

    private static bool IsValid(RecentPreset e)
    {
        if (e is null || string.IsNullOrWhiteSpace(e.Name) || e.Path is null || e.Args is null || e.AppUserModelId is null ||
            e.Minutes is < 1 or > 1440 || e.LastUsedAt == default) return false;
        var hasPath = !string.IsNullOrWhiteSpace(e.Path);
        var hasId = !string.IsNullOrWhiteSpace(e.AppUserModelId);
        return hasPath != hasId;
    }

    private static bool SameTarget(AppEntry app, RecentPreset recent) =>
        !string.IsNullOrWhiteSpace(recent.AppUserModelId)
            ? string.Equals(app.AppUserModelId, recent.AppUserModelId, StringComparison.OrdinalIgnoreCase)
            : string.Equals(app.Path, recent.Path, StringComparison.OrdinalIgnoreCase);

    private static bool SameLaunch(RecentPreset a, RecentPreset b) => SameTarget(a.ToAppEntry(), b) &&
        string.Equals(a.Args, b.Args, StringComparison.Ordinal) && a.Minutes == b.Minutes && a.CloseOtherApps == b.CloseOtherApps;
}
