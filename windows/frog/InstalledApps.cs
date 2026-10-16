using System.IO;
using System.Diagnostics;
using System.Linq;
using System.Text.Json;
using System.Text;

namespace VoidApp;

/// <summary>A packaged app available to the current Windows user.</summary>
public sealed record InstalledApp(string Name, string AppUserModelId, string? ExecutableName = null)
{
    public override string ToString() => Name;
}

/// <summary>Lists packaged apps that can be activated by AppUserModelID.</summary>
public static class InstalledApps
{
    private const int TimeoutMilliseconds = 10_000;
    private const string PowerShellRelativePath = @"WindowsPowerShell\v1.0\powershell.exe";
    private const string Query = "[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false); $ErrorActionPreference = 'Stop'; try { $packageExecutables = @{}; foreach ($package in @(Get-AppxPackage)) { try { $manifestPath = Join-Path $package.InstallLocation 'AppxManifest.xml'; if (Test-Path -LiteralPath $manifestPath) { [xml]$manifest = Get-Content -LiteralPath $manifestPath -Raw; foreach ($application in $manifest.SelectNodes(\"//*[local-name()='Applications']/*[local-name()='Application']\")) { $executableName = [IO.Path]::GetFileName([string]$application.Executable); if ($executableName) { $appUserModelId = [string]$package.PackageFamilyName + '!' + [string]$application.Id; $packageExecutables[$appUserModelId] = $executableName } } } } catch { } }; $apps = @(Get-StartApps | Where-Object { $_.AppID -like '*!*' } | ForEach-Object { $appUserModelId = [string]$_.AppID; [PSCustomObject]@{ Name = [string]$_.Name; AppUserModelId = $appUserModelId; ExecutableName = [string]$packageExecutables[$appUserModelId] } }); ConvertTo-Json -InputObject $apps -Compress -ErrorAction Stop } catch { [Console]::Error.WriteLine($_.Exception.Message); exit 1 }";

    /// <summary>Loads packaged Start apps for the signed-in user.</summary>
    public static async Task<List<InstalledApp>> LoadAsync()
    {
        var systemDirectory = Environment.GetFolderPath(Environment.SpecialFolder.System);
        var executable = Path.Combine(systemDirectory, PowerShellRelativePath);
        if (!File.Exists(executable))
            throw new InvalidOperationException($"Windows PowerShell was not found at '{executable}'. You can browse for an app executable and enter its AppUserModelID manually.");

        using var process = new Process
        {
            StartInfo = new ProcessStartInfo
            {
                FileName = executable,
                UseShellExecute = false,
                CreateNoWindow = true,
                WindowStyle = ProcessWindowStyle.Hidden,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                StandardOutputEncoding = Encoding.UTF8,
                StandardErrorEncoding = Encoding.UTF8,
                Arguments = "-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command \"" + Query + "\"",
            },
            EnableRaisingEvents = true,
        };

        try
        {
            if (!process.Start())
                throw new InvalidOperationException("Windows PowerShell could not be started.");
        }
        catch (Exception ex) when (ex is not InvalidOperationException)
        {
            throw new InvalidOperationException($"Could not start Windows PowerShell: {ex.Message} You can browse for an app executable and enter its AppUserModelID manually.", ex);
        }

        var stdoutTask = process.StandardOutput.ReadToEndAsync();
        var stderrTask = process.StandardError.ReadToEndAsync();
        using var timeout = new CancellationTokenSource(TimeoutMilliseconds);
        try
        {
            await process.WaitForExitAsync(timeout.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            try { process.Kill(entireProcessTree: true); } catch (InvalidOperationException) { }
            throw new TimeoutException("Windows app discovery took too long. You can browse for an app executable and enter its AppUserModelID manually.");
        }

        var output = await stdoutTask.ConfigureAwait(false);
        var error = await stderrTask.ConfigureAwait(false);
        if (process.ExitCode != 0)
        {
            var detail = string.IsNullOrWhiteSpace(error) ? "Windows PowerShell returned an error." : error.Trim();
            throw new InvalidOperationException($"Could not list packaged apps: {detail} You can browse for an app executable and enter its AppUserModelID manually.");
        }

        try
        {
            return (JsonSerializer.Deserialize<List<InstalledApp>>(output) ?? [])
                .Where(app => !string.IsNullOrWhiteSpace(app.AppUserModelId))
                .GroupBy(app => app.AppUserModelId, StringComparer.OrdinalIgnoreCase)
                .Select(group => group.First())
                .OrderBy(app => app.Name, StringComparer.OrdinalIgnoreCase)
                .ThenBy(app => app.AppUserModelId, StringComparer.OrdinalIgnoreCase)
                .ToList();
        }
        catch (JsonException ex)
        {
            throw new InvalidOperationException("Windows app discovery returned data that could not be read. You can browse for an app executable and enter its AppUserModelID manually.", ex);
        }
    }

    /// <summary>Returns packaged apps whose installed manifest names the exact approved executable.</summary>
    public static List<InstalledApp> FindByExecutableName(IEnumerable<InstalledApp> apps, string executableName)
    {
        if (string.IsNullOrWhiteSpace(executableName) ||
            !string.Equals(Path.GetFileName(executableName), executableName, StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException("An executable basename is required.", nameof(executableName));
        return apps.Where(app => !string.IsNullOrWhiteSpace(app.ExecutableName) &&
                string.Equals(Path.GetFileName(app.ExecutableName), executableName, StringComparison.OrdinalIgnoreCase))
            .GroupBy(app => app.AppUserModelId, StringComparer.OrdinalIgnoreCase)
            .Select(group => group.First())
            .OrderBy(app => app.Name, StringComparer.OrdinalIgnoreCase)
            .ThenBy(app => app.AppUserModelId, StringComparer.OrdinalIgnoreCase)
            .ToList();
    }
}
