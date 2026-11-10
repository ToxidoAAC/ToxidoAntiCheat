using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;

internal static class Program
{
    private const uint TH32CS_SNAPMODULE = 0x00000008;
    private const uint TH32CS_SNAPMODULE32 = 0x00000010;
    private const int MAX_MODULES = 4096;

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

        // Toolhelp32 snapshot kullanilir: hedef surecte bellek okumaz,
        // OpenProcess/ReadProcessMemory cagirmaz. Yuklu modul listesi,
        // imza ve hash kontrolu yapilir.
        ScanModules(pid);
        return 0;
    }

    private static void ScanModules(int pid)
    {
        IntPtr snapshot = CreateToolhelp32Snapshot(
            TH32CS_SNAPMODULE | TH32CS_SNAPMODULE32, (uint)pid);
        if (snapshot == IntPtr.Zero || snapshot == new IntPtr(-1))
        {
            Console.WriteLine("ERROR\tCreateToolhelp32Snapshot " + Marshal.GetLastWin32Error());
            return;
        }

        try
        {
            MODULEENTRY32 entry = new MODULEENTRY32();
            entry.dwSize = (uint)Marshal.SizeOf(typeof(MODULEENTRY32));

            int count = 0;
            bool ok = Module32First(snapshot, ref entry);
            while (ok && count++ < MAX_MODULES)
            {
                string file = string.IsNullOrEmpty(entry.szExePath)
                    ? entry.szModule
                    : entry.szExePath;
                if (!string.IsNullOrEmpty(file))
                {
                    file = file.Replace('\t', ' ');
                    string lower = file.ToLowerInvariant();
                    bool trusted = IsTrusted(lower);
                    bool signed = IsSigned(file);
                    string hash = trusted ? "" : Sha256(file);
                    Console.WriteLine("MODULE\t" + file
                        + "\t0x" + entry.modBaseAddr.ToInt64().ToString("X")
                        + "\t0x" + entry.modBaseSize.ToString("X")
                        + "\t" + (signed ? "1" : "0")
                        + "\t" + hash);
                }

                entry.dwSize = (uint)Marshal.SizeOf(typeof(MODULEENTRY32));
                ok = Module32Next(snapshot, ref entry);
            }
        }
        finally
        {
            CloseHandle(snapshot);
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

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct MODULEENTRY32
    {
        public uint dwSize;
        public uint th32ModuleID;
        public uint th32ProcessID;
        public uint GlblcntUsage;
        public uint ProccntUsage;
        public IntPtr modBaseAddr;
        public uint modBaseSize;
        public IntPtr hModule;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)]
        public string szModule;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 260)]
        public string szExePath;
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr CreateToolhelp32Snapshot(
        uint dwFlags, uint th32ProcessID);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool Module32First(
        IntPtr hSnapshot, ref MODULEENTRY32 lpme);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool Module32Next(
        IntPtr hSnapshot, ref MODULEENTRY32 lpme);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool CloseHandle(IntPtr hObject);
}
