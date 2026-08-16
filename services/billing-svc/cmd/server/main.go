package main

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/nats-io/nats.go"
	"github.com/veritasvpn/lib/config"
	"github.com/veritasvpn/lib/logging"
	"github.com/veritasvpn/services/billing-svc/internal/handler"
	"github.com/veritasvpn/services/billing-svc/internal/migrate"
	"github.com/veritasvpn/services/billing-svc/internal/provider"
	"github.com/veritasvpn/services/billing-svc/internal/repository"
	"github.com/veritasvpn/services/billing-svc/internal/service"
	"github.com/veritasvpn/services/billing-svc/internal/tokenauth"
	"go.uber.org/zap"
)

func main() {
	cfg := config.Load()
	log, err := logging.New(cfg.LogLevel)
	if err != nil {
		fmt.Fprintf(os.Stderr, "failed to initialize logger: %v\n", err)
		os.Exit(1)
	}
	defer log.Sync()

	if err := cfg.RequireBTCPayProduction(); err != nil {
		log.Fatal("invalid production billing config", zap.Error(err))
	}

	if cfg.BTCPayAPIKey != "" && cfg.BTCPayServerURL != "" &&
		!strings.Contains(cfg.BTCPayServerURL, "btcpay:49392") &&
		strings.ToLower(os.Getenv("ALLOW_MOCK_BTCPAY")) == "true" {
		log.Warn("ALLOW_MOCK_BTCPAY=true is set but real BTCPay credentials are present — mock mode will NOT be used")
	}

	dbPool, err := connectDatabase(cfg.DatabaseURL, log)
	if err != nil {
		log.Fatal("database unavailable after retries", zap.Error(err))
	}
	defer dbPool.Close()
	log.Info("connected to PostgreSQL")

	ctx := context.Background()
	if err := migrate.Up(ctx, dbPool); err != nil {
		log.Fatal("failed to apply billing migrations", zap.Error(err))
	}
	log.Info("billing migrations applied")

	var natsConn *nats.Conn
	if cfg.NatsURL != "" {
		nc, err := nats.Connect(cfg.NatsURL,
			nats.RetryOnFailedConnect(true),
			nats.MaxReconnects(-1),
			nats.ReconnectWait(2*time.Second),
			nats.Timeout(5*time.Second),
			nats.DisconnectErrHandler(func(_ *nats.Conn, err error) {
				log.Warn("NATS connection interrupted; reconnecting", zap.Error(err))
			}),
			nats.ReconnectHandler(func(nc *nats.Conn) {
				log.Info("NATS connection restored", zap.String("url", nc.ConnectedUrl()))
			}),
			nats.ClosedHandler(func(_ *nats.Conn) {
				log.Warn("NATS connection closed")
			}),
		)
		if err != nil {
			log.Warn("failed to connect to NATS, continuing without events", zap.Error(err))
		} else {
			natsConn = nc
			log.Info("connected to NATS")
			defer natsConn.Close()
		}
	}

	db := repository.NewPostgres(dbPool)

	var (
		invoiceCreator provider.InvoiceCreator
		btcpay         *provider.BTCPayProvider
		mock           *provider.MockBTCPayProvider
	)

	useMock := cfg.UseMockBTCPay()
	if useMock {
		mock = provider.NewMockBTCPayProvider(cfg.BillingPublicURL)
		invoiceCreator = mock
		log.Warn("BTCPay mock mode enabled — no real Bitcoin invoices")
	} else {
		btcpay = provider.NewBTCPayProvider(
			log,
			cfg.BTCPayServerURL,
			cfg.BTCPayAPIKey,
			cfg.BTCPayStoreID,
			cfg.BTCPayWebhookSecret,
			cfg.CheckoutSuccessURL,
		)
		if cfg.BTCPayPublicURL != "" {
			btcpay.SetPublicURL(cfg.BTCPayPublicURL)
		}
		invoiceCreator = btcpay
		log.Info("BTCPay provider configured", zap.String("url", cfg.BTCPayServerURL), zap.String("store", cfg.BTCPayStoreID))
		if cfg.BTCPayWebhookSecret == "" {
			log.Warn("BTCPAY_WEBHOOK_SECRET is empty — webhooks will be rejected")
		}
	}

	svc := service.New(log, db, natsConn, invoiceCreator, btcpay, mock, service.BillingConfig{
		PremiumPriceUSDCents: cfg.PremiumPriceUSDCents,
		PremiumPeriodDays:    cfg.PremiumPeriodDays,
		BitcoinReadinessURL:  cfg.BitcoinReadinessURL,
	})

	tokenVerifier := tokenauth.NewVerifier(cfg.JWTSecret)
	billingHandler := handler.NewBillingHandler(log, svc, tokenVerifier, cfg.AllowedCORSOrigins(), cfg.CheckoutSuccessURL, useMock)

	mux := http.NewServeMux()
	billingHandler.RegisterRoutes(mux)

	// Background expiry worker
	go func() {
		ticker := time.NewTicker(1 * time.Hour)
		defer ticker.Stop()
		for {
			n, err := svc.ExpireDueSubscriptions(context.Background())
			if err != nil {
				log.Error("expiry worker failed", zap.Error(err))
			} else if n > 0 {
				log.Info("expired premium subscriptions", zap.Int("count", n))
			}
			<-ticker.C
		}
	}()

	addr := cfg.ServerAddr()
	srv := &http.Server{
		Addr:         addr,
		Handler:      securityHeaders(mux),
		ReadTimeout:  15 * time.Second,
		WriteTimeout: 15 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	go func() {
		log.Info("billing-svc starting", zap.String("addr", addr))
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatal("HTTP server failed", zap.Error(err))
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	log.Info("shutting down billing-svc...")
	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer shutdownCancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		log.Fatal("server forced to shutdown", zap.Error(err))
	}
	log.Info("billing-svc stopped")
}

func connectDatabase(databaseURL string, log *logging.Logger) (*pgxpool.Pool, error) {
	var lastErr error
	deadline := time.Now().Add(5 * time.Minute)
	for attempt := 1; ; attempt++ {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		pool, err := pgxpool.New(ctx, databaseURL)
		if err == nil {
			err = pool.Ping(ctx)
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
		time.Sleep(2 * time.Second)
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
