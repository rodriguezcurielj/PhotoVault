using System;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Windows.Forms;
using Microsoft.Win32;

namespace GalleryServer
{
    // "Keep this PC awake": while Gallery Server runs, the PC doesn't go to sleep on its own, so phones can always reach it.
    // Many newer laptops use Modern Standby: when the screen turns off they go into standby and (often) drop the
    // network, and only keeping the screen on prevents that. On those, the screen stays on, but only while plugged in.
    // On battery nothing is held, so the battery isn't drained.
    static class Power
    {
        public static event Action Changed;
        public static readonly bool ModernStandby = DetectModernStandby();
        public static bool OnBattery => SystemInformation.PowerStatus.PowerLineStatus == PowerLineStatus.Offline;

        public static void Init(Control ui)
        {
            // Plugged in / unplugged / woke up: comes on another thread, so hop to the UI thread.
            SystemEvents.PowerModeChanged += (s, e) =>
            {
                if (e.Mode == PowerModes.StatusChange || e.Mode == PowerModes.Resume)
                    ui.BeginInvoke((Action)(() => { Apply(); Changed?.Invoke(); }));
            };
            Apply();
        }

        // Must run on the UI thread (the request belongs to the thread that makes it).
        public static void Apply()
        {
            uint flags = 0x80000000u; // ES_CONTINUOUS
            if (Settings.KeepAwake && !OnBattery)
            {
                flags |= 0x00000001u; // ES_SYSTEM_REQUIRED
                if (ModernStandby) flags |= 0x00000002u; // ES_DISPLAY_REQUIRED
            }
            SetThreadExecutionState(flags);
        }

        // Text for the Connect window when the PC may sleep and phones lose the connection; null when fine.
        public static string Warning()
        {
            if (OnBattery) return "This PC is running on battery. When it sleeps, your phone can't reach Gallery. Plug it in to keep Gallery online.";
            if (!Settings.KeepAwake) return "\"Keep this PC awake\" is off. When this PC sleeps, your phone can't reach Gallery. Turn it on from the tray icon.";
            return null;
        }

        static bool DetectModernStandby()
        {
            try
            {
                var psi = new ProcessStartInfo("powercfg.exe", "/a") { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true };
                using (var p = Process.Start(psi))
                {
                    var text = p.StandardOutput.ReadToEnd();
                    p.WaitForExit(5000);
                    // The first section lists what's available; Modern Standby is "Standby (S0 Low Power Idle)".
                    int notAvail = text.IndexOf("not available", StringComparison.OrdinalIgnoreCase);
                    int s0 = text.IndexOf("S0 Low Power Idle", StringComparison.OrdinalIgnoreCase);
                    return s0 >= 0 && (notAvail < 0 || s0 < notAvail);
                }
            }
            catch { return false; }
        }

        [DllImport("kernel32.dll")] static extern uint SetThreadExecutionState(uint flags);
    }
}
