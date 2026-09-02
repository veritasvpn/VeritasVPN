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
	dnssvc "github.com/veritasvpn/services/veritas-agent/internal/dns"
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
	ServerID       string            `json:"server_id"`
	PeerCount      int32             `json:"peer_count"`
	LoadFactor     float64           `json:"load_factor"`
	RXBytes        int64             `json:"rx_bytes"`
	TXBytes        int64             `json:"tx_bytes"`
	DNSBlockedByIP map[string]uint64 `json:"dns_blocked_by_ip,omitempty"`
}

type PeerUpdate struct {
	Action       string   `json:"action"`
	PeerID       string   `json:"peer_id"`
	PublicKey    string   `json:"public_key"`
	PresharedKey string   `json:"preshared_key"`
	AllowedIPs   []string `json:"allowed_ips"`
	ForwardID    string   `json:"forward_id"`
	Protocol     string   `json:"protocol"`
	ExternalPort int      `json:"external_port"`
	InternalPort int      `json:"internal_port"`
	AssignedIP   string   `json:"assigned_ip"`
}

type AgentManagerClient interface {
	RegisterServer(ctx context.Context, req *RegisterServerRequest) (*RegisterServerResponse, error)
	SendHeartbeat(ctx context.Context, req *HeartbeatRequest) error
	StreamPeerUpdates(ctx context.Context, serverID, authToken string) (<-chan *PeerUpdate, <-chan error)
	ReportPeerApplied(ctx context.Context, serverID, peerID, authToken string) error
	ReportPeerExpired(ctx context.Context, serverID, peerID, authToken string) error
}

type httpAgentClient struct {
	baseURL    string
	authToken  string
	httpClient *http.Client
}

func NewAgentClient(endpoint, authToken string) *httpAgentClient {
	return &httpAgentClient{
		baseURL:   strings.TrimRight(endpoint, "/"),
		authToken: authToken,
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
		return nil, &httpStatusError{Code: resp.StatusCode, Body: string(data)}
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
	// Always send bearer (same as stream/applied). wg-manager rejects missing/mismatched tokens with 401.
	httpReq.Header.Set("Authorization", "Bearer "+c.authToken)

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

func (c *httpAgentClient) ReportPeerExpired(ctx context.Context, serverID, peerID, authToken string) error {
	body, err := json.Marshal(map[string]string{"server_id": serverID, "peer_id": peerID})
	if err != nil {
		return fmt.Errorf("marshal expired request: %w", err)
	}
	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+"/api/v1/agents/peers/expired", bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("create expired request: %w", err)
	}
	httpReq.Header.Set("Content-Type", "application/json")
	httpReq.Header.Set("Authorization", "Bearer "+authToken)
	resp, err := c.httpClient.Do(httpReq)
	if err != nil {
		return fmt.Errorf("expired request: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusNoContent {
		data, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("expired returned %d: %s", resp.StatusCode, string(data))
	}
	return nil
}

func urlQueryEscape(s string) string {
	return strings.ReplaceAll(s, " ", "%20")
}

type AgentConfig struct {
	AuthToken   string
	WGInterface string
	// WGPort is the local listener; WGPublicPort is advertised to clients.
	// They differ when the router forwards public UDP 443 to Dell UDP 51820.
	WGPort                int
	WGPublicPort          int
	WGSubnet              string
	ManagerEndpoint       string
	MetricsPort           string
	MetricsBind           string
	ServerHostname        string
	ServerRegion          string
	ServerCity            string
	ServerCountry         string
	DNSListen             string
	DNSUpstream           string
	DNSBlocklistURLs      string
	DNSBlocklistRefresh   time.Duration
	DNSBlocklistStateFile string
	BandwidthLimitMbps    int
	PeerNoHandshakeGrace     time.Duration
	PeerStaleAfter           time.Duration
	RegisterRetryInitial     time.Duration
	RegisterRetryMaxInterval time.Duration
	RegisterRetryTimeout     time.Duration
}

func LoadAgentConfig() *AgentConfig {
	hostname, _ := os.Hostname()
	port, _ := strconv.Atoi(envOrDefault("WG_PORT", "51820"))
	publicPort, _ := strconv.Atoi(envOrDefault("WG_PUBLIC_PORT", strconv.Itoa(port)))
	bandwidth, _ := strconv.Atoi(envOrDefault("PEER_BANDWIDTH_LIMIT_MBPS", "150"))

	return &AgentConfig{
		AuthToken:             os.Getenv("AGENT_AUTH_TOKEN"),
		WGInterface:           envOrDefault("WG_INTERFACE", "wg0"),
		WGPort:                port,
		WGPublicPort:          publicPort,
		WGSubnet:              os.Getenv("WG_SUBNET"),
		ManagerEndpoint:       envOrDefault("MANAGER_ENDPOINT", "http://wg-manager:8080"),
		MetricsPort:           envOrDefault("METRICS_PORT", "9090"),
		MetricsBind:           envOrDefault("METRICS_BIND", "0.0.0.0"),
		ServerHostname:        envOrDefault("SERVER_HOSTNAME", hostname),
		ServerRegion:          os.Getenv("SERVER_REGION"),
		ServerCity:            os.Getenv("SERVER_CITY"),
		ServerCountry:         os.Getenv("SERVER_COUNTRY"),
		DNSListen:             envOrDefault("DNS_LISTEN", "10.0.0.1:53"),
		DNSUpstream:           envOrDefault("DNS_UPSTREAM", "https://cloudflare-dns.com/dns-query,https://dns.google/dns-query"),
		DNSBlocklistURLs:      os.Getenv("DNS_BLOCKLIST_URLS"),
		DNSBlocklistRefresh:   durationOrDefault("DNS_BLOCKLIST_REFRESH", 6*time.Hour),
		DNSBlocklistStateFile: envOrDefault("DNS_BLOCKLIST_STATE_FILE", "/var/lib/veritasvpn/dns/blocklist.txt"),
		BandwidthLimitMbps:    bandwidth,
		PeerNoHandshakeGrace:  durationOrDefault("PEER_NO_HANDSHAKE_GRACE", 3*time.Minute),
		// PEER_STALE_AFTER: keep peers through sleep/Wi-Fi blips so reconnects stay sticky
		// and the bandwidth reconciler does not churn add/remove/stale on short gaps.
		PeerStaleAfter: durationOrDefault("PEER_STALE_AFTER", 30*time.Minute),
		// Boot can race wg-manager; retry registration instead of CrashLoopBackOff.
		RegisterRetryInitial:     durationOrDefault("REGISTER_RETRY_INITIAL", time.Second),
		RegisterRetryMaxInterval: durationOrDefault("REGISTER_RETRY_MAX_INTERVAL", 15*time.Second),
		// 0 = retry until the process is signaled. Default covers slow cluster boots.
		RegisterRetryTimeout: durationOrDefault("REGISTER_RETRY_TIMEOUT", 15*time.Minute),
	}
}

func envOrDefault(key, defaultVal string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return defaultVal
}

func durationOrDefault(key string, defaultVal time.Duration) time.Duration {
	value := os.Getenv(key)
	if value == "" {
		return defaultVal
	}
	parsed, err := time.ParseDuration(value)
	if err != nil || parsed <= 0 {
		return defaultVal
	}
	return parsed
}

type Agent struct {
	cfg           *AgentConfig
	logger        *logging.Logger
	wgManager     *wireguard.Manager
	peerManager   *peer.Manager
	fwManager     *firewall.Manager
	dnsForwarder  *dnssvc.Forwarder
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

	a.metrics = metrics.NewWithBind(a.cfg.MetricsPort, a.cfg.MetricsBind)
	a.dnsForwarder = dnssvc.New(dnssvc.Config{
		ListenAddr:         a.cfg.DNSListen,
		UpstreamAddr:       a.cfg.DNSUpstream,
		BlocklistURLs:      a.cfg.DNSBlocklistURLs,
		BlocklistRefresh:   a.cfg.DNSBlocklistRefresh,
		BlocklistStateFile: a.cfg.DNSBlocklistStateFile,
	}, a.metrics, a.logger)
	if err := a.dnsForwarder.Start(ctx); err != nil {
		return fmt.Errorf("start encrypted DNS forwarder: %w", err)
	}

	if err := a.setupFirewall(); err != nil {
		return fmt.Errorf("firewall setup: %w", err)
	}

	// Serve /metrics before registration so startupProbe stays healthy while we
	// wait for wg-manager during cluster boot races.
	go func() {
		if err := a.metrics.Start(); err != nil {
			a.logger.Error("Metrics server error", zap.Error(err))
		}
	}()

	a.managerClient = NewAgentClient(a.cfg.ManagerEndpoint, a.cfg.AuthToken)

	resp, err := a.registerWithManagerRetry(ctx)
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

	if a.dnsForwarder != nil {
		if err := a.dnsForwarder.Shutdown(); err != nil {
			a.logger.Error("DNS forwarder shutdown error", zap.Error(err))
		}
	}

	// Leave nftables table veritas in place across restarts so isolation never
	// disappears while the agent is down.
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

	// Port-forward table must exist before Reconcile so DNAT state can survive
	// veritas table rebuilds.
	if err := a.fwManager.EnsurePortForwardTable(a.cfg.WGInterface); err != nil {
		return fmt.Errorf("ensure port-forward nftables table: %w", err)
	}

	// nftables owns NAT + fail-closed forward isolation. Host tc owns bandwidth.
	// A failure stops startup so the agent never advertises a VPN without isolation.
	if err := a.fwManager.Reconcile(a.cfg.WGInterface, a.cfg.WGPort, a.cfg.BandwidthLimitMbps); err != nil {
		return fmt.Errorf("reconcile nftables firewall: %w", err)
	}

	a.logger.Info("Firewall rules configured",
		zap.String("interface", a.cfg.WGInterface),
		zap.Int("port", a.cfg.WGPort))
	return nil
}

func enableIPForward() error {
	current, err := os.ReadFile("/proc/sys/net/ipv4/ip_forward")
	if err != nil {
		return err
	}
	if strings.TrimSpace(string(current)) == "1" {
		return nil
	}
	return os.WriteFile("/proc/sys/net/ipv4/ip_forward", []byte("1"), 0644)
}

func (a *Agent) registerWithManager(ctx context.Context) (*RegisterServerResponse, error) {
	publicIP, _ := getPublicIP()

	req := &RegisterServerRequest{
		Hostname:  a.cfg.ServerHostname,
		PublicKey: a.publicKey,
		PublicIP:  publicIP,
		WGPort:    int32(a.cfg.WGPublicPort),
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

		now := time.Now()
		removedOrphans, remainingOrphans, orphanErr := a.peerManager.ReconcileOrphans(now, a.cfg.PeerStaleAfter)
		if orphanErr != nil {
			a.metrics.PeerExpiryFailures.Inc()
			a.logger.Warn("orphan peer reconciliation partially failed", zap.Error(orphanErr))
		}
		if removedOrphans > 0 {
			a.metrics.OrphanPeerRemovals.Add(float64(removedOrphans))
			a.logger.Info("orphan peers removed", zap.Int("count", removedOrphans))
		}
		a.metrics.OrphanPeerCount.Set(float64(remainingOrphans))
		for _, stale := range a.peerManager.StalePeers(now, a.cfg.PeerNoHandshakeGrace, a.cfg.PeerStaleAfter) {
			expireCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
			err := a.managerClient.ReportPeerExpired(expireCtx, a.serverID, stale.PeerID, a.cfg.AuthToken)
			cancel()
			if err != nil {
				a.metrics.PeerExpiryFailures.Inc()
				a.logger.Warn("stale peer reconciliation failed", zap.String("peer_id", stale.PeerID), zap.Error(err))
				continue
			}
			if allowedIPs, err := a.peerManager.RemovePeer(stale.PublicKey); err != nil {
				a.metrics.PeerExpiryFailures.Inc()
				a.logger.Warn("stale peer local removal failed", zap.String("peer_id", stale.PeerID), zap.Error(err))
				continue
			} else if a.dnsForwarder != nil {
				a.dnsForwarder.ClearBlockedForCIDRs(allowedIPs)
			}
			a.logger.Info("stale peer expired", zap.String("peer_id", stale.PeerID))
		}

		rx, tx, count, activeCount := a.peerManager.GetStats()
		loadFactor := getLoadFactor()

		req := &HeartbeatRequest{
			ServerID:       a.serverID,
			PeerCount:      count,
			LoadFactor:     loadFactor,
			RXBytes:        rx,
			TXBytes:        tx,
			DNSBlockedByIP: func() map[string]uint64 {
				if a.dnsForwarder == nil {
					return nil
				}
				return a.dnsForwarder.BlockedCounts()
			}(),
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
		a.metrics.StalePeerCount.Set(float64(a.peerManager.StalePeerCount(now, a.cfg.PeerNoHandshakeGrace, a.cfg.PeerStaleAfter)))
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
		a.metrics.PeerStreamConnected.Set(1)

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
					a.logger.Warn("Peer update stream ended, reconnecting...")
					goto reconnect
				}
				if err != nil {
					msg := err.Error()
					if strings.Contains(msg, "EOF") || strings.Contains(msg, "connection reset") {
						a.logger.Warn("Peer update stream disconnected, reconnecting...", zap.Error(err))
					} else {
						a.logger.Error("Peer update stream error", zap.Error(err))
					}
					goto reconnect
				}
			case <-ctx.Done():
				return
			}
		}

	reconnect:
		a.metrics.PeerStreamConnected.Set(0)
		a.metrics.PeerStreamDisconnects.Inc()
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
		if err := a.peerManager.AddPeer(update.PeerID, update.PublicKey, update.PresharedKey, update.AllowedIPs); err != nil {
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
		allowedIPs, err := a.peerManager.RemovePeer(update.PublicKey)
		if err != nil {
			a.logger.Error("Failed to remove peer",
				zap.String("peer_id", update.PeerID), zap.Error(err))
			return
		}
		if a.dnsForwarder != nil {
			if len(allowedIPs) == 0 {
				allowedIPs = update.AllowedIPs
			}
			a.dnsForwarder.ClearBlockedForCIDRs(allowedIPs)
		}
		a.logger.Info("Peer removed", zap.String("peer_id", update.PeerID))
	case "PORT_FORWARD_ADD":
		if err := a.fwManager.AddPortForward(firewall.PortForward{
			ID:           update.ForwardID,
			Protocol:     update.Protocol,
			ExternalPort: update.ExternalPort,
			InternalPort: update.InternalPort,
			AssignedIP:   firewall.StripCIDR(update.AssignedIP),
		}); err != nil {
			a.logger.Error("Failed to add port forward",
				zap.String("forward_id", update.ForwardID), zap.Error(err))
			return
		}
		a.logger.Info("Port forward added",
			zap.String("forward_id", update.ForwardID),
			zap.String("protocol", update.Protocol),
			zap.Int("external_port", update.ExternalPort),
			zap.Int("internal_port", update.InternalPort),
			zap.String("assigned_ip", firewall.StripCIDR(update.AssignedIP)))
	case "PORT_FORWARD_REMOVE":
		if err := a.fwManager.RemovePortForward(update.ForwardID); err != nil {
			a.logger.Error("Failed to remove port forward",
				zap.String("forward_id", update.ForwardID), zap.Error(err))
			return
		}
		a.logger.Info("Port forward removed", zap.String("forward_id", update.ForwardID))
	default:
		a.logger.Warn("Unknown peer update action",
			zap.String("action", update.Action))
	}
}

func getPublicIP() (string, error) {
	if ip := strings.TrimSpace(os.Getenv("PUBLIC_IP")); ip != "" {
		return ip, nil
	}
	return "", fmt.Errorf("PUBLIC_IP is required; refusing to discover endpoint via third-party service")
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
