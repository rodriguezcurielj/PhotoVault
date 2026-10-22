using System;
using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;

namespace GalleryServer
{
    // Runs the Gallery server (node.exe + app\src\server.js) and restarts it if it stops.
    // The node process is put in a job object, so it closes whenever this app closes, even if it's killed.
    sealed class ServerHost : IDisposable
    {
        public static readonly string Root = Environment.GetEnvironmentVariable("GALLERY_SERVER_ROOT") ?? AppDomain.CurrentDomain.BaseDirectory;
        public static readonly string LogFile = Path.Combine(Settings.Dir, "server.log");

        public event Action<bool> StateChanged;   // true = answering on the port
        public bool Running { get; private set; }

        Process proc;
        bool stopping;
        readonly IntPtr job = CreateJob();
        readonly object logLock = new object();
        Timer healthTimer;

        public void Start()
        {
            stopping = false;
            Launch();
            healthTimer = new Timer(_ => CheckHealth(), null, 2000, 5000);
        }

        void Launch()
        {
            var app = Path.Combine(Root, "app");
            var psi = new ProcessStartInfo(Path.Combine(Root, "node", "node.exe"), "src\\server.js")
            {
                WorkingDirectory = app,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            };
            var data = Settings.DataDir;
            psi.EnvironmentVariables["DATA_DIR"] = data;
            psi.EnvironmentVariables["DB_DIR"] = data;
            psi.EnvironmentVariables["PORT"] = Settings.Port.ToString();
            psi.EnvironmentVariables["NODE_ENV"] = "production";
            psi.EnvironmentVariables["NODE_NO_WARNINGS"] = "1";
            psi.EnvironmentVariables["GEO_DIR"] = Path.Combine(app, "geo");
            psi.EnvironmentVariables["PATH"] = Path.Combine(Root, "ffmpeg") + ";" + Environment.GetEnvironmentVariable("PATH");
            // Mail and the Tailscale link read these from .env on budget-pc; a home server can set them in server.env.
            var env = Path.Combine(Settings.Dir, "server.env");
            if (File.Exists(env))
                foreach (var line in File.ReadAllLines(env))
                {
                    int i = line.IndexOf('=');
                    if (i > 0 && !line.TrimStart().StartsWith("#")) psi.EnvironmentVariables[line.Substring(0, i).Trim()] = line.Substring(i + 1).Trim();
                }

            Log($"--- starting server (photos in {data})");
            try
            {
                proc = Process.Start(psi);
            }
            catch (Exception e)
            {
                Log("could not start: " + e.Message);
                RetryLater();
                return;
            }
            AssignProcessToJobObject(job, proc.Handle);
            try { proc.PriorityClass = ProcessPriorityClass.BelowNormal; } catch { }
            proc.OutputDataReceived += (s, e) => { if (e.Data != null) Log(e.Data); };
            proc.ErrorDataReceived += (s, e) => { if (e.Data != null) Log(e.Data); };
            proc.BeginOutputReadLine();
            proc.BeginErrorReadLine();
            proc.EnableRaisingEvents = true;
            proc.Exited += (s, e) =>
            {
                Log($"--- server stopped (exit code {SafeExitCode()})");
                SetRunning(false);
                if (!stopping) RetryLater();
            };
        }

        int SafeExitCode() { try { return proc.ExitCode; } catch { return -1; } }

        void RetryLater() => Task.Delay(5000).ContinueWith(_ => { if (!stopping) Launch(); });

        static readonly HttpClient http = new HttpClient { Timeout = TimeSpan.FromSeconds(4) };
        async void CheckHealth()
        {
            bool ok;
            try { ok = (await http.GetAsync($"http://127.0.0.1:{Settings.Port}/api/health")).IsSuccessStatusCode; }
            catch { ok = false; }
            SetRunning(ok);
        }

        void SetRunning(bool ok)
        {
            if (ok == Running) return;
            Running = ok;
            StateChanged?.Invoke(ok);
        }

        public void Restart()
        {
            // Killing it lets the Exited handler start it again.
            try { if (proc != null && !proc.HasExited) proc.Kill(); else Launch(); } catch { }
        }

        void Log(string line)
        {
            lock (logLock)
            {
                try
                {
                    Directory.CreateDirectory(Settings.Dir);
                    var fi = new FileInfo(LogFile);
                    if (fi.Exists && fi.Length > 5 * 1024 * 1024)
                    {
                        File.Copy(LogFile, LogFile + ".old", true);
                        File.WriteAllText(LogFile, "");
                    }
                    File.AppendAllText(LogFile, $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} {line}\r\n");
                }
                catch { }
            }
        }

        public void Dispose()
        {
            stopping = true;
            healthTimer?.Dispose();
            try { if (proc != null && !proc.HasExited) proc.Kill(); } catch { }
            CloseHandle(job);
        }

        // ----- job object: child processes die with this app -----
        static IntPtr CreateJob()
        {
            var h = CreateJobObject(IntPtr.Zero, null);
            var info = new JOBOBJECT_EXTENDED_LIMIT_INFORMATION();
            info.BasicLimitInformation.LimitFlags = 0x2000; // JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
            int len = Marshal.SizeOf(typeof(JOBOBJECT_EXTENDED_LIMIT_INFORMATION));
            var ptr = Marshal.AllocHGlobal(len);
            Marshal.StructureToPtr(info, ptr, false);
            SetInformationJobObject(h, 9 /* ExtendedLimitInformation */, ptr, (uint)len);
            Marshal.FreeHGlobal(ptr);
            return h;
        }

        [StructLayout(LayoutKind.Sequential)]
        struct JOBOBJECT_BASIC_LIMIT_INFORMATION
        {
            public long PerProcessUserTimeLimit, PerJobUserTimeLimit;
            public uint LimitFlags;
            public UIntPtr MinimumWorkingSetSize, MaximumWorkingSetSize;
            public uint ActiveProcessLimit;
            public UIntPtr Affinity;
            public uint PriorityClass, SchedulingClass;
        }
        [StructLayout(LayoutKind.Sequential)]
        struct IO_COUNTERS { public ulong a, b, c, d, e, f; }
        [StructLayout(LayoutKind.Sequential)]
        struct JOBOBJECT_EXTENDED_LIMIT_INFORMATION
        {
            public JOBOBJECT_BASIC_LIMIT_INFORMATION BasicLimitInformation;
            public IO_COUNTERS IoInfo;
            public UIntPtr ProcessMemoryLimit, JobMemoryLimit, PeakProcessMemoryUsed, PeakJobMemoryUsed;
        }
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern IntPtr CreateJobObject(IntPtr a, string name);
        [DllImport("kernel32.dll")] static extern bool SetInformationJobObject(IntPtr job, int cls, IntPtr info, uint len);
        [DllImport("kernel32.dll")] static extern bool AssignProcessToJobObject(IntPtr job, IntPtr proc);
        [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr h);
    }
}
