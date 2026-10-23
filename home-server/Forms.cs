using System;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Windows.Forms;
using QRCoder;

namespace GalleryServer
{
    static class Ui
    {
        public static readonly Font Body = new Font("Segoe UI", 10f);
        public static readonly Font Title = new Font("Segoe UI Semibold", 15f);
        public static readonly Font Big = new Font("Segoe UI Semibold", 13f);
        public static readonly Color Muted = Color.FromArgb(96, 96, 104);
        public static readonly Color Blue = Color.FromArgb(0, 122, 255);

        public static void Style(Form f, string title)
        {
            f.Text = title;
            f.Icon = Program.AppIcon;
            f.Font = Body;
            f.BackColor = Color.White;
            f.FormBorderStyle = FormBorderStyle.FixedDialog;
            f.MaximizeBox = false;
            f.StartPosition = FormStartPosition.CenterScreen;
            f.AutoScaleMode = AutoScaleMode.Dpi;
            f.AutoScaleDimensions = new SizeF(96f, 96f);
        }

        public static Label Text(string text, Font font = null, Color? color = null, int width = 460) =>
            new Label { Text = text, Font = font ?? Body, ForeColor = color ?? Color.Black, AutoSize = true, MaximumSize = new Size(width, 0), Margin = new Padding(0, 0, 0, 10) };

        public static Button Primary(string text)
        {
            var b = new Button { Text = text, AutoSize = true, Padding = new Padding(14, 4, 14, 4), BackColor = Blue, ForeColor = Color.White, FlatStyle = FlatStyle.Flat, Font = new Font("Segoe UI Semibold", 10f) };
            b.FlatAppearance.BorderSize = 0;
            return b;
        }
    }

    // First run: where the photos go.
    sealed class SetupForm : Form
    {
        readonly TextBox folder = new TextBox { Width = 360 };
        readonly Label space = Ui.Text("", null, Ui.Muted);

        public SetupForm()
        {
            Ui.Style(this, "Set up Gallery Server");
            var col = new FlowLayoutPanel { FlowDirection = FlowDirection.TopDown, WrapContents = false, AutoSize = true, Padding = new Padding(24, 20, 24, 20) };
            col.Controls.Add(new PictureBox { Image = Program.Logo, SizeMode = PictureBoxSizeMode.Zoom, Size = new Size(48, 48), Margin = new Padding(0, 0, 0, 8) });
            col.Controls.Add(Ui.Text("Welcome to Gallery Server", Ui.Title));
            col.Controls.Add(Ui.Text("This PC will keep your photos and videos, and your phone backs up to it. Your photos stay at home on this PC, not in someone else's cloud."));
            col.Controls.Add(Ui.Text("Where should Gallery keep your photos?", Ui.Big));
            col.Controls.Add(Ui.Text("Pick a drive with plenty of space. You can find them later from the tray icon.", null, Ui.Muted));

            var row = new FlowLayoutPanel { AutoSize = true, WrapContents = false, Margin = new Padding(0, 0, 0, 4) };
            folder.Text = Settings.SuggestedDataDir();
            folder.TextChanged += (s, e) => ShowSpace();
            var browse = new Button { Text = "Browse…", AutoSize = true };
            browse.Click += (s, e) =>
            {
                using (var d = new FolderBrowserDialog { Description = "Pick a folder for your photos", ShowNewFolderButton = true })
                {
                    if (Directory.Exists(folder.Text)) d.SelectedPath = folder.Text;
                    if (d.ShowDialog(this) == DialogResult.OK)
                        folder.Text = Path.GetFileName(d.SelectedPath).Equals("Gallery", StringComparison.OrdinalIgnoreCase) || Directory.EnumerateFileSystemEntries(d.SelectedPath).Any() == false
                            ? d.SelectedPath : Path.Combine(d.SelectedPath, "Gallery");
                }
            };
            row.Controls.Add(folder);
            row.Controls.Add(browse);
            col.Controls.Add(row);
            col.Controls.Add(space);

            var start = Ui.Primary("Start Gallery Server");
            start.Margin = new Padding(0, 12, 0, 0);
            start.Click += (s, e) => Finish();
            col.Controls.Add(start);
            AcceptButton = start;

            Controls.Add(col);
            AutoSize = true;
            AutoSizeMode = AutoSizeMode.GrowAndShrink;
            ShowSpace();
        }

        void ShowSpace()
        {
            try
            {
                var d = new DriveInfo(Path.GetPathRoot(Path.GetFullPath(folder.Text)));
                space.Text = $"{d.AvailableFreeSpace / 1e9:0} GB free on {d.Name.TrimEnd('\\')}";
            }
            catch { space.Text = ""; }
        }

        void Finish()
        {
            string path;
            try
            {
                path = Path.GetFullPath(folder.Text.Trim());
                Directory.CreateDirectory(path);
                File.WriteAllText(Path.Combine(path, ".gallery-test"), "");
                File.Delete(Path.Combine(path, ".gallery-test"));
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, "Gallery can't save to that folder:\n" + ex.Message, "Gallery Server", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }
            Settings.DataDir = path;
            DialogResult = DialogResult.OK;
        }
    }

    // Shows the address and a QR code to scan with the Gallery app, and turns on access from anywhere.
    sealed class ConnectForm : Form
    {
        readonly TunnelHost tunnel;
        readonly PictureBox qr = new PictureBox { Size = new Size(220, 220), SizeMode = PictureBoxSizeMode.Zoom, Margin = new Padding(0, 4, 0, 8) };
        readonly Label address = Ui.Text("", Ui.Big, Ui.Blue);
        readonly Label reach = Ui.Text("");
        readonly Label others = Ui.Text("", null, Ui.Muted);
        readonly Label state = Ui.Text("", null, Ui.Muted);
        readonly Label sleepWarning = Ui.Text("", null, Color.FromArgb(176, 96, 0));
        readonly FlowLayoutPanel remoteBox = new FlowLayoutPanel { FlowDirection = FlowDirection.TopDown, WrapContents = false, AutoSize = true, BackColor = Color.FromArgb(242, 246, 255), Padding = new Padding(14, 12, 14, 12), Margin = new Padding(0, 4, 0, 10) };
        readonly Label remoteStatus = Ui.Text("", null, null, 430);
        readonly LinkLabel remoteLink = new LinkLabel { AutoSize = true, Margin = new Padding(0, 0, 0, 4), Visible = false };
        readonly FlowLayoutPanel choose = new FlowLayoutPanel { FlowDirection = FlowDirection.TopDown, WrapContents = false, AutoSize = true, Margin = new Padding(0) };
        readonly LinkLabel turnOff = new LinkLabel { Text = "Turn off access from anywhere", AutoSize = true, Margin = new Padding(0, 0, 0, 8) };
        readonly ProgressBar busy = new ProgressBar { Style = ProgressBarStyle.Marquee, MarqueeAnimationSpeed = 30, Width = 430, Height = 6, Margin = new Padding(0, 2, 0, 10) };
        string pendingUrl, stepText;

        public ConnectForm(TunnelHost tunnel)
        {
            this.tunnel = tunnel;
            Ui.Style(this, "Connect a phone to Gallery Server");
            var col = new FlowLayoutPanel { FlowDirection = FlowDirection.TopDown, WrapContents = false, AutoSize = true, Padding = new Padding(24, 20, 24, 20) };
            col.Controls.Add(Ui.Text("Connect your phone", Ui.Title));
            col.Controls.Add(Ui.Text("1.  Install the Gallery app on your phone."));
            col.Controls.Add(Ui.Text("2.  In the app, tap Sign In, then scan this code or type the address under Server address."));
            col.Controls.Add(Ui.Text("3.  Sign in with your Gallery username and password. New here? Open Gallery on this PC first to make them."));
            col.Controls.Add(qr);
            col.Controls.Add(address);
            col.Controls.Add(reach);
            col.Controls.Add(others);
            col.Controls.Add(sleepWarning);

            remoteBox.Controls.Add(Ui.Text("Use it on any network", Ui.Big, null, 430));
            remoteBox.Controls.Add(Ui.Text("Let your phone reach Gallery from anywhere: school or work Wi-Fi, cell data, or a trip. It's free and uses Tailscale, a secure connection service.", null, null, 430));
            choose.Controls.Add(Ui.Text("Do you have a Tailscale account?", new Font("Segoe UI Semibold", 10f), null, 430));
            var isNew = Ui.Primary("I'm new: make a free account");
            isNew.Margin = new Padding(0, 0, 0, 6);
            isNew.Click += (s, e) => TurnOn(
                "Finish in your browser:\n" +
                "1.  Pick Google, Microsoft, Apple or GitHub to make your free Tailscale account.\n" +
                "2.  If it asks a few questions about how you'll use it, answer them or skip.\n" +
                "3.  When it shows this PC (\"gallery\"), click Connect.\n" +
                "Don't download or install the Tailscale app. If Tailscale offers it, skip it or close that page. Gallery Server already has it built in.");
            var hasOne = new Button { Text = "I have one: sign in", AutoSize = true, Padding = new Padding(14, 4, 14, 4), Font = new Font("Segoe UI Semibold", 10f), BackColor = Color.White, FlatStyle = FlatStyle.Flat, ForeColor = Ui.Blue };
            hasOne.FlatAppearance.BorderColor = Ui.Blue;
            hasOne.Click += (s, e) => TurnOn(
                "Finish in your browser:\n" +
                "1.  Sign in the way you usually sign in to Tailscale (the same Google, Microsoft, Apple or GitHub account).\n" +
                "2.  When it shows this PC (\"gallery\"), click Connect.\n" +
                "Don't download or install anything. Gallery Server already has Tailscale built in.");
            choose.Controls.Add(isNew);
            choose.Controls.Add(hasOne);
            choose.Controls.Add(Ui.Text("You only need an account. You don't need to install the Tailscale app, on this PC or on your phone.", null, Ui.Muted, 430));
            remoteBox.Controls.Add(choose);
            remoteBox.Controls.Add(busy);
            remoteBox.Controls.Add(remoteStatus);
            remoteLink.LinkClicked += (s, e) => { if (pendingUrl != null) Program.OpenUrl(pendingUrl); };
            remoteBox.Controls.Add(remoteLink);
            col.Controls.Add(remoteBox);
            turnOff.LinkClicked += (s, e) =>
            {
                Settings.Remote = false;
                tunnel.Stop();
                Fill();
            };
            col.Controls.Add(turnOff);
            col.Controls.Add(state);

            var row = new FlowLayoutPanel { AutoSize = true, WrapContents = false, Margin = new Padding(0, 6, 0, 0) };
            var copy = new Button { Text = "Copy address", AutoSize = true };
            copy.Click += (s, e) => { if (address.Text.StartsWith("http")) Clipboard.SetText(address.Text); };
            var refresh = new Button { Text = "Refresh", AutoSize = true };
            refresh.Click += (s, e) => Fill();
            var done = Ui.Primary("Done");
            done.Click += (s, e) => Close();
            row.Controls.Add(done);
            row.Controls.Add(copy);
            row.Controls.Add(refresh);
            col.Controls.Add(row);

            Controls.Add(col);
            // Draw in one go and keep the top edge still while parts show and hide, so nothing jumps around.
            DoubleBuffered = true;
            AutoSize = true;
            AutoSizeMode = AutoSizeMode.GrowAndShrink;
            Fill();
        }

        public void Fill()
        {
            if (IsDisposed) return;
            SuspendLayout();
            try { FillNow(); } finally { ResumeLayout(true); }
            // An auto-sized form grows but doesn't always shrink when parts hide; make it fit again.
            AutoSize = false;
            AutoSize = true;
        }

        void FillNow()
        {
            var lan = Net.Addresses().Select(Net.ServerUrl).ToList();
            var remote = tunnel.Url;
            string url;
            if (remote != null)
            {
                url = remote;
                reach.Text = "✓ Works on any network: Wi-Fi, cell data, anywhere.";
                reach.ForeColor = Color.FromArgb(30, 150, 70);
                others.Text = lan.Count > 0 ? "On the same Wi-Fi as this PC you can also use " + lan[0] : "";
            }
            else
            {
                url = lan.FirstOrDefault();
                reach.Text = url == null ? "No network found. Connect this PC to Wi-Fi or a router, then tap Refresh." : "Works only when the phone is on the same Wi-Fi as this PC.";
                reach.ForeColor = Ui.Muted;
                others.Text = lan.Count > 1 ? "If that doesn't work, try: " + string.Join(", ", lan.Skip(1)) : "";
            }
            others.Visible = others.Text.Length > 0;
            var warn = Power.Warning();
            sleepWarning.Text = warn == null ? "" : "⚠ " + warn;
            sleepWarning.Visible = warn != null;
            address.Text = url ?? "";
            remoteBox.Visible = remote == null;
            bool started = tunnel.On && !tunnel.Preparing;
            choose.Visible = !started;
            remoteStatus.Visible = started;
            remoteLink.Visible = started && pendingUrl != null;
            busy.Visible = started;
            turnOff.Visible = started;
            // Only redraw the code when the address changes.
            if (url == qrUrl) return;
            qrUrl = url;
            qr.Image = null;
            if (url == null) return;
            using (var gen = new QRCodeGenerator())
            using (var data = gen.CreateQrCode(url, QRCodeGenerator.ECCLevel.M))
            using (var code = new QRCode(data))
                qr.Image = code.GetGraphic(10, Color.Black, Color.White, true);
        }
        string qrUrl = "";

        // What the tunnel is doing after the person signed in.
        public void ShowProgress(string step)
        {
            if (IsDisposed) return;
            switch (step)
            {
                case "signedin":
                    ShowStep("✓ Signed in to Tailscale.\nSetting up your address…");
                    break;
                case "cert":
                    ShowStep("✓ Signed in to Tailscale.\nMaking your secure https address. The first time, this takes about a minute. " +
                             "You can close this window; it keeps going and tells you when it's ready.");
                    break;
                case "publish":
                    ShowStep("✓ Signed in to Tailscale.\n✓ Secure address made.\nAlmost ready: putting your address online. This can take up to a minute.");
                    break;
                case "certerror":
                    ShowStep("✓ Signed in to Tailscale.\nStill making your secure address. Tailscale is slow right now, so it will keep trying.");
                    break;
            }
        }

        void TurnOn(string howTo)
        {
            Settings.Remote = true;
            stepText = howTo;
            tunnel.Interactive = true;
            var ready = tunnel.LoginUrl;
            tunnel.Start();
            if (ready != null)
            {
                Program.OpenUrl(ready);   // already waiting, opens right away
                ShowStep(null, ready);
            }
            else ShowStep("Connecting…");   // the sign-in steps show when Tailscale sends the page (or it's done if already signed in)
            Fill();
        }

        // Steps of turning on access from anywhere, from the tunnel. text null = the sign-in instructions.
        public void ShowStep(string text, string link = null)
        {
            if (IsDisposed) return;
            SuspendLayout();
            remoteStatus.Text = text ?? stepText ?? "Sign in to Tailscale in your browser to finish.";
            if (link != null || text != null) pendingUrl = link;
            remoteLink.Text = pendingUrl == null ? "" : "Browser didn't open? Click here";
            remoteLink.Visible = pendingUrl != null && tunnel.On && !tunnel.Preparing;
            ResumeLayout(true);
        }

        public void SetRunning(bool ok)
        {
            if (IsDisposed) return;
            state.Text = ok ? "● Gallery Server is running" : "○ Gallery Server is starting…";
            state.ForeColor = ok ? Color.FromArgb(30, 150, 70) : Ui.Muted;
        }
    }
}
