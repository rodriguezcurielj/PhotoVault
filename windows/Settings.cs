using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Gallery;

/// <summary>Saved in %LOCALAPPDATA%\Gallery: server, login (token encrypted for this Windows user) and upload options.</summary>
public sealed class Settings
{
    public static readonly string Dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Gallery");
    static readonly string FilePath = Path.Combine(Dir, "settings.json");

    public string Server { get; set; } = "https://budget-pc.tail05789d.ts.net:8443";
    public string Username { get; set; } = "";
    public string? TokenProtected { get; set; }
    public int Parallel { get; set; } = 4;

    public string? Token
    {
        get
        {
            if (TokenProtected == null) return null;
            try { return Encoding.UTF8.GetString(ProtectedData.Unprotect(Convert.FromBase64String(TokenProtected), null, DataProtectionScope.CurrentUser)); }
            catch { return null; }
        }
        set => TokenProtected = value == null ? null
            : Convert.ToBase64String(ProtectedData.Protect(Encoding.UTF8.GetBytes(value), null, DataProtectionScope.CurrentUser));
    }

    public static Settings Load()
    {
        try { return JsonSerializer.Deserialize<Settings>(File.ReadAllText(FilePath)) ?? new Settings(); }
        catch { return new Settings(); }
    }

    public void Save()
    {
        Directory.CreateDirectory(Dir);
        File.WriteAllText(FilePath, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
    }
}

/// <summary>
/// Files already sent to the server (path + size + modified time), so running the same folder again
/// skips them without re-reading every file.
/// </summary>
public sealed class UploadedIndex
{
    static readonly string FilePath = Path.Combine(Settings.Dir, "uploaded.txt");
    readonly HashSet<string> seen = new(StringComparer.OrdinalIgnoreCase);
    readonly object gate = new();

    public UploadedIndex()
    {
        try { foreach (var line in File.ReadLines(FilePath)) seen.Add(line); } catch { /* first run */ }
    }

    static string Key(FileInfo f) => $"{f.FullName}\t{f.Length}\t{f.LastWriteTimeUtc.Ticks}";

    public bool Contains(FileInfo f) { lock (gate) return seen.Contains(Key(f)); }

    public void Add(FileInfo f)
    {
        var k = Key(f);
        lock (gate)
        {
            if (!seen.Add(k)) return;
            Directory.CreateDirectory(Settings.Dir);
            File.AppendAllText(FilePath, k + "\n");
        }
    }
}
