// Part of MicMask (GPL-3.0, see LICENSE at the repository root). Links tailscale.com (BSD-3-Clause).

//go:build !android

package main

// Desktop OSes enumerate interfaces natively; a list passed in via
// setInterfaces is kept but only used on Android.
func installPlatformHooks() {}
