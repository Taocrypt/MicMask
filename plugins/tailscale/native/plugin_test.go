// Part of MicMask (GPL-3.0, see LICENSE at the repository root). Links tailscale.com (BSD-3-Clause).

package main

// Loopback integration test: an in-process control server + DERP/STUN
// (tailscale.com/tstest/integration), one node driven through this plugin's
// core (doStart/doExpose/doDial...) and one plain tsnet peer. Verifies
// login-URL reporting, expose (tailnet -> 127.0.0.1) and dial
// (127.0.0.1 -> tailnet) for both TCP and UDP. Runs on the host OS only.

import (
	"bytes"
	"context"
	"net"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"tailscale.com/ipn/store/mem"
	"tailscale.com/net/netns"
	"tailscale.com/tailcfg"
	"tailscale.com/tsnet"
	"tailscale.com/tstest/integration"
	"tailscale.com/tstest/integration/testcontrol"
	"tailscale.com/types/logger"
)

func startControl(t *testing.T, requireAuth bool) (string, *testcontrol.Server) {
	netns.SetEnabled(false)
	t.Cleanup(func() { netns.SetEnabled(true) })
	derpMap := integration.RunDERPAndSTUN(t, logger.Discard, "127.0.0.1")
	c := &testcontrol.Server{
		DERPMap:        derpMap,
		DNSConfig:      &tailcfg.DNSConfig{Proxied: true},
		MagicDNSDomain: "tail-scale.ts.net",
		RequireAuth:    requireAuth,
		Logf:           logger.Discard,
	}
	c.HTTPTestServer = httptest.NewUnstartedServer(c)
	c.HTTPTestServer.Start()
	t.Cleanup(c.HTTPTestServer.Close)
	return c.HTTPTestServer.URL, c
}

func echoTCP(t *testing.T, ln net.Listener) {
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				buf := make([]byte, 4096)
				for {
					n, err := c.Read(buf)
					if err != nil {
						return
					}
					c.Write(append([]byte("echo:"), buf[:n]...))
				}
			}()
		}
	}()
}

func echoUDP(pc net.PacketConn) {
	go func() {
		buf := make([]byte, 65535)
		for {
			n, a, err := pc.ReadFrom(buf)
			if err != nil {
				return
			}
			pc.WriteTo(append([]byte("echo:"), buf[:n]...), a)
		}
	}()
}

func roundTrip(t *testing.T, c net.Conn, msg string, tries int) {
	t.Helper()
	buf := make([]byte, 4096)
	for i := 0; i < tries; i++ {
		c.SetDeadline(time.Now().Add(3 * time.Second))
		if _, err := c.Write([]byte(msg)); err != nil {
			t.Fatalf("write: %v", err)
		}
		n, err := c.Read(buf)
		if err == nil {
			if !bytes.Equal(buf[:n], []byte("echo:"+msg)) {
				t.Fatalf("got %q", buf[:n])
			}
			return
		}
		t.Logf("try %d: %v", i, err)
	}
	t.Fatalf("no echo for %q", msg)
}

func TestLoginURL(t *testing.T) {
	ctrl, _ := startControl(t, true)
	dir := filepath.Join(t.TempDir(), "a")
	defer doStop()
	if err := doStart(startOpts{StateDir: dir, Hostname: "mm-login", ControlURL: ctrl}); err != nil {
		t.Fatal(err)
	}
	st := doWait(30 * time.Second)
	t.Logf("state=%s loginUrl=%q", st.State, st.LoginURL)
	if st.LoginURL == "" || st.Running {
		t.Fatalf("want loginUrl and not running, got %+v", st)
	}
}

func TestExposeAndDial(t *testing.T) {
	ctrl, _ := startControl(t, false)
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()

	// Node A: through the plugin.
	if p := os.Getenv("MMTS_TEST_LOG"); p != "" {
		doSetLog(p)
	}
	t0 := time.Now()
	if err := doStart(startOpts{StateDir: filepath.Join(t.TempDir(), "a"), Hostname: "mm-a", ControlURL: ctrl, Ephemeral: true}); err != nil {
		t.Fatal(err)
	}
	defer doStop()
	st := doWait(60 * time.Second)
	if !st.Running || len(st.IPs) == 0 {
		t.Fatalf("A not running: %+v", st)
	}
	t.Logf("A running in %v, ips=%v", time.Since(t0), st.IPs)
	aIP := st.IPs[0]

	// Node B: plain tsnet peer.
	b := &tsnet.Server{Dir: filepath.Join(t.TempDir(), "b"), Hostname: "mm-b", ControlURL: ctrl, Store: new(mem.Store), Ephemeral: true}
	defer b.Close()
	bst, err := b.Up(ctx)
	if err != nil {
		t.Fatal(err)
	}
	bIP := bst.TailscaleIPs[0]

	// ---- expose: A's tailnet :7000 -> A's 127.0.0.1:<local echo>
	lt, _ := net.Listen("tcp", "127.0.0.1:0")
	defer lt.Close()
	echoTCP(t, lt)
	lu, _ := net.ListenPacket("udp", "127.0.0.1:0")
	defer lu.Close()
	echoUDP(lu)
	if err := doExpose(7000, lt.Addr().(*net.TCPAddr).Port, false); err != nil {
		t.Fatal(err)
	}
	if err := doExpose(7000, lu.LocalAddr().(*net.UDPAddr).Port, true); err != nil {
		t.Fatal(err)
	}
	var c net.Conn
	for i := 0; i < 20; i++ {
		c, err = b.Dial(ctx, "tcp", net.JoinHostPort(aIP, "7000"))
		if err == nil {
			break
		}
		time.Sleep(500 * time.Millisecond)
	}
	if err != nil {
		t.Fatalf("B dial A tcp: %v", err)
	}
	roundTrip(t, c, "expose-tcp", 3)
	c.Close()
	t.Log("expose TCP ok")
	uc, err := b.Dial(ctx, "udp", net.JoinHostPort(aIP, "7000"))
	if err != nil {
		t.Fatal(err)
	}
	roundTrip(t, uc, "expose-udp", 5)
	uc.Close()
	t.Log("expose UDP ok")

	// ---- dial: A's 127.0.0.1:<lp> -> B tailnet :8000
	bl, err := b.Listen("tcp", ":8000")
	if err != nil {
		t.Fatal(err)
	}
	echoTCP(t, bl)
	bp, err := b.ListenPacket("udp", net.JoinHostPort(bIP.String(), "8000"))
	if err != nil {
		t.Fatal(err)
	}
	echoUDP(bp)
	lp, err := doDial(bIP.String(), 8000, false)
	if err != nil {
		t.Fatal(err)
	}
	c, err = net.Dial("tcp", "127.0.0.1:"+strconv.Itoa(lp))
	if err != nil {
		t.Fatal(err)
	}
	roundTrip(t, c, "dial-tcp", 3)
	c.Close()
	t.Log("dial TCP ok")
	ulp, err := doDial(bIP.String(), 8000, true)
	if err != nil {
		t.Fatal(err)
	}
	uc, err = net.Dial("udp", "127.0.0.1:"+strconv.Itoa(ulp))
	if err != nil {
		t.Fatal(err)
	}
	roundTrip(t, uc, "dial-udp", 5)
	// a larger datagram (Opus/PCM frames are <=1200 B in MicMask Link)
	big := string(bytes.Repeat([]byte("x"), 1200))
	roundTrip(t, uc, big, 5)
	uc.Close()
	t.Log("dial UDP ok")

	full := doStatus(true)
	t.Logf("status: running=%v ips=%v peers=%d exposed=%v dials=%v", full.Running, full.IPs, len(full.Peers), full.Exposed, full.Dials)
	if len(full.Peers) != 1 {
		t.Fatalf("want 1 peer, got %d", len(full.Peers))
	}
	if err := doStop(); err != nil {
		t.Fatal(err)
	}
	if s := doStatus(false); s.State != "NotStarted" {
		t.Fatalf("after stop: %+v", s)
	}
}

// TestPeerServer is not a unit test: with MMTS_PEER_OUT=<file> it runs a
// control server + an echo peer ("mm-b", TCP and UDP echo on tailnet :8000)
// for MMTS_PEER_SECS seconds so an external process (the JVM smoke test in
// kotlin-test/) can join the same tailnet through the plugin library.
// Writing "<network> <ip:port>" to <file>.dial makes the peer send
// "probe" there and write "OK <reply>" or "FAIL <err>" to <file>.dialres.
func TestPeerServer(t *testing.T) {
	out := os.Getenv("MMTS_PEER_OUT")
	if out == "" {
		t.Skip("MMTS_PEER_OUT not set")
	}
	secs, _ := strconv.Atoi(os.Getenv("MMTS_PEER_SECS"))
	if secs <= 0 {
		secs = 60
	}
	ctrl, _ := startControl(t, false)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	b := &tsnet.Server{Dir: filepath.Join(t.TempDir(), "b"), Hostname: "mm-b", ControlURL: ctrl, Store: new(mem.Store), Ephemeral: true}
	defer b.Close()
	st, err := b.Up(ctx)
	if err != nil {
		t.Fatal(err)
	}
	bIP := st.TailscaleIPs[0].String()
	bl, err := b.Listen("tcp", ":8000")
	if err != nil {
		t.Fatal(err)
	}
	echoTCP(t, bl)
	bp, err := b.ListenPacket("udp", net.JoinHostPort(bIP, "8000"))
	if err != nil {
		t.Fatal(err)
	}
	echoUDP(bp)
	os.WriteFile(out+".tmp", []byte("CONTROL="+ctrl+"\nBIP="+bIP+"\n"), 0o644)
	os.Rename(out+".tmp", out)
	end := time.Now().Add(time.Duration(secs) * time.Second)
	for time.Now().Before(end) {
		if req, err := os.ReadFile(out + ".dial"); err == nil && len(req) > 0 {
			os.Remove(out + ".dial")
			f := strings.Fields(string(req))
			res := "FAIL bad request"
			if len(f) == 2 {
				res = probe(b, f[0], f[1])
			}
			os.WriteFile(out+".dialres.tmp", []byte(res), 0o644)
			os.Rename(out+".dialres.tmp", out+".dialres")
		}
		time.Sleep(100 * time.Millisecond)
	}
}

func probe(b *tsnet.Server, network, addr string) string {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	c, err := b.Dial(ctx, network, addr)
	if err != nil {
		return "FAIL " + err.Error()
	}
	defer c.Close()
	buf := make([]byte, 2048)
	for i := 0; i < 5; i++ {
		c.SetDeadline(time.Now().Add(2 * time.Second))
		c.Write([]byte("probe"))
		if n, err := c.Read(buf); err == nil {
			return "OK " + string(buf[:n])
		}
	}
	return "FAIL no reply"
}
