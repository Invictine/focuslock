using VoidApp;
using System.Diagnostics;
using System.Runtime.InteropServices;

static void Check(bool condition, string message)
{
    if (!condition) throw new Exception(message);
}



static void ExpectInvalid(Action action, string message)
{
    try { action(); }
    catch (InvalidDataException) { return; }
    throw new Exception(message);
}

var root = Path.Combine(Path.GetTempPath(), "Void-storage-tests-" + Guid.NewGuid().ToString("N"));
var userDirectory = Path.Combine(root, "local", "Void");
Directory.CreateDirectory(userDirectory);
Environment.SetEnvironmentVariable("VOID_USER_DIRECTORY", userDirectory);
try
{
    var migrated = Config.Load();
    Check(migrated.LockMinutes == 1, "legacy zero lock duration should migrate to one");
    Check(migrated.CloseOtherApps && migrated.CompletionSound && migrated.BreakMinutes == 5 && migrated.PrimaryMonitorOnly, "new setting defaults should survive legacy migration");
    Check(migrated.Apps[0].Path == Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "tools", "app.exe")), "relative legacy executable should resolve from app directory");
    Check(migrated.Apps[2].Path == Environment.ExpandEnvironmentVariables(@"%LOCALAPPDATA%\VoidTest\app.exe"), "environment variables in executable paths should expand without becoming relative paths");
    Check(File.Exists(Config.SettingsPath), "first load should persist migrated user settings");

    migrated.Title = "Saved";
    migrated.BreakMinutes = 12;
    migrated.Save();
    Check(Config.Load().Title == "Saved" && Config.Load().BreakMinutes == 12, "user settings should persist across loads");
    migrated.PrimaryMonitorOnly = false;
    migrated.Save();
    Check(!Config.Load().PrimaryMonitorOnly, "primary monitor setting should persist false");
    migrated.PrimaryMonitorOnly = true;
    migrated.Save();
    Check(Config.Load().PrimaryMonitorOnly, "primary monitor setting should persist true");

    var presetApps = Enumerable.Range(0, 7).Select(i => new AppEntry { Name = $"Preset {i}", Path = $"C:\\Apps\\app{i}.exe", Args = $"--profile={i}" }).ToList();
    for (var i = 0; i < 7; i++) RecentPresets.Record(presetApps[i], 20 + i, i % 2 == 0);
    Check(RecentPresets.Load().Count == 5 && RecentPresets.Load()[0].Name == "Preset 6" && RecentPresets.Load()[^1].Name == "Preset 2",
        "recent presets should keep the five newest entries in newest-first order");
    RecentPresets.Record(presetApps[4], 24, true);
    Check(RecentPresets.Load().Count == 5 && RecentPresets.Load()[0].Name == "Preset 4", "recording an identical preset should deduplicate and move it to newest");
    var renamed = presetApps.Select(a => new AppEntry { Name = "Renamed " + a.Name, Path = a.Path, Args = a.Args, AppUserModelId = a.AppUserModelId }).ToList();
    renamed.RemoveAll(a => a.Name.EndsWith("Preset 3", StringComparison.Ordinal));
    renamed.Single(a => a.Name == "Renamed Preset 2").Args = "--changed";
    var resolved = RecentPresets.ForApps(renamed);
    Check(resolved.All(p => p.Name.StartsWith("Renamed ", StringComparison.Ordinal)) && resolved.All(p => p.Name != "Renamed Preset 3" && p.Name != "Renamed Preset 2"),
        "recent presets should display current names and remove deleted apps or changed arguments");
    var beforeCorrupt = RecentPresets.StoragePath;
    File.WriteAllText(beforeCorrupt, "not-json");
    ExpectInvalid(() => RecentPresets.Load(), "corrupt recent presets should raise a useful error");
    Check(File.ReadAllText(beforeCorrupt) == "not-json", "loading corrupt recent presets must preserve the original file");
    File.Delete(beforeCorrupt);

    ExpectInvalid(() => { var c = new Config { LockMinutes = 1441 }; c.Save(); }, "out-of-range lock duration should be rejected");
    ExpectInvalid(() => { var c = new Config { BreakMinutes = 0 }; c.Save(); }, "out-of-range break duration should be rejected");
    ExpectInvalid(() => { var c = new Config { Title = "  " }; c.Save(); }, "empty title should be rejected");
    ExpectInvalid(() => { var c = new Config { Apps = [new AppEntry { Name = "Bad", Path = "a.exe", AppUserModelId = "id" }] }; c.Save(); }, "app with two launch targets should be rejected");

    var historyPath = SessionHistory.HistoryPath;
    var start = DateTimeOffset.UtcNow;
    for (var i = 0; i < 505; i++)
        SessionHistory.Record(new FocusSession { AppName = $"App {i}", StartedAt = start.AddMinutes(i), PlannedMinutes = 25, FocusedSeconds = i, Completed = i % 2 == 0 });
    var history = SessionHistory.Load();
    Check(history.Count == 500, "history should retain at most 500 sessions");
    Check(history[0].AppName == "App 504" && history[^1].AppName == "App 5", "history should be newest first and discard oldest overflow");

    File.WriteAllText(historyPath, "not-json");
    ExpectInvalid(() => SessionHistory.Load(), "corrupt history should raise a useful error");
    Check(File.ReadAllText(historyPath) == "not-json", "loading corrupt history must preserve the original file");

    var clock = new ManualTimeProvider();
    var focusLock = new FocusLock(clock);
    focusLock.Start(1);
    Check(focusLock.IsLocked && focusLock.Remaining == TimeSpan.FromMinutes(1), "lock should begin with its planned duration");
    clock.Advance(TimeSpan.FromSeconds(17));
    var remainingAfterAdvance = focusLock.Remaining;
    focusLock.Start(10);
    Check(focusLock.Remaining == remainingAfterAdvance, "repeated Start must not reset an active lock");
    clock.Advance(TimeSpan.FromSeconds(43));
    Check(!focusLock.IsLocked && focusLock.Remaining == TimeSpan.Zero, "lock should expire according to monotonic elapsed time");

    var zeroLock = new FocusLock(clock);
    zeroLock.Start(0);
    Check(zeroLock.Started && !zeroLock.Enabled && !zeroLock.IsLocked, "zero-minute lock should be started but immediately expired");
    try { new FocusLock(clock).Start(-1); throw new Exception("negative duration should be rejected"); }
    catch (ArgumentOutOfRangeException) { }

    const string validFrogState = """{"protocolVersion":1,"title":"Study the chapter","projectName":"Chemistry","phase":"working","graceRemainingSeconds":0,"cycleDate":"2026-10-05","trackedSeconds":120,"requiredSeconds":600,"tickedOff":false,"running":true,"remainingSeconds":900,"blockMinutes":25,"tools":[{"id":"chrome.exe","label":"Chrome"}],"domains":["YouTube.com","https://youtube.com/"]}""";
    var frogState = FocusLockSessionState.Parse(validFrogState);
    Check(frogState.Tools.Single().Id == "chrome.exe" && frogState.Domains.Count == 1 && frogState.Domains[0] == "youtube.com",
        "protocol parsing should validate tools and normalize duplicate HTTPS domains");
    var optionalProject = FocusLockSessionState.Parse(validFrogState.Replace("\"projectName\":\"Chemistry\",", "\"projectName\":null,"));
    Check(optionalProject.ProjectName is null, "a missing project name should remain optional");
    var missingProject = FocusLockSessionState.Parse(validFrogState.Replace("\"projectName\":\"Chemistry\",", ""));
    Check(missingProject.ProjectName is null, "a missing project name field should remain optional");
    const string graceFrogState = """{"protocolVersion":1,"title":"Get ready","phase":"grace","graceRemainingSeconds":127,"cycleDate":"2026-10-05","trackedSeconds":0,"requiredSeconds":600,"tickedOff":false,"running":false,"remainingSeconds":900,"blockMinutes":25,"tools":[],"domains":[]}""";
    var graceState = FocusLockSessionState.Parse(graceFrogState);
    Check(graceState.Phase == "grace" && graceState.GraceRemainingSeconds == 127 && !graceState.Running && graceState.Tools.Count == 0 && graceState.Domains.Count == 0,
        "grace snapshots should carry their countdown without timer work or allowlisted tools");
    const string pickingFrogState = """{"protocolVersion":1,"title":"Pick one task","phase":"pick_frog","graceRemainingSeconds":0,"cycleDate":"2026-10-05","trackedSeconds":0,"requiredSeconds":600,"tickedOff":false,"running":false,"remainingSeconds":900,"blockMinutes":25,"tools":[],"domains":[]}""";
    var pickingState = FocusLockSessionState.Parse(pickingFrogState);
    Check(pickingState.Phase == "pick_frog" && !pickingState.Running && pickingState.Tools.Count == 0 && pickingState.Domains.Count == 0,
        "task selection snapshots should not expose timer work or allowlisted tools before a task is saved");
    ExpectInvalid(() => FocusLockSessionState.Parse(graceFrogState.Replace("\"graceRemainingSeconds\":127", "\"graceRemainingSeconds\":301")),
        "grace countdowns longer than five minutes should be rejected");
    var selectedFrog = FocusLockProtocol.SerializeAction(new FocusLockAction {
        Action = "select_frog", Title = "Study chemistry", AppIds = ["Code.exe"], Domains = ["docs.google.com"]
    });
    Check(selectedFrog.Contains("\"action\":\"select_frog\"", StringComparison.Ordinal) &&
          selectedFrog.Contains("\"appIds\":[\"Code.exe\"]", StringComparison.Ordinal) &&
          selectedFrog.Contains("\"domains\":[\"docs.google.com\"]", StringComparison.Ordinal),
        "task selection actions should send only the selected title and approved apps and sites");
    var readyEnvelope = FocusLockProtocol.SerializeAction(new FocusLockAction { Action = "ready" });
    Check(readyEnvelope.Contains("\"protocolVersion\":1", StringComparison.Ordinal) && readyEnvelope.Contains("\"action\":\"ready\"", StringComparison.Ordinal) && !readyEnvelope.Contains("toolId", StringComparison.Ordinal),
        "protocol events should use the versioned camel-case schema without unsupported fields");
    var legacyInstalledApp = new InstalledApp("Legacy entry", "Legacy.Package!App");
    Check(legacyInstalledApp.ExecutableName is null, "existing two-argument installed-app construction should remain compatible");
    var executableMatches = InstalledApps.FindByExecutableName([
        new InstalledApp("ChatGPT", "OpenAI.Codex_abc!App", "ChatGPT.exe"),
        new InstalledApp("Second ChatGPT", "Other.Package!App", "chatgpt.EXE"),
        new InstalledApp("Codex CLI", "CLI.Package!App", "codex.exe"),
    ], "ChatGPT.exe");
    Check(executableMatches.Count == 2 && executableMatches.All(app => string.Equals(app.ExecutableName, "ChatGPT.exe", StringComparison.OrdinalIgnoreCase)),
        "packaged app matching should use an exact executable basename and retain ambiguity for explicit selection");
    try { InstalledApps.FindByExecutableName([], @"C:\Apps\ChatGPT.exe"); throw new Exception("executable resolver should reject paths instead of basenames"); }
    catch (ArgumentException) { }
    ExpectInvalid(() => FocusLockSessionState.Parse(validFrogState.Replace("\"protocolVersion\":1", "\"protocolVersion\":2")), "unsupported protocol versions should be rejected");
    ExpectInvalid(() => FocusLockSessionState.Parse(validFrogState.Replace("YouTube.com", "http://example.com")), "non-HTTPS domains should be rejected");
    ExpectInvalid(() => FocusLockSessionState.Parse(validFrogState.Replace("\"label\":\"Chrome\"", "\"label\":\"Chrome\",\"executablePath\":\"C:\\\\Apps\\\\other.exe\"")), "tool paths that do not match their approved basename should be rejected");
    ExpectInvalid(() => FocusLockSessionState.Parse("""{"protocolVersion":1,"blockMinutes":25}"""), "incomplete protocol states should be rejected");

    Check(Marshal.SizeOf<DisplayPath>() == 72, "DISPLAYCONFIG_PATH_INFO should be 72 bytes");
    Check(Marshal.SizeOf<DisplayMode>() == 64, "DISPLAYCONFIG_MODE_INFO should be 64 bytes");
    Check(Marshal.SizeOf<DisplaySource>() == 20, "DISPLAYCONFIG_PATH_SOURCE_INFO should be 20 bytes");
    Check(Marshal.SizeOf<DisplayTarget>() == 48, "DISPLAYCONFIG_PATH_TARGET_INFO should be 48 bytes");

    var otherSource = new DisplayMode { Type = 1, Id = 10, Source = new DisplaySourceMode { Width = 1600, Height = 900, X = 1600, Y = 0 } };
    var primarySource = new DisplayMode { Type = 1, Id = 11, Source = new DisplaySourceMode { Width = 1920, Height = 1080, X = 0, Y = 0 } };
    var primaryTarget = new DisplayMode { Type = 2, Id = 11, Data0 = 0x0102030405060708, Data1 = 0x1112131415161718,
        Data2 = 0x2122232425262728, Data3 = 0x3132333435363738, Data4 = 0x4142434445464748, Data5 = 0x5152535455565758 };
    var otherTarget = new DisplayMode { Type = 2, Id = 10, Data0 = 0xA1A2A3A4A5A6A7A8 };
    var otherPath = new DisplayPath { Source = new DisplaySource { Id = 10, ModeIndex = 0 }, Target = new DisplayTarget { Id = 10, ModeIndex = 3 } };
    var primaryPath = new DisplayPath { Source = new DisplaySource { Id = 11, ModeIndex = 1 }, Target = new DisplayTarget { Id = 11, ModeIndex = 2 } };
    var originalSnapshot = new DisplaySnapshot([otherPath, primaryPath], [otherSource, primarySource, primaryTarget, otherTarget]);
    var selected = DisplaySession.SelectPrimary(originalSnapshot);
    Check(selected.Paths.Length == 1 && selected.Modes.Length == 2, "primary selection should retain only its path and modes");
    Check(selected.Paths[0].Source.Id == 11 && selected.Paths[0].Target.Id == 11, "primary selection should retain the path with source origin");
    Check(selected.Paths[0].Source.ModeIndex == 0 && selected.Paths[0].Target.ModeIndex == 1, "selected path mode indexes should be remapped");
    Check(selected.Modes[0].Source.X == 0 && selected.Modes[1].Data5 == primaryTarget.Data5, "primary source and target modes should be retained in order");
    Check(originalSnapshot.Paths.Length == 2 && originalSnapshot.Paths[1].Source.ModeIndex == 1 && originalSnapshot.Paths[1].Target.ModeIndex == 2,
        "primary selection should not mutate the original snapshot");

    var snapshotPath = Path.Combine(root, "display", "snapshot.bin");
    DisplaySession.WriteSnapshot(snapshotPath, originalSnapshot);
    var restoredSnapshot = DisplaySession.ReadSnapshot(snapshotPath);
    Check(restoredSnapshot.Paths.SequenceEqual(originalSnapshot.Paths), "snapshot paths should round-trip exactly");
    Check(restoredSnapshot.Modes.SequenceEqual(originalSnapshot.Modes), "snapshot modes should round-trip exactly, including all union sentinel values");
    Check(StructureBytes(restoredSnapshot.Modes[2]).SequenceEqual(StructureBytes(primaryTarget)), "target union must preserve all 48 sentinel bytes");
    var truncatedPath = Path.Combine(root, "display", "truncated.bin");
    var snapshotBytes = File.ReadAllBytes(snapshotPath);
    File.WriteAllBytes(truncatedPath, snapshotBytes[..^1]);
    ExpectInvalid(() => DisplaySession.ReadSnapshot(truncatedPath), "truncated recovery snapshot should be rejected");
    var corruptPath = Path.Combine(root, "display", "corrupt.bin");
    File.WriteAllBytes(corruptPath, [0, 0, 0, 0, 1, 0, 0, 0]);
    ExpectInvalid(() => DisplaySession.ReadSnapshot(corruptPath), "corrupt recovery snapshot should be rejected");
    Check(DisplaySession.Restore(Path.Combine(root, "missing-snapshot.bin")), "restoring a nonexistent snapshot should succeed without requiring a native display call");

    if (OperatingSystem.IsWindows())
    {
        var active = DisplaySession.Capture();
        int primaryValidation = DisplaySession.ValidateSnapshot(DisplaySession.SelectPrimary(active));
        Console.WriteLine($"Windows display read-only check: active paths={active.Paths.Length}, primary SDC_VALIDATE result={primaryValidation}.");
        Check(primaryValidation == 0 || primaryValidation == 5, "Windows rejected SDC_VALIDATE for primary-only layout");
    }
    Console.WriteLine("Storage behavior checks passed.");
}

finally
{
    Directory.Delete(root, recursive: true);
}

static byte[] StructureBytes<T>(T value) where T : struct
{
    int size = Marshal.SizeOf<T>();
    nint memory = Marshal.AllocHGlobal(size);
    try
    {
        Marshal.StructureToPtr(value, memory, false);
        var bytes = new byte[size];
        Marshal.Copy(memory, bytes, 0, size);
        return bytes;
    }
    finally { Marshal.FreeHGlobal(memory); }
}

sealed class ManualTimeProvider : TimeProvider
{
    private long timestamp;
    public override long TimestampFrequency => 1000;
    public override long GetTimestamp() => timestamp;
    public void Advance(TimeSpan amount) => timestamp += (long)amount.TotalMilliseconds;
}
