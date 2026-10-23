using System;
using System.Collections.Generic;
using System.Linq;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Threading.Tasks;

namespace GalleryServer
{
    static class Net
    {
        // Looking the addresses up takes 100-200 ms, too slow for the window's thread, so keep the last answer
        // and refresh it in the background when the network changes (new Wi-Fi, waking up from sleep).
        static List<string> cached;
        public static event Action Changed;

        public static List<string> Addresses() => cached ?? (cached = LanAddresses());

        public static void Watch()
        {
            NetworkChange.NetworkAddressChanged += (s, e) => Task.Run(() =>
            {
                var now = LanAddresses();
                if (cached != null && now.SequenceEqual(cached)) return;
                cached = now;
                Changed?.Invoke();
            });
        }

        // This PC's addresses on the home network (Wi-Fi / Ethernet with a router), best first.
        // Skips Tailscale, WSL / Hyper-V, VMware and VirtualBox adapters.
        public static List<string> LanAddresses()
        {
            var skip = new[] { "tailscale", "vethernet", "vmware", "virtualbox", "hyper-v", "loopback", "bluetooth", "wsl" };
            return NetworkInterface.GetAllNetworkInterfaces()
                .Where(n => n.OperationalStatus == OperationalStatus.Up
                    && (n.NetworkInterfaceType == NetworkInterfaceType.Ethernet || n.NetworkInterfaceType == NetworkInterfaceType.Wireless80211 || n.NetworkInterfaceType == NetworkInterfaceType.GigabitEthernet)
                    && !skip.Any(s => (n.Name + " " + n.Description).ToLowerInvariant().Contains(s)))
                .Select(n => new { n, p = n.GetIPProperties() })
                .OrderByDescending(x => x.p.GatewayAddresses.Any(g => g.Address.AddressFamily == AddressFamily.InterNetwork && !g.Address.ToString().StartsWith("0.")))
                .ThenBy(x => x.n.NetworkInterfaceType == NetworkInterfaceType.Wireless80211 ? 1 : 0)
                .SelectMany(x => x.p.UnicastAddresses)
                .Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork && !a.Address.ToString().StartsWith("169.254."))
                .Select(a => a.Address.ToString())
                .Distinct()
                .ToList();
        }

        public static string ServerUrl(string ip) => $"http://{ip}:{Settings.Port}";
    }
}
