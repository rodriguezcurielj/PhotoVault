using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Shell;
using System.Windows.Threading;
using Microsoft.Win32;

namespace Gallery;

public partial class MainWindow : Window
{
    readonly Settings settings = Settings.Load();
    readonly Api api = new();
    readonly UploadedIndex index = new();
    readonly DispatcherTimer ticker = new() { Interval = TimeSpan.FromMilliseconds(250) };
    Uploader? job;
    CancellationTokenSource? cts;
    bool running;
    // Upload speed, smoothed over the last few seconds.
    long lastBytes; DateTime lastTick; double speed;

    /// <summary>Folders/files dropped on Gallery.exe (or passed on the command line) start uploading after sign-in.</summary>
    readonly string[] startPaths = Environment.GetCommandLineArgs().Skip(1).Where(p => System.IO.Directory.Exists(p) || System.IO.File.Exists(p)).ToArray();

    public MainWindow()
    {
        InitializeComponent();
        api.Server = settings.Server;
        api.Token = settings.Token;
        ServerBox.Text = settings.Server;
        UserBox.Text = settings.Username;
        ParallelBox.SelectedIndex = settings.Parallel switch { 1 => 0, 2 => 1, 6 => 3, 8 => 4, _ => 2 };
        ticker.Tick += (_, _) => Refresh();
        Loaded += async (_, _) =>
        {
            if (api.Token == null) { ShowLogin(); return; }
            try { await ShowMain(); }
            catch (ApiException e) when (e.Status == 401) { ShowLogin(); }
            catch { await ShowMain(offline: true); }
        };
        Closing += (s, e) =>
        {
            if (running && MessageBox.Show(this, "Uploads are still running. Stop them and close?", "Gallery", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes)
                e.Cancel = true;
            else cts?.Cancel();
        };
    }

    // ---------------------------------------------------------------- sign in

    void ShowLogin(string? error = null)
    {
        LoginView.Visibility = Visibility.Visible;
        MainView.Visibility = Visibility.Collapsed;
        LoginError.Text = error ?? "";
        (string.IsNullOrEmpty(UserBox.Text) ? (Control)UserBox : PwBox).Focus();
    }

    async Task ShowMain(bool offline = false)
    {
        LoginView.Visibility = Visibility.Collapsed;
        MainView.Visibility = Visibility.Visible;
        AccountText.Text = settings.Username;
        if (!offline)
        {
            var (name, email) = await api.Profile();
            AccountText.Text = string.IsNullOrEmpty(email) ? name : $"{name} · {email}";
        }
        if (startPaths.Length > 0 && job == null) Start(startPaths);
    }

    void Pw_KeyDown(object sender, System.Windows.Input.KeyEventArgs e) { if (e.Key == System.Windows.Input.Key.Enter) SignIn_Click(sender, e); }

    async void SignIn_Click(object sender, RoutedEventArgs e)
    {
        SignInBtn.IsEnabled = false;
        LoginError.Text = "";
        try
        {
            var server = ServerBox.Text.Trim().TrimEnd('/');
            if (!server.StartsWith("http")) server = "https://" + server;
            var problem = await api.Health(server);
            if (problem != null) { LoginError.Text = problem; return; }
            api.Server = server;
            api.Token = await api.Login(UserBox.Text, PwBox.Password);
            settings.Server = server;
            settings.Username = UserBox.Text.Trim();
            settings.Token = api.Token;
            settings.Save();
            PwBox.Clear();
            await ShowMain();
        }
        catch (Exception ex) { LoginError.Text = ex.Message; }
        finally { SignInBtn.IsEnabled = true; }
    }

    async void SignOut_Click(object sender, RoutedEventArgs e)
    {
        if (running) { MessageBox.Show(this, "Cancel the uploads first.", "Gallery"); return; }
        await api.Logout();
        api.Token = null;
        settings.Token = null;
        settings.Save();
        ShowLogin();
    }

    void Open_Click(object sender, RoutedEventArgs e) => Process.Start(new ProcessStartInfo(api.Server) { UseShellExecute = true });

    void Parallel_Changed(object sender, SelectionChangedEventArgs e)
    {
        if (ParallelBox.SelectedItem is ComboBoxItem { Content: string s }) { settings.Parallel = int.Parse(s); settings.Save(); }
    }

    // ---------------------------------------------------------------- choosing files

    void Drop_DragEnter(object sender, DragEventArgs e)
    {
        e.Effects = e.Data.GetDataPresent(DataFormats.FileDrop) && !running ? DragDropEffects.Copy : DragDropEffects.None;
        DashRect.Stroke = (Brush)FindResource("Accent");
    }
    void Drop_DragLeave(object sender, DragEventArgs e) => DashRect.Stroke = new SolidColorBrush(Color.FromRgb(0xC7, 0xC7, 0xCC));
    void Drop_Drop(object sender, DragEventArgs e)
    {
        Drop_DragLeave(sender, e);
        if (e.Data.GetData(DataFormats.FileDrop) is string[] paths) Start(paths);
    }

    void ChooseFolder_Click(object sender, RoutedEventArgs e)
    {
        var d = new OpenFolderDialog { Title = "Choose folders to upload", Multiselect = true };
        if (d.ShowDialog(this) == true) Start(d.FolderNames);
    }

    void ChooseFiles_Click(object sender, RoutedEventArgs e)
    {
        var d = new OpenFileDialog
        {
            Title = "Choose photos and videos", Multiselect = true,
            Filter = "Photos and videos|*.jpg;*.jpeg;*.png;*.gif;*.webp;*.heic;*.heif;*.avif;*.bmp;*.tif;*.tiff;*.dng;*.mp4;*.mov;*.m4v;*.3gp;*.webm;*.mkv;*.avi|All files|*.*",
        };
        if (d.ShowDialog(this) == true) Start(d.FileNames);
    }

    // ---------------------------------------------------------------- uploading

    async void Start(IEnumerable<string> paths)
    {
        if (running) { MessageBox.Show(this, "Wait for the current upload to finish, or cancel it.", "Gallery"); return; }
        job = new Uploader(api, index);
        await RunJob(c => job.Run(paths.ToList(), settings.Parallel, c));
    }

    async void Retry_Click(object sender, RoutedEventArgs e)
    {
        if (job == null || running) return;
        await RunJob(c => job.RetryFailed(settings.Parallel, c));
    }

    async Task RunJob(Func<CancellationToken, Task> work)
    {
        running = true;
        cts = new CancellationTokenSource();
        ProgressCard.Visibility = Visibility.Visible;
        RetryBtn.Visibility = Visibility.Collapsed;
        PauseBtn.Visibility = CancelBtn.Visibility = Visibility.Visible;
        PauseBtn.Content = "Pause";
        FolderBtn.IsEnabled = FilesBtn.IsEnabled = false;
        lastBytes = job!.BytesSent; lastTick = DateTime.UtcNow; speed = 0;
        KeepAwake(true);
        ticker.Start();
        try { await Task.Run(() => work(cts.Token)); }
        catch (OperationCanceledException) { job.Phase = "Stopped"; }
        catch (ApiException ex) when (ex.Status == 401) { job.Phase = "Signed out"; ShowLogin(job.FatalError ?? "Sign in again to keep uploading."); }
        catch (Exception ex) { job.Phase = "Something went wrong: " + ex.Message; }
        finally
        {
            running = false;
            ticker.Stop();
            KeepAwake(false);
            Refresh();
            PauseBtn.Visibility = CancelBtn.Visibility = Visibility.Collapsed;
            RetryBtn.Visibility = job.Failed > 0 ? Visibility.Visible : Visibility.Collapsed;
            FolderBtn.IsEnabled = FilesBtn.IsEnabled = true;
            Taskbar.ProgressState = TaskbarItemProgressState.None;
            if (WindowState == WindowState.Minimized || !IsActive) FlashTaskbar();
        }
    }

    void Pause_Click(object sender, RoutedEventArgs e)
    {
        if (job == null) return;
        if (job.IsPaused) { job.Resume(); PauseBtn.Content = "Pause"; }
        else { job.Pause(); PauseBtn.Content = "Resume"; }
        Refresh();
    }

    void Cancel_Click(object sender, RoutedEventArgs e) { job?.Resume(); cts?.Cancel(); }

    void Refresh()
    {
        var j = job;
        if (j == null) return;
        PhaseText.Text = j.IsPaused ? "Paused" : j.Phase;
        var done = j.AlreadyThere + j.Uploaded + j.Failed;
        var frac = j.Found == 0 ? 0 : (double)done / j.Found;
        Bar.Value = frac;
        Taskbar.ProgressState = j.IsPaused ? TaskbarItemProgressState.Paused : running ? TaskbarItemProgressState.Normal : TaskbarItemProgressState.None;
        Taskbar.ProgressValue = frac;

        var parts = new List<string> { $"{j.Found:N0} found" };
        if (j.AlreadyThere > 0) parts.Add($"{j.AlreadyThere:N0} already in Gallery");
        if (j.ToUpload > 0 || j.Uploaded > 0) parts.Add($"{j.Uploaded:N0} of {j.ToUpload:N0} uploaded");
        if (j.Failed > 0) parts.Add($"{j.Failed:N0} failed");
        if (running && j.Checked < j.Found && j.Found > 0) parts.Add($"checked {j.Checked:N0}");
        CountsText.Text = string.Join(" · ", parts);

        var now = DateTime.UtcNow;
        var dt = (now - lastTick).TotalSeconds;
        if (dt >= 1)
        {
            var inst = (j.BytesSent - lastBytes) / dt;
            speed = speed == 0 ? inst : speed * 0.8 + inst * 0.2;
            lastBytes = j.BytesSent; lastTick = now;
        }
        var left = Math.Max(0, j.BytesToUpload - j.BytesSent);
        SpeedText.Text = !running ? $"{Size(j.BytesSent)} sent"
            : j.IsPaused || speed < 1 ? $"{Size(j.BytesSent)} of {Size(j.BytesToUpload)}"
            : $"{Size(j.BytesSent)} of {Size(j.BytesToUpload)} · {Size((long)speed)}/s · about {Eta(left / speed)} left";
        RecentList.ItemsSource = j.Recent.Reverse().ToList();
    }

    static string Size(long b) => b >= 1L << 30 ? $"{b / (double)(1L << 30):0.0} GB" : b >= 1 << 20 ? $"{b / (double)(1 << 20):0.0} MB" : $"{Math.Max(1, b / 1024)} KB";
    static string Eta(double s) => s < 90 ? $"{Math.Max(1, (int)s)} sec" : s < 5400 ? $"{(int)(s / 60)} min" : $"{s / 3600:0.0} hours";

    // ---------------------------------------------------------------- Windows bits

    [DllImport("kernel32.dll")] static extern uint SetThreadExecutionState(uint flags);
    // Keeps the PC from sleeping mid-upload (the screen can still turn off).
    static void KeepAwake(bool on) => SetThreadExecutionState(on ? 0x80000001u : 0x80000000u);

    [StructLayout(LayoutKind.Sequential)] struct FLASHWINFO { public uint cbSize; public IntPtr hwnd; public uint dwFlags; public uint uCount; public uint dwTimeout; }
    [DllImport("user32.dll")] static extern bool FlashWindowEx(ref FLASHWINFO info);
    void FlashTaskbar()
    {
        var info = new FLASHWINFO { hwnd = new System.Windows.Interop.WindowInteropHelper(this).Handle, dwFlags = 3 | 12, uCount = 3 };
        info.cbSize = (uint)Marshal.SizeOf(info);
        FlashWindowEx(ref info);
    }
}
