using System.Collections.Concurrent;
using System.Globalization;
using System.IO;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading.Channels;

namespace Gallery;

/// <summary>
/// Sends a big pile of photos/videos (tens of thousands) to the server:
/// find files → skip ones uploaded before → fingerprint (SHA-1) in batches → ask the server which it already has →
/// upload the rest several at a time. Uploading starts while later batches are still being checked.
/// </summary>
public sealed class Uploader
{
    static readonly HashSet<string> MediaExt = new(StringComparer.OrdinalIgnoreCase)
    {
        ".jpg", ".jpeg", ".png", ".gif", ".webp", ".heic", ".heif", ".avif", ".bmp", ".tif", ".tiff", ".dng",
        ".mp4", ".mov", ".m4v", ".3gp", ".webm", ".mkv", ".avi",
    };

    readonly Api api;
    readonly UploadedIndex index;
    public Uploader(Api api, UploadedIndex index) { this.api = api; this.index = index; }

    // ---- progress (read by the window a few times a second) ----
    public volatile string Phase = "";
    public int Found, AlreadyThere, ToUpload, Uploaded, Failed, Checked;
    public long BytesToUpload, BytesSent;
    public readonly ConcurrentQueue<string> Recent = new();
    public readonly ConcurrentBag<(FileInfo file, long hint, string error)> Failures = new();
    public string? FatalError;

    // ---- pause ----
    volatile TaskCompletionSource? paused;
    public bool IsPaused => paused != null;
    public void Pause() => paused ??= new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
    public void Resume() { var p = paused; paused = null; p?.TrySetResult(); }
    Task WaitIfPaused() => paused?.Task ?? Task.CompletedTask;

    record Item(FileInfo File, long Hint);

    public async Task Run(IEnumerable<string> paths, int parallel, CancellationToken ct)
    {
        Phase = "Finding photos and videos…";
        var items = await Task.Run(() => Scan(paths, ct), ct);
        Found = items.Count;
        // Oldest first, so the library fills in by date.
        items.Sort((a, b) => a.Hint.CompareTo(b.Hint));

        var fresh = items.Where(i => !index.Contains(i.File)).ToList();
        AlreadyThere = items.Count - fresh.Count;
        Checked = AlreadyThere;

        var queue = Channel.CreateBounded<Item>(new BoundedChannelOptions(400) { SingleWriter = true });
        Phase = "Checking what's already in Gallery…";
        var producer = Task.Run(async () =>
        {
            try
            {
                foreach (var batch in fresh.Chunk(200))
                {
                    ct.ThrowIfCancellationRequested();
                    // Fingerprint the batch in parallel (disk-bound), then one request asks which ones the server has.
                    var sums = new string?[batch.Length];
                    await Parallel.ForAsync(0, batch.Length, new ParallelOptions { MaxDegreeOfParallelism = Math.Min(8, Environment.ProcessorCount), CancellationToken = ct },
                        async (i, c) => sums[i] = await Sha1(batch[i].File, c));
                    var have = await Retry(() => api.Existing(sums.Where(s => s != null).Select(s => s!).ToList(), ct), ct);
                    for (var i = 0; i < batch.Length; i++)
                    {
                        if (sums[i] != null && have.Contains(sums[i]!)) { index.Add(batch[i].File); Interlocked.Increment(ref AlreadyThere); }
                        else
                        {
                            Interlocked.Increment(ref ToUpload);
                            Interlocked.Add(ref BytesToUpload, batch[i].File.Length);
                            await queue.Writer.WriteAsync(batch[i], ct);
                        }
                        Interlocked.Increment(ref Checked);
                    }
                }
            }
            finally { queue.Writer.TryComplete(); }
        }, ct);

        var workers = Enumerable.Range(0, Math.Clamp(parallel, 1, 8)).Select(_ => Task.Run(async () =>
        {
            await foreach (var item in queue.Reader.ReadAllAsync(ct))
            {
                await WaitIfPaused();
                if (Phase.StartsWith("Checking")) Phase = "Uploading…";
                await UploadOne(item, ct);
            }
        }, ct)).ToList();

        await producer;
        await Task.WhenAll(workers);
        Phase = Failed > 0 ? "Done, with some that didn't upload" : "All done";
    }

    /// <summary>Tries the files that failed again.</summary>
    public async Task RetryFailed(int parallel, CancellationToken ct)
    {
        var again = new List<Item>();
        while (Failures.TryTake(out var f)) again.Add(new Item(f.file, f.hint));
        Failed = 0;
        Phase = "Uploading…";
        var q = new ConcurrentQueue<Item>(again);
        await Task.WhenAll(Enumerable.Range(0, Math.Clamp(parallel, 1, 8)).Select(_ => Task.Run(async () =>
        {
            while (q.TryDequeue(out var item)) { await WaitIfPaused(); await UploadOne(item, ct); }
        }, ct)));
        Phase = Failed > 0 ? "Done, with some that didn't upload" : "All done";
    }

    async Task UploadOne(Item item, CancellationToken ct)
    {
        long sent = 0;
        for (var attempt = 1; ; attempt++)
        {
            try
            {
                await api.Upload(item.File, item.Hint, n => { sent += n; Interlocked.Add(ref BytesSent, n); }, ct);
                index.Add(item.File);
                Interlocked.Increment(ref Uploaded);
                Note("✓ " + item.File.Name);
                return;
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { Interlocked.Add(ref BytesSent, -sent); throw; }
            catch (Exception e)
            {
                Interlocked.Add(ref BytesSent, -sent);
                sent = 0;
                if (e is ApiException { Status: 401 }) { FatalError = "You were signed out. Sign in again and run it again — finished files are skipped."; throw; }
                var permanent = e is ApiException { Status: 400 or 413 or 415 } || e is FileNotFoundException or UnauthorizedAccessException;
                // Server down or restarting: wait for it instead of using up this file's tries.
                if (!permanent && await WaitForServer(ct)) { attempt--; continue; }
                if (permanent || attempt >= 4)
                {
                    Interlocked.Increment(ref Failed);
                    Failures.Add((item.File, item.Hint, e.Message));
                    Note("✗ " + item.File.Name + " — " + e.Message);
                    return;
                }
                await Task.Delay(TimeSpan.FromSeconds(attempt * attempt * 2), ct);
            }
        }
    }

    void Note(string s)
    {
        Recent.Enqueue(s);
        while (Recent.Count > 8) Recent.TryDequeue(out _);
    }

    async Task<T> Retry<T>(Func<Task<T>> f, CancellationToken ct)
    {
        for (var attempt = 1; ; attempt++)
        {
            try { return await f(); }
            catch (Exception e) when (attempt < 5 && !ct.IsCancellationRequested && e is not ApiException { Status: 401 })
            {
                if (await WaitForServer(ct)) attempt--;
                else await Task.Delay(TimeSpan.FromSeconds(attempt * 3), ct);
            }
        }
    }

    // ---- riding out server outages (restart, Wi-Fi drop, PC asleep) ----
    readonly SemaphoreSlim outageGate = new(1, 1);
    DateTime recoveredAt = DateTime.MinValue;

    /// <summary>
    /// If the server isn't answering, waits (as long as it takes) until it is, and returns true.
    /// Returns false right away when the server is fine, i.e. the error was about this one file.
    /// </summary>
    async Task<bool> WaitForServer(CancellationToken ct)
    {
        await outageGate.WaitAsync(ct); // one worker checks; the others wait here and reuse its answer
        try
        {
            if (DateTime.UtcNow - recoveredAt < TimeSpan.FromSeconds(5)) return true; // an outage just ended while we waited
            if (await api.Health(api.Server) == null) return false;
            var before = Phase;
            Phase = "Can't reach the server — waiting for it to come back…";
            Note("… server not answering, will continue on its own");
            while (await api.Health(api.Server) != null) await Task.Delay(TimeSpan.FromSeconds(10), ct);
            recoveredAt = DateTime.UtcNow;
            Phase = before.StartsWith("Can't reach") ? "Uploading…" : before;
            return true;
        }
        finally { outageGate.Release(); }
    }

    static async Task<string?> Sha1(FileInfo f, CancellationToken ct)
    {
        try
        {
            await using var fs = new FileStream(f.FullName, FileMode.Open, FileAccess.Read, FileShare.ReadWrite, 1 << 20, FileOptions.Asynchronous | FileOptions.SequentialScan);
            return Convert.ToHexString(await SHA1.HashDataAsync(fs, ct)).ToLowerInvariant();
        }
        catch (OperationCanceledException) { throw; }
        catch { return null; } // unreadable: let the upload report the problem
    }

    // ---------------------------------------------------------------- finding files + their dates

    List<Item> Scan(IEnumerable<string> paths, CancellationToken ct)
    {
        var files = new List<FileInfo>();
        var sidecars = new Dictionary<string, long>(StringComparer.OrdinalIgnoreCase);
        var opts = new EnumerationOptions { RecurseSubdirectories = true, IgnoreInaccessible = true, AttributesToSkip = FileAttributes.Hidden | FileAttributes.System };
        void Consider(FileInfo f)
        {
            if (MediaExt.Contains(f.Extension)) { files.Add(f); Found = files.Count; }
            else if (f.Extension.Equals(".json", StringComparison.OrdinalIgnoreCase) && f.Length < 1_000_000) ReadSidecar(f, sidecars);
        }
        foreach (var p in paths)
        {
            ct.ThrowIfCancellationRequested();
            if (Directory.Exists(p)) foreach (var f in new DirectoryInfo(p).EnumerateFiles("*", opts)) { ct.ThrowIfCancellationRequested(); Consider(f); }
            else if (File.Exists(p)) Consider(new FileInfo(p));
        }
        return files.Select(f => new Item(f, DateHint(f, sidecars))).ToList();
    }

    // Google Takeout keeps the real date next to each photo in a .json whose "title" is the photo's name.
    static void ReadSidecar(FileInfo f, Dictionary<string, long> into)
    {
        try
        {
            using var doc = JsonDocument.Parse(File.ReadAllText(f.FullName));
            var r = doc.RootElement;
            if (!r.TryGetProperty("title", out var title)) return;
            foreach (var key in new[] { "photoTakenTime", "creationTime" })
                if (r.TryGetProperty(key, out var t) && t.TryGetProperty("timestamp", out var ts) && long.TryParse(ts.GetString(), out var sec))
                {
                    into[Path.Combine(f.DirectoryName!, title.GetString()!)] = sec * 1000;
                    return;
                }
        }
        catch { /* not a Takeout file */ }
    }

    // IMG_20230514_181530.jpg, VID-20230514-WA0001.mp4, Screenshot_2023-05-14-18-15-30.png, PXL_20230514_181530123.jpg …
    static readonly Regex NameDate = new(@"(?<!\d)((?:19|20)\d{2})[-_.]?(0[1-9]|1[0-2])[-_.]?(0[1-9]|[12]\d|3[01])(?:[-_. T]?([01]\d|2[0-3])[-_.:]?([0-5]\d)[-_.:]?([0-5]\d))?", RegexOptions.Compiled);

    /// <summary>
    /// When the photo has no date inside it (EXIF / video metadata, which the server reads first), the server
    /// falls back to this: Takeout date → date in the file name → the file's last-modified time.
    /// </summary>
    static long DateHint(FileInfo f, Dictionary<string, long> sidecars)
    {
        if (sidecars.TryGetValue(f.FullName, out var t)) return t;
        var plain = Regex.Replace(f.Name, @"(-edited|\(\d+\))(?=\.[^.]+$)", "", RegexOptions.IgnoreCase);
        if (sidecars.TryGetValue(Path.Combine(f.DirectoryName!, plain), out t)) return t;
        var m = NameDate.Match(Path.GetFileNameWithoutExtension(f.Name));
        if (m.Success)
        {
            int G(int i, int d) => m.Groups[i].Success ? int.Parse(m.Groups[i].Value, CultureInfo.InvariantCulture) : d;
            try
            {
                var local = new DateTime(G(1, 2000), G(2, 1), G(3, 1), G(4, 12), G(5, 0), G(6, 0), DateTimeKind.Local);
                if (local.Year >= 1990 && local <= DateTime.Now.AddDays(1)) return new DateTimeOffset(local).ToUnixTimeMilliseconds();
            }
            catch { /* not a real date */ }
        }
        return new DateTimeOffset(f.LastWriteTimeUtc).ToUnixTimeMilliseconds();
    }
}
