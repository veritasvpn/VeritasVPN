package handler

import (
	"context"
	"strings"
	"time"

	"github.com/veritasvpn/lib/logging"
	authv1 "github.com/veritasvpn/api/gen/auth/v1"
	"github.com/veritasvpn/services/auth-svc/internal/middleware"
	"github.com/veritasvpn/services/auth-svc/internal/service"
	"go.uber.org/zap"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

type AuthHandler struct {
	authv1.UnimplementedAuthServiceServer
	log     *logging.Logger
	service *service.AuthService
}

func NewAuthHandler(log *logging.Logger, svc *service.AuthService) *AuthHandler {
	return &AuthHandler{log: log, service: svc}
}

func (h *AuthHandler) Register(ctx context.Context, req *authv1.RegisterRequest) (*authv1.RegisterResponse, error) {
	accessToken, refreshToken, accountID, expiresAt, err := h.service.Register(ctx, req.DeviceId, req.PublicKey)
	if err != nil {
		h.log.Error("register failed", zap.Error(err))
		return nil, status.Error(codes.Internal, err.Error())
	}

	return &authv1.RegisterResponse{
		AccessToken:  accessToken,
		RefreshToken: refreshToken,
		AccountId:    accountID,
		ExpiresAt:    expiresAt,
	}, nil
}

func (h *AuthHandler) RefreshToken(ctx context.Context, req *authv1.RefreshTokenRequest) (*authv1.RefreshTokenResponse, error) {
	accessToken, refreshToken, expiresAt, err := h.service.RefreshToken(ctx, req.RefreshToken)
	if err != nil {
		h.log.Error("refresh token failed", zap.Error(err))
		return nil, status.Error(codes.Unauthenticated, "invalid refresh token")
	}

	return &authv1.RefreshTokenResponse{
		AccessToken:  accessToken,
		RefreshToken: refreshToken,
		ExpiresAt:    expiresAt,
	}, nil
}

func (h *AuthHandler) ValidateToken(ctx context.Context, req *authv1.ValidateTokenRequest) (*authv1.ValidateTokenResponse, error) {
	claims, err := h.service.ValidateToken(ctx, req.AccessToken)
	if err != nil {
		return &authv1.ValidateTokenResponse{Valid: false}, nil
	}

	return &authv1.ValidateTokenResponse{
		Valid:     true,
		AccountId: claims.AccountID,
		Tier:      claims.Tier,
	}, nil
}

func (h *AuthHandler) GetAccount(ctx context.Context, req *authv1.GetAccountRequest) (*authv1.GetAccountResponse, error) {
	accountID, err := middleware.AccountIDFromContext(ctx)
	if err != nil {
		return nil, status.Error(codes.Unauthenticated, "unauthenticated")
	}
	if req.AccountId != "" && req.AccountId != accountID {
		return nil, status.Error(codes.PermissionDenied, "permission denied")
	}

	acc, err := h.service.GetAccount(ctx, accountID)
	if err != nil {
		h.log.Error("get account failed", zap.Error(err))
		return nil, status.Error(codes.NotFound, "account not found")
	}

	resp := &authv1.GetAccountResponse{
		AccountId: acc.ID,
		Tier:      acc.SubscriptionTier,
		Status:    acc.AccountStatus,
		CreatedAt: acc.CreatedAt.Unix(),
	}

	if acc.SubscriptionExpiry != nil {
		resp.SubscriptionExpiry = acc.SubscriptionExpiry.Unix()
	}

	return resp, nil
}

func (h *AuthHandler) DeleteAccount(ctx context.Context, req *authv1.DeleteAccountRequest) (*authv1.DeleteAccountResponse, error) {
	accountID, err := middleware.AccountIDFromContext(ctx)
	if err != nil {
		return nil, status.Error(codes.Unauthenticated, "unauthenticated")
	}
	if req.AccountId != "" && req.AccountId != accountID {
		return nil, status.Error(codes.PermissionDenied, "permission denied")
	}

	accessToken, _ := middleware.AccessTokenFromContext(ctx)
	if err := h.service.DeleteAccount(ctx, accountID, accessToken); err != nil {
		h.log.Error("delete account failed", zap.Error(err))
		if strings.Contains(err.Error(), "teardown") {
			return nil, status.Error(codes.Unavailable, "failed to revoke VPN sessions; try again shortly")
		}
		return nil, status.Error(codes.Internal, "failed to delete account")
	}

	return &authv1.DeleteAccountResponse{Success: true}, nil
}

func timeToUnix(t time.Time) int64 {
	if t.IsZero() {
		return 0
	}
	return t.Unix()
}
