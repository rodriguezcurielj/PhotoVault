using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;

namespace Gallery;

public sealed class ApiException(int status, string message) : Exception(message)
{
    public int Status { get; } = status;
}

/// <summary>Talks to the Gallery (PhotoVault) server.</summary>
public sealed class Api
{
    readonly HttpClient http = new(new SocketsHttpHandler
    {
        MaxConnectionsPerServer = 16,
        PooledConnectionLifetime = TimeSpan.FromMinutes(10),
        AutomaticDecompression = DecompressionMethods.All,
        ConnectTimeout = TimeSpan.FromSeconds(20),
    }) { Timeout = Timeout.InfiniteTimeSpan };

    public string Server { get; set; } = "";
    public string? Token { get; set; }

    HttpRequestMessage Request(HttpMethod method, string path, HttpContent? body = null)
    {
        var req = new HttpRequestMessage(method, Server.TrimEnd('/') + path) { Content = body };
        if (Token != null) req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", Token);
        return req;
    }

    async Task<JsonElement> Send(HttpRequestMessage req, CancellationToken ct, TimeSpan? timeout = null)
    {
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cts.CancelAfter(timeout ?? TimeSpan.FromSeconds(60));
        using var res = await http.SendAsync(req, cts.Token);
        var text = await res.Content.ReadAsStringAsync(cts.Token);
        JsonElement json = default;
        try { json = JsonDocument.Parse(string.IsNullOrWhiteSpace(text) ? "{}" : text).RootElement.Clone(); } catch { /* not JSON */ }
        if (!res.IsSuccessStatusCode)
        {
            var msg = json.ValueKind == JsonValueKind.Object && json.TryGetProperty("error", out var e) ? e.GetString() : null;
            throw new ApiException((int)res.StatusCode, msg ?? $"Server error {(int)res.StatusCode}");
        }
        return json;
    }

    static StringContent Json(object o) => new(JsonSerializer.Serialize(o), Encoding.UTF8, "application/json");

    /// <summary>null when the address answers as Gallery, otherwise what went wrong.</summary>
    public async Task<string?> Health(string server)
    {
        try
        {
            using var res = await http.GetAsync(server.TrimEnd('/') + "/api/health", new CancellationTokenSource(TimeSpan.FromSeconds(15)).Token);
            var text = await res.Content.ReadAsStringAsync();
            return res.IsSuccessStatusCode && text.Contains("PhotoVault") ? null : $"That address answered, but it isn't Gallery ({(int)res.StatusCode})";
        }
        catch (Exception e) { return "Can't reach the server: " + e.Message; }
    }

    public async Task<string> Login(string username, string password)
    {
        var r = await Send(Request(HttpMethod.Post, "/api/login", Json(new { username = username.Trim(), password, device = "Windows · " + Environment.MachineName })), CancellationToken.None);
        return r.GetProperty("token").GetString()!;
    }

    public async Task Logout() { try { await Send(Request(HttpMethod.Post, "/api/logout"), CancellationToken.None); } catch { } }

    public async Task<(string name, string email)> Profile()
    {
        var r = await Send(Request(HttpMethod.Get, "/api/profile"), CancellationToken.None);
        string S(string k) => r.TryGetProperty(k, out var v) ? v.GetString() ?? "" : "";
        return (S("name").Length > 0 ? S("name") : S("username"), S("email"));
    }

    /// <summary>Which of these SHA-1 checksums the server already has.</summary>
    public async Task<HashSet<string>> Existing(IReadOnlyCollection<string> checksums, CancellationToken ct)
    {
        var r = await Send(Request(HttpMethod.Post, "/api/assets/check", Json(new { checksums })), ct);
        var have = new HashSet<string>();
        if (r.TryGetProperty("existing", out var ex)) foreach (var p in ex.EnumerateObject()) have.Add(p.Name);
        return have;
    }

    /// <summary>Uploads one file. <paramref name="takenHint"/> (ms since 1970) is used when the file has no date of its own.</summary>
    public async Task Upload(FileInfo file, long takenHint, Action<long> sent, CancellationToken ct)
    {
        using var form = new MultipartFormDataContent();
        form.Add(new StringContent(takenHint.ToString()), "lastModified");
        var content = new ProgressContent(file, sent);
        content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
        form.Add(content, "files", file.Name);
        // Big videos over slow links can take a while; allow ~1 minute per 20 MB, at least 10 minutes.
        var timeout = TimeSpan.FromMinutes(Math.Max(10, file.Length / (20 * 1024 * 1024)));
        var r = await Send(Request(HttpMethod.Post, "/api/upload", form), ct, timeout);
        var first = r.GetProperty("results").EnumerateArray().FirstOrDefault();
        if (first.ValueKind == JsonValueKind.Object && first.TryGetProperty("error", out var err)) throw new ApiException(400, err.GetString() ?? "Upload failed");
    }

    /// <summary>Streams a file from disk, reporting bytes as they go out.</summary>
    sealed class ProgressContent(FileInfo file, Action<long> sent) : HttpContent
    {
        protected override async Task SerializeToStreamAsync(Stream stream, TransportContext? context)
        {
            await using var fs = new FileStream(file.FullName, FileMode.Open, FileAccess.Read, FileShare.ReadWrite, 1 << 20, FileOptions.Asynchronous | FileOptions.SequentialScan);
            var buf = new byte[256 * 1024];
            int n;
            while ((n = await fs.ReadAsync(buf)) > 0)
            {
                await stream.WriteAsync(buf.AsMemory(0, n));
                sent(n);
            }
        }
        protected override bool TryComputeLength(out long length) { length = file.Length; return true; }
    }
}
