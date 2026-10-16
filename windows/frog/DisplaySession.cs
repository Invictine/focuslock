using System.ComponentModel;
using System.IO;
using System.Runtime.InteropServices;

namespace VoidApp;

/// <summary>Temporary primary-display topology, with a recovery snapshot shared with the watchdog.</summary>
internal static class DisplaySession
{
    internal static string? RecoveryFile { get; private set; }
    internal static string? LastError { get; private set; }
    private const uint QueryActive = 2, UseSupplied = 0x20, Validate = 0x40, Apply = 0x80;
    private const uint SaveToDatabase = 0x200, AllowChanges = 0x400, UseDatabaseCurrent = 0xF;
    private const int InsufficientBuffer = 122;

    internal static void InitializeRecovery(string file) => RecoveryFile = file;

    internal static void UsePrimaryOnly()
    {
        LastError = null;
        if (RecoveryFile is null) throw new InvalidOperationException("Display recovery has not been initialized.");
        if (File.Exists(RecoveryFile)) return;
        var original = Capture();
        if (original.Paths.Length <= 1) return;
        var primary = SelectPrimary(original);
        int valResult = Configure(primary, Validate | AllowChanges);
        if (valResult != 0 && valResult != 5) ThrowOnError(valResult, "Windows cannot use the primary monitor alone");
        WriteSnapshot(RecoveryFile, original);
        // Persist the temporary single-display mode to the database so Windows/DWM does not revert after countdown.
        int result = Configure(primary, Apply | SaveToDatabase | AllowChanges);
        if (result != 0)
        {
            Restore();
            ThrowOnError(result, "Windows could not switch to the primary monitor");
        }
    }

    internal static bool Restore() => Restore(RecoveryFile);

    internal static bool Restore(string? file)
    {
        if (string.IsNullOrWhiteSpace(file) || !File.Exists(file)) return true;
        // Both the UI and its helper can restore. Serialize the claim and delete only on success.
        using var mutex = new Mutex(false, "Local\\Void.DisplayRecovery." + Path.GetFileNameWithoutExtension(file));
        bool acquired = false;
        try
        {
            try { acquired = mutex.WaitOne(TimeSpan.FromSeconds(10)); }
            catch (AbandonedMutexException) { acquired = true; }
            if (!acquired) return false;
            if (!File.Exists(file)) return true;
            var original = ReadSnapshot(file);
            int result = Configure(original, Apply | SaveToDatabase | AllowChanges);
            if (result != 0)
            {
                // A monitor may have been unplugged. Let Windows find a usable version of the old layout.
                result = Configure(original, Apply | AllowChanges);
                if (result != 0) result = SetDisplayConfig(0, null, 0, null, Apply | SaveToDatabase | UseDatabaseCurrent);
            }
            ThrowOnError(result, "Windows could not restore the display layout");
            File.Delete(file);
            LastError = null;
            return true;
        }
        catch (Exception ex)
        {
            LastError = ex.Message;
            // Retain the snapshot so the helper can retry after the main app exits.
            return false;
        }
        finally { if (acquired) mutex.ReleaseMutex(); }
    }

    internal static DisplaySnapshot Capture()
    {
        for (int attempt = 0; attempt < 5; attempt++)
        {
            ThrowOnError(GetDisplayConfigBufferSizes(QueryActive, out uint pathCount, out uint modeCount), "Could not read connected displays");
            if (pathCount == 0 || pathCount > 128 || modeCount > 512) throw new InvalidOperationException("Windows returned an unexpected display configuration.");
            var paths = new DisplayPath[pathCount];
            var modes = new DisplayMode[modeCount];
            int result = QueryDisplayConfig(QueryActive, ref pathCount, paths, ref modeCount, modes, 0);
            if (result == InsufficientBuffer) continue; // Displays can change between sizing and query.
            ThrowOnError(result, "Could not read the current display layout");
            return new(paths.Take((int)pathCount).ToArray(), modes.Take((int)modeCount).ToArray());
        }
        throw new InvalidOperationException("The display layout is changing. Try again once Windows finishes switching displays.");
    }

    internal static DisplaySnapshot SelectPrimary(DisplaySnapshot snapshot)
    {
        // Windows places the primary desktop source at (0,0). In clone mode the first
        // active target in Windows' path priority order is retained for that source.
        foreach (var path in snapshot.Paths)
        {
            uint sourceIndex = path.Source.ModeIndex;
            if (sourceIndex >= snapshot.Modes.Length) continue;
            var source = snapshot.Modes[sourceIndex];
            if (source.Type != 1 || source.Source.X != 0 || source.Source.Y != 0) continue;
            var chosen = path;
            var modes = new List<DisplayMode> { source };
            chosen.Source.ModeIndex = 0;
            if (chosen.Target.ModeIndex < snapshot.Modes.Length)
            {
                modes.Add(snapshot.Modes[chosen.Target.ModeIndex]);
                chosen.Target.ModeIndex = 1;
            }
            else chosen.Target.ModeIndex = uint.MaxValue;
            return new([chosen], modes.ToArray());
        }
        throw new InvalidOperationException("Windows did not identify a primary monitor. Your display layout was left as it was.");
    }

    internal static int ValidateSnapshot(DisplaySnapshot snapshot) => Configure(snapshot, Validate | AllowChanges);
    private static int Configure(DisplaySnapshot snapshot, uint operation) => SetDisplayConfig((uint)snapshot.Paths.Length, snapshot.Paths, (uint)snapshot.Modes.Length, snapshot.Modes, operation | UseSupplied);
    private static void ThrowOnError(int error, string action)
    {
        if (error != 0) throw new Win32Exception(error, $"{action}: {new Win32Exception(error).Message} ({error}).");
    }

    internal static void WriteSnapshot(string file, DisplaySnapshot snapshot)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(file)!);
        string temporary = file + ".tmp";
        try
        {
            using (var writer = new BinaryWriter(File.Create(temporary)))
            {
                writer.Write(0x56444953); // VDIS, followed by a format version.
                writer.Write(1);
                WriteArray(writer, snapshot.Paths);
                WriteArray(writer, snapshot.Modes);
            }
            File.Move(temporary, file, overwrite: true);
        }
        finally { if (File.Exists(temporary)) File.Delete(temporary); }
    }
    internal static DisplaySnapshot ReadSnapshot(string file)
    {
        using var reader = new BinaryReader(File.OpenRead(file));
        if (reader.ReadInt32() != 0x56444953 || reader.ReadInt32() != 1) throw new InvalidDataException("Unrecognized display recovery snapshot.");
        var result = new DisplaySnapshot(ReadArray<DisplayPath>(reader, 128), ReadArray<DisplayMode>(reader, 512));
        if (result.Paths.Length == 0 || reader.BaseStream.Position != reader.BaseStream.Length) throw new InvalidDataException("Incomplete display recovery snapshot.");
        return result;
    }
    private static void WriteArray<T>(BinaryWriter writer, T[] items) where T : struct
    {
        int size = Marshal.SizeOf<T>();
        writer.Write(items.Length); writer.Write(size);
        nint buffer = Marshal.AllocHGlobal(size);
        try
        {
            var bytes = new byte[size];
            foreach (var item in items)
            {
                Marshal.StructureToPtr(item, buffer, false);
                Marshal.Copy(buffer, bytes, 0, size); writer.Write(bytes);
            }
        }
        finally { Marshal.FreeHGlobal(buffer); }
    }
    private static T[] ReadArray<T>(BinaryReader reader, int maximum) where T : struct
    {
        int count = reader.ReadInt32(), size = reader.ReadInt32();
        if (count < 0 || count > maximum || size != Marshal.SizeOf<T>()) throw new InvalidDataException("Invalid display recovery array.");
        var items = new T[count];
        nint buffer = Marshal.AllocHGlobal(size);
        try
        {
            for (int i = 0; i < count; i++)
            {
                var bytes = reader.ReadBytes(size);
                if (bytes.Length != size) throw new InvalidDataException("Truncated display recovery snapshot.");
                Marshal.Copy(bytes, 0, buffer, size); items[i] = Marshal.PtrToStructure<T>(buffer);
            }
        }
        finally { Marshal.FreeHGlobal(buffer); }
        return items;
    }

    [DllImport("user32.dll")] private static extern int GetDisplayConfigBufferSizes(uint flags, out uint paths, out uint modes);
    [DllImport("user32.dll")] private static extern int QueryDisplayConfig(uint flags, ref uint paths, [Out] DisplayPath[] pathArray, ref uint modes, [Out] DisplayMode[] modeArray, nint topology);
    [DllImport("user32.dll")] private static extern int SetDisplayConfig(uint paths, [In] DisplayPath[]? pathArray, uint modes, [In] DisplayMode[]? modeArray, uint flags);
}

internal sealed record DisplaySnapshot(DisplayPath[] Paths, DisplayMode[] Modes);
[StructLayout(LayoutKind.Sequential)] internal struct DisplayLuid { public uint Low; public int High; }
[StructLayout(LayoutKind.Sequential)] internal struct DisplaySource { public DisplayLuid Adapter; public uint Id, ModeIndex, Status; }
[StructLayout(LayoutKind.Sequential)] internal struct DisplayRational { public uint Numerator, Denominator; }
[StructLayout(LayoutKind.Sequential)] internal struct DisplayTarget
{
    public DisplayLuid Adapter;
    public uint Id, ModeIndex, OutputTechnology, Rotation, Scaling;
    public DisplayRational RefreshRate;
    public uint ScanLineOrdering;
    public int Available;
    public uint Status;
}
[StructLayout(LayoutKind.Sequential)] internal struct DisplayPath { public DisplaySource Source; public DisplayTarget Target; public uint Flags; }
[StructLayout(LayoutKind.Sequential)] internal struct DisplaySourceMode { public uint Width, Height, PixelFormat; public int X, Y; }
[StructLayout(LayoutKind.Explicit, Size = 64)] internal struct DisplayMode
{
    [FieldOffset(0)] public uint Type;
    [FieldOffset(4)] public uint Id;
    [FieldOffset(8)] public DisplayLuid Adapter;
    [FieldOffset(16)] public DisplaySourceMode Source;
    // Preserve the entire native union, including target video-signal and desktop-image modes.
    [FieldOffset(16)] public ulong Data0;
    [FieldOffset(24)] public ulong Data1;
    [FieldOffset(32)] public ulong Data2;
    [FieldOffset(40)] public ulong Data3;
    [FieldOffset(48)] public ulong Data4;
    [FieldOffset(56)] public ulong Data5;
}
