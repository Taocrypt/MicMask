// Part of MicMask (GPL-3.0, see LICENSE at the repository root). Links tailscale.com (BSD-3-Clause).

package main

import (
	"encoding/json"
	"errors"
	"net"
	"net/netip"
	"sync"
	"time"

	"tailscale.com/net/netmon"
)

// Android denies app UIDs NETLINK_ROUTE, so Go's net.Interfaces fails there.
// The official Android app feeds interfaces from Java via
// netmon.RegisterInterfaceGetter; we do the same: the Kotlin side passes
// java.net.NetworkInterface data as JSON (mmts_set_interfaces_json /
// TsNative.setInterfaces) and calls it again whenever connectivity changes.
// Until it does, a synthetic interface built from the kernel's chosen
// outbound source address is used (same trick as tailscale.com/feature/androidbin).

type ifaceJSON struct {
	Name     string   `json:"name"`
	Index    int      `json:"index"`
	MTU      int      `json:"mtu"`
	Up       bool     `json:"up"`
	Loopback bool     `json:"loopback"`
	Addrs    []string `json:"addrs"` // CIDR, e.g. "192.168.1.5/24"
}

var (
	ifMu   sync.Mutex
	ifList []netmon.Interface
	hookMu sync.Once
)

func setInterfacesJSON(js string) error {
	var in []ifaceJSON
	if err := json.Unmarshal([]byte(js), &in); err != nil {
		return err
	}
	var out []netmon.Interface
	for i, x := range in {
		fl := net.Flags(0)
		if x.Up {
			fl |= net.FlagUp | net.FlagRunning | net.FlagMulticast
		}
		if x.Loopback {
			fl |= net.FlagLoopback
		}
		idx := x.Index
		if idx <= 0 {
			idx = i + 1
		}
		mtu := x.MTU
		if mtu <= 0 {
			mtu = 1500
		}
		var addrs []net.Addr
		for _, a := range x.Addrs {
			p, err := netip.ParsePrefix(a)
			if err != nil {
				continue
			}
			addrs = append(addrs, &net.IPNet{IP: p.Addr().AsSlice(), Mask: net.CIDRMask(p.Bits(), p.Addr().BitLen())})
		}
		if addrs == nil {
			addrs = []net.Addr{}
		}
		out = append(out, netmon.Interface{
			Interface: &net.Interface{Index: idx, MTU: mtu, Name: x.Name, Flags: fl},
			AltAddrs:  addrs,
		})
	}
	ifMu.Lock()
	ifList = out
	ifMu.Unlock()
	mu.Lock()
	s := srv
	mu.Unlock()
	if s != nil {
		if nm, ok := s.Sys().NetMon.GetOK(); ok && nm != nil {
			nm.InjectEvent()
		}
	}
	return nil
}

func javaInterfaces() ([]netmon.Interface, error) {
	ifMu.Lock()
	l := ifList
	ifMu.Unlock()
	if l != nil {
		return l, nil
	}
	return syntheticInterfaces()
}

func syntheticInterfaces() ([]netmon.Interface, error) {
	var addrs []net.Addr
	if ip, ok := outboundIP("udp4", "8.8.8.8:53"); ok {
		addrs = append(addrs, &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(32, 32)})
	}
	if ip, ok := outboundIP("udp6", "[2001:4860:4860::8888]:53"); ok {
		addrs = append(addrs, &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(128, 128)})
	}
	if len(addrs) == 0 {
		return nil, errors.New("no interfaces (none passed from Java and no outbound route)")
	}
	return []netmon.Interface{{
		Interface: &net.Interface{Index: 1, MTU: 1500, Name: "android", Flags: net.FlagUp | net.FlagRunning},
		AltAddrs:  addrs,
	}}, nil
}

func outboundIP(network, addr string) (netip.Addr, bool) {
	d := net.Dialer{Timeout: 2 * time.Second}
	c, err := d.Dial(network, addr) // UDP "dial" sends nothing
	if err != nil {
		return netip.Addr{}, false
	}
	defer c.Close()
	ua, ok := c.LocalAddr().(*net.UDPAddr)
	if !ok {
		return netip.Addr{}, false
	}
	ip, ok := netip.AddrFromSlice(ua.IP)
	if !ok {
		return netip.Addr{}, false
	}
	ip = ip.Unmap()
	if ip.IsLoopback() || ip.IsUnspecified() {
		return netip.Addr{}, false
	}
	return ip, true
}
