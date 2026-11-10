using System;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Runtime.InteropServices;

internal static class Program
{
    private const string PayloadId = "__PAYLOAD_ID__";
    private const string AppName = "Toxido Anti Cheat";

    private static int Main(string[] args)
    {
        if (args == null)
        {
            args = new string[0];
        }
        try
        {
            string local = Environment.GetFolderPath(
                Environment.SpecialFolder.LocalApplicationData);
            string engineRoot = Path.Combine(local, "40x1d0AC", "engine");
            string engine = Path.Combine(engineRoot, PayloadId);

            string java;
            string app;
            string config;
            string agent;
            string hook;
            string input;
            Resolve(engine, out java, out app, out config, out agent, out hook, out input);

            if (!IsInstalled(java, app, config, agent, hook, input))
            {
                // Mevcut kurulum gecerliyse yeniden cikarma yapilmaz.
                engine = Extract(engine);
                Resolve(engine, out java, out app, out config, out agent, out hook, out input);
            }

            Validate(java, app, config, agent, hook, input);

            ProcessStartInfo info = new ProcessStartInfo();
            info.FileName = java;
            info.Arguments = "--add-modules jdk.attach"
                + " -Dac.config=\"" + config + "\""
                + " -Dac.agent.jar=\"" + agent + "\""
                + " -jar \"" + app + "\" --service";
            info.WorkingDirectory = engine;
            info.UseShellExecute = false;
            info.CreateNoWindow = true;

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
            MessageBoxW(
                IntPtr.Zero,
                exception.Message,
                AppName,
                0x00000010);
            return 1;
        }
    }

    private static void Resolve(
        string engine,
        out string java,
        out string app,
        out string config,
        out string agent,
        out string hook,
        out string input)
    {
        java = Path.Combine(engine, "runtime", "bin", "javaw.exe");
        app = Path.Combine(engine, "app", "ac-app.jar");
        config = Path.Combine(engine, "app", "client.properties");
        agent = Path.Combine(engine, "app", "ac-agent.jar");
        hook = Path.Combine(engine, "app", "ac-agent-hook.jar");
        input = Path.Combine(engine, "app", "40x1d0AC-input.exe");
    }

    private static bool IsInstalled(params string[] files)
    {
        foreach (string path in files)
        {
            if (!File.Exists(path))
            {
                return false;
            }
        }
        return true;
    }

    private static void Validate(params string[] files)
    {
        foreach (string path in files)
        {
            FileInfo info = new FileInfo(path);
            if (!info.Exists)
            {
                throw new Exception(
                    "Gerekli dosya eksik: " + path
                    + ". Antivirus karantinasi veya bozuk paket olabilir.");
            }
        }
    }

    private static string Extract(string target)
    {
        string parent = Path.GetDirectoryName(target);
        if (!String.IsNullOrEmpty(parent))
        {
            Directory.CreateDirectory(parent);
        }

        string staging = target + ".tmp-"
            + Guid.NewGuid().ToString("N").Substring(0, 8);

        try
        {
            Directory.CreateDirectory(staging);

            // Paket dogrudan bellekten acilir; gecici zip dosyasi diske yazilmaz.
            using (Stream resource = Assembly.GetExecutingAssembly()
                .GetManifestResourceStream("payload.zip"))
            {
                if (resource == null)
                {
                    throw new Exception("Gomulu paket bulunamadi.");
                }

                using (MemoryStream memory = new MemoryStream())
                {
                    resource.CopyTo(memory);
                    memory.Position = 0;

                    using (ZipArchive archive = new ZipArchive(memory, ZipArchiveMode.Read))
                    {
                        string root = Path.GetFullPath(staging)
                            + Path.DirectorySeparatorChar;

                        foreach (ZipArchiveEntry entry in archive.Entries)
                        {
                            string name = entry.FullName.Replace(
                                '/', Path.DirectorySeparatorChar);
                            if (name.Length == 0)
                            {
                                continue;
                            }

                            string destination = Path.GetFullPath(
                                Path.Combine(staging, name));
                            if (!destination.StartsWith(
                                root, StringComparison.OrdinalIgnoreCase))
                            {
                                throw new Exception(
                                    "Gecersiz paket girdisi: " + entry.FullName);
                            }

                            if (entry.Name.Length == 0)
                            {
                                Directory.CreateDirectory(destination);
                                continue;
                            }

                            string directory = Path.GetDirectoryName(destination);
                            if (!String.IsNullOrEmpty(directory))
                            {
                                Directory.CreateDirectory(directory);
                            }

                            using (Stream source = entry.Open())
                            using (FileStream output = File.Create(destination))
                            {
                                source.CopyTo(output);
                            }
                        }
                    }
                }
            }

            if (Directory.Exists(target))
            {
                try
                {
                    Directory.Delete(target, true);
                }
                catch
                {
                    target = target + "-"
                        + Guid.NewGuid().ToString("N").Substring(0, 8);
                }
            }

            Directory.Move(staging, target);
            return target;
        }
        catch
        {
            try
            {
                if (Directory.Exists(staging))
                {
                    Directory.Delete(staging, true);
                }
            }
            catch
            {
            }
            throw;
        }
    }

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int MessageBoxW(
        IntPtr hWnd,
        string text,
        string caption,
        uint type);
}
