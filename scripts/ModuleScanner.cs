using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;

internal static class Program
{
    private const uint PROCESS_QUERY_INFORMATION = 0x0400;
    private const uint PROCESS_VM_READ = 0x0010;
    private const uint LIST_MODULES_ALL = 0x03;
    private const uint MEM_COMMIT = 0x1000;
    private const uint MEM_PRIVATE = 0x20000;
    private const uint PAGE_EXECUTE = 0x10;
    private const uint PAGE_EXECUTE_READ = 0x20;
    private const uint PAGE_EXECUTE_READWRITE = 0x40;
    private const uint PAGE_EXECUTE_WRITECOPY = 0x80;

    private static int Main(string[] args)
    {
        if (args.Length < 1)
        {
            Console.WriteLine("ERROR\tpid gerekli");
            return 1;
        }

        int pid;
        if (!int.TryParse(args[0], out pid))
        {
            Console.WriteLine("ERROR\tgecersiz pid");
            return 1;
        }

        IntPtr process = OpenProcess(
            PROCESS_QUERY_INFORMATION | PROCESS_VM_READ, false, pid);
        if (process == IntPtr.Zero)
        {
            Console.WriteLine("ERROR\tOpenProcess " + Marshal.GetLastWin32Error());
            return 1;
        }

        try
        {
            ScanModules(process);
            ScanMemory(process);
        }
        finally
        {
            CloseHandle(process);
        }
        return 0;
    }

    private static void ScanModules(IntPtr process)
    {
        IntPtr[] modules = new IntPtr[4096];
        uint needed;
        if (!EnumProcessModulesEx(
            process, modules, (uint)(modules.Length * IntPtr.Size), out needed, LIST_MODULES_ALL))
        {
            Console.WriteLine("ERROR\tEnumProcessModulesEx " + Marshal.GetLastWin32Error());
            return;
        }

        int count = (int)(needed / (uint)IntPtr.Size);
        if (count > modules.Length)
        {
            count = modules.Length;
        }

        for (int i = 0; i < count; i++)
        {
            StringBuilder path = new StringBuilder(1024);
            if (GetModuleFileNameEx(process, modules[i], path, (uint)path.Capacity) == 0)
            {
                continue;
            }

            MODULEINFO info = new MODULEINFO();
            GetModuleInformation(
                process, modules[i], out info, (uint)Marshal.SizeOf(typeof(MODULEINFO)));

            string file = path.ToString().Replace('\t', ' ');
            string lower = file.ToLowerInvariant();
            bool trusted = IsTrusted(lower);
            bool signed = IsSigned(file);
            string hash = trusted ? "" : Sha256(file);
            Console.WriteLine("MODULE\t" + file + "\t0x" + info.lpBaseOfDll.ToInt64().ToString("X")
                + "\t0x" + info.SizeOfImage.ToString("X") + "\t" + (signed ? "1" : "0")
                + "\t" + hash);
        }
        Console.Out.Flush();
    }

    private static void ScanMemory(IntPtr process)
    {
        ulong address = 0;
        int guard = 0;
        while (guard++ < 200000)
        {
            MEMORY_BASIC_INFORMATION info = new MEMORY_BASIC_INFORMATION();
            UIntPtr queried = VirtualQueryEx(
                process, new IntPtr((long)address), out info,
                (UIntPtr)Marshal.SizeOf(typeof(MEMORY_BASIC_INFORMATION)));
            if (queried == UIntPtr.Zero)
            {
                break;
            }

            ulong baseAddress = (ulong)info.BaseAddress.ToInt64();
            ulong size = (ulong)info.RegionSize.ToUInt64();
            if (size == 0)
            {
                break;
            }

            bool executable = (info.Protect & PAGE_EXECUTE) != 0
                || (info.Protect & PAGE_EXECUTE_READ) != 0
                || (info.Protect & PAGE_EXECUTE_READWRITE) != 0
                || (info.Protect & PAGE_EXECUTE_WRITECOPY) != 0;
            if (info.State == MEM_COMMIT && info.Type == MEM_PRIVATE && executable)
            {
                byte[] buffer = new byte[4096];
                UIntPtr read;
                if (ReadProcessMemory(
                    process, info.BaseAddress, buffer, (UIntPtr)buffer.Length, out read)
                    && read.ToUInt64() >= 64
                    && buffer[0] == (byte)'M' && buffer[1] == (byte)'Z')
                {
                    int peOffset = BitConverter.ToInt32(buffer, 0x3C);
                    if (peOffset > 0 && peOffset + 4 < buffer.Length
                        && buffer[peOffset] == (byte)'P'
                        && buffer[peOffset + 1] == (byte)'E'
                        && buffer[peOffset + 2] == 0
                        && buffer[peOffset + 3] == 0)
                    {
                        Console.WriteLine("MEMORY\t0x" + baseAddress.ToString("X")
                            + "\t0x" + size.ToString("X"));
                    }
                }
            }

            ulong next = baseAddress + size;
            if (next <= baseAddress)
            {
                break;
            }
            address = next;
        }
        Console.Out.Flush();
    }

    private static bool IsTrusted(string path)
    {
        return path.Contains("\\windows\\")
            || path.Contains("\\program files")
            || path.Contains("\\runtime\\")
            || path.Contains("\\40x1d0ac\\")
            || path.Contains("\\.minecraft\\")
            || path.Contains("\\jdk")
            || path.Contains("\\java");
    }

    private static bool IsSigned(string path)
    {
        try
        {
            X509Certificate.CreateFromSignedFile(path);
            return true;
        }
        catch
        {
            return false;
        }
    }

    private static string Sha256(string path)
    {
        try
        {
            using (FileStream stream = File.OpenRead(path))
            using (SHA256 sha = SHA256.Create())
            {
                byte[] hash = sha.ComputeHash(stream);
                StringBuilder text = new StringBuilder(hash.Length * 2);
                for (int i = 0; i < hash.Length; i++)
                {
                    text.Append(hash[i].ToString("x2"));
                }
                return text.ToString();
            }
        }
        catch
        {
            return "";
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MODULEINFO
    {
        public IntPtr lpBaseOfDll;
        public uint SizeOfImage;
        public IntPtr EntryPoint;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MEMORY_BASIC_INFORMATION
    {
        public IntPtr BaseAddress;
        public IntPtr AllocationBase;
        public uint AllocationProtect;
        public UIntPtr RegionSize;
        public uint State;
        public uint Protect;
        public uint Type;
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr OpenProcess(
        uint dwDesiredAccess, bool bInheritHandle, int dwProcessId);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool CloseHandle(IntPtr hObject);

    [DllImport("psapi.dll", SetLastError = true)]
    private static extern bool EnumProcessModulesEx(
        IntPtr hProcess, [Out] IntPtr[] lphModule, uint cb,
        out uint lpcbNeeded, uint dwFilterFlag);

    [DllImport("psapi.dll", CharSet = CharSet.Unicode)]
    private static extern uint GetModuleFileNameEx(
        IntPtr hProcess, IntPtr hModule, StringBuilder lpFilename, uint nSize);

    [DllImport("psapi.dll", SetLastError = true)]
    private static extern bool GetModuleInformation(
        IntPtr hProcess, IntPtr hModule, out MODULEINFO lpmodinfo, uint cb);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern UIntPtr VirtualQueryEx(
        IntPtr hProcess, IntPtr lpAddress, out MEMORY_BASIC_INFORMATION lpBuffer,
        UIntPtr dwLength);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool ReadProcessMemory(
        IntPtr hProcess, IntPtr lpBaseAddress, [Out] byte[] lpBuffer,
        UIntPtr nSize, out UIntPtr lpNumberOfBytesRead);
}