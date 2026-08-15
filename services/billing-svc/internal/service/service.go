package service

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/nats-io/nats.go"
	"github.com/veritasvpn/lib/logging"
	"github.com/veritasvpn/services/billing-svc/internal/model"
	"github.com/veritasvpn/services/billing-svc/internal/provider"
	"github.com/veritasvpn/services/billing-svc/internal/repository"
	"go.uber.org/zap"
)

type BillingConfig struct {
	PremiumPriceUSDCents int64
	PremiumPeriodDays    int
	BitcoinReadinessURL  string
}

type BillingService struct {
	log      *logging.Logger
	db       *repository.Postgres
	natsConn *nats.Conn
	invoices provider.InvoiceCreator
	btcpay   *provider.BTCPayProvider // nil when mock-only
	mock     *provider.MockBTCPayProvider
	cfg      BillingConfig
	http     *http.Client
}

func New(
	log *logging.Logger,
	db *repository.Postgres,
	natsConn *nats.Conn,
	invoices provider.InvoiceCreator,
	btcpay *provider.BTCPayProvider,
	mock *provider.MockBTCPayProvider,
	cfg BillingConfig,
) *BillingService {
	return &BillingService{
		log:      log,
		db:       db,
		natsConn: natsConn,
		invoices: invoices,
		btcpay:   btcpay,
		mock:     mock,
		cfg:      cfg,
		http:     &http.Client{Timeout: 3 * time.Second},
	}
}

var ErrBitcoinNotReady = errors.New("Bitcoin payments are temporarily unavailable while the node synchronizes")

func (s *BillingService) bitcoinReady(ctx context.Context) error {
	if s.cfg.BitcoinReadinessURL == "" {
		return nil
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, s.cfg.BitcoinReadinessURL, nil)
	if err != nil {
		return ErrBitcoinNotReady
	}
	resp, err := s.http.Do(req)
	if err != nil {
		return ErrBitcoinNotReady
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return ErrBitcoinNotReady
	}
	return nil
}

func (s *BillingService) Ready(ctx context.Context) error {
	if err := s.db.Ping(ctx); err != nil {
		return err
	}
	return s.bitcoinReady(ctx)
}

func (s *BillingService) PremiumAmountCents() int64 {
	if s.cfg.PremiumPriceUSDCents <= 0 {
		return 300
	}
	return s.cfg.PremiumPriceUSDCents
}

func (s *BillingService) periodDuration() time.Duration {
	days := s.cfg.PremiumPeriodDays
	if days <= 0 {
		days = 30
	}
	return time.Duration(days) * 24 * time.Hour
}

// EnsureFree creates an active free subscription if none exists.
func (s *BillingService) EnsureFree(ctx context.Context, accountID string) (*model.Subscription, error) {
	sub, err := s.db.GetSubscription(ctx, accountID)
	if err == nil {
		return sub, nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return nil, err
	}

	now := time.Now().UTC()
	sub = &model.Subscription{
		AccountID:          accountID,
		Tier:               model.TierFree,
		Status:             model.StatusActive,
		PaymentMethod:      model.PaymentNone,
		CurrentPeriodStart: now,
		CurrentPeriodEnd:   now.Add(100 * 365 * 24 * time.Hour),
		CancelAtPeriodEnd:  false,
	}
	if err := s.db.CreateSubscription(ctx, sub); err != nil {
		return nil, fmt.Errorf("create free subscription: %w", err)
	}
	return sub, nil
}

// GetStatus returns subscription status, ensuring a free row exists.
func (s *BillingService) GetStatus(ctx context.Context, accountID string) (*model.StatusResponse, error) {
	sub, err := s.EnsureFree(ctx, accountID)
	if err != nil {
		return nil, err
	}

	// Treat expired premium as free for API consumers.
	if sub.Tier == model.TierPremium && sub.Status == model.StatusActive && time.Now().UTC().After(sub.CurrentPeriodEnd) {
		if err := s.expireOne(ctx, sub); err != nil {
			s.log.Error("failed to expire premium during status", zap.Error(err))
		} else {
			sub, err = s.db.GetSubscription(ctx, accountID)
			if err != nil {
				return nil, err
			}
		}
	}

	start := sub.CurrentPeriodStart
	end := sub.CurrentPeriodEnd
	isPremium := sub.Tier == model.TierPremium && sub.Status == model.StatusActive && time.Now().UTC().Before(sub.CurrentPeriodEnd)

	return &model.StatusResponse{
		AccountID:          sub.AccountID,
		Tier:               sub.Tier,
		Status:             sub.Status,
		PaymentMethod:      sub.PaymentMethod,
		CurrentPeriodStart: &start,
		CurrentPeriodEnd:   &end,
		CancelAtPeriodEnd:  sub.CancelAtPeriodEnd,
		IsPremium:          isPremium,
	}, nil
}

// CreatePremiumCheckout starts a Bitcoin checkout for premium. Does NOT activate until paid.
func (s *BillingService) CreatePremiumCheckout(ctx context.Context, accountID, paymentMethod string) (checkoutURL string, err error) {
	if err := s.bitcoinReady(ctx); err != nil {
		return "", err
	}
	if accountID == "" {
		return "", fmt.Errorf("account_id is required")
	}

	existing, err := s.db.GetSubscription(ctx, accountID)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return "", err
	}
	if err == nil && existing.Tier == model.TierPremium && existing.Status == model.StatusActive && time.Now().UTC().Before(existing.CurrentPeriodEnd) && !existing.CancelAtPeriodEnd {
		return "", fmt.Errorf("already subscribed to premium until %s", existing.CurrentPeriodEnd.Format(time.RFC3339))
	}

	now := time.Now().UTC()
	sub := &model.Subscription{
		AccountID:          accountID,
		Tier:               model.TierPremium,
		Status:             model.StatusPending,
		PaymentMethod:      paymentMethod,
		CurrentPeriodStart: now,
		CurrentPeriodEnd:   now, // activated on settle
		CancelAtPeriodEnd:  false,
	}
	// Keep existing active free/premium period visible until paid: if they had free, stay free until settle.
	if existing != nil && existing.Tier == model.TierFree {
		sub.Tier = model.TierFree
		sub.Status = model.StatusActive
		sub.PaymentMethod = paymentMethod
		sub.CurrentPeriodStart = existing.CurrentPeriodStart
		sub.CurrentPeriodEnd = existing.CurrentPeriodEnd
	}

	if err := s.db.CreateSubscription(ctx, sub); err != nil {
		return "", fmt.Errorf("upsert subscription: %w", err)
	}

	// Reload to get ID
	sub, err = s.db.GetSubscription(ctx, accountID)
	if err != nil {
		return "", err
	}

	amountCents := s.PremiumAmountCents()
	amountUSD := float64(amountCents) / 100.0

	invoiceID, url, err := s.invoices.CreateInvoice(accountID, model.TierPremium, paymentMethod, amountUSD)
	if err != nil {
		return "", fmt.Errorf("create invoice: %w", err)
	}

	if err := s.db.CreatePaymentRecord(ctx, &model.PaymentRecord{
		SubscriptionID:        sub.ID,
		AccountID:             accountID,
		Amount:                amountCents,
		Currency:              "usd",
		Status:                model.PaymentPending,
		ProviderTransactionID: invoiceID,
	}); err != nil {
		return "", fmt.Errorf("create payment record: %w", err)
	}

	s.log.Info("premium checkout created",
		zap.String("account_hash", logging.HashIdentifier(accountID)),
		zap.String("invoice_id", invoiceID),
		zap.Int64("amount_cents", amountCents),
	)

	return url, nil
}

func (s *BillingService) CancelSubscription(ctx context.Context, accountID string) error {
	if err := s.db.CancelSubscription(ctx, accountID); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return fmt.Errorf("subscription not found")
		}
		return err
	}
	sub, _ := s.db.GetSubscription(ctx, accountID)
	tier := ""
	if sub != nil {
		tier = sub.Tier
	}
	s.publishEvent("subscription.canceled", map[string]interface{}{
		"account_id": accountID,
		"tier":       tier,
	})
	return nil
}

func (s *BillingService) ProcessBTCPayWebhook(ctx context.Context, payload []byte, signature string) error {
	if s.btcpay == nil {
		return fmt.Errorf("btcpay provider not configured")
	}
	event, err := s.btcpay.ParseWebhook(payload, signature)
	if err != nil {
		return err
	}

	s.log.Info("btcpay webhook",
		zap.String("type", event.Type),
		zap.String("invoice_id", event.InvoiceID),
	)

	switch event.Type {
	case "InvoiceSettled", "InvoiceReceivedPayment":
		return s.SettleInvoice(ctx, event.InvoiceID, event.AccountID)
	default:
		return nil
	}
}

// SettleInvoice activates or renews premium after payment confirmation.
func (s *BillingService) SettleInvoice(ctx context.Context, invoiceID, accountIDHint string) error {
	if invoiceID == "" {
		return fmt.Errorf("invoice_id required")
	}

	pr, err := s.db.GetPaymentByProviderTxn(ctx, invoiceID)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return fmt.Errorf("unknown invoice")
		}
		return err
	}
	if pr.Status == model.PaymentCompleted {
		return nil // idempotent
	}

	accountID := pr.AccountID
	if accountID == "" {
		accountID = accountIDHint
	}
	if accountID == "" {
		return fmt.Errorf("missing account_id for invoice")
	}

	sub, err := s.db.GetSubscription(ctx, accountID)
	if err != nil {
		return err
	}

	now := time.Now().UTC()
	periodStart := now
	if sub.Tier == model.TierPremium && sub.Status == model.StatusActive && sub.CurrentPeriodEnd.After(now) {
		periodStart = sub.CurrentPeriodEnd
	}
	periodEnd := periodStart.Add(s.periodDuration())

	paymentMethod := model.PaymentBTCPay
	sub.Tier = model.TierPremium
	sub.Status = model.StatusActive
	sub.PaymentMethod = paymentMethod
	sub.CurrentPeriodStart = periodStart
	sub.CurrentPeriodEnd = periodEnd
	sub.CancelAtPeriodEnd = false

	if err := s.db.UpdateSubscription(ctx, sub); err != nil {
		return fmt.Errorf("activate premium: %w", err)
	}
	if err := s.db.CompletePayment(ctx, invoiceID); err != nil {
		return fmt.Errorf("complete payment: %w", err)
	}

	s.publishEvent("subscription.renewed", map[string]interface{}{
		"tier":           model.TierPremium,
		"payment_method": paymentMethod,
	})

	s.log.Info("premium activated",
		zap.String("account_hash", logging.HashIdentifier(accountID)),
		zap.String("invoice_id", invoiceID),
		zap.Time("period_end", periodEnd),
	)
	return nil
}

// SettleMockInvoice is used by the local mock checkout page.
func (s *BillingService) SettleMockInvoice(ctx context.Context, invoiceID string) error {
	if s.mock == nil {
		return fmt.Errorf("mock payments disabled")
	}
	inv, err := s.mock.MarkSettled(invoiceID)
	if err != nil {
		return err
	}
	return s.SettleInvoice(ctx, invoiceID, inv.AccountID)
}

func (s *BillingService) GetMockInvoice(invoiceID string) (provider.MockInvoice, bool) {
	if s.mock == nil {
		return provider.MockInvoice{}, false
	}
	return s.mock.Get(invoiceID)
}

func (s *BillingService) ExpireDueSubscriptions(ctx context.Context) (int, error) {
	subs, err := s.db.ListExpiredPremium(ctx, time.Now().UTC())
	if err != nil {
		return 0, err
	}
	n := 0
	for _, sub := range subs {
		if err := s.expireOne(ctx, sub); err != nil {
			s.log.Error("expire failed", zap.String("account_hash", logging.HashIdentifier(sub.AccountID)), zap.Error(err))
			continue
		}
		n++
	}
	return n, nil
}

func (s *BillingService) expireOne(ctx context.Context, sub *model.Subscription) error {
	now := time.Now().UTC()
	sub.Tier = model.TierFree
	sub.Status = model.StatusActive
	sub.PaymentMethod = model.PaymentNone
	sub.CurrentPeriodStart = now
	sub.CurrentPeriodEnd = now.Add(100 * 365 * 24 * time.Hour)
	sub.CancelAtPeriodEnd = false
	if err := s.db.UpdateSubscription(ctx, sub); err != nil {
		return err
	}
	s.publishEvent("subscription.expired", map[string]interface{}{
		"account_id": sub.AccountID,
		"tier":       model.TierFree,
	})
	return nil
}

func (s *BillingService) publishEvent(subject string, payload map[string]interface{}) {
	if s.natsConn == nil {
		return
	}
	data, err := json.Marshal(payload)
	if err != nil {
		s.log.Error("failed to marshal event payload", zap.Error(err))
		return
	}
	if err := s.natsConn.Publish(subject, data); err != nil {
		s.log.Error("failed to publish NATS event", zap.String("subject", subject), zap.Error(err))
	}
}
