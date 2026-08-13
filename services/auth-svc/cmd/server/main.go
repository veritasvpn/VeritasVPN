package main

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/nats-io/nats.go"
	authv1 "github.com/veritasvpn/api/gen/auth/v1"
	"github.com/veritasvpn/lib/config"
	jwtlib "github.com/veritasvpn/lib/jwt"
	"github.com/veritasvpn/lib/logging"
	"github.com/veritasvpn/services/auth-svc/internal/email"
	"github.com/veritasvpn/services/auth-svc/internal/handler"
	"github.com/veritasvpn/services/auth-svc/internal/middleware"
	"github.com/veritasvpn/services/auth-svc/internal/repository"
	"github.com/veritasvpn/services/auth-svc/internal/service"
	"go.uber.org/zap"
	"google.golang.org/grpc"
	"google.golang.org/grpc/reflection"
)

func main() {
	cfg := config.Load()
	log, err := logging.New(cfg.LogLevel)
	if err != nil {
		fmt.Fprintf(os.Stderr, "failed to initialize logger: %v\n", err)
		os.Exit(1)
	}
	defer log.Sync()

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	dbPool, err := pgxpool.New(ctx, cfg.DatabaseURL)
	if err != nil {
		log.Fatal("failed to connect to database", zap.Error(err))
	}
	defer dbPool.Close()

	if err := dbPool.Ping(ctx); err != nil {
		log.Fatal("database ping failed", zap.Error(err))
	}
	log.Info("connected to PostgreSQL")

	redisClient, err := repository.NewRedis(cfg.RedisURL)
	if err != nil {
		log.Fatal("failed to connect to Redis", zap.Error(err))
	}
	log.Info("connected to Redis")

	db := repository.NewPostgres(dbPool)
	jwtMgr := jwtlib.NewManager(cfg.JWTSecret, cfg.AccessTokenTTL, cfg.RefreshTokenTTL)
	var emailClient *email.Client
	if cfg.ResendAPIKey != "" {
		emailClient = email.NewClient(cfg.ResendAPIKey)
		log.Info("resend email client configured")
	}
	svc := service.New(log, db, redisClient, jwtMgr, emailClient, cfg)
	go func() {
		ticker := time.NewTicker(6 * time.Hour)
		defer ticker.Stop()
		for range ticker.C {
			if deleted, err := svc.CleanupUnverified(context.Background()); err != nil {
				log.Warn("unverified account cleanup failed", zap.Error(err))
			} else if deleted > 0 {
				log.Info("removed expired unverified accounts", zap.Int64("count", deleted))
			}
		}
	}()
	authHandler := handler.NewAuthHandler(log, svc)
	authInterceptor := middleware.NewAuthInterceptor(log, jwtMgr, redisClient)

	var natsConn *nats.Conn
	if cfg.NatsURL != "" {
		nc, err := nats.Connect(cfg.NatsURL)
		if err != nil {
			log.Warn("failed to connect to NATS; tier sync disabled", zap.Error(err))
		} else {
			natsConn = nc
			defer natsConn.Close()
			if err := svc.StartSubscriptionSync(natsConn, log); err != nil {
				log.Warn("subscription sync subscribe failed", zap.Error(err))
			} else {
				log.Info("listening for billing subscription events")
			}
		}
	}

	grpcServer := grpc.NewServer(
		grpc.UnaryInterceptor(authInterceptor.Unary()),
	)
	authv1.RegisterAuthServiceServer(grpcServer, authHandler)
	reflection.Register(grpcServer)

	lis, err := net.Listen("tcp", cfg.GRPCServerAddr())
	if err != nil {
		log.Fatal("failed to listen gRPC", zap.Error(err))
	}

	go func() {
		log.Info("auth-svc gRPC starting", zap.String("addr", cfg.GRPCServerAddr()))
		if err := grpcServer.Serve(lis); err != nil {
			log.Fatal("gRPC server failed", zap.Error(err))
		}
	}()

	httpHandler := handler.NewHTTPHandler(log, svc, cfg.AllowedCORSOrigins())
	mux := http.NewServeMux()
	httpHandler.RegisterRoutes(mux)

	httpSrv := &http.Server{
		Addr:         cfg.ServerAddr(),
		Handler:      mux,
		ReadTimeout:  15 * time.Second,
		WriteTimeout: 15 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	go func() {
		log.Info("auth-svc HTTP starting", zap.String("addr", cfg.ServerAddr()))
		if err := httpSrv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatal("HTTP server failed", zap.Error(err))
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	log.Info("shutting down auth-svc...")
	grpcServer.GracefulStop()

	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer shutdownCancel()
	if err := httpSrv.Shutdown(shutdownCtx); err != nil {
		log.Fatal("HTTP server forced to shutdown", zap.Error(err))
	}
	log.Info("auth-svc stopped")
}
