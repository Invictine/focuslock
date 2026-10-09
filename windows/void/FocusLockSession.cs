using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace VoidApp;

/// <summary>Version 1 line-delimited JSON protocol shared with the FocusLock host.</summary>
public sealed class FocusLockSessionState
{
    public int ProtocolVersion { get; set; }
    public string Title { get; set; } = "";
    public string? ProjectName { get; set; }
    public string Phase { get; set; } = "";
    public int GraceRemainingSeconds { get; set; }
    public string CycleDate { get; set; } = "";
    public long TrackedSeconds { get; set; }
    public long RequiredSeconds { get; set; }
    public bool TickedOff { get; set; }
    public bool Running { get; set; }
    public long RemainingSeconds { get; set; }
    public int BlockMinutes { get; set; }
    public List<FocusLockTool> Tools { get; set; } = [];
    public List<string> Domains { get; set; } = [];

    public static FocusLockSessionState Parse(string line)
    {
        if (string.IsNullOrWhiteSpace(line)) throw new InvalidDataException("FocusLock sent an empty state.");
        FocusLockSessionState state;
        try
        {
            using var document = JsonDocument.Parse(line);
            if (document.RootElement.ValueKind != JsonValueKind.Object)
                throw new InvalidDataException("FocusLock state must be a JSON object.");
            string[] required = ["protocolVersion", "title", "phase", "cycleDate", "trackedSeconds", "requiredSeconds", "tickedOff", "running", "remainingSeconds", "blockMinutes", "tools", "domains"];
            if (required.Any(name => !document.RootElement.EnumerateObject().Any(property => string.Equals(property.Name, name, StringComparison.OrdinalIgnoreCase))))
                throw new InvalidDataException("FocusLock state is missing required protocol fields.");
            state = JsonSerializer.Deserialize<FocusLockSessionState>(line, JsonOptions)
                ?? throw new InvalidDataException("FocusLock sent an empty state.");
        }
        catch (JsonException ex) { throw new InvalidDataException("FocusLock sent malformed state JSON.", ex); }
        state.Validate();
        return state;
    }

    public void Validate()
    {
        if (ProtocolVersion != 1) throw new InvalidDataException($"Unsupported FocusLock protocol version: {ProtocolVersion}.");
        if (Title is null || Phase is null || CycleDate is null)
            throw new InvalidDataException("FocusLock state has a missing display field.");
        if (!BoundedText(Title, 1, 200) || (ProjectName is not null && !BoundedText(ProjectName, 0, 160)) ||
            !BoundedText(Phase, 1, 32) || !BoundedText(CycleDate, 1, 32) ||
            Phase is not ("not_armed" or "grace" or "pick_frog" or "working" or "complete") ||
            GraceRemainingSeconds is < 0 or > 300)
            throw new InvalidDataException("FocusLock state has invalid display text or phase.");
        ProjectName = string.IsNullOrWhiteSpace(ProjectName) ? null : ProjectName;
        if (TrackedSeconds is < 0 or > 604800 || RequiredSeconds is < 1 or > 86400 ||
            RemainingSeconds is < 0 or > 86400 || BlockMinutes is < 1 or > 480)
            throw new InvalidDataException("FocusLock state contains an invalid duration.");
        if (Tools is null || Domains is null || Tools.Count > 64 || Domains.Count > 512 || Tools.Any(tool => tool is null))
            throw new InvalidDataException("FocusLock state has an invalid tool or domain list.");
        if (Tools.Select(tool => tool.Id).Distinct(StringComparer.OrdinalIgnoreCase).Count() != Tools.Count)
            throw new InvalidDataException("FocusLock state contains duplicate tool ids.");
        foreach (var tool in Tools) tool.Validate();
        Domains = Domains.Select(NormalizeHttpsDomain).Distinct(StringComparer.OrdinalIgnoreCase).ToList();
    }

    public static string NormalizeHttpsDomain(string value)
    {
        if (string.IsNullOrWhiteSpace(value))
            throw new InvalidDataException("FocusLock domains must be normalized HTTPS origins or host names.");
        var candidate = value.Contains("://", StringComparison.Ordinal) ? value : "https://" + value;
        if (!Uri.TryCreate(candidate, UriKind.Absolute, out var uri) ||
            uri.Scheme != Uri.UriSchemeHttps || string.IsNullOrWhiteSpace(uri.Host) ||
            !string.IsNullOrEmpty(uri.UserInfo) || (uri.AbsolutePath != "/") || !string.IsNullOrEmpty(uri.Query) || !string.IsNullOrEmpty(uri.Fragment))
            throw new InvalidDataException("FocusLock domains must be normalized HTTPS origins or host names.");
        if (uri.Port != 443)
            throw new InvalidDataException("FocusLock domains must use the standard HTTPS port.");
        var host = uri.IdnHost.ToLowerInvariant().TrimEnd('.');
        if (host.Length is < 1 or > 253 || host.Split('.').Any(label =>
            label.Length is < 1 or > 63 || label.StartsWith('-') || label.EndsWith('-') ||
            label.Any(character => !(char.IsAsciiLetterOrDigit(character) || character == '-'))))
            throw new InvalidDataException("FocusLock domain is not a normalized host name.");
        return host;
    }

    internal static bool BoundedText(string? value, int minimum, int maximum) =>
        value is not null && value.Trim().Length >= minimum && value.Trim().Length <= maximum && !value.Contains('\0');

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNameCaseInsensitive = true,
        NumberHandling = JsonNumberHandling.Strict,
    };
}

public sealed class FocusLockTool
{
    public string Id { get; set; } = "";
    public string Label { get; set; } = "";
    public string? ExecutablePath { get; set; }
    public string? AppUserModelId { get; set; }

    public void Validate()
    {
        if (!FocusLockSessionState.BoundedText(Id, 1, 260) || !FocusLockSessionState.BoundedText(Label, 1, 120) ||
            Id != Path.GetFileName(Id) || Id.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0 ||
            Id.Contains('/') || Id.Contains('\\'))
            throw new InvalidDataException("FocusLock tools need a label and a basename id.");
        if (!string.IsNullOrWhiteSpace(ExecutablePath) &&
            !string.Equals(Path.GetFileName(ExecutablePath), Id, StringComparison.OrdinalIgnoreCase))
            throw new InvalidDataException($"FocusLock tool path does not match approved id '{Id}'.");
        if (!string.IsNullOrWhiteSpace(ExecutablePath) && !Path.IsPathRooted(ExecutablePath))
            throw new InvalidDataException($"FocusLock tool path for '{Id}' must be absolute.");
        if (ExecutablePath is not null && !FocusLockSessionState.BoundedText(ExecutablePath, 1, 2048) ||
            AppUserModelId is not null && !FocusLockSessionState.BoundedText(AppUserModelId, 1, 256))
            throw new InvalidDataException($"FocusLock tool '{Id}' has an oversized launch identifier.");
        if (string.IsNullOrWhiteSpace(ExecutablePath) && string.IsNullOrWhiteSpace(AppUserModelId))
            return; // Void config may supply the approved launch target.
    }
}

public sealed record FocusLockAction
{
    public int ProtocolVersion { get; init; } = 1;
    public required string Action { get; init; }
    public string? ToolId { get; init; }
    public string? Domain { get; init; }
    public int? Minutes { get; init; }
    public string? Message { get; init; }
    public string? Title { get; init; }
    public List<string>? AppIds { get; init; }
    public List<string>? Domains { get; init; }
}

public sealed class FocusLockProtocol
{
    private readonly object outputLock = new();
    private readonly CancellationTokenSource stop = new();
    private readonly StreamReader input;
    private readonly StreamWriter output;
    private long lastStateTimestamp;
    private int endedOnce;

    public event Action<FocusLockSessionState>? StateReceived;
    public event Action<string>? Ended;

    public FocusLockProtocol()
    {
        var utf8 = new System.Text.UTF8Encoding(encoderShouldEmitUTF8Identifier: false, throwOnInvalidBytes: true);
        input = new StreamReader(Console.OpenStandardInput(), utf8, detectEncodingFromByteOrderMarks: false, bufferSize: 1024, leaveOpen: true);
        output = new StreamWriter(Console.OpenStandardOutput(), utf8, bufferSize: 1024, leaveOpen: true)
        {
            AutoFlush = true,
            NewLine = "\n",
        };
    }

    public void Start()
    {
        lastStateTimestamp = Stopwatch.GetTimestamp();
        _ = Task.Run(ReadLoopAsync);
        _ = Task.Run(WatchHeartbeatAsync);
    }

    public void RequestStop() => stop.Cancel();

    public void WriteAction(FocusLockAction action)
    {
        lock (outputLock)
        {
            try
            {
                output.WriteLine(SerializeAction(action));
                output.Flush();
            }
            catch (IOException) { RequestStop(); }
        }
    }

    public static string SerializeAction(FocusLockAction action) => JsonSerializer.Serialize(action, OutputJsonOptions);

    private async Task ReadLoopAsync()
    {
        string reason = "FocusLock closed the connection.";
        try
        {
            while (!stop.IsCancellationRequested)
            {
                var line = await input.ReadLineAsync(stop.Token).ConfigureAwait(false);
                if (line is null) { reason = "FocusLock closed the connection."; break; }
                if (System.Text.Encoding.UTF8.GetByteCount(line) > 64 * 1024)
                    throw new InvalidDataException("FocusLock state exceeded the protocol frame limit.");
                var state = FocusLockSessionState.Parse(line);
                lastStateTimestamp = Stopwatch.GetTimestamp();
                StateReceived?.Invoke(state);
            }
        }
        catch (OperationCanceledException) when (stop.IsCancellationRequested) { }
        catch (InvalidDataException ex) { reason = ex.Message; }
        catch (Exception ex) when (ex is IOException or ObjectDisposedException)
        { reason = ex.Message; }
        finally
        {
            End(reason);
        }
    }

    private async Task WatchHeartbeatAsync()
    {
        try
        {
            while (!stop.IsCancellationRequested)
            {
                await Task.Delay(TimeSpan.FromSeconds(1), stop.Token).ConfigureAwait(false);
                if (Stopwatch.GetElapsedTime(lastStateTimestamp) > TimeSpan.FromSeconds(10))
                {
                    End("FocusLock connection timed out.");
                    return;
                }
            }
        }
        catch (OperationCanceledException) when (stop.IsCancellationRequested) { }
    }

    private void End(string reason)
    {
        if (Interlocked.Exchange(ref endedOnce, 1) != 0) return;
        stop.Cancel();
        Ended?.Invoke(reason);
    }

    private static readonly JsonSerializerOptions OutputJsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };
}
