package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"syscall"
	"time"

	"go.uber.org/zap"
	"golang.zx2c4.com/wireguard/wgctrl/wgtypes"

	"github.com/veritasvpn/lib/logging"
	"github.com/veritasvpn/services/veritas-agent/internal/firewall"
	"github.com/veritasvpn/services/veritas-agent/internal/metrics"
	"github.com/veritasvpn/services/veritas-agent/internal/peer"
	"github.com/veritasvpn/services/veritas-agent/internal/wireguard"
)

type RegisterServerRequest struct {
	Hostname  string `json:"hostname"`
	PublicKey string `json:"public_key"`
	PublicIP  string `json:"public_ip"`
	WGPort    int32  `json:"wg_port"`
	Region    string `json:"region"`
	City      string `json:"city"`
	Country   string `json:"country"`
	AuthToken string `json:"auth_token"`
}

type RegisterServerResponse struct {
	ServerID  string `json:"server_id"`
	WGSubnet  string `json:"wg_subnet"`
	DNSServer string `json:"dns_server"`
	Capacity  int32  `json:"capacity"`
}

type HeartbeatRequest struct {
	ServerID   string  `json:"server_id"`
	PeerCount  int32   `json:"peer_count"`
	LoadFactor float64 `json:"load_factor"`
	RXBytes    int64   `json:"rx_bytes"`
	TXBytes    int64   `json:"tx_bytes"`
}

type PeerUpdate struct {
	Action       string   `json:"action"`
	PeerID       string   `json:"peer_id"`
	PublicKey    string   `json:"public_key"`
	PresharedKey string   `json:"preshared_key"`
	AllowedIPs   []string `json:"allowed_ips"`
}

type AgentManagerClient interface {
	RegisterServer(ctx context.Context, req *RegisterServerRequest) (*RegisterServerResponse, error)
	SendHeartbeat(ctx context.Context, req *HeartbeatRequest) error
	StreamPeerUpdates(ctx context.Context, serverID, authToken string) (<-chan *PeerUpdate, <-chan error)
	ReportPeerApplied(ctx context.Context, serverID, peerID, authToken string) error
}

type httpAgentClient struct {
	baseURL    string
	httpClient *http.Client
}

func NewAgentClient(endpoint string) *httpAgentClient {
	return &httpAgentClient{
		baseURL: strings.TrimRight(endpoint, "/"),
		httpClient: &http.Client{
			Timeout: 10 * time.Second,
		},
	}
}

func (c *httpAgentClient) streamClient() *http.Client {
	return &http.Client{
		// SSE streams must not use a request timeout.
		Timeout: 0,
	}
}

func (c *httpAgentClient) RegisterServer(ctx context.Context, req *RegisterServerRequest) (*RegisterServerResponse, error) {
	body, err := json.Marshal(req)
	if err != nil {
		return nil, fmt.Errorf("marshal register request: %w", err)
	}

	httpReq, err := http.NewRequestWithContext(ctx, "POST", c.baseURL+"/api/v1/agents/register", bytes.NewReader(body))
	if err != nil {
		return nil, fmt.Errorf("create register request: %w", err)
	}
	httpReq.Header.Set("Content-Type", "application/json")

	resp, err := c.httpClient.Do(httpReq)
	if err != nil {
		return nil, fmt.Errorf("register request: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		data, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("register returned %d: %s", resp.StatusCode, string(data))
	}

	var r RegisterServerResponse
	if err := json.NewDecoder(resp.Body).Decode(&r); err != nil {
		return nil, fmt.Errorf("decode register response: %w", err)
	}
	return &r, nil
}

func (c *httpAgentClient) SendHeartbeat(ctx context.Context, req *HeartbeatRequest) error {
	body, err := json.Marshal(req)
	if err != nil {
		return fmt.Errorf("marshal heartbeat: %w", err)
	}

	httpReq, err := http.NewRequestWithContext(ctx, "POST", c.baseURL+"/api/v1/agents/heartbeat", bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("create heartbeat request: %w", err)
	}
	httpReq.Header.Set("Content-Type", "application/json")

	resp, err := c.httpClient.Do(httpReq)
	if err != nil {
		return fmt.Errorf("heartbeat request: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusNoContent {
		data, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("heartbeat returned %d: %s", resp.StatusCode, string(data))
	}
	return nil
}

func (c *httpAgentClient) StreamPeerUpdates(ctx context.Context, serverID, authToken string) (<-chan *PeerUpdate, <-chan error) {
	updateCh := make(chan *PeerUpdate, 10)
	errCh := make(chan error, 1)

	go func() {
		defer close(updateCh)
		defer close(errCh)

		url := fmt.Sprintf("%s/api/v1/agents/peers/stream?server_id=%s", c.baseURL, urlQueryEscape(serverID))
		httpReq, err := http.NewRequestWithContext(ctx, "GET", url, nil)
		if err != nil {
			errCh <- err
			return
		}
		httpReq.Header.Set("Accept", "text/event-stream")
		httpReq.Header.Set("Authorization", "Bearer "+authToken)

		resp, err := c.streamClient().Do(httpReq)
		if err != nil {
			errCh <- err
			return
		}
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusOK {
			data, _ := io.ReadAll(resp.Body)
			errCh <- fmt.Errorf("stream returned %d: %s", resp.StatusCode, string(data))
			return
		}

		scanner := bufio.NewScanner(resp.Body)
		scanner.Buffer(make([]byte, 0, 64*1024), 1024*1024)
		for scanner.Scan() {
			line := scanner.Text()
			if !strings.HasPrefix(line, "data: ") {
				continue
			}
			payload := strings.TrimPrefix(line, "data: ")
			if payload == "" {
				continue
			}

			var update PeerUpdate
			if err := json.Unmarshal([]byte(payload), &update); err != nil {
				errCh <- fmt.Errorf("parse peer update: %w", err)
				return
			}
			select {
			case updateCh <- &update:
			case <-ctx.Done():
				return
			}
		}
		if err := scanner.Err(); err != nil {
			select {
			case errCh <- err:
			default:
			}
		}
	}()

	return updateCh, errCh
}

func (c *httpAgentClient) ReportPeerApplied(ctx context.Context, serverID, peerID, authToken string) error {
	body, err := json.Marshal(map[string]string{
		"server_id": serverID,
		"peer_id":   peerID,
	})
	if err != nil {
		return fmt.Errorf("marshal applied request: %w", err)
	}

	httpReq, err := http.NewRequestWithContext(ctx, "POST", c.baseURL+"/api/v1/agents/peers/applied", bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("create applied request: %w", err)
	}
	httpReq.Header.Set("Content-Type", "application/json")
	httpReq.Header.Set("Authorization", "Bearer "+authToken)

	resp, err := c.httpClient.Do(httpReq)
	if err != nil {
		return fmt.Errorf("applied request: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusNoContent {
		data, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("applied returned %d: %s", resp.StatusCode, string(data))
	}
	return nil
}

func urlQueryEscape(s string) string {
	return strings.ReplaceAll(s, " ", "%20")
}

type AgentConfig struct {
	AuthToken       string
	WGInterface     string
	WGPort          int
	WGSubnet        string
	ManagerEndpoint string
	MetricsPort     string
	ServerHostname  string
	ServerRegion    string
	ServerCity      string
	ServerCountry   string
}

func LoadAgentConfig() *AgentConfig {
	hostname, _ := os.Hostname()
	port, _ := strconv.Atoi(envOrDefault("WG_PORT", "51820"))

	return &AgentConfig{
		AuthToken:       os.Getenv("AGENT_AUTH_TOKEN"),
		WGInterface:     envOrDefault("WG_INTERFACE", "wg0"),
		WGPort:          port,
		WGSubnet:        os.Getenv("WG_SUBNET"),
		ManagerEndpoint: envOrDefault("MANAGER_ENDPOINT", "http://wg-manager:8080"),
		MetricsPort:     envOrDefault("METRICS_PORT", "9090"),
		ServerHostname:  envOrDefault("SERVER_HOSTNAME", hostname),
		ServerRegion:    os.Getenv("SERVER_REGION"),
		ServerCity:      os.Getenv("SERVER_CITY"),
		ServerCountry:   os.Getenv("SERVER_COUNTRY"),
	}
}

func envOrDefault(key, defaultVal string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return defaultVal
}

type Agent struct {
	cfg           *AgentConfig
	logger        *logging.Logger
	wgManager     *wireguard.Manager
	peerManager   *peer.Manager
	fwManager     *firewall.Manager
	metrics       *metrics.Metrics
	managerClient AgentManagerClient
	serverID      string
	publicKey     string
	startTime     time.Time
	prevRXBytes   int64
	prevTXBytes   int64
}

func NewAgent(cfg *AgentConfig, logger *logging.Logger) (*Agent, error) {
	wm, err := wireguard.NewManager(cfg.WGInterface)
	if err != nil {
		return nil, fmt.Errorf("init wireguard manager: %w", err)
	}

	return &Agent{
		cfg:         cfg,
		logger:      logger,
		wgManager:   wm,
		peerManager: peer.New(wm),
		fwManager:   firewall.New(),
		startTime:   time.Now(),
	}, nil
}

func (a *Agent) Run() error {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	privKey, publicKey, err := a.getServerKey()
	if err != nil {
		return fmt.Errorf("server key: %w", err)
	}
	a.publicKey = publicKey

	serverAddr := "10.0.0.1/24"
	if a.cfg.WGSubnet != "" {
		serverAddr = strings.Replace(a.cfg.WGSubnet, ".0/24", ".1/24", 1)
	}
	if err := a.wgManager.EnsureInterface(privKey, a.cfg.WGPort, serverAddr); err != nil {
		return fmt.Errorf("ensure wireguard interface: %w", err)
	}

	if err := a.setupFirewall(); err != nil {
		return fmt.Errorf("firewall setup: %w", err)
	}

	a.managerClient = NewAgentClient(a.cfg.ManagerEndpoint)

	resp, err := a.registerWithManager(ctx)
	if err != nil {
		return fmt.Errorf("register with manager: %w", err)
	}
	a.serverID = resp.ServerID
	if resp.WGSubnet != "" {
		a.cfg.WGSubnet = resp.WGSubnet
		addr := strings.Replace(resp.WGSubnet, ".0/24", ".1/24", 1)
		if err := a.wgManager.EnsureInterface(privKey, a.cfg.WGPort, addr); err != nil {
			a.logger.Warn("failed to apply registered subnet address", zap.Error(err))
		}
	}

	a.logger.Info("Registered with wg-manager",
		zap.String("server_id", resp.ServerID))

	a.metrics = metrics.New(a.cfg.MetricsPort)

	go func() {
		if err := a.metrics.Start(); err != nil {
			a.logger.Error("Metrics server error", zap.Error(err))
		}
	}()

	go a.heartbeatLoop(ctx)

	go a.streamLoop(ctx)

	a.logger.Info("Agent running",
		zap.String("interface", a.cfg.WGInterface),
		zap.Int("port", a.cfg.WGPort),
		zap.String("server_id", a.serverID))

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	sig := <-sigCh
	a.logger.Info("Received signal, shutting down", zap.String("signal", sig.String()))

	cancel()

	a.logger.Info("Cleaning up firewall rules")
	if err := a.fwManager.Cleanup(); err != nil {
		a.logger.Error("Firewall cleanup error", zap.Error(err))
	}

	a.logger.Info("Closing WireGuard client")
	if err := a.wgManager.Close(); err != nil {
		a.logger.Error("WireGuard close error", zap.Error(err))
	}

	return nil
}

func (a *Agent) getServerKey() (wgtypes.Key, string, error) {
	keyPath := "/etc/wireguard/private.key"
	if kf := os.Getenv("WG_PRIVATE_KEY_FILE"); kf != "" {
		keyPath = kf
	}

	data, err := os.ReadFile(keyPath)
	if err == nil {
		key, err := wgtypes.ParseKey(strings.TrimSpace(string(data)))
		if err == nil {
			return key, key.PublicKey().String(), nil
		}
		a.logger.Warn("Existing private key unparseable, generating new one",
			zap.String("path", keyPath))
	}

	privKey, err := wgtypes.GeneratePrivateKey()
	if err != nil {
		return wgtypes.Key{}, "", fmt.Errorf("generate wg key: %w", err)
	}

	if err := os.MkdirAll(filepath.Dir(keyPath), 0700); err != nil {
		a.logger.Warn("Failed to create key directory",
			zap.String("path", filepath.Dir(keyPath)), zap.Error(err))
	}
	if err := os.WriteFile(keyPath, []byte(privKey.String()), 0600); err != nil {
		a.logger.Warn("Failed to persist private key",
			zap.String("path", keyPath), zap.Error(err))
	}

	return privKey, privKey.PublicKey().String(), nil
}

func (a *Agent) setupFirewall() error {
	if err := enableIPForward(); err != nil {
		a.logger.Warn("ip_forward enable failed (non-fatal)", zap.Error(err))
	}
	if err := ensureMasquerade(a.cfg.WGSubnet, a.cfg.WGInterface); err != nil {
		a.logger.Warn("iptables MASQUERADE failed (non-fatal)", zap.Error(err))
	}
	if err := ensureForwardAccept(a.cfg.WGInterface); err != nil {
		a.logger.Warn("iptables FORWARD accept failed (non-fatal)", zap.Error(err))
	}

	if err := a.fwManager.SetupNAT(a.cfg.WGInterface); err != nil {
		a.logger.Warn("NAT setup failed (non-fatal)", zap.Error(err))
	}
	if err := a.fwManager.SetupKillSwitch(a.cfg.WGInterface, a.cfg.WGPort); err != nil {
		a.logger.Warn("Kill switch setup failed (non-fatal)", zap.Error(err))
	}

	a.logger.Info("Firewall rules configured",
		zap.String("interface", a.cfg.WGInterface),
		zap.Int("port", a.cfg.WGPort))
	return nil
}

func enableIPForward() error {
	return os.WriteFile("/proc/sys/net/ipv4/ip_forward", []byte("1"), 0644)
}

func ensureMasquerade(subnet, wgIface string) error {
	if subnet == "" {
		subnet = "10.0.0.0/24"
	}
	egress := os.Getenv("EGRESS_IFACE")
	if egress == "" {
		out, err := exec.Command("sh", "-c", "ip route show default | awk '{print $5; exit}'").Output()
		if err != nil {
			return err
		}
		egress = strings.TrimSpace(string(out))
	}
	if egress == "" {
		return fmt.Errorf("no egress iface")
	}
	_ = wgIface
	check := exec.Command("iptables", "-t", "nat", "-C", "POSTROUTING", "-s", subnet, "-o", egress, "-j", "MASQUERADE")
	if check.Run() == nil {
		return nil
	}
	return exec.Command("iptables", "-t", "nat", "-A", "POSTROUTING", "-s", subnet, "-o", egress, "-j", "MASQUERADE").Run()
}

// ensureForwardAccept inserts ACCEPT rules at the top of FORWARD so WG traffic is
// not dropped by a default DROP policy / UFW reject rules later in the chain.
func ensureForwardAccept(wgIface string) error {
	if wgIface == "" {
		wgIface = "wg0"
	}
	_ = exec.Command("iptables", "-D", "FORWARD", "-i", wgIface, "-j", "ACCEPT").Run()
	_ = exec.Command("iptables", "-D", "FORWARD", "-o", wgIface, "-j", "ACCEPT").Run()
	_ = exec.Command("iptables", "-D", "FORWARD", "-m", "conntrack", "--ctstate", "RELATED,ESTABLISHED", "-j", "ACCEPT").Run()

	if err := exec.Command("iptables", "-I", "FORWARD", "1", "-o", wgIface, "-j", "ACCEPT").Run(); err != nil {
		return err
	}
	if err := exec.Command("iptables", "-I", "FORWARD", "1", "-i", wgIface, "-j", "ACCEPT").Run(); err != nil {
		return err
	}
	if err := exec.Command("iptables", "-I", "FORWARD", "1", "-m", "conntrack", "--ctstate", "RELATED,ESTABLISHED", "-j", "ACCEPT").Run(); err != nil {
		return err
	}
	_ = exec.Command("iptables", "-D", "FORWARD", "-p", "tcp", "--tcp-flags", "SYN,RST", "SYN", "-j", "TCPMSS", "--clamp-mss-to-pmtu").Run()
	return exec.Command("iptables", "-I", "FORWARD", "1", "-p", "tcp", "--tcp-flags", "SYN,RST", "SYN", "-j", "TCPMSS", "--clamp-mss-to-pmtu").Run()
}

func (a *Agent) registerWithManager(ctx context.Context) (*RegisterServerResponse, error) {
	publicIP, _ := getPublicIP()

	req := &RegisterServerRequest{
		Hostname:  a.cfg.ServerHostname,
		PublicKey: a.publicKey,
		PublicIP:  publicIP,
		WGPort:    int32(a.cfg.WGPort),
		Region:    a.cfg.ServerRegion,
		City:      a.cfg.ServerCity,
		Country:   a.cfg.ServerCountry,
		AuthToken: a.cfg.AuthToken,
	}

	return a.managerClient.RegisterServer(ctx, req)
}

func (a *Agent) heartbeatLoop(ctx context.Context) {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
		case <-ctx.Done():
			return
		}

		rx, tx, count, activeCount := a.peerManager.GetStats()
		loadFactor := getLoadFactor()

		req := &HeartbeatRequest{
			ServerID:   a.serverID,
			PeerCount:  count,
			LoadFactor: loadFactor,
			RXBytes:    rx,
			TXBytes:    tx,
		}

		if err := a.managerClient.SendHeartbeat(ctx, req); err != nil {
			a.logger.Error("Heartbeat failed", zap.Error(err))
		}

		deltaRX := rx - a.prevRXBytes
		deltaTX := tx - a.prevTXBytes
		if deltaRX > 0 {
			a.metrics.RXBytesTotal.Add(float64(deltaRX))
		}
		if deltaTX > 0 {
			a.metrics.TXBytesTotal.Add(float64(deltaTX))
		}
		a.prevRXBytes = rx
		a.prevTXBytes = tx

		a.metrics.PeerCount.Set(float64(count))
		a.metrics.ActivePeerCount.Set(float64(activeCount))
		a.metrics.UptimeSeconds.Add(30)
		a.metrics.CPUUsage.Set(loadFactor * 100)
		a.metrics.MemoryUsage.Set(float64(getMemoryUsage()))
	}
}

func (a *Agent) streamLoop(ctx context.Context) {
	for {
		select {
		case <-ctx.Done():
			return
		default:
		}

		a.logger.Info("Connecting to peer update stream")
		updates, errs := a.managerClient.StreamPeerUpdates(ctx, a.serverID, a.cfg.AuthToken)

		for {
			select {
			case update, ok := <-updates:
				if !ok {
					a.logger.Warn("Peer update stream closed, reconnecting...")
					goto reconnect
				}
				a.handlePeerUpdate(update)
			case err, ok := <-errs:
				if !ok || err == io.EOF {
					goto reconnect
				}
				if err != nil {
					a.logger.Error("Peer update stream error", zap.Error(err))
					goto reconnect
				}
			case <-ctx.Done():
				return
			}
		}

	reconnect:
		select {
		case <-ctx.Done():
			return
		case <-time.After(5 * time.Second):
		}
	}
}

func (a *Agent) handlePeerUpdate(update *PeerUpdate) {
	switch update.Action {
	case "ADD":
		if err := a.peerManager.AddPeer(update.PeerID, update.PublicKey, update.PresharedKey, []string{"0.0.0.0/0"}); err != nil {
			a.logger.Error("Failed to add peer",
				zap.String("peer_id", update.PeerID), zap.Error(err))
			return
		}
		a.logger.Info("Peer added", zap.String("peer_id", update.PeerID))
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := a.managerClient.ReportPeerApplied(ctx, a.serverID, update.PeerID, a.cfg.AuthToken); err != nil {
			a.logger.Warn("Failed to report peer applied",
				zap.String("peer_id", update.PeerID), zap.Error(err))
		}
	case "REMOVE":
		if err := a.peerManager.RemovePeer(update.PublicKey); err != nil {
			a.logger.Error("Failed to remove peer",
				zap.String("peer_id", update.PeerID), zap.Error(err))
			return
		}
		a.logger.Info("Peer removed", zap.String("peer_id", update.PeerID))
	default:
		a.logger.Warn("Unknown peer update action",
			zap.String("action", update.Action))
	}
}

func getPublicIP() (string, error) {
	if ip := os.Getenv("PUBLIC_IP"); ip != "" {
		return ip, nil
	}

	client := &http.Client{Timeout: 5 * time.Second}
	resp, err := client.Get("https://api.ipify.org?format=text")
	if err != nil {
		return "", fmt.Errorf("fetch public ip: %w", err)
	}
	defer resp.Body.Close()

	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", fmt.Errorf("read public ip: %w", err)
	}
	return strings.TrimSpace(string(data)), nil
}

func getLoadFactor() float64 {
	data, err := os.ReadFile("/proc/loadavg")
	if err != nil {
		return 0.0
	}
	parts := strings.Fields(string(data))
	if len(parts) < 1 {
		return 0.0
	}
	load, _ := strconv.ParseFloat(parts[0], 64)
	return load
}

func getMemoryUsage() int64 {
	var m runtime.MemStats
	runtime.ReadMemStats(&m)
	return int64(m.Alloc)
}

func main() {
	cfg := LoadAgentConfig()

	if cfg.AuthToken == "" {
		log.Fatal("AGENT_AUTH_TOKEN environment variable is required")
	}

	logger, err := logging.New(envOrDefault("LOG_LEVEL", "info"))
	if err != nil {
		log.Fatalf("Failed to initialize logger: %v", err)
	}
	defer logger.Sync()

	agent, err := NewAgent(cfg, logger)
	if err != nil {
		logger.Fatal("Failed to create agent", zap.Error(err))
	}

	if err := agent.Run(); err != nil {
		logger.Fatal("Agent failed", zap.Error(err))
	}

	logger.Info("Agent stopped")
}
