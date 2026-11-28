// Part of MicMask (GPL-3.0, see LICENSE at the repository root). Links tailscale.com (BSD-3-Clause).
//
// MicMask Tailscale plugin: a thin layer over tailscale.com/tsnet (the same Go
// package libtailscale wraps), built as a c-shared library for Android
// (arm64-v8a .so) and Windows (x64 .dll).
//
// Why not libtailscale's own tailscale_* C API verbatim: it hands listeners and
// connections to C as socketpair(2) fds passed with SCM_RIGHTS (fd_linux.go /
// fd_other.go use syscall.Socketpair + golang.org/x/sys/unix), which does not
// build for GOOS=windows. MicMask also wants a "forward to 127.0.0.1:port"
// model rather than raw fds, so the forwarding is done here in Go and only
// small, value-typed functions cross the C boundary.
//
// All functions are safe to call from any thread. Strings returned as char*
// are malloc'd; release them with mmts_free.
package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
	"unsafe"

	"tailscale.com/envknob"
	"tailscale.com/hostinfo"
	"tailscale.com/ipn"
	"tailscale.com/tsnet"
)

func main() {}

// Version of this plugin's C ABI / behaviour. Bump on incompatible change.
const abiVersion = 1

var pluginVersion = "dev" // set with -ldflags "-X main.pluginVersion=..."

var (
	mu      sync.Mutex
	srv     *tsnet.Server
	srvDir  string
	lastErr string
	logOut  io.Writer = io.Discard
	logFile *os.File
	exposes = map[string]io.Closer{} // "tcp:7000" / "udp:7000"
	dials   = map[string]io.Closer{} // "tcp:<localPort>" / "udp:<localPort>"
)

func setErr(err error) C.int {
	mu.Lock()
	defer mu.Unlock()
	if err == nil {
		lastErr = ""
		return 0
	}
	lastErr = err.Error()
	return -1
}

func logf(format string, a ...any) {
	mu.Lock()
	w := logOut
	mu.Unlock()
	if w == io.Discard {
		return
	}
	fmt.Fprintf(w, time.Now().Format("01-02 15:04:05.000 ")+format+"\n", a...)
}

type logWriter struct{}

func (logWriter) Write(p []byte) (int, error) {
	logf("[std] %s", strings.TrimRight(string(p), "\n"))
	return len(p), nil
}

func current() (*tsnet.Server, error) {
	mu.Lock()
	defer mu.Unlock()
	if srv == nil {
		return nil, errors.New("not started")
	}
	return srv, nil
}

// ---------- core (Go-typed, shared by the C ABI and the JNI layer) ----------

type startOpts struct {
	StateDir   string
	Hostname   string
	AuthKey    string
	ControlURL string // "" = Tailscale SaaS; set for Headscale
	Ephemeral  bool
	UploadLogs bool // false (default) = TS_NO_LOGS_NO_SUPPORT, nothing sent to log.tailscale.com
}

func doStart(o startOpts) error {
	if o.StateDir == "" {
		return errors.New("stateDir is required")
	}
	if err := os.MkdirAll(o.StateDir, 0o700); err != nil {
		return err
	}
	mu.Lock()
	if srv != nil {
		same := srvDir == o.StateDir
		mu.Unlock()
		if same {
			return nil // already started; idempotent
		}
		return errors.New("already started with another stateDir; call stop first")
	}
	mu.Unlock()

	if !o.UploadLogs {
		envknob.SetNoLogsNoSupport()
	}
	hostinfo.SetApp("micmask")
	// Some tailscale packages use the std logger directly; keep that out of
	// the host process's stderr/logcat and in the plugin log instead.
	log.SetFlags(0)
	log.SetOutput(logWriter{})
	installPlatformHooks()

	s := &tsnet.Server{
		Dir:        o.StateDir,
		Hostname:   o.Hostname,
		AuthKey:    o.AuthKey,
		ControlURL: o.ControlURL,
		Ephemeral:  o.Ephemeral,
		Logf:       func(f string, a ...any) { logf("[ts] "+f, a...) },
		UserLogf:   func(f string, a ...any) { logf("[ts-user] "+f, a...) },
	}
	if err := s.Start(); err != nil { // non-blocking: backend starts, login runs in background
		s.Close()
		return err
	}
	mu.Lock()
	srv, srvDir = s, o.StateDir
	mu.Unlock()
	logf("started dir=%s host=%q control=%q ephemeral=%v", o.StateDir, o.Hostname, o.ControlURL, o.Ephemeral)
	return nil
}

type peerJSON struct {
	Name   string   `json:"name"`
	DNS    string   `json:"dns"`
	OS     string   `json:"os"`
	IPs    []string `json:"ips"`
	Online bool     `json:"online"`
}

type statusJSON struct {
	Running  bool       `json:"running"`
	State    string     `json:"state"` // NoState/NeedsLogin/NeedsMachineAuth/Starting/Running/Stopped, or "NotStarted"
	IPs      []string   `json:"ips"`
	LoginURL string     `json:"loginUrl,omitempty"`
	Self     string     `json:"self,omitempty"` // MagicDNS name of this node
	Tailnet  string     `json:"tailnet,omitempty"`
	Health   []string   `json:"health,omitempty"`
	Peers    []peerJSON `json:"peers"`
	Exposed  []string   `json:"exposed"`
	Dials    []string   `json:"dials"`
	Error    string     `json:"error,omitempty"`
	Version  string     `json:"version"`
}

func doStatus(withPeers bool) statusJSON {
	out := statusJSON{State: "NotStarted", IPs: []string{}, Peers: []peerJSON{}, Exposed: []string{}, Dials: []string{}, Version: pluginVersion}
	mu.Lock()
	s := srv
	for k := range exposes {
		out.Exposed = append(out.Exposed, k)
	}
	for k := range dials {
		out.Dials = append(out.Dials, k)
	}
	out.Error = lastErr
	mu.Unlock()
	if s == nil {
		return out
	}
	lc, err := s.LocalClient()
	if err != nil {
		out.Error = err.Error()
		return out
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	st, err := lc.Status(ctx)
	if !withPeers && err == nil {
		st.Peer = nil
	}
	if err != nil {
		out.Error = err.Error()
		return out
	}
	out.State = st.BackendState
	out.Running = st.BackendState == ipn.Running.String()
	out.LoginURL = st.AuthURL
	out.Health = st.Health
	for _, ip := range st.TailscaleIPs {
		out.IPs = append(out.IPs, ip.String())
	}
	if st.Self != nil {
		out.Self = strings.TrimSuffix(st.Self.DNSName, ".")
	}
	if st.CurrentTailnet != nil {
		out.Tailnet = st.CurrentTailnet.Name
	}
	for _, p := range st.Peer {
		pj := peerJSON{Name: p.HostName, DNS: strings.TrimSuffix(p.DNSName, "."), OS: p.OS, Online: p.Online, IPs: []string{}}
		for _, ip := range p.TailscaleIPs {
			pj.IPs = append(pj.IPs, ip.String())
		}
		out.Peers = append(out.Peers, pj)
	}
	return out
}

// doWait blocks until the node is Running, a login URL is available, or the
// timeout passes, then returns the status. Used right after start so the
// caller gets the loginUrl (or "running") in one call.
func doWait(timeout time.Duration) statusJSON {
	deadline := time.Now().Add(timeout)
	for {
		st := doStatus(false)
		if st.Running || st.LoginURL != "" || st.State == "NotStarted" || st.State == "NeedsMachineAuth" || time.Now().After(deadline) {
			return st
		}
		time.Sleep(200 * time.Millisecond)
	}
}

func doStop() error {
	mu.Lock()
	s := srv
	srv, srvDir = nil, ""
	ex, dl := exposes, dials
	exposes, dials = map[string]io.Closer{}, map[string]io.Closer{}
	mu.Unlock()
	for _, c := range ex {
		c.Close()
	}
	for _, c := range dl {
		c.Close()
	}
	if s == nil {
		return nil
	}
	logf("stopping")
	return s.Close()
}

func doLogout(wipe bool) error {
	s, err := current()
	if err == nil {
		if lc, e := s.LocalClient(); e == nil {
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			err = lc.Logout(ctx)
			cancel()
		}
	}
	mu.Lock()
	dir := srvDir
	mu.Unlock()
	stopErr := doStop()
	if wipe && dir != "" {
		// Only remove what tsnet itself writes, never the whole directory.
		for _, n := range []string{"tailscaled.state", "tailscaled.log.conf", "tailscaled.log1.txt", "tailscaled.log2.txt", "certs"} {
			os.RemoveAll(filepath.Join(dir, n))
		}
	}
	if err != nil && !strings.Contains(err.Error(), "not started") {
		return err
	}
	return stopErr
}

// ---------- expose: tailnet port -> 127.0.0.1:localPort ----------

func doExpose(port, localPort int, udp bool) error {
	if port <= 0 || port > 65535 {
		return fmt.Errorf("bad port %d", port)
	}
	if localPort <= 0 {
		localPort = port
	}
	s, err := current()
	if err != nil {
		return err
	}
	key := "tcp:" + strconv.Itoa(port)
	if udp {
		key = "udp:" + strconv.Itoa(port)
	}
	mu.Lock()
	_, dup := exposes[key]
	mu.Unlock()
	if dup {
		return nil
	}
	target := net.JoinHostPort("127.0.0.1", strconv.Itoa(localPort))
	var closer io.Closer
	if !udp {
		ln, err := s.Listen("tcp", ":"+strconv.Itoa(port))
		if err != nil {
			return err
		}
		go func() {
			for {
				c, err := ln.Accept()
				if err != nil {
					return
				}
				go func() {
					l, err := net.DialTimeout("tcp", target, 5*time.Second)
					if err != nil {
						logf("expose %s: local dial: %v", key, err)
						c.Close()
						return
					}
					pipe(c, l)
				}()
			}
		}()
		closer = ln
	} else {
		// tsnet.ListenPacket needs a concrete IP; wait for our tailnet IPs.
		ip4, ip6, err := waitIPs(s, 30*time.Second)
		if err != nil {
			return err
		}
		var mc multiCloser
		for _, ip := range []netip.Addr{ip4, ip6} {
			if !ip.IsValid() {
				continue
			}
			pc, err := s.ListenPacket("udp", netip.AddrPortFrom(ip, uint16(port)).String())
			if err != nil {
				mc.Close()
				return err
			}
			r := &udpRelay{pc: pc, target: target, key: key, sessions: map[string]*net.UDPConn{}}
			go r.run()
			mc = append(mc, r)
		}
		closer = &mc
	}
	mu.Lock()
	exposes[key] = closer
	mu.Unlock()
	logf("expose %s -> %s", key, target)
	return nil
}

func doUnexpose(port int, udp bool) error {
	key := "tcp:" + strconv.Itoa(port)
	if udp {
		key = "udp:" + strconv.Itoa(port)
	}
	mu.Lock()
	c := exposes[key]
	delete(exposes, key)
	mu.Unlock()
	if c != nil {
		return c.Close()
	}
	return nil
}

func waitIPs(s *tsnet.Server, d time.Duration) (ip4, ip6 netip.Addr, err error) {
	end := time.Now().Add(d)
	for {
		ip4, ip6 = s.TailscaleIPs()
		if ip4.IsValid() || ip6.IsValid() {
			return
		}
		if time.Now().After(end) {
			return ip4, ip6, errors.New("no tailnet IP yet (not logged in / not running)")
		}
		time.Sleep(200 * time.Millisecond)
	}
}

// udpRelay: every remote tailnet peer (ip:port) gets its own local UDP socket
// connected to 127.0.0.1:target, so replies from the local app go back to the
// right peer. The local app sees 127.0.0.1:<ephemeral> as the source.
type udpRelay struct {
	pc       net.PacketConn
	target   string
	key      string
	mu       sync.Mutex
	sessions map[string]*net.UDPConn
	closed   bool
}

const udpIdle = 2 * time.Minute

func (r *udpRelay) run() {
	buf := make([]byte, 65535)
	for {
		n, from, err := r.pc.ReadFrom(buf)
		if err != nil {
			return
		}
		r.mu.Lock()
		lc := r.sessions[from.String()]
		if lc == nil && !r.closed {
			raddr, _ := net.ResolveUDPAddr("udp", r.target)
			lc, err = net.DialUDP("udp", nil, raddr)
			if err != nil {
				r.mu.Unlock()
				logf("expose %s: local udp: %v", r.key, err)
				continue
			}
			r.sessions[from.String()] = lc
			go r.back(lc, from)
		}
		r.mu.Unlock()
		if lc != nil {
			lc.Write(buf[:n])
		}
	}
}

func (r *udpRelay) back(lc *net.UDPConn, peer net.Addr) {
	defer func() {
		r.mu.Lock()
		delete(r.sessions, peer.String())
		r.mu.Unlock()
		lc.Close()
	}()
	buf := make([]byte, 65535)
	for {
		lc.SetReadDeadline(time.Now().Add(udpIdle))
		n, err := lc.Read(buf)
		if err != nil {
			return
		}
		if _, err := r.pc.WriteTo(buf[:n], peer); err != nil {
			return
		}
	}
}

func (r *udpRelay) Close() error {
	r.mu.Lock()
	r.closed = true
	for _, c := range r.sessions {
		c.Close()
	}
	r.mu.Unlock()
	return r.pc.Close()
}

type multiCloser []io.Closer

func (m *multiCloser) Close() error {
	for _, c := range *m {
		c.Close()
	}
	return nil
}

// ---------- dial: 127.0.0.1:<random> -> tailnet host:port ----------

func doDial(host string, port int, udp bool) (int, error) {
	s, err := current()
	if err != nil {
		return 0, err
	}
	remote := net.JoinHostPort(host, strconv.Itoa(port))
	if !udp {
		ln, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			return 0, err
		}
		lp := ln.Addr().(*net.TCPAddr).Port
		go func() {
			for {
				c, err := ln.Accept()
				if err != nil {
					return
				}
				go func() {
					ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
					r, err := s.Dial(ctx, "tcp", remote)
					cancel()
					if err != nil {
						logf("dial tcp %s: %v", remote, err)
						c.Close()
						return
					}
					pipe(c, r)
				}()
			}
		}()
		mu.Lock()
		dials["tcp:"+strconv.Itoa(lp)] = ln
		mu.Unlock()
		logf("dial tcp 127.0.0.1:%d -> %s", lp, remote)
		return lp, nil
	}
	// UDP: one local socket; datagrams from the (latest) local sender go to
	// the remote over a tsnet UDP "connection"; replies go back to that sender.
	lc, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		return 0, err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	rc, err := s.Dial(ctx, "udp", remote)
	cancel()
	if err != nil {
		lc.Close()
		return 0, err
	}
	lp := lc.LocalAddr().(*net.UDPAddr).Port
	var last struct {
		sync.Mutex
		a *net.UDPAddr
	}
	go func() {
		buf := make([]byte, 65535)
		for {
			n, a, err := lc.ReadFromUDP(buf)
			if err != nil {
				rc.Close()
				return
			}
			last.Lock()
			last.a = a
			last.Unlock()
			if _, err := rc.Write(buf[:n]); err != nil {
				logf("dial udp %s write: %v", remote, err)
			}
		}
	}()
	go func() {
		buf := make([]byte, 65535)
		for {
			n, err := rc.Read(buf)
			if err != nil {
				lc.Close()
				return
			}
			last.Lock()
			a := last.a
			last.Unlock()
			if a != nil {
				lc.WriteToUDP(buf[:n], a)
			}
		}
	}()
	mu.Lock()
	dials["udp:"+strconv.Itoa(lp)] = &multiCloser{lc, rc}
	mu.Unlock()
	logf("dial udp 127.0.0.1:%d -> %s", lp, remote)
	return lp, nil
}

func doUndial(localPort int, udp bool) error {
	key := "tcp:" + strconv.Itoa(localPort)
	if udp {
		key = "udp:" + strconv.Itoa(localPort)
	}
	mu.Lock()
	c := dials[key]
	delete(dials, key)
	mu.Unlock()
	if c != nil {
		return c.Close()
	}
	return nil
}

func pipe(a, b net.Conn) {
	done := make(chan struct{}, 2)
	cp := func(dst, src net.Conn) {
		io.Copy(dst, src)
		if cw, ok := dst.(interface{ CloseWrite() error }); ok {
			cw.CloseWrite()
		} else {
			dst.Close()
		}
		done <- struct{}{}
	}
	go cp(a, b)
	go cp(b, a)
	<-done
	<-done
	a.Close()
	b.Close()
}

func doSetLog(path string) error {
	mu.Lock()
	defer mu.Unlock()
	if logFile != nil {
		logFile.Close()
		logFile = nil
	}
	if path == "" {
		logOut = io.Discard
		return nil
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		logOut = io.Discard
		return err
	}
	logFile, logOut = f, f
	return nil
}

func toJSON(v any) string {
	b, _ := json.Marshal(v)
	return string(b)
}

// ---------- plain C ABI (for JNA / C callers) ----------

func gs(p *C.char) string {
	if p == nil {
		return ""
	}
	return C.GoString(p)
}

//export mmts_abi_version
func mmts_abi_version() C.int { return abiVersion }

//export mmts_version
func mmts_version() *C.char { return C.CString(pluginVersion) }

//export mmts_set_log_file
func mmts_set_log_file(path *C.char) C.int { return setErr(doSetLog(gs(path))) }

//export mmts_start
func mmts_start(stateDir, hostname, authKey, controlURL *C.char, ephemeral, uploadLogs C.int) C.int {
	return setErr(doStart(startOpts{gs(stateDir), gs(hostname), gs(authKey), gs(controlURL), ephemeral != 0, uploadLogs != 0}))
}

// mmts_wait_json waits up to timeout_ms for Running or a login URL; returns status JSON.
//
//export mmts_wait_json
func mmts_wait_json(timeoutMs C.int) *C.char {
	return C.CString(toJSON(doWait(time.Duration(timeoutMs) * time.Millisecond)))
}

//export mmts_status_json
func mmts_status_json(withPeers C.int) *C.char { return C.CString(toJSON(doStatus(withPeers != 0))) }

//export mmts_expose
func mmts_expose(port, localPort, udp C.int) C.int {
	return setErr(doExpose(int(port), int(localPort), udp != 0))
}

//export mmts_unexpose
func mmts_unexpose(port, udp C.int) C.int { return setErr(doUnexpose(int(port), udp != 0)) }

// mmts_dial returns the local 127.0.0.1 port (>0) or -1.
//
//export mmts_dial
func mmts_dial(host *C.char, port, udp C.int) C.int {
	lp, err := doDial(gs(host), int(port), udp != 0)
	if setErr(err) != 0 {
		return -1
	}
	return C.int(lp)
}

//export mmts_undial
func mmts_undial(localPort, udp C.int) C.int { return setErr(doUndial(int(localPort), udp != 0)) }

//export mmts_stop
func mmts_stop() C.int { return setErr(doStop()) }

//export mmts_logout
func mmts_logout(wipe C.int) C.int { return setErr(doLogout(wipe != 0)) }

//export mmts_set_interfaces_json
func mmts_set_interfaces_json(js *C.char) C.int { return setErr(setInterfacesJSON(gs(js))) }

//export mmts_last_error
func mmts_last_error() *C.char {
	mu.Lock()
	defer mu.Unlock()
	return C.CString(lastErr)
}

//export mmts_free
func mmts_free(p *C.char) { C.free(unsafe.Pointer(p)) }
