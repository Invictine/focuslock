using System;
using System.Diagnostics;
using System.Runtime.InteropServices;

namespace VoidApp;

/// <summary>Activates an installed packaged app by its AppUserModelID.</summary>
internal static class PackagedAppLauncher
{
    internal static Process Activate(string appUserModelId, string? args)
    {
        if (string.IsNullOrWhiteSpace(appUserModelId))
            throw new ArgumentException("An AppUserModelID is required.", nameof(appUserModelId));

        object? instance = null;
        try
        {
            instance = new ApplicationActivationManager();
            var manager = (IApplicationActivationManager)instance;
            int result = manager.ActivateApplication(appUserModelId, args, ActivateOptions.None, out uint processId);
            Marshal.ThrowExceptionForHR(result);
            if (processId == 0 || processId > int.MaxValue)
                throw new InvalidOperationException("Windows activated the app without returning a usable process ID.");
            return Process.GetProcessById((int)processId);
        }
        finally
        {
            if (instance is not null && Marshal.IsComObject(instance))
                Marshal.FinalReleaseComObject(instance);
        }
    }

    private enum ActivateOptions : uint
    {
        None = 0,
    }

    [ComImport]
    [Guid("45BA127D-10A8-46EA-8AB7-56EA9078943C")]
    private class ApplicationActivationManager { }

    [ComImport]
    [Guid("2E941141-7F97-4756-BA1D-9DECDE894A3D")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IApplicationActivationManager
    {
        [PreserveSig]
        int ActivateApplication(
            [MarshalAs(UnmanagedType.LPWStr)] string appUserModelId,
            [MarshalAs(UnmanagedType.LPWStr)] string? arguments,
            ActivateOptions options,
            out uint processId);
    }
}
