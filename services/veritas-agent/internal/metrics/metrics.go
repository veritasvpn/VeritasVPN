package metrics

import (
	"net/http"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

type Metrics struct {
	PeerCount     prometheus.Gauge
	RXBytesTotal  prometheus.Counter
	TXBytesTotal  prometheus.Counter
	UptimeSeconds prometheus.Counter
	CPUUsage      prometheus.Gauge
	MemoryUsage   prometheus.Gauge

	registry *prometheus.Registry
	port     string
}

func New(port string) *Metrics {
	reg := prometheus.NewRegistry()

	m := &Metrics{
		PeerCount: prometheus.NewGauge(prometheus.GaugeOpts{
			Name: "veritas_agent_peer_count",
			Help: "Current number of WireGuard peers.",
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
		registry: reg,
		port:     port,
	}

	reg.MustRegister(m.PeerCount)
	reg.MustRegister(m.RXBytesTotal)
	reg.MustRegister(m.TXBytesTotal)
	reg.MustRegister(m.UptimeSeconds)
	reg.MustRegister(m.CPUUsage)
	reg.MustRegister(m.MemoryUsage)

	return m
}

func (m *Metrics) Handler() http.Handler {
	return promhttp.HandlerFor(m.registry, promhttp.HandlerOpts{})
}

func (m *Metrics) Server() *http.Server {
	mux := http.NewServeMux()
	mux.Handle("/metrics", m.Handler())
	return &http.Server{
		Addr:              ":" + m.port,
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
