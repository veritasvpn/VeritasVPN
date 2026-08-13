package provider

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/veritasvpn/lib/logging"
	"go.uber.org/zap"
)

type BTCPayProvider struct {
	log           *logging.Logger
	serverURL     string
	publicURL     string
	apiKey        string
	storeID       string
	webhookSecret string
	redirectURL   string
	httpClient    *http.Client
}

func NewBTCPayProvider(log *logging.Logger, serverURL, apiKey, storeID, webhookSecret, redirectURL string) *BTCPayProvider {
	return &BTCPayProvider{
		log:           log,
		serverURL:     strings.TrimRight(serverURL, "/"),
		apiKey:        apiKey,
		storeID:       storeID,
		webhookSecret: webhookSecret,
		redirectURL:   redirectURL,
		httpClient:    &http.Client{Timeout: 30 * time.Second},
	}
}

type BTCPayInvoiceRequest struct {
	Amount   string `json:"amount"`
	Currency string `json:"currency"`
	Metadata struct {
		AccountID string `json:"account_id"`
		Tier      string `json:"tier"`
	} `json:"metadata"`
	Checkout struct {
		RedirectURL          string   `json:"redirectURL"`
		PaymentMethods       []string `json:"paymentMethods,omitempty"`
		DefaultPaymentMethod string   `json:"defaultPaymentMethod,omitempty"`
	} `json:"checkout"`
}

type BTCPayInvoiceResponse struct {
	ID           string `json:"id"`
	CheckoutLink string `json:"checkoutLink"`
	Status       string `json:"status"`
}

// WebhookEvent is a normalized BTCPay webhook payload.
type WebhookEvent struct {
	Type      string
	InvoiceID string
	AccountID string
	Tier      string
}

func (b *BTCPayProvider) CreateInvoice(accountID, tier, paymentMethod string, amountUSD float64) (invoiceID, checkoutURL string, err error) {
	invReq := BTCPayInvoiceRequest{
		Amount:   fmt.Sprintf("%.2f", amountUSD),
		Currency: "USD",
	}
	invReq.Metadata.AccountID = accountID
	invReq.Metadata.Tier = tier
	invReq.Checkout.RedirectURL = b.redirectURL
	if paymentMethod == "btcpay_xmr" {
		invReq.Checkout.PaymentMethods = []string{"XMR-CHAIN"}
		invReq.Checkout.DefaultPaymentMethod = "XMR-CHAIN"
	} else {
		invReq.Checkout.PaymentMethods = []string{"BTC-CHAIN"}
		invReq.Checkout.DefaultPaymentMethod = "BTC-CHAIN"
	}

	body, err := json.Marshal(invReq)
	if err != nil {
		return "", "", fmt.Errorf("marshal invoice request: %w", err)
	}

	url := fmt.Sprintf("%s/api/v1/stores/%s/invoices", b.serverURL, b.storeID)
	req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return "", "", fmt.Errorf("create btcpay request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "token "+b.apiKey)

	resp, err := b.httpClient.Do(req)
	if err != nil {
		return "", "", fmt.Errorf("btcpay api call: %w", err)
	}
	defer resp.Body.Close()

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", "", fmt.Errorf("read btcpay response: %w", err)
	}
	if resp.StatusCode >= 400 {
		b.log.Error("btcpay api error",
			zap.Int("status", resp.StatusCode),
			zap.String("body", string(respBody)),
		)
		return "", "", fmt.Errorf("btcpay api error: %d %s", resp.StatusCode, string(respBody))
	}

	var invoice BTCPayInvoiceResponse
	if err := json.Unmarshal(respBody, &invoice); err != nil {
		return "", "", fmt.Errorf("unmarshal btcpay response: %w", err)
	}

	checkoutURL = invoice.CheckoutLink
	if b.publicURL != "" {
		checkoutURL = strings.Replace(invoice.CheckoutLink, b.serverURL, b.publicURL, 1)
	}
	return invoice.ID, checkoutURL, nil
}

func (b *BTCPayProvider) SetPublicURL(publicURL string) {
	b.publicURL = strings.TrimRight(publicURL, "/")
}

func (b *BTCPayProvider) ParseWebhook(payload []byte, signature string) (*WebhookEvent, error) {
	if err := b.verifySignature(payload, signature); err != nil {
		return nil, err
	}

	var raw struct {
		Type      string `json:"type"`
		InvoiceID string `json:"invoiceId"`
		Metadata  struct {
			AccountID string `json:"account_id"`
			Tier      string `json:"tier"`
		} `json:"metadata"`
	}
	if err := json.Unmarshal(payload, &raw); err != nil {
		return nil, fmt.Errorf("unmarshal btcpay event: %w", err)
	}

	return &WebhookEvent{
		Type:      raw.Type,
		InvoiceID: raw.InvoiceID,
		AccountID: raw.Metadata.AccountID,
		Tier:      raw.Metadata.Tier,
	}, nil
}

func (b *BTCPayProvider) verifySignature(payload []byte, signature string) error {
	if b.webhookSecret == "" {
		return fmt.Errorf("BTCPAY_WEBHOOK_SECRET is required for webhook verification")
	}
	if signature == "" {
		return fmt.Errorf("missing BTCPay-Sig header")
	}

	// BTCPay sends: sha256=<hex>
	sig := strings.TrimPrefix(signature, "sha256=")
	mac := hmac.New(sha256.New, []byte(b.webhookSecret))
	mac.Write(payload)
	expected := hex.EncodeToString(mac.Sum(nil))
	if !hmac.Equal([]byte(expected), []byte(sig)) {
		return fmt.Errorf("invalid btcpay webhook signature")
	}
	return nil
}
