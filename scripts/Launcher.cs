using System;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;

internal static class Program
{
    private const string PayloadId = "__PAYLOAD_ID__";
    private const string AppName = "Toxido Anti Cheat";

    private static int Main(string[] args)
    {
        try
        {
            Console.Title = AppName;
            PrepareConsole();

            string local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            string engineRoot = Path.Combine(local, "40x1d0AC", "engine");
            string engine = Path.Combine(engineRoot, PayloadId);
            string java = Path.Combine(engine, "runtime", "bin", "java.exe");
            string app = Path.Combine(engine, "app", "ac-app.jar");
            string config = Path.Combine(engine, "app", "client.properties");
            string agent = Path.Combine(engine, "app", "ac-agent.jar");
            string hook = Path.Combine(engine, "app", "ac-agent-hook.jar");
            string input = Path.Combine(engine, "app", "40x1d0AC-input.exe");

            if (!File.Exists(java) || !File.Exists(app)
                || !File.Exists(config) || !File.Exists(agent)
                || !File.Exists(hook) || !File.Exists(input))
            {
                engine = Extract(engine);
                java = Path.Combine(engine, "runtime", "bin", "java.exe");
                app = Path.Combine(engine, "app", "ac-app.jar");
                config = Path.Combine(engine, "app", "client.properties");
                agent = Path.Combine(engine, "app", "ac-agent.jar");
                hook = Path.Combine(engine, "app", "ac-agent-hook.jar");
                input = Path.Combine(engine, "app", "40x1d0AC-input.exe");
            }

            Validate(engine, java, app, config, agent, hook, input);

            if (args.Length > 0 && args[0] == "--diagnose")
            {
                Diagnose(engine, java, app, config, agent, hook, input);
                return 0;
            }

            if (args.Length > 0 && args[0] == "--print-path")
            {
                Console.WriteLine(engine);
                return 0;
            }

            ProcessStartInfo info = new ProcessStartInfo();
            info.FileName = java;
            info.Arguments = "--add-modules jdk.attach"
                + " -Dac.config=\"" + config + "\""
                + " -Dac.agent.jar=\"" + agent + "\""
                + " -jar \"" + app + "\"";
            info.WorkingDirectory = engine;
            info.UseShellExecute = false;
            info.CreateNoWindow = false;

            try
            {
                info.EnvironmentVariables["COLUMNS"] =
                    Console.WindowWidth.ToString();
                info.EnvironmentVariables["LINES"] =
                    Console.WindowHeight.ToString();
            }
            catch
            {
                // Konsol boyutu okunamazsa Java tarafindaki varsayilan kullanilir.
            }

            Process child = Process.Start(info);
            if (child == null)
            {
                throw new Exception("Java sureci baslatilamadi.");
            }
            child.WaitForExit();
            return child.ExitCode;
        }
        catch (Exception exception)
        {
            Log(exception.ToString());
            try
            {
                Console.Error.WriteLine("Toxido AC baslatilamadi.");
                Console.Error.WriteLine("Ayrintilar: " + LogPath());
            }
            catch
            {
                // Konsol yoksa sessizce cik.
            }
            return 1;
        }
    }

    private static void Validate(string engine, params string[] files)
    {
        Log("Engine: " + engine);
        foreach (string path in files)
        {
            FileInfo info = new FileInfo(path);
            if (!info.Exists)
            {
                throw new Exception(
                    "Gerekli dosya eksik: " + path
                    + ". Antivirus karantinasi veya bozuk paket olabilir.");
            }
            Log("Dosya: " + path + " (" + info.Length + " bayt)");
        }
    }

    private static void Diagnose(
        string engine,
        string java,
        string app,
        string config,
        string agent,
        string hook,
        string input)
    {
        StringBuilder report = new StringBuilder();
        report.AppendLine("Toxido AC teshis");
        report.AppendLine("Engine: " + engine);
        foreach (string path in new[] { java, app, config, agent, hook, input })
        {
            FileInfo info = new FileInfo(path);
            report.AppendLine(
                (info.Exists ? "OK   " : "EKSIK") + " " + path
                + (info.Exists ? " (" + info.Length + " bayt)" : ""));
        }
        report.AppendLine("Java: " + java);
        string output = report.ToString();
        Console.WriteLine(output);
        try
        {
            string path = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                "40x1d0AC",
                "diagnose.txt");
            Directory.CreateDirectory(Path.GetDirectoryName(path));
            File.WriteAllText(path, output, Encoding.UTF8);
        }
        catch
        {
            // Teshis dosyasi yazilamazsa konsol ciktisi yeterli.
        }
    }

    private static void PrepareConsole()
    {
        try
        {
            SetConsoleOutputCP(65001);
            SetConsoleCP(65001);

            IntPtr handle = GetStdHandle(-11);
            if (handle == IntPtr.Zero || handle == new IntPtr(-1))
            {
                return;
            }

            CONSOLE_FONT_INFO_EX current = new CONSOLE_FONT_INFO_EX();
            current.cbSize = Marshal.SizeOf(typeof(CONSOLE_FONT_INFO_EX));
            current.FaceName = "";
            string oldFace = "";
            int oldSize = 0;
            if (GetCurrentConsoleFontEx(handle, false, ref current))
            {
                oldFace = current.FaceName;
                oldSize = current.dwFontSizeY;
            }

            CONSOLE_FONT_INFO_EX wanted = new CONSOLE_FONT_INFO_EX();
            wanted.cbSize = Marshal.SizeOf(typeof(CONSOLE_FONT_INFO_EX));
            wanted.FaceName = "Consolas";
            wanted.FontFamily = 54;
            wanted.FontWeight = 400;
            wanted.dwFontSizeX = 0;
            wanted.dwFontSizeY = 16;
            bool changed = SetCurrentConsoleFontEx(handle, false, ref wanted);

            SMALL_RECT tiny = new SMALL_RECT();
            tiny.Left = 0;
            tiny.Top = 0;
            tiny.Right = 0;
            tiny.Bottom = 0;
            SetConsoleWindowInfo(handle, true, ref tiny);

            COORD buffer = new COORD();
            buffer.X = 120;
            buffer.Y = 50;
            bool bufferOk = SetConsoleScreenBufferSize(handle, buffer);

            SMALL_RECT window = new SMALL_RECT();
            window.Left = 0;
            window.Top = 0;
            window.Right = 119;
            window.Bottom = 29;
            bool windowOk = SetConsoleWindowInfo(handle, true, ref window);

            Log("Konsol fontu: " + oldFace + " " + oldSize
                + " -> Consolas 16 (" + changed + "), buffer=" + bufferOk
                + ", window=" + windowOk + ".");
        }
        catch (Exception exception)
        {
            Log("Konsol ayari uygulanamadi: " + exception.Message);
        }
    }

    private static string Extract(string target)
    {
        Console.Error.Write("Toxido AC hazirlaniyor...");
        try
        {
            string parent = Path.GetDirectoryName(target);
            if (!String.IsNullOrEmpty(parent))
            {
                Directory.CreateDirectory(parent);
            }

            if (Directory.Exists(target))
            {
                try
                {
                    Directory.Delete(target, true);
                }
                catch
                {
                    target = target + "-" + Guid.NewGuid().ToString("N").Substring(0, 8);
                }
            }

            string temp = Path.Combine(
                Path.GetTempPath(),
                "40x1d0AC-" + Guid.NewGuid().ToString("N") + ".zip");

            using (Stream input = Assembly.GetExecutingAssembly()
                .GetManifestResourceStream("payload.zip"))
            {
                if (input == null)
                {
                    throw new Exception("Gomulu paket bulunamadi.");
                }
                using (FileStream output = File.Create(temp))
                {
                    input.CopyTo(output);
                }
            }

            try
            {
                ZipFile.ExtractToDirectory(temp, target);
            }
            finally
            {
                try { File.Delete(temp); } catch { }
            }

            Console.Error.Write("\r" + new String(' ', 30) + "\r");
            return target;
        }
        catch
        {
            Console.Error.Write("\r" + new String(' ', 30) + "\r");
            throw;
        }
    }

    private static string LogPath()
    {
        return Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "40x1d0AC",
            "launcher.log");
    }

    private static void Log(string message)
    {
        try
        {
            string path = LogPath();
            Directory.CreateDirectory(Path.GetDirectoryName(path));
            File.AppendAllText(
                path,
                "[" + DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + "] " + message
                    + Environment.NewLine,
                Encoding.UTF8);
        }
        catch
        {
            // Log yazilamazsa devam et.
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct COORD
    {
        public short X;
        public short Y;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct SMALL_RECT
    {
        public short Left;
        public short Top;
        public short Right;
        public short Bottom;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct CONSOLE_FONT_INFO_EX
    {
        public int cbSize;
        public int nFont;
        public short dwFontSizeX;
        public short dwFontSizeY;
        public int FontFamily;
        public int FontWeight;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)]
        public string FaceName;
    }

    [DllImport("kernel32.dll")]
    private static extern IntPtr GetStdHandle(int nStdHandle);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    private static extern bool GetCurrentConsoleFontEx(
        IntPtr hConsoleOutput,
        bool bMaximumWindow,
        ref CONSOLE_FONT_INFO_EX lpConsoleCurrentFontEx);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    private static extern bool SetCurrentConsoleFontEx(
        IntPtr hConsoleOutput,
        bool bMaximumWindow,
        ref CONSOLE_FONT_INFO_EX lpConsoleCurrentFontEx);

    [DllImport("kernel32.dll")]
    private static extern bool SetConsoleScreenBufferSize(
        IntPtr hConsoleOutput,
        COORD dwSize);

    [DllImport("kernel32.dll")]
    private static extern bool SetConsoleWindowInfo(
        IntPtr hConsoleOutput,
        bool bAbsolute,
        ref SMALL_RECT lpConsoleWindow);

    [DllImport("kernel32.dll")]
    private static extern bool SetConsoleOutputCP(uint wCodePageID);

    [DllImport("kernel32.dll")]
    private static extern bool SetConsoleCP(uint wCodePageID);
}
