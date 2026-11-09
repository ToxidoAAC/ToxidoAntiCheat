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
            string java = Path.Combine(engine, "runtime", "bin", "javaw.exe");
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
                java = Path.Combine(engine, "runtime", "bin", "javaw.exe");
                app = Path.Combine(engine, "app", "ac-app.jar");
                config = Path.Combine(engine, "app", "client.properties");
                agent = Path.Combine(engine, "app", "ac-agent.jar");
                hook = Path.Combine(engine, "app", "ac-agent-hook.jar");
                input = Path.Combine(engine, "app", "40x1d0AC-input.exe");
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
            info.WindowStyle = ProcessWindowStyle.Hidden;

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

        string temp = Path.Combine(
            Path.GetTempPath(),
            "40x1d0AC-" + Guid.NewGuid().ToString("N") + ".zip");

        try
        {
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

            return target;
        }
        catch
        {
            try { File.Delete(temp); } catch { }
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
