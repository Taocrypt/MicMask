// Part of MicMask (GPL-3.0, see LICENSE at the repository root). Links tailscale.com (BSD-3-Clause).

//go:build android

package main

import "tailscale.com/net/netmon"

func installPlatformHooks() {
	hookMu.Do(func() { netmon.RegisterInterfaceGetter(javaInterfaces) })
}
