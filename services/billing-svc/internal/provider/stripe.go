package provider

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/veritasvpn/lib/logging"
	"go.uber.org/zap"
)

type StripeProvider struct {
	log           *logging.Logger
	secretKey     string
	webhookSecret string
	httpClient    *http.Client
}

func NewStripeProvider(log *logging.Logger, secretKey, webhookSecret string) *StripeProvider {
	return &StripeProvider{
		log:           log,
		secretKey:     secretKey,
		webhookSecret: webhookSecret,
		httpClient:    &http.Client{Timeout: 30 * time.Second},
	}
}

type CheckoutSessionResponse struct {
	ID  string `json:"id"`
	URL string `json:"url"`
}

func (s *StripeProvider) CreateCheckoutSession(accountID, tier string) (string, string, error) {
	tierPriceID := getTierPriceID(tier)
	if tierPriceID == "" {
		return "", "", fmt.Errorf("unknown tier: %s", tier)
	}

	form := url.Values{}
	form.Set("mode", "subscription")
	form.Set("success_url", "https://veritasvpn.com/success?session_id={CHECKOUT_SESSION_ID}")
	form.Set("cancel_url", "https://veritasvpn.com/cancel")
	form.Add("line_items[0][price]", tierPriceID)
	form.Add("line_items[0][quantity]", "1")
	form.Set("client_reference_id", accountID)
	form.Set("metadata[account_id]", accountID)

	req, err := http.NewRequest(
		"POST",
		"https://api.stripe.com/v1/checkout/sessions",
		strings.NewReader(form.Encode()),
	)
	if err != nil {
		return "", "", fmt.Errorf("create stripe request: %w", err)
	}

	req.SetBasicAuth(s.secretKey, "")
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	resp, err := s.httpClient.Do(req)
	if err != nil {
		return "", "", fmt.Errorf("stripe api call: %w", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", "", fmt.Errorf("read stripe response: %w", err)
	}

	if resp.StatusCode >= 400 {
		s.log.Error("stripe api error",
			zap.Int("status", resp.StatusCode),
			zap.String("body", string(body)),
		)
		return "", "", fmt.Errorf("stripe api error: %d %s", resp.StatusCode, string(body))
	}

	var session CheckoutSessionResponse
	if err := json.Unmarshal(body, &session); err != nil {
		return "", "", fmt.Errorf("unmarshal stripe response: %w", err)
	}

	return session.URL, session.ID, nil
}

func (s *StripeProvider) HandleWebhook(payload []byte, signature string) error {
	if !s.verifySignature(payload, signature) {
		return fmt.Errorf("invalid stripe webhook signature")
	}

	var event struct {
		Type string          `json:"type"`
		Data json.RawMessage `json:"data"`
	}
	if err := json.Unmarshal(payload, &event); err != nil {
		return fmt.Errorf("unmarshal stripe event: %w", err)
	}

	s.log.Info("stripe webhook received", zap.String("type", event.Type))

	switch event.Type {
	case "checkout.session.completed":
		return s.handleCheckoutCompleted(event.Data)
	case "customer.subscription.deleted":
		return s.handleSubscriptionDeleted(event.Data)
	case "invoice.payment_failed":
		return s.handleInvoicePaymentFailed(event.Data)
	default:
		s.log.Debug("unhandled stripe event type", zap.String("type", event.Type))
		return nil
	}
}

func (s *StripeProvider) verifySignature(payload []byte, signature string) bool {
	if s.webhookSecret == "" {
		return true
	}

	parts := strings.Split(signature, ",")
	var timestamp string
	var sigs []string
	for _, part := range parts {
		part = strings.TrimSpace(part)
		if strings.HasPrefix(part, "t=") {
			timestamp = part[2:]
		} else if strings.HasPrefix(part, "v1=") {
			sigs = append(sigs, part[3:])
		}
	}

	if timestamp == "" || len(sigs) == 0 {
		return false
	}

	signedPayload := fmt.Sprintf("%s.%s", timestamp, string(payload))
	mac := hmac.New(sha256.New, []byte(s.webhookSecret))
	mac.Write([]byte(signedPayload))
	expected := hex.EncodeToString(mac.Sum(nil))

	for _, sig := range sigs {
		if hmac.Equal([]byte(expected), []byte(sig)) {
			return true
		}
	}
	return false
}

func (s *StripeProvider) handleCheckoutCompleted(data json.RawMessage) error {
	var wrapper struct {
		Object struct {
			ID                string `json:"id"`
			ClientReferenceID string `json:"client_reference_id"`
			Subscription      string `json:"subscription"`
		} `json:"object"`
	}
	if err := json.Unmarshal(data, &wrapper); err != nil {
		return fmt.Errorf("unmarshal checkout data: %w", err)
	}

	s.log.Info("checkout session completed",
		zap.String("session_id", wrapper.Object.ID),
		zap.String("account_id", wrapper.Object.ClientReferenceID),
		zap.String("stripe_subscription_id", wrapper.Object.Subscription),
	)

	return nil
}

func (s *StripeProvider) handleSubscriptionDeleted(data json.RawMessage) error {
	var wrapper struct {
		Object struct {
			ID     string `json:"id"`
			Status string `json:"status"`
		} `json:"object"`
	}
	if err := json.Unmarshal(data, &wrapper); err != nil {
		return fmt.Errorf("unmarshal subscription deleted data: %w", err)
	}

	s.log.Info("subscription deleted",
		zap.String("stripe_subscription_id", wrapper.Object.ID),
		zap.String("status", wrapper.Object.Status),
	)

	return nil
}

func (s *StripeProvider) handleInvoicePaymentFailed(data json.RawMessage) error {
	var wrapper struct {
		Object struct {
			ID            string `json:"id"`
			Subscription  string `json:"subscription"`
			BillingReason string `json:"billing_reason"`
			AmountPaid    int64  `json:"amount_paid"`
			AmountDue     int64  `json:"amount_due"`
		} `json:"object"`
	}
	if err := json.Unmarshal(data, &wrapper); err != nil {
		return fmt.Errorf("unmarshal invoice data: %w", err)
	}

	s.log.Warn("invoice payment failed",
		zap.String("invoice_id", wrapper.Object.ID),
		zap.String("stripe_subscription_id", wrapper.Object.Subscription),
		zap.String("billing_reason", wrapper.Object.BillingReason),
		zap.Int64("amount_due", wrapper.Object.AmountDue),
	)

	return nil
}

func getTierPriceID(tier string) string {
	switch strings.ToLower(tier) {
	case "premium":
		return "price_premium_monthly"
	case "free":
		return "price_free"
	default:
		return ""
	}
}
