package main

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/nats-io/nats.go"
	"github.com/veritasvpn/lib/config"
	jwtlib "github.com/veritasvpn/lib/jwt"
	"github.com/veritasvpn/lib/logging"
	"github.com/veritasvpn/services/wg-manager/internal/communicator"
	"github.com/veritasvpn/services/wg-manager/internal/entitlement"
	"github.com/veritasvpn/services/wg-manager/internal/handler"
	"github.com/veritasvpn/services/wg-manager/internal/hub"
	"github.com/veritasvpn/services/wg-manager/internal/metrics"
	"github.com/veritasvpn/services/wg-manager/internal/migrate"
	"github.com/veritasvpn/services/wg-manager/internal/repository"
	"github.com/veritasvpn/services/wg-manager/internal/scheduler"
	"github.com/veritasvpn/services/wg-manager/internal/service"
)

func main() {
	cfg := config.Load()
	if strings.TrimSpace(cfg.AgentAuthToken) == "" {
		fmt.Fprintln(os.Stderr, "AGENT_AUTH_TOKEN must be set to a non-empty value")
		os.Exit(1)
	}

	log, err := logging.New(cfg.LogLevel)
	if err != nil {
		fmt.Fprintf(os.Stderr, "failed to create logger: %v\n", err)
		os.Exit(1)
	}
	defer log.Sync()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	pool, err := connectDatabase(ctx, cfg.DatabaseURL, log)
	if err != nil {
		log.Fatal("postgres unavailable after retries", "error", err)
	}
	defer pool.Close()
	log.Info("connected to postgres")

	if err := migrate.Up(ctx, pool); err != nil {
		log.Fatal("failed to apply wg migrations", "error", err)
	}
	log.Info("wg migrations applied")

	redisRepo, err := repository.NewRedis(cfg.RedisURL)
	if err != nil {
		log.Fatal("failed to create redis client", "error", err)
	}

	if err := redisRepo.Client().Ping(ctx).Err(); err != nil {
		log.Fatal("redis ping failed", "error", err)
	}
	log.Info("connected to redis")

	nc, err := connectNATS(ctx, cfg.NatsURL, log)
	if err != nil {
		log.Fatal("failed to connect to nats", "error", err)
	}
	defer nc.Close()
	log.Info("connected to nats", "url", cfg.NatsURL)

	pgRepo := repository.NewPostgres(pool)
	sched := scheduler.New(pgRepo, log)
	sseHub := hub.New(log)
	agentClient := communicator.NewSSEAgentClient(sseHub, log)
	comm := communicator.New(agentClient, log)

	tierCache := entitlement.NewTierCache(log)
	if err := tierCache.StartSync(nc); err != nil {
		log.Fatal("failed to start tier sync", "error", err)
	}

	svc := service.New(pgRepo, redisRepo, sched, comm, nc, cfg.AgentAuthToken, tierCache, log)
	svc.SetFreeAllowedRegions(entitlement.ParseFreeRegions(os.Getenv("FREE_ALLOWED_REGIONS")))
	if lanIP := strings.TrimSpace(os.Getenv("LAN_ENDPOINT_IP")); lanIP != "" {
		lanPort := int32(51820)
		if v := strings.TrimSpace(os.Getenv("LAN_ENDPOINT_PORT")); v != "" {
			if n, err := strconv.Atoi(v); err == nil && n > 0 && n <= 65535 {
				lanPort = int32(n)
			}
		}
		svc.SetLANEndpoint(lanIP, lanPort)
		log.Info("LAN WireGuard endpoint enabled", "ip", lanIP, "port", lanPort)
	}
	stealthHost := strings.TrimSpace(os.Getenv("STEALTH_ENDPOINT_HOST"))
	if stealthHost == "" {
		// Fall back to PUBLIC_IP from configmap when host is not set explicitly.
		stealthHost = strings.TrimSpace(os.Getenv("PUBLIC_IP"))
	}
	stealthPort := int32(443)
	if v := strings.TrimSpace(os.Getenv("STEALTH_ENDPOINT_PORT")); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 && n <= 65535 {
			stealthPort = int32(n)
		}
	}
	stealthPrefix := strings.TrimSpace(os.Getenv("STEALTH_PATH_PREFIX"))
	stealthEnabled := strings.EqualFold(strings.TrimSpace(os.Getenv("STEALTH_ENABLED")), "true") ||
		strings.TrimSpace(os.Getenv("STEALTH_ENABLED")) == "1"
	if stealthEnabled && stealthHost != "" && stealthPrefix != "" {
		svc.SetStealthEndpoint(stealthHost, stealthPort, stealthPrefix)
		log.Info("stealth WireGuard endpoint enabled", "host", stealthHost, "port", stealthPort)
	}
	svcMetrics := metrics.New()
	jwtManager, err := jwtlib.NewManagerWithKeys(
		cfg.JWTSecret,
		"",
		cfg.JWTPublicKeys,
		"",
		cfg.JWTIssuer,
		cfg.JWTAudience,
		time.Hour,
	)
	if err != nil {
		log.Fatal("invalid JWT verifier configuration", "error", err)
	}
	httpHandler := handler.NewHTTPHandler(svc, sseHub, pool, redisRepo, svcMetrics, jwtManager, log)
	httpHandler.SetBrowserGateway(handler.BrowserGateway{
		Scheme:           cfg.BrowserProxyScheme,
		Host:             cfg.BrowserProxyHost,
		Port:             cfg.BrowserProxyPort,
		ExpectedEgressIP: cfg.BrowserExpectedEgressIP,
	})

	httpAddr := cfg.HTTPServerAddr()
	httpSrv := &http.Server{
		Addr:              httpAddr,
		Handler:           securityHeaders(httpHandler.Routes()),
		ReadHeaderTimeout: 10 * time.Second,
	}

	errCh := make(chan error, 1)
	go func() {
		log.Info("starting HTTP server", "addr", httpAddr)
		if err := httpSrv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			errCh <- fmt.Errorf("http serve: %w", err)
		}
	}()

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)

	select {
	case sig := <-sigCh:
		log.Info("shutting down", "signal", sig.String())
	case err := <-errCh:
		log.Error("server error", "error", err)
	}

	cancel()
	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer shutdownCancel()
	_ = httpSrv.Shutdown(shutdownCtx)
	log.Info("server stopped")
}

func connectNATS(ctx context.Context, url string, log *logging.Logger) (*nats.Conn, error) {
	var lastErr error
	for attempt := 1; ; attempt++ {
		nc, err := nats.Connect(url, nats.MaxReconnects(-1), nats.ReconnectWait(time.Second))
		if err == nil {
			return nc, nil
		}
		lastErr = err
		if attempt == 1 || attempt%10 == 0 {
			log.Warn("NATS unavailable; retrying", "attempt", attempt, "error", err)
		}
		select {
		case <-ctx.Done():
			return nil, fmt.Errorf("nats connection canceled: %w", lastErr)
		case <-time.After(time.Second):
		}
	}
}

func connectDatabase(ctx context.Context, databaseURL string, log *logging.Logger) (*pgxpool.Pool, error) {
	var lastErr error
	deadline := time.Now().Add(5 * time.Minute)
	for attempt := 1; ; attempt++ {
		pingCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
		pool, err := pgxpool.New(pingCtx, databaseURL)
		if err == nil {
			err = pool.Ping(pingCtx)
		}
		cancel()
		if err == nil {
			return pool, nil
		}
		if pool != nil {
			pool.Close()
		}
		lastErr = err
		if time.Now().After(deadline) {
			return nil, lastErr
		}
		if attempt == 1 || attempt%10 == 0 {
			log.Warn("PostgreSQL unavailable; retrying", "attempt", attempt, "error", err)
		}
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(2 * time.Second):
		}
	}
}

func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
		w.Header().Set("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
		w.Header().Set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
		w.Header().Set("Cache-Control", "no-store")
		next.ServeHTTP(w, r)
	})
}
