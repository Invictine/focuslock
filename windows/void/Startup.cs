using Microsoft.Win32;
namespace VoidApp;
internal static class Startup
{
    internal static void Apply(bool enabled)
    {
        using var key = Registry.CurrentUser.CreateSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run");
        string command = $"\"{Environment.ProcessPath}\"";
        if (string.Equals(System.IO.Path.GetFileNameWithoutExtension(Environment.ProcessPath), "dotnet", StringComparison.OrdinalIgnoreCase))
            command += $" \"{System.IO.Path.Combine(AppContext.BaseDirectory, "Void.dll")}\"";
        if (enabled) key.SetValue("Void", command);
        else if (key.GetValue("Void") is string existing && existing == command) key.DeleteValue("Void", false);
    }
}
