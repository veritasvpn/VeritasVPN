package handler

import (
	"net/http"
	"strings"
	"time"
)

const refreshCookieName = "veritas_rt"

func (h *HTTPHandler) wantsAuthCookies(r *http.Request) bool {
	origin := strings.TrimSpace(r.Header.Get("Origin"))
	if origin == "" {
		return false
	}
	if !h.originAllowedForCookies(origin) {
		return false
	}
	switch strings.ToLower(strings.TrimSpace(r.Header.Get("X-Veritas-Client"))) {
	case "android", "desktop", "chrome-extension":
		return false
	}
	return true
}

func (h *HTTPHandler) originAllowedForCookies(origin string) bool {
	if _, ok := h.corsMap[origin]; ok {
		return true
	}
	// Production website origins (nginx CORS map). auth-svc ConfigMap may lag.
	switch origin {
	case "https://veritasvpn.cloud", "https://www.veritasvpn.cloud",
		"http://localhost:8000", "http://127.0.0.1:8000":
		return true
	}
	return false
}

func (h *HTTPHandler) isWebClient(r *http.Request) bool {
	return strings.EqualFold(strings.TrimSpace(r.Header.Get("X-Veritas-Client")), "web")
}

func (h *HTTPHandler) setRefreshCookie(w http.ResponseWriter, r *http.Request, refreshToken string) {
	if !h.wantsAuthCookies(r) || strings.TrimSpace(refreshToken) == "" {
		return
	}
	maxAge := int(h.service.RefreshTokenTTL().Seconds())
	if maxAge <= 0 {
		maxAge = int((7 * 24 * time.Hour).Seconds())
	}
	http.SetCookie(w, &http.Cookie{
		Name:     refreshCookieName,
		Value:    refreshToken,
		Path:     "/api/v1/auth",
		Domain:   ".veritasvpn.cloud",
		MaxAge:   maxAge,
		HttpOnly: true,
		Secure:   true,
		SameSite: http.SameSiteLaxMode,
	})
}

func (h *HTTPHandler) clearRefreshCookie(w http.ResponseWriter, r *http.Request) {
	origin := strings.TrimSpace(r.Header.Get("Origin"))
	if origin != "" && !h.originAllowedForCookies(origin) {
		return
	}
	http.SetCookie(w, &http.Cookie{
		Name:     refreshCookieName,
		Value:    "",
		Path:     "/api/v1/auth",
		Domain:   ".veritasvpn.cloud",
		MaxAge:   -1,
		HttpOnly: true,
		Secure:   true,
		SameSite: http.SameSiteLaxMode,
	})
}

func refreshTokenFromRequest(r *http.Request, bodyToken string) string {
	if t := strings.TrimSpace(bodyToken); t != "" {
		return t
	}
	if c, err := r.Cookie(refreshCookieName); err == nil {
		return strings.TrimSpace(c.Value)
	}
	return ""
}

func (h *HTTPHandler) writeAuthTokens(w http.ResponseWriter, r *http.Request, status int, accessToken, refreshToken, accountID string, expiresAt int64, extra map[string]interface{}) {
	h.setRefreshCookie(w, r, refreshToken)
	payload := map[string]interface{}{
		"access_token": accessToken,
		"expires_at":   expiresAt,
	}
	if accountID != "" {
		payload["account_id"] = accountID
	}
	for k, v := range extra {
		payload[k] = v
	}
	// Web cookie path: do not put refresh in JSON (XSS cannot steal it).
	if !h.wantsAuthCookies(r) {
		payload["refresh_token"] = refreshToken
	}
	writeHTTPJSON(w, status, payload)
}
