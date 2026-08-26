package handler

import (
	"context"
	"encoding/json"
	"net"
	"net/http"
	"strings"
	"time"

	"errors"

	"github.com/golang-jwt/jwt/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/veritasvpn/lib/logging"
	"github.com/veritasvpn/services/wg-manager/internal/entitlement"
	"github.com/veritasvpn/services/wg-manager/internal/hub"
	"github.com/veritasvpn/services/wg-manager/internal/metrics"
	"github.com/veritasvpn/services/wg-manager/internal/service"
)

type HTTPHandler struct {
	svc       *service.Service
	hub       *hub.Hub
	pool      *pgxpool.Pool
	metrics   *metrics.Metrics
	jwtSecret []byte
	authToken string
	log       *logging.Logger
}

func NewHTTPHandler(svc *service.Service, h *hub.Hub, pool *pgxpool.Pool, m *metrics.Metrics, jwtSecret, authToken string, log *logging.Logger) *HTTPHandler {
	return &HTTPHandler{
		svc:       svc,
		hub:       h,
		pool:      pool,
		metrics:   m,
		jwtSecret: []byte(jwtSecret),
		authToken: authToken,
		log:       log,
	}
}

func (h *HTTPHandler) Routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", h.handleHealth)
	mux.Handle("/metrics", h.metrics.Handler())
	mux.HandleFunc("/api/v1/agents/register", h.handleAgentRegister)
	mux.HandleFunc("/api/v1/agents/heartbeat", h.handleAgentHeartbeat)
	mux.HandleFunc("/api/v1/agents/peers/stream", h.handlePeerStream)
	mux.HandleFunc("/api/v1/agents/peers/applied", h.handlePeerApplied)
	mux.HandleFunc("/api/v1/agents/peers/expired", h.handlePeerExpired)
	mux.HandleFunc("/api/v1/wg/peers", h.handlePeers)
	mux.HandleFunc("/api/v1/wg/peers/", h.handlePeerByID)
	mux.HandleFunc("/api/v1/wg/port-forwards", h.handlePortForwards)
	mux.HandleFunc("/api/v1/wg/port-forwards/", h.handlePortForwardByID)
	mux.HandleFunc("/api/v1/wg/servers", h.handleListServers)
	return mux
}

// RegisterRoutes mounts the same handlers on an existing mux (auth/billing style).
func (h *HTTPHandler) RegisterRoutes(mux *http.ServeMux) {
	mux.HandleFunc("/healthz", h.handleHealth)
	mux.Handle("/metrics", h.metrics.Handler())
	mux.HandleFunc("/api/v1/agents/register", h.handleAgentRegister)
	mux.HandleFunc("/api/v1/agents/heartbeat", h.handleAgentHeartbeat)
	mux.HandleFunc("/api/v1/agents/peers/stream", h.handlePeerStream)
	mux.HandleFunc("/api/v1/agents/peers/applied", h.handlePeerApplied)
	mux.HandleFunc("/api/v1/agents/peers/expired", h.handlePeerExpired)
	mux.HandleFunc("/api/v1/wg/peers", h.handlePeers)
	mux.HandleFunc("/api/v1/wg/peers/", h.handlePeerByID)
	mux.HandleFunc("/api/v1/wg/port-forwards", h.handlePortForwards)
	mux.HandleFunc("/api/v1/wg/port-forwards/", h.handlePortForwardByID)
	mux.HandleFunc("/api/v1/wg/servers", h.handleListServers)
}

func (h *HTTPHandler) handleHealth(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if h.pool != nil {
		pingCtx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
		err := h.pool.Ping(pingCtx)
		cancel()
		if err != nil {
			if h.metrics != nil {
				h.metrics.PostgresUp.Set(0)
			}
			writeJSON(w, http.StatusServiceUnavailable, map[string]string{"status": "postgres unavailable"})
			return
		}
	}
	if h.metrics != nil {
		h.metrics.PostgresUp.Set(1)
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

type registerRequest struct {
	Hostname  string `json:"hostname"`
	PublicKey string `json:"public_key"`
	PublicIP  string `json:"public_ip"`
	WGPort    int32  `json:"wg_port"`
	Region    string `json:"region"`
	City      string `json:"city"`
	Country   string `json:"country"`
	AuthToken string `json:"auth_token"`
}

func (h *HTTPHandler) handleAgentRegister(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var req registerRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid json"})
		return
	}
	if req.Hostname == "" || req.PublicKey == "" || req.PublicIP == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "hostname, public_key, and public_ip are required"})
		return
	}
	if req.WGPort == 0 {
		req.WGPort = 51820
	}
	if req.Region == "" {
		req.Region = "local"
	}
	if req.City == "" {
		req.City = "local"
	}
	if req.Country == "" {
		req.Country = "XX"
	}

	srv, err := h.svc.RegisterServer(r.Context(), req.Hostname, req.PublicKey, req.PublicIP, req.WGPort, req.Region, req.City, req.Country, req.AuthToken)
	if err != nil {
		h.log.Error("agent register failed", "error", err)
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": err.Error()})
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"server_id":  srv.ID,
		"wg_subnet":  srv.WGSubnet,
		"dns_server": srv.DNSServer,
		"capacity":   srv.Capacity,
	})
}

type heartbeatRequest struct {
	ServerID       string            `json:"server_id"`
	PeerCount      int32             `json:"peer_count"`
	LoadFactor     float64           `json:"load_factor"`
	RXBytes        int64             `json:"rx_bytes"`
	TXBytes        int64             `json:"tx_bytes"`
	DNSBlockedByIP map[string]uint64 `json:"dns_blocked_by_ip"`
}

func (h *HTTPHandler) handleAgentHeartbeat(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	token := extractBearer(r)
	if token == "" || token != h.authToken {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"})
		return
	}

	var req heartbeatRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid json"})
		return
	}
	if req.ServerID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "server_id is required"})
		return
	}

	if err := h.svc.HandleHeartbeat(r.Context(), req.ServerID, req.PeerCount, req.LoadFactor, req.RXBytes, req.TXBytes, req.DNSBlockedByIP); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *HTTPHandler) handlePeerStream(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	token := extractBearer(r)
	if token == "" || token != h.authToken {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"})
		return
	}

	serverID := r.URL.Query().Get("server_id")
	if serverID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "server_id is required"})
		return
	}

	flusher, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "streaming unsupported", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	w.WriteHeader(http.StatusOK)
	flusher.Flush()

	// Catch-up: push existing peers so a reconnecting agent is consistent.
	// Peers stay pending until the agent applies them and calls /peers/applied.
	peers, err := h.svc.ListPeersForServer(r.Context(), serverID)
	if err != nil {
		h.log.Warn("failed listing peers for stream catch-up", "server_id", serverID, "error", err)
	} else {
		for _, p := range peers {
			psk := ""
			if p.PresharedKey != nil {
				psk = *p.PresharedKey
			}
			update := hub.PeerUpdate{
				Action:       "ADD",
				PeerID:       p.ID,
				PublicKey:    p.Pubkey,
				PresharedKey: psk,
				AllowedIPs:   p.AllowedIPs,
			}
			line, encErr := hub.EncodeSSE(update)
			if encErr != nil {
				continue
			}
			_, _ = w.Write(line)
			flusher.Flush()
		}
	}

	forwards, err := h.svc.ListPortForwardsForServer(r.Context(), serverID)
	if err != nil {
		h.log.Warn("failed listing port forwards for stream catch-up", "server_id", serverID, "error", err)
	} else {
		for _, pf := range forwards {
			update := hub.PeerUpdate{
				Action:       "PORT_FORWARD_ADD",
				PeerID:       pf.PeerID,
				ForwardID:    pf.ID,
				Protocol:     pf.Protocol,
				ExternalPort: pf.ExternalPort,
				InternalPort: pf.InternalPort,
				AssignedIP:   pf.AssignedIP,
			}
			line, encErr := hub.EncodeSSE(update)
			if encErr != nil {
				continue
			}
			_, _ = w.Write(line)
			flusher.Flush()
		}
	}

	updates, unsubscribe := h.hub.Subscribe(serverID)
	defer unsubscribe()

	ticker := time.NewTicker(15 * time.Second)
	defer ticker.Stop()

	ctx := r.Context()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			_, _ = w.Write([]byte(": keepalive\n\n"))
			flusher.Flush()
		case update, ok := <-updates:
			if !ok {
				return
			}
			line, encErr := hub.EncodeSSE(update)
			if encErr != nil {
				continue
			}
			if _, err := w.Write(line); err != nil {
				return
			}
			flusher.Flush()
		}
	}
}

type peerAppliedRequest struct {
	PeerID   string `json:"peer_id"`
	ServerID string `json:"server_id"`
}

func (h *HTTPHandler) handlePeerExpired(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if token := extractBearer(r); token == "" || token != h.authToken {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"})
		return
	}
	var req peerAppliedRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid json"})
		return
	}
	if req.PeerID == "" || req.ServerID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "peer_id and server_id are required"})
		return
	}
	if err := h.svc.ExpirePeer(r.Context(), req.ServerID, req.PeerID); err != nil {
		h.log.Warn("stale peer expiry failed", "peer_id", req.PeerID, "server_id", req.ServerID, "error", err)
		writeJSON(w, http.StatusConflict, map[string]string{"error": err.Error()})
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *HTTPHandler) handlePeerApplied(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	token := extractBearer(r)
	if token == "" || token != h.authToken {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"})
		return
	}

	var req peerAppliedRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid json"})
		return
	}
	if req.PeerID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "peer_id is required"})
		return
	}

	if err := h.svc.MarkPeerActive(r.Context(), req.PeerID); err != nil {
		h.log.Warn("mark peer active failed", "peer_id", req.PeerID, "error", err)
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

type createPeerRequest struct {
	PublicKey string `json:"public_key"`
	Region    string `json:"region"`
}

func (h *HTTPHandler) handlePeers(w http.ResponseWriter, r *http.Request) {
	accountID, tier, err := h.accountFromRequest(r)
	if err != nil {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": err.Error()})
		return
	}

	switch r.Method {
	case http.MethodPost:
		var req createPeerRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid json"})
			return
		}
		if req.PublicKey == "" {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "public_key is required"})
			return
		}
		cfg, err := h.svc.CreatePeer(r.Context(), accountID, tier, req.PublicKey, req.Region, clientIPFromRequest(r))
		if err != nil {
			var planErr *entitlement.PlanError
			if errors.As(err, &planErr) {
				h.log.Warn("create peer denied by plan", "account_id", accountID, "tier", tier, "code", planErr.Code)
				writeJSON(w, http.StatusForbidden, map[string]string{"error": planErr.Message, "code": planErr.Code})
				return
			}
			h.log.Error("create peer failed", "account_id", accountID, "error", err)
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{
			"peer_id":              cfg.PeerID,
			"server_id":            cfg.ServerID,
			"server_hostname":      cfg.ServerHostname,
			"server_public_key":    cfg.ServerPublicKey,
			"server_endpoint":      cfg.ServerEndpoint,
			"stealth_endpoint":     cfg.StealthEndpoint,
			"stealth_available":    cfg.StealthAvailable,
			"stealth_path_prefix":  cfg.StealthPathPrefix,
			"assigned_ip":          cfg.AssignedIP,
			"address":              cfg.AssignedIP,
			"dns_server":           cfg.DNSServer,
			"preshared_key":        cfg.PresharedKey,
			"allowed_ips":          cfg.ClientAllowedIPs,
			"client_allowed_ips":   cfg.ClientAllowedIPs,
			"persistent_keepalive": cfg.PersistentKeepaliveSec,
		})
	case http.MethodGet:
		peers, err := h.svc.ListPeers(r.Context(), accountID)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
			return
		}
		out := make([]map[string]interface{}, 0, len(peers))
		for _, p := range peers {
			var expiresAt interface{}
			if p.ExpiresAt != nil {
				expiresAt = p.ExpiresAt.Unix()
			}
			out = append(out, map[string]interface{}{
				"id":                p.ID,
				"account_id":        p.AccountID,
				"server_id":         p.ServerID,
				"pubkey":            p.Pubkey,
				"allowed_ips":       p.AllowedIPs,
				"assigned_ip":       p.AssignedIP,
				"status":            p.Status,
				"created_at":        p.CreatedAt.Unix(),
				"expires_at":        expiresAt,
				"dns_blocked_count": h.svc.DNSBlockedCount(r.Context(), p.AssignedIP),
			})
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"peers": out})
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func (h *HTTPHandler) handlePeerByID(w http.ResponseWriter, r *http.Request) {
	accountID, _, err := h.accountFromRequest(r)
	if err != nil {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": err.Error()})
		return
	}

	peerID := strings.TrimPrefix(r.URL.Path, "/api/v1/wg/peers/")
	peerID = strings.Trim(peerID, "/")
	if peerID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "peer_id is required"})
		return
	}

	switch r.Method {
	case http.MethodDelete:
		if err := h.svc.DeletePeer(r.Context(), peerID, accountID); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
			return
		}
		w.WriteHeader(http.StatusNoContent)
	case http.MethodGet:
		peer, srv, err := h.svc.GetPeer(r.Context(), peerID, accountID)
		if err != nil {
			writeJSON(w, http.StatusNotFound, map[string]string{"error": err.Error()})
			return
		}
		resp := map[string]interface{}{"peer": peer}
		if srv != nil {
			endpoint := h.svc.ClientEndpoint(srv, clientIPFromRequest(r))
			resp["server"] = srv
			resp["server_endpoint"] = endpoint
			resp["stealth_endpoint"] = h.svc.ClientStealthEndpoint(srv, clientIPFromRequest(r))
			resp["stealth_available"] = h.svc.StealthAvailable()
			resp["stealth_path_prefix"] = h.svc.StealthPathPrefix()
		}
		writeJSON(w, http.StatusOK, resp)
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func (h *HTTPHandler) handleListServers(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if _, _, err := h.accountFromRequest(r); err != nil {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": err.Error()})
		return
	}
	servers, err := h.svc.ListServers(r.Context())
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}
	clientIP := clientIPFromRequest(r)
	out := make([]map[string]interface{}, 0, len(servers))
	for i := range servers {
		srv := &servers[i]
		out = append(out, map[string]interface{}{
			"id":                  srv.ID,
			"hostname":            srv.Hostname,
			"public_ip":           srv.PublicIP,
			"wg_port":             srv.WGPort,
			"public_key":          srv.PublicKey,
			"status":              srv.Status,
			"region":              srv.Region,
			"city":                srv.City,
			"country":             srv.Country,
			"server_endpoint":     h.svc.ClientEndpoint(srv, clientIP),
			"stealth_endpoint":    h.svc.ClientStealthEndpoint(srv, clientIP),
			"stealth_available":   h.svc.StealthAvailable(),
			"stealth_path_prefix": h.svc.StealthPathPrefix(),
		})
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{"servers": out})
}

type createPortForwardRequest struct {
	PeerID       string `json:"peer_id"`
	Protocol     string `json:"protocol"`
	ExternalPort int    `json:"external_port"`
	InternalPort int    `json:"internal_port"`
}

func (h *HTTPHandler) handlePortForwards(w http.ResponseWriter, r *http.Request) {
	accountID, tier, err := h.accountFromRequest(r)
	if err != nil {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": err.Error()})
		return
	}

	switch r.Method {
	case http.MethodGet:
		forwards, err := h.svc.ListPortForwards(r.Context(), accountID)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
			return
		}
		out := make([]map[string]interface{}, 0, len(forwards))
		for _, pf := range forwards {
			out = append(out, map[string]interface{}{
				"id":              pf.ID,
				"peer_id":         pf.PeerID,
				"server_id":       pf.ServerID,
				"protocol":        pf.Protocol,
				"external_port":   pf.ExternalPort,
				"internal_port":   pf.InternalPort,
				"status":          pf.Status,
				"assigned_ip":     pf.AssignedIP,
				"egress_endpoint": pf.EgressEndpoint,
				"created_at":      pf.CreatedAt.Unix(),
			})
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"port_forwards": out})
	case http.MethodPost:
		var req createPortForwardRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid json"})
			return
		}
		if req.PeerID == "" {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "peer_id is required"})
			return
		}
		if req.Protocol == "" {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "protocol is required"})
			return
		}
		if req.ExternalPort == 0 {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "external_port is required"})
			return
		}
		pf, err := h.svc.CreatePortForward(r.Context(), accountID, tier, req.PeerID, req.Protocol, req.ExternalPort, req.InternalPort)
		if err != nil {
			var planErr *entitlement.PlanError
			if errors.As(err, &planErr) {
				status := http.StatusForbidden
				switch planErr.Code {
				case "invalid_external_port", "invalid_internal_port", "invalid_protocol",
					"external_port_taken", "reserved_external_port":
					status = http.StatusBadRequest
				}
				writeJSON(w, status, map[string]string{"error": planErr.Message, "code": planErr.Code})
				return
			}
			h.log.Error("create port forward failed", "account_id", accountID, "error", err)
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
			return
		}
		writeJSON(w, http.StatusCreated, map[string]interface{}{
			"id":              pf.ID,
			"peer_id":         pf.PeerID,
			"server_id":       pf.ServerID,
			"protocol":        pf.Protocol,
			"external_port":   pf.ExternalPort,
			"internal_port":   pf.InternalPort,
			"status":          pf.Status,
			"assigned_ip":     pf.AssignedIP,
			"egress_endpoint": pf.EgressEndpoint,
			"created_at":      pf.CreatedAt.Unix(),
		})
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func (h *HTTPHandler) handlePortForwardByID(w http.ResponseWriter, r *http.Request) {
	accountID, _, err := h.accountFromRequest(r)
	if err != nil {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": err.Error()})
		return
	}

	id := strings.TrimPrefix(r.URL.Path, "/api/v1/wg/port-forwards/")
	id = strings.Trim(id, "/")
	if id == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "id is required"})
		return
	}

	switch r.Method {
	case http.MethodDelete:
		if err := h.svc.DeletePortForward(r.Context(), id, accountID); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
			return
		}
		w.WriteHeader(http.StatusNoContent)
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

type claims struct {
	AccountID string `json:"account_id"`
	Tier      string `json:"tier"`
	jwt.RegisteredClaims
}

func (h *HTTPHandler) accountFromRequest(r *http.Request) (accountID, tier string, err error) {
	tokenStr := extractBearer(r)
	if tokenStr == "" {
		return "", "", errUnauthorized("missing bearer token")
	}
	token, err := jwt.ParseWithClaims(tokenStr, &claims{}, func(t *jwt.Token) (interface{}, error) {
		if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, errUnauthorized("unexpected signing method")
		}
		return h.jwtSecret, nil
	})
	if err != nil {
		return "", "", errUnauthorized("invalid token")
	}
	c, ok := token.Claims.(*claims)
	if !ok || !token.Valid || c.AccountID == "" {
		return "", "", errUnauthorized("invalid token claims")
	}
	return c.AccountID, entitlement.NormalizeTier(c.Tier), nil
}

type unauthorizedError struct{ msg string }

func (e unauthorizedError) Error() string { return e.msg }

func errUnauthorized(msg string) error { return unauthorizedError{msg: msg} }

func extractBearer(r *http.Request) string {
	auth := r.Header.Get("Authorization")
	if !strings.HasPrefix(auth, "Bearer ") {
		return ""
	}
	return strings.TrimSpace(strings.TrimPrefix(auth, "Bearer "))
}

func clientIPFromRequest(r *http.Request) string {
	for _, h := range []string{"CF-Connecting-IP", "True-Client-IP", "X-Real-IP"} {
		if v := strings.TrimSpace(r.Header.Get(h)); v != "" {
			return stripHostPort(v)
		}
	}
	if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
		parts := strings.Split(xff, ",")
		return stripHostPort(strings.TrimSpace(parts[0]))
	}
	return stripHostPort(r.RemoteAddr)
}

func stripHostPort(v string) string {
	host, _, err := net.SplitHostPort(v)
	if err != nil {
		return v
	}
	return host
}

func writeJSON(w http.ResponseWriter, status int, body interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}
