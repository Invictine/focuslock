using System.IO;
using System.Text.Json;

namespace VoidApp;

public sealed class Config
{
    private static readonly JsonSerializerOptions JsonOptions = new() { PropertyNameCaseInsensitive = true, WriteIndented = true };

    public string Title { get; set; } = "FOCUS";
    public List<AppEntry> Apps { get; set; } = [];
    public bool Guard { get; set; }
    public bool StartWithWindows { get; set; }
    public int LockMinutes { get; set; } = 25;
    public bool CloseOtherApps { get; set; } = true;
    public bool CompletionSound { get; set; } = true;
    public int BreakMinutes { get; set; } = 5;
    public bool PrimaryMonitorOnly { get; set; } = true;

    public static string UserDirectory => Environment.GetEnvironmentVariable("VOID_USER_DIRECTORY") is { Length: > 0 } overridePath
        ? overridePath
        : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Void");

    public static string SettingsPath => Path.Combine(UserDirectory, "config.json");

    /// <summary>Loads user settings, migrating the adjacent legacy config on first run.</summary>
    public static Config Load()
    {
        Directory.CreateDirectory(UserDirectory);
        if (File.Exists(SettingsPath))
            return NormalizeAndValidate(Deserialize(File.ReadAllText(SettingsPath), SettingsPath), resolveRelativePaths: true);

        var legacyPath = Path.Combine(AppContext.BaseDirectory, "config.json");
        var config = File.Exists(legacyPath)
            ? Deserialize(File.ReadAllText(legacyPath), legacyPath)
            : new Config();

        config = NormalizeAndValidate(config, resolveRelativePaths: true, allowLegacyZeroLock: File.Exists(legacyPath));
        config.Save();
        return config;
    }

    /// <summary>Reads saved mappings for an integrated host without creating or migrating user settings.</summary>
    public static Config LoadExistingReadOnly()
    {
        if (!File.Exists(SettingsPath)) return new Config();
        return NormalizeAndValidate(Deserialize(File.ReadAllText(SettingsPath), SettingsPath), resolveRelativePaths: true);
    }

    /// <summary>Atomically persists these settings in the current user's profile.</summary>
    public void Save()
    {
        NormalizeAndValidate(this, resolveRelativePaths: false);
        Directory.CreateDirectory(UserDirectory);
        var tempPath = Path.Combine(UserDirectory, $"config.{Guid.NewGuid():N}.tmp");
        try
        {
            File.WriteAllText(tempPath, JsonSerializer.Serialize(this, JsonOptions));
            File.Move(tempPath, SettingsPath, overwrite: true);
        }
        finally
        {
            if (File.Exists(tempPath)) File.Delete(tempPath);
        }
    }

    private static Config Deserialize(string json, string source)
    {
        try
        {
            return JsonSerializer.Deserialize<Config>(json, JsonOptions)
                ?? throw new InvalidDataException($"Settings file '{source}' is empty.");
        }
        catch (JsonException ex)
        {
            throw new InvalidDataException($"Settings file '{source}' contains invalid JSON.", ex);
        }
    }

    private static Config NormalizeAndValidate(Config config, bool resolveRelativePaths, bool allowLegacyZeroLock = false)
    {
        if (config.Title is null || string.IsNullOrWhiteSpace(config.Title))
            throw new InvalidDataException("title must not be empty.");
        if (allowLegacyZeroLock && config.LockMinutes == 0) config.LockMinutes = 1;
        if (config.LockMinutes is < 1 or > 1440)
            throw new InvalidDataException("lockMinutes must be between 1 and 1440.");
        if (config.BreakMinutes is < 1 or > 60)
            throw new InvalidDataException("breakMinutes must be between 1 and 60.");
        if (config.Apps is null || config.Apps.Any(a => a is null || string.IsNullOrWhiteSpace(a.Name) ||
            (string.IsNullOrWhiteSpace(a.Path) == string.IsNullOrWhiteSpace(a.AppUserModelId))))
            throw new InvalidDataException("Each app needs a name and exactly one of path or appUserModelId.");

        if (resolveRelativePaths)
        {
            foreach (var app in config.Apps)
            {
                if (string.IsNullOrWhiteSpace(app.Path)) continue;
                app.Path = Environment.ExpandEnvironmentVariables(app.Path);
                if (!Path.IsPathRooted(app.Path))
                    app.Path = Path.GetFullPath(app.Path, AppContext.BaseDirectory);
            }
        }
        return config;
    }
}

public sealed class AppEntry
{
    public string Name { get; set; } = "";
    public string Path { get; set; } = "";
    public string Args { get; set; } = "";
    public string AppUserModelId { get; set; } = "";
    public override string ToString() => Name;
}
