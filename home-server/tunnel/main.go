// gallery-tunnel runs next to Gallery Server on Windows: it joins the owner's own Tailscale account
// (userspace, no VPN driver) and opens a public HTTPS address with Tailscale Funnel, so their phone
// reaches the server from any network: campus Wi-Fi, cell data, hotels. Nothing to set up on the router.
//
// Gallery Server starts it with:
//
//	gallery-tunnel.exe -dir <state dir> -hostname gallery -target 127.0.0.1:8420
//
// It prints status lines on stdout:
//
//	LOGIN <url>   sign in to Tailscale (first time only)
//	ENABLE <url>  turn on Funnel for the account (first time only)
//	URL <url>     the public address of this Gallery server
//	ERR <message>
//
// It exits when its stdin closes (Gallery Server went away).
package main

import (
	"context"
	"crypto/tls"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"regexp"
	"strings"
	"sync"
	"time"

	"tailscale.com/client/local"
	"tailscale.com/ipn"
	"tailscale.com/tsnet"
)

var out sync.Mutex

func say(format string, a ...any) {
	out.Lock()
	defer out.Unlock()
	fmt.Printf(format+"\n", a...)
}

var loginURL = regexp.MustCompile(`https://login\.tailscale\.com/\S+`)

var lastLogin string

// sayLogin prints a sign-in link once (tsnet's log and the IPN bus both report it).
func sayLogin(u string) {
	out.Lock()
	defer out.Unlock()
	if u != lastLogin {
		lastLogin = u
		fmt.Printf("LOGIN %s\n", u)
	}
}

func main() {
	dir := flag.String("dir", "", "state directory")
	hostname := flag.String("hostname", "gallery", "device name in the Tailscale account (part of the address)")
	target := flag.String("target", "127.0.0.1:8420", "Gallery server to forward to")
	flag.Parse()

	go func() {
		io.Copy(io.Discard, os.Stdin)
		os.Exit(0)
	}()

	s := &tsnet.Server{
		Dir:      *dir,
		Hostname: *hostname,
		Logf:     func(string, ...any) {},
		// tsnet prints the sign-in link here when the account isn't logged in yet.
		UserLogf: func(format string, a ...any) {
			if u := loginURL.FindString(fmt.Sprintf(format, a...)); u != "" {
				sayLogin(u)
			}
		},
	}
	if os.Getenv("TUNNEL_DEBUG") != "" {
		s.Logf = log.Printf
	}
	defer s.Close()

	lc, err := s.LocalClient()
	if err != nil {
		fail(err)
	}
	go watchLogin(lc)

	// Wait for sign-in (no time limit: the person may take a while in the browser).
	var dnsName string
	for {
		st, err := s.Up(context.Background())
		if err == nil {
			dnsName = strings.TrimSuffix(st.Self.DNSName, ".")
			break
		}
		say("ERR %v", err)
		time.Sleep(5 * time.Second)
	}

	enableFunnel(lc)
	getCert(lc, dnsName)

	l, err := listenFunnel(s)
	if err != nil {
		fail(err)
	}

	proxy := httputil.NewSingleHostReverseProxy(&url.URL{Scheme: "http", Host: *target})
	proxy.FlushInterval = -1 // stream videos and downloads as they come
	proxy.ErrorHandler = func(w http.ResponseWriter, r *http.Request, err error) {
		http.Error(w, "Gallery Server is starting. Try again in a moment.", http.StatusBadGateway)
	}
	say("URL https://%s", dnsName)
	srv := &http.Server{Handler: proxy, ReadHeaderTimeout: 30 * time.Second}
	fail(srv.Serve(l))
}

// The sign-in link also comes through the IPN bus (for example after the login expires).
func watchLogin(lc *local.Client) {
	w, err := lc.WatchIPNBus(context.Background(), ipn.NotifyInitialState)
	if err != nil {
		return
	}
	defer w.Close()
	for {
		n, err := w.Next()
		if err != nil {
			return
		}
		if n.BrowseToURL != nil && *n.BrowseToURL != "" {
			sayLogin(*n.BrowseToURL)
		}
	}
}

// Funnel (and the HTTPS certificate it needs) is off in a new Tailscale account until the owner
// clicks "enable" once. Tailscale gives a link for that; wait until it's done.
func enableFunnel(lc *local.Client) {
	shown := false
	for {
		ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
		r, err := lc.QueryFeature(ctx, "funnel")
		cancel()
		if err != nil {
			say("ERR %v", err)
			time.Sleep(10 * time.Second)
			continue
		}
		if r.Complete || r.URL == "" {
			return
		}
		if !shown {
			say("ENABLE %s", r.URL)
			shown = true
		}
		time.Sleep(4 * time.Second)
	}
}

// ListenFunnel can fail for a little while right after Funnel is enabled, until the new settings arrive.
func listenFunnel(s *tsnet.Server) (net.Listener, error) {
	var err error
	for i := 0; i < 30; i++ {
		var l net.Listener
		if l, err = s.ListenFunnel("tcp", ":443", tsnet.FunnelTLSConfig(certConfig(s))); err == nil {
			return l, nil
		}
		time.Sleep(4 * time.Second)
	}
	return nil, err
}

// getCert makes the HTTPS certificate before the address is shown. The first one takes a minute or
// more (Let's Encrypt); fetching it during a visitor's request would be cut off when they give up.
func getCert(lc *local.Client, domain string) {
	for {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
		_, _, err := lc.CertPair(ctx, domain)
		cancel()
		if err == nil {
			return
		}
		say("ERR certificate: %v", err)
		time.Sleep(10 * time.Second)
	}
}

// certConfig gets the HTTPS certificate for the address from Tailscale (Let's Encrypt) and reports
// problems, e.g. HTTPS certificates turned off for the account.
func certConfig(s *tsnet.Server) *tls.Config {
	lc, _ := s.LocalClient()
	var mu sync.Mutex
	lastErr := ""
	return &tls.Config{GetCertificate: func(hi *tls.ClientHelloInfo) (*tls.Certificate, error) {
		c, err := lc.GetCertificate(hi)
		if err != nil {
			mu.Lock()
			if err.Error() != lastErr {
				lastErr = err.Error()
				say("ERR certificate: %v", err)
			}
			mu.Unlock()
		}
		return c, err
	}}
}

func fail(err error) {
	say("ERR %v", err)
	os.Exit(1)
}
