// tsproxy runs inside the Gallery Android app: it joins the owner's tailnet (Tailscale, userspace,
// no VPN permission) and forwards connections from 127.0.0.1 to the Gallery server over that direct,
// encrypted WireGuard path instead of the slower public Funnel relay.
//
// The app starts it with:
//
//	libtsproxy.so -dir <state dir> -hostname <name> -target <tailnet ip:port> -ifaces <file> -dns <ip,ip>
//
// and the env var TS_AUTHKEY (only needed the first time; afterwards the state dir keeps the login).
// It prints status lines on stdout: "LISTEN <port>", "UP <tailnet ip>", "ERR <message>".
// It exits when its stdin closes (the app went away).
package main

import (
	"bufio"
	"context"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/netip"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"tailscale.com/net/netmon"
	"tailscale.com/tailcfg"
	"tailscale.com/tsnet"
)

var out sync.Mutex

func say(format string, a ...any) {
	out.Lock()
	defer out.Unlock()
	fmt.Printf(format+"\n", a...)
}

func main() {
	dir := flag.String("dir", "", "state directory")
	hostname := flag.String("hostname", "gallery-phone", "tailnet device name")
	target := flag.String("target", "", "server address on the tailnet, ip:port")
	ifaces := flag.String("ifaces", "", "file listing the phone's network interfaces (written by the app)")
	dns := flag.String("dns", "", "comma-separated DNS servers")
	flag.Parse()

	// Android has no /etc/resolv.conf for a plain program; use the phone's DNS servers (or public ones).
	servers := []string{}
	for _, s := range strings.Split(*dns, ",") {
		if s = strings.TrimSpace(s); s != "" {
			servers = append(servers, net.JoinHostPort(s, "53"))
		}
	}
	servers = append(servers, "1.1.1.1:53", "8.8.8.8:53")
	net.DefaultResolver = &net.Resolver{PreferGo: true, Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
		var d net.Dialer
		var last error
		for _, s := range servers {
			c, err := d.DialContext(ctx, network, s)
			if err == nil {
				return c, nil
			}
			last = err
		}
		return nil, last
	}}

	// Android doesn't let apps list network interfaces through netlink, so the app writes them to a file.
	if *ifaces != "" {
		netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) { return readInterfaces(*ifaces) })
	}

	s := &tsnet.Server{
		Dir:      *dir,
		Hostname: *hostname,
		AuthKey:  os.Getenv("TS_AUTHKEY"),
		Logf:     func(string, ...any) {},
	}
	if os.Getenv("TSPROXY_DEBUG") != "" {
		s.Logf = log.Printf
	}
	defer s.Close()

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		say("ERR %v", err)
		os.Exit(1)
	}
	say("LISTEN %d", ln.Addr().(*net.TCPAddr).Port)

	go func() {
		// Quit when the app closes our stdin (or dies).
		io.Copy(io.Discard, os.Stdin)
		s.Close()
		os.Exit(0)
	}()
	go func() {
		// Connect (and log in the first time), retrying until it works. Forwarding waits for this.
		for {
			ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
			st, err := s.Up(ctx)
			cancel()
			if err == nil && len(st.TailscaleIPs) > 0 {
				say("UP %s", st.TailscaleIPs[0])
				watchPath(s, *target)
				return
			}
			say("ERR %v", err)
			time.Sleep(10 * time.Second)
		}
	}()

	for {
		c, err := ln.Accept()
		if err != nil {
			say("ERR %v", err)
			return
		}
		go forward(s, c, *target)
	}
}

// watchPath reports whether the server is reachable directly ("PATH direct") or only through a Tailscale
// relay ("PATH relay"). Relayed is often slower than Funnel, so the app only uses the link when direct.
// The pings also make Tailscale look for a direct path.
func watchPath(s *tsnet.Server, target string) {
	host, _, _ := net.SplitHostPort(target)
	ip, err := netip.ParseAddr(host)
	if err != nil {
		return
	}
	lc, err := s.LocalClient()
	if err != nil {
		return
	}
	last := ""
	for {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		lc.Ping(ctx, ip, tailcfg.PingDisco)
		path := "relay"
		if st, err := lc.Status(ctx); err == nil {
			for _, p := range st.Peer {
				for _, a := range p.TailscaleIPs {
					if a == ip && p.CurAddr != "" {
						path = "direct"
					}
				}
			}
		}
		cancel()
		if path != last {
			say("PATH %s", path)
			last = path
		}
		time.Sleep(15 * time.Second)
	}
}

func forward(s *tsnet.Server, c net.Conn, target string) {
	defer c.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	up, err := s.Dial(ctx, "tcp", target)
	cancel()
	if err != nil {
		return
	}
	defer up.Close()
	done := make(chan struct{}, 2)
	go func() { io.Copy(up, c); closeWrite(up); done <- struct{}{} }()
	go func() { io.Copy(c, up); closeWrite(c); done <- struct{}{} }()
	<-done
	<-done
}

func closeWrite(c net.Conn) {
	if cw, ok := c.(interface{ CloseWrite() error }); ok {
		cw.CloseWrite()
	}
}

// File format, one interface per line: name index mtu up(0/1) loopback(0/1) addr/prefix addr/prefix ...
func readInterfaces(path string) ([]netmon.Interface, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	var list []netmon.Interface
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		p := strings.Fields(sc.Text())
		if len(p) < 5 {
			continue
		}
		idx, _ := strconv.Atoi(p[1])
		mtu, _ := strconv.Atoi(p[2])
		var flags net.Flags
		if p[3] == "1" {
			flags |= net.FlagUp | net.FlagRunning
		}
		if p[4] == "1" {
			flags |= net.FlagLoopback
		} else {
			flags |= net.FlagMulticast | net.FlagBroadcast
		}
		var addrs []net.Addr
		for _, a := range p[5:] {
			if ip, ipnet, err := net.ParseCIDR(a); err == nil {
				ipnet.IP = ip
				addrs = append(addrs, ipnet)
			}
		}
		list = append(list, netmon.Interface{
			Interface: &net.Interface{Index: idx, MTU: mtu, Name: p[0], Flags: flags},
			AltAddrs:  addrs,
		})
	}
	return list, sc.Err()
}
