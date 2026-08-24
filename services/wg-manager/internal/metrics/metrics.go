package metrics

import (
	"net/http"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

type Metrics struct {
	PostgresUp prometheus.Gauge
	registry   *prometheus.Registry
}

func New() *Metrics {
	reg := prometheus.NewRegistry()
	m := &Metrics{
		PostgresUp: prometheus.NewGauge(prometheus.GaugeOpts{
			Name: "veritas_wg_manager_postgres_up",
			Help: "1 when wg-manager can reach PostgreSQL.",
		}),
		registry: reg,
	}
	reg.MustRegister(m.PostgresUp)
	return m
}

func (m *Metrics) Handler() http.Handler {
	return promhttp.HandlerFor(m.registry, promhttp.HandlerOpts{})
}
