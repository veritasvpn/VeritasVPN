package service

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/nats-io/nats.go"
	"github.com/veritasvpn/lib/config"
	libcrypto "github.com/veritasvpn/lib/crypto"
	jwtlib "github.com/veritasvpn/lib/jwt"
	"github.com/veritasvpn/lib/logging"
	"github.com/veritasvpn/services/auth-svc/internal/email"
	"github.com/veritasvpn/services/auth-svc/internal/model"
	"github.com/veritasvpn/services/auth-svc/internal/repository"
	"github.com/veritasvpn/services/auth-svc/internal/turnstile"
	"go.uber.org/zap"
)

type AuthService struct {
	log   *logging.Logger
	db    *repository.Postgres
	redis *repository.Redis
	jwt   *jwtlib.Manager
	email *email.Client
	cfg   *config.Config
	nats  *nats.Conn
}

func New(log *logging.Logger, db *repository.Postgres, redis *repository.Redis, jwt *jwtlib.Manager, emailClient *email.Client, cfg *config.Config) *AuthService {
	return &AuthService{log: log, db: db, redis: redis, jwt: jwt, email: emailClient, cfg: cfg}
}

func (s *AuthService) SetNATS(nc *nats.Conn) { s.nats = nc }

func (s *AuthService) publishEvent(subject string, payload map[string]interface{}) {
	if s.nats == nil {
		return
	}
	data, err := json.Marshal(payload)
	if err != nil {
		s.log.Warn("failed to marshal activity event", zap.Error(err))
		return
	}
	if err := s.nats.Publish(subject, data); err != nil {
		s.log.Warn("failed to publish activity event", zap.String("subject", subject), zap.Error(err))
	}
}

func (s *AuthService) RateLimited(ctx context.Context, key string, limit int, window time.Duration) bool {
	limited, err := s.redis.CheckRateLimit(ctx, "auth:"+key, limit, window)
	if err != nil {
		s.log.Warn("rate limit unavailable", zap.Error(err))
		return true
	}
	return limited
}

func hashInput(input string) string {
	h := sha256.Sum256([]byte(input))
	return hex.EncodeToString(h[:])
}

func (s *AuthService) Register(ctx context.Context, deviceID, publicKey string) (string, string, string, int64, error) {
	if deviceID == "" || publicKey == "" {
		return "", "", "", 0, fmt.Errorf("device_id and public_key are required")
	}

	hashedDeviceID := hashInput(deviceID)
	hashedPublicKey := hashInput(publicKey)

	accountID, err := libcrypto.GenerateAccountID()
	if err != nil {
		return "", "", "", 0, fmt.Errorf("generate account id: %w", err)
	}

	acc := &model.Account{
		ID:               accountID,
		HashedDeviceID:   hashedDeviceID,
		HashedPublicKey:  hashedPublicKey,
		SubscriptionTier: "free",
	}

	if err := s.db.CreateAccount(ctx, acc); err != nil {
		s.log.Error("failed to create account", zap.Error(err))
		return "", "", "", 0, fmt.Errorf("create account: %w", err)
	}

	accessToken, expiresAt, err := s.jwt.GenerateAccessToken(acc.ID, acc.SubscriptionTier)
	if err != nil {
		return "", "", "", 0, fmt.Errorf("generate access token: %w", err)
	}

	refreshToken, err := libcrypto.GenerateRefreshToken()
	if err != nil {
		return "", "", "", 0, fmt.Errorf("generate refresh token: %w", err)
	}

	refreshTokenHash := hashInput(refreshToken)
	rt := &model.RefreshToken{
		AccountID: acc.ID,
		TokenHash: refreshTokenHash,
		ExpiresAt: time.Now().Add(30 * 24 * time.Hour),
	}

	if err := s.db.StoreRefreshToken(ctx, rt); err != nil {
		return "", "", "", 0, fmt.Errorf("store refresh token: %w", err)
	}

	s.log.Info("account registered",
		zap.String("account_hash", logging.HashIdentifier(acc.ID)),
		zap.String("tier", acc.SubscriptionTier),
	)

	s.publishEvent("account.registered", map[string]interface{}{"account_type": "device"})

	return accessToken, refreshToken, acc.ID, expiresAt, nil
}

func (s *AuthService) RefreshToken(ctx context.Context, refreshToken string) (string, string, int64, error) {
	tokenHash := hashInput(refreshToken)

	rt, err := s.db.GetRefreshToken(ctx, tokenHash)
	if err != nil {
		return "", "", 0, fmt.Errorf("invalid refresh token: %w", err)
	}

	if err := s.db.DeleteRefreshToken(ctx, tokenHash); err != nil {
		s.log.Warn("failed to delete old refresh token", zap.Error(err))
	}

	acc, err := s.db.GetAccountByID(ctx, rt.AccountID)
	if err != nil {
		return "", "", 0, fmt.Errorf("get account: %w", err)
	}
	if acc.Email != nil && acc.EmailVerifiedAt == nil {
		return "", "", 0, fmt.Errorf("email_not_verified")
	}

	accessToken, expiresAt, err := s.jwt.GenerateAccessToken(acc.ID, acc.SubscriptionTier)
	if err != nil {
		return "", "", 0, fmt.Errorf("generate access token: %w", err)
	}

	newRefreshToken, err := libcrypto.GenerateRefreshToken()
	if err != nil {
		return "", "", 0, fmt.Errorf("generate refresh token: %w", err)
	}

	newTokenHash := hashInput(newRefreshToken)
	newRT := &model.RefreshToken{
		AccountID: acc.ID,
		TokenHash: newTokenHash,
		ExpiresAt: time.Now().Add(30 * 24 * time.Hour),
	}

	if err := s.db.StoreRefreshToken(ctx, newRT); err != nil {
		return "", "", 0, fmt.Errorf("store new refresh token: %w", err)
	}

	return accessToken, newRefreshToken, expiresAt, nil
}

func (s *AuthService) ValidateToken(ctx context.Context, accessToken string) (*jwtlib.Claims, error) {
	tokenHash := hashInput(accessToken)

	blacklisted, err := s.redis.IsTokenBlacklisted(ctx, tokenHash)
	if err != nil {
		return nil, fmt.Errorf("check blacklist: %w", err)
	}
	if blacklisted {
		return nil, fmt.Errorf("token has been revoked")
	}

	claims, err := s.jwt.ValidateAccessToken(accessToken)
	if err != nil {
		return nil, fmt.Errorf("validate token: %w", err)
	}
	if _, err := s.db.GetAccountByID(ctx, claims.AccountID); err != nil {
		return nil, fmt.Errorf("account is no longer active: %w", err)
	}

	return claims, nil
}

func (s *AuthService) GetAccount(ctx context.Context, accountID string) (*model.Account, error) {
	return s.db.GetAccountByID(ctx, accountID)
}

func (s *AuthService) DeleteAccount(ctx context.Context, accountID string) error {
	if err := s.db.DeleteAccount(ctx, accountID); err != nil {
		return fmt.Errorf("delete account: %w", err)
	}

	s.log.Info("account permanently deleted", zap.String("account_hash", logging.HashIdentifier(accountID)))
	return nil
}

// LogoutAllSessions revokes every refresh token for the account and blacklists
// the caller's current access token so it cannot be reused until it expires.
func (s *AuthService) LogoutAllSessions(ctx context.Context, accountID, accessToken string) error {
	if err := s.db.DeleteAllRefreshTokens(ctx, accountID); err != nil {
		return fmt.Errorf("delete refresh tokens: %w", err)
	}

	ttl := s.cfg.AccessTokenTTL
	if claims, err := s.jwt.ValidateAccessToken(accessToken); err == nil && claims.ExpiresAt != nil {
		if remaining := time.Until(claims.ExpiresAt.Time); remaining > 0 {
			ttl = remaining
		}
	}
	if ttl > 0 {
		if err := s.redis.BlacklistToken(ctx, hashInput(accessToken), ttl); err != nil {
			return fmt.Errorf("blacklist access token: %w", err)
		}
	}

	s.log.Info("all sessions logged out", zap.String("account_hash", logging.HashIdentifier(accountID)))
	return nil
}

func (s *AuthService) RegisterWithEmail(ctx context.Context, email, password string) (string, string, string, int64, error) {
	if email == "" || password == "" {
		return "", "", "", 0, fmt.Errorf("email and password are required")
	}
	if err := validatePassword(password); err != nil {
		return "", "", "", 0, err
	}

	passwordHash, err := libcrypto.HashPassword(password)
	if err != nil {
		return "", "", "", 0, fmt.Errorf("hash password: %w", err)
	}

	accountID, err := libcrypto.GenerateAccountID()
	if err != nil {
		return "", "", "", 0, fmt.Errorf("generate account id: %w", err)
	}

	deviceID, err := libcrypto.GenerateRefreshToken()
	if err != nil {
		return "", "", "", 0, fmt.Errorf("generate device id: %w", err)
	}

	emailVal := email
	acc := &model.Account{
		ID:               accountID,
		HashedDeviceID:   hashInput(deviceID),
		HashedPublicKey:  "",
		Email:            &emailVal,
		PasswordHash:     &passwordHash,
		SubscriptionTier: "free",
	}

	if err := s.db.CreateAccountWithEmail(ctx, acc); err != nil {
		s.log.Error("failed to create account with email", zap.Error(err))
		return "", "", "", 0, fmt.Errorf("create account: %w", err)
	}

	accessToken, expiresAt, err := s.jwt.GenerateAccessToken(acc.ID, acc.SubscriptionTier)
	if err != nil {
		return "", "", "", 0, fmt.Errorf("generate access token: %w", err)
	}

	refreshToken, err := libcrypto.GenerateRefreshToken()
	if err != nil {
		return "", "", "", 0, fmt.Errorf("generate refresh token: %w", err)
	}

	refreshTokenHash := hashInput(refreshToken)
	rt := &model.RefreshToken{
		AccountID: acc.ID,
		TokenHash: refreshTokenHash,
		ExpiresAt: time.Now().Add(30 * 24 * time.Hour),
	}

	if err := s.db.StoreRefreshToken(ctx, rt); err != nil {
		return "", "", "", 0, fmt.Errorf("store refresh token: %w", err)
	}

	s.log.Info("account registered with email",
		zap.String("account_hash", logging.HashIdentifier(acc.ID)),
		zap.String("email", email),
	)

	s.publishEvent("account.registered", map[string]interface{}{"account_type": "email"})

	return accessToken, refreshToken, acc.ID, expiresAt, nil
}

func (s *AuthService) SignInWithEmail(ctx context.Context, email, password string) (string, string, string, int64, error) {
	if email == "" || password == "" {
		return "", "", "", 0, fmt.Errorf("email and password are required")
	}
	if len(password) < 10 {
		return "", "", "", 0, fmt.Errorf("invalid email or password")
	}

	acc, err := s.db.GetAccountByEmail(ctx, email)
	if err != nil {
		return "", "", "", 0, fmt.Errorf("invalid email or password: %w", err)
	}

	if acc.PasswordHash == nil || !libcrypto.CheckPassword(password, *acc.PasswordHash) {
		return "", "", "", 0, fmt.Errorf("invalid email or password")
	}
	if acc.EmailVerifiedAt == nil {
		return "", "", "", 0, fmt.Errorf("email_not_verified")
	}

	accessToken, expiresAt, err := s.jwt.GenerateAccessToken(acc.ID, acc.SubscriptionTier)
	if err != nil {
		return "", "", "", 0, fmt.Errorf("generate access token: %w", err)
	}

	refreshToken, err := libcrypto.GenerateRefreshToken()
	if err != nil {
		return "", "", "", 0, fmt.Errorf("generate refresh token: %w", err)
	}

	refreshTokenHash := hashInput(refreshToken)
	rt := &model.RefreshToken{
		AccountID: acc.ID,
		TokenHash: refreshTokenHash,
		ExpiresAt: time.Now().Add(30 * 24 * time.Hour),
	}

	if err := s.db.StoreRefreshToken(ctx, rt); err != nil {
		return "", "", "", 0, fmt.Errorf("store refresh token: %w", err)
	}

	s.log.Info("user signed in with email",
		zap.String("account_hash", logging.HashIdentifier(acc.ID)),
	)

	return accessToken, refreshToken, acc.ID, expiresAt, nil
}

func (s *AuthService) RequestPasswordReset(ctx context.Context, emailAddr string) error {
	acc, err := s.db.GetAccountByEmail(ctx, emailAddr)
	if err != nil {
		return fmt.Errorf("no account found with this email")
	}

	token, err := libcrypto.GenerateResetToken()
	if err != nil {
		return fmt.Errorf("generate reset token: %w", err)
	}

	expiry := time.Now().Add(1 * time.Hour)
	if err := s.db.SetResetToken(ctx, acc.ID, token, expiry); err != nil {
		return fmt.Errorf("set reset token: %w", err)
	}

	s.log.Info("password reset requested",
		zap.String("account_hash", logging.HashIdentifier(acc.ID)),
	)

	if s.email != nil {
		resetURL := fmt.Sprintf("%s/reset-password?token=%s", s.cfg.PublicBaseURL, token)
		if err := s.email.Send(ctx, email.SendRequest{
			From:    "VeritasVPN <noreply@veritasvpn.cloud>",
			To:      emailAddr,
			Subject: "Reset your VeritasVPN password",
			HTML:    resetEmailHTML(resetURL),
		}); err != nil {
			s.log.Error("failed to send reset email", zap.Error(err))
		} else {
			s.log.Info("reset email sent", zap.String("account_hash", logging.HashIdentifier(acc.ID)))
		}
	}

	return nil
}

func resetEmailHTML(resetURL string) string {
	return `<!DOCTYPE html>
<html>
<body style="font-family: sans-serif; background: #0a0a0f; color: #e0e0e0; padding: 40px; text-align: center;">
  <h2 style="color: #00d2ff;">VeritasVPN</h2>
  <p>You requested a password reset. Click the button below to set a new password. This link expires in 1 hour.</p>
  <a href="` + resetURL + `" style="display: inline-block; margin: 20px 0; padding: 14px 32px; background: linear-gradient(135deg, #00d2ff, #7b61ff); color: #fff; text-decoration: none; border-radius: 8px; font-weight: 600;">Reset Password</a>
  <p style="color: #888; font-size: 13px;">If you did not request this, you can safely ignore this email.</p>
</body>
</html>`
}

func (s *AuthService) ResetPassword(ctx context.Context, resetToken, newPassword string) error {
	if err := validatePassword(newPassword); err != nil {
		return err
	}

	acc, err := s.db.GetAccountByResetToken(ctx, resetToken)
	if err != nil {
		return fmt.Errorf("invalid or expired reset token")
	}

	passwordHash, err := libcrypto.HashPassword(newPassword)
	if err != nil {
		return fmt.Errorf("hash password: %w", err)
	}

	if err := s.db.UpdateAccountPassword(ctx, acc.ID, passwordHash); err != nil {
		return fmt.Errorf("update password: %w", err)
	}

	s.log.Info("password reset completed",
		zap.String("account_hash", logging.HashIdentifier(acc.ID)),
	)

	return nil
}

func (s *AuthService) GetAccountByEmail(ctx context.Context, email string) (*model.Account, error) {
	return s.db.GetAccountByEmail(ctx, email)
}

func (s *AuthService) RegisterAnonymous(ctx context.Context) (string, string, string, int64, error) {
	accountID, err := libcrypto.GenerateAccountID()
	if err != nil {
		return "", "", "", 0, fmt.Errorf("generate account id: %w", err)
	}

	deviceID, err := libcrypto.GenerateRefreshToken()
	if err != nil {
		return "", "", "", 0, fmt.Errorf("generate device id: %w", err)
	}

	acc := &model.Account{
		ID:               accountID,
		HashedDeviceID:   hashInput(deviceID),
		HashedPublicKey:  "",
		SubscriptionTier: "free",
	}

	if err := s.db.CreateAccount(ctx, acc); err != nil {
		s.log.Error("failed to create anonymous account", zap.Error(err))
		return "", "", "", 0, fmt.Errorf("create account: %w", err)
	}

	accessToken, expiresAt, err := s.jwt.GenerateAccessToken(acc.ID, acc.SubscriptionTier)
	if err != nil {
		return "", "", "", 0, fmt.Errorf("generate access token: %w", err)
	}

	refreshToken, err := libcrypto.GenerateRefreshToken()
	if err != nil {
		return "", "", "", 0, fmt.Errorf("generate refresh token: %w", err)
	}

	refreshTokenHash := hashInput(refreshToken)
	rt := &model.RefreshToken{
		AccountID: acc.ID,
		TokenHash: refreshTokenHash,
		ExpiresAt: time.Now().Add(30 * 24 * time.Hour),
	}

	if err := s.db.StoreRefreshToken(ctx, rt); err != nil {
		return "", "", "", 0, fmt.Errorf("store refresh token: %w", err)
	}

	s.log.Info("anonymous account created",
		zap.String("account_hash", logging.HashIdentifier(acc.ID)),
	)

	s.publishEvent("account.registered", map[string]interface{}{"account_type": "anonymous"})

	return accessToken, refreshToken, acc.ID, expiresAt, nil
}

func (s *AuthService) SignInWithAccountID(ctx context.Context, accountID string) (string, string, string, int64, error) {
	if accountID == "" {
		return "", "", "", 0, fmt.Errorf("account_id is required")
	}

	acc, err := s.db.GetAccountByID(ctx, accountID)
	if err != nil {
		return "", "", "", 0, fmt.Errorf("invalid account_id")
	}

	accessToken, expiresAt, err := s.jwt.GenerateAccessToken(acc.ID, acc.SubscriptionTier)
	if err != nil {
		return "", "", "", 0, fmt.Errorf("generate access token: %w", err)
	}

	refreshToken, err := libcrypto.GenerateRefreshToken()
	if err != nil {
		return "", "", "", 0, fmt.Errorf("generate refresh token: %w", err)
	}

	refreshTokenHash := hashInput(refreshToken)
	rt := &model.RefreshToken{
		AccountID: acc.ID,
		TokenHash: refreshTokenHash,
		ExpiresAt: time.Now().Add(30 * 24 * time.Hour),
	}

	if err := s.db.StoreRefreshToken(ctx, rt); err != nil {
		return "", "", "", 0, fmt.Errorf("store refresh token: %w", err)
	}

	s.log.Info("anonymous user signed in",
		zap.String("account_hash", logging.HashIdentifier(acc.ID)),
	)

	return accessToken, refreshToken, acc.ID, expiresAt, nil
}

func (s *AuthService) TurnstileEnabled() bool {
	return strings.TrimSpace(s.cfg.TurnstileSecretKey) != ""
}

func (s *AuthService) VerifyTurnstile(ctx context.Context, token, remoteIP string) error {
	return turnstile.Verify(ctx, s.cfg.TurnstileSecretKey, token, remoteIP)
}
