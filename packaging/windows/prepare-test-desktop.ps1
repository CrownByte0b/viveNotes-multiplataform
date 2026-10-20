$ErrorActionPreference = 'Stop'
# Native Swing interaction tests need room for their 1400/1600 px windows. Hosted Windows
# runners start at 1024x768 and constrain those windows, hiding the controls under test.
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class ViveTestDisplay {
    [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)]
    public struct Mode {
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst=32)] public string device;
        public short spec, driver, size, extra;
        public int fields, x, y, orientation, fixedOutput;
        public short color, duplex, yResolution, ttOption, collate;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst=32)] public string form;
        public short logPixels;
        public int bits, width, height, flags, frequency, icmMethod, icmIntent, media,
            dither, reserved1, reserved2, panningWidth, panningHeight;
    }
    [DllImport("user32.dll", CharSet=CharSet.Unicode)]
    static extern bool EnumDisplaySettings(string device, int index, ref Mode mode);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)]
    static extern int ChangeDisplaySettingsEx(string device, ref Mode mode, IntPtr window, int flags, IntPtr data);
    static Mode Current() {
        var mode = new Mode { size = (short)Marshal.SizeOf(typeof(Mode)) };
        if (!EnumDisplaySettings(null, -1, ref mode)) throw new Exception("No interactive Windows display");
        return mode;
    }
    public static string Prepare() {
        var mode = Current();
        if (mode.width < 1600 || mode.height < 900) {
            foreach (var dimensions in new[] { new[] {1920, 1200}, new[] {1920, 1080}, new[] {1600, 900} }) {
                mode = Current();
                mode.width = dimensions[0]; mode.height = dimensions[1];
                mode.fields = 0x80000 | 0x100000;
                // Flags zero changes this session only; no registry or machine configuration.
                if (ChangeDisplaySettingsEx(null, ref mode, IntPtr.Zero, 0, IntPtr.Zero) == 0) break;
            }
            mode = Current();
        }
        if (mode.width < 1600 || mode.height < 900)
            throw new Exception("Native UI tests need a desktop of at least 1600x900");
        return mode.width + "x" + mode.height;
    }
}
'@
Write-Host "Windows test display: $([ViveTestDisplay]::Prepare())"
