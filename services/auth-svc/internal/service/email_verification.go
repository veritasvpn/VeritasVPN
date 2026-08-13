package service

import (
	"context"
	"fmt"
	"net/mail"
	"net/url"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	libcrypto "github.com/veritasvpn/lib/crypto"
	"github.com/veritasvpn/services/auth-svc/internal/email"
	"github.com/veritasvpn/services/auth-svc/internal/model"
)

const verificationTTL = time.Hour

func validatePassword(password string) error {
	if utf8.RuneCountInString(password) < 10 {
		return fmt.Errorf("password must be at least 10 characters")
	}
	var hasUpper, hasLower, hasNumber bool
	for _, character := range password {
		hasUpper = hasUpper || unicode.IsUpper(character)
		hasLower = hasLower || unicode.IsLower(character)
		hasNumber = hasNumber || unicode.IsNumber(character)
	}
	if !hasUpper || !hasLower || !hasNumber {
		return fmt.Errorf("password must include uppercase, lowercase, and a number")
	}
	return nil
}

func normalizeEmail(value string) (string, error) {
	value = strings.ToLower(strings.TrimSpace(value))
	parsed, err := mail.ParseAddress(value)
	if err != nil || parsed.Address != value || len(value) > 254 {
		return "", fmt.Errorf("invalid email address")
	}
	return value, nil
}

func (s *AuthService) RegisterPendingEmail(ctx context.Context, emailAddr, password string) (string, error) {
	emailAddr, err := normalizeEmail(emailAddr)
	if err != nil {
		return "", err
	}
	if err := validatePassword(password); err != nil {
		return "", err
	}
	if s.email == nil {
		return "", fmt.Errorf("email delivery is temporarily unavailable")
	}
	passwordHash, err := libcrypto.HashPassword(password)
	if err != nil {
		return "", fmt.Errorf("hash password: %w", err)
	}
	accountID, err := libcrypto.GenerateAccountID()
	if err != nil {
		return "", err
	}
	deviceID, err := libcrypto.GenerateRefreshToken()
	if err != nil {
		return "", err
	}
	token, err := libcrypto.GenerateResetToken()
	if err != nil {
		return "", err
	}
	expiry := time.Now().Add(verificationTTL)
	acc := &model.Account{ID: accountID, HashedDeviceID: hashInput(deviceID), HashedPublicKey: "",
		Email: &emailAddr, PasswordHash: &passwordHash, SubscriptionTier: "free",
		VerificationTokenHash: stringPtr(hashInput(token)), VerificationTokenExpiry: &expiry}
	if err := s.db.CreatePendingEmailAccount(ctx, acc); err != nil {
		return "", fmt.Errorf("an account with this email already exists")
	}
	if err := s.sendVerificationEmail(ctx, emailAddr, token); err != nil {
		return "", fmt.Errorf("send verification email: %w", err)
	}
	return emailAddr, nil
}

func stringPtr(value string) *string { return &value }

func (s *AuthService) sendVerificationEmail(ctx context.Context, emailAddr, token string) error {
	verifyURL := fmt.Sprintf("%s/verify-email.html?token=%s", strings.TrimRight(s.cfg.PublicBaseURL, "/"), url.QueryEscape(token))
	return s.email.Send(ctx, email.SendRequest{From: "VeritasVPN <noreply@veritasvpn.cloud>", To: emailAddr,
		Subject: "Verify your VeritasVPN email", HTML: verificationEmailHTML(verifyURL)})
}

func verificationEmailHTML(verifyURL string) string {
	return `<!doctype html><html><body style="font-family:Arial;background:#050b18;color:#eaf7ff;padding:40px;text-align:center">
	<h1 style="color:#09c7f5">Verify your email</h1><p>Confirm that this email belongs to you to activate your VeritasVPN account.</p>
	<a href="` + verifyURL + `" style="display:inline-block;padding:14px 28px;border-radius:10px;background:#0878ff;color:white;text-decoration:none;font-weight:700">Verify email</a>
	<p style="color:#8797ad">This single-use link expires in 1 hour. If you did not create this account, ignore this email.</p></body></html>`
}

func (s *AuthService) VerifyEmail(ctx context.Context, token string) error {
	if strings.TrimSpace(token) == "" {
		return fmt.Errorf("verification token is required")
	}
	_, err := s.db.VerifyEmailToken(ctx, hashInput(token))
	return err
}

func (s *AuthService) ResendVerification(ctx context.Context, emailAddr string) error {
	emailAddr, err := normalizeEmail(emailAddr)
	if err != nil {
		return nil
	}
	acc, err := s.db.GetAccountByEmail(ctx, emailAddr)
	if err != nil || acc.EmailVerifiedAt != nil {
		return nil
	}
	if acc.VerificationSentAt != nil && time.Since(*acc.VerificationSentAt) < time.Minute {
		return nil
	}
	token, err := libcrypto.GenerateResetToken()
	if err != nil {
		return err
	}
	if err = s.db.SetVerificationToken(ctx, acc.ID, hashInput(token), time.Now().Add(verificationTTL)); err != nil {
		return err
	}
	return s.sendVerificationEmail(ctx, emailAddr, token)
}

func (s *AuthService) CleanupUnverified(ctx context.Context) (int64, error) {
	return s.db.DeleteExpiredUnverifiedAccounts(ctx, time.Now().Add(-72*time.Hour))
}
