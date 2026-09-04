package middleware

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"strings"

	"github.com/veritasvpn/lib/jwt"
	"github.com/veritasvpn/lib/logging"
	jwtlib "github.com/veritasvpn/lib/jwt"
	"github.com/veritasvpn/services/auth-svc/internal/repository"
	"go.uber.org/zap"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
)

type contextKey string

const AccountIDKey contextKey = "account_id"
const TierKey contextKey = "tier"
const AccessTokenKey contextKey = "access_token"

type AuthInterceptor struct {
	log    *logging.Logger
	jwt    *jwtlib.Manager
	redis  *repository.Redis
}

func NewAuthInterceptor(log *logging.Logger, jwt *jwtlib.Manager, redis *repository.Redis) *AuthInterceptor {
	return &AuthInterceptor{log: log, jwt: jwt, redis: redis}
}

func (i *AuthInterceptor) Unary() grpc.UnaryServerInterceptor {
	return func(ctx context.Context, req interface{}, info *grpc.UnaryServerInfo, handler grpc.UnaryHandler) (interface{}, error) {
		newCtx, err := i.authorize(ctx, info.FullMethod)
		if err != nil {
			return nil, err
		}
		return handler(newCtx, req)
	}
}

func (i *AuthInterceptor) authorize(ctx context.Context, method string) (context.Context, error) {
	methods := []string{
		"/auth.v1.AuthService/Register",
		"/auth.v1.AuthService/RefreshToken",
		"/auth.v1.AuthService/ValidateToken",
	}

	for _, m := range methods {
		if method == m {
			return ctx, nil
		}
	}

	md, ok := metadata.FromIncomingContext(ctx)
	if !ok {
		return nil, status.Error(codes.Unauthenticated, "metadata is missing")
	}

	values := md.Get("authorization")
	if len(values) == 0 {
		return nil, status.Error(codes.Unauthenticated, "authorization token is missing")
	}

	token := strings.TrimPrefix(values[0], "Bearer ")
	if token == values[0] {
		return nil, status.Error(codes.Unauthenticated, "malformed authorization header")
	}

	tokenHash := hashToken(token)

	blacklisted, err := i.redis.IsTokenBlacklisted(ctx, tokenHash)
	if err != nil {
		i.log.Error("failed to check token blacklist", zap.Error(err))
		return nil, status.Error(codes.Internal, "internal error")
	}
	if blacklisted {
		return nil, status.Error(codes.Unauthenticated, "token has been revoked")
	}

	claims, err := i.jwt.ValidateAccessToken(token)
	if err != nil {
		return nil, status.Error(codes.Unauthenticated, "invalid or expired token")
	}

	newCtx := context.WithValue(ctx, AccountIDKey, claims.AccountID)
	newCtx = context.WithValue(newCtx, TierKey, claims.Tier)
	newCtx = context.WithValue(newCtx, AccessTokenKey, token)

	return newCtx, nil
}

func hashToken(token string) string {
	h := sha256.Sum256([]byte(token))
	return hex.EncodeToString(h[:])
}

func AccountIDFromContext(ctx context.Context) (string, error) {
	id, ok := ctx.Value(AccountIDKey).(string)
	if !ok {
		return "", fmt.Errorf("account_id not found in context")
	}
	return id, nil
}

func AccessTokenFromContext(ctx context.Context) (string, bool) {
	token, ok := ctx.Value(AccessTokenKey).(string)
	return token, ok && token != ""
}

func TierFromContext(ctx context.Context) (string, error) {
	tier, ok := ctx.Value(TierKey).(string)
	if !ok {
		return "", fmt.Errorf("tier not found in context")
	}
	return tier, nil
}

func ValidateTokenMiddleware(jwtMgr *jwt.Manager, redis *repository.Redis, log *logging.Logger) func(ctx context.Context, token string) (*jwt.Claims, error) {
	return func(ctx context.Context, token string) (*jwt.Claims, error) {
		tokenHash := hashToken(token)

		blacklisted, err := redis.IsTokenBlacklisted(ctx, tokenHash)
		if err != nil {
			return nil, fmt.Errorf("blacklist check: %w", err)
		}
		if blacklisted {
			return nil, fmt.Errorf("token revoked")
		}

		claims, err := jwtMgr.ValidateAccessToken(token)
		if err != nil {
			return nil, err
		}
		return claims, nil
	}
}
