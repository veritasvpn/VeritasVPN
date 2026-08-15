package main

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/nats-io/nats.go"
	"github.com/veritasvpn/lib/config"
	"github.com/veritasvpn/lib/logging"
	"github.com/veritasvpn/services/wg-manager/internal/communicator"
	"github.com/veritasvpn/services/wg-manager/internal/entitlement"
	"github.com/veritasvpn/services/wg-manager/internal/handler"
	"github.com/veritasvpn/services/wg-manager/internal/hub"
	"github.com/veritasvpn/services/wg-manager/internal/migrate"
	"github.com/veritasvpn/services/wg-manager/internal/repository"
	"github.com/veritasvpn/services/wg-manager/internal/scheduler"
	"github.com/veritasvpn/services/wg-manager/internal/service"
)

func main() {
	cfg := config.Load()

	log, err := logging.New(cfg.LogLevel)
	if err != nil {
		fmt.Fprintf(os.Stderr, "failed to create logger: %v\n", err)
		os.Exit(1)
	}
	defer log.Sync()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	pool, err := pgxpool.New(ctx, cfg.DatabaseURL)
	if err != nil {
		log.Fatal("failed to connect to postgres", "error", err)
	}
	defer pool.Close()

	if err := pool.Ping(ctx); err != nil {
		log.Fatal("postgres ping failed", "error", err)
	}
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
	httpHandler := handler.NewHTTPHandler(svc, sseHub, cfg.JWTSecret, cfg.AgentAuthToken, log)

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
