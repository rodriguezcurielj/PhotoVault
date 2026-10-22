using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;

namespace GalleryServer
{
    // Small key=value file in %LOCALAPPDATA%\Gallery Server: where the photos live and which port to use.
    static class Settings
    {
        public static readonly string Dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Gallery Server");
        static readonly string File_ = Path.Combine(Dir, "settings.txt");
        static readonly Dictionary<string, string> values = Load();

        static Dictionary<string, string> Load()
        {
            var d = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            try
            {
                foreach (var line in File.ReadAllLines(File_))
                {
                    int i = line.IndexOf('=');
                    if (i > 0) d[line.Substring(0, i).Trim()] = line.Substring(i + 1).Trim();
                }
            }
            catch { /* first run */ }
            return d;
        }

        static void Save()
        {
            Directory.CreateDirectory(Dir);
            File.WriteAllLines(File_, values.Select(kv => kv.Key + "=" + kv.Value));
        }

        public static string DataDir
        {
            get => values.TryGetValue("DataDir", out var v) && v.Length > 0 ? v : null;
            set { values["DataDir"] = value; Save(); }
        }

        public static int Port => values.TryGetValue("Port", out var v) && int.TryParse(v, out var p) ? p : 8420;

        public static bool KeepAwake
        {
            get => !values.TryGetValue("KeepAwake", out var v) || v != "0";
            set { values["KeepAwake"] = value ? "1" : "0"; Save(); }
        }

        // Access from anywhere (Tailscale Funnel) is on, and the public address it got last time.
        public static bool Remote
        {
            get => values.TryGetValue("Remote", out var v) && v == "1";
            set { values["Remote"] = value ? "1" : "0"; Save(); }
        }

        public static string RemoteUrl
        {
            get => values.TryGetValue("RemoteUrl", out var v) && v.Length > 0 ? v : null;
            set { values["RemoteUrl"] = value ?? ""; Save(); }
        }

        // A good first guess for the photos folder: the fixed drive with the most free space (not C: if there's another).
        public static string SuggestedDataDir()
        {
            var pictures = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyPictures), "Gallery");
            try
            {
                var system = Path.GetPathRoot(Environment.SystemDirectory);
                var other = DriveInfo.GetDrives()
                    .Where(d => d.DriveType == DriveType.Fixed && d.IsReady && !string.Equals(d.Name, system, StringComparison.OrdinalIgnoreCase))
                    .OrderByDescending(d => d.AvailableFreeSpace).FirstOrDefault();
                var sys = new DriveInfo(system);
                if (other != null && other.AvailableFreeSpace > sys.AvailableFreeSpace) return Path.Combine(other.Name, "Gallery");
            }
            catch { }
            return pictures;
        }
    }
}
