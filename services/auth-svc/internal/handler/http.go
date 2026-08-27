package handler

import (
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"strings"
	"time"

	"github.com/veritasvpn/lib/logging"
	"github.com/veritasvpn/services/auth-svc/internal/service"
	"go.uber.org/zap"
)

type HTTPHandler struct {
	log     *logging.Logger
	service *service.AuthService
	corsMap map[string]struct{}
}

func NewHTTPHandler(log *logging.Logger, svc *service.AuthService, corsOrigins []string) *HTTPHandler {
	m := make(map[string]struct{}, len(corsOrigins))
	for _, o := range corsOrigins {
		m[o] = struct{}{}
	}
	return &HTTPHandler{log: log, service: svc, corsMap: m}
}

func clientIP(r *http.Request) string {
	// Trust X-Real-IP only — nginx sets it from the Cloudflare tunnel hop.
	// Do not read CF-Connecting-IP / X-Forwarded-For here; clients can spoof them.
	if value := strings.TrimSpace(strings.Split(r.Header.Get("X-Real-IP"), ",")[0]); value != "" {
		return value
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func (h *HTTPHandler) verifyTurnstileIfRequired(w http.ResponseWriter, r *http.Request, token string) bool {
	// When Turnstile is configured, every register path must present a valid token.
	// Origin / X-Veritas-Client are telemetry only — they must not gate enforcement.
	if !h.service.TurnstileEnabled() {
		return true
	}

	origin := strings.TrimSpace(r.Header.Get("Origin"))
	client := strings.ToLower(strings.TrimSpace(r.Header.Get("X-Veritas-Client")))
	if err := h.service.VerifyTurnstile(r.Context(), token, clientIP(r)); err != nil {
		h.log.Warn("turnstile verification failed",
			zap.String("origin", origin),
			zap.String("client", client),
			zap.Error(err),
		)
		writeHTTPError(w, http.StatusBadRequest, "verification failed; please try again")
		return false
	}
	return true
}

func (h *HTTPHandler) RegisterRoutes(mux *http.ServeMux) {
	mux.HandleFunc("/healthz", h.handleHealth)
	mux.HandleFunc("/api/v1/auth/register", h.withCORS(h.handleRegister))
	mux.HandleFunc("/api/v1/auth/signin", h.withCORS(h.handleSignIn))
	mux.HandleFunc("/api/v1/auth/verify-email", h.withCORS(h.handleVerifyEmail))
	mux.HandleFunc("/api/v1/auth/resend-verification", h.withCORS(h.handleResendVerification))
	mux.HandleFunc("/api/v1/auth/refresh", h.withCORS(h.handleRefresh))
	mux.HandleFunc("/api/v1/auth/validate", h.withCORS(h.handleValidate))
	mux.HandleFunc("/api/v1/auth/me", h.withCORS(h.handleMe))
	mux.HandleFunc("/api/v1/auth/account", h.withCORS(h.handleDeleteAccount))
	mux.HandleFunc("/api/v1/auth/reset-password", h.withCORS(h.handleResetPassword))
	mux.HandleFunc("/api/v1/auth/complete-reset", h.withCORS(h.handleCompleteReset))
	mux.HandleFunc("/api/v1/auth/register-anonymous", h.withCORS(h.handleRegisterAnonymous))
	mux.HandleFunc("/api/v1/auth/signin-account", h.withCORS(h.handleSignInAccount))
	mux.HandleFunc("/api/v1/auth/download-account", h.withCORS(h.handleDownloadAccount))
	mux.HandleFunc("/api/v1/auth/logout-all", h.withCORS(h.handleLogoutAll))
}

func (h *HTTPHandler) handleHealth(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
}

func (h *HTTPHandler) withCORS(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		origin := r.Header.Get("Origin")
		if origin != "" {
			if _, ok := h.corsMap[origin]; ok {
				w.Header().Set("Access-Control-Allow-Origin", origin)
				w.Header().Set("Vary", "Origin")
				w.Header().Set("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Veritas-Client")
				w.Header().Set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
			}
		}
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next(w, r)
	}
}

func (h *HTTPHandler) handleRegister(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeHTTPError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	var req struct {
		Email          string `json:"email"`
		Password       string `json:"password"`
		TurnstileToken string `json:"turnstile_token"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeHTTPError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	if !h.verifyTurnstileIfRequired(w, r, req.TurnstileToken) {
		return
	}

	normalizedEmail := strings.ToLower(strings.TrimSpace(req.Email))
	if h.service.RateLimited(r.Context(), "email-register-ip:"+clientIP(r), 20, time.Hour) ||
		h.service.RateLimited(r.Context(), "email-register-address:"+normalizedEmail, 10, time.Hour) {
		writeHTTPError(w, http.StatusTooManyRequests, "too many registration attempts; wait a few minutes and try again")
		return
	}

	emailAddr, err := h.service.RegisterPendingEmail(r.Context(), req.Email, req.Password)
	if err != nil {
		h.log.Warn("register failed", zap.Error(err))
		writeHTTPError(w, http.StatusBadRequest, err.Error())
		return
	}
	writeHTTPJSON(w, http.StatusCreated, map[string]interface{}{
		"verification_required": true, "email": emailAddr,
		"message": "Check your inbox to verify your email before signing in.",
	})
}

func (h *HTTPHandler) handleVerifyEmail(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeHTTPError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	var req struct {
		Token string `json:"token"`
	}
	if json.NewDecoder(r.Body).Decode(&req) != nil {
		writeHTTPError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	if err := h.service.VerifyEmail(r.Context(), req.Token); err != nil {
		writeHTTPError(w, http.StatusBadRequest, "invalid or expired verification link")
		return
	}
	writeHTTPJSON(w, http.StatusOK, map[string]interface{}{"verified": true})
}

func (h *HTTPHandler) handleResendVerification(w http.ResponseWriter, r *http.Request) {
	if h.service.RateLimited(r.Context(), "verification-resend:"+clientIP(r), 5, time.Hour) {
		writeHTTPError(w, http.StatusTooManyRequests, "too many resend attempts; try again later")
		return
	}
	if r.Method != http.MethodPost {
		writeHTTPError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	var req struct {
		Email string `json:"email"`
	}
	if json.NewDecoder(r.Body).Decode(&req) != nil {
		writeHTTPError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	_ = h.service.ResendVerification(r.Context(), req.Email)
	writeHTTPJSON(w, http.StatusOK, map[string]interface{}{"message": "If this address is awaiting verification, a new link has been sent."})
}

func (h *HTTPHandler) handleSignIn(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeHTTPError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	var req struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeHTTPError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	normalizedEmail := strings.ToLower(strings.TrimSpace(req.Email))
	if h.service.RateLimited(r.Context(), "email-signin-ip:"+clientIP(r), 10, time.Minute) ||
		h.service.RateLimited(r.Context(), "email-signin-address:"+normalizedEmail, 10, time.Minute) {
		writeHTTPError(w, http.StatusTooManyRequests, "too many sign-in attempts; try again later")
		return
	}

	accessToken, refreshToken, accountID, expiresAt, err := h.service.SignInWithEmail(r.Context(), req.Email, req.Password)
	if err != nil {
		h.log.Warn("sign in failed", zap.String("email_hash", logging.HashIdentifier(req.Email)), zap.Error(err))
		writeHTTPError(w, http.StatusUnauthorized, "incorrect email or password")
		return
	}

	writeHTTPJSON(w, http.StatusOK, map[string]interface{}{
		"access_token":  accessToken,
		"refresh_token": refreshToken,
		"account_id":    accountID,
		"expires_at":    expiresAt,
		"email":         req.Email,
	})
}

func (h *HTTPHandler) handleRefresh(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeHTTPError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	var req struct {
		RefreshToken string `json:"refresh_token"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeHTTPError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	accessToken, refreshToken, expiresAt, err := h.service.RefreshToken(r.Context(), req.RefreshToken)
	if err != nil {
		h.log.Warn("refresh failed", zap.Error(err))
		writeHTTPError(w, http.StatusUnauthorized, "invalid refresh token")
		return
	}

	writeHTTPJSON(w, http.StatusOK, map[string]interface{}{
		"access_token":  accessToken,
		"refresh_token": refreshToken,
		"expires_at":    expiresAt,
	})
}

func (h *HTTPHandler) handleValidate(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeHTTPError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	token := extractBearer(r)
	if token == "" {
		writeHTTPJSON(w, http.StatusOK, map[string]interface{}{"valid": false})
		return
	}

	claims, err := h.service.ValidateToken(r.Context(), token)
	if err != nil {
		writeHTTPJSON(w, http.StatusOK, map[string]interface{}{"valid": false})
		return
	}

	writeHTTPJSON(w, http.StatusOK, map[string]interface{}{
		"valid":      true,
		"account_id": claims.AccountID,
		"tier":       claims.Tier,
	})
}

func (h *HTTPHandler) handleMe(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeHTTPError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	token := extractBearer(r)
	if token == "" {
		writeHTTPError(w, http.StatusUnauthorized, "missing authorization token")
		return
	}

	claims, err := h.service.ValidateToken(r.Context(), token)
	if err != nil {
		writeHTTPError(w, http.StatusUnauthorized, "invalid token")
		return
	}

	acc, err := h.service.GetAccount(r.Context(), claims.AccountID)
	if err != nil {
		writeHTTPError(w, http.StatusNotFound, "account not found")
		return
	}

	writeHTTPJSON(w, http.StatusOK, map[string]interface{}{
		"account_id":     acc.ID,
		"email":          acc.Email,
		"email_verified": acc.Email == nil || acc.EmailVerifiedAt != nil,
		"tier":           acc.SubscriptionTier,
		"status":         acc.AccountStatus,
		"created_at":     acc.CreatedAt.Unix(),
	})
}

func (h *HTTPHandler) handleDeleteAccount(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodDelete {
		writeHTTPError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	token := extractBearer(r)
	if token == "" {
		writeHTTPError(w, http.StatusUnauthorized, "missing authorization token")
		return
	}

	claims, err := h.service.ValidateToken(r.Context(), token)
	if err != nil {
		writeHTTPError(w, http.StatusUnauthorized, "invalid token")
		return
	}

	if err := h.service.DeleteAccount(r.Context(), claims.AccountID); err != nil {
		h.log.Error("delete account failed", zap.String("account_hash", logging.HashIdentifier(claims.AccountID)), zap.Error(err))
		writeHTTPError(w, http.StatusInternalServerError, "failed to delete account")
		return
	}

	w.WriteHeader(http.StatusNoContent)
}

func (h *HTTPHandler) handleResetPassword(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeHTTPError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	var req struct {
		Email string `json:"email"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeHTTPError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	req.Email = strings.ToLower(strings.TrimSpace(req.Email))
	if h.service.RateLimited(r.Context(), "password-reset-ip:"+clientIP(r), 1, 30*time.Second) ||
		h.service.RateLimited(r.Context(), "password-reset-email:"+req.Email, 1, 30*time.Second) {
		writeHTTPError(w, http.StatusTooManyRequests, "please wait 30 seconds before requesting another reset email")
		return
	}

	if err := h.service.RequestPasswordReset(r.Context(), req.Email); err != nil {
		h.log.Warn("password reset request failed", zap.Error(err))
	}

	writeHTTPJSON(w, http.StatusOK, map[string]interface{}{
		"message": "If an account exists with this email, a reset link has been sent.",
	})
}

func (h *HTTPHandler) handleCompleteReset(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeHTTPError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	var req struct {
		Token       string `json:"token"`
		NewPassword string `json:"new_password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeHTTPError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	if err := h.service.ResetPassword(r.Context(), req.Token, req.NewPassword); err != nil {
		writeHTTPError(w, http.StatusBadRequest, err.Error())
		return
	}

	writeHTTPJSON(w, http.StatusOK, map[string]interface{}{"success": true})
}

func (h *HTTPHandler) handleRegisterAnonymous(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeHTTPError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	var req struct {
		TurnstileToken string `json:"turnstile_token"`
	}
	_ = json.NewDecoder(r.Body).Decode(&req)

	if !h.verifyTurnstileIfRequired(w, r, req.TurnstileToken) {
		return
	}

	if h.service.RateLimited(r.Context(), "anonymous-register:"+clientIP(r), 20, time.Hour) {
		writeHTTPError(w, http.StatusTooManyRequests, "too many registration attempts; try again later")
		return
	}

	accessToken, refreshToken, accountID, expiresAt, err := h.service.RegisterAnonymous(r.Context())
	if err != nil {
		h.log.Error("anonymous register failed", zap.Error(err))
		writeHTTPError(w, http.StatusInternalServerError, "failed to create account")
		return
	}

	writeHTTPJSON(w, http.StatusCreated, map[string]interface{}{
		"access_token":  accessToken,
		"refresh_token": refreshToken,
		"account_id":    accountID,
		"expires_at":    expiresAt,
	})
}

func (h *HTTPHandler) handleDownloadAccount(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeHTTPError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	// Prefer Authorization header. Query ?token= is accepted temporarily for
	// older clients but must not be the primary path (leaks into logs/Referer).
	token := extractBearer(r)
	if token == "" {
		if q := strings.TrimSpace(r.URL.Query().Get("token")); q != "" {
			h.log.Warn("download-account used deprecated query token")
			token = q
		}
	}
	if token == "" {
		writeHTTPError(w, http.StatusUnauthorized, "missing authorization token")
		return
	}

	claims, err := h.service.ValidateToken(r.Context(), token)
	if err != nil {
		writeHTTPError(w, http.StatusUnauthorized, "invalid token")
		return
	}

	content := fmt.Sprintf("VeritasVPN Account ID\n\n%s\n\nSave this file — it is the only way to recover your account.\n", claims.AccountID)

	w.Header().Set("Content-Type", "text/plain")
	w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="veritasvpn-account.txt"`))
	w.Header().Set("Content-Length", fmt.Sprintf("%d", len(content)))
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(content))
}

func (h *HTTPHandler) handleLogoutAll(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeHTTPError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	if h.service.RateLimited(r.Context(), "logout-all:"+clientIP(r), 5, time.Minute) {
		writeHTTPError(w, http.StatusTooManyRequests, "too many logout attempts; try again later")
		return
	}

	token := extractBearer(r)
	if token == "" {
		writeHTTPError(w, http.StatusUnauthorized, "missing authorization token")
		return
	}

	claims, err := h.service.ValidateToken(r.Context(), token)
	if err != nil {
		writeHTTPError(w, http.StatusUnauthorized, "invalid token")
		return
	}

	if err := h.service.LogoutAllSessions(r.Context(), claims.AccountID, token); err != nil {
		h.log.Error("logout-all failed", zap.String("account_hash", logging.HashIdentifier(claims.AccountID)), zap.Error(err))
		writeHTTPError(w, http.StatusInternalServerError, "failed to logout all sessions")
		return
	}

	writeHTTPJSON(w, http.StatusOK, map[string]interface{}{"ok": true})
}

func (h *HTTPHandler) handleSignInAccount(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeHTTPError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	if h.service.RateLimited(r.Context(), "signin-account-ip:"+clientIP(r), 10, time.Minute) {
		writeHTTPError(w, http.StatusTooManyRequests, "too many sign-in attempts; try again later")
		return
	}

	var req struct {
		AccountID string `json:"account_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeHTTPError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	accessToken, refreshToken, accountID, expiresAt, err := h.service.SignInWithAccountID(r.Context(), req.AccountID)
	if err != nil {
		h.log.Warn("account sign in failed", zap.Error(err))
		writeHTTPError(w, http.StatusUnauthorized, "invalid account ID")
		return
	}

	writeHTTPJSON(w, http.StatusOK, map[string]interface{}{
		"access_token":  accessToken,
		"refresh_token": refreshToken,
		"account_id":    accountID,
		"expires_at":    expiresAt,
	})
}

func extractBearer(r *http.Request) string {
	auth := r.Header.Get("Authorization")
	if !strings.HasPrefix(auth, "Bearer ") {
		return ""
	}
	return strings.TrimSpace(strings.TrimPrefix(auth, "Bearer "))
}

func writeHTTPJSON(w http.ResponseWriter, status int, data interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(data)
}

func writeHTTPError(w http.ResponseWriter, status int, message string) {
	writeHTTPJSON(w, status, map[string]string{"error": message})
}
