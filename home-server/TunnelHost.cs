using System;
using System.Diagnostics;
using System.IO;
using System.Threading.Tasks;

namespace GalleryServer
{
    // "Access from anywhere": runs gallery-tunnel.exe, which signs in to the owner's Tailscale account and
    // gives the server a public https://gallery.<name>.ts.net address through Tailscale Funnel.
    sealed class TunnelHost : IDisposable
    {
        public static readonly string StateDir = Path.Combine(Settings.Dir, "tailscale");

        public event Action<string> Login, Enable, Ready, Error;   // each gets a URL (or a message for Error)
        public string Url { get; private set; }
        public bool Waiting { get; private set; }   // started, but no address yet
        public bool Interactive;                     // the person just clicked "Turn on", so open sign-in pages for them
        public bool Preparing { get; private set; }  // started early (Connect window open) so the sign-in link is ready
        public string LoginUrl { get; private set; }

        Process proc;
        bool stopping = true;

        public bool On => !stopping;

        public void Start()
        {
            Preparing = false;
            if (On) return;
            stopping = false;
            Waiting = true;
            Launch();
        }

        // Starts it in the background before the person clicks, so the sign-in page opens right away.
        // (The first run of a new exe can take 10+ seconds while antivirus checks it.)
        // Only before the first sign-in: once signed in, starting early would turn access on by itself.
        public void Prepare()
        {
            if (On || Settings.RemoteUrl != null) return;
            Start();
            Preparing = true;
        }

        void Launch()
        {
            var psi = new ProcessStartInfo(Path.Combine(ServerHost.Root, "gallery-tunnel.exe"),
                $"-dir \"{StateDir}\" -hostname gallery -target 127.0.0.1:{Settings.Port}")
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardInput = true,   // it quits when this closes
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            };
            try { proc = Process.Start(psi); }
            catch (Exception e) { Error?.Invoke(e.Message); RetryLater(); return; }
            Job.Add(proc);
            proc.OutputDataReceived += (s, e) => OnLine(e.Data);
            proc.ErrorDataReceived += (s, e) => { };
            proc.BeginOutputReadLine();
            proc.BeginErrorReadLine();
            proc.EnableRaisingEvents = true;
            proc.Exited += (s, e) => { Url = null; if (!stopping) { Waiting = true; RetryLater(); } };
        }

        void OnLine(string line)
        {
            if (string.IsNullOrEmpty(line)) return;
            int i = line.IndexOf(' ');
            string kind = i > 0 ? line.Substring(0, i) : line, rest = i > 0 ? line.Substring(i + 1).Trim() : "";
            switch (kind)
            {
                case "LOGIN": LoginUrl = rest; Login?.Invoke(rest); break;
                case "ENABLE": Enable?.Invoke(rest); break;
                case "URL":
                    if (Preparing) { Stop(); break; }   // already signed in; wait for them to turn it on
                    Url = rest;
                    Waiting = false;
                    LoginUrl = null;
                    Settings.RemoteUrl = rest;
                    Ready?.Invoke(rest);
                    break;
                case "ERR": Error?.Invoke(rest); break;
            }
        }

        void RetryLater() => Task.Delay(10000).ContinueWith(_ => { if (!stopping) Launch(); });

        public void Stop()
        {
            stopping = true;
            Waiting = Preparing = false;
            Url = LoginUrl = null;
            try { if (proc != null && !proc.HasExited) proc.Kill(); } catch { }
        }

        public void Dispose() => Stop();
    }
}
