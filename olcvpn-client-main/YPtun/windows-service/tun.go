package main

import (
	"fmt"
	"log"
	"os/exec"
	"strconv"
	"strings"
	"sync"

	"golang.zx2c4.com/wintun"
)

// tunState owns the wintun adapter + session the service created as LocalSystem. The GUI (unelevated)
// will drive the data path over this adapter; the service is the only component that touches the
// privileged objects.
type tunState struct {
	adapter *wintun.Adapter
	session wintun.Session
	name    string
}

var (
	tunMu      sync.Mutex
	currentTun *tunState
)

// startTun creates/opens the wintun adapter, sets its address + DNS and installs routes. All calls run
// in the elevated service, so no UAC is ever shown to the user.
func startTun(name string, address string, prefixLen int, dns []string, routes []string) error {
	tunMu.Lock()
	defer tunMu.Unlock()
	if currentTun != nil {
		return nil
	}
	if name == "" {
		name = "your_vpn"
	}

	adapter, err := wintun.CreateAdapter(name, "your_vpn", nil)
	if err != nil {
		opened, openErr := wintun.OpenAdapter(name)
		if openErr != nil {
			return fmt.Errorf("create adapter %q: %v; open existing: %v", name, err, openErr)
		}
		adapter = opened
	}
	session, err := adapter.StartSession(0x400000)
	if err != nil {
		_ = adapter.Close()
		return fmt.Errorf("start session: %v", err)
	}

	st := &tunState{adapter: adapter, session: session, name: name}

	if address != "" {
		mask := prefixToMask(prefixLen)
		if out, e := run("netsh", "interface", "ipv4", "set", "address",
			"name="+name, "static", address, mask); e != nil {
			stopTunLocked()
			return fmt.Errorf("set address: %v: %s", e, out)
		}
	}
	for i, d := range dns {
		args := []string{"interface", "ipv4", "set", "dns", "name=" + name}
		if i == 0 {
			args = append(args, "static", d)
		} else {
			args = append(args, "add", d, "index="+strconv.Itoa(i+1))
		}
		if out, e := run("netsh", args...); e != nil {
			stopTunLocked()
			return fmt.Errorf("set dns %s: %v: %s", d, e, out)
		}
	}
	for _, r := range routes {
		if out, e := run("netsh", "interface", "ipv4", "add", "route",
			r, name, "0.0.0.0", "metric=1"); e != nil {
			log.Printf("add route %s: %v: %s", r, e, out)
		}
	}

	currentTun = st
	log.Printf("tun %q up: %s/%d dns=%v routes=%v", name, address, prefixLen, dns, routes)
	return nil
}

func tunStateName() string {
	tunMu.Lock()
	defer tunMu.Unlock()
	if currentTun == nil {
		return "idle"
	}
	return "tun-up"
}

func stopTun() error {
	tunMu.Lock()
	defer tunMu.Unlock()
	return stopTunLocked()
}

func stopTunLocked() error {
	st := currentTun
	if st == nil {
		return nil
	}
	currentTun = nil
	for _, r := range []string{"0.0.0.0/1", "128.0.0.0/1"} {
		_, _ = run("netsh", "interface", "ipv4", "delete", "route", r, st.name)
	}
	_, _ = run("netsh", "interface", "ipv4", "set", "dns", "name="+st.name, "source=dhcp")
	_, _ = run("netsh", "interface", "ipv4", "set", "address", "name="+st.name, "source=dhcp")
	st.session.End()
	_ = st.adapter.Close()
	log.Printf("tun %q down", st.name)
	return nil
}

func run(name string, args ...string) (string, error) {
	out, err := exec.Command(name, args...).CombinedOutput()
	return strings.TrimSpace(string(out)), err
}

// prefixToMask converts a CIDR prefix length to a dotted IPv4 mask for netsh.
func prefixToMask(prefix int) string {
	if prefix < 0 {
		prefix = 0
	}
	if prefix > 32 {
		prefix = 32
	}
	mask := uint32(0xFFFFFFFF) << (32 - prefix)
	if prefix == 0 {
		mask = 0
	}
	return fmt.Sprintf("%d.%d.%d.%d",
		byte(mask>>24), byte(mask>>16), byte(mask>>8), byte(mask))
}
