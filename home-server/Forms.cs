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

    // Shows the address and a QR code to scan with the Gallery app.
    sealed class ConnectForm : Form
    {
        readonly PictureBox qr = new PictureBox { Size = new Size(220, 220), SizeMode = PictureBoxSizeMode.Zoom, Margin = new Padding(0, 4, 0, 8) };
        readonly Label address = Ui.Text("", Ui.Title, Ui.Blue);
        readonly Label state = Ui.Text("", null, Ui.Muted);
        readonly Label others = Ui.Text("", null, Ui.Muted);

        public ConnectForm()
        {
            Ui.Style(this, "Connect a phone to Gallery Server");
            var col = new FlowLayoutPanel { FlowDirection = FlowDirection.TopDown, WrapContents = false, AutoSize = true, Padding = new Padding(24, 20, 24, 20) };
            col.Controls.Add(Ui.Text("Connect your phone", Ui.Title));
            col.Controls.Add(Ui.Text("1.  Install the Gallery app on your phone."));
            col.Controls.Add(Ui.Text("2.  Make sure the phone is on the same Wi-Fi as this PC."));
            col.Controls.Add(Ui.Text("3.  In the app, tap Sign In, then scan this code or type the address under Server address."));
            col.Controls.Add(qr);
            col.Controls.Add(address);
            col.Controls.Add(others);
            col.Controls.Add(state);
            col.Controls.Add(Ui.Text("New here? Open Gallery on this PC first to create your username and password.", null, Ui.Muted));

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
            AutoSize = true;
            AutoSizeMode = AutoSizeMode.GrowAndShrink;
            Fill();
        }

        void Fill()
        {
            var ips = Net.LanAddresses();
            if (ips.Count == 0)
            {
                address.Text = "No network found";
                others.Text = "Connect this PC to your home Wi-Fi or router, then tap Refresh.";
                qr.Image = null;
                return;
            }
            var url = Net.ServerUrl(ips[0]);
            address.Text = url;
            others.Text = ips.Count > 1 ? "If that doesn't work, try: " + string.Join(", ", ips.Skip(1).Select(Net.ServerUrl)) : "";
            using (var gen = new QRCodeGenerator())
            using (var data = gen.CreateQrCode(url, QRCodeGenerator.ECCLevel.M))
            using (var code = new QRCode(data))
                qr.Image = code.GetGraphic(10, Color.Black, Color.White, true);
        }

        public void SetRunning(bool ok)
        {
            if (IsDisposed) return;
            state.Text = ok ? "● Gallery Server is running" : "○ Gallery Server is starting…";
            state.ForeColor = ok ? Color.FromArgb(30, 150, 70) : Ui.Muted;
        }
    }
}
