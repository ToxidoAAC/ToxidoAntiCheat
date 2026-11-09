using System;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Threading;

internal static class Program
{
    private const int WH_MOUSE_LL = 14;
    private const int WM_LBUTTONDOWN = 0x0201;
    private const int WM_LBUTTONUP = 0x0202;
    private const int WM_INPUT = 0x00FF;
    private const uint LLMHF_INJECTED = 0x00000001;
    private const uint LLMHF_LOWER_IL_INJECTED = 0x00000002;
    private const uint RID_INPUT = 0x10000003;
    private const uint RIDEV_INPUTSINK = 0x00000100;
    private const ushort HID_USAGE_PAGE_GENERIC = 0x01;
    private const ushort HID_USAGE_GENERIC_MOUSE = 0x02;
    private const uint RIM_TYPEMOUSE = 0;
    private const ushort RI_MOUSE_LEFT_BUTTON_DOWN = 0x0001;
    private const ushort RI_MOUSE_LEFT_BUTTON_UP = 0x0002;

    private delegate IntPtr LowLevelMouseProc(int nCode, IntPtr wParam, IntPtr lParam);
    private delegate IntPtr WndProc(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);

    private static LowLevelMouseProc hookProc;
    private static WndProc windowProc;
    private static IntPtr hook;
    private static IntPtr window;
    private static int parentPid;
    private static Timer parentTimer;

    [STAThread]
    private static int Main(string[] args)
    {
        try
        {
            ShowWindow(GetConsoleWindow(), 0);
            for (int i = 0; i + 1 < args.Length; i++)
            {
                if (args[i] == "--parent")
                {
                    int.TryParse(args[i + 1], out parentPid);
                }
            }

            if (parentPid > 0)
            {
                parentTimer = new Timer(CheckParent, null, 2000, 2000);
            }

            hookProc = HookCallback;
            hook = SetWindowsHookEx(WH_MOUSE_LL, hookProc, GetModuleHandle(null), 0);
            if (hook == IntPtr.Zero)
            {
                Console.WriteLine("HOOK_FAIL " + Marshal.GetLastWin32Error());
                Console.Out.Flush();
            }

            CreateRawWindow();

            Console.WriteLine("READY " + Now());
            Console.Out.Flush();

            MSG msg;
            while (GetMessage(out msg, IntPtr.Zero, 0, 0) > 0)
            {
                TranslateMessage(ref msg);
                DispatchMessage(ref msg);
            }
            return 0;
        }
        catch (Exception exception)
        {
            Console.WriteLine("ERROR " + exception.Message);
            Console.Out.Flush();
            return 1;
        }
        finally
        {
            if (hook != IntPtr.Zero)
            {
                UnhookWindowsHookEx(hook);
            }
        }
    }

    private static void CheckParent(object state)
    {
        try
        {
            Process.GetProcessById(parentPid);
        }
        catch
        {
            Environment.Exit(0);
        }
    }

    private static IntPtr HookCallback(int nCode, IntPtr wParam, IntPtr lParam)
    {
        if (nCode >= 0)
        {
            int message = wParam.ToInt32();
            if (message == WM_LBUTTONDOWN || message == WM_LBUTTONUP)
            {
                MSLLHOOKSTRUCT data = (MSLLHOOKSTRUCT)Marshal.PtrToStructure(
                    lParam, typeof(MSLLHOOKSTRUCT));
                bool injected = (data.flags & (LLMHF_INJECTED | LLMHF_LOWER_IL_INJECTED)) != 0;
                string kind = message == WM_LBUTTONDOWN ? "D" : "U";
                Console.WriteLine(kind + " " + Now() + " " + (injected ? "1" : "0")
                    + " " + data.time);
                Console.Out.Flush();
            }
        }
        return CallNextHookEx(hook, nCode, wParam, lParam);
    }

    private static void CreateRawWindow()
    {
        try
        {
            IntPtr instance = GetModuleHandle(null);
            windowProc = WindowCallback;
            WNDCLASSEX wc = new WNDCLASSEX();
            wc.cbSize = (uint)Marshal.SizeOf(typeof(WNDCLASSEX));
            wc.lpfnWndProc = Marshal.GetFunctionPointerForDelegate(windowProc);
            wc.hInstance = instance;
            wc.lpszClassName = "ToxidoACInput";
            if (RegisterClassEx(ref wc) == 0)
            {
                Console.WriteLine("RAW_FAIL class " + Marshal.GetLastWin32Error());
                Console.Out.Flush();
                return;
            }

            window = CreateWindowEx(
                0, "ToxidoACInput", "", 0, 0, 0, 0, 0,
                new IntPtr(-3), IntPtr.Zero, instance, IntPtr.Zero);
            if (window == IntPtr.Zero)
            {
                Console.WriteLine("RAW_FAIL window " + Marshal.GetLastWin32Error());
                Console.Out.Flush();
                return;
            }

            RAWINPUTDEVICE[] devices = new RAWINPUTDEVICE[1];
            devices[0].usUsagePage = HID_USAGE_PAGE_GENERIC;
            devices[0].usUsage = HID_USAGE_GENERIC_MOUSE;
            devices[0].dwFlags = RIDEV_INPUTSINK;
            devices[0].hwndTarget = window;
            if (!RegisterRawInputDevices(
                devices, 1, (uint)Marshal.SizeOf(typeof(RAWINPUTDEVICE))))
            {
                Console.WriteLine("RAW_FAIL register " + Marshal.GetLastWin32Error());
                Console.Out.Flush();
            }
        }
        catch (Exception exception)
        {
            Console.WriteLine("RAW_FAIL " + exception.Message);
            Console.Out.Flush();
        }
    }

    private static IntPtr WindowCallback(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam)
    {
        if (msg == WM_INPUT)
        {
            try
            {
                uint size = 0;
                uint headerSize = (uint)Marshal.SizeOf(typeof(RAWINPUTHEADER));
                GetRawInputData(lParam, RID_INPUT, IntPtr.Zero, ref size, headerSize);
                if (size > 0)
                {
                    IntPtr buffer = Marshal.AllocHGlobal((int)size);
                    try
                    {
                        if (GetRawInputData(lParam, RID_INPUT, buffer, ref size, headerSize) == size)
                        {
                            RAWINPUTHEADER header = (RAWINPUTHEADER)Marshal.PtrToStructure(
                                buffer, typeof(RAWINPUTHEADER));
                            if (header.dwType == RIM_TYPEMOUSE)
                            {
                                IntPtr mousePtr = new IntPtr(buffer.ToInt64()
                                    + Marshal.SizeOf(typeof(RAWINPUTHEADER)));
                                RAWMOUSE mouse = (RAWMOUSE)Marshal.PtrToStructure(
                                    mousePtr, typeof(RAWMOUSE));
                                if ((mouse.usButtonFlags & RI_MOUSE_LEFT_BUTTON_DOWN) != 0)
                                {
                                    Console.WriteLine("RD " + Now());
                                    Console.Out.Flush();
                                }
                                if ((mouse.usButtonFlags & RI_MOUSE_LEFT_BUTTON_UP) != 0)
                                {
                                    Console.WriteLine("RU " + Now());
                                    Console.Out.Flush();
                                }
                            }
                        }
                    }
                    finally
                    {
                        Marshal.FreeHGlobal(buffer);
                    }
                }
            }
            catch
            {
            }
        }
        return DefWindowProc(hWnd, msg, wParam, lParam);
    }

    private static long Now()
    {
        return (long)(DateTime.UtcNow - new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc)).TotalMilliseconds;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct POINT
    {
        public int x;
        public int y;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MSLLHOOKSTRUCT
    {
        public POINT pt;
        public uint mouseData;
        public uint flags;
        public uint time;
        public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MSG
    {
        public IntPtr hwnd;
        public uint message;
        public IntPtr wParam;
        public IntPtr lParam;
        public uint time;
        public POINT pt;
        public uint lPrivate;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct RAWINPUTDEVICE
    {
        public ushort usUsagePage;
        public ushort usUsage;
        public uint dwFlags;
        public IntPtr hwndTarget;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct RAWINPUTHEADER
    {
        public uint dwType;
        public uint dwSize;
        public IntPtr hDevice;
        public IntPtr wParam;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct RAWMOUSE
    {
        public ushort usFlags;
        public ushort usButtonFlags;
        public ushort usButtonData;
        public uint ulRawButtons;
        public int lLastX;
        public int lLastY;
        public uint ulExtraInformation;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Auto)]
    private struct WNDCLASSEX
    {
        public uint cbSize;
        public uint style;
        public IntPtr lpfnWndProc;
        public int cbClsExtra;
        public int cbWndExtra;
        public IntPtr hInstance;
        public IntPtr hIcon;
        public IntPtr hCursor;
        public IntPtr hbrBackground;
        public string lpszMenuName;
        public string lpszClassName;
        public IntPtr hIconSm;
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr SetWindowsHookEx(
        int idHook, LowLevelMouseProc lpfn, IntPtr hMod, uint dwThreadId);

    [DllImport("user32.dll")]
    private static extern bool UnhookWindowsHookEx(IntPtr hhk);

    [DllImport("user32.dll")]
    private static extern IntPtr CallNextHookEx(
        IntPtr hhk, int nCode, IntPtr wParam, IntPtr lParam);

    [DllImport("kernel32.dll", CharSet = CharSet.Auto)]
    private static extern IntPtr GetModuleHandle(string lpModuleName);

    [DllImport("user32.dll")]
    private static extern int GetMessage(
        out MSG lpMsg, IntPtr hWnd, uint wMsgFilterMin, uint wMsgFilterMax);

    [DllImport("user32.dll")]
    private static extern bool TranslateMessage(ref MSG lpMsg);

    [DllImport("user32.dll")]
    private static extern IntPtr DispatchMessage(ref MSG lpMsg);

    [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Auto)]
    private static extern ushort RegisterClassEx(ref WNDCLASSEX lpwcx);

    [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Auto)]
    private static extern IntPtr CreateWindowEx(
        uint dwExStyle, string lpClassName, string lpWindowName, uint dwStyle,
        int x, int y, int nWidth, int nHeight, IntPtr hWndParent, IntPtr hMenu,
        IntPtr hInstance, IntPtr lpParam);

    [DllImport("user32.dll")]
    private static extern IntPtr DefWindowProc(
        IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool RegisterRawInputDevices(
        RAWINPUTDEVICE[] pRawInputDevices, uint uiNumDevices, uint cbSize);

    [DllImport("user32.dll")]
    private static extern uint GetRawInputData(
        IntPtr hRawInput, uint uiCommand, IntPtr pData, ref uint pcbSize,
        uint cbSizeHeader);

    [DllImport("kernel32.dll")]
    private static extern IntPtr GetConsoleWindow();

    [DllImport("user32.dll")]
    private static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
}