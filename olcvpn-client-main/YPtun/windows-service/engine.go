package main

import (
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"sync"
)

// The service hosts the engine as a child process running as LocalSystem. sing-box creates the TUN
// adapter and installs its routes/DNS itself (auto_route), so nothing in the GUI needs elevation.
// The GUI only sends the sing-box config and talks to the local inbound.

var (
	engineMu  sync.Mutex
	engineCmd *exec.Cmd
)

func startEngine(config string) error {
	engineMu.Lock()
	defer engineMu.Unlock()
	if engineCmd != nil {
		return nil
	}
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	dir := filepath.Dir(exe)
	singBox := filepath.Join(dir, "sing-box.exe")
	if _, err := os.Stat(singBox); err != nil {
		return fmt.Errorf("sing-box.exe not found next to the service: %w", err)
	}
	cfgPath := filepath.Join(dataDir, "engine.json")
	if err := os.WriteFile(cfgPath, []byte(config), 0o600); err != nil {
		return err
	}
	logPath := filepath.Join(dataDir, "engine.log")
	lf, err := os.OpenFile(logPath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		return err
	}

	cmd := exec.Command(singBox, "run", "-c", cfgPath)
	cmd.Dir = dir
	cmd.Stdout = lf
	cmd.Stderr = lf
	if err := cmd.Start(); err != nil {
		_ = lf.Close()
		return fmt.Errorf("start sing-box: %w", err)
	}
	engineCmd = cmd
	log.Printf("engine started: sing-box run -c %s (pid %d)", cfgPath, cmd.Process.Pid)
	return nil
}

func stopEngine() error {
	engineMu.Lock()
	defer engineMu.Unlock()
	if engineCmd == nil {
		return nil
	}
	if engineCmd.Process != nil {
		_ = engineCmd.Process.Kill()
		_, _ = engineCmd.Process.Wait()
	}
	engineCmd = nil
	log.Printf("engine stopped")
	return nil
}

func engineState() string {
	engineMu.Lock()
	defer engineMu.Unlock()
	if engineCmd == nil {
		return "idle"
	}
	return "engine-up"
}
