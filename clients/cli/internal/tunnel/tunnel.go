package tunnel

import (
	"bufio"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

type Config struct {
	PrivateKey     string
	Address        string
	DNS            string
	ServerPubkey   string
	Endpoint       string
	AllowedIPs     []string
	PresharedKey   string
}

type Tunnel struct {
	ifaceName string
	uapiPath  string
	confPath  string
	cmd       *exec.Cmd
}

func Up(ifaceName, confPath string) (*Tunnel, error) {
	cfg, err := parseConfig(confPath)
	if err != nil {
		return nil, err
	}

	wgBin := resolveWireguardGo()
	if wgBin == "" {
		return nil, fmt.Errorf("wireguard-go binary not found — run 'veritas install' first")
	}

	dir := filepath.Dir(confPath)
	uapiPath := filepath.Join(dir, "uapi.txt")

	addr := strings.Split(cfg.Address, "/")[0]
	if addr == "" {
		return nil, fmt.Errorf("missing address in config")
	}

	privHex, err := b64KeyToHex(cfg.PrivateKey)
	if err != nil {
		return nil, fmt.Errorf("bad private key: %w", err)
	}

	pubHex, err := b64KeyToHex(cfg.ServerPubkey)
	if err != nil {
		return nil, fmt.Errorf("bad server public key: %w", err)
	}

	allowed := cfg.AllowedIPs
	if len(allowed) == 0 {
		allowed = []string{"0.0.0.0/0"}
	}

	var uapi strings.Builder
	uapi.WriteString(fmt.Sprintf("set=1\nprivate_key=%s\nreplace_peers=true\n", privHex))
	uapi.WriteString(fmt.Sprintf("public_key=%s\nendpoint=%s\npersistent_keepalive_interval=25\n", pubHex, cfg.Endpoint))

	if cfg.PresharedKey != "" {
		pskHex, err := b64KeyToHex(cfg.PresharedKey)
		if err != nil {
			return nil, fmt.Errorf("bad preshared key: %w", err)
		}
		uapi.WriteString(fmt.Sprintf("preshared_key=%s\n", pskHex))
	}

	for _, ip := range allowed {
		uapi.WriteString(fmt.Sprintf("allowed_ip=%s\n", strings.TrimSpace(ip)))
	}

	dns := cfg.DNS
	if dns == "" {
		dns = "1.1.1.1"
	}
	uapi.WriteString(fmt.Sprintf("dns=%s\n\n", dns))

	if err := os.WriteFile(uapiPath, []byte(uapi.String()), 0600); err != nil {
		return nil, fmt.Errorf("write uapi: %w", err)
	}

	cmd := exec.Command(wgBin, ifaceName)
	cmd.Stdin, _ = os.Open(uapiPath)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr

	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("start wireguard-go: %w", err)
	}

	time.Sleep(500 * time.Millisecond)

	if err := exec.Command("ip", "addr", "add", cfg.Address, "dev", ifaceName).Run(); err != nil {
		_ = cmd.Process.Kill()
		return nil, fmt.Errorf("set address: %w (try: sudo ip addr add %s dev %s)", err, cfg.Address, ifaceName)
	}

	if err := exec.Command("ip", "link", "set", "up", "dev", ifaceName).Run(); err != nil {
		_ = cmd.Process.Kill()
		return nil, fmt.Errorf("set link up: %w", err)
	}

	for _, cidr := range allowed {
		cmd := exec.Command("ip", "route", "add", cidr, "dev", ifaceName)
		if err := cmd.Run(); err != nil {
			_ = exec.Command("ip", "route", "replace", cidr, "dev", ifaceName).Run()
		}
	}

	_ = os.WriteFile(filepath.Join(dir, "wireguard-go.pid"), []byte(fmt.Sprintf("%d", cmd.Process.Pid)), 0600)
	_ = os.WriteFile(filepath.Join(dir, "iface"), []byte(ifaceName), 0600)

	return &Tunnel{
		ifaceName: ifaceName,
		uapiPath:  uapiPath,
		confPath:  confPath,
		cmd:       cmd,
	}, nil
}

func (t *Tunnel) Down() {
	if t.cmd != nil && t.cmd.Process != nil {
		_ = t.cmd.Process.Signal(os.Interrupt)
		_ = t.cmd.Wait()
	}

	_ = exec.Command("ip", "link", "delete", t.ifaceName).Run()

	dir := filepath.Dir(t.confPath)
	_ = os.Remove(t.uapiPath)
	_ = os.Remove(t.confPath)
	_ = os.Remove(filepath.Join(dir, "wireguard-go.pid"))
	_ = os.Remove(filepath.Join(dir, "iface"))
}

func parseConfig(path string) (*Config, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}

	cfg := &Config{}
	scanner := bufio.NewScanner(strings.NewReader(string(data)))
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") || strings.HasPrefix(line, "[") {
			continue
		}
		parts := strings.SplitN(line, "=", 2)
		if len(parts) != 2 {
			continue
		}
		key := strings.TrimSpace(parts[0])
		val := strings.TrimSpace(parts[1])

		switch key {
		case "PrivateKey":
			cfg.PrivateKey = val
		case "Address":
			cfg.Address = val
		case "DNS":
			cfg.DNS = val
		case "PublicKey":
			cfg.ServerPubkey = val
		case "Endpoint":
			cfg.Endpoint = val
		case "AllowedIPs":
			for _, ip := range strings.Split(val, ",") {
				cfg.AllowedIPs = append(cfg.AllowedIPs, strings.TrimSpace(ip))
			}
		case "PresharedKey":
			cfg.PresharedKey = val
		}
	}

	return cfg, nil
}

func b64KeyToHex(b64 string) (string, error) {
	bytes, err := base64.StdEncoding.DecodeString(strings.TrimSpace(b64))
	if err != nil {
		return "", fmt.Errorf("decode key: %w", err)
	}
	if len(bytes) != 32 {
		return "", fmt.Errorf("key must be 32 bytes, got %d", len(bytes))
	}
	return hex.EncodeToString(bytes), nil
}

func resolveWireguardGo() string {
	exe, err := os.Executable()
	if err == nil {
		candidates := []string{
			filepath.Join(filepath.Dir(exe), "wireguard-go"),
			filepath.Join(filepath.Dir(exe), "bin", "wireguard-go"),
		}
		for _, p := range candidates {
			if _, err := os.Stat(p); err == nil {
				return p
			}
		}
	}
	for _, p := range []string{
		"/usr/local/bin/wireguard-go",
		"/usr/bin/wireguard-go",
	} {
		if _, err := os.Stat(p); err == nil {
			return p
		}
	}
	if p, err := exec.LookPath("wireguard-go"); err == nil {
		return p
	}
	return ""
}
