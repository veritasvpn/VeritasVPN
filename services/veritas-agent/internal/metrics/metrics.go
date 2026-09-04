package metrics

import (
	"net/http"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

type Metrics struct {
	PeerCount                   prometheus.Gauge
	ActivePeerCount             prometheus.Gauge
	RXBytesTotal                prometheus.Counter
	TXBytesTotal                prometheus.Counter
	UptimeSeconds               prometheus.Counter
	CPUUsage                    prometheus.Gauge
	MemoryUsage                 prometheus.Gauge
	StalePeerCount              prometheus.Gauge
	PeerExpiryFailures          prometheus.Counter
	OrphanPeerCount             prometheus.Gauge
	OrphanPeerRemovals          prometheus.Counter
	DNSQueriesTotal             prometheus.Counter
	DNSBlockedTotal             prometheus.Counter
	DNSBlockedByCategory        *prometheus.CounterVec
	DNSUpstreamFailures         prometheus.Counter
	DNSBlocklistDomains         prometheus.Gauge
	DNSBlocklistDomainsCategory *prometheus.GaugeVec
	DNSBlocklistLastRefresh     prometheus.Gauge
	DNSBlocklistRefreshFailures prometheus.Counter
	PeerStreamConnected         prometheus.Gauge
	PeerStreamDisconnects       prometheus.Counter

	registry *prometheus.Registry
	port     string
	bind     string
}

func New(port string) *Metrics {
	// Default loopback bind. hostNetwork agents should keep METRICS_BIND=127.0.0.1
	// and probe via host:127.0.0.1; expose ClusterIP scrape only inside the cluster.
	// If a non-loopback bind is required, block WAN/LAN with deploy/node/veritas-firewall.sh.
	return NewWithBind(port, "127.0.0.1")
}

func NewWithBind(port, bind string) *Metrics {
	if bind == "" {
		bind = "0.0.0.0"
	}
	reg := prometheus.NewRegistry()

	m := &Metrics{
		PeerCount: prometheus.NewGauge(prometheus.GaugeOpts{
			Name: "veritas_agent_peer_count",
			Help: "Current number of WireGuard peers.",
		}),
		ActivePeerCount: prometheus.NewGauge(prometheus.GaugeOpts{
			Name: "veritas_agent_active_peer_count",
			Help: "WireGuard peers with a handshake in the last three minutes.",
		}),
		RXBytesTotal: prometheus.NewCounter(prometheus.CounterOpts{
			Name: "veritas_agent_rx_bytes_total",
			Help: "Total bytes received across all peers.",
		}),
		TXBytesTotal: prometheus.NewCounter(prometheus.CounterOpts{
			Name: "veritas_agent_tx_bytes_total",
			Help: "Total bytes transmitted across all peers.",
		}),
		UptimeSeconds: prometheus.NewCounter(prometheus.CounterOpts{
			Name: "veritas_agent_uptime_seconds",
			Help: "Agent uptime in seconds.",
		}),
		CPUUsage: prometheus.NewGauge(prometheus.GaugeOpts{
			Name: "veritas_agent_cpu_usage_percent",
			Help: "CPU usage percentage.",
		}),
		MemoryUsage: prometheus.NewGauge(prometheus.GaugeOpts{
			Name: "veritas_agent_memory_usage_bytes",
			Help: "Memory usage in bytes.",
		}),
		StalePeerCount: prometheus.NewGauge(prometheus.GaugeOpts{
			Name: "veritas_agent_stale_peer_count",
			Help: "WireGuard peers that exceeded the stale-session threshold.",
		}),
		PeerExpiryFailures: prometheus.NewCounter(prometheus.CounterOpts{
			Name: "veritas_agent_peer_expiry_failures_total",
			Help: "Failed stale WireGuard peer reconciliation attempts.",
		}),
		OrphanPeerCount: prometheus.NewGauge(prometheus.GaugeOpts{
			Name: "veritas_agent_orphan_peer_count",
			Help: "Kernel WireGuard peers not yet represented in the manager stream.",
		}),
		OrphanPeerRemovals: prometheus.NewCounter(prometheus.CounterOpts{
			Name: "veritas_agent_orphan_peer_removals_total",
			Help: "Kernel peers removed because they were no longer managed.",
		}),
		DNSQueriesTotal: prometheus.NewCounter(prometheus.CounterOpts{
			Name: "veritas_agent_dns_queries_total",
			Help: "Total DNS requests handled by the VPN DNS gateway. No query names or client identifiers are recorded.",
		}),
		DNSBlockedTotal: prometheus.NewCounter(prometheus.CounterOpts{
			Name: "veritas_agent_dns_blocked_total",
			Help: "DNS requests blocked by Veritas Shield. No query names or client identifiers are recorded.",
		}),
		DNSBlockedByCategory: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "veritas_agent_dns_blocked_by_category_total",
			Help: "DNS requests blocked by Veritas Shield category. Labels are category only—never query names.",
		}, []string{"category"}),
		DNSUpstreamFailures: prometheus.NewCounter(prometheus.CounterOpts{
			Name: "veritas_agent_dns_upstream_failures_total",
			Help: "Requests for which every encrypted DNS upstream failed.",
		}),
		DNSBlocklistDomains: prometheus.NewGauge(prometheus.GaugeOpts{
			Name: "veritas_agent_dns_blocklist_domains",
			Help: "Number of domains in the active Veritas Shield policy (all categories).",
		}),
		DNSBlocklistDomainsCategory: prometheus.NewGaugeVec(prometheus.GaugeOpts{
			Name: "veritas_agent_dns_blocklist_domains_by_category",
			Help: "Domains loaded per Veritas Shield category after the last successful refresh.",
		}, []string{"category"}),
		DNSBlocklistLastRefresh: prometheus.NewGauge(prometheus.GaugeOpts{
			Name: "veritas_agent_dns_blocklist_last_successful_refresh_timestamp_seconds",
			Help: "Unix timestamp of the most recent successful DNS blocklist refresh.",
		}),
		PeerStreamConnected: prometheus.NewGauge(prometheus.GaugeOpts{
			Name: "veritas_agent_peer_stream_connected",
			Help: "1 while the agent peer update SSE stream is connected.",
		}),
		PeerStreamDisconnects: prometheus.NewCounter(prometheus.CounterOpts{
			Name: "veritas_agent_peer_stream_disconnects_total",
			Help: "Peer update stream closures and errors requiring reconnect.",
		}),
		DNSBlocklistRefreshFailures: prometheus.NewCounter(prometheus.CounterOpts{
			Name: "veritas_agent_dns_blocklist_refresh_failures_total",
			Help: "Failed DNS blocklist refresh attempts.",
		}),
		registry: reg,
		port:     port,
		bind:     bind,
	}

	reg.MustRegister(m.PeerCount)
	reg.MustRegister(m.ActivePeerCount)
	reg.MustRegister(m.RXBytesTotal)
	reg.MustRegister(m.TXBytesTotal)
	reg.MustRegister(m.UptimeSeconds)
	reg.MustRegister(m.CPUUsage)
	reg.MustRegister(m.MemoryUsage)
	reg.MustRegister(m.StalePeerCount)
	reg.MustRegister(m.PeerExpiryFailures)
	reg.MustRegister(m.OrphanPeerCount)
	reg.MustRegister(m.OrphanPeerRemovals)
	reg.MustRegister(m.DNSQueriesTotal)
	reg.MustRegister(m.DNSBlockedTotal)
	reg.MustRegister(m.DNSBlockedByCategory)
	reg.MustRegister(m.DNSUpstreamFailures)
	reg.MustRegister(m.DNSBlocklistDomains)
	reg.MustRegister(m.DNSBlocklistDomainsCategory)
	reg.MustRegister(m.DNSBlocklistLastRefresh)
	reg.MustRegister(m.DNSBlocklistRefreshFailures)
	reg.MustRegister(m.PeerStreamConnected)
	reg.MustRegister(m.PeerStreamDisconnects)

	return m
}

// DNSQuery records only aggregate DNS activity. It intentionally has no
// labels, so monitoring cannot become a record of users' browsing activity.
func (m *Metrics) DNSQuery(blocked bool) {
	m.DNSQueriesTotal.Inc()
	if blocked {
		m.DNSBlockedTotal.Inc()
	}
}

func (m *Metrics) DNSBlockedCategory(category string) {
	if category == "" {
		category = "unknown"
	}
	m.DNSBlockedByCategory.WithLabelValues(category).Inc()
}

func (m *Metrics) DNSUpstreamFailure() { m.DNSUpstreamFailures.Inc() }

func (m *Metrics) DNSBlocklistRefreshed(domains int, at time.Time) {
	m.DNSBlocklistDomains.Set(float64(domains))
	m.DNSBlocklistLastRefresh.Set(float64(at.Unix()))
}

func (m *Metrics) DNSBlocklistCategorySizes(byCategory map[string]int) {
	m.DNSBlocklistDomainsCategory.Reset()
	for cat, n := range byCategory {
		m.DNSBlocklistDomainsCategory.WithLabelValues(cat).Set(float64(n))
	}
}

func (m *Metrics) DNSBlocklistRefreshFailed() { m.DNSBlocklistRefreshFailures.Inc() }

func (m *Metrics) Handler() http.Handler {
	return promhttp.HandlerFor(m.registry, promhttp.HandlerOpts{})
}

func (m *Metrics) Server() *http.Server {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok\n"))
	})
	mux.Handle("/metrics", m.Handler())
	return &http.Server{
		Addr:              m.bind + ":" + m.port,
		Handler:           mux,
		ReadTimeout:       5 * time.Second,
		WriteTimeout:      10 * time.Second,
		IdleTimeout:       60 * time.Second,
		ReadHeaderTimeout: 3 * time.Second,
	}
}

func (m *Metrics) Start() error {
	return m.Server().ListenAndServe()
}

func (m *Metrics) StartWithServer(srv *http.Server) error {
	return srv.ListenAndServe()
}
