using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Win32;

namespace GalleryServer
{
    static class Program
    {
        const string ShowEventName = "GalleryServer.ShowConnect";
        public static Icon AppIcon;
        public static Image Logo;

        [STAThread]
        static void Main(string[] args)
        {
            // One copy at a time. Opening it again shows the "Connect a phone" window of the one that's running.
            using (var mutex = new Mutex(true, "GalleryServer.SingleInstance", out bool first))
            {
                if (!first)
                {
                    try { EventWaitHandle.OpenExisting(ShowEventName).Set(); } catch { }
                    return;
                }
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                using (var s = typeof(Program).Assembly.GetManifestResourceStream("gallery.ico")) AppIcon = new Icon(s);
                Logo = Image.FromStream(typeof(Program).Assembly.GetManifestResourceStream("gallery.png"));
                Application.ThreadException += (o, e) => Crash(e.Exception);
                AppDomain.CurrentDomain.UnhandledException += (o, e) => Crash(e.ExceptionObject as Exception);

                bool firstRun = Settings.DataDir == null;
                if (firstRun)
                {
                    using (var setup = new SetupForm())
                        if (setup.ShowDialog() != DialogResult.OK) return;
                    Startup.Enabled = true;
                }
                Directory.CreateDirectory(Settings.DataDir);
                Application.Run(new TrayApp(firstRun, new EventWaitHandle(false, EventResetMode.AutoReset, ShowEventName)));
            }
        }

        public static void OpenUrl(string url)
        {
            try { Process.Start(new ProcessStartInfo(url) { UseShellExecute = true }); } catch { }
        }

        static void Crash(Exception e)
        {
            try
            {
                Directory.CreateDirectory(Settings.Dir);
                File.AppendAllText(Path.Combine(Settings.Dir, "error.log"), $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} {e}\r\n");
            }
            catch { }
            MessageBox.Show("Gallery Server ran into a problem:\n" + e?.Message, "Gallery Server", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    // "Start with Windows": a Run entry for this user.
    static class Startup
    {
        const string Key = @"Software\Microsoft\Windows\CurrentVersion\Run", Name = "Gallery Server";
        public static bool Enabled
        {
            get { using (var k = Registry.CurrentUser.OpenSubKey(Key)) return k?.GetValue(Name) != null; }
            set
            {
                using (var k = Registry.CurrentUser.CreateSubKey(Key))
                    if (value) k.SetValue(Name, $"\"{Application.ExecutablePath}\" --background");
                    else k.DeleteValue(Name, false);
            }
        }
    }

    sealed class TrayApp : ApplicationContext
    {
        readonly NotifyIcon tray;
        readonly ServerHost host = new ServerHost();
        readonly TunnelHost tunnel = new TunnelHost();
        readonly ToolStripMenuItem status, awake;
        readonly Control ui = new Control();
        ConnectForm connect;
        bool openWhenReady;

        public TrayApp(bool firstRun, EventWaitHandle showEvent)
        {
            ui.CreateControl();
            var menu = new ContextMenuStrip();
            status = new ToolStripMenuItem("Starting…") { Enabled = false };
            menu.Items.Add(status);
            menu.Items.Add(new ToolStripSeparator());
            var open = new ToolStripMenuItem("Open Gallery", null, (s, e) => OpenGallery());
            open.Font = new Font(open.Font, FontStyle.Bold);
            menu.Items.Add(open);
            menu.Items.Add("Connect a phone…", null, (s, e) => ShowConnect());
            menu.Items.Add("Open photos folder", null, (s, e) => Process.Start("explorer.exe", $"\"{Path.Combine(Settings.DataDir, "library")}\""));
            menu.Items.Add(new ToolStripSeparator());
            var startup = new ToolStripMenuItem("Start with Windows") { Checked = Startup.Enabled, CheckOnClick = true };
            startup.CheckedChanged += (s, e) => Startup.Enabled = startup.Checked;
            menu.Items.Add(startup);
            awake = new ToolStripMenuItem("Keep this PC awake") { Checked = Settings.KeepAwake, CheckOnClick = true,
                ToolTipText = Power.ModernStandby
                    ? "While plugged in, this PC (and its screen) won't go to sleep on its own, so your phone can always reach Gallery. On battery it sleeps as usual."
                    : "While plugged in, this PC won't go to sleep on its own, so your phone can always reach Gallery." };
            awake.CheckedChanged += (s, e) => { Settings.KeepAwake = awake.Checked; Power.Apply(); connect?.Fill(); };
            menu.Items.Add(awake);
            menu.Items.Add("Restart server", null, (s, e) => host.Restart());
            menu.Items.Add("Show log", null, (s, e) => { if (File.Exists(ServerHost.LogFile)) Process.Start("notepad.exe", $"\"{ServerHost.LogFile}\""); });
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("Quit Gallery Server", null, (s, e) => Quit());

            tray = new NotifyIcon { Icon = Program.AppIcon, Text = "Gallery Server", ContextMenuStrip = menu, Visible = true };
            tray.DoubleClick += (s, e) => OpenGallery();

            host.StateChanged += ok => ui.BeginInvoke((Action)(() => OnState(ok)));
            host.Start();
            Power.Init(ui);
            Power.Changed += () => connect?.Fill();
            Net.Watch();
            Task.Run(() => Net.Addresses());   // look it up now, so the Connect window opens without waiting
            Net.Changed += () => ui.BeginInvoke((Action)(() => connect?.Fill()));
            WireTunnel();
            if (Settings.Remote) tunnel.Start();

            // The installer and the Start menu open it without --background; show the phone setup then.
            bool background = Environment.GetCommandLineArgs().Length > 1 && Environment.GetCommandLineArgs()[1] == "--background";
            if (firstRun) openWhenReady = true;
            else if (!background) ui.BeginInvoke((Action)ShowConnect);

            new Thread(() => { while (showEvent.WaitOne()) ui.BeginInvoke((Action)ShowConnect); }) { IsBackground = true }.Start();
        }

        void OnState(bool ok)
        {
            status.Text = ok ? $"Running on port {Settings.Port}" : "Starting…";
            tray.Text = ok ? "Gallery Server: running" : "Gallery Server: starting";
            connect?.SetRunning(ok);
            if (ok && openWhenReady)
            {
                openWhenReady = false;
                // First time: make the account in the browser, then connect the phone.
                OpenGallery();
                ShowConnect();
                tray.ShowBalloonTip(8000, "Gallery Server is running", "Create your account in the browser, then connect your phone. Gallery Server stays down here by the clock.", ToolTipIcon.Info);
            }
        }

        void OpenGallery() => Program.OpenUrl($"http://localhost:{Settings.Port}/");

        // Access from anywhere: open the Tailscale pages when the person just turned it on; otherwise tell them.
        void WireTunnel()
        {
            string balloonUrl = null;
            tray.BalloonTipClicked += (s, e) => { if (balloonUrl != null) Program.OpenUrl(balloonUrl); };
            tunnel.Login += url => ui.BeginInvoke((Action)(() =>
            {
                if (tunnel.Preparing) return;   // ready for when they click
                connect?.ShowStep(null, url);
                if (tunnel.Interactive) Program.OpenUrl(url);
                else
                {
                    balloonUrl = url;
                    tray.ShowBalloonTip(15000, "Sign in to keep access from anywhere", "Your phone can only reach Gallery at home until you sign in to Tailscale again. Click here.", ToolTipIcon.Warning);
                }
            }));
            tunnel.Enable += url => ui.BeginInvoke((Action)(() =>
            {
                if (tunnel.Preparing) return;
                connect?.ShowStep("One more step: in your browser, click Enable to turn on the public address (Tailscale Funnel).", url);
                if (tunnel.Interactive) Program.OpenUrl(url);
                else { balloonUrl = url; tray.ShowBalloonTip(15000, "Gallery Server needs one click", "Turn on Tailscale Funnel so your phone can reach Gallery from anywhere. Click here.", ToolTipIcon.Warning); }
            }));
            tunnel.Step += step => ui.BeginInvoke((Action)(() => connect?.ShowProgress(step)));
            tunnel.Ready += url => ui.BeginInvoke((Action)(() =>
            {
                connect?.Fill();
                if (tunnel.Interactive)
                {
                    balloonUrl = null;
                    tray.ShowBalloonTip(8000, "Access from anywhere is on", $"Your phone can now reach Gallery on any network at {url}", ToolTipIcon.Info);
                }
                tunnel.Interactive = false;
            }));
            // Errors usually fix themselves (the tunnel retries); only a certificate problem needs the person.
            tunnel.Error += msg => ui.BeginInvoke((Action)(() => { if (tunnel.Waiting && !tunnel.Preparing && msg.StartsWith("certificate")) connect?.ShowProgress("certerror"); }));
        }

        void ShowConnect()
        {
            if (connect == null || connect.IsDisposed)
            {
                connect = new ConnectForm(tunnel);
                if (!Settings.Remote) tunnel.Prepare();
                connect.SetRunning(host.Running);
                connect.Show();
            }
            connect.WindowState = FormWindowState.Normal;
            connect.Activate();
        }


        void Quit()
        {
            if (MessageBox.Show("Quit Gallery Server?\n\nYour phone won't be able to back up or show your photos until it's running again.",
                    "Gallery Server", MessageBoxButtons.OKCancel, MessageBoxIcon.Question) != DialogResult.OK) return;
            tray.Visible = false;
            host.Dispose();
            tunnel.Dispose();
            ExitThread();
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing) { tray.Dispose(); host.Dispose(); tunnel.Dispose(); }
            base.Dispose(disposing);
        }

    }
}
