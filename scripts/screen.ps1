param(
    [ValidateSet('shot','click','key','focus')]
    [string]$Action = 'shot',
    [string]$Path = "$PSScriptRoot\..\build\screen.png",
    [int]$X = 0,
    [int]$Y = 0,
    [int]$TargetPid = 0,
    [string]$Keys = ''
)

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class Win32Input {
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int X, int Y);
    [DllImport("user32.dll")] public static extern void mouse_event(uint dwFlags, uint dx, uint dy, uint dwData, UIntPtr dwExtraInfo);
    public const uint LEFTDOWN = 0x0002;
    public const uint LEFTUP = 0x0004;
}
"@

switch ($Action) {
    'focus' {
        $p = Get-Process -Id $TargetPid
        [Win32Input]::ShowWindow($p.MainWindowHandle, 9) | Out-Null
        Start-Sleep -Milliseconds 300
        [Win32Input]::SetForegroundWindow($p.MainWindowHandle) | Out-Null
        Start-Sleep -Milliseconds 500
        "Odak: $($p.ProcessName) $($p.Id)"
    }
    'shot' {
        $bounds = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
        $bmp = New-Object System.Drawing.Bitmap($bounds.Width, $bounds.Height)
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.CopyFromScreen($bounds.Location, [System.Drawing.Point]::Empty, $bounds.Size)
        $bmp.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
        $g.Dispose(); $bmp.Dispose()
        "Ekran: $Path ($($bounds.Width)x$($bounds.Height))"
    }
    'click' {
        [Win32Input]::SetCursorPos($X, $Y) | Out-Null
        Start-Sleep -Milliseconds 120
        [Win32Input]::mouse_event([Win32Input]::LEFTDOWN, 0, 0, 0, [UIntPtr]::Zero)
        Start-Sleep -Milliseconds 60
        [Win32Input]::mouse_event([Win32Input]::LEFTUP, 0, 0, 0, [UIntPtr]::Zero)
        Start-Sleep -Milliseconds 300
        "Tik: $X,$Y"
    }
    'key' {
        $p = Get-Process -Id $TargetPid
        [Win32Input]::SetForegroundWindow($p.MainWindowHandle) | Out-Null
        Start-Sleep -Milliseconds 300
        [System.Windows.Forms.SendKeys]::SendWait($Keys)
        Start-Sleep -Milliseconds 300
        "Tus: $Keys"
    }
}
