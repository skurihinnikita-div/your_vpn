// Command your_vpn_service is the privileged Windows helper for the your_vpn desktop client.
//
// Why it exists: TUN mode needs administrator rights (wintun adapter + routes + DNS), and a running
// process cannot gain them — the GUI used to relaunch itself through UAC on every start. The service
// is installed ONCE by the installer (that is the single UAC prompt); from then on it runs as
// LocalSystem and owns the privileged work, so the GUI launches and runs with no elevation.
//
// This is phase 1: the service lifecycle + a local control channel that only the installing user and
// administrators can reach (loopback + a per-install secret). The privileged TUN operations and the
// engine are wired in the next phase.
package main

import (
	"bufio"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	"golang.org/x/sys/windows/svc"
)

const (
	serviceName = "your_vpn_service"
	displayName = "your_vpn Service"

	// Loopback control channel. Distinct from the GUI's own ports (47638/47639/47640).
	listenAddr = "127.0.0.1:47641"
)

var dataDir = filepath.Join(os.Getenv("ProgramData"), "your_vpn")

func main() {
	install := flag.Bool("install", false, "register and start the Windows service")
	remove := flag.Bool("remove", false, "stop and delete the Windows service")
	foreground := flag.Bool("run", false, "run in the foreground (debugging, no SCM)")
	flag.Parse()

	switch {
	case *install:
		if err := installService(); err != nil {
			log.Fatalf("install: %v", err)
		}
		fmt.Println("your_vpn_service installed")
	case *remove:
		if err := removeService(); err != nil {
			log.Fatalf("remove: %v", err)
		}
		fmt.Println("your_vpn_service removed")
	case *foreground:
		runForeground()
	default:
		isService, err := svc.IsWindowsService()
		if err != nil {
			log.Fatalf("IsWindowsService: %v", err)
		}
		if !isService {
			log.Fatal("not started by the Service Control Manager; use -run to run in the foreground")
		}
		if err := svc.Run(serviceName, &handler{}); err != nil {
			log.Fatalf("svc.Run: %v", err)
		}
	}
}

// ---- service lifecycle ----

type handler struct{}

func (h *handler) Execute(args []string, r <-chan svc.ChangeRequest, changes chan<- svc.Status) (bool, uint32) {
	changes <- svc.Status{State: svc.StartPending}

	stop := make(chan struct{})
	go serve(stop)

	changes <- svc.Status{State: svc.Running, Accepts: svc.AcceptStop | svc.AcceptShutdown}

	for c := range r {
		switch c.Cmd {
		case svc.Stop, svc.Shutdown:
			close(stop)
			_ = stopEngine()
			changes <- svc.Status{State: svc.StopPending}
			return false, 0
		}
	}
	return false, 0
}

func runForeground() {
	stop := make(chan struct{})
	go serve(stop)
	log.Println("your_vpn_service running in foreground; Ctrl+C to stop")
	select {}
}

func installService() error {
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	// Register (idempotent: ignore "already exists"), then start. binPath/DisplayName are quoted
	// because the install dir ("C:\Program Files\your_vpn") and the display name contain spaces.
	_ = runSc("create", serviceName, "binPath=", "\""+exe+"\"", "start=", "auto", "DisplayName=", "\""+displayName+"\"")
	return runSc("start", serviceName)
}

func removeService() error {
	_ = runSc("stop", serviceName)
	return runSc("delete", serviceName)
}

func runSc(args ...string) error {
	out, err := exec.Command("sc.exe", args...).CombinedOutput()
	if err != nil {
		return fmt.Errorf("sc %s: %v: %s", strings.Join(args, " "), err, strings.TrimSpace(string(out)))
	}
	return nil
}

// ---- control channel ----

func serve(stop <-chan struct{}) {
	if err := os.MkdirAll(dataDir, 0o755); err != nil {
		log.Printf("mkdir %s: %v", dataDir, err)
	}

	token, err := loadOrCreateToken()
	if err != nil {
		log.Printf("token: %v", err)
		return
	}

	ln, err := net.Listen("tcp", listenAddr)
	if err != nil {
		log.Printf("listen %s: %v", listenAddr, err)
		return
	}
	defer ln.Close()

	go func() {
		<-stop
		_ = ln.Close()
	}()

	log.Printf("your_vpn_service listening on %s", listenAddr)
	for {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		go handleConn(conn, token)
	}
}

// loadOrCreateToken keeps a per-install secret in %ProgramData%\your_vpn\service.token. The channel is
// loopback-only, and the token makes it non-trivial for another local user to drive the privileged
// service: the file's ACL (set at install) grants read to the installing user and administrators only.
func loadOrCreateToken() (string, error) {
	path := filepath.Join(dataDir, "service.token")
	if b, err := os.ReadFile(path); err == nil && len(b) > 0 {
		return strings.TrimSpace(string(b)), nil
	}
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	token := hex.EncodeToString(buf)
	if err := os.WriteFile(path, []byte(token), 0o600); err != nil {
		return "", err
	}
	return token, nil
}

type request struct {
	Cmd          string   `json:"cmd"`
	Token        string   `json:"token"`
	Name         string   `json:"name"`
	Address      string   `json:"address"`
	PrefixLength int      `json:"prefixLength"`
	DNS          []string `json:"dns"`
	Routes       []string `json:"routes"`
	Config       string   `json:"config"`
}

func handleConn(conn net.Conn, token string) {
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(30 * time.Second))

	sc := bufio.NewScanner(conn)
	if !sc.Scan() {
		return
	}
	var req request
	if err := json.Unmarshal(sc.Bytes(), &req); err != nil {
		writeJSON(conn, map[string]any{"ok": false, "error": "bad request"})
		return
	}
	if req.Token != token {
		writeJSON(conn, map[string]any{"ok": false, "error": "unauthorized"})
		return
	}

	switch req.Cmd {
	case "hello":
		writeJSON(conn, map[string]any{"ok": true, "service": serviceName, "version": version})
	case "status":
		writeJSON(conn, map[string]any{"ok": true, "state": tunStateName()})
	case "startTun":
		if err := startTun(req.Name, req.Address, req.PrefixLength, req.DNS, req.Routes); err != nil {
			writeJSON(conn, map[string]any{"ok": false, "error": err.Error()})
		} else {
			writeJSON(conn, map[string]any{"ok": true, "state": "tun-up"})
		}
	case "stopTun":
		_ = stopTun()
		writeJSON(conn, map[string]any{"ok": true, "state": "idle"})
	case "startEngine":
		if err := startEngine(req.Config); err != nil {
			writeJSON(conn, map[string]any{"ok": false, "error": err.Error()})
		} else {
			writeJSON(conn, map[string]any{"ok": true, "state": "engine-up"})
		}
	case "stopEngine":
		_ = stopEngine()
		writeJSON(conn, map[string]any{"ok": true, "state": "idle"})
	default:
		writeJSON(conn, map[string]any{"ok": false, "error": "unknown command"})
	}
}

func writeJSON(conn net.Conn, v any) {
	_ = json.NewEncoder(conn).Encode(v)
}

const version = "1.4.1"
