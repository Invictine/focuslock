using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media.Imaging;

namespace VoidApp;

internal static class AppIcon
{
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct ShellInfo
    {
        public nint Icon;
        public int IconIndex;
        public uint Attributes;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 260)] public string DisplayName;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 80)] public string TypeName;
    }

    [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
    private static extern int SHParseDisplayName(string name, nint context, out nint item, uint flags, out uint attributes);
    [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
    private static extern nint SHGetFileInfo(nint item, uint attributes, out ShellInfo info, uint size, uint flags);
    [DllImport("user32.dll")] private static extern bool DestroyIcon(nint icon);

    internal static FrameworkElement Create(FocusLockTool tool)
    {
        var target = !string.IsNullOrWhiteSpace(tool.ExecutablePath) ? tool.ExecutablePath :
            !string.IsNullOrWhiteSpace(tool.AppUserModelId) ? "shell:AppsFolder\\" + tool.AppUserModelId : null;
        nint item = 0;
        nint icon = 0;
        try
        {
            if (target is not null && SHParseDisplayName(target, 0, out item, 0, out _) == 0 &&
                SHGetFileInfo(item, 0, out var info, (uint)Marshal.SizeOf<ShellInfo>(), 0x100 | 0x8) != 0)
            {
                icon = info.Icon;
                if (icon != 0)
                {
                    var source = Imaging.CreateBitmapSourceFromHIcon(icon, Int32Rect.Empty, BitmapSizeOptions.FromWidthAndHeight(52, 52));
                    source.Freeze();
                    return new Image { Source = source, Width = 52, Height = 52 };
                }
            }
        }
        catch (Exception) { /* Keep the approved shortcut usable when its icon is unavailable. */ }
        finally
        {
            if (icon != 0) DestroyIcon(icon);
            if (item != 0) Marshal.FreeCoTaskMem(item);
        }
        return new TextBlock { Text = tool.Label[..Math.Min(1, tool.Label.Length)], FontSize = 28,
            VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Center };
    }
}
