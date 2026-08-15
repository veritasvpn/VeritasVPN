package handler

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"html/template"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/veritasvpn/lib/logging"
	"github.com/veritasvpn/services/billing-svc/internal/service"
	"github.com/veritasvpn/services/billing-svc/internal/tokenauth"
	"go.uber.org/zap"
)

type BillingHandler struct {
	log        *logging.Logger
	service    *service.BillingService
	tokenAuth  *tokenauth.Verifier
	corsAllow  map[string]struct{}
	successURL string
	enableMock bool
}

func NewBillingHandler(log *logging.Logger, svc *service.BillingService, verifier *tokenauth.Verifier, corsOrigins []string, successURL string, enableMock bool) *BillingHandler {
	allow := make(map[string]struct{}, len(corsOrigins))
	for _, o := range corsOrigins {
		allow[o] = struct{}{}
	}
	if successURL == "" {
		successURL = "https://veritasvpn.cloud/account/#/subscription"
	}
	return &BillingHandler{log: log, service: svc, tokenAuth: verifier, corsAllow: allow, successURL: successURL, enableMock: enableMock}
}

func (h *BillingHandler) RegisterRoutes(mux *http.ServeMux) {
	mux.HandleFunc("/healthz", h.handleHealth)
	mux.HandleFunc("/readyz", h.handleReady)
	mux.HandleFunc("/api/v1/billing/readyz", h.handleReady)
	mux.HandleFunc("/api/v1/billing/subscribe", h.withCORS(h.handleSubscribe))
	mux.HandleFunc("/api/v1/billing/cancel", h.withCORS(h.handleCancel))
	mux.HandleFunc("/api/v1/billing/status", h.withCORS(h.handleStatus))
	mux.HandleFunc("/api/v1/billing/webhook/btcpay", h.handleBTCPayWebhook)
	if h.enableMock {
		mux.HandleFunc("/api/v1/billing/mock-checkout", h.handleMockCheckout)
		mux.HandleFunc("/api/v1/billing/mock-settle", h.handleMockSettle)
	}
}

func (h *BillingHandler) handleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (h *BillingHandler) handleReady(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
	defer cancel()
	if err := h.service.Ready(ctx); err != nil {
		if errors.Is(err, service.ErrBitcoinNotReady) {
			writeError(w, http.StatusServiceUnavailable, err.Error())
			return
		}
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ready"})
}

func (h *BillingHandler) withCORS(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		origin := r.Header.Get("Origin")
		if origin != "" {
			if _, ok := h.corsAllow[origin]; ok {
				w.Header().Set("Access-Control-Allow-Origin", origin)
				w.Header().Set("Vary", "Origin")
				w.Header().Set("Access-Control-Allow-Headers", "Authorization, Content-Type")
				w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
			}
		}
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next(w, r)
	}
}

func (h *BillingHandler) requireUID(w http.ResponseWriter, r *http.Request) (string, bool) {
	auth := r.Header.Get("Authorization")
	if !strings.HasPrefix(auth, "Bearer ") {
		writeError(w, http.StatusUnauthorized, "missing bearer token")
		return "", false
	}
	token := strings.TrimSpace(strings.TrimPrefix(auth, "Bearer "))
	uid, _, err := h.tokenAuth.Verify(r.Context(), token)
	if err != nil {
		h.log.Warn("token auth failed", zap.Error(err))
		writeError(w, http.StatusUnauthorized, "invalid token")
		return "", false
	}
	return uid, true
}

func (h *BillingHandler) handleSubscribe(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	uid, ok := h.requireUID(w, r)
	if !ok {
		return
	}

	var req struct {
		Tier          string `json:"tier"`
		PaymentMethod string `json:"payment_method"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil && err != io.EOF {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	if req.Tier == "" {
		req.Tier = "premium"
	}
	if req.Tier != "premium" {
		writeError(w, http.StatusBadRequest, "only premium tier is supported for checkout")
		return
	}
	if req.PaymentMethod == "" {
		req.PaymentMethod = "btcpay"
	}
	if req.PaymentMethod != "btcpay" {
		writeError(w, http.StatusBadRequest, "only bitcoin (btcpay) payments are supported")
		return
	}

	checkoutURL, err := h.service.CreatePremiumCheckout(r.Context(), uid, req.PaymentMethod)
	if err != nil {
		h.log.Error("failed to create checkout", zap.Error(err))
		if errors.Is(err, service.ErrBitcoinNotReady) {
			writeError(w, http.StatusServiceUnavailable, err.Error())
			return
		}
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}

	writeJSON(w, http.StatusCreated, map[string]string{
		"checkout_url": checkoutURL,
	})
}

func (h *BillingHandler) handleCancel(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	uid, ok := h.requireUID(w, r)
	if !ok {
		return
	}
	if err := h.service.CancelSubscription(r.Context(), uid); err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"success": true})
}

func (h *BillingHandler) handleStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	uid, ok := h.requireUID(w, r)
	if !ok {
		return
	}
	status, err := h.service.GetStatus(r.Context(), uid)
	if err != nil {
		h.log.Error("failed to get status", zap.Error(err))
		writeError(w, http.StatusInternalServerError, "failed to load subscription")
		return
	}
	writeJSON(w, http.StatusOK, status)
}

func (h *BillingHandler) handleBTCPayWebhook(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	payload, err := io.ReadAll(r.Body)
	if err != nil {
		writeError(w, http.StatusBadRequest, "failed to read body")
		return
	}
	signature := r.Header.Get("BTCPay-Sig")
	if err := h.service.ProcessBTCPayWebhook(r.Context(), payload, signature); err != nil {
		h.log.Error("btcpay webhook failed", zap.Error(err))
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (h *BillingHandler) handleMockCheckout(w http.ResponseWriter, r *http.Request) {
	invoiceID := r.URL.Query().Get("invoice_id")
	inv, ok := h.service.GetMockInvoice(invoiceID)
	if !ok {
		http.Error(w, "invoice not found (is mock mode enabled?)", http.StatusNotFound)
		return
	}

	tmpl := template.Must(template.New("mock").Parse(mockCheckoutHTML))
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_ = tmpl.Execute(w, map[string]string{
		"InvoiceID": inv.ID,
		"AccountID": inv.AccountID,
		"Tier":      inv.Tier,
		"Amount":    fmt.Sprintf("%.2f", inv.AmountUSD),
		"SettleURL": "/api/v1/billing/mock-settle?invoice_id=" + inv.ID,
	})
}

func (h *BillingHandler) handleMockSettle(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost && r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	invoiceID := r.URL.Query().Get("invoice_id")
	if invoiceID == "" {
		writeError(w, http.StatusBadRequest, "invoice_id required")
		return
	}
	if err := h.service.SettleMockInvoice(r.Context(), invoiceID); err != nil {
		h.log.Error("mock settle failed", zap.Error(err))
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	http.Redirect(w, r, h.successURL, http.StatusSeeOther)
}

func writeJSON(w http.ResponseWriter, status int, data interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(data)
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{"error": message})
}

const mockCheckoutHTML = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Mock Bitcoin Checkout — VeritasVPN</title>
  <style>
    body { font-family: Inter, system-ui, sans-serif; background:#05070a; color:#fff; display:flex; min-height:100vh; align-items:center; justify-content:center; margin:0; }
    .card { background:#0b1018; border:1px solid #1a2332; border-radius:16px; padding:32px; max-width:420px; width:90%; }
    h1 { font-size:22px; margin:0 0 8px; }
    p { color:#9aa8bc; line-height:1.5; }
    .amount { font-size:40px; font-weight:800; margin:16px 0; background:linear-gradient(135deg,#00d2ff,#0066ff); -webkit-background-clip:text; -webkit-text-fill-color:transparent; }
    .meta { font-size:12px; color:#6b7a90; word-break:break-all; margin-bottom:20px; }
    button { width:100%; padding:14px; border:0; border-radius:10px; font-weight:700; cursor:pointer; background:linear-gradient(135deg,#00d2ff,#0066ff); color:#fff; font-size:15px; }
    .note { margin-top:16px; font-size:12px; color:#6b7a90; }
  </style>
</head>
<body>
  <div class="card">
    <h1>Mock Bitcoin payment</h1>
    <p>Local development stand-in for BTCPay. No real Bitcoin is sent.</p>
    <div class="amount">${{.Amount}} USD</div>
    <div class="meta">Invoice: {{.InvoiceID}}<br>Account: {{.AccountID}}<br>Tier: {{.Tier}}</div>
    <form method="POST" action="{{.SettleURL}}">
      <button type="submit">Simulate payment received</button>
    </form>
    <p class="note">Configure real BTCPay via BTCPAY_* env vars to disable mock mode.</p>
  </div>
</body>
</html>`
